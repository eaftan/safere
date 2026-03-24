// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere.benchmark;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Pathological fanout benchmark, ported from RE2 C++ {@code regexp_benchmark.cc}.
 *
 * <p>The pattern {@code (?:[\x{80}-\x{10FFFF}]?){100}[\x{80}-\x{10FFFF}]} creates high NFA
 * fanout because each of the 100 optional Unicode ranges can match or skip, creating 2^100 possible
 * paths. This stresses DFA state generation and NFA thread management.
 *
 * <p>Also includes a simpler nested-quantifier fanout pattern to measure the impact of quantifier
 * nesting depth.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class FanoutBenchmark {

  @Param({"1024", "10240", "102400"})
  private int textSize;

  // Unicode fanout: RE2's Search_Fanout pattern (adapted for Java).
  // [\x{80}-\x{10FFFF}] matches any non-ASCII Unicode code point.
  private dev.eaftan.safere.Pattern safeFanout;
  private java.util.regex.Pattern jdkFanout;
  private com.google.re2j.Pattern re2jFanout;

  // Nested quantifier: a?{n}a{n} but with higher n and varying text.
  private dev.eaftan.safere.Pattern safeNested;
  private java.util.regex.Pattern jdkNested;
  private com.google.re2j.Pattern re2jNested;

  private String unicodeText;
  private String asciiText;

  private static final String FANOUT_PATTERN =
      "(?:[\\x{80}-\\x{10FFFF}]?){100}[\\x{80}-\\x{10FFFF}]";
  private static final String NESTED_PATTERN = "(?:a?){20}a{20}";

  @Setup
  public void setup() {
    safeFanout = dev.eaftan.safere.Pattern.compile(FANOUT_PATTERN);
    jdkFanout = java.util.regex.Pattern.compile(
        "(?:[\\x{80}-\\x{10FFFF}]?){100}[\\x{80}-\\x{10FFFF}]");

    safeNested = dev.eaftan.safere.Pattern.compile(NESTED_PATTERN);
    jdkNested = java.util.regex.Pattern.compile(NESTED_PATTERN);

    re2jFanout = com.google.re2j.Pattern.compile(FANOUT_PATTERN);
    re2jNested = com.google.re2j.Pattern.compile(NESTED_PATTERN);

    // Generate Unicode text (mix of CJK, emoji, Latin Extended).
    Random rng = new Random(42);
    StringBuilder sb = new StringBuilder();
    int[] codePoints = {
        0x4E00, 0x4E01, 0x4E02, // CJK
        0x00E9, 0x00F1, 0x00FC, // Latin Extended
        0x0410, 0x0411, 0x0412, // Cyrillic
        0x3042, 0x3044, 0x3046, // Hiragana
    };
    while (sb.length() < textSize) {
      sb.appendCodePoint(codePoints[rng.nextInt(codePoints.length)]);
    }
    unicodeText = sb.toString();

    // Generate ASCII text with 'a' characters for nested quantifier test.
    char[] chars = new char[textSize];
    String alphabet = "abcdefghijklmnopqrstuvwxyz";
    for (int i = 0; i < textSize; i++) {
      chars[i] = alphabet.charAt(rng.nextInt(alphabet.length()));
    }
    asciiText = new String(chars);
  }

  // ===== Unicode fanout: find in Unicode text =====

  @Benchmark
  public boolean fanoutUnicode_safere() {
    return safeFanout.matcher(unicodeText).find();
  }

  @Benchmark
  public boolean fanoutUnicode_jdk() {
    return jdkFanout.matcher(unicodeText).find();
  }

  @Benchmark
  public boolean fanoutUnicode_re2j() {
    return re2jFanout.matcher(unicodeText).find();
  }

  // ===== Nested quantifier: find in ASCII text =====

  @Benchmark
  public boolean nestedQuantifier_safere() {
    return safeNested.matcher(asciiText).find();
  }

  @Benchmark
  public boolean nestedQuantifier_jdk() {
    return jdkNested.matcher(asciiText).find();
  }

  @Benchmark
  public boolean nestedQuantifier_re2j() {
    return re2jNested.matcher(asciiText).find();
  }
}
