// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

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
 * Benchmarks for capture group overhead scaling, ported from RE2 C++ {@code regexp_benchmark.cc}.
 *
 * <p>Measures the cost of extracting 1, 3, and 10 capture groups to show how SafeRE and JDK scale
 * with increasing capture count. RE2 C++ tests up to 26 groups; we use 10 as a practical upper
 * bound.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class CaptureScalingBenchmark {

  // 1 capture group: phone area code.
  private dev.eaftan.safere.Pattern safe1;
  private java.util.regex.Pattern jdk1;
  private String text1;
  private String pat1;

  // 3 capture groups: phone number (same as RE2's Parse_Digits).
  private dev.eaftan.safere.Pattern safe3;
  private java.util.regex.Pattern jdk3;
  private String text3;
  private String pat3;

  // 10 capture groups: key=value pairs.
  private dev.eaftan.safere.Pattern safe10;
  private java.util.regex.Pattern jdk10;
  private String text10;
  private String pat10;

  // 0 capture groups (baseline): same text, no groups.
  private dev.eaftan.safere.Pattern safe0;
  private java.util.regex.Pattern jdk0;
  private String pat0;
  private String text0;

  // RE2/J patterns.
  private com.google.re2j.Pattern re2j0;
  private com.google.re2j.Pattern re2j1;
  private com.google.re2j.Pattern re2j3;
  private com.google.re2j.Pattern re2j10;

  // RE2-FFM patterns.
  private dev.eaftan.safere.re2ffm.RE2FfmPattern re2ffm0;
  private dev.eaftan.safere.re2ffm.RE2FfmPattern re2ffm1;
  private dev.eaftan.safere.re2ffm.RE2FfmPattern re2ffm3;
  private dev.eaftan.safere.re2ffm.RE2FfmPattern re2ffm10;

  @Setup
  public void setup() {
    BenchmarkData data = BenchmarkData.get();
    pat0 = data.getString("captureScaling.capture0.pattern");
    text0 = data.getString("captureScaling.capture0.text");
    pat1 = data.getString("captureScaling.capture1.pattern");
    text1 = data.getString("captureScaling.capture1.text");
    pat3 = data.getString("captureScaling.capture3.pattern");
    text3 = data.getString("captureScaling.capture3.text");
    pat10 = data.getString("captureScaling.capture10.pattern");
    text10 = data.getString("captureScaling.capture10.text");

    safe0 = dev.eaftan.safere.Pattern.compile(pat0);
    jdk0 = java.util.regex.Pattern.compile(pat0);
    safe1 = dev.eaftan.safere.Pattern.compile(pat1);
    jdk1 = java.util.regex.Pattern.compile(pat1);
    safe3 = dev.eaftan.safere.Pattern.compile(pat3);
    jdk3 = java.util.regex.Pattern.compile(pat3);
    safe10 = dev.eaftan.safere.Pattern.compile(pat10);
    jdk10 = java.util.regex.Pattern.compile(pat10);

    re2j0 = com.google.re2j.Pattern.compile(pat0);
    re2j1 = com.google.re2j.Pattern.compile(pat1);
    re2j3 = com.google.re2j.Pattern.compile(pat3);
    re2j10 = com.google.re2j.Pattern.compile(pat10);

    re2ffm0 = dev.eaftan.safere.re2ffm.RE2FfmPattern.compile(pat0);
    re2ffm1 = dev.eaftan.safere.re2ffm.RE2FfmPattern.compile(pat1);
    re2ffm3 = dev.eaftan.safere.re2ffm.RE2FfmPattern.compile(pat3);
    re2ffm10 = dev.eaftan.safere.re2ffm.RE2FfmPattern.compile(pat10);
  }

  // ===== 0 groups (baseline) =====

  @Benchmark
  public boolean capture0_safere() {
    return safe0.matcher(text0).matches();
  }

  @Benchmark
  public boolean capture0_jdk() {
    return jdk0.matcher(text0).matches();
  }

  @Benchmark
  public boolean capture0_re2j() {
    return re2j0.matcher(text0).matches();
  }

  @Benchmark
  public boolean capture0_re2ffm() {
    return re2ffm0.matcher(text0).matches();
  }

  // ===== 1 group =====

  @Benchmark
  public String capture1_safere() {
    dev.eaftan.safere.Matcher m = safe1.matcher(text1);
    m.matches();
    return m.group(1);
  }

  @Benchmark
  public String capture1_jdk() {
    java.util.regex.Matcher m = jdk1.matcher(text1);
    m.matches();
    return m.group(1);
  }

  @Benchmark
  public String capture1_re2j() {
    com.google.re2j.Matcher m = re2j1.matcher(text1);
    m.matches();
    return m.group(1);
  }

  @Benchmark
  public String capture1_re2ffm() {
    dev.eaftan.safere.re2ffm.RE2FfmMatcher m = re2ffm1.matcher(text1);
    m.matches();
    return m.group(1);
  }

  // ===== 3 groups =====

  @Benchmark
  public String capture3_safere() {
    dev.eaftan.safere.Matcher m = safe3.matcher(text3);
    m.matches();
    return m.group(1) + m.group(2) + m.group(3);
  }

  @Benchmark
  public String capture3_jdk() {
    java.util.regex.Matcher m = jdk3.matcher(text3);
    m.matches();
    return m.group(1) + m.group(2) + m.group(3);
  }

  @Benchmark
  public String capture3_re2j() {
    com.google.re2j.Matcher m = re2j3.matcher(text3);
    m.matches();
    return m.group(1) + m.group(2) + m.group(3);
  }

  @Benchmark
  public String capture3_re2ffm() {
    dev.eaftan.safere.re2ffm.RE2FfmMatcher m = re2ffm3.matcher(text3);
    m.matches();
    return m.group(1) + m.group(2) + m.group(3);
  }

  // ===== 10 groups =====

  @Benchmark
  public String capture10_safere() {
    dev.eaftan.safere.Matcher m = safe10.matcher(text10);
    m.matches();
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= 10; i++) {
      sb.append(m.group(i));
    }
    return sb.toString();
  }

  @Benchmark
  public String capture10_jdk() {
    java.util.regex.Matcher m = jdk10.matcher(text10);
    m.matches();
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= 10; i++) {
      sb.append(m.group(i));
    }
    return sb.toString();
  }

  @Benchmark
  public String capture10_re2j() {
    com.google.re2j.Matcher m = re2j10.matcher(text10);
    m.matches();
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= 10; i++) {
      sb.append(m.group(i));
    }
    return sb.toString();
  }

  @Benchmark
  public String capture10_re2ffm() {
    dev.eaftan.safere.re2ffm.RE2FfmMatcher m = re2ffm10.matcher(text10);
    m.matches();
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= 10; i++) {
      sb.append(m.group(i));
    }
    return sb.toString();
  }
}
