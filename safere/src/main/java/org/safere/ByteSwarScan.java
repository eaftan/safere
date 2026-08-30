// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.lang.invoke.MethodHandles.byteArrayViewVarHandle;
import static java.nio.ByteOrder.nativeOrder;
import static java.util.Objects.requireNonNull;
import static org.safere.Swar.BYTE_HIGH_BITS;
import static org.safere.Swar.BYTE_ONES;

import java.lang.invoke.VarHandle;

/** Shared 64-bit SWAR kernels for scanning bounded 1-byte sequences. */
abstract class ByteSwarScan {

  /**
   * Input sizes at which the SWAR candidate filter overtakes the skip loop. The filter always
   * advances eight positions per step, while the skip loop can advance as far as the literal
   * length, so the filter needs an input large enough to outweigh that per-step advantage. Both
   * bounds were measured; see {@link #indexOfFiltered}.
   */
  static final int MIN_FILTER_LENGTH = 64;

  static final int FILTER_LENGTH_FACTOR = 40;

  private static final VarHandle LONG_VIEW = byteArrayViewVarHandle(long[].class, nativeOrder());

  final byte[] bytes;
  final int offset;
  final int length;

  ByteSwarScan(byte[] bytes, int offset, int length) {
    this.bytes = requireNonNull(bytes, "bytes");
    if (offset < 0 || length < 0 || offset > bytes.length - length) {
      throw new IndexOutOfBoundsException(
          "offset=" + offset + ", length=" + length + ", arrayLength=" + bytes.length);
    }
    this.offset = offset;
    this.length = length;
  }

  static long filterThreshold(int literalLength) {
    return Math.max(MIN_FILTER_LENGTH, (long) literalLength * FILTER_LENGTH_FACTOR);
  }

  final int indexOfByte(byte target, int start) {
    return indexOfByte(bytes, offset, length, target, start);
  }

  static int indexOfByte(byte[] bytes, int offset, int length, byte target, int start) {
    int position = start;
    int wordEnd = length - Long.BYTES;
    long repeatedTarget = (target & 0xFFL) * BYTE_ONES;
    while (position <= wordEnd) {
      long difference = (long) LONG_VIEW.get(bytes, offset + position) ^ repeatedTarget;
      if (((difference - BYTE_ONES) & ~difference & BYTE_HIGH_BITS) != 0) {
        for (int index = 0; index < Long.BYTES; index++) {
          if (bytes[offset + position + index] == target) {
            return position + index;
          }
        }
      }
      position += Long.BYTES;
    }
    while (position < length) {
      if (bytes[offset + position] == target) {
        return position;
      }
      position++;
    }
    return -1;
  }

  static int lastIndexOfByte(
      byte[] bytes, int offset, int length, byte target, int fromIndex, int toIndex) {
    int pos = Math.min(fromIndex, length - 1);
    int minLimit = Math.max(0, toIndex);
    if (pos < minLimit || minLimit >= length) {
      return -1;
    }
    long repeatedTarget = (target & 0xFFL) * BYTE_ONES;
    while (pos >= minLimit + Long.BYTES - 1) {
      int wordStart = pos - Long.BYTES + 1;
      long difference = (long) LONG_VIEW.get(bytes, offset + wordStart) ^ repeatedTarget;
      if (((difference - BYTE_ONES) & ~difference & BYTE_HIGH_BITS) != 0) {
        for (int index = Long.BYTES - 1; index >= 0; index--) {
          if (bytes[offset + wordStart + index] == target) {
            return wordStart + index;
          }
        }
      }
      pos -= Long.BYTES;
    }
    for (; pos >= minLimit; pos--) {
      if (bytes[offset + pos] == target) {
        return pos;
      }
    }
    return -1;
  }

  static int indexOfByteOrNonAscii(byte[] bytes, int offset, int length, byte target, int start) {
    return indexOfBytesOrNonAscii(bytes, offset, length, target, target, target, start, 1);
  }

  static int indexOfBytePairOrNonAscii(
      byte[] bytes, int offset, int length, byte first, byte second, int start) {
    return indexOfBytesOrNonAscii(bytes, offset, length, first, second, second, start, 2);
  }

  static int indexOfByteTripleOrNonAscii(
      byte[] bytes, int offset, int length, byte first, byte second, byte third, int start) {
    return indexOfBytesOrNonAscii(bytes, offset, length, first, second, third, start, 3);
  }

  private static int indexOfBytesOrNonAscii(
      byte[] bytes,
      int offset,
      int length,
      byte first,
      byte second,
      byte third,
      int start,
      int targetCount) {
    int position = start;
    int wordEnd = length - Long.BYTES;
    long repeatedFirst = (first & 0xFFL) * BYTE_ONES;
    long repeatedSecond = (second & 0xFFL) * BYTE_ONES;
    long repeatedThird = (third & 0xFFL) * BYTE_ONES;
    while (position <= wordEnd) {
      long word = (long) LONG_VIEW.get(bytes, offset + position);
      long firstDifference = word ^ repeatedFirst;
      long candidates = (firstDifference - BYTE_ONES) & ~firstDifference;
      if (targetCount >= 2) {
        long secondDifference = word ^ repeatedSecond;
        candidates |= (secondDifference - BYTE_ONES) & ~secondDifference;
      }
      if (targetCount == 3) {
        long thirdDifference = word ^ repeatedThird;
        candidates |= (thirdDifference - BYTE_ONES) & ~thirdDifference;
      }
      if (((candidates | word) & BYTE_HIGH_BITS) != 0) {
        for (int index = 0; index < Long.BYTES; index++) {
          byte value = bytes[offset + position + index];
          if (value < 0
              || value == first
              || (targetCount >= 2 && value == second)
              || (targetCount == 3 && value == third)) {
            return position + index;
          }
        }
      }
      position += Long.BYTES;
    }
    while (position < length) {
      byte value = bytes[offset + position];
      if (value < 0
          || value == first
          || (targetCount >= 2 && value == second)
          || (targetCount == 3 && value == third)) {
        return position;
      }
      position++;
    }
    return -1;
  }

  static int indexOfIgnoreCase(byte[] bytes, int offset, int length, String prefix, int start) {
    int prefixLen = prefix.length();
    if (prefixLen == 0) {
      return Math.min(Math.max(0, start), length);
    }
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return -2;
      }
    }
    int anchorOffset = RarityOracle.rarestAsciiOffset(prefix, prefixLen);
    char anchor = prefix.charAt(anchorOffset);
    byte low = (byte) Ascii.toLowerCase(anchor);
    byte high = (byte) Ascii.toUpperCase(anchor);
    return indexOfIgnoreCase(
        bytes, offset, length, prefix, prefixLen, anchorOffset, low, high, start);
  }

  static int indexOfIgnoreCase(
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
    if (pos <= length - prefixLen
        && Ascii.regionMatchesIgnoreCase(bytes, offset + pos, prefix, prefixLen)) {
      return pos;
    }
    long verificationWork = 0;
    long workLimit = WorkLimit.forRemaining(length - pos);

    int wordEnd = length - prefixLen - Long.BYTES + 1;
    long repeatedLow = (low & 0xFFL) * BYTE_ONES;
    long repeatedHigh = (high & 0xFFL) * BYTE_ONES;

    while (pos <= wordEnd) {
      // Tier 0: Immediate anchor check in 1 CPU cycle
      byte currentAnchor = bytes[offset + pos + anchorOffset];
      if (currentAnchor == low || currentAnchor == high) {
        if (Ascii.regionMatchesIgnoreCase(bytes, offset + pos, prefix, prefixLen)) {
          return pos;
        }
        verificationWork += prefixLen;
        if (WorkLimit.isExhausted(verificationWork, workLimit)) {
          return VectorScanProvider.UNSUPPORTED;
        }
        pos++;
        continue;
      }

      long word = (long) LONG_VIEW.get(bytes, offset + pos + anchorOffset);
      long lowDiff = word ^ repeatedLow;
      long highDiff = word ^ repeatedHigh;
      if (((((lowDiff - BYTE_ONES) & ~lowDiff) | ((highDiff - BYTE_ONES) & ~highDiff))
              & BYTE_HIGH_BITS)
          != 0) {
        for (int index = 0; index < Long.BYTES; index++) {
          byte value = bytes[offset + pos + anchorOffset + index];
          if (value == low || value == high) {
            int candidatePos = pos + index;
            if (WorkLimit.candidateInBounds(candidatePos, start, length, prefixLen)) {
              if (Ascii.regionMatchesIgnoreCase(bytes, offset + candidatePos, prefix, prefixLen)) {
                return candidatePos;
              }
              verificationWork += prefixLen;
              if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                return VectorScanProvider.UNSUPPORTED;
              }
            }
          }
        }
      }
      pos += Long.BYTES;
    }

    int limitScalar = length - prefixLen;
    for (; pos <= limitScalar; pos++) {
      byte value = bytes[offset + pos + anchorOffset];
      if (value == low || value == high) {
        if (Ascii.regionMatchesIgnoreCase(bytes, offset + pos, prefix, prefixLen)) {
          return pos;
        }
        verificationWork += prefixLen;
        if (WorkLimit.isExhausted(verificationWork, workLimit)) {
          return VectorScanProvider.UNSUPPORTED;
        }
      }
    }
    return -1;
  }

  static int indexOfBytePair(
      byte[] bytes, int offset, int length, byte first, byte second, int start) {
    int position = start;
    int wordEnd = length - Long.BYTES;
    long repeatedFirst = (first & 0xFFL) * BYTE_ONES;
    long repeatedSecond = (second & 0xFFL) * BYTE_ONES;
    while (position <= wordEnd) {
      long word = (long) LONG_VIEW.get(bytes, offset + position);
      long firstDifference = word ^ repeatedFirst;
      long secondDifference = word ^ repeatedSecond;
      if (((((firstDifference - BYTE_ONES) & ~firstDifference)
                  | ((secondDifference - BYTE_ONES) & ~secondDifference))
              & BYTE_HIGH_BITS)
          != 0) {
        for (int index = 0; index < Long.BYTES; index++) {
          byte value = bytes[offset + position + index];
          if (value == first || value == second) {
            return position + index;
          }
        }
      }
      position += Long.BYTES;
    }
    while (position < length) {
      byte value = bytes[offset + position];
      if (value == first || value == second) {
        return position;
      }
      position++;
    }
    return -1;
  }

  static int indexOfByteTriple(
      byte[] bytes, int offset, int length, byte first, byte second, byte third, int start) {
    int position = start;
    int wordEnd = length - Long.BYTES;
    long repeatedFirst = (first & 0xFFL) * BYTE_ONES;
    long repeatedSecond = (second & 0xFFL) * BYTE_ONES;
    long repeatedThird = (third & 0xFFL) * BYTE_ONES;
    while (position <= wordEnd) {
      long word = (long) LONG_VIEW.get(bytes, offset + position);
      long firstDifference = word ^ repeatedFirst;
      long secondDifference = word ^ repeatedSecond;
      long thirdDifference = word ^ repeatedThird;
      if (((((firstDifference - BYTE_ONES) & ~firstDifference)
                  | ((secondDifference - BYTE_ONES) & ~secondDifference)
                  | ((thirdDifference - BYTE_ONES) & ~thirdDifference))
              & BYTE_HIGH_BITS)
          != 0) {
        for (int index = 0; index < Long.BYTES; index++) {
          byte value = bytes[offset + position + index];
          if (value == first || value == second || value == third) {
            return position + index;
          }
        }
      }
      position += Long.BYTES;
    }
    while (position < length) {
      byte value = bytes[offset + position];
      if (value == first || value == second || value == third) {
        return position;
      }
      position++;
    }
    return -1;
  }

  /**
   * Searches for a byte in the inclusive ASCII range {@code [low, high]}.
   *
   * <p>The word filter compares the common binary prefix of the range endpoints across eight bytes
   * at once. Every byte in the range has that prefix, so the filter cannot miss a match. It may
   * admit bytes outside the range when the endpoints do not cover their entire binary-prefix
   * bucket; the scalar candidate check provides the exact answer.
   */
  static int indexOfByteRange(byte[] bytes, int offset, int length, int low, int high, int start) {
    int differingBit = Integer.highestOneBit(low ^ high);
    int commonMask = ~((differingBit << 1) - 1) & 0xFF;
    long repeatedMask = (commonMask & 0xFFL) * BYTE_ONES;
    long repeatedPrefix = (low & commonMask) * BYTE_ONES;
    int position = start;
    int wordEnd = length - Long.BYTES;
    while (position <= wordEnd) {
      long word = (long) LONG_VIEW.get(bytes, offset + position);
      long difference = (word & repeatedMask) ^ repeatedPrefix;
      if (((difference - BYTE_ONES) & ~difference & BYTE_HIGH_BITS) != 0) {
        for (int index = 0; index < Long.BYTES; index++) {
          int value = bytes[offset + position + index] & 0xFF;
          if (value >= low && value <= high) {
            return position + index;
          }
        }
      }
      position += Long.BYTES;
    }
    while (position < length) {
      int value = bytes[offset + position] & 0xFF;
      if (value >= low && value <= high) {
        return position;
      }
      position++;
    }
    return -1;
  }

  /**
   * Searches for a multi-byte {@code literal} by locating candidate positions with a SWAR filter on
   * the literal's first and last bytes, then verifying each candidate in full.
   *
   * <p>Two words are loaded per step, one aligned with the literal's first byte and one with its
   * last byte. XOR-ing each against the corresponding broadcast byte turns matching positions into
   * zero bytes, so the standard zero-byte test identifies positions where both the first and last
   * byte agree. Requiring both bytes makes candidates far rarer than a single-byte filter would.
   *
   * <p>This examines eight positions per step with no data-dependent branching. A skip loop such as
   * Boyer-Moore-Horspool can advance further per step, but each of its steps is a serialized load,
   * table lookup, and add, which costs more than the wider branch-free step here.
   *
   * <p>The zero-byte test never misses a matching position, but it can flag a position that does
   * not match, so every candidate is verified against the whole literal rather than trusting the
   * filter for the first and last byte.
   *
   * <p>Verification is O(literal length) per candidate, so an adversarial input can drive this to
   * O(input length * literal length). A work budget bounds that: on exhaustion this returns {@code
   * -2} and the caller falls back to linear-time KMP.
   *
   * @return the index of the first match, {@code -1} if the literal is absent, or {@code -2} if the
   *     work budget was exhausted before either could be established
   */
  static int indexOfFiltered(byte[] bytes, int offset, int length, byte[] literal, int start) {
    int last = literal.length - 1;
    long repeatedFirst = (literal[0] & 0xFFL) * BYTE_ONES;
    long repeatedLast = (literal[last] & 0xFFL) * BYTE_ONES;
    int wordEnd = length - last - Long.BYTES;
    long work = 0;
    long workLimit = WorkLimit.forRemaining(length - start);
    int position = start;
    while (position <= wordEnd) {
      long firstDifference = (long) LONG_VIEW.get(bytes, offset + position) ^ repeatedFirst;
      long lastDifference = (long) LONG_VIEW.get(bytes, offset + position + last) ^ repeatedLast;
      long candidates =
          (firstDifference - BYTE_ONES)
              & ~firstDifference
              & (lastDifference - BYTE_ONES)
              & ~lastDifference
              & BYTE_HIGH_BITS;
      if (candidates != 0) {
        // Scanning the eight positions in address order keeps this independent of byte order and
        // returns the leftmost match within the word.
        int candidateCount = 0;
        for (int index = 0; index < Long.BYTES; index++) {
          int candidate = position + index;
          if (bytes[offset + candidate] == literal[0]) {
            if (matchesAt(bytes, offset, literal, candidate)) {
              return candidate;
            }
            candidateCount++;
          }
        }
        work = WorkLimit.addCandidateWork(work, candidateCount, literal.length);
      }
      position += Long.BYTES;
      work++;
      if (WorkLimit.isExhausted(work, workLimit)) {
        return -2;
      }
    }
    return scalarTail(bytes, offset, length, literal, position);
  }

  /**
   * Scans the trailing bytes that the word loop could not cover (fewer than {@link Long#BYTES} plus
   * the literal length). Kept out of {@link #indexOfFiltered} so the hot word loop stays within
   * C2's inlining budget; merging this loop into it measurably deoptimizes the SWAR loop.
   */
  private static int scalarTail(
      byte[] bytes, int offset, int length, byte[] literal, int position) {
    while (position <= length - literal.length) {
      if (matchesAt(bytes, offset, literal, position)) {
        return position;
      }
      position++;
    }
    return -1;
  }

  private static boolean matchesAt(byte[] bytes, int offset, byte[] literal, int position) {
    for (int index = 0; index < literal.length; index++) {
      if (bytes[offset + position + index] != literal[index]) {
        return false;
      }
    }
    return true;
  }

  static long exactAsciiRangeMask(long word, int low, int high) {
    long values = word & ~BYTE_HIGH_BITS;
    long ascii = ~word & BYTE_HIGH_BITS;
    return Swar.exactAsciiRangeMask(values, ascii, low * BYTE_ONES, high * BYTE_ONES);
  }

  static int indexOfMultipleByteRanges(
      byte[] bytes, int offset, int length, int[] ranges, long bitmap0, long bitmap1, int start) {
    return switch (ranges.length) {
      case 4 -> indexOfTwoByteRanges(bytes, offset, length, ranges, bitmap0, bitmap1, start);
      case 6 -> indexOfThreeByteRanges(bytes, offset, length, ranges, bitmap0, bitmap1, start);
      case 8 -> indexOfFourByteRanges(bytes, offset, length, ranges, bitmap0, bitmap1, start);
      default -> throw new AssertionError("unexpected range count");
    };
  }

  private static int indexOfTwoByteRanges(
      byte[] bytes, int offset, int length, int[] ranges, long bitmap0, long bitmap1, int start) {
    long low0 = ranges[0] * BYTE_ONES;
    long high0 = ranges[1] * BYTE_ONES;
    long low1 = ranges[2] * BYTE_ONES;
    long high1 = ranges[3] * BYTE_ONES;
    int position = start;
    int wordEnd = length - Long.BYTES;
    while (position <= wordEnd) {
      long word = (long) LONG_VIEW.get(bytes, offset + position);
      long values = word & ~BYTE_HIGH_BITS;
      long ascii = ~word & BYTE_HIGH_BITS;
      long matches = Swar.exactAsciiRangeMask(values, ascii, low0, high0);
      matches |= Swar.exactAsciiRangeMask(values, ascii, low1, high1);
      if (matches != 0) {
        return scalarRangeCheck(bytes, offset, bitmap0, bitmap1, position, position + Long.BYTES);
      }
      position += Long.BYTES;
    }
    return scalarRangeCheck(bytes, offset, bitmap0, bitmap1, position, length);
  }

  private static int indexOfThreeByteRanges(
      byte[] bytes, int offset, int length, int[] ranges, long bitmap0, long bitmap1, int start) {
    long low0 = ranges[0] * BYTE_ONES;
    long high0 = ranges[1] * BYTE_ONES;
    long low1 = ranges[2] * BYTE_ONES;
    long high1 = ranges[3] * BYTE_ONES;
    long low2 = ranges[4] * BYTE_ONES;
    long high2 = ranges[5] * BYTE_ONES;
    int position = start;
    int wordEnd = length - Long.BYTES;
    while (position <= wordEnd) {
      long word = (long) LONG_VIEW.get(bytes, offset + position);
      long values = word & ~BYTE_HIGH_BITS;
      long ascii = ~word & BYTE_HIGH_BITS;
      long matches = Swar.exactAsciiRangeMask(values, ascii, low0, high0);
      matches |= Swar.exactAsciiRangeMask(values, ascii, low1, high1);
      matches |= Swar.exactAsciiRangeMask(values, ascii, low2, high2);
      if (matches != 0) {
        return scalarRangeCheck(bytes, offset, bitmap0, bitmap1, position, position + Long.BYTES);
      }
      position += Long.BYTES;
    }
    return scalarRangeCheck(bytes, offset, bitmap0, bitmap1, position, length);
  }

  private static int indexOfFourByteRanges(
      byte[] bytes, int offset, int length, int[] ranges, long bitmap0, long bitmap1, int start) {
    long low0 = ranges[0] * BYTE_ONES;
    long high0 = ranges[1] * BYTE_ONES;
    long low1 = ranges[2] * BYTE_ONES;
    long high1 = ranges[3] * BYTE_ONES;
    long low2 = ranges[4] * BYTE_ONES;
    long high2 = ranges[5] * BYTE_ONES;
    long low3 = ranges[6] * BYTE_ONES;
    long high3 = ranges[7] * BYTE_ONES;
    int position = start;
    int wordEnd = length - Long.BYTES;
    while (position <= wordEnd) {
      long word = (long) LONG_VIEW.get(bytes, offset + position);
      long values = word & ~BYTE_HIGH_BITS;
      long ascii = ~word & BYTE_HIGH_BITS;
      long matches = Swar.exactAsciiRangeMask(values, ascii, low0, high0);
      matches |= Swar.exactAsciiRangeMask(values, ascii, low1, high1);
      matches |= Swar.exactAsciiRangeMask(values, ascii, low2, high2);
      matches |= Swar.exactAsciiRangeMask(values, ascii, low3, high3);
      if (matches != 0) {
        return scalarRangeCheck(bytes, offset, bitmap0, bitmap1, position, position + Long.BYTES);
      }
      position += Long.BYTES;
    }
    return scalarRangeCheck(bytes, offset, bitmap0, bitmap1, position, length);
  }

  static int indexOfAsciiClass(byte[] bytes, int offset, int length, int[] ranges, int start) {
    if (!Swar.supportsAsciiRanges(ranges, 2)) {
      return VectorScanProvider.UNSUPPORTED;
    }
    int numRanges = ranges.length / 2;

    int pos = Math.max(0, start);
    int wordEnd = length - Long.BYTES;

    long low0 = (ranges[0] & 0xFFL) * BYTE_ONES;
    long high0 = (ranges[1] & 0xFFL) * BYTE_ONES;
    long low1 = numRanges > 1 ? (ranges[2] & 0xFFL) * BYTE_ONES : 0;
    long high1 = numRanges > 1 ? (ranges[3] & 0xFFL) * BYTE_ONES : 0;

    if (numRanges == 1) {
      while (pos <= wordEnd) {
        long word = (long) LONG_VIEW.get(bytes, offset + pos);
        long values = word & ~BYTE_HIGH_BITS;
        long ascii = ~word & BYTE_HIGH_BITS;
        long matches = Swar.exactAsciiRangeMask(values, ascii, low0, high0);

        if (matches != 0) {
          int limit = pos + Long.BYTES;
          for (int i = pos; i < limit; i++) {
            int b = bytes[offset + i] & 0xFF;
            if (b >= ranges[0] && b <= ranges[1]) {
              return i;
            }
          }
        }
        pos += Long.BYTES;
      }
    } else {
      while (pos <= wordEnd) {
        long word = (long) LONG_VIEW.get(bytes, offset + pos);
        long values = word & ~BYTE_HIGH_BITS;
        long ascii = ~word & BYTE_HIGH_BITS;
        long matches =
            Swar.exactAsciiRangeMask(values, ascii, low0, high0)
                | Swar.exactAsciiRangeMask(values, ascii, low1, high1);

        if (matches != 0) {
          int limit = pos + Long.BYTES;
          for (int i = pos; i < limit; i++) {
            int b = bytes[offset + i] & 0xFF;
            if ((b >= ranges[0] && b <= ranges[1]) || (b >= ranges[2] && b <= ranges[3])) {
              return i;
            }
          }
        }
        pos += Long.BYTES;
      }
    }

    for (; pos < length; pos++) {
      int b = bytes[offset + pos] & 0xFF;
      for (int r = 0; r < numRanges; r++) {
        if (b >= ranges[r * 2] && b <= ranges[r * 2 + 1]) {
          return pos;
        }
      }
    }
    return -1;
  }

  private static int scalarRangeCheck(
      byte[] bytes, int offset, long bitmap0, long bitmap1, int position, int limit) {
    for (; position < limit; position++) {
      int value = bytes[offset + position] & 0xFF;
      if ((value < Long.SIZE && (bitmap0 & (1L << value)) != 0)
          || (value >= Long.SIZE && value < 128 && (bitmap1 & (1L << (value - Long.SIZE))) != 0)) {
        return position;
      }
    }
    return -1;
  }
}
