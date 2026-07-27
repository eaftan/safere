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

/** Generic JMH entry point for declarations that require in-process no-fork execution. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class CrossEngineNoForkBenchmark {

  /** Planned workload and execution-variant ID supplied by {@code run-java-benchmarks.sh}. */
  @Param({})
  public String crossEngineNoForkTrial;

  private CrossEngineTrialRunner runner;

  /** Prepares the selected engine, operation, and materialized input. */
  @Setup
  public void setup() {
    runner =
        CrossEngineTrialRunner.prepare(
            crossEngineNoForkTrial, CrossEngineWorkload.TimingGroup.MICROSECONDS);
  }

  /** Releases resources retained by the selected execution variant. */
  @TearDown
  public void tearDown() {
    runner.close();
  }

  /** Executes the pre-bound operation and consumes its result. */
  @Benchmark
  public void run(Blackhole blackhole) {
    runner.run(blackhole);
  }
}
