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

/** Measures explicit cached static pattern analysis independently of compilation and matching. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class PatternAnalysisBenchmark {
  private static final String REGEX = "^(?:(a)?)*$|(?i)\\b(error|warning|timeout)\\b";

  private org.safere.Pattern pattern;

  /** Compiles and analyzes the pattern before measurement. */
  @Setup
  public void setup() {
    pattern = org.safere.Pattern.compile(REGEX);
    pattern.analysis();
  }

  /** Measures retrieval of the cached immutable analysis. */
  @Benchmark
  public org.safere.PatternAnalysis cachedAnalysis() {
    return pattern.analysis();
  }

  /** Measures compilation without requesting static diagnostics analysis. */
  @Benchmark
  public org.safere.Pattern compileOnly() {
    return org.safere.Pattern.compile(REGEX);
  }

  /** Measures compilation followed by the first explicit static analysis request. */
  @Benchmark
  public org.safere.PatternAnalysis compileAndAnalyze() {
    return org.safere.Pattern.compile(REGEX).analysis();
  }
}
