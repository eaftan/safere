// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static jdk.incubator.vector.VectorOperators.EQ;
import static jdk.incubator.vector.VectorOperators.LSHR;
import static jdk.incubator.vector.VectorOperators.NE;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/** Stateless SIMD Teddy multi-keyword vector-shuffle scanning kernels using the Vector API. */
final class TeddyVectorScan {
  private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
  private static final VectorShuffle<Byte> NARROW_SHUFFLE;
  private static final VectorShuffle<Byte> ODD_SHUFFLE;
  private static final int CHAR_COUNT = SPECIES.length() / 2;

  static {
    int vLen = SPECIES.length();
    int[] narrow = new int[vLen];
    int[] odd = new int[vLen];
    int chars = vLen / 2;
    for (int i = 0; i < chars; i++) {
      narrow[i] = i * 2;
      odd[i] = i * 2 + 1;
    }
    for (int i = chars; i < vLen; i++) {
      narrow[i] = 0;
      odd[i] = 1;
    }
    NARROW_SHUFFLE = VectorShuffle.fromArray(SPECIES, narrow, 0);
    ODD_SHUFFLE = VectorShuffle.fromArray(SPECIES, odd, 0);
  }

  static int indexOfTeddyUtf8(byte[] bytes, int offset, int length, TeddyModel model, int start) {
    int minLen = model.minLength();
    int pos = Math.max(0, start);
    int vectorLen = SPECIES.length();
    int limit = length - vectorLen;

    ByteVector lutLo0 = ByteVector.fromArray(SPECIES, model.lutLo(), 0);
    ByteVector lutHi0 = ByteVector.fromArray(SPECIES, model.lutHi(), 0);
    boolean is2Byte = model.is2Byte();
    ByteVector lutLo1 = is2Byte ? ByteVector.fromArray(SPECIES, model.lutLo1(), 0) : null;
    ByteVector lutHi1 = is2Byte ? ByteVector.fromArray(SPECIES, model.lutHi1(), 0) : null;
    boolean is3Byte = model.is3Byte();
    ByteVector lutLo2 = is3Byte ? ByteVector.fromArray(SPECIES, model.lutLo2(), 0) : null;
    ByteVector lutHi2 = is3Byte ? ByteVector.fromArray(SPECIES, model.lutHi2(), 0) : null;

    String[] literals = model.literals();
    int[] buckets = model.literalBuckets();

    for (; pos <= limit; pos += vectorLen) {
      // Stage 1: 2-byte primary SIMD filter (discards ~85% of non-matching blocks)
      ByteVector input0 = ByteVector.fromArray(SPECIES, bytes, offset + pos);
      ByteVector lo0 = input0.and((byte) 0x0F);
      ByteVector hi0 = input0.lanewise(LSHR, 4).and((byte) 0x0F);
      ByteVector match0 = lo0.selectFrom(lutLo0).and(hi0.selectFrom(lutHi0));

      if (is2Byte && pos + 1 <= limit) {
        ByteVector input1 = ByteVector.fromArray(SPECIES, bytes, offset + pos + 1);
        ByteVector lo1 = input1.and((byte) 0x0F);
        ByteVector hi1 = input1.lanewise(LSHR, 4).and((byte) 0x0F);
        ByteVector match1 = lo1.selectFrom(lutLo1).and(hi1.selectFrom(lutHi1));
        match0 = match0.and(match1);
      }

      VectorMask<Byte> matchMask = match0.compare(NE, (byte) 0);
      if (matchMask.anyTrue()) {
        // Stage 2: 3-byte confirmation filter (evaluated only on ~15% candidate blocks)
        if (is3Byte && pos + 2 <= limit) {
          ByteVector input2 = ByteVector.fromArray(SPECIES, bytes, offset + pos + 2);
          ByteVector lo2 = input2.and((byte) 0x0F);
          ByteVector hi2 = input2.lanewise(LSHR, 4).and((byte) 0x0F);
          ByteVector match2 = lo2.selectFrom(lutLo2).and(hi2.selectFrom(lutHi2));
          match0 = match0.and(match2);
          matchMask = match0.compare(NE, (byte) 0);
        }

        if (matchMask.anyTrue()) {
          // Stage 3: Candidate extraction and verification (< 0.1% of blocks)
          long activeLanes = matchMask.toLong();
          while (activeLanes != 0) {
            int bit = Long.numberOfTrailingZeros(activeLanes);
            int candidatePos = pos + bit;
            byte bucketMask = match0.lane(bit);

            for (int litIdx = 0; litIdx < literals.length; litIdx++) {
              int b = buckets[litIdx];
              if ((bucketMask & (1 << b)) != 0) {
                String lit = literals[litIdx];
                if (candidatePos + lit.length() <= length
                    && Ascii.regionMatches(bytes, offset + candidatePos, lit, lit.length())) {
                  return candidatePos;
                }
              }
            }
            activeLanes &= activeLanes - 1;
          }
        }
      }
    }

    int scalarLimit = length - minLen;
    for (; pos <= scalarLimit; pos++) {
      for (String lit : literals) {
        if (pos + lit.length() <= length
            && Ascii.regionMatches(bytes, offset + pos, lit, lit.length())) {
          return pos;
        }
      }
    }
    return -1;
  }

  static int indexOfTeddyUtf16(String text, TeddyModel model, int start) {
    int length = text.length();
    int minLen = model.minLength();
    int pos = Math.max(0, start);
    int charCount = CHAR_COUNT;
    int limit = length - charCount;

    ByteVector lutLo0 = ByteVector.fromArray(SPECIES, model.lutLo(), 0);
    ByteVector lutHi0 = ByteVector.fromArray(SPECIES, model.lutHi(), 0);
    boolean is2Byte = model.is2Byte();
    ByteVector lutLo1 = is2Byte ? ByteVector.fromArray(SPECIES, model.lutLo1(), 0) : null;
    ByteVector lutHi1 = is2Byte ? ByteVector.fromArray(SPECIES, model.lutHi1(), 0) : null;
    boolean is3Byte = model.is3Byte();
    ByteVector lutLo2 = is3Byte ? ByteVector.fromArray(SPECIES, model.lutLo2(), 0) : null;
    ByteVector lutHi2 = is3Byte ? ByteVector.fromArray(SPECIES, model.lutHi2(), 0) : null;

    String[] literals = model.literals();
    int[] buckets = model.literalBuckets();

    for (; pos <= limit; pos += charCount) {
      // Stage 1: 2-byte primary SIMD filter (Narrowing-Pack 8-bit)
      ByteVector raw0 = StringSupport.byteVectorFromString(SPECIES, text, pos << 1);
      ByteVector highBytes0 = raw0.rearrange(ODD_SHUFFLE);
      VectorMask<Byte> isAscii0 = highBytes0.compare(EQ, (byte) 0);

      ByteVector packed0 = raw0.rearrange(NARROW_SHUFFLE);
      ByteVector lo0 = packed0.and((byte) 0x0F);
      ByteVector hi0 = packed0.lanewise(LSHR, 4).and((byte) 0x0F);
      ByteVector match0 = lo0.selectFrom(lutLo0).and(hi0.selectFrom(lutHi0));

      if (is2Byte && pos + 1 <= limit) {
        ByteVector raw1 = StringSupport.byteVectorFromString(SPECIES, text, (pos + 1) << 1);
        ByteVector highBytes1 = raw1.rearrange(ODD_SHUFFLE);
        VectorMask<Byte> isAscii1 = highBytes1.compare(EQ, (byte) 0);

        ByteVector packed1 = raw1.rearrange(NARROW_SHUFFLE);
        ByteVector lo1 = packed1.and((byte) 0x0F);
        ByteVector hi1 = packed1.lanewise(LSHR, 4).and((byte) 0x0F);
        ByteVector match1 = lo1.selectFrom(lutLo1).and(hi1.selectFrom(lutHi1));

        match0 = match0.and(match1);
        isAscii0 = isAscii0.and(isAscii1);
      }

      VectorMask<Byte> matchMask = match0.compare(NE, (byte) 0).and(isAscii0);
      if (matchMask.anyTrue()) {
        // Stage 2: 3-byte confirmation filter (evaluated only on ~8% candidate blocks)
        if (is3Byte && pos + 2 <= limit) {
          ByteVector raw2 = StringSupport.byteVectorFromString(SPECIES, text, (pos + 2) << 1);
          ByteVector highBytes2 = raw2.rearrange(ODD_SHUFFLE);
          VectorMask<Byte> isAscii2 = highBytes2.compare(EQ, (byte) 0);

          ByteVector packed2 = raw2.rearrange(NARROW_SHUFFLE);
          ByteVector lo2 = packed2.and((byte) 0x0F);
          ByteVector hi2 = packed2.lanewise(LSHR, 4).and((byte) 0x0F);
          ByteVector match2 = lo2.selectFrom(lutLo2).and(hi2.selectFrom(lutHi2));

          match0 = match0.and(match2);
          isAscii0 = isAscii0.and(isAscii2);
          matchMask = match0.compare(NE, (byte) 0).and(isAscii0);
        }

        if (matchMask.anyTrue()) {
          // Stage 3: Candidate extraction and verification (< 0.1% of blocks)
          long activeLanes = matchMask.toLong() & ((1L << charCount) - 1);
          while (activeLanes != 0) {
            int bit = Long.numberOfTrailingZeros(activeLanes);
            int candidatePos = pos + bit;
            byte bucketMask = match0.lane(bit);

            for (int litIdx = 0; litIdx < literals.length; litIdx++) {
              int b = buckets[litIdx];
              if ((bucketMask & (1 << b)) != 0) {
                String lit = literals[litIdx];
                if (candidatePos + lit.length() <= length && text.startsWith(lit, candidatePos)) {
                  return candidatePos;
                }
              }
            }
            activeLanes &= activeLanes - 1;
          }
        }
      }
    }

    int scalarLimit = length - minLen;
    for (; pos <= scalarLimit; pos++) {
      for (String lit : literals) {
        if (pos + lit.length() <= length && text.startsWith(lit, pos)) {
          return pos;
        }
      }
    }
    return -1;
  }

  private TeddyVectorScan() {}
}
