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
 * Benchmarks for pattern compilation time: SafeRE vs {@code java.util.regex}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class CompileBenchmark {

  private String simplePattern;
  private String mediumPattern;
  private String complexPattern;
  private String alternationPattern;

  @Setup
  public void setup() {
    BenchmarkData data = BenchmarkData.get();
    simplePattern = data.getString("compile.simple.pattern");
    mediumPattern = data.getString("compile.medium.pattern");
    complexPattern = data.getString("compile.complex.pattern");
    alternationPattern = data.getString("compile.alternation.pattern");
  }

  // ===== Simple pattern =====

  @Benchmark
  public dev.eaftan.safere.Pattern compileSimple_safere() {
    return dev.eaftan.safere.Pattern.compile(simplePattern);
  }

  @Benchmark
  public java.util.regex.Pattern compileSimple_jdk() {
    return java.util.regex.Pattern.compile(simplePattern);
  }

  @Benchmark
  public com.google.re2j.Pattern compileSimple_re2j() {
    return com.google.re2j.Pattern.compile(simplePattern);
  }

  @Benchmark
  public dev.eaftan.safere.re2ffm.RE2FfmPattern compileSimple_re2ffm() {
    return dev.eaftan.safere.re2ffm.RE2FfmPattern.compile(simplePattern);
  }

  // ===== Medium pattern (date-time with captures) =====

  @Benchmark
  public dev.eaftan.safere.Pattern compileMedium_safere() {
    return dev.eaftan.safere.Pattern.compile(mediumPattern);
  }

  @Benchmark
  public java.util.regex.Pattern compileMedium_jdk() {
    return java.util.regex.Pattern.compile(mediumPattern);
  }

  @Benchmark
  public com.google.re2j.Pattern compileMedium_re2j() {
    return com.google.re2j.Pattern.compile(mediumPattern);
  }

  @Benchmark
  public dev.eaftan.safere.re2ffm.RE2FfmPattern compileMedium_re2ffm() {
    return dev.eaftan.safere.re2ffm.RE2FfmPattern.compile(mediumPattern);
  }

  // ===== Complex pattern (email) =====

  @Benchmark
  public dev.eaftan.safere.Pattern compileComplex_safere() {
    return dev.eaftan.safere.Pattern.compile(complexPattern);
  }

  @Benchmark
  public java.util.regex.Pattern compileComplex_jdk() {
    return java.util.regex.Pattern.compile(complexPattern);
  }

  @Benchmark
  public com.google.re2j.Pattern compileComplex_re2j() {
    return com.google.re2j.Pattern.compile(complexPattern);
  }

  @Benchmark
  public dev.eaftan.safere.re2ffm.RE2FfmPattern compileComplex_re2ffm() {
    return dev.eaftan.safere.re2ffm.RE2FfmPattern.compile(complexPattern);
  }

  // ===== Alternation pattern =====

  @Benchmark
  public dev.eaftan.safere.Pattern compileAlternation_safere() {
    return dev.eaftan.safere.Pattern.compile(alternationPattern);
  }

  @Benchmark
  public java.util.regex.Pattern compileAlternation_jdk() {
    return java.util.regex.Pattern.compile(alternationPattern);
  }

  @Benchmark
  public com.google.re2j.Pattern compileAlternation_re2j() {
    return com.google.re2j.Pattern.compile(alternationPattern);
  }

  @Benchmark
  public dev.eaftan.safere.re2ffm.RE2FfmPattern compileAlternation_re2ffm() {
    return dev.eaftan.safere.re2ffm.RE2FfmPattern.compile(alternationPattern);
  }
}
