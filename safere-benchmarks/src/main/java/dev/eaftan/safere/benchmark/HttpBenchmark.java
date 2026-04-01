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
 * HTTP request parsing benchmark, ported from RE2 C++ {@code regexp_benchmark.cc}.
 *
 * <p>Measures partial matching of an HTTP request line pattern against realistic input. RE2 C++
 * tests this as {@code HTTPPartialMatch} and {@code SmallHTTPPartialMatch}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class HttpBenchmark {

  private String httpPattern;
  private String fullRequest;
  private String smallRequest;

  private dev.eaftan.safere.Pattern safeHttp;
  private java.util.regex.Pattern jdkHttp;
  private com.google.re2j.Pattern re2jHttp;
  private dev.eaftan.safere.re2ffm.RE2FfmPattern re2ffmHttp;

  @Setup
  public void setup() {
    BenchmarkData data = BenchmarkData.get();
    httpPattern = data.getString("http.pattern");
    fullRequest = data.getString("http.fullRequest");
    smallRequest = data.getString("http.smallRequest");

    safeHttp = dev.eaftan.safere.Pattern.compile(httpPattern);
    jdkHttp = java.util.regex.Pattern.compile(httpPattern);
    re2jHttp = com.google.re2j.Pattern.compile(httpPattern);
    re2ffmHttp = dev.eaftan.safere.re2ffm.RE2FfmPattern.compile(httpPattern);
  }

  // ===== Full HTTP request =====

  @Benchmark
  public boolean httpFull_safere() {
    dev.eaftan.safere.Matcher m = safeHttp.matcher(fullRequest);
    return m.find() && m.group(1) != null;
  }

  @Benchmark
  public boolean httpFull_jdk() {
    java.util.regex.Matcher m = jdkHttp.matcher(fullRequest);
    return m.find() && m.group(1) != null;
  }

  @Benchmark
  public boolean httpFull_re2j() {
    com.google.re2j.Matcher m = re2jHttp.matcher(fullRequest);
    return m.find() && m.group(1) != null;
  }

  @Benchmark
  public boolean httpFull_re2ffm() {
    dev.eaftan.safere.re2ffm.RE2FfmMatcher m = re2ffmHttp.matcher(fullRequest);
    return m.find() && m.group(1) != null;
  }

  // ===== Small HTTP request =====

  @Benchmark
  public boolean httpSmall_safere() {
    dev.eaftan.safere.Matcher m = safeHttp.matcher(smallRequest);
    return m.find() && m.group(1) != null;
  }

  @Benchmark
  public boolean httpSmall_jdk() {
    java.util.regex.Matcher m = jdkHttp.matcher(smallRequest);
    return m.find() && m.group(1) != null;
  }

  @Benchmark
  public boolean httpSmall_re2j() {
    com.google.re2j.Matcher m = re2jHttp.matcher(smallRequest);
    return m.find() && m.group(1) != null;
  }

  @Benchmark
  public boolean httpSmall_re2ffm() {
    dev.eaftan.safere.re2ffm.RE2FfmMatcher m = re2ffmHttp.matcher(smallRequest);
    return m.find() && m.group(1) != null;
  }

  // ===== URL extraction (find the path) =====

  @Benchmark
  public String httpExtract_safere() {
    dev.eaftan.safere.Matcher m = safeHttp.matcher(fullRequest);
    m.find();
    return m.group(1);
  }

  @Benchmark
  public String httpExtract_jdk() {
    java.util.regex.Matcher m = jdkHttp.matcher(fullRequest);
    m.find();
    return m.group(1);
  }

  @Benchmark
  public String httpExtract_re2j() {
    com.google.re2j.Matcher m = re2jHttp.matcher(fullRequest);
    m.find();
    return m.group(1);
  }

  @Benchmark
  public String httpExtract_re2ffm() {
    dev.eaftan.safere.re2ffm.RE2FfmMatcher m = re2ffmHttp.matcher(fullRequest);
    m.find();
    return m.group(1);
  }
}
