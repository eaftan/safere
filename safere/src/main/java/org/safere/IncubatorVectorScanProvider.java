// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Experimental scan operations implemented with the incubating Vector API. */
final class IncubatorVectorScanProvider implements VectorScanProvider {
  private static final int MINIMUM_INPUT_LENGTH = 1024;
  private static final int MINIMUM_TEDDY_INPUT_LENGTH = 1024;
  private static final int MINIMUM_MULTI_LITERAL_INPUT_LENGTH = 64;
  private static final int MINIMUM_PAIR_INPUT_LENGTH = 64;
  private static final int MINIMUM_TRIPLE_INPUT_LENGTH = 64;
  private static final int MAXIMUM_TRIPLE_INPUT_LENGTH = 10_240;

  @Override
  public int minimumInputLength() {
    return MINIMUM_INPUT_LENGTH;
  }

  @Override
  public int minimumTeddyInputLength() {
    return MINIMUM_TEDDY_INPUT_LENGTH;
  }

  @Override
  public int minimumMultiLiteralInputLength() {
    return MINIMUM_MULTI_LITERAL_INPUT_LENGTH;
  }

  @Override
  public int minimumPairInputLength() {
    return MINIMUM_PAIR_INPUT_LENGTH;
  }

  @Override
  public int minimumTripleInputLength() {
    return MINIMUM_TRIPLE_INPUT_LENGTH;
  }

  @Override
  public int maximumTripleInputLength() {
    return MAXIMUM_TRIPLE_INPUT_LENGTH;
  }

  @Override
  public int indexOfByte(byte[] bytes, int offset, int length, byte target, int start) {
    return ByteVectorScan.indexOfByte(bytes, offset, length, target, start);
  }

  @Override
  public int lastIndexOfByte(
      byte[] bytes, int offset, int length, byte target, int fromIndex, int toIndex) {
    return ByteVectorScan.lastIndexOfByte(bytes, offset, length, target, fromIndex, toIndex);
  }

  @Override
  public int indexOfAsciiClass(byte[] bytes, int offset, int length, int[] ranges, int start) {
    return ByteVectorScan.indexOfAsciiClass(bytes, offset, length, ranges, start);
  }

  @Override
  public int indexOfAsciiPair(byte[] bytes, int offset, int length, byte b0, byte b1, int start) {
    return ByteVectorScan.indexOfAsciiPair(bytes, offset, length, b0, b1, start);
  }

  @Override
  public int indexOfAsciiTriple(
      byte[] bytes, int offset, int length, byte b0, byte b1, byte b2, int start) {
    return ByteVectorScan.indexOfAsciiTriple(bytes, offset, length, b0, b1, b2, start);
  }

  @Override
  public int indexOfTeddy(byte[] bytes, int offset, int length, TeddyModel model, int start) {
    return TeddyVectorScan.indexOfTeddyUtf8(bytes, offset, length, model, start);
  }

  @Override
  public int indexOfMultiLiteral(
      byte[] bytes,
      int offset,
      int length,
      String[] literals,
      char[] anchorChars,
      int[] anchorOffsets,
      int[] anchorRanges,
      int minLength,
      TeddyModel teddyModel,
      int start) {
    return ByteVectorScan.indexOfMultiLiteral(
        bytes,
        offset,
        length,
        literals,
        anchorChars,
        anchorOffsets,
        anchorRanges,
        minLength,
        teddyModel,
        start);
  }
}
