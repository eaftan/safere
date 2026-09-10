// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Arrays;

/**
 * Encapsulates pre-computed character class metadata for scanning, start acceleration, and
 * rejection.
 */
sealed interface CharClassScanInfo {

  boolean contains(int cp);

  long bitmap0();

  long bitmap1();

  boolean isAscii();

  int[] ranges();

  /**
   * Returns whether this character class is sufficiently selective to drive start acceleration.
   * Classes with more than 3 characters or single-character classes matching high-frequency
   * characters (e.g. {@code [ ]}) are not selective and delegate to DFA matching.
   */
  default boolean isSelective() {
    int[] classRanges = ranges();
    int asciiCount = Long.bitCount(bitmap0()) + Long.bitCount(bitmap1());
    if (asciiCount > 3) {
      return false;
    }
    if (classRanges.length == 2 && classRanges[0] == classRanges[1]) {
      int ch = classRanges[0];
      if (ch < 128 && RarityOracle.byteRarity(ch) <= RarityOracle.POISONOUS_ANCHOR_MAX_RARITY) {
        return false;
      }
    }
    return true;
  }

  /** Matches 1, 2, or 3 exact ASCII characters via single-instruction SIMD equality. */
  // Arrays are immutable, privately owned scanner metadata; array identity is never observed.
  @SuppressWarnings("ArrayRecordComponent")
  record AsciiSmallSet(char[] chars, int[] ranges, long bitmap0, long bitmap1)
      implements CharClassScanInfo {
    @Override
    public boolean contains(int cp) {
      if (cp < 64) {
        return cp >= 0 && (bitmap0 & (1L << cp)) != 0;
      }
      if (cp < 128) {
        return (bitmap1 & (1L << (cp - 64))) != 0;
      }
      return false;
    }

    @Override
    public boolean isAscii() {
      return true;
    }
  }

  /** Matches contiguous ASCII intervals (1-4 ranges, e.g. [a-zA-Z0-9_]) via SIMD range tests. */
  // The array is immutable, privately owned scanner metadata; array identity is never observed.
  @SuppressWarnings("ArrayRecordComponent")
  record AsciiRanges(int[] ranges, long bitmap0, long bitmap1) implements CharClassScanInfo {
    @Override
    public boolean contains(int cp) {
      if (cp < 64) {
        return cp >= 0 && (bitmap0 & (1L << cp)) != 0;
      }
      if (cp < 128) {
        return (bitmap1 & (1L << (cp - 64))) != 0;
      }
      return false;
    }

    @Override
    public boolean isAscii() {
      return true;
    }
  }

  /** Matches arbitrary ASCII character distributions via 128-bit bitmap lookup. */
  // The array is immutable, privately owned scanner metadata; array identity is never observed.
  @SuppressWarnings("ArrayRecordComponent")
  record AsciiBitmapClass(int[] ranges, long bitmap0, long bitmap1) implements CharClassScanInfo {
    @Override
    public boolean contains(int cp) {
      if (cp < 64) {
        return cp >= 0 && (bitmap0 & (1L << cp)) != 0;
      }
      if (cp < 128) {
        return (bitmap1 & (1L << (cp - 64))) != 0;
      }
      return false;
    }

    @Override
    public boolean isAscii() {
      return true;
    }
  }

  /**
   * Matches BMP/Unicode character classes with ASCII acceleration bitmap + binary search ranges.
   */
  // The array is immutable, privately owned scanner metadata; array identity is never observed.
  @SuppressWarnings("ArrayRecordComponent")
  record UnicodeGeneral(int[] ranges, long bitmap0, long bitmap1) implements CharClassScanInfo {
    @Override
    public boolean contains(int cp) {
      if (cp < 64) {
        return cp >= 0 && (bitmap0 & (1L << cp)) != 0;
      }
      if (cp < 128) {
        return (bitmap1 & (1L << (cp - 64))) != 0;
      }
      return Matcher.binarySearchRanges(ranges, cp);
    }

    @Override
    public boolean isAscii() {
      return false;
    }
  }

  static CharClassScanInfo fromAsciiBitmap(AsciiBitmap asciiClass) {
    if (asciiClass == null || asciiClass.isEmpty()) {
      return null;
    }
    long b0 = asciiClass.bitmap0();
    long b1 = asciiClass.bitmap1();
    int count = Long.bitCount(b0) + Long.bitCount(b1);
    int[] ranges = buildRangesFromBitmaps(b0, b1);
    if (count > 0 && count <= 3) {
      char[] chars = new char[count];
      int idx = 0;
      for (int i = 0; i < 64; i++) {
        if ((b0 & (1L << i)) != 0) {
          chars[idx++] = (char) i;
        }
      }
      for (int i = 0; i < 64; i++) {
        if ((b1 & (1L << i)) != 0) {
          chars[idx++] = (char) (i + 64);
        }
      }
      return new AsciiSmallSet(chars, ranges, b0, b1);
    }

    if (ranges != null && ranges.length / 2 <= 4) {
      return new AsciiRanges(ranges, b0, b1);
    }
    return new AsciiBitmapClass(ranges, b0, b1);
  }

  static CharClassScanInfo fromCharClass(CharClass cc) {
    if (cc == null || cc.isEmpty()) {
      return null;
    }
    int numRanges = cc.numRanges();
    int[] ranges = new int[numRanges * 2];
    long b0 = 0L;
    long b1 = 0L;
    for (int i = 0; i < numRanges; i++) {
      int lo = cc.lo(i);
      int hi = cc.hi(i);
      ranges[i * 2] = lo;
      ranges[i * 2 + 1] = hi;
      int start = Math.max(0, lo);
      int end = Math.min(127, hi);
      for (int cp = start; cp <= end; cp++) {
        if (cp < 64) {
          b0 |= (1L << cp);
        } else {
          b1 |= (1L << (cp - 64));
        }
      }
    }

    boolean isAscii = numRanges > 0 && cc.hi(numRanges - 1) <= 127;
    if (isAscii) {
      int count = Long.bitCount(b0) + Long.bitCount(b1);
      if (count > 0 && count <= 3) {
        char[] chars = new char[count];
        int idx = 0;
        for (int i = 0; i < 64; i++) {
          if ((b0 & (1L << i)) != 0) {
            chars[idx++] = (char) i;
          }
        }
        for (int i = 0; i < 64; i++) {
          if ((b1 & (1L << i)) != 0) {
            chars[idx++] = (char) (i + 64);
          }
        }
        return new AsciiSmallSet(chars, ranges, b0, b1);
      }
      if (numRanges <= 4) {
        return new AsciiRanges(ranges, b0, b1);
      }
      return new AsciiBitmapClass(ranges, b0, b1);
    }

    return new UnicodeGeneral(ranges, b0, b1);
  }

  static int[] buildRangesFromBitmaps(long b0, long b1) {
    int[] temp = new int[128 * 2];
    int rangeCount = 0;
    int value = 0;
    while (value < 128) {
      boolean contains = value < 64 ? (b0 & (1L << value)) != 0 : (b1 & (1L << (value - 64))) != 0;
      if (!contains) {
        value++;
        continue;
      }
      int low = value;
      while (value + 1 < 128) {
        int next = value + 1;
        boolean nextContains =
            next < 64 ? (b0 & (1L << next)) != 0 : (b1 & (1L << (next - 64))) != 0;
        if (!nextContains) {
          break;
        }
        value++;
      }
      int high = value;
      temp[rangeCount * 2] = low;
      temp[rangeCount * 2 + 1] = high;
      rangeCount++;
      value++;
    }
    if (rangeCount == 0) {
      return new int[0];
    }
    return Arrays.copyOf(temp, rangeCount * 2);
  }
}
