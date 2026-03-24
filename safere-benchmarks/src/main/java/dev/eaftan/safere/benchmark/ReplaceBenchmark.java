// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere.benchmark;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmarks for replace operations: SafeRE vs {@code java.util.regex}.
 *
 * <p>Neither RE2 C++ nor SafeRE previously benchmarked replace. These cover replaceFirst and
 * replaceAll with varying replacement complexity.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class ReplaceBenchmark {

  // Simple literal replacement.
  private dev.eaftan.safere.Pattern safeLiteral;
  private java.util.regex.Pattern jdkLiteral;
  private com.google.re2j.Pattern re2jLiteral;
  private static final String LITERAL_TEXT = "ababababab";

  // Replace with backreference (pig latin from RE2 C++ tests).
  private dev.eaftan.safere.Pattern safePigLatin;
  private java.util.regex.Pattern jdkPigLatin;
  private com.google.re2j.Pattern re2jPigLatin;
  private static final String PIG_LATIN_TEXT = "the quick brown fox jumps over the lazy dogs";

  // replaceAll on text with many matches.
  private dev.eaftan.safere.Pattern safeDigits;
  private java.util.regex.Pattern jdkDigits;
  private com.google.re2j.Pattern re2jDigits;
  private static final String DIGITS_TEXT =
      "order 12345 shipped on 2025-03-22 tracking 9876543210 weight 42kg price $199";

  // Empty-match replacement (tricky edge case).
  private dev.eaftan.safere.Pattern safeEmpty;
  private java.util.regex.Pattern jdkEmpty;
  private com.google.re2j.Pattern re2jEmpty;
  private static final String EMPTY_TEXT = "abc";

  @Setup
  public void setup() {
    safeLiteral = dev.eaftan.safere.Pattern.compile("b");
    jdkLiteral = java.util.regex.Pattern.compile("b");

    String pigPattern = "(qu|[b-df-hj-np-tv-z]*)([a-z]+)";
    safePigLatin = dev.eaftan.safere.Pattern.compile(pigPattern);
    jdkPigLatin = java.util.regex.Pattern.compile(pigPattern);

    safeDigits = dev.eaftan.safere.Pattern.compile("\\d+");
    jdkDigits = java.util.regex.Pattern.compile("\\d+");

    safeEmpty = dev.eaftan.safere.Pattern.compile("a*");
    jdkEmpty = java.util.regex.Pattern.compile("a*");

    re2jLiteral = com.google.re2j.Pattern.compile("b");
    re2jPigLatin = com.google.re2j.Pattern.compile(pigPattern);
    re2jDigits = com.google.re2j.Pattern.compile("\\d+");
    re2jEmpty = com.google.re2j.Pattern.compile("a*");
  }

  // ===== Simple literal replaceFirst =====

  @Benchmark
  public String literalReplaceFirst_safere() {
    return safeLiteral.matcher(LITERAL_TEXT).replaceFirst("bb");
  }

  @Benchmark
  public String literalReplaceFirst_jdk() {
    return jdkLiteral.matcher(LITERAL_TEXT).replaceFirst("bb");
  }

  @Benchmark
  public String literalReplaceFirst_re2j() {
    return re2jLiteral.matcher(LITERAL_TEXT).replaceFirst("bb");
  }

  // ===== Simple literal replaceAll =====

  @Benchmark
  public String literalReplaceAll_safere() {
    return safeLiteral.matcher(LITERAL_TEXT).replaceAll("bb");
  }

  @Benchmark
  public String literalReplaceAll_jdk() {
    return jdkLiteral.matcher(LITERAL_TEXT).replaceAll("bb");
  }

  @Benchmark
  public String literalReplaceAll_re2j() {
    return re2jLiteral.matcher(LITERAL_TEXT).replaceAll("bb");
  }

  // ===== Pig Latin replaceAll (backreference in replacement) =====

  @Benchmark
  public String pigLatinReplaceAll_safere() {
    return safePigLatin.matcher(PIG_LATIN_TEXT).replaceAll("$2$1ay");
  }

  @Benchmark
  public String pigLatinReplaceAll_jdk() {
    return jdkPigLatin.matcher(PIG_LATIN_TEXT).replaceAll("$2$1ay");
  }

  @Benchmark
  public String pigLatinReplaceAll_re2j() {
    return re2jPigLatin.matcher(PIG_LATIN_TEXT).replaceAll("$2$1ay");
  }

  // ===== Digit replacement (many matches) =====

  @Benchmark
  public String digitReplaceAll_safere() {
    return safeDigits.matcher(DIGITS_TEXT).replaceAll("NUM");
  }

  @Benchmark
  public String digitReplaceAll_jdk() {
    return jdkDigits.matcher(DIGITS_TEXT).replaceAll("NUM");
  }

  @Benchmark
  public String digitReplaceAll_re2j() {
    return re2jDigits.matcher(DIGITS_TEXT).replaceAll("NUM");
  }

  // ===== Empty-match replaceAll (edge case) =====

  @Benchmark
  public String emptyReplaceAll_safere() {
    return safeEmpty.matcher(EMPTY_TEXT).replaceAll("x");
  }

  @Benchmark
  public String emptyReplaceAll_jdk() {
    return jdkEmpty.matcher(EMPTY_TEXT).replaceAll("x");
  }

  @Benchmark
  public String emptyReplaceAll_re2j() {
    return re2jEmpty.matcher(EMPTY_TEXT).replaceAll("x");
  }
}
