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

    int candidatePos = Math.max(0, searchFrom);
    MultiAnchorDescriptor.Segment firstSeg = segments[0];
    MultiAnchorDescriptor.Gap leadingGap = firstSeg.gap();
    MultiAnchorDescriptor.Anchor firstAnchor = firstSeg.anchor();
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

    while (candidatePos <= textLen - minTotalLength) {
      // Phase 1: Locate candidate for Anchor 0 (primary anchor)
      int p0 = firstAnchor.findNext(scanner, candidatePos + leadingGap.minLength());
      if (p0 < 0) {
        // First anchor not found anywhere downstream -> document-level mismatch
        return Result.MISMATCH;
      }
      if (isStartAnchored
          && p0 > 0
          && leadingGap.kind() == MultiAnchorDescriptor.GapKind.TEXT_START) {
        return Result.MISMATCH;
      }

      // Resolve match start from p0 using leading gap
      int matchStart = leadingGap.matchBackward(scanner, p0, candidatePos);
      if (matchStart < 0) {
        candidatePos = p0 + 1;
        continue;
      }

      int len0 = firstAnchor.lengthAt(scanner, p0);
      if (len0 <= 0) {
        candidatePos = p0 + 1;
        continue;
      }

      // Phase 2: Sequentially locate downstream anchors with bounded gap windows
      boolean chainMatched = true;
      int currentPos = p0 + len0;

      for (int i = 1; i < numSegments; i++) {
        MultiAnchorDescriptor.Segment seg = segments[i];
        MultiAnchorDescriptor.Gap gap = seg.gap();
        MultiAnchorDescriptor.Anchor anchor = seg.anchor();

        int p = gap.matchExecutorFixedForward(scanner, currentPos, textLen);
        if (p < 0) {
          chainMatched = false;
          break;
        }

        int anchorLen = anchor.lengthAt(scanner, p);
        if (anchorLen <= 0) {
          chainMatched = false;
          break;
        }

        currentPos = p + anchorLen;
      }

      if (!chainMatched) {
        candidatePos = p0 + 1;
        continue;
      }

      // Phase 3: Trailing gap resolution
      int matchEnd = trailingGap.matchExecutorFixedForward(scanner, currentPos, textLen);
      if (matchEnd < 0) {
        candidatePos = p0 + 1;
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

    int candidatePos = Math.max(0, searchFrom);
    MultiAnchorDescriptor.Segment firstSeg = segments[0];
    MultiAnchorDescriptor.Gap leadingGap = firstSeg.gap();
    MultiAnchorDescriptor.Anchor firstAnchor = firstSeg.anchor();
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

    while (candidatePos <= textLen - minTotalLength) {
      // Phase 1: Locate candidate for Anchor 0
      int p0 = firstAnchor.findNext(text, candidatePos + leadingGap.minLength());
      if (p0 < 0) {
        // First anchor not found anywhere downstream -> document-level mismatch
        return Result.MISMATCH;
      }
      if (isStartAnchored
          && p0 > 0
          && leadingGap.kind() == MultiAnchorDescriptor.GapKind.TEXT_START) {
        return Result.MISMATCH;
      }

      // Resolve match start from p0 using leading gap
      int matchStart = leadingGap.matchBackward(text, p0, candidatePos);
      if (matchStart < 0) {
        candidatePos = p0 + 1;
        continue;
      }

      int len0 = firstAnchor.lengthAt(text, p0);
      if (len0 <= 0) {
        candidatePos = p0 + 1;
        continue;
      }

      // Phase 2: Sequentially locate downstream anchors with bounded gap windows
      boolean chainMatched = true;
      int currentPos = p0 + len0;

      for (int i = 1; i < numSegments; i++) {
        MultiAnchorDescriptor.Segment seg = segments[i];
        MultiAnchorDescriptor.Gap gap = seg.gap();
        MultiAnchorDescriptor.Anchor anchor = seg.anchor();

        int p = gap.matchExecutorFixedForward(text, currentPos, textLen);
        if (p < 0) {
          chainMatched = false;
          break;
        }

        int anchorLen = anchor.lengthAt(text, p);
        if (anchorLen <= 0) {
          chainMatched = false;
          break;
        }

        currentPos = p + anchorLen;
      }

      if (!chainMatched) {
        candidatePos = p0 + 1;
        continue;
      }

      // Phase 3: Trailing gap resolution
      int matchEnd = trailingGap.matchExecutorFixedForward(text, currentPos, textLen);
      if (matchEnd < 0) {
        candidatePos = p0 + 1;
        continue;
      }

      return Result.matched(matchStart, matchEnd);
    }

    return Result.MISMATCH;
  }
}
