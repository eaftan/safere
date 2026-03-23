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
  private static final String TEXT_1 = "650-253-0001";
  private static final String PAT_1 = "([0-9]+)-[0-9]+-[0-9]+";

  // 3 capture groups: phone number (same as RE2's Parse_Digits).
  private dev.eaftan.safere.Pattern safe3;
  private java.util.regex.Pattern jdk3;
  private static final String TEXT_3 = "650-253-0001";
  private static final String PAT_3 = "([0-9]+)-([0-9]+)-([0-9]+)";

  // 10 capture groups: key=value pairs.
  private dev.eaftan.safere.Pattern safe10;
  private java.util.regex.Pattern jdk10;
  private static final String TEXT_10 = "a=1;b=2;c=3;d=4;e=5";
  private static final String PAT_10 =
      "(\\w+)=(\\w+);(\\w+)=(\\w+);(\\w+)=(\\w+);(\\w+)=(\\w+);(\\w+)=(\\w+)";

  // 0 capture groups (baseline): same text, no groups.
  private dev.eaftan.safere.Pattern safe0;
  private java.util.regex.Pattern jdk0;
  private static final String PAT_0 = "[0-9]+-[0-9]+-[0-9]+";

  @Setup
  public void setup() {
    safe0 = dev.eaftan.safere.Pattern.compile(PAT_0);
    jdk0 = java.util.regex.Pattern.compile(PAT_0);
    safe1 = dev.eaftan.safere.Pattern.compile(PAT_1);
    jdk1 = java.util.regex.Pattern.compile(PAT_1);
    safe3 = dev.eaftan.safere.Pattern.compile(PAT_3);
    jdk3 = java.util.regex.Pattern.compile(PAT_3);
    safe10 = dev.eaftan.safere.Pattern.compile(PAT_10);
    jdk10 = java.util.regex.Pattern.compile(PAT_10);
  }

  // ===== 0 groups (baseline) =====

  @Benchmark
  public boolean capture0_safere() {
    return safe0.matcher(TEXT_3).matches();
  }

  @Benchmark
  public boolean capture0_jdk() {
    return jdk0.matcher(TEXT_3).matches();
  }

  // ===== 1 group =====

  @Benchmark
  public String capture1_safere() {
    dev.eaftan.safere.Matcher m = safe1.matcher(TEXT_1);
    m.matches();
    return m.group(1);
  }

  @Benchmark
  public String capture1_jdk() {
    java.util.regex.Matcher m = jdk1.matcher(TEXT_1);
    m.matches();
    return m.group(1);
  }

  // ===== 3 groups =====

  @Benchmark
  public String capture3_safere() {
    dev.eaftan.safere.Matcher m = safe3.matcher(TEXT_3);
    m.matches();
    return m.group(1) + m.group(2) + m.group(3);
  }

  @Benchmark
  public String capture3_jdk() {
    java.util.regex.Matcher m = jdk3.matcher(TEXT_3);
    m.matches();
    return m.group(1) + m.group(2) + m.group(3);
  }

  // ===== 10 groups =====

  @Benchmark
  public String capture10_safere() {
    dev.eaftan.safere.Matcher m = safe10.matcher(TEXT_10);
    m.matches();
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= 10; i++) {
      sb.append(m.group(i));
    }
    return sb.toString();
  }

  @Benchmark
  public String capture10_jdk() {
    java.util.regex.Matcher m = jdk10.matcher(TEXT_10);
    m.matches();
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= 10; i++) {
      sb.append(m.group(i));
    }
    return sb.toString();
  }
}
