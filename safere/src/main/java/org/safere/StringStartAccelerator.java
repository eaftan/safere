// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import org.safere.Pattern.FixedOffsetLiteral;
import org.safere.Pattern.StartAcceleration;

/**
 * Encapsulates pre-computed start acceleration search strategies for finding candidate match
 * positions in a {@link String}.
 */
sealed interface StringStartAccelerator {

  /**
   * Creates a {@link StringStartAccelerator} for the given pattern descriptor, or {@code null} if
   * no acceleration strategy applies.
   */
  static StringStartAccelerator create(MultiAnchorDescriptor descriptor, boolean hasWordBoundary) {
    if (descriptor == null) {
      return null;
    }
    return create(descriptor.startPlan(), hasWordBoundary);
  }

  static StringStartAccelerator create(
      MultiAnchorDescriptor.StartPlan plan, boolean hasWordBoundary) {
    if (plan == null || plan instanceof MultiAnchorDescriptor.StartPlan.None) {
      return null;
    }
    return switch (plan) {
      case MultiAnchorDescriptor.StartPlan.None unusedNone -> null;
      case MultiAnchorDescriptor.StartPlan.Literal lit ->
          lit.foldCase()
              ? CaseInsensitiveLiteral.create(lit.prefix(), lit.classHashChain())
              : Literal.create(lit.prefix());
      case MultiAnchorDescriptor.StartPlan.CharClass cc ->
          hasWordBoundary || !cc.scanInfo().isSelective() ? null : CharClass.create(cc.scanInfo());
      case MultiAnchorDescriptor.StartPlan.FixedOffset fo ->
          new FixedOffset(fo.fol(), fo.leadingClass());
      case MultiAnchorDescriptor.StartPlan.MultiLiteral ml ->
          hasWordBoundary || ml.fallbackClass() == null || !ml.fallbackClass().isSelective()
              ? null
              : CharClass.create(ml.fallbackClass());
      case MultiAnchorDescriptor.StartPlan.LeadingExpansion le -> {
        StringStartAccelerator inner = create(le.innerPlan(), hasWordBoundary);
        yield inner != null
            ? new LeadingExpansion(le.leadingClass(), le.minRepetition(), le.maxRepetition(), inner)
            : null;
      }
      case MultiAnchorDescriptor.StartPlan.LineAnchor la ->
          hasWordBoundary ? null : new LineAnchor(la.acceleration());
    };
  }

  /**
   * Finds the next candidate match start position at or after {@code fromIndex} using
   * pattern-matched devirtualization.
   *
   * <p>Direct sealed-type pattern matching avoids {@code invokeinterface} dispatch overhead on hot
   * matching loops. HotSpot C2 does not automatically devirtualize megamorphic interface calls with
   * &ge; 3 implementations across the JVM lifecycle; switching over the sealed subtypes here allows
   * C2 to inline candidate searches directly into caller loops.
   */
  static int findNextCandidate(
      StringStartAccelerator accelerator, String text, int fromIndex, boolean unixLines) {
    return switch (accelerator) {
      case Literal lit -> lit.findCandidate(text, fromIndex, unixLines);
      case CaseInsensitiveLiteral cil -> cil.findCandidate(text, fromIndex, unixLines);
      case FixedOffset fo -> fo.findCandidate(text, fromIndex, unixLines);
      case CharClass cc -> cc.findCandidate(text, fromIndex, unixLines);
      case LineAnchor la -> la.findCandidate(text, fromIndex, unixLines);
      case LeadingExpansion le -> le.findCandidate(text, fromIndex, unixLines);
    };
  }

  /** Returns the tuning and diagnostic policy for this accelerator. */
  default AcceleratorPolicy policy() {
    return AcceleratorPolicy.DEFAULT;
  }

  record Literal(String prefix) implements StringStartAccelerator {

    static Literal create(String prefix) {
      return new Literal(prefix);
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
    }

    int findCandidate(String text, int fromIndex, boolean unixLines) {
      int idx = text.indexOf(prefix, fromIndex);
      if (WorkCounterConfig.ENABLED) {
        int scanned = idx >= 0 ? idx - fromIndex + prefix.length() : text.length() - fromIndex;
        WorkCounter.record(Math.max(0, scanned));
      }
      return idx;
    }
  }

  record CaseInsensitiveLiteral(
      String prefix,
      int anchorOffset,
      char anchorLow,
      char anchorHigh,
      ClassHashChain classHashChain)
      implements StringStartAccelerator {

    static CaseInsensitiveLiteral create(String prefix, ClassHashChain classHashChain) {
      if (prefix == null || prefix.isEmpty()) {
        return new CaseInsensitiveLiteral(prefix, 0, '\0', '\0', null);
      }
      int anchorOffset = RarityOracle.rarestAsciiOffset(prefix, prefix.length());
      char anchor = prefix.charAt(anchorOffset);
      char anchorLow = Ascii.toLowerCase(anchor);
      char anchorHigh = Ascii.toUpperCase(anchor);
      ClassHashChain chain =
          classHashChain != null ? classHashChain : ClassHashChain.compileCaseInsensitive(prefix);
      return new CaseInsensitiveLiteral(prefix, anchorOffset, anchorLow, anchorHigh, chain);
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
    }

    int findCandidate(String text, int fromIndex, boolean unixLines) {
      return Matcher.indexOfIgnoreCase(
          text, prefix, anchorOffset, anchorLow, anchorHigh, classHashChain, fromIndex);
    }
  }

  record FixedOffset(FixedOffsetLiteral fixedOffset, CharClassScanInfo firstCharClass)
      implements StringStartAccelerator {

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
    }

    int findCandidate(String text, int fromIndex, boolean unixLines) {
      return nextFixedOffsetCandidate(text, fixedOffset, firstCharClass, fromIndex);
    }

    private static int nextFixedOffsetCandidate(
        String text,
        FixedOffsetLiteral fixedOffsetLiteral,
        CharClassScanInfo firstCharClass,
        int fromIndex) {
      int minOffset = fixedOffsetLiteral.minOffset();
      if (minOffset > text.length() - fromIndex) {
        return -1;
      }
      int literalFrom = fromIndex + minOffset;
      int[] discreteOffsets = fixedOffsetLiteral.discreteOffsets();

      while (literalFrom <= text.length()) {
        int literalStart = text.indexOf(fixedOffsetLiteral.literal(), literalFrom);
        if (WorkCounterConfig.ENABLED) {
          int scanned =
              literalStart >= 0
                  ? literalStart - literalFrom + fixedOffsetLiteral.literal().length()
                  : text.length() - literalFrom;
          WorkCounter.record(Math.max(0, scanned));
        }
        if (literalStart < 0) {
          return -1;
        }
        if (firstCharClass != null) {
          if (discreteOffsets != null && discreteOffsets.length == 1) {
            int candidateStart = literalStart - discreteOffsets[0];
            if (candidateStart >= fromIndex) {
              int first = candidateStart < text.length() ? text.codePointAt(candidateStart) : -1;
              if (first >= 0 && firstCharClass.contains(first)) {
                return candidateStart;
              }
            }
            literalFrom = literalStart + 1;
            continue;
          } else if (discreteOffsets == null
              && fixedOffsetLiteral.minOffset() == fixedOffsetLiteral.maxOffset()) {
            int candidateStart =
                retreatByCodePoints(text, literalStart, fixedOffsetLiteral.maxOffset(), fromIndex);
            if (candidateStart >= fromIndex) {
              int first = candidateStart < text.length() ? text.codePointAt(candidateStart) : -1;
              if (first >= 0 && firstCharClass.contains(first)) {
                return candidateStart;
              }
            }
            literalFrom = literalStart + 1;
            continue;
          }
        }
        return Math.max(
            fromIndex,
            retreatByCodePoints(text, literalStart, fixedOffsetLiteral.maxOffset(), fromIndex));
      }
      return -1;
    }

    private static int retreatByCodePoints(String text, int index, int count, int minIndex) {
      int pos = index;
      while (count > 0 && pos > minIndex) {
        pos--;
        if (pos > minIndex
            && Character.isLowSurrogate(text.charAt(pos))
            && Character.isHighSurrogate(text.charAt(pos - 1))) {
          pos--;
        }
        count--;
      }
      return Math.max(minIndex, pos);
    }
  }

  // The lookup table is immutable pattern metadata; array identity and value semantics are unused.
  @SuppressWarnings("ArrayRecordComponent")
  record CharClass(CharClassScanInfo scanInfo, boolean[] asciiTable)
      implements StringStartAccelerator {

    static CharClass create(CharClassScanInfo scanInfo) {
      return new CharClass(scanInfo, buildAsciiTable(scanInfo));
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.CHAR_CLASS;
    }

    int findCandidate(String text, int fromIndex, boolean unixLines) {
      return indexOfCharClass(text, asciiTable, scanInfo.ranges(), scanInfo.isAscii(), fromIndex);
    }

    private static int indexOfCharClass(
        String text, boolean[] asciiTable, int[] ranges, boolean isAscii, int fromIndex) {
      int length = text.length();
      int index = fromIndex;
      while (index < length) {
        int asciiResult = scanAsciiRun(text, asciiTable, index);
        if (asciiResult >= 0) {
          return asciiResult;
        }
        index = ~asciiResult;
        if (index >= length) {
          return -1;
        }

        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        int cp = text.codePointAt(index);
        if (!isAscii && Matcher.binarySearchRanges(ranges, cp)) {
          return index;
        }
        index += Character.charCount(cp);
      }
      return -1;
    }

    /**
     * Scans one contiguous ASCII run. A nonnegative result is a matching position; a negative
     * result is the complement of either the first non-ASCII position or the text length. Keeping
     * Unicode decoding and range lookup outside this loop allows HotSpot to optimize the common
     * ASCII path independently.
     */
    private static int scanAsciiRun(String text, boolean[] asciiTable, int fromIndex) {
      int length = text.length();
      for (int i = fromIndex; i < length; i++) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        char ch = text.charAt(i);
        if (ch >= 128) {
          return ~i;
        }
        if (asciiTable[ch]) {
          return i;
        }
      }
      return ~length;
    }

    private static boolean[] buildAsciiTable(CharClassScanInfo scanInfo) {
      boolean[] table = new boolean[128];
      for (int i = 0; i < 128; i++) {
        table[i] = scanInfo.contains(i);
      }
      return table;
    }
  }

  record LineAnchor(StartAcceleration startAcceleration) implements StringStartAccelerator {

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LINE_ANCHOR;
    }

    int findCandidate(String text, int fromIndex, boolean unixLines) {
      return nextAcceleratedStart(text, startAcceleration, fromIndex, unixLines);
    }

    private static int nextAcceleratedStart(
        String text, StartAcceleration acceleration, int fromIndex, boolean unixLines) {
      int start = Math.max(0, fromIndex);
      for (int i = start; i < text.length(); i++) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        if (matchesStartAcceleration(text, i, acceleration, unixLines)) {
          return i;
        }
        int cp = text.codePointAt(i);
        i += Character.charCount(cp) - 1;
      }
      return -1;
    }

    private static boolean matchesStartAcceleration(
        String text, int pos, StartAcceleration acceleration, boolean unixLines) {
      boolean lineStart = isBeginLine(text, pos, unixLines);
      boolean asciiStart = matchesAsciiStart(text, pos, acceleration.asciiStart);
      if (acceleration.requireLineStart) {
        return lineStart && (acceleration.asciiStart == null || asciiStart);
      }
      return (acceleration.allowLineStart && lineStart) || asciiStart;
    }

    private static boolean matchesAsciiStart(String text, int pos, AsciiBitmap asciiStart) {
      if (asciiStart == null || pos >= text.length()) {
        return false;
      }
      char ch = text.charAt(pos);
      return asciiStart.contains(ch);
    }

    private static boolean isBeginLine(String text, int pos, boolean unixLines) {
      if (pos == 0) {
        return !text.isEmpty();
      }
      if (pos >= text.length()) {
        return false;
      }
      char prev = text.charAt(pos - 1);
      if (unixLines) {
        return prev == '\n';
      }
      return prev == '\n'
          || prev == '\u0085'
          || prev == '\u2028'
          || prev == '\u2029'
          || (prev == '\r' && text.charAt(pos) != '\n');
    }
  }

  record LeadingExpansion(
      CharClassScanInfo leadingClass,
      int minRepetition,
      int maxRepetition,
      StringStartAccelerator inner)
      implements StringStartAccelerator {

    @Override
    public AcceleratorPolicy policy() {
      return new AcceleratorPolicy(16, 4, false, inner.policy().strategy());
    }

    int findCandidate(String text, int fromIndex, boolean unixLines) {
      int searchPos = Math.max(0, fromIndex);
      int textLen = text.length();
      while (searchPos < textLen) {
        int innerMatch =
            StringStartAccelerator.findNextCandidate(inner, text, searchPos, unixLines);
        if (innerMatch < 0) {
          return -1;
        }
        int start = innerMatch;
        int count = 0;
        while (start > fromIndex) {
          int cp = text.codePointBefore(start);
          int cpStart = start - Character.charCount(cp);
          if (cpStart < fromIndex) {
            break;
          }
          if (!leadingClass.contains(cp)) {
            break;
          }
          if (count + 1 > maxRepetition) {
            break;
          }
          count++;
          start = cpStart;
        }
        if (count >= minRepetition) {
          return start;
        }
        searchPos = innerMatch + 1;
      }
      return -1;
    }
  }
}
