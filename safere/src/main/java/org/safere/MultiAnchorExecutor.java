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

    // Phase 0: Negative short-circuit on rarest anchor if not at segment 0
    int[] checkOrder = descriptor.checkOrder();
    if (checkOrder != null && checkOrder.length > 0 && checkOrder[0] != 0) {
      MultiAnchorDescriptor.Anchor rarestAnchor = segments[checkOrder[0]].anchor();
      if (rarestAnchor.findNext(scanner, 0) < 0) {
        return Result.MISMATCH;
      }
    }

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
    int[] downstreamWatermarks = new int[numSegments];
    Arrays.fill(downstreamWatermarks, searchFrom);
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
                          (long) gap.maxLength() * 4 + upstreamAnchor.maxLength(),
                          curAnchorStart - minReverseWatermark);
          int minHop = gap.minLength() + upstreamAnchor.minLength();

          int searchUpperBound = curAnchorStart - minHop;
          int searchLowerBound = Math.max(minReverseWatermark, curAnchorStart - maxHop);

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
          if (uLen <= 0 || !gap.matchesSlice(scanner, pUpstream + uLen, curAnchorStart)) {
            // Gap check failed: retry reverse search for earlier candidate
            boolean retryMatched = false;
            int nextUpper = pUpstream - 1;
            while (nextUpper >= searchLowerBound) {
              pUpstream = upstreamAnchor.lastIndexOf(scanner, searchLowerBound, nextUpper);
              if (pUpstream < 0) {
                break;
              }
              uLen = upstreamAnchor.lengthAt(scanner, pUpstream);
              if (uLen > 0 && gap.matchesSlice(scanner, pUpstream + uLen, curAnchorStart)) {
                retryMatched = true;
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
          minReverseWatermark = Math.max(minReverseWatermark, pDriver);
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
            minReverseWatermark = Math.max(minReverseWatermark, pDriver);
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

      // Phase 2: Downstream verification for A_{driverIdx+1} ... A_{numSegments-1}
      boolean chainMatched = true;
      for (int i = driverIdx + 1; i < numSegments; i++) {
        MultiAnchorDescriptor.Segment seg = segments[i];
        MultiAnchorDescriptor.Gap gap = seg.gap();
        MultiAnchorDescriptor.Anchor anchor = seg.anchor();

        int p;
        if (gap.isExecutorFixedGap()) {
          p = gap.matchExecutorFixedForward(scanner, currentPos, textLen);
          if (p < 0 || !anchor.startsWith(scanner, p)) {
            chainMatched = false;
            break;
          }
        } else {
          int minHop = currentPos + gap.minLength();
          if (minHop > textLen) {
            chainMatched = false;
            break;
          }
          int maxScan = gap.scanClassEnd(scanner, currentPos, textLen);
          int maxHop = Math.min(textLen, maxScan + anchor.maxLength());

          int searchStart = Math.max(minHop, downstreamWatermarks[i]);
          if (searchStart > maxHop) {
            chainMatched = false;
            break;
          }

          if (gap.isGreedy()) {
            p = anchor.lastIndexOf(scanner, searchStart, maxHop);
          } else {
            p = anchor.findNextWithin(scanner, searchStart, maxHop);
          }
          if (p < 0) {
            downstreamWatermarks[i] = maxHop;
            chainMatched = false;
            break;
          }
          downstreamWatermarks[i] = p;

          if (!gap.matchesSlice(scanner, currentPos, p)) {
            chainMatched = false;
            break;
          }
        }

        int anchorLen = anchor.lengthAt(scanner, p);
        if (anchorLen <= 0) {
          chainMatched = false;
          break;
        }

        currentPos = p + anchorLen;
      }

      if (!chainMatched) {
        candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
        continue;
      }

      // Phase 3: Trailing gap resolution
      int matchEnd =
          trailingGap.isExecutorFixedGap()
              ? trailingGap.matchExecutorFixedForward(scanner, currentPos, textLen)
              : trailingGap.expandTrailing(scanner, currentPos, textLen);
      if (matchEnd < 0) {
        candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
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

    // Phase 0: Negative short-circuit on rarest anchor if not at segment 0
    int[] checkOrder = descriptor.checkOrder();
    if (checkOrder != null && checkOrder.length > 0 && checkOrder[0] != 0) {
      MultiAnchorDescriptor.Anchor rarestAnchor = segments[checkOrder[0]].anchor();
      if (rarestAnchor.findNext(text, 0) < 0) {
        return Result.MISMATCH;
      }
    }

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
    int[] downstreamWatermarks = new int[numSegments];
    Arrays.fill(downstreamWatermarks, searchFrom);
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
      int pDriver = driverAnchor.findNext(text, candidatePos + minUpstreamLen);
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
          if (uLen <= 0 || !gap.matchesSlice(text, pUpstream + uLen, curAnchorStart)) {
            // Gap check failed: retry reverse search for earlier candidate
            boolean retryMatched = false;
            int nextUpper = pUpstream - 1;
            while (nextUpper >= searchLowerBound) {
              pUpstream = upstreamAnchor.lastIndexOf(text, searchLowerBound, nextUpper);
              if (pUpstream < 0) {
                break;
              }
              uLen = upstreamAnchor.lengthAt(text, pUpstream);
              if (uLen > 0 && gap.matchesSlice(text, pUpstream + uLen, curAnchorStart)) {
                retryMatched = true;
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
          minReverseWatermark = Math.max(minReverseWatermark, pDriver);
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
            minReverseWatermark = Math.max(minReverseWatermark, pDriver);
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

      // Phase 2: Downstream verification for A_{driverIdx+1} ... A_{numSegments-1}
      boolean chainMatched = true;
      for (int i = driverIdx + 1; i < numSegments; i++) {
        MultiAnchorDescriptor.Segment seg = segments[i];
        MultiAnchorDescriptor.Gap gap = seg.gap();
        MultiAnchorDescriptor.Anchor anchor = seg.anchor();

        int p;
        if (gap.isExecutorFixedGap()) {
          p = gap.matchExecutorFixedForward(text, currentPos, textLen);
          if (p < 0 || !anchor.startsWith(text, p)) {
            chainMatched = false;
            break;
          }
        } else {
          int minHop = currentPos + gap.minLength();
          if (minHop > textLen) {
            chainMatched = false;
            break;
          }
          int maxScan = gap.scanClassEnd(text, currentPos, textLen);
          int maxHop = Math.min(textLen, maxScan + anchor.maxLength());

          int searchStart = Math.max(minHop, downstreamWatermarks[i]);
          if (searchStart > maxHop) {
            chainMatched = false;
            break;
          }

          if (gap.isGreedy()) {
            p = anchor.lastIndexOf(text, searchStart, maxHop);
          } else {
            p = anchor.findNextWithin(text, searchStart, maxHop);
          }
          if (p < 0) {
            downstreamWatermarks[i] = maxHop;
            chainMatched = false;
            break;
          }
          downstreamWatermarks[i] = p;

          if (!gap.matchesSlice(text, currentPos, p)) {
            chainMatched = false;
            break;
          }
        }

        int anchorLen = anchor.lengthAt(text, p);
        if (anchorLen <= 0) {
          chainMatched = false;
          break;
        }

        currentPos = p + anchorLen;
      }

      if (!chainMatched) {
        candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
        continue;
      }

      // Phase 3: Trailing gap resolution
      int matchEnd =
          trailingGap.isExecutorFixedGap()
              ? trailingGap.matchExecutorFixedForward(text, currentPos, textLen)
              : trailingGap.expandTrailing(text, currentPos, textLen);
      if (matchEnd < 0) {
        candidatePos = advanceCandidatePos(candidatePos, pDriver, minUpstreamLen);
        continue;
      }

      return Result.matched(matchStart, matchEnd);
    }

    return Result.MISMATCH;
  }

  private static int advanceCandidatePos(int currentCandidatePos, int pDriver, int minUpstreamLen) {
    return Math.max(currentCandidatePos + 1, pDriver + 1 - minUpstreamLen);
  }
}
