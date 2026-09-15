// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * SIMD scan kernels over a UTF-8 byte array.
 *
 * <p><strong>Naming.</strong> A kernel is named {@code Byte} when it compares for equality, which
 * is exact for all 256 byte values, and {@code Ascii} when it compares for <em>order</em>, which is
 * only correct below {@code 0x80} because {@code byte} is signed. So {@link #indexOfBytePair} and
 * {@link #indexOfByteTriple} accept any bytes even though today's only caller passes ASCII, while
 * {@link #indexOfAsciiClass} takes ranges and genuinely requires them.
 *
 * <p>Match the name to the comparison, not to the caller.
 */
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
  int indexOfAsciiClass(byte[] bytes, int offset, int length, int[] ranges, int start);

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  default int indexOfBytePair(byte[] bytes, int offset, int length, byte b0, byte b1, int start) {
    return UNSUPPORTED;
  }

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  default int indexOfByteTriple(
      byte[] bytes, int offset, int length, byte b0, byte b1, byte b2, int start) {
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
