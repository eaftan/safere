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
              ? (Ascii.isAscii(lit.prefix())
                  ? CaseInsensitiveLiteral.create(lit.prefix())
                  : UnicodeCaseInsensitiveLiteral.create(lit.prefix()))
              : Literal.create(lit.prefix());
      case MultiAnchorDescriptor.StartPlan.CharClass cc ->
          hasWordBoundary || !cc.scanInfo().isSelective() ? null : CharClass.create(cc.scanInfo());
      case MultiAnchorDescriptor.StartPlan.FixedOffset fo ->
          FixedOffset.create(fo.fol(), fo.leadingClass());
      case MultiAnchorDescriptor.StartPlan.MultiLiteral ml ->
          hasWordBoundary || ml.fallbackClass() == null || !ml.fallbackClass().isSelective()
              ? null
              : CharClass.create(ml.fallbackClass());
      case MultiAnchorDescriptor.StartPlan.LeadingExpansion le -> {
        StringStartAccelerator inner = create(le.innerPlan(), hasWordBoundary);
        yield inner != null
            ? new LeadingExpansion(
                le.leadingClass(),
                le.minRepetition(),
                le.maxRepetition(),
                le.hasLeadingAssertions(),
                inner)
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
    return findNextCandidate(accelerator, new StringInputScanner(text), fromIndex, unixLines);
  }

  /**
   * Like {@link #findNextCandidate(StringStartAccelerator, String, int, boolean)}, but lets
   * small-set character class searches reuse {@code scanner}'s memo of earlier searches. Callers
   * that search the same text repeatedly must pass the same scanner each time, or those searches
   * are quadratic; see {@link StringInputScanner#memoizedIndexOf}.
   */
  static int findNextCandidate(
      StringStartAccelerator accelerator,
      StringInputScanner scanner,
      int fromIndex,
      boolean unixLines) {
    String text = scanner.text();
    return switch (accelerator) {
      case Literal lit -> lit.findCandidate(text, fromIndex, unixLines);
      case CaseInsensitiveLiteral cil -> cil.findCandidate(text, fromIndex, unixLines);
      case UnicodeCaseInsensitiveLiteral ucil -> ucil.findCandidate(text, fromIndex, unixLines);
      case FixedOffset fo -> fo.findCandidate(text, fromIndex, unixLines);
      case CharClass cc -> cc.findCandidate(scanner, fromIndex);
      case LineAnchor la -> la.findCandidate(text, fromIndex, unixLines);
      case LeadingExpansion le -> le.findCandidate(scanner, fromIndex, unixLines);
    };
  }

  /** Returns the tuning and diagnostic policy for this accelerator. */
  default AcceleratorPolicy policy() {
    return AcceleratorPolicy.DEFAULT;
  }

  /**
   * Scans for a case-sensitive literal prefix.
   *
   * @param singleAsciiChar the one ASCII character {@code prefix} consists of, or {@link
   *     StringLiteralSearch#NOT_SINGLE_ASCII}. Deciding this at plan time rather than per call is
   *     the whole point; see {@link StringLiteralSearch#singleAsciiChar}.
   */
  record Literal(String prefix, int anchorOffset, char anchor, int singleAsciiChar)
      implements StringStartAccelerator {

    static Literal create(String prefix) {
      int anchorOffset = StringLiteralSearch.anchorOffset(prefix);
      return new Literal(
          prefix,
          anchorOffset,
          StringLiteralSearch.anchorAt(prefix, anchorOffset),
          StringLiteralSearch.singleAsciiChar(prefix));
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
    }

    int findCandidate(String text, int fromIndex, boolean unixLines) {
      return StringLiteralSearch.indexOfPlanned(
          text, prefix, anchorOffset, anchor, singleAsciiChar, fromIndex);
    }
  }

  record CaseInsensitiveLiteral(
      String prefix,
      int anchorOffset,
      char anchorLow,
      char anchorHigh,
      ClassHashChain classHashChain)
      implements StringStartAccelerator {

    static CaseInsensitiveLiteral create(String prefix) {
      if (prefix == null || prefix.isEmpty()) {
        return new CaseInsensitiveLiteral(prefix, 0, '\0', '\0', null);
      }
      int anchorOffset = RarityOracle.rarestAsciiOffset(prefix, prefix.length(), true);
      char anchor = prefix.charAt(anchorOffset);
      char anchorLow = Ascii.toLowerCase(anchor);
      char anchorHigh = Ascii.toUpperCase(anchor);
      ClassHashChain chain =
          prefix.length() >= 4 ? ClassHashChain.compileCaseInsensitive(prefix) : null;
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

  record UnicodeCaseInsensitiveLiteral(String prefix, ClassHashChain classHashChain)
      implements StringStartAccelerator {

    static UnicodeCaseInsensitiveLiteral create(String prefix) {
      if (prefix == null || prefix.isEmpty()) {
        return new UnicodeCaseInsensitiveLiteral(prefix, null);
      }
      ClassHashChain chain =
          prefix.length() >= 4 ? ClassHashChain.compileCaseInsensitive(prefix) : null;
      return new UnicodeCaseInsensitiveLiteral(prefix, chain);
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
    }

    int findCandidate(String text, int fromIndex, boolean unixLines) {
      if (prefix == null || prefix.isEmpty()) {
        return Math.min(Math.max(0, fromIndex), text.length());
      }
      int pos = Math.max(0, fromIndex);
      if (classHashChain != null) {
        long limit = WorkLimit.forRemaining(text.length() - pos);
        int result = classHashChain.search(text, pos, limit);
        return result == -2 ? Utf16.indexOfUnicodeIgnoreCase(text, prefix, pos) : result;
      }
      return Utf16.indexOfUnicodeIgnoreCase(text, prefix, pos);
    }
  }

  /**
   * Scans for a literal that sits a bounded distance into the match, then walks back to the start.
   *
   * @param singleAsciiChar see {@link Literal#singleAsciiChar()}. This plan gains more from the
   *     choice than {@code Literal} does, because the loop below re-searches for the literal once
   *     per rejected candidate rather than once per call.
   */
  record FixedOffset(
      FixedOffsetLiteral fixedOffset,
      CharClassScanInfo firstCharClass,
      int anchorOffset,
      char anchor,
      int singleAsciiChar)
      implements StringStartAccelerator {

    static FixedOffset create(FixedOffsetLiteral fixedOffset, CharClassScanInfo firstCharClass) {
      String literal = fixedOffset.literal();
      int anchorOffset = StringLiteralSearch.anchorOffset(literal);
      return new FixedOffset(
          fixedOffset,
          firstCharClass,
          anchorOffset,
          StringLiteralSearch.anchorAt(literal, anchorOffset),
          StringLiteralSearch.singleAsciiChar(literal));
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
    }

    int findCandidate(String text, int fromIndex, boolean unixLines) {
      return nextFixedOffsetCandidate(
          text, fixedOffset, firstCharClass, anchorOffset, anchor, singleAsciiChar, fromIndex);
    }

    private static int nextFixedOffsetCandidate(
        String text,
        FixedOffsetLiteral fixedOffsetLiteral,
        CharClassScanInfo firstCharClass,
        int anchorOffset,
        char anchor,
        int singleAsciiChar,
        int fromIndex) {
      int minOffset = fixedOffsetLiteral.minOffset();
      if (minOffset > text.length() - fromIndex) {
        return -1;
      }
      int literalFrom = fromIndex + minOffset;
      int[] discreteOffsets = fixedOffsetLiteral.discreteOffsets();

      while (literalFrom <= text.length()) {
        int literalStart =
            StringLiteralSearch.indexOfPlanned(
                text,
                fixedOffsetLiteral.literal(),
                anchorOffset,
                anchor,
                singleAsciiChar,
                literalFrom);
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
          } else if (discreteOffsets != null) {
            int resolved =
                resolveMultiOffsetStart(
                    text,
                    fixedOffsetLiteral,
                    discreteOffsets,
                    firstCharClass,
                    literalStart,
                    fromIndex);
            if (resolved >= 0) {
              return resolved;
            }
            literalFrom = literalStart + 1;
            continue;
          } else if (fixedOffsetLiteral.minOffset() == fixedOffsetLiteral.maxOffset()) {
            int candidateStart =
                retreatedStartInClass(
                    text, fixedOffsetLiteral, firstCharClass, literalStart, fromIndex);
            if (candidateStart >= 0) {
              return candidateStart;
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

    /**
     * Resolves a literal occurrence at {@code literalStart} to a match start when the literal can
     * sit at more than one offset, or returns -1 when the leading class admits none of them and the
     * occurrence can be skipped.
     *
     * <p>The offsets are ascending, so the largest yields the earliest start; they are walked from
     * the back to find the leftmost start this occurrence admits. The result is then clamped to the
     * earliest start a <em>later</em> occurrence could imply, because the caller treats it as a
     * floor and will not look before it, and the next occurrence is at {@code literalStart + 1} at
     * the earliest. Without the clamp a wide offset span loses the leftmost match: on {@code
     * (aq|b[a-z]{9})z} the {@code z} at index 5 admits only the start at 3, while the {@code z} at
     * index 10 starts the match at 0.
     */
    private static int resolveMultiOffsetStart(
        String text,
        FixedOffsetLiteral fixedOffsetLiteral,
        int[] discreteOffsets,
        CharClassScanInfo firstCharClass,
        int literalStart,
        int fromIndex) {
      for (int i = discreteOffsets.length - 1; i >= 0; i--) {
        int start = literalStart - discreteOffsets[i];
        if (start >= fromIndex
            && start < text.length()
            && firstCharClass.contains(text.codePointAt(start))) {
          return Math.max(
              fromIndex, Math.min(start, literalStart + 1 - fixedOffsetLiteral.maxOffset()));
        }
      }
      return -1;
    }

    /**
     * Returns the start reached by retreating from {@code literalStart} over a fixed number of code
     * points when the leading class admits it, or -1 when the occurrence can be skipped.
     */
    private static int retreatedStartInClass(
        String text,
        FixedOffsetLiteral fixedOffsetLiteral,
        CharClassScanInfo firstCharClass,
        int literalStart,
        int fromIndex) {
      int candidateStart =
          retreatByCodePoints(text, literalStart, fixedOffsetLiteral.maxOffset(), fromIndex);
      if (candidateStart >= fromIndex) {
        int first = candidateStart < text.length() ? text.codePointAt(candidateStart) : -1;
        if (first >= 0 && firstCharClass.contains(first)) {
          return candidateStart;
        }
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
  record CharClass(CharClassScanInfo scanInfo, boolean[] asciiTable, char[] smallChars)
      implements StringStartAccelerator {

    /**
     * Chars {@link #findCandidateSmall} probes before the per-member searches. At 8, candidates
     * 11-15 chars apart ({@code citationScrubberFullWidthNoMatch}) always missed the probe and paid
     * for the memoized searches instead, which cost 1.14x on aarch64; 16 brings that to parity.
     */
    private static final int CANDIDATE_PROBE_CHARS = 16;

    static CharClass create(CharClassScanInfo scanInfo) {
      char[] small = null;
      if (scanInfo instanceof CharClassScanInfo.SmallSet ss
          && ss.chars() != null
          && ss.chars().length <= 2) {
        small = ss.chars();
      }
      return new CharClass(scanInfo, buildAsciiTable(scanInfo), small);
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.CHAR_CLASS;
    }

    int findCandidate(StringInputScanner scanner, int fromIndex) {
      if (smallChars != null) {
        return findCandidateSmall(scanner, fromIndex);
      }
      return indexOfCharClass(
          scanner.text(), asciiTable, scanInfo.ranges(), scanInfo.isAscii(), fromIndex);
    }

    /**
     * Searches for the first of one or two chars with the {@code String.indexOf} intrinsic, one
     * member at a time. A single member cannot be rescanned: each call resumes past the occurrence
     * the previous call returned. With two, a short {@link StringInputScanner#probeEither probe}
     * finds a nearby member first, and the farther member's occurrence would otherwise be rescanned
     * on every call, so the per-member searches go through the scanner's memo; see {@link
     * StringInputScanner#memoizedIndexOf}.
     */
    private int findCandidateSmall(StringInputScanner scanner, int fromIndex) {
      if (smallChars.length == 1) {
        return scanner.indexOfChar(smallChars[0], fromIndex);
      }
      char c0 = smallChars[0];
      char c1 = smallChars[1];
      int probe = scanner.probeEither(c0, c1, fromIndex, CANDIDATE_PROBE_CHARS);
      if (probe >= 0) {
        return probe;
      }
      int rest = ~probe;
      int first = scanner.memoizedIndexOf(c0, rest);
      int second = scanner.memoizedIndexOf(c1, rest);
      if (first < 0) {
        return second;
      }
      return second < 0 ? first : Math.min(first, second);
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
      boolean hasLeadingAssertions,
      StringStartAccelerator inner)
      implements StringStartAccelerator {

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LEADING_EXPANSION.withStrategy(inner.policy().strategy());
    }

    boolean canVerifyAtInner() {
      return minRepetition == 0 && !hasLeadingAssertions;
    }

    int findInnerCandidate(StringInputScanner scanner, int searchPos, boolean unixLines) {
      if (inner instanceof CharClass cc) {
        return cc.findCandidate(scanner, searchPos);
      }
      return StringStartAccelerator.findNextCandidate(inner, scanner, searchPos, unixLines);
    }

    int expandBackward(String text, int innerMatch, int fromIndex) {
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
      return start;
    }

    int findCandidate(StringInputScanner scanner, int fromIndex, boolean unixLines) {
      String text = scanner.text();
      int searchPos = Math.max(0, fromIndex);
      int textLen = text.length();
      while (searchPos < textLen) {
        int innerMatch = findInnerCandidate(scanner, searchPos, unixLines);
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
