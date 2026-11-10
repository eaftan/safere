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

/** Four workers share a Pattern while retaining private cursors and adaptation state. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class AdaptiveCaptureSharedBenchmark {
  /** Explicit investigation-owned scenario. */
  @Param({})
  public String scenario;

  /** Native String or UTF-8 execution. */
  @Param({"string", "utf8"})
  public String representation;

  /** Experimental library policy selected before Matcher initialization in each fresh fork. */
  @Param({})
  public String policy;

  /** Reproducible Matcher-local exploration seed. */
  @Param({"1"})
  public long seed;

  private ThreadLocal<Tasks> tasks;

  /** Prepares one shared Pattern and independently validates both consumption roles. */
  @Setup
  public void setup() {
    AdaptiveCaptureBenchmark.configure(policy, seed);
    AdaptiveCaptureCase selected = AdaptiveCaptureCase.load(scenario, representation);
    selected.validate();
    AdaptiveCaptureBenchmark.verify(policy, seed);
    AdaptiveCaptureCase.Shared shared = selected.shared();
    tasks = ThreadLocal.withInitial(() -> new Tasks(shared.task(false), shared.task(true)));
  }

  /** Even workers read captures; odd workers consume only match existence. */
  @Benchmark
  public long run(ThreadParams thread) {
    Tasks local = tasks.get();
    return ((thread.getThreadIndex() & 1) == 0 ? local.reader() : local.booleanConsumer())
        .run(null);
  }

  private record Tasks(AdaptiveCaptureCase.Task reader, AdaptiveCaptureCase.Task booleanConsumer) {}
}
