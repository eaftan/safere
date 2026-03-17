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
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Comparison of SafeRE vs JDK on the pathological pattern {@code a?{n}a{n}} for small n values
 * where the JDK backtracking engine is still feasible (≤20).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(0)
@State(Scope.Thread)
public class PathologicalComparisonBenchmark {

  @Param({"10", "15", "20"})
  private int n;

  private dev.eaftan.safere.Pattern safePattern;
  private java.util.regex.Pattern jdkPattern;
  private String text;

  @Setup
  public void setup() {
    String regex = "a?".repeat(n) + "a".repeat(n);
    safePattern = dev.eaftan.safere.Pattern.compile(regex);
    jdkPattern = java.util.regex.Pattern.compile(regex);
    text = "a".repeat(n);
  }

  @Benchmark
  public boolean pathological_safere() {
    return safePattern.matcher(text).matches();
  }

  @Benchmark
  public boolean pathological_jdk() {
    return jdkPattern.matcher(text).matches();
  }
}
