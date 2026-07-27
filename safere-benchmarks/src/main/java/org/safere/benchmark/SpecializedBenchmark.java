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
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/** Generic JMH entry point for SafeRE-specific nanosecond workloads. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class SpecializedBenchmark {

  /** Planned specialized workload and execution-variant ID. */
  @Param({})
  public String specializedTrial;

  private SpecializedTrialRunner runner;

  /** Prepares the selected specialized operation from its declaration. */
  @Setup
  public void setup() {
    runner = SpecializedTrialRunner.prepare(specializedTrial);
  }

  /** Restores specialized global state after the trial. */
  @TearDown
  public void tearDown() {
    runner.close();
  }

  /** Executes the selected operation. */
  @Benchmark
  public void run(Blackhole blackhole) {
    runner.run(blackhole);
  }
}
