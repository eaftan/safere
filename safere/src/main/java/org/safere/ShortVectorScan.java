// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.nativeOrder;
import static jdk.incubator.vector.VectorOperators.GE;
import static jdk.incubator.vector.VectorOperators.LE;
import static org.safere.Ascii.toLowerCase;
import static org.safere.Ascii.toUpperCase;
import static org.safere.Utf16.regionMatchesIgnoreCase;
import static org.safere.Utf16.regionMatchesIgnoreCaseUtf16;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Stateless SIMD kernels using the incubating Vector API for 2-byte sequences (UTF-16 and char[]).
 */
final class ShortVectorScan {
  static final VectorSpecies<Short> SPECIES = ShortVector.SPECIES_PREFERRED;

  static int indexOfCharClass(char[] chars, int offset, int length, int[] ranges, int start) {
    return indexOfCharClass(SPECIES, chars, offset, length, ranges, start);
  }

  static int indexOfCharClass(
      VectorSpecies<Short> species, char[] chars, int offset, int length, int[] ranges, int start) {
    if (!Swar.supportsBmpCodeUnitRanges(ranges, 4)) {
      return VectorScanProvider.UNSUPPORTED;
    }
    int position = Math.max(0, start);
    int vectorLen = species.length();
    int limit = length - vectorLen;

    for (; position <= limit; position += vectorLen) {
      ShortVector values = ShortVector.fromCharArray(species, chars, offset + position);
      VectorMask<Short> matches = matches(values, ranges);
      if (matches.anyTrue()) {
        return position + matches.firstTrue();
      }
    }

    for (; position < length; position++) {
      char ch = chars[offset + position];
      if (matches(ch, ranges)) {
        return position;
      }
    }
    return -1;
  }

  static int indexOfCharClassUtf16(byte[] bytes, int offset, int length, int[] ranges, int start) {
    return indexOfCharClassUtf16(SPECIES, bytes, offset, length, ranges, start);
  }

  static int indexOfCharClassUtf16(
      VectorSpecies<Short> species, byte[] bytes, int offset, int length, int[] ranges, int start) {
    if (!Swar.supportsBmpCodeUnitRanges(ranges, 4) || nativeOrder() == BIG_ENDIAN) {
      return VectorScanProvider.UNSUPPORTED;
    }
    int position = Math.max(0, start);
    int vectorLen = species.length();
    int limit = length - vectorLen;
    VectorSpecies<Byte> byteSpecies = species.withLanes(byte.class);

    for (; position <= limit; position += vectorLen) {
      ShortVector values =
          ByteVector.fromArray(byteSpecies, bytes, offset + (position << 1)).reinterpretAsShorts();
      VectorMask<Short> matches = matches(values, ranges);
      if (matches.anyTrue()) {
        return position + matches.firstTrue();
      }
    }

    for (; position < length; position++) {
      char ch =
          (char)
              ((bytes[offset + (position << 1)] & 0xFF)
                  | ((bytes[offset + (position << 1) + 1] & 0xFF) << 8));
      if (matches(ch, ranges)) {
        return position;
      }
    }
    return -1;
  }

  static int indexOfIgnoreCase(char[] chars, int offset, int length, String prefix, int start) {
    return indexOfIgnoreCase(SPECIES, chars, offset, length, prefix, start);
  }

  static int indexOfIgnoreCase(
      VectorSpecies<Short> species,
      char[] chars,
      int offset,
      int length,
      String prefix,
      int start) {
    int prefixLen = prefix.length();
    if (prefixLen == 0) {
      return Math.min(Math.max(0, start), length);
    }
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return VectorScanProvider.UNSUPPORTED;
      }
    }
    if (prefixLen > 1) {
      return Utf16.indexOfIgnoreCase(chars, offset, length, prefix, start);
    }

    int pos = Math.max(0, start);
    int vectorLen = species.length();
    int limit = length - vectorLen;

    char first = prefix.charAt(0);
    short low = (short) toLowerCase(first);
    short high = (short) toUpperCase(first);
    ShortVector lowVec = ShortVector.broadcast(species, low);
    ShortVector highVec = ShortVector.broadcast(species, high);

    for (; pos <= limit; pos += vectorLen) {
      ShortVector inputVec = ShortVector.fromCharArray(species, chars, offset + pos);
      VectorMask<Short> matchMask =
          inputVec
              .compare(VectorOperators.EQ, lowVec)
              .or(inputVec.compare(VectorOperators.EQ, highVec));

      if (matchMask.anyTrue()) {
        long activeLanes = matchMask.toLong();
        while (activeLanes != 0) {
          int bit = Long.numberOfTrailingZeros(activeLanes);
          int candidatePos = pos + bit;
          if (WorkLimit.candidateInBounds(candidatePos, start, length, prefixLen)
              && regionMatchesIgnoreCase(chars, offset + candidatePos, prefix, prefixLen)) {
            return candidatePos;
          }
          activeLanes &= activeLanes - 1;
        }
      }
    }

    int limitScalar = length - prefixLen;
    for (; pos <= limitScalar; pos++) {
      if (regionMatchesIgnoreCase(chars, offset + pos, prefix, prefixLen)) {
        return pos;
      }
    }
    return -1;
  }

  static int indexOfIgnoreCaseUtf16(
      byte[] bytes, int offset, int length, String prefix, int start) {
    return indexOfIgnoreCaseUtf16(SPECIES, bytes, offset, length, prefix, start);
  }

  static int indexOfIgnoreCaseUtf16(
      VectorSpecies<Short> species,
      byte[] bytes,
      int offset,
      int length,
      String prefix,
      int start) {
    int prefixLen = prefix.length();
    if (prefixLen == 0) {
      return Math.min(Math.max(0, start), length);
    }
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return VectorScanProvider.UNSUPPORTED;
      }
    }
    if (prefixLen > 1) {
      return Utf16.indexOfIgnoreCaseUtf16(bytes, offset, length, prefix, start);
    }
    if (nativeOrder() == BIG_ENDIAN) {
      return VectorScanProvider.UNSUPPORTED;
    }

    int pos = Math.max(0, start);
    int vectorLen = species.length();
    int limit = length - vectorLen;
    VectorSpecies<Byte> byteSpecies = species.withLanes(byte.class);

    char first = prefix.charAt(0);
    short low = (short) toLowerCase(first);
    short high = (short) toUpperCase(first);
    ShortVector lowVec = ShortVector.broadcast(species, low);
    ShortVector highVec = ShortVector.broadcast(species, high);

    for (; pos <= limit; pos += vectorLen) {
      ShortVector inputVec =
          ByteVector.fromArray(byteSpecies, bytes, offset + (pos << 1)).reinterpretAsShorts();
      VectorMask<Short> matchMask =
          inputVec
              .compare(VectorOperators.EQ, lowVec)
              .or(inputVec.compare(VectorOperators.EQ, highVec));

      if (matchMask.anyTrue()) {
        long activeLanes = matchMask.toLong();
        while (activeLanes != 0) {
          int bit = Long.numberOfTrailingZeros(activeLanes);
          int candidatePos = pos + bit;
          if (WorkLimit.candidateInBounds(candidatePos, start, length, prefixLen)
              && regionMatchesIgnoreCaseUtf16(
                  bytes, offset + (candidatePos << 1), prefix, prefixLen)) {
            return candidatePos;
          }
          activeLanes &= activeLanes - 1;
        }
      }
    }

    int limitScalar = length - prefixLen;
    for (; pos <= limitScalar; pos++) {
      if (regionMatchesIgnoreCaseUtf16(bytes, offset + (pos << 1), prefix, prefixLen)) {
        return pos;
      }
    }
    return -1;
  }

  static VectorMask<Short> matches(ShortVector values, int[] ranges) {
    VectorMask<Short> matches = matches(values, ranges[0], ranges[1]);
    if (ranges.length >= 4) {
      matches = matches.or(matches(values, ranges[2], ranges[3]));
    }
    if (ranges.length >= 6) {
      matches = matches.or(matches(values, ranges[4], ranges[5]));
    }
    if (ranges.length == 8) {
      matches = matches.or(matches(values, ranges[6], ranges[7]));
    }
    return matches;
  }

  private static VectorMask<Short> matches(ShortVector values, int lowBound, int highBound) {
    short low = (short) lowBound;
    short high = (short) highBound;
    if (low == high) {
      return values.eq(low);
    }
    if (highBound == lowBound + 1) {
      return values.eq(low).or(values.eq(high));
    }
    if (highBound <= 0x7FFF || lowBound >= 0x8000) {
      return values.compare(GE, low).and(values.compare(LE, high));
    }
    ShortVector biasedValues = values.lanewise(VectorOperators.XOR, Short.MIN_VALUE);
    short biasedLow = (short) (low ^ Short.MIN_VALUE);
    short biasedHigh = (short) (high ^ Short.MIN_VALUE);
    return biasedValues.compare(GE, biasedLow).and(biasedValues.compare(LE, biasedHigh));
  }

  static boolean matches(char value, int[] ranges) {
    for (int index = 0; index < ranges.length; index += 2) {
      if (value >= ranges[index] && value <= ranges[index + 1]) {
        return true;
      }
    }
    return false;
  }

  private ShortVectorScan() {}
}
