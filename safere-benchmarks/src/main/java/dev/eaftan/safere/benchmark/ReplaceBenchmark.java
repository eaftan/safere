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
  private String literalReplaceFirstText;
  private String literalReplaceFirstReplacement;
  private String literalReplaceAllText;
  private String literalReplaceAllReplacement;

  // Replace with backreference (pig latin from RE2 C++ tests).
  private dev.eaftan.safere.Pattern safePigLatin;
  private java.util.regex.Pattern jdkPigLatin;
  private com.google.re2j.Pattern re2jPigLatin;
  private String pigLatinText;
  private String pigLatinReplacement;

  // replaceAll on text with many matches.
  private dev.eaftan.safere.Pattern safeDigits;
  private java.util.regex.Pattern jdkDigits;
  private com.google.re2j.Pattern re2jDigits;
  private String digitsText;
  private String digitsReplacement;

  // Empty-match replacement (tricky edge case).
  private dev.eaftan.safere.Pattern safeEmpty;
  private java.util.regex.Pattern jdkEmpty;
  private com.google.re2j.Pattern re2jEmpty;
  private String emptyText;
  private String emptyReplacement;

  @Setup
  public void setup() {
    BenchmarkData data = BenchmarkData.get();

    String literalReplaceFirstPattern = data.getString("replace.literalReplaceFirst.pattern");
    literalReplaceFirstText = data.getString("replace.literalReplaceFirst.text");
    literalReplaceFirstReplacement = data.getString("replace.literalReplaceFirst.replacement");

    String literalReplaceAllPattern = data.getString("replace.literalReplaceAll.pattern");
    literalReplaceAllText = data.getString("replace.literalReplaceAll.text");
    literalReplaceAllReplacement = data.getString("replace.literalReplaceAll.replacement");

    safeLiteral = dev.eaftan.safere.Pattern.compile(literalReplaceFirstPattern);
    jdkLiteral = java.util.regex.Pattern.compile(literalReplaceFirstPattern);
    re2jLiteral = com.google.re2j.Pattern.compile(literalReplaceFirstPattern);

    String pigPattern = data.getString("replace.pigLatinReplaceAll.pattern");
    pigLatinText = data.getString("replace.pigLatinReplaceAll.text");
    pigLatinReplacement = data.getString("replace.pigLatinReplaceAll.replacement");

    safePigLatin = dev.eaftan.safere.Pattern.compile(pigPattern);
    jdkPigLatin = java.util.regex.Pattern.compile(pigPattern);
    re2jPigLatin = com.google.re2j.Pattern.compile(pigPattern);

    String digitPattern = data.getString("replace.digitReplaceAll.pattern");
    digitsText = data.getString("replace.digitReplaceAll.text");
    digitsReplacement = data.getString("replace.digitReplaceAll.replacement");

    safeDigits = dev.eaftan.safere.Pattern.compile(digitPattern);
    jdkDigits = java.util.regex.Pattern.compile(digitPattern);
    re2jDigits = com.google.re2j.Pattern.compile(digitPattern);

    String emptyPattern = data.getString("replace.emptyReplaceAll.pattern");
    emptyText = data.getString("replace.emptyReplaceAll.text");
    emptyReplacement = data.getString("replace.emptyReplaceAll.replacement");

    safeEmpty = dev.eaftan.safere.Pattern.compile(emptyPattern);
    jdkEmpty = java.util.regex.Pattern.compile(emptyPattern);
    re2jEmpty = com.google.re2j.Pattern.compile(emptyPattern);
  }

  // ===== Simple literal replaceFirst =====

  @Benchmark
  public String literalReplaceFirst_safere() {
    return safeLiteral.matcher(literalReplaceFirstText).replaceFirst(literalReplaceFirstReplacement);
  }

  @Benchmark
  public String literalReplaceFirst_jdk() {
    return jdkLiteral.matcher(literalReplaceFirstText).replaceFirst(literalReplaceFirstReplacement);
  }

  @Benchmark
  public String literalReplaceFirst_re2j() {
    return re2jLiteral.matcher(literalReplaceFirstText).replaceFirst(literalReplaceFirstReplacement);
  }

  // ===== Simple literal replaceAll =====

  @Benchmark
  public String literalReplaceAll_safere() {
    return safeLiteral.matcher(literalReplaceAllText).replaceAll(literalReplaceAllReplacement);
  }

  @Benchmark
  public String literalReplaceAll_jdk() {
    return jdkLiteral.matcher(literalReplaceAllText).replaceAll(literalReplaceAllReplacement);
  }

  @Benchmark
  public String literalReplaceAll_re2j() {
    return re2jLiteral.matcher(literalReplaceAllText).replaceAll(literalReplaceAllReplacement);
  }

  // ===== Pig Latin replaceAll (backreference in replacement) =====

  @Benchmark
  public String pigLatinReplaceAll_safere() {
    return safePigLatin.matcher(pigLatinText).replaceAll(pigLatinReplacement);
  }

  @Benchmark
  public String pigLatinReplaceAll_jdk() {
    return jdkPigLatin.matcher(pigLatinText).replaceAll(pigLatinReplacement);
  }

  @Benchmark
  public String pigLatinReplaceAll_re2j() {
    return re2jPigLatin.matcher(pigLatinText).replaceAll(pigLatinReplacement);
  }

  // ===== Digit replacement (many matches) =====

  @Benchmark
  public String digitReplaceAll_safere() {
    return safeDigits.matcher(digitsText).replaceAll(digitsReplacement);
  }

  @Benchmark
  public String digitReplaceAll_jdk() {
    return jdkDigits.matcher(digitsText).replaceAll(digitsReplacement);
  }

  @Benchmark
  public String digitReplaceAll_re2j() {
    return re2jDigits.matcher(digitsText).replaceAll(digitsReplacement);
  }

  // ===== Empty-match replaceAll (edge case) =====

  @Benchmark
  public String emptyReplaceAll_safere() {
    return safeEmpty.matcher(emptyText).replaceAll(emptyReplacement);
  }

  @Benchmark
  public String emptyReplaceAll_jdk() {
    return jdkEmpty.matcher(emptyText).replaceAll(emptyReplacement);
  }

  @Benchmark
  public String emptyReplaceAll_re2j() {
    return re2jEmpty.matcher(emptyText).replaceAll(emptyReplacement);
  }
}
