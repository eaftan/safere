// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static jdk.incubator.vector.VectorOperators.EQ;
import static jdk.incubator.vector.VectorOperators.GE;
import static jdk.incubator.vector.VectorOperators.LE;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;

/**
 * Stateless SIMD kernels using the incubating Vector API for 1-byte sequences (UTF-8 and Latin-1).
 */
final class ByteVectorScan {
  static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;

  static int indexOfAsciiClass(byte[] bytes, int offset, int length, int[] ranges, int start) {
    return indexOfAsciiClass(SPECIES, bytes, offset, length, ranges, start);
  }

  static int indexOfAsciiClass(
      VectorSpecies<Byte> species, byte[] bytes, int offset, int length, int[] ranges, int start) {
    if (!Swar.supportsAsciiRanges(ranges, 4)) {
      return VectorScanProvider.UNSUPPORTED;
    }
    int position = Math.max(0, start);
    int limit = position + species.loopBound(length - position);
    for (; position < limit; position += species.length()) {
      ByteVector values = ByteVector.fromArray(species, bytes, offset + position);
      VectorMask<Byte> matches = matches(values, ranges);
      if (matches.anyTrue()) {
        return position + matches.firstTrue();
      }
    }
    for (; position < length; position++) {
      if (matches(bytes[offset + position], ranges)) {
        return position;
      }
    }
    return -1;
  }

  static int indexOfAsciiPair(byte[] bytes, int offset, int length, byte b0, byte b1, int start) {
    int position = Math.max(0, start);
    int limit = position + SPECIES.loopBound(length - position);
    ByteVector v0 = ByteVector.broadcast(SPECIES, b0);
    ByteVector v1 = ByteVector.broadcast(SPECIES, b1);
    for (; position < limit; position += SPECIES.length()) {
      ByteVector values = ByteVector.fromArray(SPECIES, bytes, offset + position);
      VectorMask<Byte> matches = values.compare(EQ, v0).or(values.compare(EQ, v1));
      if (matches.anyTrue()) {
        return position + matches.firstTrue();
      }
    }
    for (; position < length; position++) {
      byte val = bytes[offset + position];
      if (val == b0 || val == b1) {
        return position;
      }
    }
    return -1;
  }

  static int indexOfAsciiTriple(
      byte[] bytes, int offset, int length, byte b0, byte b1, byte b2, int start) {
    int position = Math.max(0, start);
    int limit = position + SPECIES.loopBound(length - position);
    ByteVector v0 = ByteVector.broadcast(SPECIES, b0);
    ByteVector v1 = ByteVector.broadcast(SPECIES, b1);
    ByteVector v2 = ByteVector.broadcast(SPECIES, b2);
    for (; position < limit; position += SPECIES.length()) {
      ByteVector values = ByteVector.fromArray(SPECIES, bytes, offset + position);
      VectorMask<Byte> matches =
          values.compare(EQ, v0).or(values.compare(EQ, v1)).or(values.compare(EQ, v2));
      if (matches.anyTrue()) {
        return position + matches.firstTrue();
      }
    }
    for (; position < length; position++) {
      byte val = bytes[offset + position];
      if (val == b0 || val == b1 || val == b2) {
        return position;
      }
    }
    return -1;
  }

  static VectorMask<Byte> matches(ByteVector values, int[] ranges) {
    VectorMask<Byte> matches = matches(values, ranges[0], ranges[1]);
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

  private static VectorMask<Byte> matches(ByteVector values, int lowBound, int highBound) {
    byte low = (byte) lowBound;
    byte high = (byte) highBound;
    if (low == high) {
      return values.eq(low);
    }
    if (highBound == lowBound + 1) {
      return values.eq(low).or(values.eq(high));
    }
    return values.compare(GE, low).and(values.compare(LE, high));
  }

  static boolean matches(byte value, int[] ranges) {
    for (int index = 0; index < ranges.length; index += 2) {
      if (value >= (byte) ranges[index] && value <= (byte) ranges[index + 1]) {
        return true;
      }
    }
    return false;
  }

  public static int indexOfIgnoreCase(
      byte[] bytes,
      int offset,
      int length,
      String prefix,
      int prefixLen,
      int anchorOffset,
      byte low,
      byte high,
      int start) {
    return indexOfIgnoreCase(
        SPECIES, bytes, offset, length, prefix, prefixLen, anchorOffset, low, high, start);
  }

  public static int indexOfIgnoreCase(
      VectorSpecies<Byte> species,
      byte[] bytes,
      int offset,
      int length,
      String prefix,
      int prefixLen,
      int anchorOffset,
      byte low,
      byte high,
      int start) {
    if (prefixLen == 0) {
      return Math.min(Math.max(0, start), length);
    }
    int pos = Math.max(0, start);
    long verificationWork = 0;
    long workLimit = WorkLimit.forRemaining(length - pos);

    // Fast scalar prologue to catch immediate matches without SIMD setup
    int scalarPrologueLimit = Math.min(length - prefixLen + 1, pos + Integer.BYTES);
    for (; pos < scalarPrologueLimit; pos++) {
      int b = bytes[offset + pos + anchorOffset] & 0xFF;
      if ((b == (low & 0xFF) || b == (high & 0xFF))
          && Ascii.regionMatchesIgnoreCase(bytes, offset + pos, prefix, prefixLen)) {
        return pos;
      }
      if (b == (low & 0xFF) || b == (high & 0xFF)) {
        verificationWork += prefixLen;
        if (WorkLimit.isExhausted(verificationWork, workLimit)) {
          return VectorScanProvider.UNSUPPORTED;
        }
      }
    }

    int vectorLen = species.length();
    int limit = length - vectorLen;
    if (pos > limit) {
      int limitScalar = length - prefixLen;
      for (int p = Math.max(start, pos - anchorOffset); p <= limitScalar; p++) {
        int b = bytes[offset + p + anchorOffset] & 0xFF;
        if (b != (low & 0xFF) && b != (high & 0xFF)) {
          continue;
        }
        if (Ascii.regionMatchesIgnoreCase(bytes, offset + p, prefix, prefixLen)) {
          return p;
        }
        verificationWork += prefixLen;
        if (WorkLimit.isExhausted(verificationWork, workLimit)) {
          return VectorScanProvider.UNSUPPORTED;
        }
      }
      return -1;
    }

    ByteVector lowVec = ByteVector.broadcast(species, low);
    ByteVector highVec = ByteVector.broadcast(species, high);

    for (; pos <= limit; pos += vectorLen) {
      ByteVector inputVec = ByteVector.fromArray(species, bytes, offset + pos);
      VectorMask<Byte> matchMask = inputVec.compare(EQ, lowVec).or(inputVec.compare(EQ, highVec));

      if (matchMask.anyTrue()) {
        long activeLanes = matchMask.toLong();
        while (activeLanes != 0) {
          int bit = Long.numberOfTrailingZeros(activeLanes);
          int candidatePos = pos + bit - anchorOffset;
          if (WorkLimit.candidateInBounds(candidatePos, start, length, prefixLen)
              && Ascii.regionMatchesIgnoreCase(bytes, offset + candidatePos, prefix, prefixLen)) {
            return candidatePos;
          }
          if (WorkLimit.candidateInBounds(candidatePos, start, length, prefixLen)) {
            verificationWork += prefixLen;
            if (WorkLimit.isExhausted(verificationWork, workLimit)) {
              return VectorScanProvider.UNSUPPORTED;
            }
          }
          activeLanes &= activeLanes - 1;
        }
      }
    }

    int limitScalar = length - prefixLen;
    for (int p = Math.max(start, pos - anchorOffset); p <= limitScalar; p++) {
      int b = bytes[offset + p + anchorOffset] & 0xFF;
      if (b != (low & 0xFF) && b != (high & 0xFF)) {
        continue;
      }
      if (Ascii.regionMatchesIgnoreCase(bytes, offset + p, prefix, prefixLen)) {
        return p;
      }
      verificationWork += prefixLen;
      if (WorkLimit.isExhausted(verificationWork, workLimit)) {
        return VectorScanProvider.UNSUPPORTED;
      }
    }
    return -1;
  }

  static int indexOfMultiLiteral(
      byte[] bytes,
      int offset,
      int length,
      String[] literals,
      char[] anchorChars,
      int[] anchorOffsets,
      int minLength,
      int start) {
    int numLits = literals.length;
    if (numLits == 0 || length < minLength) {
      return -1;
    }
    int pos = Math.max(0, start);
    long verificationWork = 0;
    long workLimit = WorkLimit.forRemaining(length - pos);
    int vectorLen = SPECIES.length();
    int limit = length - vectorLen;

    ByteVector v0 = ByteVector.broadcast(SPECIES, (byte) anchorChars[0]);
    ByteVector v1 = numLits >= 2 ? ByteVector.broadcast(SPECIES, (byte) anchorChars[1]) : null;
    ByteVector v2 = numLits >= 3 ? ByteVector.broadcast(SPECIES, (byte) anchorChars[2]) : null;
    ByteVector v3 = numLits >= 4 ? ByteVector.broadcast(SPECIES, (byte) anchorChars[3]) : null;

    for (; pos <= limit; pos += vectorLen) {
      ByteVector inputVec = ByteVector.fromArray(SPECIES, bytes, offset + pos);
      VectorMask<Byte> matchMask = inputVec.compare(EQ, v0);
      if (numLits >= 2) {
        matchMask = matchMask.or(inputVec.compare(EQ, v1));
      }
      if (numLits >= 3) {
        matchMask = matchMask.or(inputVec.compare(EQ, v2));
      }
      if (numLits >= 4) {
        matchMask = matchMask.or(inputVec.compare(EQ, v3));
      }

      if (matchMask.anyTrue()) {
        long activeLanes = matchMask.toLong();
        while (activeLanes != 0) {
          int bit = Long.numberOfTrailingZeros(activeLanes);
          int matchIndex = pos + bit;
          for (int i = 0; i < numLits; i++) {
            int candidatePos = matchIndex - anchorOffsets[i];
            String lit = literals[i];
            if (candidatePos >= start
                && candidatePos + lit.length() <= length
                && (bytes[offset + matchIndex] & 0xFF) == (anchorChars[i] & 0xFF)) {
              if (Ascii.regionMatches(bytes, offset + candidatePos, lit, lit.length())) {
                return candidatePos;
              }
              verificationWork += lit.length();
              if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                return VectorScanProvider.UNSUPPORTED;
              }
            }
          }
          activeLanes &= activeLanes - 1;
        }
      }
    }

    int scalarLimit = length - minLength;
    for (; pos <= scalarLimit; pos++) {
      for (int i = 0; i < numLits; i++) {
        String lit = literals[i];
        if (pos + lit.length() <= length
            && Ascii.regionMatches(bytes, offset + pos, lit, lit.length())) {
          return pos;
        }
      }
      verificationWork += minLength;
      if (WorkLimit.isExhausted(verificationWork, workLimit)) {
        return VectorScanProvider.UNSUPPORTED;
      }
    }
    return -1;
  }

  private ByteVectorScan() {}
}
