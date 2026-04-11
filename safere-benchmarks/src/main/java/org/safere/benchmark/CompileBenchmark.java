// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Benchmarks for pattern compilation time: SafeRE vs {@code java.util.regex}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
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
  public org.safere.Pattern compileSimple_safere() {
    return org.safere.Pattern.compile(simplePattern);
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
  public org.safere.re2ffm.RE2FfmPattern compileSimple_re2ffm() {
    return org.safere.re2ffm.RE2FfmPattern.compile(simplePattern);
  }

  // ===== Medium pattern (date-time with captures) =====

  @Benchmark
  public org.safere.Pattern compileMedium_safere() {
    return org.safere.Pattern.compile(mediumPattern);
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
  public org.safere.re2ffm.RE2FfmPattern compileMedium_re2ffm() {
    return org.safere.re2ffm.RE2FfmPattern.compile(mediumPattern);
  }

  // ===== Complex pattern (email) =====

  @Benchmark
  public org.safere.Pattern compileComplex_safere() {
    return org.safere.Pattern.compile(complexPattern);
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
  public org.safere.re2ffm.RE2FfmPattern compileComplex_re2ffm() {
    return org.safere.re2ffm.RE2FfmPattern.compile(complexPattern);
  }

  // ===== Alternation pattern =====

  @Benchmark
  public org.safere.Pattern compileAlternation_safere() {
    return org.safere.Pattern.compile(alternationPattern);
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
  public org.safere.re2ffm.RE2FfmPattern compileAlternation_re2ffm() {
    return org.safere.re2ffm.RE2FfmPattern.compile(alternationPattern);
  }
}
