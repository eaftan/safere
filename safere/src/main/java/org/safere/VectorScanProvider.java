// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

interface VectorScanProvider {
  int UNSUPPORTED = -2;

  /**
   * Returns the shortest search window, in bytes, for which the {@code kind} kernel is expected to
   * beat the SWAR and scalar fallbacks.
   *
   * <p>The quantity is a <em>window</em> length, not an input length: it is measured over the
   * region the caller is about to scan, so that a narrow search inside a large input is costed as a
   * narrow search.
   */
  int minimumWindowLength(ScanKind kind);

  /**
   * Returns the longest search window, in bytes, for which the {@code kind} kernel is expected to
   * beat the SWAR and scalar fallbacks. Kernels that remain profitable on arbitrarily long windows
   * return {@link Integer#MAX_VALUE}.
   */
  default int maximumWindowLength(ScanKind kind) {
    return Integer.MAX_VALUE;
  }

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  default int indexOfByte(byte[] bytes, int offset, int length, byte target, int start) {
    return UNSUPPORTED;
  }

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  default int lastIndexOfByte(
      byte[] bytes, int offset, int length, byte target, int fromIndex, int toIndex) {
    return UNSUPPORTED;
  }

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  int indexOfAsciiClass(byte[] bytes, int offset, int length, int[] ranges, int start);

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  default int indexOfAsciiPair(byte[] bytes, int offset, int length, byte b0, byte b1, int start) {
    return UNSUPPORTED;
  }

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  default int lastIndexOfAsciiPair(
      byte[] bytes, int offset, int length, byte b0, byte b1, int fromIndex, int toIndex) {
    return UNSUPPORTED;
  }

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  default int indexOfAsciiTriple(
      byte[] bytes, int offset, int length, byte b0, byte b1, byte b2, int start) {
    return UNSUPPORTED;
  }

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  default int lastIndexOfAsciiTriple(
      byte[] bytes, int offset, int length, byte b0, byte b1, byte b2, int fromIndex, int toIndex) {
    return UNSUPPORTED;
  }

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  int indexOfTeddy(byte[] bytes, int offset, int length, TeddyModel model, int start);

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  int indexOfMultiLiteral(
      byte[] bytes,
      int offset,
      int length,
      String[] literals,
      char[] anchorChars,
      int[] anchorOffsets,
      int[] anchorRanges,
      int minLength,
      TeddyModel teddyModel,
      int start);
}
