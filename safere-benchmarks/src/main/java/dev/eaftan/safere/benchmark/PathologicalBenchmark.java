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
 * Benchmark demonstrating the pathological case that causes backtracking engines to exhibit
 * exponential behavior: {@code a?{n}a{n}} matched against {@code a{n}}.
 *
 * <p>SafeRE (linear-time) should complete in O(n) regardless of n. The JDK backtracking engine
 * will exhibit O(2^n) behavior, becoming unmatchable above n≈25.
 *
 * <p><strong>WARNING:</strong> The JDK benchmark is only safe for small n (≤20). For larger values,
 * only the SafeRE benchmark should be run:
 *
 * <pre>{@code
 * mvn test-compile exec:java \
 *   -Dexec.mainClass=org.openjdk.jmh.Main \
 *   -Dexec.classpathScope=test \
 *   -Dexec.args="PathologicalBenchmark.pathological_safere -f 0"
 * }</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(0)
@State(Scope.Thread)
public class PathologicalBenchmark {

  @Param({"10", "15", "20", "25", "30", "50", "100"})
  private int n;

  private dev.eaftan.safere.Pattern safePattern;
  private com.google.re2j.Pattern re2jPattern;
  private dev.eaftan.safere.re2ffm.RE2FfmPattern re2ffmPattern;
  private String text;

  @Setup
  public void setup() {
    String regex = "a?".repeat(n) + "a".repeat(n);
    safePattern = dev.eaftan.safere.Pattern.compile(regex);
    re2jPattern = com.google.re2j.Pattern.compile(regex);
    re2ffmPattern = dev.eaftan.safere.re2ffm.RE2FfmPattern.compile(regex);
    text = "a".repeat(n);
  }

  /** SafeRE: should be linear in n. */
  @Benchmark
  public boolean pathological_safere() {
    return safePattern.matcher(text).matches();
  }

  /** RE2/J: should also be linear in n. */
  @Benchmark
  public boolean pathological_re2j() {
    return re2jPattern.matcher(text).matches();
  }

  /** RE2-FFM: should also be linear in n. */
  @Benchmark
  public boolean pathological_re2ffm() {
    return re2ffmPattern.matcher(text).matches();
  }
}
