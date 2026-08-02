// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static jdk.incubator.vector.VectorOperators.GE;
import static jdk.incubator.vector.VectorOperators.LE;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;

/** Experimental scan operations implemented with the incubating Vector API. */
final class IncubatorVectorScanProvider implements VectorScanProvider {
  private static final int MINIMUM_INPUT_LENGTH = 1024;
  private static final int SCALAR_PROLOGUE_LENGTH = Integer.BYTES;
  private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;

  @Override
  public int minimumInputLength() {
    return MINIMUM_INPUT_LENGTH;
  }

  @Override
  public int indexOfAsciiClass(byte[] bytes, int offset, int length, int[] ranges, int start) {
    int position = Math.max(0, start);
    int scalarLimit = Math.min(length, position + SCALAR_PROLOGUE_LENGTH);
    for (; position < scalarLimit; position++) {
      if (matches(bytes[offset + position], ranges)) {
        return position;
      }
    }
    int limit = position + SPECIES.loopBound(length - position);
    for (; position < limit; position += SPECIES.length()) {
      ByteVector values = ByteVector.fromArray(SPECIES, bytes, offset + position);
      VectorMask<Byte> matches = matches(values, ranges);
      if (matches.anyTrue()) {
        return position + matches.firstTrue();
      }
    }
    for (; position < length; position++) {
      if (matches(bytes[offset + position], ranges)) {
        return position;
      }
    }
    return -1;
  }

  private static VectorMask<Byte> matches(ByteVector values, int[] ranges) {
    if (ranges.length == 4) {
      return values.eq((byte) ranges[0]).or(values.eq((byte) ranges[2]));
    }
    byte low = (byte) ranges[0];
    byte high = (byte) ranges[1];
    if (low == high) {
      return values.eq(low);
    }
    if (high == low + 1) {
      return values.eq(low).or(values.eq(high));
    }
    return values.compare(GE, low).and(values.compare(LE, high));
  }

  private static boolean matches(byte value, int[] ranges) {
    if (ranges.length == 4) {
      return value == (byte) ranges[0] || value == (byte) ranges[2];
    }
    return value >= (byte) ranges[0] && value <= (byte) ranges[1];
  }
}
