// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

sealed interface InputScanner permits StringInputScanner, Utf8InputScanner {
  int END_OF_INPUT = -1;

  /** Returns the length of the input in index units (chars for String, bytes for byte[]). */
  int length();

  /** Returns the ASCII value at {@code pos}, or {@code -1} if the unit is not ASCII. */
  int asciiAt(int pos);

  /**
   * Returns the code point at {@code pos} when it can be read directly from one index unit, or
   * {@code -1} when full decoding is required.
   */
  int singleUnitCodePointAt(int pos);

  /**
   * Returns the code point before {@code pos} when it can be read directly from one index unit, or
   * {@code -1} when full decoding is required.
   */
  int singleUnitCodePointBefore(int pos);

  /**
   * Returns the first position in {@code [start, limit)} belonging to the supplied code-point
   * class.
   */
  int indexOfCodePointClass(int[] ranges, long bitmap0, long bitmap1, int start, int limit);

  /**
   * Returns the first index in {@code [fromIndex, limit)} containing {@code ascii}, or {@code -1}
   * if none exists.
   */
  int indexOfAscii(int ascii, int fromIndex, int limit);

  /**
   * Returns the first index in {@code [fromIndex, limit)} containing {@code ascii} or a non-ASCII
   * code unit, or {@code -1} if neither occurs.
   */
  int indexOfAsciiOrNonAscii(int ascii, int fromIndex, int limit);

  /**
   * Returns the first index in {@code [fromIndex, limit)} containing {@code c1} or {@code c2}, or
   * {@code -1} if none exists.
   */
  int indexOfAsciiPair(int c1, int c2, int fromIndex, int limit);

  /**
   * Returns the first index in {@code [fromIndex, limit)} containing {@code c1}, {@code c2}, or a
   * non-ASCII code unit, or {@code -1} if none occurs.
   */
  int indexOfAsciiPairOrNonAscii(int c1, int c2, int fromIndex, int limit);

  /**
   * Returns the first index in {@code [fromIndex, limit)} containing {@code c1}, {@code c2}, or
   * {@code c3}, or {@code -1} if none exists.
   */
  int indexOfAsciiTriple(int c1, int c2, int c3, int fromIndex, int limit);

  /**
   * Returns the first index in {@code [fromIndex, limit)} containing {@code c1}, {@code c2}, {@code
   * c3}, or a non-ASCII code unit, or {@code -1} if none occurs.
   */
  int indexOfAsciiTripleOrNonAscii(int c1, int c2, int c3, int fromIndex, int limit);

  /** Decodes the scalar at {@code pos} and packs it with the following logical position. */
  long decodeForward(int pos);

  /** Decodes within {@code limit}, treating a split encoding at the limit as unavailable. */
  default long decodeForward(int pos, int limit) {
    long decoded = decodeForward(pos);
    return position(decoded) <= limit ? decoded : decoded(END_OF_INPUT, limit);
  }

  /** Decodes the scalar ending at {@code pos} and packs it with its starting logical position. */
  long decodeBackward(int pos);

  /** Decodes backward within {@code start}, treating a split encoding as unavailable. */
  default long decodeBackward(int pos, int start) {
    long decoded = decodeBackward(pos);
    return position(decoded) >= start ? decoded : decoded(END_OF_INPUT, start);
  }

  /** Returns whether {@code pos} is a code-point boundary in this representation. */
  boolean isCodePointBoundary(int pos);

  /** Moves backward by at most {@code count} Unicode code points from {@code pos}. */
  default int retreatByCodePoints(int pos, int count) {
    int result = pos;
    for (int remaining = count; remaining > 0 && result > 0; remaining--) {
      result = position(decodeBackward(result));
    }
    return result;
  }

  /** Returns the code point starting at the given index. */
  default int codePointAt(int pos) {
    return codePoint(decodeForward(pos));
  }

  /** Returns the code point ending at the given index. */
  default int codePointBefore(int pos) {
    return codePoint(decodeBackward(pos));
  }

  /** Finds the start of the trailing line terminator sequence. */
  int trailingLineTerminatorStart(boolean unixLines, int logicalEndPos);

  /**
   * Computes the index threshold beyond which transitions contain position-dependent emptyFlags.
   */
  int positionDependentThreshold(boolean dollarAnchorEnd, boolean unixLines);

  static long decoded(int codePoint, int position) {
    return ((long) codePoint << 32) | (position & 0xFFFF_FFFFL);
  }

  static int codePoint(long decoded) {
    return (int) (decoded >> 32);
  }

  default int indexOfCharClass(CharClassScanInfo scanInfo, int start) {
    return -1;
  }

  static int position(long decoded) {
    return (int) decoded;
  }

  static boolean classContains(int[] ranges, long bitmap0, long bitmap1, int codePoint) {
    if (codePoint < 64) {
      return (bitmap0 & (1L << codePoint)) != 0;
    }
    if (codePoint < 128) {
      return (bitmap1 & (1L << (codePoint - 64))) != 0;
    }
    int low = 0;
    int high = ranges.length / 2 - 1;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      int rangeLow = ranges[middle * 2];
      int rangeHigh = ranges[middle * 2 + 1];
      if (codePoint < rangeLow) {
        high = middle - 1;
      } else if (codePoint > rangeHigh) {
        low = middle + 1;
      } else {
        return true;
      }
    }
    return false;
  }
}
