// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Immutable precomputed lookup tables and metadata for Teddy SIMD vector-shuffle multi-literal
 * acceleration.
 *
 * <p>Teddy compresses up to 32 literal search patterns into parallel 16-byte nibble lookup tables,
 * enabling simultaneous multi-keyword matching in 3 SIMD instructions (2 {@code rearrange} + 1
 * {@code and}) on {@code ByteVector}.
 */
final class TeddyModel implements Serializable {
  private static final long serialVersionUID = 1L;

  private static final int MAX_BUCKETS = 8;
  private static final int NIBBLE_TABLE_SIZE = 16;

  private final byte[] lutLo;
  private final byte[] lutHi;
  private final byte[] lutLo1;
  private final byte[] lutHi1;
  private final byte[] lutLo2;
  private final byte[] lutHi2;
  private final String[] literals;
  private final int[] literalBuckets;
  private final int minLength;
  private final boolean is2Byte;
  private final boolean is3Byte;

  private TeddyModel(
      byte[] lutLo,
      byte[] lutHi,
      byte[] lutLo1,
      byte[] lutHi1,
      byte[] lutLo2,
      byte[] lutHi2,
      String[] literals,
      int[] literalBuckets,
      int minLength,
      boolean is2Byte,
      boolean is3Byte) {
    this.lutLo = lutLo;
    this.lutHi = lutHi;
    this.lutLo1 = lutLo1;
    this.lutHi1 = lutHi1;
    this.lutLo2 = lutLo2;
    this.lutHi2 = lutHi2;
    this.literals = literals;
    this.literalBuckets = literalBuckets;
    this.minLength = minLength;
    this.is2Byte = is2Byte;
    this.is3Byte = is3Byte;
  }

  byte[] lutLo() {
    return lutLo;
  }

  byte[] lutHi() {
    return lutHi;
  }

  byte[] lutLo1() {
    return lutLo1;
  }

  byte[] lutHi1() {
    return lutHi1;
  }

  byte[] lutLo2() {
    return lutLo2;
  }

  byte[] lutHi2() {
    return lutHi2;
  }

  String[] literals() {
    return literals;
  }

  int[] literalBuckets() {
    return literalBuckets;
  }

  int minLength() {
    return minLength;
  }

  boolean is2Byte() {
    return is2Byte;
  }

  boolean is3Byte() {
    return is3Byte;
  }

  /** Compiles a Teddy model only when the optional vector provider is active. */
  static TeddyModel compileForSelectedProvider(String[] literals) {
    return VectorScanProviders.vectorProviderAvailable() ? compile(literals, 64) : null;
  }

  /**
   * Compiles up to 32 ASCII literal keywords into a {@link TeddyModel}.
   *
   * @param literals the array of literal keywords
   * @param vectorLength the preferred vector lane width (16, 32, or 64 bytes)
   * @return the compiled model, or {@code null} if inputs are ineligible for Teddy
   */
  static TeddyModel compile(String[] literals, int vectorLength) {
    if (literals == null || literals.length < 2 || literals.length > 32) {
      return null;
    }
    int minLen = Integer.MAX_VALUE;
    for (String lit : literals) {
      if (lit == null || lit.isEmpty()) {
        return null;
      }
      for (int i = 0; i < lit.length(); i++) {
        if (lit.charAt(i) > 127) {
          return null;
        }
      }
      minLen = Math.min(minLen, lit.length());
    }

    int numBuckets = Math.min(literals.length, MAX_BUCKETS);
    int[] literalBuckets = new int[literals.length];
    byte[] baseLutLo0 = new byte[NIBBLE_TABLE_SIZE];
    byte[] baseLutHi0 = new byte[NIBBLE_TABLE_SIZE];

    for (int i = 0; i < literals.length; i++) {
      int bucket = i % numBuckets;
      literalBuckets[i] = bucket;
      byte mask = (byte) (1 << bucket);

      char c0 = literals[i].charAt(0);
      int lo0 = c0 & 0x0F;
      int hi0 = (c0 >> 4) & 0x0F;
      baseLutLo0[lo0] |= mask;
      baseLutHi0[hi0] |= mask;
    }

    boolean is2Byte = minLen >= 2;
    byte[] baseLutLo1 = is2Byte ? new byte[NIBBLE_TABLE_SIZE] : null;
    byte[] baseLutHi1 = is2Byte ? new byte[NIBBLE_TABLE_SIZE] : null;

    if (is2Byte) {
      for (int i = 0; i < literals.length; i++) {
        int bucket = literalBuckets[i];
        byte mask = (byte) (1 << bucket);

        char c1 = literals[i].charAt(1);
        int lo1 = c1 & 0x0F;
        int hi1 = (c1 >> 4) & 0x0F;
        baseLutLo1[lo1] |= mask;
        baseLutHi1[hi1] |= mask;
      }
    }

    boolean is3Byte = minLen >= 3;
    byte[] baseLutLo2 = is3Byte ? new byte[NIBBLE_TABLE_SIZE] : null;
    byte[] baseLutHi2 = is3Byte ? new byte[NIBBLE_TABLE_SIZE] : null;

    if (is3Byte) {
      for (int i = 0; i < literals.length; i++) {
        int bucket = literalBuckets[i];
        byte mask = (byte) (1 << bucket);

        char c2 = literals[i].charAt(2);
        int lo2 = c2 & 0x0F;
        int hi2 = (c2 >> 4) & 0x0F;
        baseLutLo2[lo2] |= mask;
        baseLutHi2[hi2] |= mask;
      }
    }

    int tableSize = Math.max(64, Math.max(NIBBLE_TABLE_SIZE, vectorLength));
    byte[] repeatedLo0 = repeatTable(baseLutLo0, tableSize);
    byte[] repeatedHi0 = repeatTable(baseLutHi0, tableSize);
    byte[] repeatedLo1 = is2Byte ? repeatTable(baseLutLo1, tableSize) : null;
    byte[] repeatedHi1 = is2Byte ? repeatTable(baseLutHi1, tableSize) : null;
    byte[] repeatedLo2 = is3Byte ? repeatTable(baseLutLo2, tableSize) : null;
    byte[] repeatedHi2 = is3Byte ? repeatTable(baseLutHi2, tableSize) : null;

    return new TeddyModel(
        repeatedLo0,
        repeatedHi0,
        repeatedLo1,
        repeatedHi1,
        repeatedLo2,
        repeatedHi2,
        Arrays.copyOf(literals, literals.length),
        literalBuckets,
        minLen,
        is2Byte,
        is3Byte);
  }

  private static byte[] repeatTable(byte[] base16, int totalSize) {
    byte[] result = new byte[totalSize];
    for (int i = 0; i < totalSize; i += NIBBLE_TABLE_SIZE) {
      System.arraycopy(base16, 0, result, i, Math.min(NIBBLE_TABLE_SIZE, totalSize - i));
    }
    return result;
  }
}
