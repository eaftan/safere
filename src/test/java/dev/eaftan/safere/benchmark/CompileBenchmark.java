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

  private static final String SIMPLE = "hello";
  private static final String MEDIUM = "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})";
  private static final String COMPLEX =
      "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}";
  private static final String ALTERNATION =
      "foo|bar|baz|qux|quux|corge|grault|garply|waldo|fred|plugh|xyzzy";

  // ===== Simple pattern =====

  @Benchmark
  public dev.eaftan.safere.Pattern compileSimple_safere() {
    return dev.eaftan.safere.Pattern.compile(SIMPLE);
  }

  @Benchmark
  public java.util.regex.Pattern compileSimple_jdk() {
    return java.util.regex.Pattern.compile(SIMPLE);
  }

  // ===== Medium pattern (date-time with captures) =====

  @Benchmark
  public dev.eaftan.safere.Pattern compileMedium_safere() {
    return dev.eaftan.safere.Pattern.compile(MEDIUM);
  }

  @Benchmark
  public java.util.regex.Pattern compileMedium_jdk() {
    return java.util.regex.Pattern.compile(MEDIUM);
  }

  // ===== Complex pattern (email) =====

  @Benchmark
  public dev.eaftan.safere.Pattern compileComplex_safere() {
    return dev.eaftan.safere.Pattern.compile(COMPLEX);
  }

  @Benchmark
  public java.util.regex.Pattern compileComplex_jdk() {
    return java.util.regex.Pattern.compile(COMPLEX);
  }

  // ===== Alternation pattern =====

  @Benchmark
  public dev.eaftan.safere.Pattern compileAlternation_safere() {
    return dev.eaftan.safere.Pattern.compile(ALTERNATION);
  }

  @Benchmark
  public java.util.regex.Pattern compileAlternation_jdk() {
    return java.util.regex.Pattern.compile(ALTERNATION);
  }
}
