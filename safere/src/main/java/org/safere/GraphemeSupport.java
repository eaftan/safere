// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Arrays;
import java.util.Objects;

/** Grapheme segmentation state and SafeRE's grapheme-boundary policy. */
final class GraphemeSupport {
  private static final int[][] EXTENDED_PICTOGRAPHIC =
      UnicodeProperties.lookupBinaryProperty("Extended_Pictographic");

  // UAX #29 properties generated directly from the pinned Unicode Character Database.
  private static final int[][] GCB_CONTROL = graphemeTable("Control");
  private static final int[][] GCB_EXTEND = graphemeTable("Extend");
  private static final int[][] GCB_PREPEND = graphemeTable("Prepend");
  private static final int[][] GCB_SPACING_MARK = graphemeTable("SpacingMark");
  private static final int[][] GCB_L = graphemeTable("L");
  private static final int[][] GCB_V = graphemeTable("V");
  private static final int[][] GCB_T = graphemeTable("T");
  private static final int[][] GCB_LV = graphemeTable("LV");
  private static final int[][] GCB_LVT = graphemeTable("LVT");
  private static final int[][] INCB_LINKER = graphemeTable("InCB_Linker");
  private static final int[][] INCB_CONSONANT = graphemeTable("InCB_Consonant");
  private static final int[][] INCB_EXTEND = graphemeTable("InCB_Extend");

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
  static class Context {
    private static final Context EMPTY = new Context(null);

    private final InputScanner text;
    private int[] regionalIndicatorStartsBefore;
    private int[] regionalIndicatorRunStartBefore;
    private int[] extendedPictographicStartBefore;
    private int[] indicConjunctSequenceStartBefore;
    private int[] indicConjunctLinkerStartBefore;

    private Context(InputScanner text) {
      this.text = text;
    }

    static Context createRegion(InputScanner text, int start, int end) {
      Objects.checkFromToIndex(start, end, text.length());
      if (text instanceof StringInputScanner stringInput) {
        return new StringRegionContext(stringInput, start, end);
      }
      return new RegionContext(((Utf8InputScanner) text).slice(start, end), start);
    }

    int length() {
      return text.length();
    }

    long decodeForward(int pos) {
      return text.decodeForward(pos);
    }

    static Context create(CharSequence text, boolean includeGraphemeClusterBoundary) {
      if (!includeGraphemeClusterBoundary) {
        return EMPTY;
      }
      return new Context(new StringInputScanner(text.toString()));
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
      int start = Math.max(0, Math.min(regionStart, length()));
      if (pos <= start || pos > length()) {
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
      int start = Math.max(0, Math.min(regionStart, length()));
      if (pos <= start || pos > length()) {
        return false;
      }
      ensureExtendedPictographicData();
      int pictographicStart = extendedPictographicStartBefore[pos];
      if (pictographicStart < start) {
        return false;
      }
      return true;
    }

    boolean hasIndicConjunctLinkerBefore(int pos, int regionStart) {
      if (text == null) {
        return false;
      }
      int start = Math.max(0, Math.min(regionStart, length()));
      if (pos <= start || pos > length()) {
        return false;
      }
      ensureIndicConjunctData();
      return indicConjunctLinkerStartBefore[pos] >= start;
    }

    boolean hasIndicConjunctSequenceBefore(int pos, int regionStart) {
      if (text == null) {
        return false;
      }
      int start = Math.max(0, Math.min(regionStart, length()));
      if (pos <= start || pos > length()) {
        return false;
      }
      ensureIndicConjunctData();
      return indicConjunctSequenceStartBefore[pos] >= start;
    }

    private void ensureRegionalIndicatorRunData() {
      if (regionalIndicatorStartsBefore != null) {
        return;
      }
      regionalIndicatorStartsBefore = new int[length() + 1];
      regionalIndicatorRunStartBefore = new int[length() + 1];
      int runStart = 0;
      int runLength = 0;
      int pos = 0;
      while (pos < length()) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        long decoded = decodeForward(pos);
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
      indicConjunctSequenceStartBefore = new int[length() + 1];
      indicConjunctLinkerStartBefore = new int[length() + 1];
      Arrays.fill(indicConjunctSequenceStartBefore, -1);
      Arrays.fill(indicConjunctLinkerStartBefore, -1);

      int sequenceStart = -1;
      boolean linkerSeen = false;
      int pos = 0;
      while (pos < length()) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        long decoded = decodeForward(pos);
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
      extendedPictographicStartBefore = new int[length() + 1];
      Arrays.fill(extendedPictographicStartBefore, -1);

      int visiblePictographicStart = -1;
      int pos = 0;
      while (pos < length()) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        long decoded = decodeForward(pos);
        int cp = InputScanner.codePoint(decoded);
        int next = InputScanner.position(decoded);
        if (containsCodePoint(EXTENDED_PICTOGRAPHIC, cp)) {
          visiblePictographicStart = pos;
        } else if (!containsCodePoint(GCB_EXTEND, cp)) {
          // GB11 requires Extended_Pictographic Extend* before the ZWJ. GB9/GB9a
          // keep ZWJ and SpacingMark in the cluster, but they interrupt this prefix.
          visiblePictographicStart = -1;
        }
        for (int i = pos + 1; i <= next; i++) {
          extendedPictographicStartBefore[i] = visiblePictographicStart;
        }
        pos = next;
      }
    }
  }

  /** A region context uses local coordinates while execution keeps original-input positions. */
  private static class RegionContext extends Context {
    final int offset;

    RegionContext(InputScanner text, int offset) {
      super(text);
      this.offset = offset;
    }
  }

  /** Immutable String region view; segmentation never copies or reads outside the region. */
  private static final class StringRegionContext extends RegionContext implements CharSequence {
    private final String input;
    private final int regionLength;

    StringRegionContext(StringInputScanner text, int start, int end) {
      super(text, start);
      input = text.text();
      regionLength = end - start;
    }

    @Override
    public int length() {
      return regionLength;
    }

    @Override
    public char charAt(int index) {
      Objects.checkIndex(index, regionLength);
      return input.charAt(offset + index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      Objects.checkFromToIndex(start, end, regionLength);
      return input.subSequence(offset + start, offset + end);
    }

    @Override
    public String toString() {
      return input.substring(offset, offset + regionLength);
    }

    @Override
    long decodeForward(int pos) {
      int cp = graphemeCodePointAt(this, pos, regionLength);
      return InputScanner.decoded(cp, pos + Character.charCount(cp));
    }
  }

  private static int codePointAt(CharSequence text, int pos) {
    return text instanceof String string
        ? string.codePointAt(pos)
        : Character.codePointAt(text, pos);
  }

  private static int codePointBefore(CharSequence text, int pos) {
    return text instanceof String string
        ? string.codePointBefore(pos)
        : Character.codePointBefore(text, pos);
  }

  private static int codePointAt(InputScanner text, int pos) {
    return text.codePointAt(pos);
  }

  private static int codePointBefore(InputScanner text, int pos) {
    return text.codePointBefore(pos);
  }

  static int visitKeyVariantBits() {
    return VISIT_KEY_VARIANT_BITS;
  }

  static int visitVariant(
      CharSequence text,
      int pos,
      int graphemeStart,
      int consumeRegionStart,
      int endPos,
      Context graphemeContext) {
    int start = Math.max(consumeRegionStart, graphemeStart);
    if (pos < 0 || pos >= endPos || pos >= text.length()) {
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
    int cp = graphemeCodePointAt(text, pos, endPos);
    int scalarEnd = nextRegionLocalScalarEnd(text, pos, endPos);
    int nextCp = graphemeCodePointAt(text, scalarEnd, endPos);
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
      int endPos,
      Context graphemeContext) {
    if (graphemeContext instanceof StringRegionContext view) {
      return visitVariant(
          view,
          pos - view.offset,
          graphemeStart - view.offset,
          consumeRegionStart - view.offset,
          endPos - view.offset,
          graphemeContext);
    }
    if (graphemeContext instanceof RegionContext view) {
      text = graphemeContext.text;
      pos -= view.offset;
      graphemeStart -= view.offset;
      consumeRegionStart -= view.offset;
      endPos -= view.offset;
    }
    if (text instanceof StringInputScanner stringInput) {
      return visitVariant(
          stringInput.text(), pos, graphemeStart, consumeRegionStart, endPos, graphemeContext);
    }
    int start = Math.max(consumeRegionStart, graphemeStart);
    if (pos < 0 || pos >= endPos || pos >= text.length()) {
      return 0;
    }
    int variant = 0;
    if (graphemeContext.hasExtendedPictographicBefore(pos, start)) {
      variant |= EXTENDED_PICTOGRAPHIC_VISIBLE;
    }
    int codePoint = inputCodePointAt(text, pos, endPos);
    int scalarEnd = inputNextPos(text, pos, endPos);
    int nextCodePoint = inputCodePointAt(text, scalarEnd, endPos);
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

  private static int inputCodePointAt(InputScanner text, int pos, int endPos) {
    return switch (text) {
      case StringInputScanner stringInput -> graphemeCodePointAt(stringInput.text(), pos, endPos);
      case StringRegionInputScanner regionInput -> {
        if (pos < 0 || pos >= endPos || pos >= regionInput.length()) {
          yield -1;
        }
        yield InputScanner.codePoint(regionInput.decodeForward(pos, endPos));
      }
      case Utf8InputScanner utf8Input -> {
        if (pos < 0 || pos >= endPos || pos >= utf8Input.length()) {
          yield -1;
        }
        long decoded = utf8Input.decodeForward(pos);
        yield InputScanner.position(decoded) <= endPos ? InputScanner.codePoint(decoded) : -1;
      }
    };
  }

  private static int inputNextPos(InputScanner text, int pos, int endPos) {
    return switch (text) {
      case StringInputScanner stringInput -> graphemeNextPos(stringInput.text(), pos, endPos);
      case StringRegionInputScanner regionInput -> {
        if (pos < 0 || pos >= endPos || pos >= regionInput.length()) {
          yield endPos + 1;
        }
        yield InputScanner.position(regionInput.decodeForward(pos, endPos));
      }
      case Utf8InputScanner utf8Input -> {
        if (pos < 0 || pos >= endPos || pos >= utf8Input.length()) {
          yield endPos + 1;
        }
        int next = InputScanner.position(utf8Input.decodeForward(pos));
        yield next <= endPos ? next : endPos + 1;
      }
    };
  }

  static int graphemeNextPos(InputScanner text, int pos, int endPos) {
    return inputNextPos(text, pos, endPos);
  }

  static int graphemeCodePointAt(CharSequence text, int pos, int endPos) {
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

  static int graphemeNextPos(CharSequence text, int pos, int endPos) {
    if (pos < 0 || pos >= endPos || pos >= text.length()) {
      return endPos + 1;
    }
    return nextRegionLocalScalarEnd(text, pos, endPos);
  }

  static int nextRegionLocalScalarEnd(CharSequence text, int pos, int endPos) {
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
    if (graphemeContext instanceof StringRegionContext view) {
      return boundaryFlags(
          view,
          pos - view.offset,
          graphemeContext,
          matchStart - view.offset,
          regionStart - view.offset,
          consumedInput,
          boundaryEndPos - view.offset);
    }
    if (graphemeContext instanceof RegionContext view) {
      text = graphemeContext.text;
      pos -= view.offset;
      matchStart -= view.offset;
      regionStart -= view.offset;
      boundaryEndPos -= view.offset;
    }
    if (!(text instanceof StringInputScanner stringInput)) {
      if (isGraphemeBoundaryContextEdge(pos, regionStart, boundaryEndPos)
          || isGraphemeClusterBoundaryLocal(text, pos, regionStart, graphemeContext)) {
        return EmptyOp.GRAPHEME_CLUSTER_BOUNDARY | EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
      }
      return 0;
    }
    return boundaryFlags(
        stringInput.text(),
        pos,
        graphemeContext,
        matchStart,
        regionStart,
        consumedInput,
        boundaryEndPos);
  }

  static int boundaryFlags(
      CharSequence text,
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
    }
    return flags;
  }

  static boolean isGraphemeClusterBoundary(CharSequence text, int pos) {
    return isGraphemeClusterBoundary(text, pos, 0, Context.create(text, true));
  }

  static boolean isGraphemeClusterBoundary(
      InputScanner text, int pos, int regionStart, Context graphemeContext) {
    if (graphemeContext instanceof StringRegionContext view) {
      return isGraphemeClusterBoundary(
          view, pos - view.offset, regionStart - view.offset, graphemeContext);
    }
    if (graphemeContext instanceof RegionContext view) {
      text = graphemeContext.text;
      pos -= view.offset;
      regionStart -= view.offset;
    }
    return isGraphemeClusterBoundaryLocal(text, pos, regionStart, graphemeContext);
  }

  private static boolean isGraphemeClusterBoundaryLocal(
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
    int previous = codePointBefore(text, pos);
    int next = codePointAt(text, pos);
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

  static boolean isGraphemeClusterBoundary(CharSequence text, int pos, Context graphemeContext) {
    return isGraphemeClusterBoundary(text, pos, 0, graphemeContext);
  }

  static boolean isGraphemeClusterBoundary(
      CharSequence text, int pos, int regionStart, Context graphemeContext) {
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
    if (Character.isHighSurrogate(prevChar) && !Character.isLowSurrogate(nextChar)) {
      return true;
    }
    if (Character.isLowSurrogate(prevChar)
        && !hasHighSurrogateBeforeLowSurrogateInRegion(text, pos - 1, regionStart)) {
      return true;
    }
    int prev = codePointBefore(text, pos);
    int next = codePointAt(text, pos);
    if (isGraphemeControl(prev) || isGraphemeControl(next)) {
      return true;
    }
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

  static boolean hasGraphemeExtendAt(CharSequence text, int pos) {
    return pos >= 0 && pos < text.length() && isGraphemeExtend(codePointAt(text, pos));
  }

  static boolean isExtendedPictographicAt(CharSequence text, int pos) {
    return pos >= 0
        && pos < text.length()
        && containsCodePoint(EXTENDED_PICTOGRAPHIC, codePointAt(text, pos));
  }

  private static boolean isGraphemeBoundaryContextEdge(
      int pos, int boundaryStart, int boundaryEnd) {
    return pos == boundaryStart || pos == boundaryEnd;
  }

  private static boolean isLowHighSurrogateBoundary(CharSequence text, int pos) {
    return pos > 0
        && pos < text.length()
        && Character.isLowSurrogate(text.charAt(pos - 1))
        && Character.isHighSurrogate(text.charAt(pos));
  }

  private static boolean isRegionStartSplitSurrogateBoundary(
      CharSequence text, int pos, int regionStart) {
    return pos == regionStart
        && regionStart > 0
        && regionStart < text.length()
        && Character.isHighSurrogate(text.charAt(regionStart - 1))
        && Character.isLowSurrogate(text.charAt(regionStart));
  }

  private static boolean isAfterRegionStartSplitLowSurrogateBoundary(
      CharSequence text, int pos, int regionStart) {
    return pos == regionStart + 1
        && isRegionStartSplitSurrogateBoundary(text, regionStart, regionStart);
  }

  private static boolean isRegionEndSplitSurrogateBoundary(
      CharSequence text, int pos, int regionEnd) {
    return pos == regionEnd
        && regionEnd > 0
        && regionEnd < text.length()
        && Character.isHighSurrogate(text.charAt(regionEnd - 1))
        && Character.isLowSurrogate(text.charAt(regionEnd));
  }

  private static boolean suppressesConsumedSplitLowSurrogateExplicitBoundary(
      CharSequence text, int pos, int boundaryEndPos, boolean consumedInput) {
    if (!consumedInput || pos >= boundaryEndPos) {
      return false;
    }
    if (hasGraphemeExtendAt(text, pos)) {
      return true;
    }
    // Region edges are handled before this interior assertion. Classify the next scalar in
    // the grapheme context, as for Extend above, independently of which atoms may consume it.
    return isRegionalIndicator(codePointAt(text, pos));
  }

  private static boolean isLowSurrogateBeforeZwjBoundary(
      CharSequence text, int pos, int matchStart, int regionStart) {
    return pos == matchStart
        && pos > 0
        && pos < text.length()
        && Character.isLowSurrogate(text.charAt(pos - 1))
        && text.charAt(pos) == 0x200D
        && !hasHighSurrogateBeforeLowSurrogateInRegion(text, pos - 1, regionStart);
  }

  private static boolean suppressesRegionStartSplitExplicitBoundary(
      CharSequence text, int regionStart, int matchStart, boolean consumedInput) {
    return consumedInput
        && matchStart == regionStart + 1
        && isRegionStartSplitSurrogateBoundary(text, regionStart, regionStart);
  }

  private static boolean isStandaloneZwjAfterLowSurrogateBoundary(
      CharSequence text, int pos, int matchStart, int regionStart) {
    return pos == matchStart
        && pos > 1
        && pos < text.length()
        && text.charAt(pos - 1) == 0x200D
        && Character.isLowSurrogate(text.charAt(pos - 2))
        && !hasHighSurrogateBeforeLowSurrogateInRegion(text, pos - 2, regionStart);
  }

  private static boolean isStandaloneZwjBeforePictographicBoundary(
      CharSequence text, int pos, int regionStart, Context graphemeContext) {
    Context context = graphemeContext != null ? graphemeContext : Context.create(text, true);
    return pos > 0
        && pos < text.length()
        && text.charAt(pos - 1) == 0x200D
        && containsCodePoint(EXTENDED_PICTOGRAPHIC, codePointAt(text, pos))
        && !isAfterRegionStartSplitLowSurrogate(text, pos - 1, regionStart)
        && !context.hasExtendedPictographicBefore(pos - 1, regionStart);
  }

  private static boolean isAfterPairedExtendedPictographicZwj(
      CharSequence text, int pos, int regionStart, Context graphemeContext) {
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
      CharSequence text, int pos, int regionStart) {
    return pos == regionStart + 1
        && isRegionStartSplitSurrogateBoundary(text, regionStart, regionStart);
  }

  private static boolean hasHighSurrogateBeforeLowSurrogateInRegion(
      CharSequence text, int lowSurrogatePos, int regionStart) {
    return lowSurrogatePos > regionStart
        && lowSurrogatePos > 0
        && Character.isHighSurrogate(text.charAt(lowSurrogatePos - 1));
  }

  private static boolean startsAtLowSurrogate(CharSequence text, int matchStart) {
    return matchStart >= 0
        && matchStart < text.length()
        && Character.isLowSurrogate(text.charAt(matchStart));
  }

  private static boolean startsAtStandaloneZwj(CharSequence text, int matchStart, int regionStart) {
    return matchStart >= 0
        && matchStart < text.length()
        && text.charAt(matchStart) == 0x200D
        && (matchStart == 0
            || matchStart <= regionStart
            || !Character.isLowSurrogate(text.charAt(matchStart - 1))
            || !hasHighSurrogateBeforeLowSurrogateInRegion(text, matchStart - 1, regionStart));
  }

  /** Returns whether {@code c} attaches to the preceding code point (GB9 and GB9a). */
  private static boolean isGraphemeExtend(int c) {
    return c == 0x200D
        || containsCodePoint(GCB_EXTEND, c)
        || containsCodePoint(GCB_SPACING_MARK, c);
  }

  private static boolean isUnpairedSurrogateAt(CharSequence text, int pos) {
    char c = text.charAt(pos);
    if (Character.isLowSurrogate(c)) {
      return true;
    }
    return Character.isHighSurrogate(c)
        && (pos + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(pos + 1)));
  }

  private static boolean isGraphemeControl(int c) {
    if (c < 0x7F) {
      return c < 0x20;
    }
    return containsCodePoint(GCB_CONTROL, c);
  }

  private static boolean isGraphemePrepend(int c) {
    return containsCodePoint(GCB_PREPEND, c);
  }

  private static boolean isHangulGraphemeContinuation(int prev, int next) {
    return (isHangulL(prev)
            && (isHangulL(next) || isHangulV(next) || isHangulLv(next) || isHangulLvt(next)))
        || ((isHangulV(prev) || isHangulLv(prev)) && (isHangulV(next) || isHangulT(next)))
        || ((isHangulT(prev) || isHangulLvt(prev)) && isHangulT(next));
  }

  private static boolean isHangulL(int c) {
    return containsCodePoint(GCB_L, c);
  }

  private static boolean isHangulV(int c) {
    return containsCodePoint(GCB_V, c);
  }

  private static boolean isHangulT(int c) {
    return containsCodePoint(GCB_T, c);
  }

  private static boolean isHangulLv(int c) {
    return containsCodePoint(GCB_LV, c);
  }

  private static boolean isHangulLvt(int c) {
    return containsCodePoint(GCB_LVT, c);
  }

  private static boolean isRegionalIndicator(int c) {
    return 0x1F1E6 <= c && c <= 0x1F1FF;
  }

  private static boolean isIndicConjunctConsonant(int c) {
    return containsCodePoint(INCB_CONSONANT, c);
  }

  private static boolean isIndicConjunctLinker(int c) {
    return containsCodePoint(INCB_LINKER, c);
  }

  private static boolean isIndicConjunctExtend(int c) {
    return containsCodePoint(INCB_EXTEND, c);
  }

  private static int[][] graphemeTable(String name) {
    return Objects.requireNonNull(UnicodeGeneratedTables.GRAPHEME_PROPERTIES.get(name), name);
  }

  private static boolean containsCodePoint(int[][] ranges, int c) {
    int hi = ranges.length - 1;
    if (hi < 0 || c < ranges[0][0] || c > ranges[hi][1]) {
      return false;
    }
    int lo = 0;
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
