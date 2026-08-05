// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Safe, copy-based implementation of VectorScanProvider for String operations. Copies string
 * characters into a thread-local chunk buffer to avoid unsafe reflection.
 */
final class CopyVectorScanner implements VectorScanProvider {
  private static final VectorSpecies<Short> SPECIES = ShortVector.SPECIES_PREFERRED;
  private static final int CHUNK_SIZE = 512;
  private static final int MINIMUM_INPUT_LENGTH = 512;

  // Thread-local buffer to avoid heap allocation per scan
  private static final ThreadLocal<char[]> CHUNK_BUFFER =
      ThreadLocal.withInitial(() -> new char[CHUNK_SIZE]);

  private final VectorScanProvider byteDelegate;

  CopyVectorScanner(VectorScanProvider byteDelegate) {
    this.byteDelegate = byteDelegate;
  }

  @Override
  public int minimumInputLength() {
    return MINIMUM_INPUT_LENGTH;
  }

  @Override
  public int indexOfAsciiClass(byte[] bytes, int offset, int length, int[] ranges, int start) {
    // byte[] scans do not need copying
    return byteDelegate.indexOfAsciiClass(bytes, offset, length, ranges, start);
  }

  @Override
  public int indexOfCharClass(String text, Pattern.CharClassScanInfo scanInfo, int start) {
    int textLen = text.length();
    int remaining = textLen - start;

    if (remaining < MINIMUM_INPUT_LENGTH) {
      return -2;
    }

    int numRanges = scanInfo.ranges.length / 2;
    if (numRanges > 4) {
      return -2; // Fallback to scalar
    }

    return indexOfCharClassShort(text, scanInfo.ranges, start, numRanges, false);
  }

  @Override
  public int indexOfCodePointClass(
      String text, int[] ranges, long bitmap0, long bitmap1, int start) {
    int textLen = text.length();
    int remaining = textLen - start;

    if (remaining < MINIMUM_INPUT_LENGTH) {
      return -2;
    }

    for (int r : ranges) {
      if (r >= 65536) {
        return -2;
      }
    }

    int numRanges = ranges.length / 2;
    if (numRanges > 4) {
      return -2;
    }

    return indexOfCharClassShort(text, ranges, start, numRanges, true);
  }

  private int indexOfCharClassShort(
      String text, int[] ranges, int start, int numRanges, boolean checkSurrogates) {
    int textLen = text.length();

    // Coder-agnostic surrogate check avoidance:
    // If the ranges we match do not overlap with the UTF-16 surrogate range [0xD800, 0xDFFF],
    // then no surrogate code unit can ever match our pattern anyway. We can safely skip the
    // expensive surrogate check in the vector loop.
    boolean activeSurrogateCheck = checkSurrogates;
    if (activeSurrogateCheck) {
      boolean overlaps = false;
      for (int r = 0; r < numRanges; r++) {
        int low = ranges[r * 2];
        int high = ranges[r * 2 + 1];
        if (low <= 0xDFFF && high >= 0xD800) {
          overlaps = true;
          break;
        }
      }
      if (!overlaps) {
        activeSurrogateCheck = false;
      }
    }

    ShortVector[] lowVecs = new ShortVector[numRanges];
    ShortVector[] highMinusLowVecs = new ShortVector[numRanges];
    for (int r = 0; r < numRanges; r++) {
      short low = (short) ranges[r * 2];
      short high = (short) ranges[r * 2 + 1];
      lowVecs[r] = ShortVector.broadcast(SPECIES, low);
      highMinusLowVecs[r] = ShortVector.broadcast(SPECIES, (short) (high - low));
    }

    ShortVector surrogateLow = ShortVector.broadcast(SPECIES, (short) 0xD800);
    ShortVector surrogateHigh = ShortVector.broadcast(SPECIES, (short) 0xDFFF);

    char[] buf = CHUNK_BUFFER.get();
    int vectorLen = SPECIES.length();
    int pos = start;

    while (pos < textLen) {
      int copyLen = Math.min(textLen - pos, CHUNK_SIZE);
      text.getChars(pos, pos + copyLen, buf, 0);
      MemorySegment segment = MemorySegment.ofArray(buf);

      int chunkPos = 0;
      int chunkLimit = copyLen - vectorLen;

      if (numRanges == 1) {
        ShortVector low = lowVecs[0];
        ShortVector high = ShortVector.broadcast(SPECIES, (short) ranges[1]);
        int unrolledLimit = chunkLimit - 3 * vectorLen;
        for (; chunkPos <= unrolledLimit; chunkPos += 4 * vectorLen) {
          ShortVector v0 =
              ShortVector.fromMemorySegment(
                  SPECIES, segment, (long) chunkPos * 2, ByteOrder.nativeOrder());
          ShortVector v1 =
              ShortVector.fromMemorySegment(
                  SPECIES, segment, (long) (chunkPos + vectorLen) * 2, ByteOrder.nativeOrder());
          ShortVector v2 =
              ShortVector.fromMemorySegment(
                  SPECIES, segment, (long) (chunkPos + 2 * vectorLen) * 2, ByteOrder.nativeOrder());
          ShortVector v3 =
              ShortVector.fromMemorySegment(
                  SPECIES, segment, (long) (chunkPos + 3 * vectorLen) * 2, ByteOrder.nativeOrder());

          if (activeSurrogateCheck) {
            VectorMask<Short> s0 =
                v0.compare(VectorOperators.GE, surrogateLow)
                    .and(v0.compare(VectorOperators.LE, surrogateHigh));
            VectorMask<Short> s1 =
                v1.compare(VectorOperators.GE, surrogateLow)
                    .and(v1.compare(VectorOperators.LE, surrogateHigh));
            VectorMask<Short> s2 =
                v2.compare(VectorOperators.GE, surrogateLow)
                    .and(v2.compare(VectorOperators.LE, surrogateHigh));
            VectorMask<Short> s3 =
                v3.compare(VectorOperators.GE, surrogateLow)
                    .and(v3.compare(VectorOperators.LE, surrogateHigh));
            if (s0.or(s1).or(s2).or(s3).anyTrue()) {
              return -2;
            }
          }

          VectorMask<Short> m0 =
              v0.compare(VectorOperators.GE, low).and(v0.compare(VectorOperators.LE, high));
          VectorMask<Short> m1 =
              v1.compare(VectorOperators.GE, low).and(v1.compare(VectorOperators.LE, high));
          VectorMask<Short> m2 =
              v2.compare(VectorOperators.GE, low).and(v2.compare(VectorOperators.LE, high));
          VectorMask<Short> m3 =
              v3.compare(VectorOperators.GE, low).and(v3.compare(VectorOperators.LE, high));

          VectorMask<Short> merged = m0.or(m1).or(m2).or(m3);
          if (merged.anyTrue()) {
            if (m0.anyTrue()) return pos + chunkPos + m0.firstTrue();
            if (m1.anyTrue()) return pos + chunkPos + vectorLen + m1.firstTrue();
            if (m2.anyTrue()) return pos + chunkPos + 2 * vectorLen + m2.firstTrue();
            return pos + chunkPos + 3 * vectorLen + m3.firstTrue();
          }
        }
        for (; chunkPos <= chunkLimit; chunkPos += vectorLen) {
          ShortVector inputVec =
              ShortVector.fromMemorySegment(
                  SPECIES, segment, (long) chunkPos * 2, ByteOrder.nativeOrder());
          if (activeSurrogateCheck) {
            VectorMask<Short> surrogateMask =
                inputVec
                    .compare(VectorOperators.GE, surrogateLow)
                    .and(inputVec.compare(VectorOperators.LE, surrogateHigh));
            if (surrogateMask.anyTrue()) {
              return -2;
            }
          }
          VectorMask<Short> matchMask =
              inputVec
                  .compare(VectorOperators.GE, low)
                  .and(inputVec.compare(VectorOperators.LE, high));
          if (matchMask.anyTrue()) {
            return pos + chunkPos + matchMask.firstTrue();
          }
        }
      } else {
        for (; chunkPos <= chunkLimit; chunkPos += vectorLen) {
          ShortVector inputVec =
              ShortVector.fromMemorySegment(
                  SPECIES, segment, (long) chunkPos * 2, ByteOrder.nativeOrder());
          if (activeSurrogateCheck) {
            VectorMask<Short> surrogateMask =
                inputVec
                    .compare(VectorOperators.GE, surrogateLow)
                    .and(inputVec.compare(VectorOperators.LE, surrogateHigh));
            if (surrogateMask.anyTrue()) {
              return -2;
            }
          }
          VectorMask<Short> matchMask = SPECIES.maskAll(false);
          for (int r = 0; r < numRanges; r++) {
            VectorMask<Short> rangeMask =
                inputVec.sub(lowVecs[r]).compare(VectorOperators.ULE, highMinusLowVecs[r]);
            matchMask = matchMask.or(rangeMask);
          }

          if (matchMask.anyTrue()) {
            return pos + chunkPos + matchMask.firstTrue();
          }
        }
      }

      if (copyLen == CHUNK_SIZE) {
        pos += chunkPos;
      } else {
        pos += chunkPos;
        break;
      }
    }

    // Scalar cleanup
    for (; pos < textLen; pos++) {
      char ch = text.charAt(pos);
      if (activeSurrogateCheck && Character.isSurrogate(ch)) {
        return -2;
      }
      for (int r = 0; r < numRanges; r++) {
        if (ch >= ranges[r * 2] && ch <= ranges[r * 2 + 1]) {
          return pos;
        }
      }
    }

    return -1;
  }

  @Override
  public int indexOfIgnoreCase(String text, String prefix, int start) {
    int prefixLen = prefix.length();
    if (prefixLen == 0) {
      return start;
    }

    // Only optimize ASCII prefixes
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return -2;
      }
    }

    int textLen = text.length();
    char first = prefix.charAt(0);
    short low = (short) VectorScanProvider.asciiLower(first);
    short high = (short) VectorScanProvider.asciiUpper(first);
    ShortVector lowVec = ShortVector.broadcast(SPECIES, low);
    ShortVector highVec = ShortVector.broadcast(SPECIES, high);

    char[] buf = CHUNK_BUFFER.get();
    int vectorLen = SPECIES.length();
    int pos = start;

    while (pos < textLen) {
      int copyLen = Math.min(textLen - pos, CHUNK_SIZE);
      text.getChars(pos, pos + copyLen, buf, 0);
      MemorySegment segment = MemorySegment.ofArray(buf);

      int chunkPos = 0;
      int chunkLimit = copyLen - vectorLen;

      for (; chunkPos <= chunkLimit; chunkPos += vectorLen) {
        ShortVector inputVec =
            ShortVector.fromMemorySegment(
                SPECIES, segment, (long) chunkPos * 2, ByteOrder.nativeOrder());
        VectorMask<Short> matchMask =
            inputVec
                .compare(VectorOperators.EQ, lowVec)
                .or(inputVec.compare(VectorOperators.EQ, highVec));

        if (matchMask.anyTrue()) {
          long activeLanes = matchMask.toLong();
          while (activeLanes != 0) {
            int bit = Long.numberOfTrailingZeros(activeLanes);
            int candidatePos = pos + chunkPos + bit;
            if (candidatePos + prefixLen <= textLen
                && Matcher.regionMatchesAsciiIgnoreCase(text, candidatePos, prefix, 0, prefixLen)) {
              return candidatePos;
            }
            activeLanes &= activeLanes - 1;
          }
        }
      }

      if (copyLen == CHUNK_SIZE) {
        pos += chunkPos;
      } else {
        pos += chunkPos;
        break;
      }
    }

    // Scalar cleanup
    int limitScalar = textLen - prefixLen;
    for (; pos <= limitScalar; pos++) {
      if (Matcher.regionMatchesAsciiIgnoreCase(text, pos, prefix, 0, prefixLen)) {
        return pos;
      }
    }

    return -1;
  }
}
