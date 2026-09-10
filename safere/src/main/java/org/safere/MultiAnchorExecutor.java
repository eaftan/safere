// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Arrays;
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
    long verificationWork = 0;

    int minReverseWatermark = Math.max(0, searchFrom);
    int[] downstreamGuardEnds = new int[numSegments];
    Arrays.fill(downstreamGuardEnds, -1);
    int[] firstSegmentWatermark = new int[] {searchFrom};
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

        int len0 = driverAnchor.lengthAt(scanner, pDriver);
        if (len0 <= 0) {
          candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
          continue;
        }
        currentPos = pDriver + len0;
      } else {
        // Upstream reverse verification for A_{driverIdx-1} down to A_0
        boolean upstreamMatched = true;
        int curAnchorStart = pDriver;
        int p0 = -1;

        for (int k = driverIdx - 1; k >= 0; k--) {
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
            upstreamMatched = false;
            break;
          }
          long maxAnchorBytes = (long) upstreamAnchor.maxLength() * 4L;
          int firstOverlappingAnchorStart =
              (int) Math.max(0L, (long) earliestGapStart - maxAnchorBytes);
          searchLowerBound = Math.max(searchLowerBound, firstOverlappingAnchorStart);

          if (searchUpperBound < searchLowerBound) {
            upstreamMatched = false;
            break;
          }

          int pUpstream = upstreamAnchor.lastIndexOf(scanner, searchLowerBound, searchUpperBound);
          if (pUpstream < 0) {
            upstreamMatched = false;
            break;
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
              upstreamMatched = false;
              break;
            }
            if (gap.kind() == MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT
                && curAnchorStart - (pUpstream + uLen) >= gap.minLength()) {
              upstreamMatched = false;
              break;
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
              upstreamMatched = false;
              break;
            }
          }

          curAnchorStart = pUpstream;
          if (k == 0) {
            p0 = pUpstream;
          }
        }

        if (!upstreamMatched) {
          candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
          verificationWork++;
          if (WorkLimit.isExhausted(verificationWork, workLimit)) {
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

        matchStart = resolvedStart;
        currentPos = pDriver + driverLen;
      }

      // Phase 2 & 3: Downstream verification for A_{driverIdx+1} ... A_{numSegments-1} and trailing
      // gap
      if (driverIdx < numSegments - 1) {
        MultiAnchorDescriptor.Segment seg1 = segments[driverIdx + 1];
        MultiAnchorDescriptor.Gap gap1 = seg1.gap();
        boolean reluctantGuarded = gap1.isExecutorGuardedGap() && !gap1.isGreedy();
        int maxScan;
        int cachedGuardEnd = downstreamGuardEnds[driverIdx + 1];
        if (reluctantGuarded) {
          maxScan = gap1.guardedSearchEnd(scanner, currentPos, textLen);
          if (cachedGuardEnd >= currentPos) {
            maxScan = Math.min(maxScan, cachedGuardEnd);
          }
        } else if (gap1.isExecutorGuardedGap()
            && gap1.maxLength() == Integer.MAX_VALUE
            && cachedGuardEnd >= currentPos) {
          maxScan = cachedGuardEnd;
        } else {
          maxScan = gap1.scanClassEnd(scanner, currentPos, textLen);
          if (gap1.isExecutorGuardedGap() && gap1.maxLength() == Integer.MAX_VALUE) {
            downstreamGuardEnds[driverIdx + 1] = maxScan;
          }
        }
        int maxHop = Math.min(textLen - seg1.anchor().minLength(), maxScan);
        if (currentPos + seg1.gap().minLength() > maxHop || firstSegmentWatermark[0] >= maxHop) {
          candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
          verificationWork++;
          if (WorkLimit.isExhausted(verificationWork, workLimit)) {
            return Result.FALLBACK;
          }
          continue;
        }
      }

      int matchEnd =
          matchDownstream(
              scanner,
              segments,
              driverIdx + 1,
              currentPos,
              textLen,
              trailingGap,
              firstSegmentWatermark,
              downstreamGuardEnds);
      if (matchEnd < 0) {
        candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
        verificationWork++;
        if (WorkLimit.isExhausted(verificationWork, workLimit)) {
          return Result.FALLBACK;
        }
        continue;
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
    long verificationWork = 0;

    int minReverseWatermark = Math.max(0, searchFrom);
    int[] downstreamGuardEnds = new int[numSegments];
    Arrays.fill(downstreamGuardEnds, -1);
    int[] firstSegmentWatermark = new int[] {searchFrom};
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

        int len0 = driverAnchor.lengthAt(text, pDriver);
        if (len0 <= 0) {
          candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
          continue;
        }
        currentPos = pDriver + len0;
      } else {
        // Upstream reverse verification for A_{driverIdx-1} down to A_0
        boolean upstreamMatched = true;
        int curAnchorStart = pDriver;
        int p0 = -1;

        for (int k = driverIdx - 1; k >= 0; k--) {
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
            upstreamMatched = false;
            break;
          }
          int firstOverlappingAnchorStart =
              (int) Math.max(0L, (long) earliestGapStart - upstreamAnchor.maxLength());
          searchLowerBound = Math.max(searchLowerBound, firstOverlappingAnchorStart);

          if (searchUpperBound < searchLowerBound) {
            upstreamMatched = false;
            break;
          }

          int pUpstream = upstreamAnchor.lastIndexOf(text, searchLowerBound, searchUpperBound);
          if (pUpstream < 0) {
            upstreamMatched = false;
            break;
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
              upstreamMatched = false;
              break;
            }
            if (gap.kind() == MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT
                && curAnchorStart - (pUpstream + uLen) >= gap.minLength()) {
              upstreamMatched = false;
              break;
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
              upstreamMatched = false;
              break;
            }
          }

          curAnchorStart = pUpstream;
          if (k == 0) {
            p0 = pUpstream;
          }
        }

        if (!upstreamMatched) {
          candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
          verificationWork++;
          if (WorkLimit.isExhausted(verificationWork, workLimit)) {
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

        matchStart = resolvedStart;
        currentPos = pDriver + driverLen;
      }

      // Phase 2 & 3: Downstream verification for A_{driverIdx+1} ... A_{numSegments-1} and trailing
      // gap
      if (driverIdx < numSegments - 1) {
        MultiAnchorDescriptor.Segment seg1 = segments[driverIdx + 1];
        MultiAnchorDescriptor.Gap gap1 = seg1.gap();
        boolean reluctantGuarded = gap1.isExecutorGuardedGap() && !gap1.isGreedy();
        int maxScan;
        int cachedGuardEnd = downstreamGuardEnds[driverIdx + 1];
        if (reluctantGuarded) {
          maxScan = gap1.guardedSearchEnd(text, currentPos, textLen);
          if (cachedGuardEnd >= currentPos) {
            maxScan = Math.min(maxScan, cachedGuardEnd);
          }
        } else if (gap1.isExecutorGuardedGap()
            && gap1.maxLength() == Integer.MAX_VALUE
            && cachedGuardEnd >= currentPos) {
          maxScan = cachedGuardEnd;
        } else {
          maxScan = gap1.scanClassEnd(text, currentPos, textLen);
          if (gap1.isExecutorGuardedGap() && gap1.maxLength() == Integer.MAX_VALUE) {
            downstreamGuardEnds[driverIdx + 1] = maxScan;
          }
        }
        int maxHop = Math.min(textLen - seg1.anchor().minLength(), maxScan);
        if (currentPos + seg1.gap().minLength() > maxHop || firstSegmentWatermark[0] >= maxHop) {
          candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
          verificationWork++;
          if (WorkLimit.isExhausted(verificationWork, workLimit)) {
            return Result.FALLBACK;
          }
          continue;
        }
      }

      int matchEnd =
          matchDownstream(
              text,
              segments,
              driverIdx + 1,
              currentPos,
              textLen,
              trailingGap,
              firstSegmentWatermark,
              downstreamGuardEnds);
      if (matchEnd < 0) {
        candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
        verificationWork++;
        if (WorkLimit.isExhausted(verificationWork, workLimit)) {
          return Result.FALLBACK;
        }
        continue;
      }

      return Result.matched(matchStart, matchEnd);
    }

    return Result.MISMATCH;
  }

  private static int matchDownstream(
      Utf8InputScanner scanner,
      MultiAnchorDescriptor.Segment[] segments,
      int i,
      int currentPos,
      int textLen,
      MultiAnchorDescriptor.Gap trailingGap,
      int[] firstSegmentWatermark,
      int[] downstreamGuardEnds) {
    if (i == segments.length) {
      return trailingGap.isExecutorFixedGap()
          ? trailingGap.matchExecutorFixedForward(scanner, currentPos, textLen)
          : trailingGap.expandTrailing(scanner, currentPos, textLen);
    }

    MultiAnchorDescriptor.Segment seg = segments[i];
    MultiAnchorDescriptor.Gap gap = seg.gap();
    MultiAnchorDescriptor.Anchor anchor = seg.anchor();

    if (gap.isExecutorFixedGap()) {
      int p = gap.matchExecutorFixedForward(scanner, currentPos, textLen);
      if (p < 0 || !anchor.startsWith(scanner, p)) {
        return -1;
      }
      int anchorLen = anchor.lengthAt(scanner, p);
      if (anchorLen <= 0) {
        return -1;
      }
      return matchDownstream(
          scanner, segments, i + 1, p + anchorLen, textLen, trailingGap, null, downstreamGuardEnds);
    }

    int minHop = currentPos + gap.minLength();
    if (minHop > textLen) {
      return -1;
    }
    boolean reluctantGuardedGap = gap.isExecutorGuardedGap() && !gap.isGreedy();
    int maxScan;
    int cachedGuardEnd = downstreamGuardEnds != null ? downstreamGuardEnds[i] : -1;
    if (reluctantGuardedGap) {
      maxScan = gap.guardedSearchEnd(scanner, currentPos, textLen);
      if (cachedGuardEnd >= currentPos) {
        maxScan = Math.min(maxScan, cachedGuardEnd);
      }
    } else if (gap.isExecutorGuardedGap() && gap.maxLength() == Integer.MAX_VALUE) {
      if (cachedGuardEnd >= currentPos) {
        maxScan = cachedGuardEnd;
      } else {
        maxScan = gap.scanClassEnd(scanner, currentPos, textLen);
        if (downstreamGuardEnds != null) {
          downstreamGuardEnds[i] = maxScan;
        }
      }
    } else {
      maxScan = gap.scanClassEnd(scanner, currentPos, textLen);
    }
    int maxHop = Math.min(textLen - anchor.minLength(), maxScan);
    if (minHop > maxHop) {
      return -1;
    }

    if (gap.isGreedy()) {
      int curUpper = maxHop;
      while (curUpper >= minHop) {
        int p = anchor.lastIndexOf(scanner, minHop, curUpper);
        if (p < 0) {
          if (curUpper == maxHop && firstSegmentWatermark != null) {
            firstSegmentWatermark[0] = Math.max(firstSegmentWatermark[0], maxHop);
          }
          return -1;
        }
        if ((gap.maxLength() == Integer.MAX_VALUE
                && isUnboundedGapSatisfiedUtf8(gap, p - currentPos))
            || gap.matchesSlice(scanner, currentPos, p)) {
          int anchorLen = anchor.lengthAt(scanner, p);
          if (anchorLen > 0) {
            int matchEnd =
                matchDownstream(
                    scanner,
                    segments,
                    i + 1,
                    p + anchorLen,
                    textLen,
                    trailingGap,
                    null,
                    downstreamGuardEnds);
            if (matchEnd >= 0) {
              return matchEnd;
            }
          }
        }
        curUpper = p - 1;
      }
      return -1;
    } else {
      int curLower =
          firstSegmentWatermark != null ? Math.max(minHop, firstSegmentWatermark[0]) : minHop;
      while (curLower <= maxHop) {
        int p = anchor.findNextWithin(scanner, curLower, maxHop);
        if (p < 0) {
          if (firstSegmentWatermark != null) {
            firstSegmentWatermark[0] = Math.max(firstSegmentWatermark[0], maxHop);
          }
          return -1;
        }
        if (firstSegmentWatermark != null) {
          firstSegmentWatermark[0] = Math.max(firstSegmentWatermark[0], p);
        }
        if (reluctantGuardedGap) {
          int guard = gap.findFirstGuardByte(scanner, currentPos, p);
          if (guard >= currentPos) {
            if (downstreamGuardEnds != null) {
              downstreamGuardEnds[i] = guard;
            }
            return -1;
          }
        }
        if ((gap.maxLength() == Integer.MAX_VALUE
                && isUnboundedGapSatisfiedUtf8(gap, p - currentPos))
            || gap.matchesSlice(scanner, currentPos, p)) {
          int anchorLen = anchor.lengthAt(scanner, p);
          if (anchorLen > 0) {
            int matchEnd =
                matchDownstream(
                    scanner,
                    segments,
                    i + 1,
                    p + anchorLen,
                    textLen,
                    trailingGap,
                    null,
                    downstreamGuardEnds);
            if (matchEnd >= 0) {
              return matchEnd;
            }
          }
        }
        curLower = p + 1;
      }
      return -1;
    }
  }

  private static int matchDownstream(
      String text,
      MultiAnchorDescriptor.Segment[] segments,
      int i,
      int currentPos,
      int textLen,
      MultiAnchorDescriptor.Gap trailingGap,
      int[] firstSegmentWatermark,
      int[] downstreamGuardEnds) {
    if (i == segments.length) {
      return trailingGap.isExecutorFixedGap()
          ? trailingGap.matchExecutorFixedForward(text, currentPos, textLen)
          : trailingGap.expandTrailing(text, currentPos, textLen);
    }

    MultiAnchorDescriptor.Segment seg = segments[i];
    MultiAnchorDescriptor.Gap gap = seg.gap();
    MultiAnchorDescriptor.Anchor anchor = seg.anchor();

    if (gap.isExecutorFixedGap()) {
      int p = gap.matchExecutorFixedForward(text, currentPos, textLen);
      if (p < 0 || !anchor.startsWith(text, p)) {
        return -1;
      }
      int anchorLen = anchor.lengthAt(text, p);
      if (anchorLen <= 0) {
        return -1;
      }
      return matchDownstream(
          text, segments, i + 1, p + anchorLen, textLen, trailingGap, null, downstreamGuardEnds);
    }

    int minHop = currentPos + gap.minLength();
    if (minHop > textLen) {
      return -1;
    }
    boolean reluctantGuardedGap = gap.isExecutorGuardedGap() && !gap.isGreedy();
    int maxScan;
    int cachedGuardEnd = downstreamGuardEnds != null ? downstreamGuardEnds[i] : -1;
    if (reluctantGuardedGap) {
      maxScan = gap.guardedSearchEnd(text, currentPos, textLen);
      if (cachedGuardEnd >= currentPos) {
        maxScan = Math.min(maxScan, cachedGuardEnd);
      }
    } else if (gap.isExecutorGuardedGap() && gap.maxLength() == Integer.MAX_VALUE) {
      if (cachedGuardEnd >= currentPos) {
        maxScan = cachedGuardEnd;
      } else {
        maxScan = gap.scanClassEnd(text, currentPos, textLen);
        if (downstreamGuardEnds != null) {
          downstreamGuardEnds[i] = maxScan;
        }
      }
    } else {
      maxScan = gap.scanClassEnd(text, currentPos, textLen);
    }
    int maxHop = Math.min(textLen - anchor.minLength(), maxScan);
    if (minHop > maxHop) {
      return -1;
    }

    if (gap.isGreedy()) {
      int curUpper = maxHop;
      while (curUpper >= minHop) {
        int p = anchor.lastIndexOf(text, minHop, curUpper);
        if (p < 0) {
          if (curUpper == maxHop && firstSegmentWatermark != null) {
            firstSegmentWatermark[0] = Math.max(firstSegmentWatermark[0], maxHop);
          }
          return -1;
        }
        if (gap.endsAtCodePointBoundary(text, p)
            && ((gap.maxLength() == Integer.MAX_VALUE
                    && isUnboundedGapSatisfied(gap, p - currentPos))
                || gap.matchesSlice(text, currentPos, p))) {
          int anchorLen = anchor.lengthAt(text, p);
          if (anchorLen > 0) {
            int matchEnd =
                matchDownstream(
                    text,
                    segments,
                    i + 1,
                    p + anchorLen,
                    textLen,
                    trailingGap,
                    null,
                    downstreamGuardEnds);
            if (matchEnd >= 0) {
              return matchEnd;
            }
          }
        }
        curUpper = p - 1;
      }
      return -1;
    } else {
      int curLower =
          firstSegmentWatermark != null ? Math.max(minHop, firstSegmentWatermark[0]) : minHop;
      while (curLower <= maxHop) {
        int p = anchor.findNextWithin(text, curLower, maxHop);
        if (p < 0) {
          if (firstSegmentWatermark != null) {
            firstSegmentWatermark[0] = Math.max(firstSegmentWatermark[0], maxHop);
          }
          return -1;
        }
        if (firstSegmentWatermark != null) {
          firstSegmentWatermark[0] = Math.max(firstSegmentWatermark[0], p);
        }
        if (reluctantGuardedGap) {
          int guard = gap.findFirstGuardByte(text, currentPos, p);
          if (guard >= currentPos) {
            if (downstreamGuardEnds != null) {
              downstreamGuardEnds[i] = guard;
            }
            return -1;
          }
        }
        if (gap.endsAtCodePointBoundary(text, p)
            && ((gap.maxLength() == Integer.MAX_VALUE
                    && isUnboundedGapSatisfied(gap, p - currentPos))
                || gap.matchesSlice(text, currentPos, p))) {
          int anchorLen = anchor.lengthAt(text, p);
          if (anchorLen > 0) {
            int matchEnd =
                matchDownstream(
                    text,
                    segments,
                    i + 1,
                    p + anchorLen,
                    textLen,
                    trailingGap,
                    null,
                    downstreamGuardEnds);
            if (matchEnd >= 0) {
              return matchEnd;
            }
          }
        }
        curLower = p + 1;
      }
      return -1;
    }
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
}
