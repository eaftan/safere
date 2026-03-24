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

  private static final String HTTP_PATTERN = "^(?:GET|POST) +([^ ]+) HTTP";

  // Full HTTP request (same as RE2 C++ benchmark).
  private static final String FULL_REQUEST =
      "GET /asdfhjasdhfasdlfhasdflkjasdfkljasdhflaskdjhf"
          + "lkajsdhflkajshfklasjdhfklasjdhfklashdflka HTTP/1.1";

  // Small HTTP request.
  private static final String SMALL_REQUEST = "GET /abc HTTP/1.1";

  private dev.eaftan.safere.Pattern safeHttp;
  private java.util.regex.Pattern jdkHttp;
  private com.google.re2j.Pattern re2jHttp;

  @Setup
  public void setup() {
    safeHttp = dev.eaftan.safere.Pattern.compile(HTTP_PATTERN);
    jdkHttp = java.util.regex.Pattern.compile(HTTP_PATTERN);
    re2jHttp = com.google.re2j.Pattern.compile(HTTP_PATTERN);
  }

  // ===== Full HTTP request =====

  @Benchmark
  public boolean httpFull_safere() {
    dev.eaftan.safere.Matcher m = safeHttp.matcher(FULL_REQUEST);
    return m.find() && m.group(1) != null;
  }

  @Benchmark
  public boolean httpFull_jdk() {
    java.util.regex.Matcher m = jdkHttp.matcher(FULL_REQUEST);
    return m.find() && m.group(1) != null;
  }

  @Benchmark
  public boolean httpFull_re2j() {
    com.google.re2j.Matcher m = re2jHttp.matcher(FULL_REQUEST);
    return m.find() && m.group(1) != null;
  }

  // ===== Small HTTP request =====

  @Benchmark
  public boolean httpSmall_safere() {
    dev.eaftan.safere.Matcher m = safeHttp.matcher(SMALL_REQUEST);
    return m.find() && m.group(1) != null;
  }

  @Benchmark
  public boolean httpSmall_jdk() {
    java.util.regex.Matcher m = jdkHttp.matcher(SMALL_REQUEST);
    return m.find() && m.group(1) != null;
  }

  @Benchmark
  public boolean httpSmall_re2j() {
    com.google.re2j.Matcher m = re2jHttp.matcher(SMALL_REQUEST);
    return m.find() && m.group(1) != null;
  }

  // ===== URL extraction (find the path) =====

  @Benchmark
  public String httpExtract_safere() {
    dev.eaftan.safere.Matcher m = safeHttp.matcher(FULL_REQUEST);
    m.find();
    return m.group(1);
  }

  @Benchmark
  public String httpExtract_jdk() {
    java.util.regex.Matcher m = jdkHttp.matcher(FULL_REQUEST);
    m.find();
    return m.group(1);
  }

  @Benchmark
  public String httpExtract_re2j() {
    com.google.re2j.Matcher m = re2jHttp.matcher(FULL_REQUEST);
    m.find();
    return m.group(1);
  }
}
