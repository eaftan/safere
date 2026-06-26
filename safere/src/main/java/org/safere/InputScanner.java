// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

interface InputScanner {
  /** Returns the length of the input in index units (chars for String, bytes for byte[]). */
  int length();

  /** Returns the character (or byte) value at the given index. */
  int charOrByteAt(int pos);

  /** Returns the code point starting at the given index. */
  int codePointAt(int pos);

  /**
   * Returns the code point starting at the given index, and stores the index of the next code point
   * in {@code nextPos[0]}.
   */
  int codePointAt(int pos, int[] nextPos);

  /** Returns the code point ending at the given index. */
  int codePointBefore(int pos);

  /**
   * Returns the code point ending at the given index, and stores the starting index of that code
   * point in {@code prevPos[0]}.
   */
  int codePointBefore(int pos, int[] prevPos);

  /** Finds the start of the trailing line terminator sequence. */
  int trailingLineTerminatorStart(boolean unixLines, int logicalEndPos);

  /**
   * Computes the index threshold beyond which transitions contain position-dependent emptyFlags.
   */
  int positionDependentThreshold(boolean dollarAnchorEnd, boolean unixLines);
}
