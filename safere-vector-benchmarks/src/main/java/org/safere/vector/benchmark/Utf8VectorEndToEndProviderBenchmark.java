// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.vector.benchmark;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import jdk.incubator.vector.ByteVector;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.safere.Pattern;
import org.safere.Utf8Input;
import org.safere.VectorUtf8ScanProviderBenchmarkAccess;

/** Measures complete UTF-8 find operations with native SWAR or an installed Vector provider. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class Utf8VectorEndToEndProviderBenchmark {
  /** Data-declared first-hit trial in shape/traversal/density/length/offset form. */
  @Param({})
  public String vectorScanTrial;

  /** Scanner provider selected before the UTF-8 input constructs its scanner. */
  @Param({"swar", "vector"})
  public String scanProvider;

  private Pattern pattern;
  private Utf8Input input;

  /** Installs the selected provider, materializes input, and verifies its complete result. */
  @Setup(Level.Trial)
  public void setup() {
    String[] fields = vectorScanTrial.split("/", -1);
    if (fields.length != 5 || !fields[1].equals("first")) {
      throw new IllegalArgumentException(
          "Expected first-hit Vector scan trial: " + vectorScanTrial);
    }
    String regex =
        switch (fields[0].toLowerCase(Locale.ROOT)) {
          case "singleton" -> "x+";
          case "pair" -> "[xy]";
          case "range" -> "[0-9]";
          default -> throw new IllegalArgumentException("Unknown shape: " + fields[0]);
        };
    int length = Integer.parseInt(fields[3]);
    int offset = Integer.parseInt(fields[4]);
    byte[] source = VectorScanConfiguration.input(fields[2], length);
    byte[] storage = new byte[offset + source.length + ByteVector.SPECIES_PREFERRED.length()];
    Arrays.fill(storage, (byte) 0x7f);
    System.arraycopy(source, 0, storage, offset, source.length);

    VectorUtf8ScanProviderBenchmarkAccess.clear();
    pattern = Pattern.compile(regex);
    boolean expected = pattern.find(Utf8Input.trusted(storage, offset, length));
    switch (scanProvider) {
      case "swar" -> VectorUtf8ScanProviderBenchmarkAccess.clear();
      case "vector" -> VectorUtf8ScanProviderBenchmarkAccess.install();
      default -> throw new IllegalArgumentException("Unknown scan provider: " + scanProvider);
    }
    input = Utf8Input.trusted(storage, offset, length);
    if (pattern.find(input) != expected) {
      throw new AssertionError("Provider disagreement for " + vectorScanTrial);
    }
  }

  /** Restores native scanner construction after each trial. */
  @TearDown(Level.Trial)
  public void tearDown() {
    VectorUtf8ScanProviderBenchmarkAccess.clear();
  }

  /** Runs the complete direct UTF-8 find operation. */
  @Benchmark
  public boolean safeReFind() {
    return pattern.find(input);
  }
}
