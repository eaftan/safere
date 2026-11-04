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
import org.openjdk.jmh.infra.ThreadParams;

/** Private cursors and alternating consumption roles share one compiled Pattern. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class CapturePolicySharedBenchmark {
  /** Materialized workload and String/UTF-8 representation. */
  @Param({})
  public String captureTrial;

  /** Consumption protocol for reader threads. */
  @Param({})
  public String consumption;

  private ThreadLocal<Tasks> tasks;

  /** Creates independent cursors for each thread using the same Pattern. */
  @Setup
  public void setup() {
    CapturePolicyCase selected = CapturePolicyCase.load(captureTrial, consumption);
    tasks = ThreadLocal.withInitial(() -> new Tasks(selected.task(), selected.booleanTask()));
  }

  /** Uses an even thread count to balance text readers and Boolean consumers. */
  @Benchmark
  public int run(ThreadParams thread) {
    Tasks local = tasks.get();
    return ((thread.getThreadIndex() & 1) == 0 ? local.reader() : local.booleanConsumer()).run();
  }

  private record Tasks(
      CapturePolicyProtocol.Task reader, CapturePolicyProtocol.Task booleanConsumer) {}
}
