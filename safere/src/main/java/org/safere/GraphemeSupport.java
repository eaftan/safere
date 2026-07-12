// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Arrays;

/** Grapheme segmentation state and SafeRE's grapheme-boundary policy. */
final class GraphemeSupport {
  private static final int[][] EXTENDED_PICTOGRAPHIC =
      UnicodeProperties.lookupBinaryProperty("Extended_Pictographic");

  private static final int VISIT_KEY_VARIANT_BITS = 5;
  private static final int LOW_SURROGATE_PAIR_VISIBLE = 1;
  private static final int EXTENDED_PICTOGRAPHIC_VISIBLE = 1 << 1;
  private static final int REGIONAL_INDICATOR_ODD = 1 << 2;
  private static final int INDIC_CONJUNCT_LINKER_VISIBLE = 1 << 3;
  private static final int INDIC_CONJUNCT_SEQUENCE_VISIBLE = 1 << 4;

  /**
   * Per-input grapheme segmentation context.
   *
   * <p>UAX #29 boundary checks are finite-state, but some rules need state from earlier code
   * points. Keep that state here so engines can answer boundary questions in O(1) while scanning.
   */
  static final class Context {
    private static final Context EMPTY = new Context(null);

    private final InputScanner text;
    private int[] regionalIndicatorStartsBefore;
    private int[] regionalIndicatorRunStartBefore;
    private int[] extendedPictographicStartBefore;
    private int[] extendedPictographicPrependBefore;
    private int[] indicConjunctSequenceStartBefore;
    private int[] indicConjunctLinkerStartBefore;

    private Context(InputScanner text) {
      this.text = text;
    }

    static Context create(String text, boolean includeGraphemeClusterBoundary) {
      if (!includeGraphemeClusterBoundary) {
        return EMPTY;
      }
      return new Context(new StringInputScanner(text));
    }

    static Context create(InputScanner text, boolean includeGraphemeClusterBoundary) {
      if (!includeGraphemeClusterBoundary) {
        return EMPTY;
      }
      return new Context(text);
    }

    boolean hasEvenRegionalIndicatorsBefore(int pos, int regionStart) {
      if (text == null) {
        return true;
      }
      int start = Math.max(0, Math.min(regionStart, text.length()));
      if (pos <= start || pos > text.length()) {
        return true;
      }
      ensureRegionalIndicatorRunData();
      int runStart = regionalIndicatorRunStartBefore[pos];
      int countStart = Math.max(start, runStart);
      int regionalIndicatorsBefore =
          regionalIndicatorStartsBefore[pos] - regionalIndicatorStartsBefore[countStart];
      return (regionalIndicatorsBefore & 1) == 0;
    }

    boolean hasExtendedPictographicBefore(int pos, int regionStart) {
      if (text == null) {
        return false;
      }
      int start = Math.max(0, Math.min(regionStart, text.length()));
      if (pos <= start || pos > text.length()) {
        return false;
      }
      ensureExtendedPictographicData();
      int pictographicStart = extendedPictographicStartBefore[pos];
      if (pictographicStart < start) {
        return false;
      }
      int prependStart = extendedPictographicPrependBefore[pos];
      return prependStart < start;
    }

    boolean hasIndicConjunctLinkerBefore(int pos, int regionStart) {
      if (text == null) {
        return false;
      }
      int start = Math.max(0, Math.min(regionStart, text.length()));
      if (pos <= start || pos > text.length()) {
        return false;
      }
      ensureIndicConjunctData();
      return indicConjunctLinkerStartBefore[pos] >= start;
    }

    boolean hasIndicConjunctSequenceBefore(int pos, int regionStart) {
      if (text == null) {
        return false;
      }
      int start = Math.max(0, Math.min(regionStart, text.length()));
      if (pos <= start || pos > text.length()) {
        return false;
      }
      ensureIndicConjunctData();
      return indicConjunctSequenceStartBefore[pos] >= start;
    }

    private void ensureRegionalIndicatorRunData() {
      if (regionalIndicatorStartsBefore != null) {
        return;
      }
      regionalIndicatorStartsBefore = new int[text.length() + 1];
      regionalIndicatorRunStartBefore = new int[text.length() + 1];
      int runStart = 0;
      int runLength = 0;
      int pos = 0;
      while (pos < text.length()) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        long decoded = text.decodeForward(pos);
        int cp = InputScanner.codePoint(decoded);
        int next = InputScanner.position(decoded);
        if (isRegionalIndicator(cp)) {
          if (runLength == 0) {
            runStart = pos;
          }
          runLength++;
          for (int i = pos + 1; i <= next; i++) {
            regionalIndicatorStartsBefore[i] = runLength;
            regionalIndicatorRunStartBefore[i] = runStart;
          }
        } else {
          runLength = 0;
        }
        pos = next;
      }
    }

    private void ensureIndicConjunctData() {
      if (indicConjunctLinkerStartBefore != null) {
        return;
      }
      indicConjunctSequenceStartBefore = new int[text.length() + 1];
      indicConjunctLinkerStartBefore = new int[text.length() + 1];
      Arrays.fill(indicConjunctSequenceStartBefore, -1);
      Arrays.fill(indicConjunctLinkerStartBefore, -1);

      int sequenceStart = -1;
      boolean linkerSeen = false;
      int pos = 0;
      while (pos < text.length()) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        long decoded = text.decodeForward(pos);
        int cp = InputScanner.codePoint(decoded);
        int next = InputScanner.position(decoded);
        if (isIndicConjunctConsonant(cp)) {
          sequenceStart = pos;
          linkerSeen = false;
        } else if (isIndicConjunctLinker(cp)) {
          if (sequenceStart >= 0) {
            linkerSeen = true;
          }
        } else if (!isIndicConjunctExtend(cp)) {
          sequenceStart = -1;
          linkerSeen = false;
        }
        int activeSequenceStart = sequenceStart;
        int linkerStart = linkerSeen ? sequenceStart : -1;
        for (int i = pos + 1; i <= next; i++) {
          indicConjunctSequenceStartBefore[i] = activeSequenceStart;
          indicConjunctLinkerStartBefore[i] = linkerStart;
        }
        pos = next;
      }
    }

    private void ensureExtendedPictographicData() {
      if (extendedPictographicStartBefore != null) {
        return;
      }
      extendedPictographicStartBefore = new int[text.length() + 1];
      extendedPictographicPrependBefore = new int[text.length() + 1];
      Arrays.fill(extendedPictographicStartBefore, -1);
      Arrays.fill(extendedPictographicPrependBefore, -1);

      int visiblePictographicStart = -1;
      int visiblePrependStart = -1;
      int pos = 0;
      while (pos < text.length()) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        long decoded = text.decodeForward(pos);
        int cp = InputScanner.codePoint(decoded);
        int next = InputScanner.position(decoded);
        if (containsCodePoint(EXTENDED_PICTOGRAPHIC, cp)) {
          visiblePictographicStart = pos;
          visiblePrependStart = immediatePrependStartBefore(text, pos);
        } else if (!isGraphemeExtend(cp)) {
          visiblePictographicStart = -1;
          visiblePrependStart = -1;
        }
        for (int i = pos + 1; i <= next; i++) {
          extendedPictographicStartBefore[i] = visiblePictographicStart;
          extendedPictographicPrependBefore[i] = visiblePrependStart;
        }
        pos = next;
      }
    }
  }

  static int visitKeyVariantBits() {
    return VISIT_KEY_VARIANT_BITS;
  }

  static int visitVariant(
      String text,
      int pos,
      int graphemeStart,
      int consumeRegionStart,
      int graphemeConsumeEndPos,
      Context graphemeContext) {
    int start = Math.max(consumeRegionStart, graphemeStart);
    if (pos < 0 || pos >= graphemeConsumeEndPos || pos >= text.length()) {
      return 0;
    }

    int variant = 0;
    char ch = text.charAt(pos);
    if (Character.isLowSurrogate(ch)
        && hasHighSurrogateBeforeLowSurrogateInRegion(text, pos, start)) {
      variant |= LOW_SURROGATE_PAIR_VISIBLE;
    }
    if (graphemeContext.hasExtendedPictographicBefore(pos, start)) {
      variant |= EXTENDED_PICTOGRAPHIC_VISIBLE;
    }
    int cp = graphemeCodePointAt(text, pos, graphemeConsumeEndPos);
    int scalarEnd = nextRegionLocalScalarEnd(text, pos, graphemeConsumeEndPos);
    int nextCp = graphemeCodePointAt(text, scalarEnd, graphemeConsumeEndPos);
    if (graphemeContext.hasIndicConjunctSequenceBefore(pos, start)) {
      variant |= INDIC_CONJUNCT_SEQUENCE_VISIBLE;
    }
    if ((isIndicConjunctConsonant(cp) && graphemeContext.hasIndicConjunctLinkerBefore(pos, start))
        || (isIndicConjunctConsonant(nextCp)
            && graphemeContext.hasIndicConjunctLinkerBefore(scalarEnd, start))) {
      variant |= INDIC_CONJUNCT_LINKER_VISIBLE;
    }
    if (isRegionalIndicator(cp)
        && !graphemeContext.hasEvenRegionalIndicatorsBefore(scalarEnd, start)) {
      variant |= REGIONAL_INDICATOR_ODD;
    }
    return variant;
  }

  static int visitVariant(
      InputScanner text,
      int pos,
      int graphemeStart,
      int consumeRegionStart,
      int graphemeConsumeEndPos,
      Context graphemeContext) {
    if (text instanceof StringInputScanner stringInput) {
      return visitVariant(
          stringInput.text(),
          pos,
          graphemeStart,
          consumeRegionStart,
          graphemeConsumeEndPos,
          graphemeContext);
    }
    int start = Math.max(consumeRegionStart, graphemeStart);
    if (pos < 0 || pos >= graphemeConsumeEndPos || pos >= text.length()) {
      return 0;
    }
    int variant = 0;
    if (graphemeContext.hasExtendedPictographicBefore(pos, start)) {
      variant |= EXTENDED_PICTOGRAPHIC_VISIBLE;
    }
    int codePoint = inputCodePointAt(text, pos, graphemeConsumeEndPos, true);
    int scalarEnd = inputNextPos(text, pos, graphemeConsumeEndPos, true);
    int nextCodePoint = inputCodePointAt(text, scalarEnd, graphemeConsumeEndPos, true);
    if (graphemeContext.hasIndicConjunctSequenceBefore(pos, start)) {
      variant |= INDIC_CONJUNCT_SEQUENCE_VISIBLE;
    }
    if ((isIndicConjunctConsonant(codePoint)
            && graphemeContext.hasIndicConjunctLinkerBefore(pos, start))
        || (isIndicConjunctConsonant(nextCodePoint)
            && graphemeContext.hasIndicConjunctLinkerBefore(scalarEnd, start))) {
      variant |= INDIC_CONJUNCT_LINKER_VISIBLE;
    }
    if (isRegionalIndicator(codePoint)
        && !graphemeContext.hasEvenRegionalIndicatorsBefore(scalarEnd, start)) {
      variant |= REGIONAL_INDICATOR_ODD;
    }
    return variant;
  }

  static int inputCodePointAt(InputScanner text, int pos, int endPos, boolean graphemeSensitive) {
    if (text instanceof StringInputScanner stringInput) {
      return inputCodePointAt(stringInput.text(), pos, endPos, graphemeSensitive);
    }
    if (pos < 0 || pos >= endPos || pos >= text.length()) {
      return -1;
    }
    long decoded = text.decodeForward(pos);
    return InputScanner.position(decoded) <= endPos ? InputScanner.codePoint(decoded) : -1;
  }

  static int inputNextPos(InputScanner text, int pos, int endPos, boolean graphemeSensitive) {
    if (text instanceof StringInputScanner stringInput) {
      return inputNextPos(stringInput.text(), pos, endPos, graphemeSensitive);
    }
    if (pos < 0 || pos >= endPos || pos >= text.length()) {
      return endPos + 1;
    }
    int next = InputScanner.position(text.decodeForward(pos));
    return next <= endPos ? next : endPos + 1;
  }

  static int graphemeNextPos(InputScanner text, int pos, int endPos) {
    return inputNextPos(text, pos, endPos, true);
  }

  static int inputCodePointAt(String text, int pos, int endPos, boolean graphemeSensitive) {
    if (pos < 0 || pos >= endPos || pos >= text.length()) {
      return -1;
    }
    if (!graphemeSensitive) {
      return text.codePointAt(pos);
    }
    char ch = text.charAt(pos);
    if (Character.isHighSurrogate(ch)
        && pos + 1 < endPos
        && pos + 1 < text.length()
        && Character.isLowSurrogate(text.charAt(pos + 1))) {
      return Character.toCodePoint(ch, text.charAt(pos + 1));
    }
    if (Character.isHighSurrogate(ch)
        && pos + 1 < text.length()
        && Character.isLowSurrogate(text.charAt(pos + 1))) {
      return -1;
    }
    return ch;
  }

  static int inputNextPos(String text, int pos, int endPos, boolean graphemeSensitive) {
    if (pos < 0 || pos >= endPos || pos >= text.length()) {
      return endPos + 1;
    }
    if (!graphemeSensitive) {
      return pos + Character.charCount(text.codePointAt(pos));
    }
    int cp = inputCodePointAt(text, pos, endPos, true);
    if (cp < 0) {
      return endPos + 1;
    }
    if (Character.isSupplementaryCodePoint(cp)) {
      return pos + 2;
    }
    return pos + 1;
  }

  static int graphemeCodePointAt(String text, int pos, int graphemeConsumeEndPos) {
    if (pos < 0 || pos >= graphemeConsumeEndPos || pos >= text.length()) {
      return -1;
    }
    char ch = text.charAt(pos);
    if (Character.isHighSurrogate(ch)
        && pos + 1 < graphemeConsumeEndPos
        && pos + 1 < text.length()
        && Character.isLowSurrogate(text.charAt(pos + 1))) {
      return Character.toCodePoint(ch, text.charAt(pos + 1));
    }
    return ch;
  }

  static int graphemeNextPos(String text, int pos, int graphemeConsumeEndPos) {
    if (pos < 0 || pos >= graphemeConsumeEndPos || pos >= text.length()) {
      return graphemeConsumeEndPos + 1;
    }
    return nextRegionLocalScalarEnd(text, pos, graphemeConsumeEndPos);
  }

  static int nextRegionLocalScalarEnd(String text, int pos, int endPos) {
    if (pos < 0 || pos >= endPos || pos >= text.length()) {
      return endPos + 1;
    }
    char ch = text.charAt(pos);
    if (Character.isHighSurrogate(ch)
        && pos + 1 < endPos
        && pos + 1 < text.length()
        && Character.isLowSurrogate(text.charAt(pos + 1))) {
      return pos + 2;
    }
    return pos + 1;
  }

  static int boundaryFlags(
      InputScanner text,
      int pos,
      Context graphemeContext,
      int matchStart,
      int regionStart,
      boolean consumedInput,
      int boundaryEndPos) {
    if (text instanceof Utf8InputScanner) {
      if (isGraphemeBoundaryContextEdge(pos, regionStart, boundaryEndPos)
          || isGraphemeClusterBoundary(text, pos, regionStart, graphemeContext)) {
        return EmptyOp.GRAPHEME_CLUSTER_BOUNDARY | EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
      }
      return 0;
    }
    return boundaryFlags(
        ((StringInputScanner) text).text(),
        pos,
        graphemeContext,
        matchStart,
        regionStart,
        consumedInput,
        boundaryEndPos);
  }

  static int boundaryFlags(
      String text,
      int pos,
      Context graphemeContext,
      int matchStart,
      int regionStart,
      boolean consumedInput,
      int boundaryEndPos) {
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.record();
    }
    int flags = 0;
    if (isGraphemeBoundaryContextEdge(pos, regionStart, boundaryEndPos)) {
      flags |= EmptyOp.GRAPHEME_CLUSTER_BOUNDARY | EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
    } else if (isRegionStartSplitSurrogateBoundary(text, pos, regionStart)
        || isRegionEndSplitSurrogateBoundary(text, pos, boundaryEndPos)) {
      flags |= EmptyOp.GRAPHEME_CLUSTER_BOUNDARY | EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
    } else if (isAfterRegionStartSplitLowSurrogateBoundary(text, pos, regionStart)) {
      flags |= EmptyOp.GRAPHEME_CLUSTER_BOUNDARY;
      if (!suppressesConsumedSplitLowSurrogateExplicitBoundary(
          text, pos, boundaryEndPos, consumedInput)) {
        flags |= EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
      }
    } else if (consumedInput
        && isStandaloneZwjBeforePictographicBoundary(text, pos, regionStart, graphemeContext)) {
      flags |= EmptyOp.GRAPHEME_CLUSTER_BOUNDARY;
      if (!isAfterPairedExtendedPictographicZwj(text, pos, regionStart, graphemeContext)) {
        flags |= EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
      }
    } else if (isGraphemeClusterBoundary(text, pos, regionStart, graphemeContext)) {
      flags |= EmptyOp.GRAPHEME_CLUSTER_BOUNDARY;
      if (!isAfterPairedExtendedPictographicZwj(text, pos, regionStart, graphemeContext)
          || !isStandaloneZwjBeforePictographicBoundary(text, pos, regionStart, graphemeContext)) {
        flags |= EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
      }
    } else if (isLowHighSurrogateBoundary(text, pos)
        && matchStart > 0
        && pos > matchStart
        && !hasHighSurrogateBeforeLowSurrogateInRegion(text, pos - 1, regionStart)) {
      flags |= EmptyOp.GRAPHEME_CLUSTER_BOUNDARY | EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
    } else if (startsAtLowSurrogate(text, matchStart)
        && !hasHighSurrogateBeforeLowSurrogateInRegion(text, matchStart, regionStart)
        && pos == matchStart + 1) {
      flags |= EmptyOp.GRAPHEME_CLUSTER_BOUNDARY;
      if (!hasGraphemeExtendAt(text, pos)
          && !suppressesRegionStartSplitExplicitBoundary(
              text, regionStart, matchStart, consumedInput)) {
        flags |= EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
      }
    } else if (startsAtStandaloneZwj(text, matchStart, regionStart)
        && pos == matchStart + 1
        && !hasGraphemeExtendAt(text, pos)) {
      flags |= EmptyOp.GRAPHEME_CLUSTER_BOUNDARY;
      if (!consumedInput
          && !suppressesRegionStartSplitExplicitBoundary(
              text, regionStart, matchStart, consumedInput)) {
        flags |= EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
      }
    } else if (!consumedInput
        && (isLowSurrogateBeforeZwjBoundary(text, pos, matchStart, regionStart)
            || isStandaloneZwjAfterLowSurrogateBoundary(text, pos, matchStart, regionStart))
        && !isAfterRegionStartSplitLowSurrogate(text, pos, regionStart)
        && !hasGraphemeExtendAt(text, pos)) {
      flags |= EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
    } else if (!consumedInput
        && isStandaloneZwjBeforePictographicBoundary(text, pos, regionStart, graphemeContext)) {
      flags |= EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
    } else if (isExplicitGraphemeClusterBoundary(text, pos, matchStart, regionStart)) {
      flags |= EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
    }
    return flags;
  }

  static boolean isGraphemeClusterBoundary(String text, int pos) {
    return isGraphemeClusterBoundary(text, pos, 0, Context.create(text, true));
  }

  static boolean isGraphemeClusterBoundary(
      InputScanner text, int pos, int regionStart, Context graphemeContext) {
    if (text instanceof StringInputScanner stringInput) {
      return isGraphemeClusterBoundary(stringInput.text(), pos, regionStart, graphemeContext);
    }
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.record();
    }
    if (pos < 0 || pos > text.length() || !text.isCodePointBoundary(pos)) {
      return false;
    }
    if (pos == 0 || pos == text.length()) {
      return true;
    }
    int previous = text.codePointBefore(pos);
    int next = text.codePointAt(pos);
    if (previous == '\r' && next == '\n') {
      return false;
    }
    if (isGraphemeControl(previous) || isGraphemeControl(next)) {
      return true;
    }
    if (isGraphemeExtend(next) || isGraphemePrepend(previous)) {
      return false;
    }
    if (isHangulGraphemeContinuation(previous, next)) {
      return false;
    }
    Context context = graphemeContext != null ? graphemeContext : Context.create(text, true);
    if (isIndicConjunctConsonant(next) && context.hasIndicConjunctLinkerBefore(pos, regionStart)) {
      return false;
    }
    int previousStart = InputScanner.position(text.decodeBackward(pos));
    if (previous == 0x200D
        && containsCodePoint(EXTENDED_PICTOGRAPHIC, next)
        && context.hasExtendedPictographicBefore(previousStart, regionStart)) {
      return false;
    }
    if (isRegionalIndicator(previous) && isRegionalIndicator(next)) {
      return context.hasEvenRegionalIndicatorsBefore(pos, regionStart);
    }
    return true;
  }

  static boolean isGraphemeClusterBoundary(String text, int pos, Context graphemeContext) {
    return isGraphemeClusterBoundary(text, pos, 0, graphemeContext);
  }

  static boolean isGraphemeClusterBoundary(
      String text, int pos, int regionStart, Context graphemeContext) {
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.record();
    }
    if (pos < 0 || pos > text.length()) {
      return false;
    }
    if (pos == 0 || pos == text.length()) {
      return true;
    }
    char prevChar = text.charAt(pos - 1);
    char nextChar = text.charAt(pos);
    if (Character.isHighSurrogate(prevChar) && Character.isLowSurrogate(nextChar)) {
      return false;
    }
    if (prevChar == '\r' && nextChar == '\n') {
      return false;
    }
    if (isGraphemeControl(prevChar) || isGraphemeControl(nextChar)) {
      return true;
    }
    if (Character.isHighSurrogate(prevChar) && !Character.isLowSurrogate(nextChar)) {
      return true;
    }
    if (Character.isLowSurrogate(prevChar)
        && !hasHighSurrogateBeforeLowSurrogateInRegion(text, pos - 1, regionStart)) {
      return true;
    }
    int prev = text.codePointBefore(pos);
    int next = text.codePointAt(pos);
    if (isGraphemePrepend(prev) && isUnpairedSurrogateAt(text, pos)) {
      return true;
    }
    if (isGraphemeExtend(next) || isGraphemePrepend(prev)) {
      return false;
    }
    if (isHangulGraphemeContinuation(prev, next)) {
      return false;
    }
    Context context = graphemeContext != null ? graphemeContext : Context.create(text, true);
    if (isIndicConjunctConsonant(next) && context.hasIndicConjunctLinkerBefore(pos, regionStart)) {
      return false;
    }
    if (prev == 0x200D
        && containsCodePoint(EXTENDED_PICTOGRAPHIC, next)
        && context.hasExtendedPictographicBefore(pos - 1, regionStart)) {
      return false;
    }
    if (isRegionalIndicator(prev) && isRegionalIndicator(next)) {
      return context.hasEvenRegionalIndicatorsBefore(pos, regionStart);
    }
    return true;
  }

  static boolean hasGraphemeExtendAt(String text, int pos) {
    return pos >= 0 && pos < text.length() && isGraphemeExtend(text.codePointAt(pos));
  }

  static boolean isExtendedPictographicAt(String text, int pos) {
    return pos >= 0
        && pos < text.length()
        && containsCodePoint(EXTENDED_PICTOGRAPHIC, text.codePointAt(pos));
  }

  private static boolean isGraphemeBoundaryContextEdge(
      int pos, int boundaryStart, int boundaryEnd) {
    return pos == boundaryStart || pos == boundaryEnd;
  }

  private static boolean isExplicitGraphemeClusterBoundary(
      String text, int pos, int matchStart, int regionStart) {
    if (matchStart <= 0 || pos <= matchStart || pos <= 1 || pos >= text.length()) {
      return false;
    }
    char prevChar = text.charAt(pos - 1);
    char nextChar = text.charAt(pos);
    if (Character.isLowSurrogate(prevChar) && Character.isHighSurrogate(nextChar)) {
      return !hasHighSurrogateBeforeLowSurrogateInRegion(text, pos - 1, regionStart);
    }
    if (prevChar == '\r' && nextChar == '\n') {
      return true;
    }
    if (isGraphemeExtend(nextChar)
        && Character.isLowSurrogate(prevChar)
        && hasHighSurrogateBeforeLowSurrogateInRegion(text, pos - 1, regionStart)) {
      return false;
    }
    int prev = text.codePointBefore(pos);
    int next = text.codePointAt(pos);
    return isGraphemeExtend(next) || isGraphemePrepend(prev);
  }

  private static boolean isLowHighSurrogateBoundary(String text, int pos) {
    return pos > 0
        && pos < text.length()
        && Character.isLowSurrogate(text.charAt(pos - 1))
        && Character.isHighSurrogate(text.charAt(pos));
  }

  private static boolean isRegionStartSplitSurrogateBoundary(
      String text, int pos, int regionStart) {
    return pos == regionStart
        && regionStart > 0
        && regionStart < text.length()
        && Character.isHighSurrogate(text.charAt(regionStart - 1))
        && Character.isLowSurrogate(text.charAt(regionStart));
  }

  private static boolean isAfterRegionStartSplitLowSurrogateBoundary(
      String text, int pos, int regionStart) {
    return pos == regionStart + 1
        && isRegionStartSplitSurrogateBoundary(text, regionStart, regionStart);
  }

  private static boolean isRegionEndSplitSurrogateBoundary(String text, int pos, int regionEnd) {
    return pos == regionEnd
        && regionEnd > 0
        && regionEnd < text.length()
        && Character.isHighSurrogate(text.charAt(regionEnd - 1))
        && Character.isLowSurrogate(text.charAt(regionEnd));
  }

  private static boolean suppressesConsumedSplitLowSurrogateExplicitBoundary(
      String text, int pos, int boundaryEndPos, boolean consumedInput) {
    if (!consumedInput || pos >= boundaryEndPos) {
      return false;
    }
    if (hasGraphemeExtendAt(text, pos)) {
      return true;
    }
    return isRegionalIndicator(regionLocalCodePointAt(text, pos, boundaryEndPos));
  }

  private static int regionLocalCodePointAt(String text, int pos, int endPos) {
    if (pos < 0 || pos >= endPos || pos >= text.length()) {
      return -1;
    }
    char ch = text.charAt(pos);
    if (Character.isHighSurrogate(ch)
        && pos + 1 < endPos
        && pos + 1 < text.length()
        && Character.isLowSurrogate(text.charAt(pos + 1))) {
      return Character.toCodePoint(ch, text.charAt(pos + 1));
    }
    return ch;
  }

  private static boolean isLowSurrogateBeforeZwjBoundary(
      String text, int pos, int matchStart, int regionStart) {
    return pos == matchStart
        && pos > 0
        && pos < text.length()
        && Character.isLowSurrogate(text.charAt(pos - 1))
        && text.charAt(pos) == 0x200D
        && !hasHighSurrogateBeforeLowSurrogateInRegion(text, pos - 1, regionStart);
  }

  private static boolean suppressesRegionStartSplitExplicitBoundary(
      String text, int regionStart, int matchStart, boolean consumedInput) {
    return consumedInput
        && matchStart == regionStart + 1
        && isRegionStartSplitSurrogateBoundary(text, regionStart, regionStart);
  }

  private static boolean isStandaloneZwjAfterLowSurrogateBoundary(
      String text, int pos, int matchStart, int regionStart) {
    return pos == matchStart
        && pos > 1
        && pos < text.length()
        && text.charAt(pos - 1) == 0x200D
        && Character.isLowSurrogate(text.charAt(pos - 2))
        && !hasHighSurrogateBeforeLowSurrogateInRegion(text, pos - 2, regionStart);
  }

  private static boolean isStandaloneZwjBeforePictographicBoundary(
      String text, int pos, int regionStart, Context graphemeContext) {
    Context context = graphemeContext != null ? graphemeContext : Context.create(text, true);
    return pos > 0
        && pos < text.length()
        && text.charAt(pos - 1) == 0x200D
        && containsCodePoint(EXTENDED_PICTOGRAPHIC, text.codePointAt(pos))
        && !isAfterRegionStartSplitLowSurrogate(text, pos - 1, regionStart)
        && !context.hasExtendedPictographicBefore(pos - 1, regionStart);
  }

  private static boolean isAfterPairedExtendedPictographicZwj(
      String text, int pos, int regionStart, Context graphemeContext) {
    Context context = graphemeContext != null ? graphemeContext : Context.create(text, true);
    int lowSurrogatePos = pos - 2;
    if (regionStart <= lowSurrogatePos && regionStart > 0) {
      return false;
    }
    return pos > 2
        && text.charAt(pos - 1) == 0x200D
        && Character.isLowSurrogate(text.charAt(lowSurrogatePos))
        && Character.isHighSurrogate(text.charAt(pos - 3))
        && context.hasExtendedPictographicBefore(pos - 1, regionStart);
  }

  private static boolean isAfterRegionStartSplitLowSurrogate(
      String text, int pos, int regionStart) {
    return pos == regionStart + 1
        && isRegionStartSplitSurrogateBoundary(text, regionStart, regionStart);
  }

  private static int immediatePrependStartBefore(InputScanner text, int pos) {
    if (pos <= 0) {
      return -1;
    }
    long decoded = text.decodeBackward(pos);
    if (!isGraphemePrepend(InputScanner.codePoint(decoded))) {
      return -1;
    }
    return InputScanner.position(decoded);
  }

  private static boolean hasHighSurrogateBeforeLowSurrogateInRegion(
      String text, int lowSurrogatePos, int regionStart) {
    return lowSurrogatePos > regionStart
        && lowSurrogatePos > 0
        && Character.isHighSurrogate(text.charAt(lowSurrogatePos - 1));
  }

  private static boolean startsAtLowSurrogate(String text, int matchStart) {
    return matchStart >= 0
        && matchStart < text.length()
        && Character.isLowSurrogate(text.charAt(matchStart));
  }

  private static boolean startsAtStandaloneZwj(String text, int matchStart, int regionStart) {
    return matchStart >= 0
        && matchStart < text.length()
        && text.charAt(matchStart) == 0x200D
        && (matchStart == 0
            || matchStart <= regionStart
            || !Character.isLowSurrogate(text.charAt(matchStart - 1))
            || !hasHighSurrogateBeforeLowSurrogateInRegion(text, matchStart - 1, regionStart));
  }

  private static boolean isGraphemeExtend(int c) {
    return isCombiningMark(c) || isEmojiModifier(c) || c == 0x200D;
  }

  private static boolean isUnpairedSurrogateAt(String text, int pos) {
    char c = text.charAt(pos);
    if (Character.isLowSurrogate(c)) {
      return true;
    }
    return Character.isHighSurrogate(c)
        && (pos + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(pos + 1)));
  }

  private static boolean isGraphemeControl(int c) {
    int type = Character.getType(c);
    return type == Character.CONTROL
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR;
  }

  private static boolean isCombiningMark(int c) {
    int type = Character.getType(c);
    return type == Character.NON_SPACING_MARK
        || type == Character.ENCLOSING_MARK
        || type == Character.COMBINING_SPACING_MARK;
  }

  private static boolean isEmojiModifier(int c) {
    return 0x1F3FB <= c && c <= 0x1F3FF;
  }

  private static boolean isGraphemePrepend(int c) {
    return (0x0600 <= c && c <= 0x0605)
        || c == 0x06DD
        || c == 0x070F
        || (0x0890 <= c && c <= 0x0891)
        || c == 0x08E2
        || c == 0x110BD
        || c == 0x110CD;
  }

  private static boolean isHangulGraphemeContinuation(int prev, int next) {
    return (isHangulL(prev)
            && (isHangulL(next) || isHangulV(next) || isHangulLv(next) || isHangulLvt(next)))
        || ((isHangulV(prev) || isHangulLv(prev)) && (isHangulV(next) || isHangulT(next)))
        || ((isHangulT(prev) || isHangulLvt(prev)) && isHangulT(next));
  }

  private static boolean isHangulL(int c) {
    return (0x1100 <= c && c <= 0x115F) || (0xA960 <= c && c <= 0xA97C);
  }

  private static boolean isHangulV(int c) {
    return (0x1160 <= c && c <= 0x11A7) || (0xD7B0 <= c && c <= 0xD7C6);
  }

  private static boolean isHangulT(int c) {
    return (0x11A8 <= c && c <= 0x11FF) || (0xD7CB <= c && c <= 0xD7FB);
  }

  private static boolean isHangulLv(int c) {
    return 0xAC00 <= c && c <= 0xD7A3 && (c - 0xAC00) % 28 == 0;
  }

  private static boolean isHangulLvt(int c) {
    return 0xAC00 <= c && c <= 0xD7A3 && (c - 0xAC00) % 28 != 0;
  }

  private static boolean isRegionalIndicator(int c) {
    return 0x1F1E6 <= c && c <= 0x1F1FF;
  }

  private static boolean isIndicConjunctConsonant(int c) {
    return (0x0915 <= c && c <= 0x0939)
        || (0x0958 <= c && c <= 0x095F)
        || (0x0978 <= c && c <= 0x097F)
        || (0x0995 <= c && c <= 0x09A8)
        || (0x09AA <= c && c <= 0x09B0)
        || c == 0x09B2
        || (0x09B6 <= c && c <= 0x09B9)
        || (0x09DC <= c && c <= 0x09DD)
        || c == 0x09DF
        || (0x09F0 <= c && c <= 0x09F1)
        || (0x0A95 <= c && c <= 0x0AA8)
        || (0x0AAA <= c && c <= 0x0AB0)
        || (0x0AB2 <= c && c <= 0x0AB3)
        || (0x0AB5 <= c && c <= 0x0AB9)
        || c == 0x0AF9
        || (0x0B15 <= c && c <= 0x0B28)
        || (0x0B2A <= c && c <= 0x0B30)
        || (0x0B32 <= c && c <= 0x0B33)
        || (0x0B35 <= c && c <= 0x0B39)
        || (0x0B5C <= c && c <= 0x0B5D)
        || c == 0x0B5F
        || c == 0x0B71
        || (0x0C15 <= c && c <= 0x0C28)
        || (0x0C2A <= c && c <= 0x0C39)
        || (0x0C58 <= c && c <= 0x0C5A)
        || (0x0D15 <= c && c <= 0x0D3A);
  }

  private static boolean isIndicConjunctLinker(int c) {
    return c == 0x094D || c == 0x09CD || c == 0x0ACD || c == 0x0B4D || c == 0x0C4D || c == 0x0D4D;
  }

  private static boolean isIndicConjunctExtend(int c) {
    return isGraphemeExtend(c) || (0xE0020 <= c && c <= 0xE007F);
  }

  private static boolean containsCodePoint(int[][] ranges, int c) {
    int lo = 0;
    int hi = ranges.length - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      int[] range = ranges[mid];
      if (c < range[0]) {
        hi = mid - 1;
      } else if (c > range[1]) {
        lo = mid + 1;
      } else {
        return true;
      }
    }
    return false;
  }

  private GraphemeSupport() {
    throw new AssertionError("non-instantiable");
  }
}
