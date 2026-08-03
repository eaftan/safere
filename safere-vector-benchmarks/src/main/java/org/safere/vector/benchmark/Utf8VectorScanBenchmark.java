// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.vector.benchmark;

import static jdk.incubator.vector.VectorOperators.GE;
import static jdk.incubator.vector.VectorOperators.LE;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;
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
import org.safere.Utf8ScannerBenchmarkAccess;

/** Compares current UTF-8 SWAR scans with direct borrowed-array ByteVector prototypes. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class Utf8VectorScanBenchmark {
  private static final String PROVIDER_PROPERTY = "org.safere.experimental.vectorScanProvider";
  private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
  private static final int[] SINGLETON_RANGES = {'x', 'x'};
  private static final int[] PAIR_RANGES = {'x', 'x', 'y', 'y'};
  private static final int[] RANGE_RANGES = {'0', '9'};
  private static final int[] ALNUM3_RANGES = {'0', '9', 'A', 'Z', 'a', 'z'};
  private static final int[] MIXED4_RANGES = {'!', '!', '#', '#', '0', '9', 'A', 'Z'};

  /** Data-declared trial in shape/traversal/density/length/offset form. */
  @Param({})
  public String vectorScanTrial;

  private byte[] storage;
  private int offset;
  private int length;
  private Shape shape;
  private boolean scanAll;
  private Utf8ScannerBenchmarkAccess swarScanner;
  private ScanProvider swarProvider;
  private ScanProvider vectorProvider;
  private ByteVector[] lowerBounds;
  private ByteVector[] upperBounds;
  private Pattern safeRePattern;
  private Utf8Input safeReInput;
  private Utf8Matcher safeReMatcher;

  /** Materializes the declared input and verifies that both implementations agree. */
  @Setup(Level.Trial)
  public void setup() {
    String configuredProvider = System.getProperty(PROVIDER_PROPERTY, "").trim();
    if (!configuredProvider.isEmpty() && !configuredProvider.equals("swar")) {
      throw new IllegalStateException(
          "Low-level benchmarks require the SWAR production provider, not " + configuredProvider);
    }
    String[] fields = vectorScanTrial.split("/", -1);
    if (fields.length != 5) {
      throw new IllegalArgumentException("Malformed Vector scan trial: " + vectorScanTrial);
    }
    shape = Shape.valueOf(fields[0].toUpperCase(java.util.Locale.ROOT));
    scanAll =
        switch (fields[1]) {
          case "first" -> false;
          case "all" -> true;
          default -> throw new IllegalArgumentException("Unknown traversal: " + fields[1]);
        };
    length = Integer.parseInt(fields[3]);
    offset = Integer.parseInt(fields[4]);
    byte[] input = VectorScanConfiguration.input(shape.inputProfile, fields[2], length);
    storage = new byte[offset + input.length + SPECIES.length()];
    Arrays.fill(storage, (byte) 0x7f);
    System.arraycopy(input, 0, storage, offset, input.length);
    swarScanner = new Utf8ScannerBenchmarkAccess(storage, offset, length, shape.ranges);
    swarProvider = new SwarProvider();
    vectorProvider = new VectorProvider();
    int rangeCount = shape.ranges.length / 2;
    lowerBounds = new ByteVector[rangeCount];
    upperBounds = new ByteVector[rangeCount];
    for (int range = 0; range < rangeCount; range++) {
      lowerBounds[range] = ByteVector.broadcast(SPECIES, (byte) shape.ranges[range * 2]);
      upperBounds[range] = ByteVector.broadcast(SPECIES, (byte) shape.ranges[range * 2 + 1]);
    }
    safeRePattern = Pattern.compile(shape.pattern);
    safeReInput = Utf8Input.trusted(storage, offset, length);
    safeReMatcher = safeRePattern.matcher(safeReInput);
    int swarResult = runSwar();
    int swarProviderResult = runSwarProvider();
    int vectorResult = runVector();
    int vectorProviderResult = runVectorProvider();
    int vectorBoundsResult = runVectorBounds();
    int vectorCursorResult = runVectorCursor();
    if (swarResult != swarProviderResult
        || swarResult != vectorResult
        || swarResult != vectorProviderResult
        || swarResult != vectorBoundsResult
        || swarResult != vectorCursorResult) {
      throw new AssertionError(
          "Scanner disagreement for "
              + vectorScanTrial
              + ": SWAR="
              + swarResult
              + ", SWAR provider="
              + swarProviderResult
              + ", Vector="
              + vectorResult
              + ", Vector provider="
              + vectorProviderResult
              + ", Vector bounds="
              + vectorBoundsResult
              + ", Vector cursor="
              + vectorCursorResult);
    }
    int safeReResult = runSafeRe();
    int expectedSafeReResult = scanAll ? swarResult : (swarResult >= 0 ? 1 : 0);
    if (safeReResult != expectedSafeReResult) {
      throw new AssertionError(
          "SafeRE disagreement for "
              + vectorScanTrial
              + ": expected="
              + expectedSafeReResult
              + ", actual="
              + safeReResult);
    }
  }

  /** Runs the current SafeRE SWAR implementation. */
  @Benchmark
  public int swar() {
    return runSwar();
  }

  /** Runs current SWAR through a stable benchmark-only provider interface. */
  @Benchmark
  public int swarProvider() {
    return runSwarProvider();
  }

  /** Runs the benchmark-only ByteVector implementation. */
  @Benchmark
  public int vector() {
    return runVector();
  }

  /** Runs ByteVector through a stable benchmark-only provider interface. */
  @Benchmark
  public int vectorProvider() {
    return runVectorProvider();
  }

  /** Runs ByteVector with range bounds broadcast once outside the input loop. */
  @Benchmark
  public int vectorBounds() {
    return runVectorBounds();
  }

  /** Runs a ByteVector implementation that retains and drains each complete match mask. */
  @Benchmark
  public int vectorCursor() {
    return runVectorCursor();
  }

  /** Runs the corresponding complete SafeRE UTF-8 find operation. */
  @Benchmark
  public int safeRe() {
    return runSafeRe();
  }

  private int runSafeRe() {
    if (!scanAll) {
      return safeRePattern.find(safeReInput) ? 1 : 0;
    }
    safeReMatcher.reset();
    int checksum = 0;
    while (safeReMatcher.find()) {
      checksum += safeReMatcher.start();
    }
    return checksum;
  }

  private int runSwar() {
    if (!scanAll) {
      return swarScanner.indexOfCodePointClass(0);
    }
    int checksum = 0;
    int start = 0;
    while (start < length) {
      int found = swarScanner.indexOfCodePointClass(start);
      if (found < 0) {
        break;
      }
      checksum += found;
      start = found + 1;
    }
    return checksum;
  }

  private int runSwarProvider() {
    if (!scanAll) {
      return swarProvider.indexOf(0);
    }
    int checksum = 0;
    int start = 0;
    while (start < length) {
      int found = swarProvider.indexOf(start);
      if (found < 0) {
        break;
      }
      checksum += found;
      start = found + 1;
    }
    return checksum;
  }

  private int runVector() {
    if (!scanAll) {
      return indexOfVector(0);
    }
    int checksum = 0;
    int start = 0;
    while (start < length) {
      int found = indexOfVector(start);
      if (found < 0) {
        break;
      }
      checksum += found;
      start = found + 1;
    }
    return checksum;
  }

  private int runVectorProvider() {
    if (!scanAll) {
      return vectorProvider.indexOf(0);
    }
    int checksum = 0;
    int start = 0;
    while (start < length) {
      int found = vectorProvider.indexOf(start);
      if (found < 0) {
        break;
      }
      checksum += found;
      start = found + 1;
    }
    return checksum;
  }

  private int runVectorBounds() {
    if (!scanAll) {
      return indexOfVectorBounds(0);
    }
    int checksum = 0;
    int start = 0;
    while (start < length) {
      int found = indexOfVectorBounds(start);
      if (found < 0) {
        break;
      }
      checksum += found;
      start = found + 1;
    }
    return checksum;
  }

  private int runVectorCursor() {
    if (!scanAll) {
      return indexOfVector(0);
    }
    int checksum = 0;
    int position = 0;
    int limit = SPECIES.loopBound(length);
    for (; position < limit; position += SPECIES.length()) {
      ByteVector values = ByteVector.fromArray(SPECIES, storage, offset + position);
      VectorMask<Byte> matches = shape.matches(values);
      if (!matches.anyTrue()) {
        continue;
      }
      long matchBits = matches.toLong();
      while (matchBits != 0) {
        int lane = Long.numberOfTrailingZeros(matchBits);
        checksum += position + lane;
        matchBits &= matchBits - 1;
      }
    }
    for (; position < length; position++) {
      if (shape.matches(storage[offset + position])) {
        checksum += position;
      }
    }
    return checksum;
  }

  private int indexOfVector(int start) {
    int position = Math.max(0, start);
    int limit = position + SPECIES.loopBound(length - position);
    for (; position < limit; position += SPECIES.length()) {
      ByteVector values = ByteVector.fromArray(SPECIES, storage, offset + position);
      VectorMask<Byte> matches = shape.matches(values);
      if (matches.anyTrue()) {
        return position + matches.firstTrue();
      }
    }
    for (; position < length; position++) {
      if (shape.matches(storage[offset + position])) {
        return position;
      }
    }
    return -1;
  }

  private int indexOfVectorBounds(int start) {
    int position = Math.max(0, start);
    int limit = position + SPECIES.loopBound(length - position);
    for (; position < limit; position += SPECIES.length()) {
      ByteVector values = ByteVector.fromArray(SPECIES, storage, offset + position);
      VectorMask<Byte> matches =
          values.compare(GE, lowerBounds[0]).and(values.compare(LE, upperBounds[0]));
      for (int range = 1; range < lowerBounds.length; range++) {
        matches =
            matches.or(
                values.compare(GE, lowerBounds[range]).and(values.compare(LE, upperBounds[range])));
      }
      if (matches.anyTrue()) {
        return position + matches.firstTrue();
      }
    }
    for (; position < length; position++) {
      if (shape.matches(storage[offset + position])) {
        return position;
      }
    }
    return -1;
  }

  private interface ScanProvider {
    int indexOf(int start);
  }

  private final class SwarProvider implements ScanProvider {
    @Override
    public int indexOf(int start) {
      return swarScanner.indexOfCodePointClass(start);
    }
  }

  private final class VectorProvider implements ScanProvider {
    @Override
    public int indexOf(int start) {
      return indexOfVector(start);
    }
  }

  private enum Shape {
    SINGLETON(SINGLETON_RANGES, "x") {
      @Override
      VectorMask<Byte> matches(ByteVector values) {
        return values.eq((byte) 'x');
      }

      @Override
      boolean matches(byte value) {
        return value == 'x';
      }
    },
    PAIR(PAIR_RANGES, "[xy]") {
      @Override
      VectorMask<Byte> matches(ByteVector values) {
        return values.eq((byte) 'x').or(values.eq((byte) 'y'));
      }

      @Override
      boolean matches(byte value) {
        return value == 'x' || value == 'y';
      }
    },
    RANGE(RANGE_RANGES, "[0-9]") {
      @Override
      VectorMask<Byte> matches(ByteVector values) {
        return values.compare(GE, (byte) '0').and(values.compare(LE, (byte) '9'));
      }

      @Override
      boolean matches(byte value) {
        return value >= '0' && value <= '9';
      }
    },
    ALNUM3(ALNUM3_RANGES, "[0-9A-Za-z]", "multi.") {
      @Override
      VectorMask<Byte> matches(ByteVector values) {
        VectorMask<Byte> digits =
            values.compare(GE, (byte) '0').and(values.compare(LE, (byte) '9'));
        VectorMask<Byte> upper = values.compare(GE, (byte) 'A').and(values.compare(LE, (byte) 'Z'));
        VectorMask<Byte> lower = values.compare(GE, (byte) 'a').and(values.compare(LE, (byte) 'z'));
        return digits.or(upper).or(lower);
      }

      @Override
      boolean matches(byte value) {
        return (value >= '0' && value <= '9')
            || (value >= 'A' && value <= 'Z')
            || (value >= 'a' && value <= 'z');
      }
    },
    MIXED4(MIXED4_RANGES, "[!#0-9A-Z]", "multi.") {
      @Override
      VectorMask<Byte> matches(ByteVector values) {
        VectorMask<Byte> singletons = values.eq((byte) '!').or(values.eq((byte) '#'));
        VectorMask<Byte> digits =
            values.compare(GE, (byte) '0').and(values.compare(LE, (byte) '9'));
        VectorMask<Byte> upper = values.compare(GE, (byte) 'A').and(values.compare(LE, (byte) 'Z'));
        return singletons.or(digits).or(upper);
      }

      @Override
      boolean matches(byte value) {
        return value == '!'
            || value == '#'
            || (value >= '0' && value <= '9')
            || (value >= 'A' && value <= 'Z');
      }
    };

    private final int[] ranges;
    private final String pattern;
    private final String inputProfile;

    Shape(int[] ranges, String pattern) {
      this(ranges, pattern, "");
    }

    Shape(int[] ranges, String pattern, String inputProfile) {
      this.ranges = ranges;
      this.pattern = pattern;
      this.inputProfile = inputProfile;
    }

    abstract VectorMask<Byte> matches(ByteVector values);

    abstract boolean matches(byte value);
  }
}
