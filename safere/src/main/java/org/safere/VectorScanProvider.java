// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Internal provider for experimental Vector API scan operations. */
interface VectorScanProvider {
  int UNSUPPORTED = -2;

  int minimumInputLength();

  int minimumTeddyInputLength();

  int minimumPairInputLength();

  int minimumTripleInputLength();

  int maximumTripleInputLength();

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  default int indexOfByte(byte[] bytes, int offset, int length, byte target, int start) {
    return UNSUPPORTED;
  }

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  int indexOfAsciiClass(byte[] bytes, int offset, int length, int[] ranges, int start);

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  default int indexOfAsciiPair(byte[] bytes, int offset, int length, byte b0, byte b1, int start) {
    return UNSUPPORTED;
  }

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  default int indexOfAsciiTriple(
      byte[] bytes, int offset, int length, byte b0, byte b1, byte b2, int start) {
    return UNSUPPORTED;
  }

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  int indexOfTeddy(byte[] bytes, int offset, int length, TeddyModel model, int start);
}
