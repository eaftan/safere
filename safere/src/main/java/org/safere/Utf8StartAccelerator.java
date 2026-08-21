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
  static Utf8StartAccelerator create(StartDescriptor descriptor, boolean hasWordBoundary) {
    if (descriptor == null || !descriptor.hasStartAcceleration()) {
      return null;
    }
    if (descriptor.prefix() != null) {
      if (descriptor.prefixFoldCase()) {
        return CaseInsensitiveLiteral.create(descriptor.prefix());
      }
      return Literal.create(descriptor.prefix());
    }
    if (descriptor.fixedOffsetLiteral() != null) {
      return new FixedOffset(descriptor.fixedOffsetLiteral(), descriptor.charClassPrefix());
    }
    if (descriptor.teddyModel() != null
        && !hasWordBoundary
        && VectorScanProviders.teddyProviderAvailable()) {
      return new Teddy(descriptor.teddyModel());
    }
    if (descriptor.charClassPrefix() != null && !hasWordBoundary) {
      return new CharClass(descriptor.charClassPrefix());
    }
    return null;
  }

  /**
   * Finds the next candidate match start position at or after {@code fromIndex}. Returns negative
   * if definitely not found.
   */
  int findCandidate(Utf8InputScanner scanner, int fromIndex);

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

    @Override
    public int findCandidate(Utf8InputScanner scanner, int fromIndex) {
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
      int anchorOffset = RarityOracle.rarestAsciiOffset(prefix, prefix.length(), true);
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

    @Override
    public int findCandidate(Utf8InputScanner scanner, int fromIndex) {
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

    @Override
    public int findCandidate(Utf8InputScanner scanner, int fromIndex) {
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

    @Override
    public int findCandidate(Utf8InputScanner scanner, int fromIndex) {
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

    @Override
    public int findCandidate(Utf8InputScanner scanner, int fromIndex) {
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
}
