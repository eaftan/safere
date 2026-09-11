// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Objects;

/**
 * Execution engine for deterministic multi-anchor chains (A₁ G₁ A₂ ... Aₖ).
 *
 * <p>Locates the first anchor and validates subsequent single-literal or character-class anchors
 * across empty or fixed character-class gaps. The descriptor eligibility check excludes shapes that
 * require gap backtracking, alternation retries, or end-anchor interpretation; those shapes remain
 * with the general linear engines.
 */
final class MultiAnchorExecutor {

  enum Status {
    /** A valid leftmost-first match was found. */
    MATCHED,
    /** No match is possible in the document (instant negative rejection). */
    MISMATCH,
    /** The descriptor is outside this executor's deterministic subset; fall back. */
    FALLBACK
  }

  record Result(Status status, int start, int end) {
    static final Result MISMATCH = new Result(Status.MISMATCH, -1, -1);
    static final Result FALLBACK = new Result(Status.FALLBACK, -1, -1);

    static Result matched(int start, int end) {
      return new Result(Status.MATCHED, start, end);
    }

    boolean isMatched() {
      return status == Status.MATCHED;
    }

    boolean isDefiniteMismatch() {
      return status == Status.MISMATCH;
    }

    boolean isFallback() {
      return status == Status.FALLBACK;
    }
  }

  static final int STATE_STRIDE = 8;
  private static final int OFFSET_CUR_POS = 0;
  private static final int OFFSET_SEARCH_POS = 1;
  private static final int OFFSET_MIN_HOP = 2;
  private static final int OFFSET_MAX_HOP = 3;
  private static final int OFFSET_P = 4;
  private static final int OFFSET_ANCHOR_LEN = 5;
  private static final int OFFSET_WATERMARK = 6;
  private static final int OFFSET_GUARD_END = 7;

  private static final int WORK_LIMIT_EXHAUSTED = Integer.MIN_VALUE;

  private MultiAnchorExecutor() {}

  /**
   * Executes multi-anchor matching on UTF-8 byte input.
   *
   * @param descriptor the multi-anchor descriptor containing the chain
   * @param scanner the UTF-8 input scanner
   * @param searchFrom the starting offset in the input
   * @return the execution result
   */
  static Result find(MultiAnchorDescriptor descriptor, Utf8InputScanner scanner, int searchFrom) {
    return find(descriptor, scanner, searchFrom, null, null, null, null);
  }

  static Result find(
      MultiAnchorDescriptor descriptor, Utf8InputScanner scanner, int searchFrom, int[] groups) {
    return find(descriptor, scanner, searchFrom, null, null, groups, null);
  }

  static Result find(
      MultiAnchorDescriptor descriptor,
      Utf8InputScanner scanner,
      int searchFrom,
      int[] groups,
      int[] anchorPositions) {
    return find(descriptor, scanner, searchFrom, null, null, groups, anchorPositions);
  }

  static Result find(
      MultiAnchorDescriptor descriptor,
      Utf8InputScanner scanner,
      int searchFrom,
      int[] scratch,
      long[] workHolder) {
    return find(descriptor, scanner, searchFrom, scratch, workHolder, null, null);
  }

  static Result find(
      MultiAnchorDescriptor descriptor,
      Utf8InputScanner scanner,
      int searchFrom,
      int[] scratch,
      long[] workHolder,
      int[] groups) {
    return find(descriptor, scanner, searchFrom, scratch, workHolder, groups, null);
  }

  static Result find(
      MultiAnchorDescriptor descriptor,
      Utf8InputScanner scanner,
      int searchFrom,
      int[] scratch,
      long[] workHolder,
      int[] groups,
      int[] anchorPositions) {
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(scanner, "scanner");

    if (!descriptor.isExecutableUtf8Chain()) {
      return Result.FALLBACK;
    }

    MultiAnchorDescriptor.Segment[] segments = descriptor.segments();
    int numSegments = segments.length;
    if (numSegments < 1) {
      return Result.FALLBACK;
    }

    int textLen = scanner.length();
    int minTotalLength = descriptor.minTotalLength();
    if (searchFrom + minTotalLength > textLen) {
      return Result.MISMATCH;
    }

    boolean trackCaptures = groups != null && descriptor.canExtractAllCaptures();
    if (!trackCaptures) {
      anchorPositions = null;
    } else if (anchorPositions == null || anchorPositions.length < numSegments * 2) {
      anchorPositions = new int[numSegments * 2];
    }

    if (descriptor.chain().isEndAnchored()) {
      return findEndAnchored(descriptor, scanner, searchFrom, groups, anchorPositions);
    }

    // Matcher already applies the compiled reject prefilter. Search the driver directly here;
    // execution verifies every anchor without a redundant full-input rejection pass.
    int driverIdx =
        descriptor.selectDriver(
            MultiAnchorDescriptor.InputDomain.UTF8, VectorScanProviders.teddyProviderAvailable());
    if (driverIdx < 0 || driverIdx >= numSegments) {
      driverIdx = 0;
    }

    int minUpstreamLen = 0;
    for (int i = 0; i < driverIdx; i++) {
      minUpstreamLen += segments[i].gap().minLength() + segments[i].anchor().minLength();
    }
    minUpstreamLen += segments[driverIdx].gap().minLength();

    long workLimit = WorkLimit.forRemaining(textLen - searchFrom);
    if (workHolder == null) {
      workHolder = new long[1];
    } else {
      workHolder[0] = 0;
    }

    if (scratch == null || scratch.length < numSegments * STATE_STRIDE) {
      scratch = new int[numSegments * STATE_STRIDE];
    }
    for (int s = 0; s < numSegments; s++) {
      int base = s * STATE_STRIDE;
      scratch[base + OFFSET_WATERMARK] = searchFrom;
      scratch[base + OFFSET_GUARD_END] = -1;
    }

    int minReverseWatermark = Math.max(0, searchFrom);
    int candidatePos = Math.max(0, searchFrom);
    MultiAnchorDescriptor.Segment driverSeg = segments[driverIdx];
    MultiAnchorDescriptor.Anchor driverAnchor = driverSeg.anchor();
    MultiAnchorDescriptor.Gap leadingGap = segments[0].gap();
    MultiAnchorDescriptor.Gap trailingGap = descriptor.trailingGap();
    boolean isStartAnchored =
        descriptor.chain().isStartAnchored()
            || leadingGap.kind() == MultiAnchorDescriptor.GapKind.TEXT_START;

    if (isStartAnchored
        && (searchFrom > 0
            || (candidatePos > 0
                && leadingGap.kind() == MultiAnchorDescriptor.GapKind.TEXT_START))) {
      return Result.MISMATCH;
    }

    boolean hasLeadingAnyStar = leadingGap.kind() == MultiAnchorDescriptor.GapKind.ANY_STAR;

    while (candidatePos <= textLen - minTotalLength) {
      // Phase 1: Locate candidate for Driver Anchor
      int pDriver = driverAnchor.findNext(scanner, candidatePos + minUpstreamLen);
      if (pDriver < 0) {
        return Result.MISMATCH;
      }
      if (isStartAnchored
          && driverIdx == 0
          && pDriver > 0
          && leadingGap.kind() == MultiAnchorDescriptor.GapKind.TEXT_START) {
        return Result.MISMATCH;
      }

      int matchStart;
      int currentPos;

      if (driverIdx == 0) {
        matchStart = leadingGap.expandLeading(scanner, pDriver, candidatePos);
        if (matchStart < 0) {
          candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
          continue;
        }

        int dLen = driverAnchor.lengthAt(scanner, pDriver);
        if (dLen <= 0) {
          candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
          continue;
        }
        if (anchorPositions != null) {
          anchorPositions[0] = pDriver;
          anchorPositions[1] = dLen;
        }
        currentPos = pDriver + dLen;
      } else {
        // Upstream reverse verification for A_{driverIdx-1} down to A_0
        int p0 =
            verifyUpstream(
                scanner, segments, driverIdx - 1, pDriver, minReverseWatermark, anchorPositions);
        if (p0 < 0) {
          candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
          workHolder[0]++;
          if (WorkLimit.isExhausted(workHolder[0], workLimit)) {
            return Result.FALLBACK;
          }
          continue;
        }

        // Verify leading gap before A_0
        int resolvedStart;
        if (hasLeadingAnyStar) {
          resolvedStart = Math.max(0, searchFrom);
        } else if (leadingGap.kind() == MultiAnchorDescriptor.GapKind.EMPTY) {
          resolvedStart = p0;
        } else {
          resolvedStart = leadingGap.expandLeading(scanner, p0, minReverseWatermark);
          if (resolvedStart < 0) {
            candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
            continue;
          }
        }

        int driverLen = driverAnchor.lengthAt(scanner, pDriver);
        if (driverLen <= 0) {
          candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
          continue;
        }
        if (anchorPositions != null) {
          anchorPositions[driverIdx * 2] = pDriver;
          anchorPositions[driverIdx * 2 + 1] = driverLen;
        }

        matchStart = resolvedStart;
        currentPos = pDriver + driverLen;
      }

      // Phase 2 & 3: Downstream verification for A_{driverIdx+1} ... A_{numSegments-1} and trailing
      // gap
      if (driverIdx < numSegments - 1) {
        int seg1Base = (driverIdx + 1) * STATE_STRIDE;
        MultiAnchorDescriptor.Segment seg1 = segments[driverIdx + 1];
        MultiAnchorDescriptor.Gap gap1 = seg1.gap();
        boolean reluctantGuarded = gap1.isExecutorGuardedGap() && !gap1.isGreedy();
        int maxScan;
        int firstSegmentGuardEnd = scratch[seg1Base + OFFSET_GUARD_END];
        if (gap1.isExecutorGuardedGap()
            && gap1.maxLength() == Integer.MAX_VALUE
            && firstSegmentGuardEnd >= currentPos) {
          maxScan = firstSegmentGuardEnd;
        } else {
          maxScan =
              reluctantGuarded
                  ? gap1.guardedSearchEnd(scanner, currentPos, textLen)
                  : gap1.scanClassEnd(scanner, currentPos, textLen);
          if (gapScansText(gap1, reluctantGuarded)) {
            long scanWork = Math.max(1, maxScan - currentPos);
            workHolder[0] += scanWork;
            if (WorkCounterConfig.ENABLED) {
              WorkCounter.record(scanWork);
            }
            if (WorkLimit.isExhausted(workHolder[0], workLimit)) {
              return Result.FALLBACK;
            }
          }
          if (gap1.isExecutorGuardedGap() && gap1.maxLength() == Integer.MAX_VALUE) {
            scratch[seg1Base + OFFSET_GUARD_END] = maxScan;
          }
        }
        int maxHop = Math.min(textLen - seg1.anchor().minLength(), maxScan);
        int firstSegmentWatermark = scratch[seg1Base + OFFSET_WATERMARK];
        if (currentPos + seg1.gap().minLength() > maxHop || firstSegmentWatermark >= maxHop) {
          if (driverIdx == 0
              && seg1.gap().isExecutorGuardedGap()
              && seg1.gap().isGreedy()
              && firstSegmentWatermark > pDriver) {
            candidatePos =
                Math.max(candidatePos + 1, firstSegmentWatermark - driverAnchor.minLength() + 1);
          } else {
            candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
          }
          workHolder[0]++;
          if (WorkLimit.isExhausted(workHolder[0], workLimit)) {
            return Result.FALLBACK;
          }
          continue;
        }
      }

      int matchEnd =
          matchDownstreamIterative(
              scanner,
              segments,
              driverIdx + 1,
              currentPos,
              textLen,
              trailingGap,
              scratch,
              workHolder,
              workLimit);
      if (matchEnd == WORK_LIMIT_EXHAUSTED) {
        return Result.FALLBACK;
      }
      if (matchEnd < 0) {
        int seg1Base = (driverIdx + 1) * STATE_STRIDE;
        int failedWatermark = scratch[seg1Base + OFFSET_WATERMARK];
        if (driverIdx == 0
            && segments.length > 1
            && segments[1].gap().isExecutorGuardedGap()
            && segments[1].gap().isGreedy()
            && failedWatermark > pDriver) {
          candidatePos = Math.max(candidatePos + 1, failedWatermark - driverAnchor.minLength() + 1);
        } else {
          candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
        }
        workHolder[0]++;
        if (WorkLimit.isExhausted(workHolder[0], workLimit)) {
          return Result.FALLBACK;
        }
        continue;
      }

      if (anchorPositions != null) {
        for (int i = driverIdx + 1; i < numSegments; i++) {
          int base = i * STATE_STRIDE;
          anchorPositions[i * 2] = scratch[base + OFFSET_P];
          anchorPositions[i * 2 + 1] = scratch[base + OFFSET_ANCHOR_LEN];
        }
        extractCaptures(descriptor, groups, matchStart, matchEnd, anchorPositions);
      }
      return Result.matched(matchStart, matchEnd);
    }

    return Result.MISMATCH;
  }

  /**
   * Executes multi-anchor matching on Java String input.
   *
   * @param descriptor the multi-anchor descriptor containing the chain
   * @param text the input string
   * @param searchFrom the starting character index
   * @return the execution result
   */
  static Result find(MultiAnchorDescriptor descriptor, String text, int searchFrom) {
    return find(descriptor, text, searchFrom, null, null, null, null);
  }

  static Result find(MultiAnchorDescriptor descriptor, String text, int searchFrom, int[] groups) {
    return find(descriptor, text, searchFrom, null, null, groups, null);
  }

  static Result find(
      MultiAnchorDescriptor descriptor,
      String text,
      int searchFrom,
      int[] groups,
      int[] anchorPositions) {
    return find(descriptor, text, searchFrom, null, null, groups, anchorPositions);
  }

  static Result find(
      MultiAnchorDescriptor descriptor,
      String text,
      int searchFrom,
      int[] scratch,
      long[] workHolder) {
    return find(descriptor, text, searchFrom, scratch, workHolder, null, null);
  }

  static Result find(
      MultiAnchorDescriptor descriptor,
      String text,
      int searchFrom,
      int[] scratch,
      long[] workHolder,
      int[] groups) {
    return find(descriptor, text, searchFrom, scratch, workHolder, groups, null);
  }

  static Result find(
      MultiAnchorDescriptor descriptor,
      String text,
      int searchFrom,
      int[] scratch,
      long[] workHolder,
      int[] groups,
      int[] anchorPositions) {
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(text, "text");

    if (!descriptor.isExecutableChain()) {
      return Result.FALLBACK;
    }

    MultiAnchorDescriptor.Segment[] segments = descriptor.segments();
    int numSegments = segments.length;
    if (numSegments < 1) {
      return Result.FALLBACK;
    }

    int textLen = text.length();
    int minTotalLength = descriptor.minTotalLength();
    if (searchFrom + minTotalLength > textLen) {
      return Result.MISMATCH;
    }

    boolean trackCaptures = groups != null && descriptor.canExtractAllCaptures();
    if (!trackCaptures) {
      anchorPositions = null;
    } else if (anchorPositions == null || anchorPositions.length < numSegments * 2) {
      anchorPositions = new int[numSegments * 2];
    }

    if (descriptor.chain().isEndAnchored()) {
      return findEndAnchored(descriptor, text, searchFrom, groups, anchorPositions);
    }

    // Matcher already applies the compiled reject prefilter. Search the driver directly here;
    // execution verifies every anchor without a redundant full-input rejection pass.
    int driverIdx = descriptor.selectDriver(MultiAnchorDescriptor.InputDomain.STRING, true);
    if (driverIdx < 0 || driverIdx >= numSegments) {
      driverIdx = 0;
    }

    int minUpstreamLen = 0;
    for (int i = 0; i < driverIdx; i++) {
      minUpstreamLen += segments[i].gap().minLength() + segments[i].anchor().minLength();
    }
    minUpstreamLen += segments[driverIdx].gap().minLength();

    long workLimit = WorkLimit.forRemaining(textLen - searchFrom);
    if (workHolder == null) {
      workHolder = new long[1];
    } else {
      workHolder[0] = 0;
    }

    if (scratch == null || scratch.length < numSegments * STATE_STRIDE) {
      scratch = new int[numSegments * STATE_STRIDE];
    }
    for (int s = 0; s < numSegments; s++) {
      int base = s * STATE_STRIDE;
      scratch[base + OFFSET_WATERMARK] = searchFrom;
      scratch[base + OFFSET_GUARD_END] = -1;
    }

    int minReverseWatermark = Math.max(0, searchFrom);
    int candidatePos = Math.max(0, searchFrom);
    MultiAnchorDescriptor.Segment driverSeg = segments[driverIdx];
    MultiAnchorDescriptor.Anchor driverAnchor = driverSeg.anchor();
    MultiAnchorDescriptor.Gap leadingGap = segments[0].gap();
    MultiAnchorDescriptor.Gap trailingGap = descriptor.trailingGap();
    boolean isStartAnchored =
        descriptor.chain().isStartAnchored()
            || leadingGap.kind() == MultiAnchorDescriptor.GapKind.TEXT_START;

    if (isStartAnchored
        && (searchFrom > 0
            || (candidatePos > 0
                && leadingGap.kind() == MultiAnchorDescriptor.GapKind.TEXT_START))) {
      return Result.MISMATCH;
    }

    boolean hasLeadingAnyStar = leadingGap.kind() == MultiAnchorDescriptor.GapKind.ANY_STAR;

    while (candidatePos <= textLen - minTotalLength) {
      // Phase 1: Locate candidate for Driver Anchor
      int pDriver = findNextCountingWork(driverAnchor, text, candidatePos + minUpstreamLen);
      if (pDriver < 0) {
        return Result.MISMATCH;
      }
      if (isStartAnchored
          && driverIdx == 0
          && pDriver > 0
          && leadingGap.kind() == MultiAnchorDescriptor.GapKind.TEXT_START) {
        return Result.MISMATCH;
      }

      int matchStart;
      int currentPos;

      if (driverIdx == 0) {
        matchStart = leadingGap.expandLeading(text, pDriver, candidatePos);
        if (matchStart < 0) {
          candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
          continue;
        }

        int dLen = driverAnchor.lengthAt(text, pDriver);
        if (dLen <= 0) {
          candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
          continue;
        }
        if (anchorPositions != null) {
          anchorPositions[0] = pDriver;
          anchorPositions[1] = dLen;
        }
        currentPos = pDriver + dLen;
      } else {
        // Upstream reverse verification for A_{driverIdx-1} down to A_0
        int p0 =
            verifyUpstream(
                text, segments, driverIdx - 1, pDriver, minReverseWatermark, anchorPositions);
        if (p0 < 0) {
          candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
          workHolder[0]++;
          if (WorkLimit.isExhausted(workHolder[0], workLimit)) {
            return Result.FALLBACK;
          }
          continue;
        }

        // Verify leading gap before A_0
        int resolvedStart;
        if (hasLeadingAnyStar) {
          resolvedStart = Math.max(0, searchFrom);
        } else if (leadingGap.kind() == MultiAnchorDescriptor.GapKind.EMPTY) {
          resolvedStart = p0;
        } else {
          resolvedStart = leadingGap.expandLeading(text, p0, minReverseWatermark);
          if (resolvedStart < 0) {
            candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
            continue;
          }
        }

        int driverLen = driverAnchor.lengthAt(text, pDriver);
        if (driverLen <= 0) {
          candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
          continue;
        }
        if (anchorPositions != null) {
          anchorPositions[driverIdx * 2] = pDriver;
          anchorPositions[driverIdx * 2 + 1] = driverLen;
        }

        matchStart = resolvedStart;
        currentPos = pDriver + driverLen;
      }

      // Phase 2 & 3: Downstream verification for A_{driverIdx+1} ... A_{numSegments-1} and trailing
      // gap
      if (driverIdx < numSegments - 1) {
        int seg1Base = (driverIdx + 1) * STATE_STRIDE;
        MultiAnchorDescriptor.Segment seg1 = segments[driverIdx + 1];
        MultiAnchorDescriptor.Gap gap1 = seg1.gap();
        boolean reluctantGuarded = gap1.isExecutorGuardedGap() && !gap1.isGreedy();
        int maxScan;
        int firstSegmentGuardEnd = scratch[seg1Base + OFFSET_GUARD_END];
        if (gap1.isExecutorGuardedGap()
            && gap1.maxLength() == Integer.MAX_VALUE
            && firstSegmentGuardEnd >= currentPos) {
          maxScan = firstSegmentGuardEnd;
        } else {
          maxScan =
              reluctantGuarded
                  ? gap1.guardedSearchEnd(text, currentPos, textLen)
                  : gap1.scanClassEnd(text, currentPos, textLen);
          if (gapScansText(gap1, reluctantGuarded)) {
            long scanWork = Math.max(1, maxScan - currentPos);
            workHolder[0] += scanWork;
            if (WorkCounterConfig.ENABLED) {
              WorkCounter.record(scanWork);
            }
            if (WorkLimit.isExhausted(workHolder[0], workLimit)) {
              return Result.FALLBACK;
            }
          }
          if (gap1.isExecutorGuardedGap() && gap1.maxLength() == Integer.MAX_VALUE) {
            scratch[seg1Base + OFFSET_GUARD_END] = maxScan;
          }
        }
        int maxHop = Math.min(textLen - seg1.anchor().minLength(), maxScan);
        int firstSegmentWatermark = scratch[seg1Base + OFFSET_WATERMARK];
        if (currentPos + seg1.gap().minLength() > maxHop || firstSegmentWatermark >= maxHop) {
          if (driverIdx == 0
              && seg1.gap().isExecutorGuardedGap()
              && seg1.gap().isGreedy()
              && firstSegmentWatermark > pDriver) {
            candidatePos =
                Math.max(candidatePos + 1, firstSegmentWatermark - driverAnchor.minLength() + 1);
          } else {
            candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
          }
          workHolder[0]++;
          if (WorkLimit.isExhausted(workHolder[0], workLimit)) {
            return Result.FALLBACK;
          }
          continue;
        }
      }

      int matchEnd =
          matchDownstreamIterative(
              text,
              segments,
              driverIdx + 1,
              currentPos,
              textLen,
              trailingGap,
              scratch,
              workHolder,
              workLimit);
      if (matchEnd == WORK_LIMIT_EXHAUSTED) {
        return Result.FALLBACK;
      }
      if (matchEnd < 0) {
        int seg1Base = (driverIdx + 1) * STATE_STRIDE;
        int failedWatermark = scratch[seg1Base + OFFSET_WATERMARK];
        if (driverIdx == 0
            && segments.length > 1
            && segments[1].gap().isExecutorGuardedGap()
            && segments[1].gap().isGreedy()
            && failedWatermark > pDriver) {
          candidatePos = Math.max(candidatePos + 1, failedWatermark - driverAnchor.minLength() + 1);
        } else {
          candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
        }
        workHolder[0]++;
        if (WorkLimit.isExhausted(workHolder[0], workLimit)) {
          return Result.FALLBACK;
        }
        continue;
      }

      if (anchorPositions != null) {
        for (int i = driverIdx + 1; i < numSegments; i++) {
          int base = i * STATE_STRIDE;
          anchorPositions[i * 2] = scratch[base + OFFSET_P];
          anchorPositions[i * 2 + 1] = scratch[base + OFFSET_ANCHOR_LEN];
        }
        extractCaptures(descriptor, groups, matchStart, matchEnd, anchorPositions);
      }
      return Result.matched(matchStart, matchEnd);
    }

    return Result.MISMATCH;
  }

  private static int matchDownstreamIterative(
      Utf8InputScanner scanner,
      MultiAnchorDescriptor.Segment[] segments,
      int startSeg,
      int startPos,
      int textLen,
      MultiAnchorDescriptor.Gap trailingGap,
      int[] state,
      long[] workHolder,
      long workLimit) {
    if (startSeg == segments.length) {
      return trailingGap.isExecutorFixedGap()
          ? trailingGap.matchExecutorFixedForward(scanner, startPos, textLen)
          : trailingGap.expandTrailing(scanner, startPos, textLen);
    }

    int s = startSeg;
    state[s * STATE_STRIDE + OFFSET_CUR_POS] = startPos;
    boolean backtrack = false;

    while (s >= startSeg) {
      int base = s * STATE_STRIDE;
      MultiAnchorDescriptor.Segment seg = segments[s];
      MultiAnchorDescriptor.Gap gap = seg.gap();
      MultiAnchorDescriptor.Anchor anchor = seg.anchor();
      int currentPos = state[base + OFFSET_CUR_POS];

      if (gap.isExecutorFixedGap()) {
        if (backtrack) {
          s--;
          continue;
        }
        int p = gap.matchExecutorFixedForward(scanner, currentPos, textLen);
        if (p < 0 || !anchor.startsWith(scanner, p)) {
          backtrack = true;
          s--;
          continue;
        }
        int anchorLen = anchor.lengthAt(scanner, p);
        if (anchorLen <= 0) {
          backtrack = true;
          s--;
          continue;
        }
        state[base + OFFSET_P] = p;
        state[base + OFFSET_ANCHOR_LEN] = anchorLen;

        if (s == segments.length - 1) {
          int matchEnd =
              trailingGap.isExecutorFixedGap()
                  ? trailingGap.matchExecutorFixedForward(scanner, p + anchorLen, textLen)
                  : trailingGap.expandTrailing(scanner, p + anchorLen, textLen);
          if (matchEnd >= 0) {
            return matchEnd;
          }
          backtrack = true;
          s--;
          continue;
        }

        s++;
        state[s * STATE_STRIDE + OFFSET_CUR_POS] = p + anchorLen;
        backtrack = false;
        continue;
      }

      boolean reluctantGuarded = gap.isExecutorGuardedGap() && !gap.isGreedy();

      if (!backtrack) {
        int minHop = currentPos + gap.minLength();
        if (minHop > textLen) {
          backtrack = true;
          s--;
          continue;
        }

        int maxScan;
        int cachedGuardEnd = state[base + OFFSET_GUARD_END];
        if (gap.isExecutorGuardedGap()
            && gap.maxLength() == Integer.MAX_VALUE
            && cachedGuardEnd >= currentPos) {
          maxScan = cachedGuardEnd;
        } else {
          maxScan =
              reluctantGuarded
                  ? gap.guardedSearchEnd(scanner, currentPos, textLen)
                  : gap.scanClassEnd(scanner, currentPos, textLen);
          if (gapScansText(gap, reluctantGuarded)) {
            long scanWork = Math.max(1, maxScan - currentPos);
            workHolder[0] += scanWork;
            if (WorkCounterConfig.ENABLED) {
              WorkCounter.record(scanWork);
            }
            if (WorkLimit.isExhausted(workHolder[0], workLimit)) {
              return WORK_LIMIT_EXHAUSTED;
            }
          }
          if (gap.isExecutorGuardedGap() && gap.maxLength() == Integer.MAX_VALUE) {
            state[base + OFFSET_GUARD_END] = maxScan;
            cachedGuardEnd = maxScan;
          }
        }

        int maxHop = Math.min(textLen - anchor.minLength(), maxScan);
        if (minHop > maxHop) {
          backtrack = true;
          s--;
          continue;
        }

        state[base + OFFSET_MIN_HOP] = minHop;
        state[base + OFFSET_MAX_HOP] = maxHop;

        if (gap.isGreedy()) {
          state[base + OFFSET_SEARCH_POS] = maxHop;
        } else {
          int curWatermark = state[base + OFFSET_WATERMARK];
          int curLower;
          if (gap.isExecutorGuardedGap()
              && gap.maxLength() == Integer.MAX_VALUE
              && currentPos <= cachedGuardEnd) {
            curLower = Math.max(minHop, curWatermark);
          } else {
            curLower = minHop;
          }
          if (curLower > maxHop) {
            backtrack = true;
            s--;
            continue;
          }
          state[base + OFFSET_SEARCH_POS] = curLower;
        }
      }

      int minHop = state[base + OFFSET_MIN_HOP];
      int maxHop = state[base + OFFSET_MAX_HOP];
      boolean found = false;

      if (gap.isGreedy()) {
        int curUpper = state[base + OFFSET_SEARCH_POS];
        while (curUpper >= minHop) {
          int p = anchor.lastIndexOf(scanner, minHop, curUpper);
          long workUnits = Math.max(1, curUpper - (p < 0 ? minHop : p) + anchor.minLength());
          workHolder[0] += workUnits;
          if (WorkCounterConfig.ENABLED) {
            WorkCounter.record(workUnits);
          }
          if (WorkLimit.isExhausted(workHolder[0], workLimit)) {
            return WORK_LIMIT_EXHAUSTED;
          }
          if (p < 0) {
            if (curUpper == maxHop) {
              state[base + OFFSET_WATERMARK] = Math.max(state[base + OFFSET_WATERMARK], maxHop);
            }
            break;
          }
          boolean matches;
          if (gap.maxLength() == Integer.MAX_VALUE
              && isUnboundedGapSatisfiedUtf8(gap, p - currentPos)) {
            matches = true;
          } else {
            long sliceWork = Math.max(1, p - currentPos);
            workHolder[0] += sliceWork;
            if (WorkCounterConfig.ENABLED) {
              WorkCounter.record(sliceWork);
            }
            if (WorkLimit.isExhausted(workHolder[0], workLimit)) {
              return WORK_LIMIT_EXHAUSTED;
            }
            matches = gap.matchesSlice(scanner, currentPos, p);
          }
          if (matches) {
            int anchorLen = anchor.lengthAt(scanner, p);
            if (anchorLen > 0) {
              state[base + OFFSET_P] = p;
              state[base + OFFSET_ANCHOR_LEN] = anchorLen;
              state[base + OFFSET_SEARCH_POS] = p - 1;
              found = true;
              break;
            }
          }
          curUpper = p - 1;
        }
      } else {
        int curLower = state[base + OFFSET_SEARCH_POS];
        int curWatermark = state[base + OFFSET_WATERMARK];
        while (curLower <= maxHop) {
          int p = anchor.findNextWithin(scanner, curLower, maxHop);
          long workUnits = Math.max(1, (p < 0 ? maxHop : p) - curLower + anchor.minLength());
          workHolder[0] += workUnits;
          if (WorkCounterConfig.ENABLED) {
            WorkCounter.record(workUnits);
          }
          if (WorkLimit.isExhausted(workHolder[0], workLimit)) {
            return WORK_LIMIT_EXHAUSTED;
          }
          if (p < 0) {
            curWatermark = Math.max(curWatermark, maxHop);
            state[base + OFFSET_WATERMARK] = curWatermark;
            break;
          }

          if (reluctantGuarded) {
            int guard = gap.findFirstGuardByte(scanner, currentPos, p);
            if (guard >= currentPos) {
              break;
            }
          }
          curWatermark = Math.max(curWatermark, p);
          state[base + OFFSET_WATERMARK] = curWatermark;

          boolean matches;
          // No endsAtCodePointBoundary check here, unlike the String path: that check exists only
          // to reject candidates that land between a UTF-16 surrogate pair. UTF-8 is
          // self-synchronizing (lead bytes are 0x00-0x7F and 0xC2-0xF4, continuations are
          // 0x80-0xBF), so a well-formed needle can never match starting inside a multi-byte
          // sequence, and malformed input is decoded as one-byte U+FFFD replacements that are
          // themselves boundaries.
          if (gap.maxLength() == Integer.MAX_VALUE
              && isUnboundedGapSatisfiedUtf8(gap, p - currentPos)) {
            matches = true;
          } else {
            long sliceWork = Math.max(1, p - currentPos);
            workHolder[0] += sliceWork;
            if (WorkCounterConfig.ENABLED) {
              WorkCounter.record(sliceWork);
            }
            if (WorkLimit.isExhausted(workHolder[0], workLimit)) {
              return WORK_LIMIT_EXHAUSTED;
            }
            matches = gap.matchesSlice(scanner, currentPos, p);
          }
          if (matches) {
            int anchorLen = anchor.lengthAt(scanner, p);
            if (anchorLen > 0) {
              state[base + OFFSET_P] = p;
              state[base + OFFSET_ANCHOR_LEN] = anchorLen;
              state[base + OFFSET_SEARCH_POS] = p + 1;
              found = true;
              break;
            }
          }
          curLower = p + 1;
        }
      }

      if (!found) {
        backtrack = true;
        s--;
        continue;
      }

      int p = state[base + OFFSET_P];
      int anchorLen = state[base + OFFSET_ANCHOR_LEN];

      if (s == segments.length - 1) {
        int matchEnd =
            trailingGap.isExecutorFixedGap()
                ? trailingGap.matchExecutorFixedForward(scanner, p + anchorLen, textLen)
                : trailingGap.expandTrailing(scanner, p + anchorLen, textLen);
        if (matchEnd >= 0) {
          return matchEnd;
        }
        backtrack = true;
        continue;
      }

      s++;
      state[s * STATE_STRIDE + OFFSET_CUR_POS] = p + anchorLen;
      backtrack = false;
    }

    return -1;
  }

  private static int matchDownstreamIterative(
      String text,
      MultiAnchorDescriptor.Segment[] segments,
      int startSeg,
      int startPos,
      int textLen,
      MultiAnchorDescriptor.Gap trailingGap,
      int[] state,
      long[] workHolder,
      long workLimit) {
    if (startSeg == segments.length) {
      return trailingGap.isExecutorFixedGap()
          ? trailingGap.matchExecutorFixedForward(text, startPos, textLen)
          : trailingGap.expandTrailing(text, startPos, textLen);
    }

    int s = startSeg;
    state[s * STATE_STRIDE + OFFSET_CUR_POS] = startPos;
    boolean backtrack = false;

    while (s >= startSeg) {
      int base = s * STATE_STRIDE;
      MultiAnchorDescriptor.Segment seg = segments[s];
      MultiAnchorDescriptor.Gap gap = seg.gap();
      MultiAnchorDescriptor.Anchor anchor = seg.anchor();
      int currentPos = state[base + OFFSET_CUR_POS];

      if (gap.isExecutorFixedGap()) {
        if (backtrack) {
          s--;
          continue;
        }
        int p = gap.matchExecutorFixedForward(text, currentPos, textLen);
        if (p < 0 || !anchor.startsWith(text, p)) {
          backtrack = true;
          s--;
          continue;
        }
        int anchorLen = anchor.lengthAt(text, p);
        if (anchorLen <= 0) {
          backtrack = true;
          s--;
          continue;
        }
        state[base + OFFSET_P] = p;
        state[base + OFFSET_ANCHOR_LEN] = anchorLen;

        if (s == segments.length - 1) {
          int matchEnd =
              trailingGap.isExecutorFixedGap()
                  ? trailingGap.matchExecutorFixedForward(text, p + anchorLen, textLen)
                  : trailingGap.expandTrailing(text, p + anchorLen, textLen);
          if (matchEnd >= 0) {
            return matchEnd;
          }
          backtrack = true;
          s--;
          continue;
        }

        s++;
        state[s * STATE_STRIDE + OFFSET_CUR_POS] = p + anchorLen;
        backtrack = false;
        continue;
      }

      boolean reluctantGuarded = gap.isExecutorGuardedGap() && !gap.isGreedy();

      if (!backtrack) {
        int minHop = currentPos + gap.minLength();
        if (minHop > textLen) {
          backtrack = true;
          s--;
          continue;
        }

        int maxScan;
        int cachedGuardEnd = state[base + OFFSET_GUARD_END];
        if (gap.isExecutorGuardedGap()
            && gap.maxLength() == Integer.MAX_VALUE
            && cachedGuardEnd >= currentPos) {
          maxScan = cachedGuardEnd;
        } else {
          maxScan =
              reluctantGuarded
                  ? gap.guardedSearchEnd(text, currentPos, textLen)
                  : gap.scanClassEnd(text, currentPos, textLen);
          if (gapScansText(gap, reluctantGuarded)) {
            long scanWork = Math.max(1, maxScan - currentPos);
            workHolder[0] += scanWork;
            if (WorkCounterConfig.ENABLED) {
              WorkCounter.record(scanWork);
            }
            if (WorkLimit.isExhausted(workHolder[0], workLimit)) {
              return WORK_LIMIT_EXHAUSTED;
            }
          }
          if (gap.isExecutorGuardedGap() && gap.maxLength() == Integer.MAX_VALUE) {
            state[base + OFFSET_GUARD_END] = maxScan;
            cachedGuardEnd = maxScan;
          }
        }

        int maxHop = Math.min(textLen - anchor.minLength(), maxScan);
        if (minHop > maxHop) {
          backtrack = true;
          s--;
          continue;
        }

        state[base + OFFSET_MIN_HOP] = minHop;
        state[base + OFFSET_MAX_HOP] = maxHop;

        if (gap.isGreedy()) {
          state[base + OFFSET_SEARCH_POS] = maxHop;
        } else {
          int curWatermark = state[base + OFFSET_WATERMARK];
          int curLower;
          if (gap.isExecutorGuardedGap()
              && gap.maxLength() == Integer.MAX_VALUE
              && currentPos <= cachedGuardEnd) {
            curLower = Math.max(minHop, curWatermark);
          } else {
            curLower = minHop;
          }
          if (curLower > maxHop) {
            backtrack = true;
            s--;
            continue;
          }
          state[base + OFFSET_SEARCH_POS] = curLower;
        }
      }

      int minHop = state[base + OFFSET_MIN_HOP];
      int maxHop = state[base + OFFSET_MAX_HOP];
      boolean found = false;

      if (gap.isGreedy()) {
        int curUpper = state[base + OFFSET_SEARCH_POS];
        while (curUpper >= minHop) {
          int p = anchor.lastIndexOf(text, minHop, curUpper);
          long workUnits = Math.max(1, curUpper - (p < 0 ? minHop : p) + anchor.minLength());
          workHolder[0] += workUnits;
          if (WorkCounterConfig.ENABLED) {
            WorkCounter.record(workUnits);
          }
          if (WorkLimit.isExhausted(workHolder[0], workLimit)) {
            return WORK_LIMIT_EXHAUSTED;
          }
          if (p < 0) {
            if (curUpper == maxHop) {
              state[base + OFFSET_WATERMARK] = Math.max(state[base + OFFSET_WATERMARK], maxHop);
            }
            break;
          }
          boolean matches;
          if (!gap.endsAtCodePointBoundary(text, p)) {
            matches = false;
          } else if (gap.maxLength() == Integer.MAX_VALUE
              && isUnboundedGapSatisfied(gap, p - currentPos)) {
            matches = true;
          } else {
            long sliceWork = Math.max(1, p - currentPos);
            workHolder[0] += sliceWork;
            if (WorkCounterConfig.ENABLED) {
              WorkCounter.record(sliceWork);
            }
            if (WorkLimit.isExhausted(workHolder[0], workLimit)) {
              return WORK_LIMIT_EXHAUSTED;
            }
            matches = gap.matchesSlice(text, currentPos, p);
          }
          if (matches) {
            int anchorLen = anchor.lengthAt(text, p);
            if (anchorLen > 0) {
              state[base + OFFSET_P] = p;
              state[base + OFFSET_ANCHOR_LEN] = anchorLen;
              state[base + OFFSET_SEARCH_POS] = p - 1;
              found = true;
              break;
            }
          }
          curUpper = p - 1;
        }
      } else {
        int curLower = state[base + OFFSET_SEARCH_POS];
        int curWatermark = state[base + OFFSET_WATERMARK];
        while (curLower <= maxHop) {
          int p = anchor.findNextWithin(text, curLower, maxHop);
          long workUnits = Math.max(1, (p < 0 ? maxHop : p) - curLower + anchor.minLength());
          workHolder[0] += workUnits;
          if (WorkCounterConfig.ENABLED) {
            WorkCounter.record(workUnits);
          }
          if (WorkLimit.isExhausted(workHolder[0], workLimit)) {
            return WORK_LIMIT_EXHAUSTED;
          }
          if (p < 0) {
            curWatermark = Math.max(curWatermark, maxHop);
            state[base + OFFSET_WATERMARK] = curWatermark;
            break;
          }

          if (reluctantGuarded) {
            int guard = gap.findFirstGuardByte(text, currentPos, p);
            if (guard >= currentPos) {
              break;
            }
          }
          curWatermark = Math.max(curWatermark, p);
          state[base + OFFSET_WATERMARK] = curWatermark;

          boolean matches;
          if (!gap.endsAtCodePointBoundary(text, p)) {
            matches = false;
          } else if (gap.maxLength() == Integer.MAX_VALUE
              && isUnboundedGapSatisfied(gap, p - currentPos)) {
            matches = true;
          } else {
            long sliceWork = Math.max(1, p - currentPos);
            workHolder[0] += sliceWork;
            if (WorkCounterConfig.ENABLED) {
              WorkCounter.record(sliceWork);
            }
            if (WorkLimit.isExhausted(workHolder[0], workLimit)) {
              return WORK_LIMIT_EXHAUSTED;
            }
            matches = gap.matchesSlice(text, currentPos, p);
          }
          if (matches) {
            int anchorLen = anchor.lengthAt(text, p);
            if (anchorLen > 0) {
              state[base + OFFSET_P] = p;
              state[base + OFFSET_ANCHOR_LEN] = anchorLen;
              state[base + OFFSET_SEARCH_POS] = p + 1;
              found = true;
              break;
            }
          }
          curLower = p + 1;
        }
      }

      if (!found) {
        backtrack = true;
        s--;
        continue;
      }

      int p = state[base + OFFSET_P];
      int anchorLen = state[base + OFFSET_ANCHOR_LEN];

      if (s == segments.length - 1) {
        int matchEnd =
            trailingGap.isExecutorFixedGap()
                ? trailingGap.matchExecutorFixedForward(text, p + anchorLen, textLen)
                : trailingGap.expandTrailing(text, p + anchorLen, textLen);
        if (matchEnd >= 0) {
          return matchEnd;
        }
        backtrack = true;
        continue;
      }

      s++;
      state[s * STATE_STRIDE + OFFSET_CUR_POS] = p + anchorLen;
      backtrack = false;
    }

    return -1;
  }

  private static int findNextCountingWork(
      MultiAnchorDescriptor.Anchor anchor, String text, int fromIndex) {
    int result = anchor.findNext(text, fromIndex);
    if (WorkCounterConfig.ENABLED) {
      int examinedEnd = result < 0 ? text.length() : result + anchor.minLength();
      WorkCounter.record(Math.max(0, examinedEnd - fromIndex));
    }
    return result;
  }

  private static int advanceCandidatePos(int currentCandidatePos, int pDriver, int minUpstreamLen) {
    return Math.max(currentCandidatePos + 1, pDriver + 1 - minUpstreamLen);
  }

  private static boolean isUnboundedGapSatisfied(MultiAnchorDescriptor.Gap gap, int len) {
    if (gap.minLength() <= 1) {
      return len >= gap.minLength();
    }
    return len >= gap.minLength() * 2;
  }

  private static boolean isUnboundedGapSatisfiedUtf8(MultiAnchorDescriptor.Gap gap, int len) {
    if (gap.minLength() <= 1) {
      return len >= gap.minLength();
    }
    return len >= gap.minLength() * 4;
  }

  private static boolean gapScansText(MultiAnchorDescriptor.Gap gap, boolean reluctantGuarded) {
    if (reluctantGuarded) {
      return gap.maxLength() != Integer.MAX_VALUE;
    }
    return gap.kind() == MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT
        || gap.kind() == MultiAnchorDescriptor.GapKind.SINGLE_LINE_ANY_STAR;
  }

  private static Result findEndAnchored(
      MultiAnchorDescriptor descriptor,
      Utf8InputScanner scanner,
      int searchFrom,
      int[] groups,
      int[] anchorPositions) {
    int textLen = scanner.length();
    boolean wasDollar = descriptor.chain().endAnchorWasDollar();
    boolean unixLines = descriptor.chain().endAnchorUnixLines();
    int trailingTerm = wasDollar ? scanner.trailingLineTerminatorStart(unixLines, textLen) : -1;

    Result best = null;
    int[] bestAnchors = null;
    if (trailingTerm >= 0 && trailingTerm >= searchFrom) {
      best = matchAtTargetEnd(scanner, descriptor, trailingTerm, searchFrom, anchorPositions);
      if (best != null && anchorPositions != null) {
        bestAnchors = anchorPositions.clone();
      }
    }
    if (textLen >= searchFrom) {
      Result atEnd = matchAtTargetEnd(scanner, descriptor, textLen, searchFrom, anchorPositions);
      if (atEnd != null) {
        if (best == null || atEnd.start() < best.start()) {
          best = atEnd;
          bestAnchors = null;
        }
      }
    }
    if (best == null) {
      return Result.MISMATCH;
    }
    if (anchorPositions != null) {
      int[] chosenAnchors = bestAnchors != null ? bestAnchors : anchorPositions;
      extractCaptures(descriptor, groups, best.start(), best.end(), chosenAnchors);
    }
    return best;
  }

  private static Result matchAtTargetEnd(
      Utf8InputScanner scanner,
      MultiAnchorDescriptor descriptor,
      int targetEnd,
      int searchFrom,
      int[] anchorPositions) {
    MultiAnchorDescriptor.Segment[] segments = descriptor.chain().segments();
    int numSegments = segments.length;
    MultiAnchorDescriptor.Segment lastSeg = segments[numSegments - 1];
    MultiAnchorDescriptor.Anchor lastAnchor = lastSeg.anchor();
    MultiAnchorDescriptor.Gap trailingGap = descriptor.chain().trailingGap();

    if (trailingGap.kind() == MultiAnchorDescriptor.GapKind.EMPTY
        || trailingGap.kind() == MultiAnchorDescriptor.GapKind.TEXT_END
        || trailingGap.isExecutorFixedGap()) {
      int gapLen = trailingGap.isExecutorFixedGap() ? trailingGap.minLength() : 0;
      int gapStart = targetEnd - gapLen;
      if (gapStart < 0) {
        return null;
      }
      if (trailingGap.isExecutorFixedGap()
          && !trailingGap.matchesSlice(scanner, gapStart, targetEnd)) {
        return null;
      }

      int pLast = -1;
      int lastAnchorLen = -1;
      if (lastAnchor instanceof MultiAnchorDescriptor.Anchor.Single single) {
        int anchorLen = single.literalUtf8().length;
        int cand = gapStart - anchorLen;
        if (cand >= 0 && lastAnchor.startsWith(scanner, cand)) {
          pLast = cand;
          lastAnchorLen = anchorLen;
        }
      } else {
        int cand =
            lastAnchor.lastIndexOf(
                scanner,
                Math.max(0, gapStart - lastAnchor.maxLength()),
                Math.max(0, gapStart - lastAnchor.minLength()));
        if (cand >= 0) {
          int cLen = lastAnchor.lengthAt(scanner, cand);
          if (cand + cLen == gapStart) {
            pLast = cand;
            lastAnchorLen = cLen;
          }
        }
      }
      if (pLast < 0) {
        return null;
      }
      if (anchorPositions != null) {
        anchorPositions[(numSegments - 1) * 2] = pLast;
        anchorPositions[(numSegments - 1) * 2 + 1] = lastAnchorLen;
      }
      int p0 = verifyUpstream(scanner, segments, numSegments - 2, pLast, 0, anchorPositions);
      if (p0 < 0) {
        return null;
      }
      int matchStart = resolveLeadingStart(scanner, segments[0].gap(), p0, searchFrom);
      if (matchStart < 0 || matchStart < searchFrom) {
        return null;
      }
      return Result.matched(matchStart, targetEnd);
    }

    if (trailingGap.kind() == MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT) {
      int minGap = trailingGap.minLength();
      int maxGap = trailingGap.maxLength();
      if (targetEnd - minGap < 0) {
        return null;
      }
      int searchLowerBound =
          Math.max(0, targetEnd - (maxGap == Integer.MAX_VALUE ? targetEnd : maxGap * 4));
      int earliestGapStart = trailingGap.scanClassStart(scanner, searchLowerBound, targetEnd);
      if (targetEnd - earliestGapStart < minGap) {
        return null;
      }
      int maxAnchorStart = targetEnd - minGap - lastAnchor.minLength();
      int maxAnchorBytes = lastAnchor.maxLength() * 4;
      int minAnchorStart = Math.max(0, earliestGapStart - maxAnchorBytes);
      if (maxGap != Integer.MAX_VALUE) {
        minAnchorStart =
            Math.max(minAnchorStart, (int) Math.max(0L, targetEnd - maxGap * 4L - maxAnchorBytes));
      }
      if (maxAnchorStart < minAnchorStart) {
        return null;
      }
      int nextUpper = maxAnchorStart;
      while (nextUpper >= minAnchorStart) {
        int pLast = lastAnchor.lastIndexOf(scanner, minAnchorStart, nextUpper);
        if (pLast < 0) {
          break;
        }
        int uLen = lastAnchor.lengthAt(scanner, pLast);
        if (uLen > 0
            && pLast + uLen >= earliestGapStart
            && targetEnd - (pLast + uLen) >= minGap
            && (maxGap == Integer.MAX_VALUE || targetEnd - (pLast + uLen) <= maxGap)
            && trailingGap.matchesSlice(scanner, pLast + uLen, targetEnd)) {
          if (anchorPositions != null) {
            anchorPositions[(numSegments - 1) * 2] = pLast;
            anchorPositions[(numSegments - 1) * 2 + 1] = uLen;
          }
          int p0 = verifyUpstream(scanner, segments, numSegments - 2, pLast, 0, anchorPositions);
          if (p0 >= 0) {
            int matchStart = resolveLeadingStart(scanner, segments[0].gap(), p0, searchFrom);
            if (matchStart >= 0 && matchStart >= searchFrom) {
              return Result.matched(matchStart, targetEnd);
            }
          }
        }
        nextUpper = pLast - 1;
      }
      return null;
    }

    return null;
  }

  private static int resolveLeadingStart(
      Utf8InputScanner scanner, MultiAnchorDescriptor.Gap leadingGap, int p0, int searchFrom) {
    if (leadingGap.kind() == MultiAnchorDescriptor.GapKind.ANY_STAR) {
      return Math.max(0, searchFrom);
    }
    if (leadingGap.kind() == MultiAnchorDescriptor.GapKind.EMPTY) {
      return p0;
    }
    if (leadingGap.kind() == MultiAnchorDescriptor.GapKind.TEXT_START) {
      return p0 == 0 ? 0 : -1;
    }
    return leadingGap.expandLeading(scanner, p0, Math.max(0, searchFrom));
  }

  private static int verifyUpstream(
      Utf8InputScanner scanner,
      MultiAnchorDescriptor.Segment[] segments,
      int fromIndex,
      int anchorStart,
      int minReverseWatermark,
      int[] anchorPositions) {
    if (fromIndex < 0) {
      return anchorStart;
    }
    int curAnchorStart = anchorStart;
    int p0 = -1;

    for (int k = fromIndex; k >= 0; k--) {
      MultiAnchorDescriptor.Segment nextSeg = segments[k + 1];
      MultiAnchorDescriptor.Gap gap = nextSeg.gap();
      MultiAnchorDescriptor.Anchor upstreamAnchor = segments[k].anchor();

      int maxHop =
          (gap.maxLength() == Integer.MAX_VALUE)
              ? (curAnchorStart - minReverseWatermark)
              : (int)
                  Math.min(
                      ((long) gap.maxLength() + upstreamAnchor.maxLength()) * 4,
                      curAnchorStart - minReverseWatermark);
      int minHop = gap.minLength() + upstreamAnchor.minLength();

      int searchUpperBound = curAnchorStart - minHop;
      int searchLowerBound = Math.max(minReverseWatermark, curAnchorStart - maxHop);
      int earliestGapStart = gap.scanClassStart(scanner, searchLowerBound, curAnchorStart);
      if (curAnchorStart - earliestGapStart < gap.minLength()) {
        return -1;
      }
      long maxAnchorBytes = upstreamAnchor.maxLength() * 4L;
      int firstOverlappingAnchorStart = (int) Math.max(0L, earliestGapStart - maxAnchorBytes);
      searchLowerBound = Math.max(searchLowerBound, firstOverlappingAnchorStart);

      if (searchUpperBound < searchLowerBound) {
        return -1;
      }

      int pUpstream = upstreamAnchor.lastIndexOf(scanner, searchLowerBound, searchUpperBound);
      if (pUpstream < 0) {
        return -1;
      }

      int uLen = upstreamAnchor.lengthAt(scanner, pUpstream);
      boolean sliceValid =
          uLen > 0
              && pUpstream + uLen >= earliestGapStart
              && curAnchorStart - (pUpstream + uLen) >= gap.minLength()
              && ((gap.maxLength() == Integer.MAX_VALUE
                      && isUnboundedGapSatisfiedUtf8(gap, curAnchorStart - (pUpstream + uLen)))
                  || gap.matchesSlice(scanner, pUpstream + uLen, curAnchorStart));
      if (!sliceValid) {
        if (pUpstream + uLen < earliestGapStart) {
          return -1;
        }
        if (gap.kind() == MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT
            && curAnchorStart - (pUpstream + uLen) >= gap.minLength()) {
          return -1;
        }
        // Gap check failed: retry reverse search for earlier candidate
        boolean retryMatched = false;
        int nextUpper = pUpstream - 1;
        while (nextUpper >= searchLowerBound) {
          pUpstream = upstreamAnchor.lastIndexOf(scanner, searchLowerBound, nextUpper);
          if (pUpstream < 0) {
            break;
          }
          uLen = upstreamAnchor.lengthAt(scanner, pUpstream);
          if (uLen > 0
              && pUpstream + uLen >= earliestGapStart
              && curAnchorStart - (pUpstream + uLen) >= gap.minLength()
              && ((gap.maxLength() == Integer.MAX_VALUE
                      && isUnboundedGapSatisfiedUtf8(gap, curAnchorStart - (pUpstream + uLen)))
                  || gap.matchesSlice(scanner, pUpstream + uLen, curAnchorStart))) {
            retryMatched = true;
            break;
          }
          if (pUpstream + uLen < earliestGapStart) {
            break;
          }
          if (gap.kind() == MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT
              && curAnchorStart - (pUpstream + uLen) >= gap.minLength()) {
            break;
          }
          nextUpper = pUpstream - 1;
        }
        if (!retryMatched) {
          return -1;
        }
      }

      curAnchorStart = pUpstream;
      if (anchorPositions != null) {
        anchorPositions[k * 2] = pUpstream;
        anchorPositions[k * 2 + 1] = uLen;
      }
      if (k == 0) {
        p0 = pUpstream;
      }
    }
    return p0;
  }

  private static Result findEndAnchored(
      MultiAnchorDescriptor descriptor,
      String text,
      int searchFrom,
      int[] groups,
      int[] anchorPositions) {
    int textLen = text.length();
    boolean wasDollar = descriptor.chain().endAnchorWasDollar();
    boolean unixLines = descriptor.chain().endAnchorUnixLines();
    int trailingTerm =
        wasDollar ? StringInputScanner.trailingLineTerminatorStart(text, unixLines, textLen) : -1;

    Result best = null;
    int[] bestAnchors = null;
    if (trailingTerm >= 0 && trailingTerm >= searchFrom) {
      best = matchAtTargetEnd(text, descriptor, trailingTerm, searchFrom, anchorPositions);
      if (best != null && anchorPositions != null) {
        bestAnchors = anchorPositions.clone();
      }
    }
    if (textLen >= searchFrom) {
      Result atEnd = matchAtTargetEnd(text, descriptor, textLen, searchFrom, anchorPositions);
      if (atEnd != null) {
        if (best == null || atEnd.start() < best.start()) {
          best = atEnd;
          bestAnchors = null;
        }
      }
    }
    if (best == null) {
      return Result.MISMATCH;
    }
    if (anchorPositions != null) {
      int[] chosenAnchors = bestAnchors != null ? bestAnchors : anchorPositions;
      extractCaptures(descriptor, groups, best.start(), best.end(), chosenAnchors);
    }
    return best;
  }

  private static Result matchAtTargetEnd(
      String text,
      MultiAnchorDescriptor descriptor,
      int targetEnd,
      int searchFrom,
      int[] anchorPositions) {
    MultiAnchorDescriptor.Segment[] segments = descriptor.chain().segments();
    int numSegments = segments.length;
    MultiAnchorDescriptor.Segment lastSeg = segments[numSegments - 1];
    MultiAnchorDescriptor.Anchor lastAnchor = lastSeg.anchor();
    MultiAnchorDescriptor.Gap trailingGap = descriptor.chain().trailingGap();

    if (trailingGap.kind() == MultiAnchorDescriptor.GapKind.EMPTY
        || trailingGap.kind() == MultiAnchorDescriptor.GapKind.TEXT_END
        || trailingGap.isExecutorFixedGap()) {
      int gapLen = trailingGap.isExecutorFixedGap() ? trailingGap.minLength() : 0;
      int gapStart = targetEnd - gapLen;
      if (gapStart < 0) {
        return null;
      }
      if (trailingGap.isExecutorFixedGap()
          && !trailingGap.matchesSlice(text, gapStart, targetEnd)) {
        return null;
      }

      int pLast = -1;
      int lastAnchorLen = -1;
      if (lastAnchor instanceof MultiAnchorDescriptor.Anchor.Single single) {
        int anchorLen = single.literal().length();
        int cand = gapStart - anchorLen;
        if (cand >= 0 && lastAnchor.startsWith(text, cand)) {
          pLast = cand;
          lastAnchorLen = anchorLen;
        }
      } else {
        int cand =
            lastAnchor.lastIndexOf(
                text,
                Math.max(0, gapStart - lastAnchor.maxLength()),
                Math.max(0, gapStart - lastAnchor.minLength()));
        if (cand >= 0) {
          int cLen = lastAnchor.lengthAt(text, cand);
          if (cand + cLen == gapStart) {
            pLast = cand;
            lastAnchorLen = cLen;
          }
        }
      }
      if (pLast < 0) {
        return null;
      }
      if (anchorPositions != null) {
        anchorPositions[(numSegments - 1) * 2] = pLast;
        anchorPositions[(numSegments - 1) * 2 + 1] = lastAnchorLen;
      }
      int p0 = verifyUpstream(text, segments, numSegments - 2, pLast, 0, anchorPositions);
      if (p0 < 0) {
        return null;
      }
      int matchStart = resolveLeadingStart(text, segments[0].gap(), p0, searchFrom);
      if (matchStart < 0 || matchStart < searchFrom) {
        return null;
      }
      return Result.matched(matchStart, targetEnd);
    }

    if (trailingGap.kind() == MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT) {
      int minGap = trailingGap.minLength();
      int maxGap = trailingGap.maxLength();
      if (targetEnd - minGap < 0) {
        return null;
      }
      int searchLowerBound =
          Math.max(0, targetEnd - (maxGap == Integer.MAX_VALUE ? targetEnd : maxGap * 2));
      int earliestGapStart = trailingGap.scanClassStart(text, searchLowerBound, targetEnd);
      if (targetEnd - earliestGapStart < minGap) {
        return null;
      }
      int maxAnchorStart = targetEnd - minGap - lastAnchor.minLength();
      int minAnchorStart = Math.max(0, earliestGapStart - lastAnchor.maxLength());
      if (maxGap != Integer.MAX_VALUE) {
        minAnchorStart =
            Math.max(
                minAnchorStart,
                (int) Math.max(0L, targetEnd - maxGap * 2L - lastAnchor.maxLength()));
      }
      if (maxAnchorStart < minAnchorStart) {
        return null;
      }
      int nextUpper = maxAnchorStart;
      while (nextUpper >= minAnchorStart) {
        int pLast = lastAnchor.lastIndexOf(text, minAnchorStart, nextUpper);
        if (pLast < 0) {
          break;
        }
        int uLen = lastAnchor.lengthAt(text, pLast);
        if (uLen > 0
            && pLast + uLen >= earliestGapStart
            && targetEnd - (pLast + uLen) >= minGap
            && (maxGap == Integer.MAX_VALUE || targetEnd - (pLast + uLen) <= maxGap)
            && trailingGap.matchesSlice(text, pLast + uLen, targetEnd)) {
          if (anchorPositions != null) {
            anchorPositions[(numSegments - 1) * 2] = pLast;
            anchorPositions[(numSegments - 1) * 2 + 1] = uLen;
          }
          int p0 = verifyUpstream(text, segments, numSegments - 2, pLast, 0, anchorPositions);
          if (p0 >= 0) {
            int matchStart = resolveLeadingStart(text, segments[0].gap(), p0, searchFrom);
            if (matchStart >= 0 && matchStart >= searchFrom) {
              return Result.matched(matchStart, targetEnd);
            }
          }
        }
        nextUpper = pLast - 1;
      }
      return null;
    }

    return null;
  }

  private static int resolveLeadingStart(
      String text, MultiAnchorDescriptor.Gap leadingGap, int p0, int searchFrom) {
    if (leadingGap.kind() == MultiAnchorDescriptor.GapKind.ANY_STAR) {
      return Math.max(0, searchFrom);
    }
    if (leadingGap.kind() == MultiAnchorDescriptor.GapKind.EMPTY) {
      return p0;
    }
    if (leadingGap.kind() == MultiAnchorDescriptor.GapKind.TEXT_START) {
      return p0 == 0 ? 0 : -1;
    }
    return leadingGap.expandLeading(text, p0, Math.max(0, searchFrom));
  }

  private static int verifyUpstream(
      String text,
      MultiAnchorDescriptor.Segment[] segments,
      int fromIndex,
      int anchorStart,
      int minReverseWatermark,
      int[] anchorPositions) {
    if (fromIndex < 0) {
      return anchorStart;
    }
    int curAnchorStart = anchorStart;
    int p0 = -1;

    for (int k = fromIndex; k >= 0; k--) {
      MultiAnchorDescriptor.Segment nextSeg = segments[k + 1];
      MultiAnchorDescriptor.Gap gap = nextSeg.gap();
      MultiAnchorDescriptor.Anchor upstreamAnchor = segments[k].anchor();

      int maxHop =
          (gap.maxLength() == Integer.MAX_VALUE)
              ? (curAnchorStart - minReverseWatermark)
              : (int)
                  Math.min(
                      (long) gap.maxLength() * 2 + upstreamAnchor.maxLength(),
                      curAnchorStart - minReverseWatermark);
      int minHop = gap.minLength() + upstreamAnchor.minLength();

      int searchUpperBound = curAnchorStart - minHop;
      int searchLowerBound = Math.max(minReverseWatermark, curAnchorStart - maxHop);
      int earliestGapStart = gap.scanClassStart(text, searchLowerBound, curAnchorStart);
      if (curAnchorStart - earliestGapStart < gap.minLength()) {
        return -1;
      }
      int firstOverlappingAnchorStart =
          (int) Math.max(0L, (long) earliestGapStart - upstreamAnchor.maxLength());
      searchLowerBound = Math.max(searchLowerBound, firstOverlappingAnchorStart);

      if (searchUpperBound < searchLowerBound) {
        return -1;
      }

      int pUpstream = upstreamAnchor.lastIndexOf(text, searchLowerBound, searchUpperBound);
      if (pUpstream < 0) {
        return -1;
      }

      int uLen = upstreamAnchor.lengthAt(text, pUpstream);
      boolean sliceValid =
          uLen > 0
              && pUpstream + uLen >= earliestGapStart
              && curAnchorStart - (pUpstream + uLen) >= gap.minLength()
              && ((gap.maxLength() == Integer.MAX_VALUE
                      && isUnboundedGapSatisfied(gap, curAnchorStart - (pUpstream + uLen)))
                  ? gap.endsAtCodePointBoundary(text, pUpstream + uLen)
                  : gap.matchesSlice(text, pUpstream + uLen, curAnchorStart));
      if (!sliceValid) {
        if (pUpstream + uLen < earliestGapStart) {
          return -1;
        }
        if (gap.kind() == MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT
            && curAnchorStart - (pUpstream + uLen) >= gap.minLength()) {
          return -1;
        }
        // Gap check failed: retry reverse search for earlier candidate
        boolean retryMatched = false;
        int nextUpper = pUpstream - 1;
        while (nextUpper >= searchLowerBound) {
          pUpstream = upstreamAnchor.lastIndexOf(text, searchLowerBound, nextUpper);
          if (pUpstream < 0) {
            break;
          }
          uLen = upstreamAnchor.lengthAt(text, pUpstream);
          if (uLen > 0
              && pUpstream + uLen >= earliestGapStart
              && curAnchorStart - (pUpstream + uLen) >= gap.minLength()
              && ((gap.maxLength() == Integer.MAX_VALUE
                      && isUnboundedGapSatisfied(gap, curAnchorStart - (pUpstream + uLen)))
                  ? gap.endsAtCodePointBoundary(text, pUpstream + uLen)
                  : gap.matchesSlice(text, pUpstream + uLen, curAnchorStart))) {
            retryMatched = true;
            break;
          }
          if (pUpstream + uLen < earliestGapStart) {
            break;
          }
          if (gap.kind() == MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT
              && curAnchorStart - (pUpstream + uLen) >= gap.minLength()) {
            break;
          }
          nextUpper = pUpstream - 1;
        }
        if (!retryMatched) {
          return -1;
        }
      }

      curAnchorStart = pUpstream;
      if (anchorPositions != null) {
        anchorPositions[k * 2] = pUpstream;
        anchorPositions[k * 2 + 1] = uLen;
      }
      if (k == 0) {
        p0 = pUpstream;
      }
    }
    return p0;
  }

  private static int getBoundaryPoint(int pt, int matchStart, int matchEnd, int[] anchorPositions) {
    if (pt == 0) {
      return matchStart;
    }
    int numSegments = anchorPositions.length / 2;
    if (pt == 2 * numSegments + 1) {
      return matchEnd;
    }
    int k = (pt - 1) / 2;
    int start = anchorPositions[k * 2];
    int len = anchorPositions[k * 2 + 1];
    return (pt & 1) == 1 ? start : start + len;
  }

  private static void extractCaptures(
      MultiAnchorDescriptor descriptor,
      int[] groups,
      int matchStart,
      int matchEnd,
      int[] anchorPositions) {
    int[] spans = descriptor.captureGroupSpans();
    if (spans == null || groups == null || anchorPositions == null) {
      return;
    }
    int ncap = (spans.length / 2) - 1;
    for (int g = 1; g <= ncap; g++) {
      int sPt = spans[2 * g];
      int ePt = spans[2 * g + 1];
      if (sPt >= 0 && ePt >= 0 && 2 * g + 1 < groups.length) {
        groups[2 * g] = getBoundaryPoint(sPt, matchStart, matchEnd, anchorPositions);
        groups[2 * g + 1] = getBoundaryPoint(ePt, matchStart, matchEnd, anchorPositions);
      }
    }
  }
}
