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
import org.safere.Pattern;
import org.safere.Utf8Input;
import org.safere.Utf8Matcher;

/** Measures complete UTF-8 find operations with the provider selected at JVM startup. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class Utf8VectorEndToEndProviderBenchmark {
  /** Data-declared first-hit trial in shape/traversal/density/length/offset form. */
  @Param({})
  public String vectorScanTrial;

  /** Scanner provider expected to have been selected by the benchmark runner. */
  @Param({"swar", "vector"})
  public String scanProvider;

  private Pattern pattern;
  private Utf8Input input;
  private Utf8Matcher matcher;
  private boolean scanAll;
  private int expected;

  /** Materializes input and verifies the selected provider and complete result. */
  @Setup(Level.Trial)
  public void setup() {
    String[] fields = vectorScanTrial.split("/", -1);
    if (fields.length != 5) {
      throw new IllegalArgumentException("Malformed Vector scan trial: " + vectorScanTrial);
    }
    scanAll = fields[1].equals("all");
    if (!scanAll && !fields[1].equals("first")) {
      throw new IllegalArgumentException("Unknown traversal: " + fields[1]);
    }
    String inputProfile = "";
    String regex =
        switch (fields[0].toLowerCase(Locale.ROOT)) {
          case "singleton" ->
              throw new IllegalArgumentException(
                  "Singleton patterns use literal scanning, not the character-class provider");
          case "pair" -> "[xy]";
          case "range" -> "[0-9]";
          case "alnum3" -> {
            inputProfile = "multi.";
            yield "[0-9A-Za-z]";
          }
          case "mixed4" -> {
            inputProfile = "multi.";
            yield "[!#0-9A-Z]";
          }
          default -> throw new IllegalArgumentException("Unknown shape: " + fields[0]);
        };
    int length = Integer.parseInt(fields[3]);
    int offset = Integer.parseInt(fields[4]);
    String configuredProvider =
        System.getProperty("org.safere.experimental.vectorScanProvider", "swar").trim();
    if (!scanProvider.equals(configuredProvider)) {
      throw new IllegalStateException(
          "Expected provider " + scanProvider + " but JVM selected " + configuredProvider);
    }
    byte[] source = VectorScanConfiguration.input(inputProfile, fields[2], length);
    byte[] storage = new byte[offset + source.length + ByteVector.SPECIES_PREFERRED.length()];
    Arrays.fill(storage, (byte) 0x7f);
    System.arraycopy(source, 0, storage, offset, source.length);

    pattern = Pattern.compile(regex);
    input = Utf8Input.trusted(storage, offset, length);
    matcher = pattern.matcher(input);
    expected = expectedResult(fields[0], source);
    if (runSafeRe() != expected) {
      throw new AssertionError("Unexpected result for " + vectorScanTrial);
    }
  }

  /** Runs the complete direct UTF-8 find operation. */
  @Benchmark
  public int safeReFind() {
    return runSafeRe();
  }

  private int runSafeRe() {
    if (!scanAll) {
      return pattern.find(input) ? 1 : 0;
    }
    matcher.reset();
    int checksum = 0;
    while (matcher.find()) {
      checksum += matcher.start();
    }
    return checksum;
  }

  private int expectedResult(String shape, byte[] source) {
    int checksum = 0;
    for (int position = 0; position < source.length; position++) {
      if (matches(shape, source[position])) {
        if (!scanAll) {
          return 1;
        }
        checksum += position;
      }
    }
    return checksum;
  }

  private static boolean matches(String shape, byte value) {
    return switch (shape) {
      case "pair" -> value == 'x' || value == 'y';
      case "range" -> value >= '0' && value <= '9';
      case "alnum3" ->
          (value >= '0' && value <= '9')
              || (value >= 'A' && value <= 'Z')
              || (value >= 'a' && value <= 'z');
      case "mixed4" ->
          value == '!'
              || value == '#'
              || (value >= '0' && value <= '9')
              || (value >= 'A' && value <= 'Z');
      default -> throw new IllegalArgumentException("Unknown shape: " + shape);
    };
  }
}
