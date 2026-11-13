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
          FixedOffset.create(fo.fol(), fo.leadingClass(), fo.leadingLiteral());
      case MultiAnchorDescriptor.StartPlan.FixedOffsetClass fc ->
          new FixedOffsetClass(CharClass.create(fc.scanInfo()), fc.offset(), fc.leadingClass());
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
                MultiAnchorCompiler.isWideLeadingClass(le.leadingClass()),
                LeadingExpansion.forwardScanChars(le),
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
    return switch (accelerator) {
      case Literal lit -> lit.findCandidate(text, fromIndex, unixLines);
      case CaseInsensitiveLiteral cil -> cil.findCandidate(text, fromIndex, unixLines);
      case UnicodeCaseInsensitiveLiteral ucil -> ucil.findCandidate(text, fromIndex, unixLines);
      case FixedOffset fo -> fo.findCandidate(text, fromIndex, unixLines);
      case FixedOffsetClass fc -> fc.findCandidate(text, fromIndex, unixLines);
      case CharClass cc -> cc.findCandidate(text, fromIndex, unixLines);
      case LineAnchor la -> la.findCandidate(text, fromIndex, unixLines);
      case LeadingExpansion le -> le.findCandidate(text, fromIndex, unixLines);
    };
  }

  /** Uses the same plans on a bounded, region-relative String view. */
  static int findNextCandidate(
      StringStartAccelerator accelerator,
      StringRegionInputScanner text,
      int fromIndex,
      boolean unixLines) {
    return switch (accelerator) {
      case Literal lit -> text.indexOf(lit.prefix(), fromIndex, text.length());
      case CaseInsensitiveLiteral cil ->
          text.indexOfIgnoreCase(
              cil.prefix(), cil.anchorOffset(), cil.anchorLow(), cil.anchorHigh(), fromIndex);
      case UnicodeCaseInsensitiveLiteral ucil -> {
        if (ucil.classHashChain() != null) {
          int candidate =
              ucil.classHashChain()
                  .search(text, fromIndex, WorkLimit.forRemaining(text.length() - fromIndex));
          if (candidate != -2) {
            yield candidate;
          }
        }
        yield Utf16.indexOfUnicodeIgnoreCase(text, ucil.prefix(), fromIndex);
      }
      case CharClass cc -> text.indexOfCharClass(cc.scanInfo(), fromIndex);
      case FixedOffset fo -> fo.findCandidate(text, fromIndex, unixLines);
      case FixedOffsetClass fc -> fc.findCandidate(text, fromIndex, unixLines);
      case LineAnchor la -> la.findCandidate(text, fromIndex, unixLines);
      case LeadingExpansion le -> le.findCandidate(text, fromIndex, unixLines);
    };
  }

  /** Dispatches composite plans without copying their input view. */
  static int findNextCandidate(
      StringStartAccelerator accelerator, CharSequence text, int fromIndex, boolean unixLines) {
    return text instanceof String string
        ? findNextCandidate(accelerator, string, fromIndex, unixLines)
        : findNextCandidate(accelerator, (StringRegionInputScanner) text, fromIndex, unixLines);
  }

  private static int indexOfPlanned(
      CharSequence text,
      String literal,
      int anchorOffset,
      char anchor,
      int singleAsciiChar,
      int fromIndex) {
    return text instanceof String string
        ? StringLiteralSearch.indexOfPlanned(
            string, literal, anchorOffset, anchor, singleAsciiChar, fromIndex)
        : ((StringRegionInputScanner) text).indexOf(literal, fromIndex, text.length());
  }

  private static int indexOfClass(CharSequence text, CharClassScanInfo info, int fromIndex) {
    return text instanceof String string
        ? StringInputScanner.indexOfCharClass(string, info, fromIndex)
        : ((StringRegionInputScanner) text).indexOfCharClass(info, fromIndex);
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
      int singleAsciiChar,
      Literal leadingLiteral,
      boolean plainMultiOffset)
      implements StringStartAccelerator {

    static FixedOffset create(
        FixedOffsetLiteral fixedOffset, CharClassScanInfo firstCharClass, String leadingLiteral) {
      String literal = fixedOffset.literal();
      int anchorOffset = StringLiteralSearch.anchorOffset(literal);
      return new FixedOffset(
          fixedOffset,
          firstCharClass,
          anchorOffset,
          StringLiteralSearch.anchorAt(literal, anchorOffset),
          StringLiteralSearch.singleAsciiChar(literal),
          leadingLiteral != null ? Literal.create(leadingLiteral) : null,
          firstCharClass != null
              && fixedOffset.discreteOffsets() != null
              && fixedOffset.discreteOffsets().length > 1
              && !firstCharClass.isSelective());
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
    }

    int findCandidate(CharSequence text, int fromIndex, boolean unixLines) {
      if (plainMultiOffset) {
        int minOffset = fixedOffset.minOffset();
        return minOffset > text.length() - fromIndex
            ? -1
            : findUnfilteredMultiOffsetCandidate(
                text,
                fixedOffset,
                firstCharClass,
                anchorOffset,
                anchor,
                singleAsciiChar,
                fromIndex + minOffset,
                fromIndex);
      }
      return nextFixedOffsetCandidate(
          text,
          fixedOffset,
          firstCharClass,
          anchorOffset,
          anchor,
          singleAsciiChar,
          leadingLiteral,
          fromIndex);
    }

    private static int nextFixedOffsetCandidate(
        CharSequence text,
        FixedOffsetLiteral fixedOffsetLiteral,
        CharClassScanInfo firstCharClass,
        int anchorOffset,
        char anchor,
        int singleAsciiChar,
        Literal leadingLiteral,
        int fromIndex) {
      int minOffset = fixedOffsetLiteral.minOffset();
      if (minOffset > text.length() - fromIndex) {
        return -1;
      }
      int literalFrom = fromIndex + minOffset;
      int[] discreteOffsets = fixedOffsetLiteral.discreteOffsets();
      int cheapRejections = 0;

      while (literalFrom <= text.length()) {
        int literalStart =
            indexOfPlanned(
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
              int first =
                  candidateStart < text.length() ? Character.codePointAt(text, candidateStart) : -1;
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
            if (++cheapRejections == AcceleratorPolicy.FIXED_OFFSET_LITERAL_CHEAP_REJECTIONS
                && text.length() - literalFrom
                    >= AcceleratorPolicy.FIXED_OFFSET_LITERAL_MIN_REMAINING
                && (leadingLiteral != null
                    || firstCharClass instanceof CharClassScanInfo.AsciiSmallSet)) {
              return findFilteredMultiOffsetCandidate(
                  text,
                  fixedOffsetLiteral,
                  firstCharClass,
                  anchorOffset,
                  anchor,
                  singleAsciiChar,
                  leadingLiteral,
                  literalFrom,
                  fromIndex);
            }
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

    /** Alternates monotonic scans, abandoning the leading filter after an unprofitable skip. */
    private static int findFilteredMultiOffsetCandidate(
        CharSequence text,
        FixedOffsetLiteral fixedOffsetLiteral,
        CharClassScanInfo firstCharClass,
        int anchorOffset,
        char anchor,
        int singleAsciiChar,
        Literal leadingLiteral,
        int literalFrom,
        int fromIndex) {
      int minOffset = fixedOffsetLiteral.minOffset();
      int[] discreteOffsets = fixedOffsetLiteral.discreteOffsets();
      int nextLeading = -1;
      while (literalFrom <= text.length()) {
        // A later hit cannot imply a start below this floor, even across widely spaced offsets.
        int leadingFrom = Math.max(fromIndex, literalFrom - fixedOffsetLiteral.maxOffset());
        if (nextLeading < leadingFrom) {
          nextLeading =
              leadingLiteral != null
                  ? findNextCandidate(leadingLiteral, text, leadingFrom, false)
                  : indexOfClass(text, firstCharClass, leadingFrom);
        }
        if (nextLeading < 0 || minOffset > text.length() - nextLeading) {
          return -1;
        }
        int nextLiteralFrom = Math.max(literalFrom, nextLeading + minOffset);
        if (nextLiteralFrom - literalFrom < AcceleratorPolicy.LITERAL.minProfitableSkip()) {
          return findUnfilteredMultiOffsetCandidate(
              text,
              fixedOffsetLiteral,
              firstCharClass,
              anchorOffset,
              anchor,
              singleAsciiChar,
              nextLiteralFrom,
              fromIndex);
        }
        literalFrom = nextLiteralFrom;
        int hit =
            indexOfPlanned(
                text,
                fixedOffsetLiteral.literal(),
                anchorOffset,
                anchor,
                singleAsciiChar,
                literalFrom);
        if (hit < 0) {
          return -1;
        }
        int resolved =
            resolveMultiOffsetStart(
                text, fixedOffsetLiteral, discreteOffsets, firstCharClass, hit, fromIndex);
        if (resolved >= 0) {
          return resolved;
        }
        literalFrom = hit + 1;
      }
      return -1;
    }

    /** Continues from the established floor after an unprofitable leading probe. */
    private static int findUnfilteredMultiOffsetCandidate(
        CharSequence text,
        FixedOffsetLiteral fixedOffsetLiteral,
        CharClassScanInfo firstCharClass,
        int anchorOffset,
        char anchor,
        int singleAsciiChar,
        int literalFrom,
        int fromIndex) {
      int[] discreteOffsets = fixedOffsetLiteral.discreteOffsets();
      while (literalFrom <= text.length()) {
        int hit =
            indexOfPlanned(
                text,
                fixedOffsetLiteral.literal(),
                anchorOffset,
                anchor,
                singleAsciiChar,
                literalFrom);
        if (hit < 0) {
          return -1;
        }
        int resolved =
            resolveMultiOffsetStart(
                text, fixedOffsetLiteral, discreteOffsets, firstCharClass, hit, fromIndex);
        if (resolved >= 0) {
          return resolved;
        }
        literalFrom = hit + 1;
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
        CharSequence text,
        FixedOffsetLiteral fixedOffsetLiteral,
        int[] discreteOffsets,
        CharClassScanInfo firstCharClass,
        int literalStart,
        int fromIndex) {
      for (int i = discreteOffsets.length - 1; i >= 0; i--) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        int start = literalStart - discreteOffsets[i];
        if (start >= fromIndex
            && start < text.length()
            && firstCharClass.contains(Character.codePointAt(text, start))) {
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
        CharSequence text,
        FixedOffsetLiteral fixedOffsetLiteral,
        CharClassScanInfo firstCharClass,
        int literalStart,
        int fromIndex) {
      int candidateStart =
          retreatByCodePoints(text, literalStart, fixedOffsetLiteral.maxOffset(), fromIndex);
      if (candidateStart >= fromIndex) {
        int first =
            candidateStart < text.length() ? Character.codePointAt(text, candidateStart) : -1;
        if (first >= 0 && firstCharClass.contains(first)) {
          return candidateStart;
        }
      }
      return -1;
    }

    private static int retreatByCodePoints(CharSequence text, int index, int count, int minIndex) {
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

  /**
   * Scans a required interior class and checks its implied leading ASCII character.
   *
   * <p>The prefix's ASCII width is the same in both input representations. After a bounded number
   * of cheap leading-check failures, scans jump to the next leading-class hit before resuming the
   * interior scan. Both scans advance monotonically, including when interior hits are dense and
   * leading hits are absent. Full verification stays in the engine; ASCII leading hits exclude
   * starts inside surrogate pairs.
   */
  record FixedOffsetClass(CharClass inner, int offset, CharClassScanInfo leadingClass)
      implements StringStartAccelerator {
    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.CHAR_CLASS;
    }

    int findCandidate(CharSequence text, int fromIndex, boolean unixLines) {
      int start = Math.max(0, fromIndex);
      if (offset >= text.length() - start) {
        return -1;
      }
      int position = start + offset;
      int cheapRejections = 0;
      while (position < text.length()) {
        int hit = findNextCandidate(inner, text, position, unixLines);
        if (hit < 0) {
          return -1;
        }
        int candidate = hit - offset;
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        if (leadingClass.contains(text.charAt(candidate))) {
          return candidate;
        }
        if (cheapRejections < AcceleratorPolicy.FIXED_OFFSET_CLASS_CHEAP_REJECTIONS) {
          cheapRejections++;
          position = hit + 1;
          continue;
        }
        int nextLeading = indexOfClass(text, leadingClass, candidate + 1);
        if (nextLeading < 0 || offset >= text.length() - nextLeading) {
          return -1;
        }
        position = nextLeading + offset;
      }
      return -1;
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
      // Three members cost three intrinsic passes over a gap, which has not been measured against
      // the table scan below, so only one- and two-member sets take the per-member search.
      if (scanInfo instanceof CharClassScanInfo.SmallSet smallSet && smallSet.chars().length <= 2) {
        return StringInputScanner.indexOfSmallSet(text, smallSet.chars(), fromIndex);
      }
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

    int findCandidate(CharSequence text, int fromIndex, boolean unixLines) {
      return nextAcceleratedStart(text, startAcceleration, fromIndex, unixLines);
    }

    private static int nextAcceleratedStart(
        CharSequence text, StartAcceleration acceleration, int fromIndex, boolean unixLines) {
      int start = Math.max(0, fromIndex);
      for (int i = start; i < text.length(); i++) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        if (matchesStartAcceleration(text, i, acceleration, unixLines)) {
          return i;
        }
        int cp = Character.codePointAt(text, i);
        i += Character.charCount(cp) - 1;
      }
      return -1;
    }

    private static boolean matchesStartAcceleration(
        CharSequence text, int pos, StartAcceleration acceleration, boolean unixLines) {
      boolean lineStart = isBeginLine(text, pos, unixLines);
      boolean asciiStart = matchesAsciiStart(text, pos, acceleration.asciiStart);
      if (acceleration.requireLineStart) {
        return lineStart && (acceleration.asciiStart == null || asciiStart);
      }
      return (acceleration.allowLineStart && lineStart) || asciiStart;
    }

    private static boolean matchesAsciiStart(CharSequence text, int pos, AsciiBitmap asciiStart) {
      if (asciiStart == null || pos >= text.length()) {
        return false;
      }
      char ch = text.charAt(pos);
      return asciiStart.contains(ch);
    }

    private static boolean isBeginLine(CharSequence text, int pos, boolean unixLines) {
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

  // Arrays are immutable, privately owned scanner metadata; array identity is never observed.
  @SuppressWarnings("ArrayRecordComponent")
  record LeadingExpansion(
      CharClassScanInfo leadingClass,
      int minRepetition,
      int maxRepetition,
      boolean hasLeadingAssertions,
      boolean wideLeadingClass,
      char[] excludedChars,
      StringStartAccelerator inner)
      implements StringStartAccelerator {

    /**
     * Returns the code units a wide leading class excludes, when a forward search for them can
     * stand in for the backward walk: the class must exclude only BMP non-surrogate code points,
     * and the repetition must be unbounded with at most one required repetition. Otherwise null.
     */
    static char[] forwardScanChars(MultiAnchorDescriptor.StartPlan.LeadingExpansion le) {
      int[] excluded = MultiAnchorCompiler.excludedCodePoints(le.leadingClass());
      if (excluded == null || le.minRepetition() > 1 || le.maxRepetition() != Integer.MAX_VALUE) {
        return null;
      }
      char[] chars = new char[excluded.length];
      for (int i = 0; i < excluded.length; i++) {
        int cp = excluded[i];
        if (cp > Character.MAX_VALUE || Character.isSurrogate((char) cp)) {
          return null;
        }
        chars[i] = (char) cp;
      }
      return chars;
    }

    /** Returns whether {@code text[from, to)} holds a code unit the leading class excludes. */
    private boolean containsExcluded(String text, int from, int to) {
      for (char c : excludedChars) {
        int hit = text.indexOf(c, from, to);
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(hit < 0 ? to - from : hit - from + 1);
        }
        if (hit >= 0) {
          return true;
        }
      }
      return false;
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LEADING_EXPANSION.withStrategy(inner.policy().strategy());
    }

    /**
     * Returns whether an unanchored DFA probe from the inner candidate can reject every expanded
     * start at once. Not for a wide leading class such as non-DOTALL {@code .}: its expanded start
     * is the line start, and when the probe succeeds on a later line the search scans that gap a
     * second time from the expanded start. Searching from the expanded start directly is never
     * worse than the probe there.
     */
    boolean canVerifyAtInner() {
      return minRepetition == 0 && !hasLeadingAssertions && !wideLeadingClass;
    }

    int findInnerCandidate(CharSequence text, int searchPos, boolean unixLines) {
      return StringStartAccelerator.findNextCandidate(inner, text, searchPos, unixLines);
    }

    int expandBackward(CharSequence text, int innerMatch, int fromIndex) {
      int start = innerMatch;
      int count = 0;
      while (start > fromIndex) {
        int cp = Character.codePointBefore(text, start);
        int cpStart = start - Character.charCount(cp);
        if (cpStart < fromIndex) {
          // Preserve an explicit low-surrogate cursor when its high surrogate precedes fromIndex.
          cp = text.charAt(fromIndex);
          cpStart = fromIndex;
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

    int findCandidate(CharSequence text, int fromIndex, boolean unixLines) {
      int searchPos = Math.max(0, fromIndex);
      int textLen = text.length();
      // A wide class's walk covers the rest of the line, so a skip shorter than
      // MIN_WIDE_LEADING_SKIP saves less than the literal scan and the walk cost. Such searches
      // start at the search start, as they would with no start plan.
      if (wideLeadingClass && textLen - searchPos < MultiAnchorCompiler.MIN_WIDE_LEADING_SKIP) {
        return searchPos;
      }
      while (searchPos < textLen) {
        int innerMatch = findInnerCandidate(text, searchPos, unixLines);
        if (innerMatch < 0) {
          return -1;
        }
        if (wideLeadingClass
            && innerMatch - fromIndex < MultiAnchorCompiler.MIN_WIDE_LEADING_SKIP) {
          return Math.max(0, fromIndex);
        }
        // A forward scan can show that the walk would reach the search start. Probe only the
        // first hit: an excluded code point before it will also precede every later hit.
        if (excludedChars != null
            && searchPos == fromIndex
            && innerMatch > fromIndex
            && text instanceof String string
            && !containsExcluded(string, fromIndex, innerMatch)) {
          return fromIndex;
        }
        int start = innerMatch;
        int count = 0;
        while (start > fromIndex) {
          int cp = Character.codePointBefore(text, start);
          int cpStart = start - Character.charCount(cp);
          if (cpStart < fromIndex) {
            // Preserve an explicit low-surrogate cursor when its high surrogate precedes fromIndex.
            cp = text.charAt(fromIndex);
            cpStart = fromIndex;
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
