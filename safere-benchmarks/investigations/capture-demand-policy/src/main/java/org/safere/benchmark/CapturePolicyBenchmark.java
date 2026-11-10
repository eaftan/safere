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

/** Explicitly selected capture-history investigation, excluded from the release corpus. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class CapturePolicyBenchmark {
  /** Materialized workload and String/UTF-8 representation. */
  @Param({})
  public String captureTrial;

  /** Consumption protocol declared in the investigation configuration. */
  @Param({})
  public String consumption;

  private CapturePolicyProtocol.Task task;

  /** Uses the declared cursor lifetime for both the oracle and measurement. */
  @Setup
  public void setup() {
    CapturePolicyCase selected = CapturePolicyCase.load(captureTrial, consumption);
    selected.validate();
    task = selected.task();
  }

  /** Runs one complete capture/Boolean/capture cycle and consumes its checksum. */
  @Benchmark
  public int run() {
    return task.run();
  }
}
