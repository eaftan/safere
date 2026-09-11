// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Experimental scan operations implemented with the incubating Vector API. */
final class IncubatorVectorScanProvider implements VectorScanProvider {
  private static final int MINIMUM_BYTE_WINDOW_LENGTH = 64;
  private static final int MINIMUM_PAIR_WINDOW_LENGTH = 64;
  private static final int MINIMUM_TRIPLE_WINDOW_LENGTH = 64;
  private static final int MAXIMUM_TRIPLE_WINDOW_LENGTH = 10_240;
  private static final int MINIMUM_CLASS_WINDOW_LENGTH = 1024;
  private static final int MINIMUM_IGNORE_CASE_WINDOW_LENGTH = 1024;
  private static final int MINIMUM_TEDDY_WINDOW_LENGTH = 1024;
  private static final int MINIMUM_MULTI_LITERAL_WINDOW_LENGTH = 64;

  @Override
  public int minimumWindowLength(ScanKind kind) {
    return switch (kind) {
      case BYTE -> MINIMUM_BYTE_WINDOW_LENGTH;
      case PAIR -> MINIMUM_PAIR_WINDOW_LENGTH;
      case TRIPLE -> MINIMUM_TRIPLE_WINDOW_LENGTH;
      case CLASS -> MINIMUM_CLASS_WINDOW_LENGTH;
      case IGNORE_CASE -> MINIMUM_IGNORE_CASE_WINDOW_LENGTH;
      case TEDDY -> MINIMUM_TEDDY_WINDOW_LENGTH;
      case MULTI_LITERAL -> MINIMUM_MULTI_LITERAL_WINDOW_LENGTH;
    };
  }

  @Override
  public int maximumWindowLength(ScanKind kind) {
    // TRIPLE is the only kind with a measured upper crossover: past this window the SWAR triple
    // scan wins again. Every other kernel stays profitable for arbitrarily long windows.
    return kind == ScanKind.TRIPLE ? MAXIMUM_TRIPLE_WINDOW_LENGTH : Integer.MAX_VALUE;
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
  public int lastIndexOfAsciiPair(
      byte[] bytes, int offset, int length, byte b0, byte b1, int fromIndex, int toIndex) {
    return ByteVectorScan.lastIndexOfAsciiPair(bytes, offset, length, b0, b1, fromIndex, toIndex);
  }

  @Override
  public int indexOfAsciiTriple(
      byte[] bytes, int offset, int length, byte b0, byte b1, byte b2, int start) {
    return ByteVectorScan.indexOfAsciiTriple(bytes, offset, length, b0, b1, b2, start);
  }

  @Override
  public int lastIndexOfAsciiTriple(
      byte[] bytes, int offset, int length, byte b0, byte b1, byte b2, int fromIndex, int toIndex) {
    return ByteVectorScan.lastIndexOfAsciiTriple(
        bytes, offset, length, b0, b1, b2, fromIndex, toIndex);
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
