// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.nio.charset.StandardCharsets;
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
              ? CaseInsensitiveLiteral.create(lit.prefix())
              : Literal.create(lit.prefix());
      case MultiAnchorDescriptor.StartPlan.CharClass cc ->
          hasWordBoundary || !cc.scanInfo().isSelective() ? null : new CharClass(cc.scanInfo());
      case MultiAnchorDescriptor.StartPlan.FixedOffset fo ->
          new FixedOffset(fo.fol(), fo.leadingClass());
      case MultiAnchorDescriptor.StartPlan.MultiLiteral ml -> {
        if (hasWordBoundary) {
          yield null;
        }
        int k = ml.literals().length;
        if (k <= 4 && VectorScanProviders.multiLiteralProviderAvailable()) {
          MultiLiteralInfo info = MultiLiteralInfo.create(ml.literals());
          if (info != null) {
            TeddyModel teddy =
                VectorScanProviders.teddyProviderAvailable()
                    ? TeddyModel.compileForSelectedProvider(ml.literals())
                    : null;
            yield new MultiLiteral(info, teddy);
          }
        }
        if (VectorScanProviders.teddyProviderAvailable() && k <= 32) {
          TeddyModel model = TeddyModel.compileForSelectedProvider(ml.literals());
          if (model != null) {
            yield new Teddy(model);
          }
        }
        int uniqueStartChars = countUniqueFirstBytes(ml.literals());
        if (k >= 128 || uniqueStartChars <= 8) {
          AhoCorasickSearcher ac = AhoCorasickSearcher.create(ml.literals(), false);
          if (ac != null) {
            yield new AhoCorasick(ac);
          }
        }
        if (ml.fallbackClass() != null && ml.fallbackClass().isSelective()) {
          yield new CharClass(ml.fallbackClass());
        }
        yield null;
      }
      case MultiAnchorDescriptor.StartPlan.LeadingExpansion le -> {
        Utf8StartAccelerator inner = create(le.innerPlan(), hasWordBoundary);
        yield inner != null
            ? new LeadingExpansion(le.leadingClass(), le.minRepetition(), le.maxRepetition(), inner)
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
      case FixedOffset fo -> fo.findCandidate(scanner, pos);
      case CharClass cc -> cc.findCandidate(scanner, pos);
      case Teddy t -> t.findCandidate(scanner, pos);
      case MultiLiteral ml -> ml.findCandidate(scanner, pos);
      case AhoCorasick ac -> ac.findCandidate(scanner, pos);
      case LeadingExpansion le -> le.findCandidate(scanner, pos);
    };
  }

  /** Returns the tuning and diagnostic policy for this accelerator. */
  default AcceleratorPolicy policy() {
    return AcceleratorPolicy.DEFAULT;
  }

  @SuppressWarnings("ArrayRecordComponent")
  record Literal(byte[] prefixUtf8, int[] prefixUtf8Failure, int[] prefixUtf8Shifts)
      implements Utf8StartAccelerator {

    static Literal create(String prefix) {
      byte[] utf8 = prefix.getBytes(StandardCharsets.UTF_8);
      return new Literal(utf8, Pattern.literalFailure(utf8), Pattern.literalShifts(utf8));
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      if (prefixUtf8 != null) {
        return scanner.indexOf(prefixUtf8, prefixUtf8Failure, prefixUtf8Shifts, fromIndex);
      }
      return fromIndex;
    }
  }

  @SuppressWarnings("ArrayRecordComponent")
  record CaseInsensitiveLiteral(
      String prefix, int[] failure, int anchorOffset, byte anchorLow, byte anchorHigh)
      implements Utf8StartAccelerator {

    static Utf8StartAccelerator create(String prefix) {
      for (int i = 0; i < prefix.length(); i++) {
        if (prefix.charAt(i) > 127) {
          return null;
        }
      }
      int anchorOffset = RarityOracle.rarestAsciiOffset(prefix, prefix.length());
      char anchor = prefix.charAt(anchorOffset);
      byte low = (byte) Ascii.toLowerCase(anchor);
      byte high = (byte) Ascii.toUpperCase(anchor);
      return new CaseInsensitiveLiteral(
          prefix, Ascii.ignoreCaseFailure(prefix), anchorOffset, low, high);
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      return scanner.indexOfIgnoreCase(
          prefix, failure, anchorOffset, anchorLow, anchorHigh, fromIndex);
    }
  }

  record FixedOffset(FixedOffsetLiteral fixedOffset, CharClassScanInfo charClassPrefix)
      implements Utf8StartAccelerator {

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      return nextFixedOffsetCandidate(scanner, fixedOffset, charClassPrefix, fromIndex);
    }

    private static int nextFixedOffsetCandidate(
        Utf8InputScanner scanner,
        FixedOffsetLiteral fixedOffsetLiteral,
        CharClassScanInfo charClassPrefix,
        int searchFrom) {
      int literalFrom = searchFrom + fixedOffsetLiteral.minOffset();
      int[] discreteOffsets = fixedOffsetLiteral.discreteOffsets();
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
        if (discreteOffsets != null && discreteOffsets.length == 1 && charClassPrefix != null) {
          int earliestValid = -1;
          for (int offset : discreteOffsets) {
            int candidateStart = literalStart - offset;
            if (candidateStart >= searchFrom) {
              int first =
                  candidateStart < scanner.length() ? scanner.codePointAt(candidateStart) : -1;
              if (first >= 0
                  && charClassPrefix.contains(first)
                  && (earliestValid < 0 || candidateStart < earliestValid)) {
                earliestValid = candidateStart;
              }
            }
          }
          if (earliestValid >= 0) {
            return earliestValid;
          }
          literalFrom = literalStart + 1;
          continue;
        }
        return Math.max(
            searchFrom, scanner.retreatByCodePoints(literalStart, fixedOffsetLiteral.maxOffset()));
      }
      return -1;
    }
  }

  record CharClass(CharClassScanInfo scanInfo) implements Utf8StartAccelerator {

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.CHAR_CLASS;
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      if (scanInfo == null) {
        return fromIndex;
      }
      return switch (scanInfo) {
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
      VectorScanProvider provider = VectorScanProviders.providerForTeddyLength(scanner.length());
      if (provider == null) {
        return fromIndex;
      }
      int idx =
          provider.indexOfTeddy(
              scanner.bytes(), scanner.offset(), scanner.length(), model, fromIndex);
      if (idx != VectorScanProvider.UNSUPPORTED) {
        return idx;
      }
      return fromIndex;
    }
  }

  record AhoCorasick(AhoCorasickSearcher searcher) implements Utf8StartAccelerator {
    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.VECTOR_MULTI_LITERAL;
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      return searcher.findNext(scanner.bytes(), scanner.offset(), scanner.length(), fromIndex);
    }
  }

  record LeadingExpansion(
      CharClassScanInfo leadingClass,
      int minRepetition,
      int maxRepetition,
      Utf8StartAccelerator inner)
      implements Utf8StartAccelerator {

    @Override
    public AcceleratorPolicy policy() {
      return new AcceleratorPolicy(16, 4, false, inner.policy().strategy());
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      int searchPos = Math.max(0, fromIndex);
      int textLen = scanner.length();
      while (searchPos < textLen) {
        int innerMatch = Utf8StartAccelerator.findNextCandidate(inner, scanner, searchPos);
        if (innerMatch < 0) {
          return -1;
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
      VectorScanProvider provider =
          VectorScanProviders.providerForMultiLiteralLength(scanner.length());
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
          return idx;
        }
        return fromIndex;
      }
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

  private static int countUniqueFirstBytes(String[] literals) {
    long lo = 0;
    long hi = 0;
    int nonAsciiCount = 0;
    for (String lit : literals) {
      if (lit.isEmpty()) {
        continue;
      }
      char c = lit.charAt(0);
      if (c < 64) {
        lo |= (1L << c);
      } else if (c < 128) {
        hi |= (1L << (c - 64));
      } else {
        nonAsciiCount++;
      }
    }
    return Long.bitCount(lo) + Long.bitCount(hi) + nonAsciiCount;
  }
}
