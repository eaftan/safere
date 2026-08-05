// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.concurrent.TimeUnit;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class VectorMicroBenchmark {

  @Param({"2048"})
  public int length;

  private byte[] value;

  private static final VectorSpecies<Byte> BYTE_SPECIES_128 = ByteVector.SPECIES_128;
  private static final VectorSpecies<Byte> BYTE_SPECIES_256 = ByteVector.SPECIES_256;
  private static final VectorSpecies<Short> SHORT_SPECIES_256 = ShortVector.SPECIES_256;

  private ByteVector byteLow128;
  private ByteVector byteHigh128;
  private ByteVector byteLow256;
  private ByteVector byteHigh256;
  private ShortVector shortLow256;
  private ShortVector shortHigh256;

  @Setup
  public void setup() {
    value = new byte[length];
    // Put match at the very end
    value[length - 1] = '1';

    byteLow128 = ByteVector.broadcast(BYTE_SPECIES_128, (byte) '0');
    byteHigh128 = ByteVector.broadcast(BYTE_SPECIES_128, (byte) '9');

    byteLow256 = ByteVector.broadcast(BYTE_SPECIES_256, (byte) '0');
    byteHigh256 = ByteVector.broadcast(BYTE_SPECIES_256, (byte) '9');

    shortLow256 = ShortVector.broadcast(SHORT_SPECIES_256, (short) '0');
    shortHigh256 = ShortVector.broadcast(SHORT_SPECIES_256, (short) '9');
  }

  @Benchmark
  public int byteVectorScan32Lanes() {
    int pos = 0;
    int limit = value.length - BYTE_SPECIES_256.length();
    for (; pos <= limit; pos += BYTE_SPECIES_256.length()) {
      ByteVector inputVec = ByteVector.fromArray(BYTE_SPECIES_256, value, pos);
      VectorMask<Byte> mask =
          inputVec
              .compare(VectorOperators.GE, byteLow256)
              .and(inputVec.compare(VectorOperators.LE, byteHigh256));
      if (mask.anyTrue()) {
        return pos + mask.firstTrue();
      }
    }
    for (; pos < value.length; pos++) {
      if (value[pos] >= '0' && value[pos] <= '9') {
        return pos;
      }
    }
    return -1;
  }

  @Benchmark
  public int byteVectorScan16Lanes() {
    int pos = 0;
    int limit = value.length - BYTE_SPECIES_128.length();
    for (; pos <= limit; pos += BYTE_SPECIES_128.length()) {
      ByteVector inputVec = ByteVector.fromArray(BYTE_SPECIES_128, value, pos);
      VectorMask<Byte> mask =
          inputVec
              .compare(VectorOperators.GE, byteLow128)
              .and(inputVec.compare(VectorOperators.LE, byteHigh128));
      if (mask.anyTrue()) {
        return pos + mask.firstTrue();
      }
    }
    for (; pos < value.length; pos++) {
      if (value[pos] >= '0' && value[pos] <= '9') {
        return pos;
      }
    }
    return -1;
  }

  @Benchmark
  public int shortVectorScan16LanesWithB2S() {
    int pos = 0;
    int limit = value.length - BYTE_SPECIES_256.length();
    for (; pos <= limit; pos += SHORT_SPECIES_256.length()) {
      ByteVector byteVec = ByteVector.fromArray(BYTE_SPECIES_256, value, pos);
      ShortVector inputVec = (ShortVector) byteVec.convert(VectorOperators.B2S, 0);
      VectorMask<Short> mask =
          inputVec
              .compare(VectorOperators.GE, shortLow256)
              .and(inputVec.compare(VectorOperators.LE, shortHigh256));
      if (mask.anyTrue()) {
        return pos + mask.firstTrue();
      }
    }
    for (; pos < value.length; pos++) {
      if (value[pos] >= '0' && value[pos] <= '9') {
        return pos;
      }
    }
    return -1;
  }
}
