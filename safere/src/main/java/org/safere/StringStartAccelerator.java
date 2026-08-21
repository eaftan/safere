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
  static StringStartAccelerator create(StartDescriptor descriptor, boolean hasWordBoundary) {
    if (descriptor == null || !descriptor.hasStartAcceleration()) {
      return null;
    }
    if (descriptor.prefix() != null) {
      return new Literal(descriptor.prefix(), descriptor.prefixFoldCase());
    }
    if (descriptor.fixedOffsetLiteral() != null) {
      return new FixedOffset(descriptor.fixedOffsetLiteral(), descriptor.charClassPrefix());
    }
    if (descriptor.charClassPrefix() != null && !hasWordBoundary) {
      return new CharClass(descriptor.charClassPrefix());
    }
    if (descriptor.lineAnchor() != null && !hasWordBoundary) {
      return new LineAnchor(descriptor.lineAnchor());
    }
    if (descriptor.leadingExpansion() != null) {
      StringStartAccelerator inner =
          create(descriptor.leadingExpansion().innerDescriptor(), hasWordBoundary);
      if (inner != null) {
        return new LeadingExpansion(
            descriptor.leadingExpansion().leadingClass(),
            descriptor.leadingExpansion().minRepetition(),
            descriptor.leadingExpansion().maxRepetition(),
            inner);
      }
    }
    return null;
  }

  /**
   * Finds the next candidate match start position at or after {@code fromIndex}. Returns negative
   * if definitely not found.
   */
  int findCandidate(String text, int fromIndex, boolean unixLines);

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

  final class Literal implements StringStartAccelerator {
    private final String prefix;
    private final boolean prefixFoldCase;
    private final int[] failure;
    private final int anchorOffset;
    private final char anchorLow;
    private final char anchorHigh;

    Literal(String prefix, boolean prefixFoldCase) {
      this.prefix = prefix;
      this.prefixFoldCase = prefixFoldCase;
      if (prefixFoldCase && prefix != null && !prefix.isEmpty()) {
        this.failure = Ascii.ignoreCaseFailure(prefix);
        this.anchorOffset = RarityOracle.rarestAsciiOffset(prefix, prefix.length());
        char anchor = prefix.charAt(anchorOffset);
        this.anchorLow = Ascii.toLowerCase(anchor);
        this.anchorHigh = Ascii.toUpperCase(anchor);
      } else {
        this.failure = null;
        this.anchorOffset = 0;
        this.anchorLow = 0;
        this.anchorHigh = 0;
      }
    }

    public String prefix() {
      return prefix;
    }

    public boolean prefixFoldCase() {
      return prefixFoldCase;
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
    }

    @Override
    public int findCandidate(String text, int fromIndex, boolean unixLines) {
      if (prefixFoldCase) {
        return Matcher.indexOfIgnoreCase(
            text, prefix, failure, anchorOffset, anchorLow, anchorHigh, fromIndex);
      }
      int idx = text.indexOf(prefix, fromIndex);
      if (WorkCounterConfig.ENABLED) {
        int scanned = idx >= 0 ? idx - fromIndex + prefix.length() : text.length() - fromIndex;
        WorkCounter.record(Math.max(0, scanned));
      }
      return idx;
    }
  }

  final class FixedOffset implements StringStartAccelerator {
    private final FixedOffsetLiteral fixedOffset;
    private final CharClassScanInfo firstCharClass;

    FixedOffset(FixedOffsetLiteral fixedOffset, CharClassScanInfo firstCharClass) {
      this.fixedOffset = fixedOffset;
      this.firstCharClass = firstCharClass;
    }

    public FixedOffsetLiteral fixedOffset() {
      return fixedOffset;
    }

    public CharClassScanInfo firstCharClass() {
      return firstCharClass;
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
    }

    @Override
    public int findCandidate(String text, int fromIndex, boolean unixLines) {
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
        if (discreteOffsets != null && discreteOffsets.length == 1 && firstCharClass != null) {
          boolean matchFound = false;
          int earliestValid = -1;
          for (int offset : discreteOffsets) {
            int candidateStart = literalStart - offset;
            if (candidateStart >= fromIndex) {
              int first = candidateStart < text.length() ? text.charAt(candidateStart) : -1;
              if (first >= 0 && firstCharClass.contains(first)) {
                matchFound = true;
                if (earliestValid < 0 || candidateStart < earliestValid) {
                  earliestValid = candidateStart;
                }
              }
            }
          }
          if (matchFound) {
            return earliestValid;
          }
          literalFrom = literalStart + 1;
          continue;
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

  final class CharClass implements StringStartAccelerator {
    private final CharClassScanInfo scanInfo;
    private final boolean[] asciiTable;

    CharClass(CharClassScanInfo scanInfo) {
      this.scanInfo = scanInfo;
      this.asciiTable = buildAsciiTable(scanInfo);
    }

    public CharClassScanInfo scanInfo() {
      return scanInfo;
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.CHAR_CLASS;
    }

    @Override
    public int findCandidate(String text, int fromIndex, boolean unixLines) {
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

  final class LineAnchor implements StringStartAccelerator {
    private final StartAcceleration startAcceleration;

    LineAnchor(StartAcceleration startAcceleration) {
      this.startAcceleration = startAcceleration;
    }

    public StartAcceleration startAcceleration() {
      return startAcceleration;
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LINE_ANCHOR;
    }

    @Override
    public int findCandidate(String text, int fromIndex, boolean unixLines) {
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

    @Override
    public int findCandidate(String text, int fromIndex, boolean unixLines) {
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
          if (!leadingClass.contains(cp)) {
            break;
          }
          if (count + 1 > maxRepetition) {
            break;
          }
          count++;
          start -= Character.charCount(cp);
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
