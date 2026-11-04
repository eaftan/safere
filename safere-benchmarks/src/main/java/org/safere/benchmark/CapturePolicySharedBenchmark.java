// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.List;
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
import org.openjdk.jmh.infra.ThreadParams;

/** Concurrent readers and boolean consumers sharing one compiled pattern. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class CapturePolicySharedBenchmark {
  /** Declarative findUsage trial; run with an even number of JMH threads. */
  @Param({})
  public String crossEngineTrial;

  private RegexEngineVariant.CompiledRegex pattern;
  private ThreadLocal<Tasks> tasks;

  /** Shares the pattern while keeping mutable cursors private to each executing thread. */
  @Setup
  public void setup() {
    CrossEngineBenchmarkPlan.Trial trial =
        CrossEngineBenchmarkPlan.load().resolve(crossEngineTrial);
    CrossEngineWorkload workload = trial.workload();
    if (workload.operation() != BenchmarkOperation.FIND_USAGE) {
      throw new IllegalArgumentException("Shared policy trials require findUsage");
    }
    pattern = trial.variant().compile(workload.patterns().getFirst());
    List<RegexEngineVariant.RegexInput> inputs =
        trial.variant().prepareInputs(BenchmarkData.get(), workload.inputKeys());
    tasks =
        ThreadLocal.withInitial(
            () -> new Tasks(bind(trial, inputs, workload.mode()), bind(trial, inputs, "boolean")));
  }

  private BenchmarkOperation.BenchmarkTask bind(
      CrossEngineBenchmarkPlan.Trial trial,
      List<RegexEngineVariant.RegexInput> inputs,
      String mode) {
    CrossEngineWorkload w = trial.workload();
    return w.operation()
        .bind(
            trial.variant(),
            w.patterns(),
            List.of(pattern),
            inputs,
            w.groups(),
            null,
            w.limit(),
            w.lifecycle(),
            w.flagSet(),
            w.seed(),
            w.count(),
            mode);
  }

  /** Alternates thread roles so boolean consumers cannot overwrite other matchers' history. */
  @Benchmark
  public void run(Blackhole blackhole, ThreadParams thread) {
    Tasks local = tasks.get();
    ((thread.getThreadIndex() & 1) == 0 ? local.reader() : local.booleanConsumer()).run(blackhole);
  }

  /** Releases the shared compiled pattern after all worker threads finish. */
  @TearDown
  public void tearDown() {
    pattern.close();
  }

  private record Tasks(
      BenchmarkOperation.BenchmarkTask reader, BenchmarkOperation.BenchmarkTask booleanConsumer) {}
}
