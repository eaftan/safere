// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static jdk.incubator.vector.VectorOperators.LSHR;
import static jdk.incubator.vector.VectorOperators.NE;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;

/** Stateless SIMD Teddy multi-keyword vector-shuffle scanning kernels using the Vector API. */
final class TeddyVectorScan {
  private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;

  static int indexOfTeddyUtf8(byte[] bytes, int offset, int length, TeddyModel model, int start) {
    int minLen = model.minLength();
    int pos = Math.max(0, start);
    int vectorLen = SPECIES.length();
    int limit = length - vectorLen;
    boolean is2Byte = model.is2Byte();
    boolean is3Byte = model.is3Byte();

    long verificationWork = 0;
    long workLimit = WorkLimit.forRemaining(length - pos);

    ByteVector lutLo0 = ByteVector.fromArray(SPECIES, model.lutLo(), 0);
    ByteVector lutHi0 = ByteVector.fromArray(SPECIES, model.lutHi(), 0);
    ByteVector lutLo1 = is2Byte ? ByteVector.fromArray(SPECIES, model.lutLo1(), 0) : null;
    ByteVector lutHi1 = is2Byte ? ByteVector.fromArray(SPECIES, model.lutHi1(), 0) : null;
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
                if (candidatePos + lit.length() <= length) {
                  if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record(lit.length());
                  }
                  if (Ascii.regionMatches(bytes, offset + candidatePos, lit, lit.length())) {
                    return candidatePos;
                  }
                  verificationWork += lit.length();
                  if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                    return VectorScanProvider.UNSUPPORTED;
                  }
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
        if (pos + lit.length() <= length) {
          if (WorkCounterConfig.ENABLED) {
            WorkCounter.record(lit.length());
          }
          if (Ascii.regionMatches(bytes, offset + pos, lit, lit.length())) {
            return pos;
          }
        }
      }
      verificationWork += minLen;
      if (WorkLimit.isExhausted(verificationWork, workLimit)) {
        return VectorScanProvider.UNSUPPORTED;
      }
    }
    return -1;
  }

  private TeddyVectorScan() {}
}
