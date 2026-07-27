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

/** Generic single-shot entry point for fresh-process cold-start declarations. */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class CrossEngineColdStartBenchmark {

  /** Planned workload and execution-variant ID supplied by {@code run-java-benchmarks.sh}. */
  @Param({})
  public String crossEngineColdStartTrial;

  private CrossEngineTrialRunner runner;

  /** Resolves the declaration without performing the measured compile operation. */
  @Setup
  public void setup() {
    runner =
        CrossEngineTrialRunner.prepareColdStart(
            crossEngineColdStartTrial, CrossEngineWorkload.TimingGroup.MILLISECONDS);
  }

  /** Releases resources retained by the selected execution variant. */
  @TearDown
  public void tearDown() {
    runner.close();
  }

  /** Executes one declared cold-start operation. */
  @Benchmark
  public void run(Blackhole blackhole) {
    runner.run(blackhole);
  }
}
