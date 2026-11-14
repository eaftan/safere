// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.safere.Pattern.FixedOffsetLiteral;

/**
 * Encapsulates pre-computed start acceleration search strategies for finding candidate match
 * positions in a {@link Utf8InputScanner}.
 */
sealed interface Utf8StartAccelerator {

  /**
   * Creates a {@link Utf8StartAccelerator} for the given pattern descriptor, or {@code null} if no
   * acceleration strategy applies.
   */
  static Utf8StartAccelerator create(MultiAnchorDescriptor descriptor, boolean hasWordBoundary) {
    if (descriptor == null) {
      return null;
    }
    return create(descriptor.startPlan(), hasWordBoundary);
  }

  static Utf8StartAccelerator create(
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
          hasWordBoundary || !cc.scanInfo().isSelective() ? null : new CharClass(cc.scanInfo());
      case MultiAnchorDescriptor.StartPlan.FixedOffset fo -> FixedOffset.create(fo);
      case MultiAnchorDescriptor.StartPlan.FixedOffsetClass fc ->
          new FixedOffsetClass(fc.scanInfo(), fc.offset(), fc.leadingClass());
      case MultiAnchorDescriptor.StartPlan.MultiLiteral ml -> {
        if (hasWordBoundary) {
          yield null;
        }
        if (VectorScanProviders.vectorProviderAvailable()) {
          MultiLiteralInfo info = MultiLiteralInfo.create(ml.literals());
          if (info != null) {
            TeddyModel teddy =
                VectorScanProviders.vectorProviderAvailable()
                    ? TeddyModel.compileForSelectedProvider(ml.literals())
                    : null;
            yield new MultiLiteral(info, teddy);
          }
        }
        if (VectorScanProviders.vectorProviderAvailable()) {
          TeddyModel model = TeddyModel.compileForSelectedProvider(ml.literals());
          if (model != null) {
            yield new Teddy(model);
          }
        }
        if (ml.fallbackClass() != null && ml.fallbackClass().isSelective()) {
          yield new CharClass(ml.fallbackClass());
        }
        yield null;
      }
      case MultiAnchorDescriptor.StartPlan.LeadingExpansion le -> {
        Utf8StartAccelerator inner = create(le.innerPlan(), hasWordBoundary);
        // This wrapper does not retain the folded filter's per-search scan cursor, and an optional
        // single-character leading class before a non-ASCII inner class is faster in the UTF-8
        // byte DFA than running an un-memoized scalar UTF-8 code-point scan plus inner DFA check.
        if (inner instanceof UnicodeCaseInsensitiveLiteral
            || (le.maxRepetition() == 1
                && inner instanceof CharClass cc
                && !cc.scanInfo().isAscii())) {
          yield null;
        }
        yield inner != null
            ? new LeadingExpansion(
                le.leadingClass(),
                le.minRepetition(),
                le.maxRepetition(),
                le.hasLeadingAssertions(),
                MultiAnchorCompiler.isWideLeadingClass(le.leadingClass()),
                LeadingExpansion.ExcludedSet.of(le),
                inner)
            : null;
      }
      case MultiAnchorDescriptor.StartPlan.LineAnchor unusedLa -> null;
    };
  }

  /**
   * Finds the next candidate match start position at or after {@code pos} using pattern-matched
   * devirtualization.
   *
   * <p>Direct sealed-type pattern matching avoids {@code invokeinterface} dispatch overhead on hot
   * matching loops. HotSpot C2 does not automatically devirtualize megamorphic interface calls with
   * &ge; 3 implementations across the JVM lifecycle; switching over the sealed record subtypes here
   * allows C2 to inline candidate searches directly into caller loops.
   */
  static int findNextCandidate(
      Utf8StartAccelerator accelerator, Utf8InputScanner scanner, int pos) {
    return switch (accelerator) {
      case Literal lit -> lit.findCandidate(scanner, pos);
      case CaseInsensitiveLiteral cil -> cil.findCandidate(scanner, pos);
      case UnicodeCaseInsensitiveLiteral unicode -> unicode.findCandidate(scanner, pos);
      case FixedOffset fo -> fo.findCandidate(scanner, pos);
      case FixedOffsetClass fc -> fc.findCandidate(scanner, pos);
      case CharClass cc -> cc.findCandidate(scanner, pos);
      case Teddy t -> t.findCandidate(scanner, pos);
      case MultiLiteral ml -> ml.findCandidate(scanner, pos);
      case LeadingExpansion le -> le.findCandidate(scanner, pos);
    };
  }

  /** A search-local cursor for accelerators that scan multiple folded byte variants. */
  interface SearchCursor {
    int findCandidate(Utf8InputScanner scanner, int fromIndex);
  }

  static SearchCursor searchCursor(Utf8StartAccelerator accelerator) {
    return accelerator instanceof UnicodeCaseInsensitiveLiteral unicode
        ? unicode.new SearchCursorImpl()
        : null;
  }

  /** Returns the tuning and diagnostic policy for this accelerator. */
  default AcceleratorPolicy policy() {
    return AcceleratorPolicy.DEFAULT;
  }

  // The arrays are immutable search metadata owned by this internal accelerator.
  @SuppressWarnings("ArrayRecordComponent")
  record Literal(
      byte[] prefixUtf8, int[] prefixUtf8Failure, int[] prefixUtf8Shifts, int rareByteOffset)
      implements Utf8StartAccelerator {

    static Literal create(String prefix) {
      byte[] utf8 = prefix.getBytes(StandardCharsets.UTF_8);
      return new Literal(
          utf8,
          Pattern.literalFailure(utf8),
          Pattern.literalShifts(utf8),
          RarityOracle.rarestUtf8LiteralByteOffset(utf8));
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      if (prefixUtf8 != null) {
        return scanner.indexOf(
            prefixUtf8, prefixUtf8Failure, prefixUtf8Shifts, fromIndex, rareByteOffset);
      }
      return fromIndex;
    }
  }

  @SuppressWarnings("ArrayRecordComponent")
  record CaseInsensitiveLiteral(
      String prefix,
      int[] failure,
      int anchorOffset,
      byte anchorLow,
      byte anchorHigh,
      int anchorOffset2,
      byte anchorLow2,
      byte anchorHigh2)
      implements Utf8StartAccelerator {

    static Utf8StartAccelerator create(String prefix) {
      for (int i = 0; i < prefix.length(); i++) {
        if (prefix.charAt(i) > 127) {
          return null;
        }
      }
      int len = prefix.length();
      if (len >= 2) {
        RarityOracle.AsciiPair pair = RarityOracle.rarestAsciiPairIgnoreCase(prefix, len);
        if (pair != null) {
          return new CaseInsensitiveLiteral(
              prefix,
              Ascii.ignoreCaseFailure(prefix),
              pair.offset1(),
              pair.low1(),
              pair.high1(),
              pair.offset2(),
              pair.low2(),
              pair.high2());
        }
      }
      int anchorOffset = RarityOracle.rarestAsciiOffset(prefix, len, true);
      char anchor = prefix.charAt(anchorOffset);
      byte low = (byte) Ascii.toLowerCase(anchor);
      byte high = (byte) Ascii.toUpperCase(anchor);
      return new CaseInsensitiveLiteral(
          prefix, Ascii.ignoreCaseFailure(prefix), anchorOffset, low, high, -1, (byte) 0, (byte) 0);
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      if (anchorOffset2 >= 0) {
        return scanner.indexOfPairIgnoreCase(
            prefix,
            failure,
            anchorOffset,
            anchorLow,
            anchorHigh,
            anchorOffset2,
            anchorLow2,
            anchorHigh2,
            fromIndex);
      }
      return scanner.indexOfIgnoreCase(
          prefix, failure, anchorOffset, anchorLow, anchorHigh, fromIndex);
    }
  }

  /** A bounded byte filter for Unicode-folded literal prefixes on UTF-8 input. */
  final class UnicodeCaseInsensitiveLiteral implements Utf8StartAccelerator {
    private static final int MAX_FOLD_VARIANTS = 4;
    private static final int ANCHOR_SCAN_WINDOW = 256;

    private final List<int[]> prefixFoldVariants;
    private final int anchorByteOffset;
    private final List<EncodedFold> anchorVariants;

    private UnicodeCaseInsensitiveLiteral(
        List<int[]> prefixFoldVariants, int anchorByteOffset, List<EncodedFold> anchorVariants) {
      this.prefixFoldVariants = prefixFoldVariants;
      this.anchorByteOffset = anchorByteOffset;
      this.anchorVariants = anchorVariants;
    }

    static Utf8StartAccelerator create(String prefix) {
      int[] codePoints = prefix.codePoints().toArray();
      int stablePrefixBytes = 0;
      int bestRarity = -1;
      int bestOffset = 0;
      List<EncodedFold> bestVariants = null;
      List<int[]> prefixFoldVariants = new ArrayList<>(codePoints.length);
      boolean stablePrefixWidth = true;
      for (int codePoint : codePoints) {
        if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
          return null;
        }
        int[] foldVariants = foldVariants(codePoint);
        if (foldVariants == null) {
          return null;
        }
        prefixFoldVariants.add(foldVariants);
        if (!stablePrefixWidth) {
          continue;
        }
        List<EncodedFold> variants = encodedFoldVariants(foldVariants);
        int rarity = 255;
        int width = variants.getFirst().bytes.length;
        boolean stableWidth = true;
        for (EncodedFold variant : variants) {
          int variantRarity = 0;
          for (byte value : variant.bytes) {
            variantRarity = Math.max(variantRarity, RarityOracle.exactByteRarity(value & 0xFF));
          }
          rarity = Math.min(rarity, variantRarity);
          stableWidth &= variant.bytes.length == width;
        }
        if (rarity > bestRarity) {
          bestRarity = rarity;
          bestOffset = stablePrefixBytes;
          bestVariants = variants;
        }
        // A later anchor has no fixed byte offset if an earlier fold changes UTF-8 width.
        if (!stableWidth) {
          stablePrefixWidth = false;
        } else {
          stablePrefixBytes += width;
        }
      }
      return bestVariants == null
          ? null
          : new UnicodeCaseInsensitiveLiteral(prefixFoldVariants, bestOffset, bestVariants);
    }

    private static int[] foldVariants(int codePoint) {
      CharClassBuilder builder = new CharClassBuilder();
      UnicodeCaseFolding.addUnicodeFoldedRange(builder, codePoint, codePoint);
      org.safere.CharClass closure = builder.build();
      if (closure.numRunes() > MAX_FOLD_VARIANTS) {
        return null;
      }
      int[] variants = new int[closure.numRunes()];
      int count = 0;
      for (int i = 0; i < closure.numRanges(); i++) {
        for (int cp = closure.lo(i); cp <= closure.hi(i); cp++) {
          variants[count++] = cp;
        }
      }
      return variants;
    }

    private static List<EncodedFold> encodedFoldVariants(int[] foldVariants) {
      List<EncodedFold> variants = new ArrayList<>();
      for (int folded : foldVariants) {
        byte[] bytes = new String(Character.toChars(folded)).getBytes(StandardCharsets.UTF_8);
        variants.add(new EncodedFold(bytes, Pattern.literalFailure(bytes)));
      }
      return variants;
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      return new SearchCursorImpl().findCandidate(scanner, fromIndex);
    }

    /** Keeps candidate verification work bounded across one forward search. */
    final class SearchCursorImpl implements SearchCursor {
      private boolean exhausted;
      private long verificationWork;
      private long workLimit = -1;

      @Override
      public int findCandidate(Utf8InputScanner scanner, int fromIndex) {
        int start = Math.max(0, fromIndex);
        if (exhausted) {
          return start;
        }
        if (anchorByteOffset > scanner.length() - start) {
          return -1;
        }
        if (workLimit < 0) {
          workLimit = WorkLimit.forRemaining(scanner.length() - start);
        }
        int searchFrom = start + anchorByteOffset;
        while (searchFrom < scanner.length()) {
          int windowEnd =
              searchFrom + Math.min(ANCHOR_SCAN_WINDOW, scanner.length() - searchFrom) - 1;
          int nextHit = Integer.MAX_VALUE;
          for (EncodedFold variant : anchorVariants) {
            int hit =
                scanner.indexOfWithin(
                    variant.bytes, variant.failure, searchFrom, Math.min(windowEnd, nextHit - 1));
            if (hit >= 0) {
              nextHit = hit;
              if (hit == searchFrom) {
                break;
              }
            }
          }
          if (nextHit == Integer.MAX_VALUE) {
            searchFrom = windowEnd + 1;
            continue;
          }
          int candidate = nextHit - anchorByteOffset;
          if (candidate >= start
              && scanner.isCodePointBoundary(candidate)
              && matchesPrefix(scanner, candidate)) {
            return candidate;
          }
          verificationWork += prefixFoldVariants.size();
          if (WorkLimit.isExhausted(verificationWork, workLimit)) {
            // A search can call this cursor again at every subsequent byte. Keep the fallback
            // sticky so candidate verification cannot restart from scratch.
            exhausted = true;
            return start;
          }
          searchFrom = nextHit + 1;
        }
        return -1;
      }
    }

    private boolean matchesPrefix(Utf8InputScanner scanner, int candidate) {
      int position = candidate;
      for (int[] foldVariants : prefixFoldVariants) {
        if (position >= scanner.length()) {
          return false;
        }
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        long decoded = scanner.decodeForward(position);
        int actual = InputScanner.codePoint(decoded);
        boolean equivalent = false;
        for (int variant : foldVariants) {
          if (actual == variant) {
            equivalent = true;
            break;
          }
        }
        if (!equivalent) {
          return false;
        }
        position = InputScanner.position(decoded);
      }
      return true;
    }

    private static final class EncodedFold {
      private final byte[] bytes;
      private final int[] failure;

      private EncodedFold(byte[] bytes, int[] failure) {
        this.bytes = bytes;
        this.failure = failure;
      }
    }
  }

  record FixedOffset(
      FixedOffsetLiteral fixedOffset,
      CharClassScanInfo charClassPrefix,
      Literal leadingLiteral,
      boolean plainMultiOffset)
      implements Utf8StartAccelerator {

    static FixedOffset create(MultiAnchorDescriptor.StartPlan.FixedOffset plan) {
      Literal leading = null;
      if (plan.leadingLiteral() != null) {
        String prefix = plan.leadingLiteral();
        byte[] utf8 = prefix.getBytes(StandardCharsets.UTF_8);
        // Dense interior hits have already defeated the primary filter. Search any rare prefix
        // byte, including an endpoint, with the existing budgeted scan and linear fallback.
        // Short tails use KMP; an alternate filter needs no retained Horspool shift table.
        leading =
            new Literal(
                utf8,
                Pattern.literalFailure(utf8),
                null,
                RarityOracle.rarestAsciiOffset(prefix, prefix.length()));
      }
      return new FixedOffset(
          plan.fol(),
          plan.leadingClass(),
          leading,
          plan.leadingClass() != null
              && plan.fol().discreteOffsets() != null
              && plan.fol().discreteOffsets().length > 1
              && !plan.leadingClass().isSelective());
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      if (plainMultiOffset) {
        int minOffset = fixedOffset.minOffset();
        return minOffset > scanner.length() - fromIndex
            ? -1
            : findUnfilteredMultiOffsetCandidate(
                scanner, fixedOffset, charClassPrefix, fromIndex + minOffset, fromIndex);
      }
      return nextFixedOffsetCandidate(
          scanner, fixedOffset, charClassPrefix, leadingLiteral, fromIndex);
    }

    private static int nextFixedOffsetCandidate(
        Utf8InputScanner scanner,
        FixedOffsetLiteral fixedOffsetLiteral,
        CharClassScanInfo charClassPrefix,
        Literal leadingLiteral,
        int searchFrom) {
      int literalFrom = searchFrom + fixedOffsetLiteral.minOffset();
      int[] discreteOffsets = fixedOffsetLiteral.discreteOffsets();
      int cheapRejections = 0;
      while (literalFrom <= scanner.length()) {
        int literalStart =
            scanner.indexOf(
                fixedOffsetLiteral.utf8(),
                fixedOffsetLiteral.failure(),
                fixedOffsetLiteral.shifts(),
                literalFrom);
        if (literalStart < 0) {
          return -1;
        }
        if (charClassPrefix != null) {
          if (discreteOffsets != null && discreteOffsets.length == 1) {
            int candidateStart = literalStart - discreteOffsets[0];
            if (candidateStart >= searchFrom) {
              int first =
                  candidateStart < scanner.length() ? scanner.codePointAt(candidateStart) : -1;
              if (first >= 0 && charClassPrefix.contains(first)) {
                return candidateStart;
              }
            }
            literalFrom = literalStart + 1;
            continue;
          } else if (discreteOffsets != null) {
            int resolved =
                resolveMultiOffsetStart(
                    scanner,
                    fixedOffsetLiteral,
                    discreteOffsets,
                    charClassPrefix,
                    literalStart,
                    searchFrom);
            if (resolved >= 0) {
              return resolved;
            }
            literalFrom = literalStart + 1;
            if (++cheapRejections == AcceleratorPolicy.FIXED_OFFSET_LITERAL_CHEAP_REJECTIONS
                && scanner.length() - literalFrom
                    >= AcceleratorPolicy.FIXED_OFFSET_LITERAL_MIN_REMAINING
                && (leadingLiteral != null
                    || charClassPrefix instanceof CharClassScanInfo.AsciiSmallSet)) {
              return findFilteredMultiOffsetCandidate(
                  scanner,
                  fixedOffsetLiteral,
                  charClassPrefix,
                  leadingLiteral,
                  literalFrom,
                  searchFrom);
            }
            continue;
          } else if (fixedOffsetLiteral.minOffset() == fixedOffsetLiteral.maxOffset()) {
            int candidateStart =
                retreatedStartInClass(
                    scanner, fixedOffsetLiteral, charClassPrefix, literalStart, searchFrom);
            if (candidateStart >= 0) {
              return candidateStart;
            }
            literalFrom = literalStart + 1;
            continue;
          }
        }
        return Math.max(
            searchFrom, scanner.retreatByCodePoints(literalStart, fixedOffsetLiteral.maxOffset()));
      }
      return -1;
    }

    /** Alternates monotonic scans, abandoning the leading filter after an unprofitable skip. */
    private static int findFilteredMultiOffsetCandidate(
        Utf8InputScanner scanner,
        FixedOffsetLiteral fixedOffsetLiteral,
        CharClassScanInfo charClassPrefix,
        Literal leadingLiteral,
        int literalFrom,
        int searchFrom) {
      int minOffset = fixedOffsetLiteral.minOffset();
      int[] discreteOffsets = fixedOffsetLiteral.discreteOffsets();
      int nextLeading = -1;
      while (literalFrom <= scanner.length()) {
        // A later hit cannot imply a start below this floor, even across widely spaced offsets.
        int leadingFrom = Math.max(searchFrom, literalFrom - fixedOffsetLiteral.maxOffset());
        if (nextLeading < leadingFrom) {
          nextLeading =
              leadingLiteral != null
                  ? leadingLiteral.findCandidate(scanner, leadingFrom)
                  : scanner.indexOfCharClass(charClassPrefix, leadingFrom);
        }
        if (nextLeading < 0 || minOffset > scanner.length() - nextLeading) {
          return -1;
        }
        int nextLiteralFrom = Math.max(literalFrom, nextLeading + minOffset);
        if (nextLiteralFrom - literalFrom < AcceleratorPolicy.LITERAL.minProfitableSkip()) {
          return findUnfilteredMultiOffsetCandidate(
              scanner, fixedOffsetLiteral, charClassPrefix, nextLiteralFrom, searchFrom);
        }
        literalFrom = nextLiteralFrom;
        int hit =
            scanner.indexOf(
                fixedOffsetLiteral.utf8(),
                fixedOffsetLiteral.failure(),
                fixedOffsetLiteral.shifts(),
                literalFrom);
        if (hit < 0) {
          return -1;
        }
        int resolved =
            resolveMultiOffsetStart(
                scanner, fixedOffsetLiteral, discreteOffsets, charClassPrefix, hit, searchFrom);
        if (resolved >= 0) {
          return resolved;
        }
        literalFrom = hit + 1;
      }
      return -1;
    }

    /** Continues from the established floor after an unprofitable leading probe. */
    private static int findUnfilteredMultiOffsetCandidate(
        Utf8InputScanner scanner,
        FixedOffsetLiteral fixedOffsetLiteral,
        CharClassScanInfo charClassPrefix,
        int literalFrom,
        int searchFrom) {
      int[] discreteOffsets = fixedOffsetLiteral.discreteOffsets();
      while (literalFrom <= scanner.length()) {
        int hit =
            scanner.indexOf(
                fixedOffsetLiteral.utf8(),
                fixedOffsetLiteral.failure(),
                fixedOffsetLiteral.shifts(),
                literalFrom);
        if (hit < 0) {
          return -1;
        }
        int resolved =
            resolveMultiOffsetStart(
                scanner, fixedOffsetLiteral, discreteOffsets, charClassPrefix, hit, searchFrom);
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
     *
     * <p>Discrete offsets are only recorded for all-ASCII prefixes, so they are byte counts here.
     */
    private static int resolveMultiOffsetStart(
        InputScanner scanner,
        FixedOffsetLiteral fixedOffsetLiteral,
        int[] discreteOffsets,
        CharClassScanInfo charClassPrefix,
        int literalStart,
        int searchFrom) {
      for (int i = discreteOffsets.length - 1; i >= 0; i--) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        int start = literalStart - discreteOffsets[i];
        if (start >= searchFrom
            && start < scanner.length()
            && charClassPrefix.contains(scanner.codePointAt(start))) {
          return Math.max(
              searchFrom, Math.min(start, literalStart + 1 - fixedOffsetLiteral.maxOffset()));
        }
      }
      return -1;
    }

    /**
     * Returns the start reached by retreating from {@code literalStart} over a fixed number of code
     * points when the leading class admits it, or -1 when the occurrence can be skipped.
     */
    private static int retreatedStartInClass(
        InputScanner scanner,
        FixedOffsetLiteral fixedOffsetLiteral,
        CharClassScanInfo charClassPrefix,
        int literalStart,
        int searchFrom) {
      int candidateStart =
          scanner.retreatByCodePoints(literalStart, fixedOffsetLiteral.maxOffset());
      if (candidateStart >= searchFrom) {
        int first = candidateStart < scanner.length() ? scanner.codePointAt(candidateStart) : -1;
        if (first >= 0 && charClassPrefix.contains(first)) {
          return candidateStart;
        }
      }
      return -1;
    }
  }

  // nonAsciiUtf8 is an internal precomputed UTF-8 byte sequence, never mutated or exposed.
  @SuppressWarnings("ArrayRecordComponent")
  record CharClass(CharClassScanInfo scanInfo, byte[] nonAsciiUtf8)
      implements Utf8StartAccelerator {

    CharClass(CharClassScanInfo scanInfo) {
      this(scanInfo, nonAsciiUtf8(scanInfo));
    }

    private static byte[] nonAsciiUtf8(CharClassScanInfo scanInfo) {
      if (scanInfo instanceof CharClassScanInfo.UnicodeSmallSet smallSet) {
        char[] chars = smallSet.chars();
        if (chars.length == 1) {
          return String.valueOf(chars[0]).getBytes(StandardCharsets.UTF_8);
        }
        if (chars.length == 2 && chars[0] < 0x80 && chars[1] >= 0x80) {
          return String.valueOf(chars[1]).getBytes(StandardCharsets.UTF_8);
        }
      }
      return null;
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.CHAR_CLASS;
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      if (scanInfo == null) {
        return fromIndex;
      }
      return switch (scanInfo) {
        case CharClassScanInfo.UnicodeSmallSet smallSet when nonAsciiUtf8 != null -> {
          char[] chars = smallSet.chars();
          int length = scanner.length();
          if (chars.length == 1) {
            yield scanner.indexOfUtf8Sequence(nonAsciiUtf8, fromIndex, length);
          }
          int ascii = chars[0];
          int first = scanner.indexOfAsciiOrNonAscii(ascii, fromIndex, length);
          if (first < 0 || scanner.asciiAt(first) == ascii) {
            yield first;
          }
          yield scanner.indexOfMixedPair(ascii, nonAsciiUtf8, first, length);
        }
        case CharClassScanInfo.AsciiSmallSet smallSet -> {
          char[] chars = smallSet.chars();
          yield switch (chars.length) {
            case 1 -> scanner.indexOfAscii(chars[0], fromIndex, scanner.length());
            case 2 -> scanner.indexOfAsciiPair(chars[0], chars[1], fromIndex, scanner.length());
            case 3 ->
                scanner.indexOfCodePointClass(
                    smallSet.ranges(),
                    smallSet.bitmap0(),
                    smallSet.bitmap1(),
                    fromIndex,
                    scanner.length());
            default ->
                scanner.indexOfCodePointClass(
                    smallSet.ranges(),
                    smallSet.bitmap0(),
                    smallSet.bitmap1(),
                    fromIndex,
                    scanner.length());
          };
        }
        case CharClassScanInfo other ->
            scanner.indexOfCodePointClass(
                other.ranges(), other.bitmap0(), other.bitmap1(), fromIndex, scanner.length());
      };
    }
  }

  record Teddy(TeddyModel model) implements Utf8StartAccelerator {
    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.VECTOR_MULTI_LITERAL;
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      int window = scanner.length() - fromIndex;
      VectorScanProvider provider = VectorScanProviders.providerFor(ScanKind.TEDDY, window);
      if (provider == null) {
        ScanAudit.record(ScanKind.TEDDY, ScanDirection.FORWARD, window, ScanPath.DECLINED);
        return fromIndex;
      }
      int idx =
          provider.indexOfTeddy(
              scanner.bytes(), scanner.offset(), scanner.length(), model, fromIndex);
      if (idx != VectorScanProvider.UNSUPPORTED) {
        ScanAudit.record(ScanKind.TEDDY, ScanDirection.FORWARD, window, ScanPath.VECTOR);
        return idx;
      }
      ScanAudit.record(ScanKind.TEDDY, ScanDirection.FORWARD, window, ScanPath.SCALAR);
      int len = scanner.length();
      int minLen = model.minLength();
      byte[] bytes = scanner.bytes();
      int offset = scanner.offset();
      for (int i = fromIndex; i <= len - minLen; i++) {
        for (String lit : model.literals()) {
          if (i + lit.length() <= len
              && Ascii.regionMatches(bytes, offset + i, lit, lit.length())) {
            return i;
          }
        }
      }
      return -1;
    }
  }

  /**
   * Scans a required interior class following an exact-width ASCII prefix.
   *
   * <p>ASCII offsets are also byte offsets. After a bounded number of cheap leading-check failures,
   * scans jump to the next leading-class hit before resuming the interior scan. Both scans advance
   * monotonically, including when interior hits are dense and leading hits are absent. ASCII
   * leading hits exclude continuation-byte starts.
   */
  record FixedOffsetClass(CharClassScanInfo scanInfo, int offset, CharClassScanInfo leadingClass)
      implements Utf8StartAccelerator {
    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.CHAR_CLASS;
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      int start = Math.max(0, fromIndex);
      if (offset >= scanner.length() - start) {
        return -1;
      }
      int position = start + offset;
      int cheapRejections = 0;
      while (position < scanner.length()) {
        int hit =
            scanner.indexOfCodePointClass(
                scanInfo.ranges(),
                scanInfo.bitmap0(),
                scanInfo.bitmap1(),
                position,
                scanner.length());
        if (hit < 0) {
          return -1;
        }
        int candidate = hit - offset;
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        if (leadingClass.contains(scanner.asciiAt(candidate))) {
          return candidate;
        }
        if (cheapRejections < AcceleratorPolicy.FIXED_OFFSET_CLASS_CHEAP_REJECTIONS) {
          cheapRejections++;
          position = hit + 1;
          continue;
        }
        int[] leadingRanges = leadingClass.ranges();
        // A singleton leading class can reuse the dedicated byte scanner.
        int nextLeading =
            leadingRanges.length == 2 && leadingRanges[0] == leadingRanges[1]
                ? scanner.indexOfAscii(leadingRanges[0], candidate + 1, scanner.length())
                : scanner.indexOfCharClass(leadingClass, candidate + 1);
        if (nextLeading < 0 || offset >= scanner.length() - nextLeading) {
          return -1;
        }
        position = nextLeading + offset;
      }
      return -1;
    }
  }

  record LeadingExpansion(
      CharClassScanInfo leadingClass,
      int minRepetition,
      int maxRepetition,
      boolean hasLeadingAssertions,
      boolean wideLeadingClass,
      ExcludedSet excludedSet,
      Utf8StartAccelerator inner)
      implements Utf8StartAccelerator {

    /**
     * The code points a wide leading class excludes, split into ASCII members and the UTF-8
     * encodings of the others. Surrogates never occur in valid UTF-8 and are left out.
     */
    // Arrays are immutable, privately owned scanner metadata; array identity is never observed.
    @SuppressWarnings("ArrayRecordComponent")
    record ExcludedSet(int[] ascii, byte[][] nonAscii) {
      /**
       * Returns the excluded set when a forward search for it can stand in for the backward walk:
       * the repetition must be unbounded with at most one required repetition. Otherwise null.
       */
      static ExcludedSet of(MultiAnchorDescriptor.StartPlan.LeadingExpansion le) {
        int[] excluded = MultiAnchorCompiler.excludedCodePoints(le.leadingClass());
        if (excluded == null || le.minRepetition() > 1 || le.maxRepetition() != Integer.MAX_VALUE) {
          return null;
        }
        return new ExcludedSet(
            Arrays.stream(excluded).filter(cp -> cp < 0x80).toArray(),
            Arrays.stream(excluded)
                .filter(
                    cp ->
                        cp >= 0x80
                            && (cp > Character.MAX_VALUE || !Character.isSurrogate((char) cp)))
                .mapToObj(cp -> Character.toString(cp).getBytes(StandardCharsets.UTF_8))
                .toArray(byte[][]::new));
      }

      /**
       * Returns false only when {@code [from, to)} holds no excluded code point. With non-ASCII
       * members and up to three ASCII members, any non-ASCII byte also counts as a possible hit.
       */
      boolean mayContain(Utf8InputScanner scanner, int from, int to) {
        boolean anyNonAscii = nonAscii.length > 0;
        int hit =
            switch (ascii.length) {
              case 1 ->
                  anyNonAscii
                      ? scanner.indexOfAsciiOrNonAscii(ascii[0], from, to)
                      : scanner.indexOfAscii(ascii[0], from, to);
              case 2 ->
                  anyNonAscii
                      ? scanner.indexOfAsciiPairOrNonAscii(ascii[0], ascii[1], from, to)
                      : scanner.indexOfAsciiPair(ascii[0], ascii[1], from, to);
              case 3 ->
                  anyNonAscii
                      ? scanner.indexOfAsciiTripleOrNonAscii(ascii[0], ascii[1], ascii[2], from, to)
                      : scanner.indexOfAsciiTriple(ascii[0], ascii[1], ascii[2], from, to);
              default -> indexOfAny(scanner, from, to);
            };
        return hit >= 0 && hit < to;
      }

      private int indexOfAny(Utf8InputScanner scanner, int from, int to) {
        for (int c : ascii) {
          int hit = scanner.indexOfAscii(c, from, to);
          if (hit >= 0) {
            return hit;
          }
        }
        for (byte[] sequence : nonAscii) {
          int hit = scanner.indexOfUtf8Sequence(sequence, from, to);
          if (hit >= 0) {
            return hit;
          }
        }
        return -1;
      }
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

    int findInnerCandidate(Utf8InputScanner scanner, int searchPos) {
      if (wideLeadingClass && inner instanceof Literal lit && lit.prefixUtf8() != null) {
        // Search the way the UTF-8 reject prefilter does, since find() skips that prefilter when
        // this search subsumes it (see Pattern.startSubsumesFindReject).
        return scanner.indexOfInTail(lit.prefixUtf8(), lit.prefixUtf8Failure(), searchPos);
      }
      return Utf8StartAccelerator.findNextCandidate(inner, scanner, searchPos);
    }

    int expandBackward(Utf8InputScanner scanner, int innerMatch, int fromIndex) {
      int start = innerMatch;
      int count = 0;
      while (start > fromIndex) {
        int cp = scanner.singleUnitCodePointBefore(start);
        int prevPos;
        if (cp >= 0) {
          prevPos = start - 1;
        } else {
          long decoded = scanner.decodeBackward(start);
          cp = InputScanner.codePoint(decoded);
          prevPos = InputScanner.position(decoded);
        }
        if (!leadingClass.contains(cp)) {
          break;
        }
        if (count + 1 > maxRepetition) {
          break;
        }
        count++;
        start = prevPos;
      }
      return start;
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      int searchPos = Math.max(0, fromIndex);
      int textLen = scanner.length();
      // A wide class's walk covers the rest of the line, so a skip shorter than
      // MIN_WIDE_LEADING_SKIP saves less than the literal scan and the walk cost. Such searches
      // start at the search start, as they would with no start plan.
      if (wideLeadingClass && textLen - searchPos < MultiAnchorCompiler.MIN_WIDE_LEADING_SKIP) {
        return searchPos;
      }
      while (searchPos < textLen) {
        int innerMatch = findInnerCandidate(scanner, searchPos);
        if (innerMatch < 0) {
          return -1;
        }
        if (wideLeadingClass
            && innerMatch - fromIndex < MultiAnchorCompiler.MIN_WIDE_LEADING_SKIP) {
          return Math.max(0, fromIndex);
        }
        // A forward scan can show that the walk would reach the search start. Probe only the
        // first hit: an excluded code point before it will also precede every later hit.
        if (excludedSet != null
            && searchPos == fromIndex
            && innerMatch > fromIndex
            && !excludedSet.mayContain(scanner, fromIndex, innerMatch)) {
          return fromIndex;
        }
        int start = innerMatch;
        int count = 0;
        while (start > fromIndex) {
          int cp = scanner.singleUnitCodePointBefore(start);
          int prevPos;
          if (cp >= 0) {
            prevPos = start - 1;
          } else {
            long decoded = scanner.decodeBackward(start);
            cp = InputScanner.codePoint(decoded);
            prevPos = InputScanner.position(decoded);
          }
          if (!leadingClass.contains(cp)) {
            break;
          }
          if (count + 1 > maxRepetition) {
            break;
          }
          count++;
          start = prevPos;
        }
        if (count >= minRepetition) {
          return start;
        }
        searchPos = innerMatch + 1;
      }
      return -1;
    }
  }

  record MultiLiteral(MultiLiteralInfo info, TeddyModel teddyModel)
      implements Utf8StartAccelerator {
    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.VECTOR_MULTI_LITERAL;
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      int window = scanner.length() - fromIndex;
      VectorScanProvider provider = VectorScanProviders.providerFor(ScanKind.MULTI_LITERAL, window);
      if (provider != null) {
        int idx =
            provider.indexOfMultiLiteral(
                scanner.bytes(),
                scanner.offset(),
                scanner.length(),
                info.literals(),
                info.anchorChars(),
                info.anchorOffsets(),
                info.anchorRanges(),
                info.minLength(),
                teddyModel,
                fromIndex);
        if (idx != VectorScanProvider.UNSUPPORTED) {
          ScanAudit.record(ScanKind.MULTI_LITERAL, ScanDirection.FORWARD, window, ScanPath.VECTOR);
          return idx;
        }
        ScanAudit.record(ScanKind.MULTI_LITERAL, ScanDirection.FORWARD, window, ScanPath.DECLINED);
        return fromIndex;
      }
      ScanAudit.record(ScanKind.MULTI_LITERAL, ScanDirection.FORWARD, window, ScanPath.SCALAR);
      return findScalar(scanner, fromIndex);
    }

    private int findScalar(Utf8InputScanner scanner, int fromIndex) {
      int len = scanner.length();
      int minLen = info.minLength();
      String[] literals = info.literals();
      char[] anchorChars = info.anchorChars();
      int[] anchorOffsets = info.anchorOffsets();
      byte[] bytes = scanner.bytes();
      int offset = scanner.offset();
      long verificationWork = 0;
      long workLimit = WorkLimit.forRemaining(len - fromIndex);

      for (int i = fromIndex; i <= len - minLen; i++) {
        int val = bytes[offset + i] & 0xFF;
        for (int k = 0; k < literals.length; k++) {
          if (val == (anchorChars[k] & 0xFF)) {
            String lit = literals[k];
            int start = i - anchorOffsets[k];
            if (start >= fromIndex && start + lit.length() <= len) {
              if (WorkCounterConfig.ENABLED) {
                WorkCounter.record(lit.length());
              }
              if (Ascii.regionMatches(bytes, offset + start, lit, lit.length())) {
                return start;
              }
              verificationWork += lit.length();
              if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                return fromIndex;
              }
            }
          }
        }
      }
      return -1;
    }
  }
}
