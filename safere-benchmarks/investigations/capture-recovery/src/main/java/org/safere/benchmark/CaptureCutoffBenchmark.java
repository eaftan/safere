// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import com.google.gson.JsonObject;
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

/** Warm capture-demand cutoff study, selected explicitly through the investigation wrapper. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class CaptureCutoffBenchmark {
  /** Corpus workload and native representation. */
  @Param({})
  public String cutoffTrial;

  /** Public matching operation. The corpus patterns explicitly anchor find at the start. */
  @Param({"matches", "lookingAt", "find"})
  public String operation;

  /** Independent hit and conditional capture-read schedule, owned by the corpus. */
  @Param({"text", "mixed", "rare", "reject", "boolean"})
  public String profile;

  private CaptureCutoffTask task;

  /** Checks a separate SafeRE task against JDK before preparing the measurement task. */
  @Setup
  public void setup() {
    CrossEngineBenchmarkPlan.Trial trial = CrossEngineBenchmarkPlan.load().resolve(cutoffTrial);
    CrossEngineWorkload workload = trial.workload();
    JsonObject config = BenchmarkData.get().investigationConfiguration();
    JsonObject schedule = config.getAsJsonObject("profiles").getAsJsonObject(profile);
    if (schedule == null || !List.of("matches", "lookingAt", "find").contains(operation)) {
      throw new IllegalArgumentException("Unknown cutoff operation or profile");
    }
    int documents = config.get("documentsPerInvocation").getAsInt();
    int hitEvery = schedule.get("hitEvery").getAsInt();
    int readEvery = schedule.get("readEvery").getAsInt();
    try (CaptureCutoffTask oracle =
            prepare(RegexEngineVariant.JDK_STRING, workload, documents, hitEvery, readEvery);
        CaptureCutoffTask candidate =
            prepare(trial.variant(), workload, documents, hitEvery, readEvery)) {
      for (int round = 0; round < 4; round++) {
        if (oracle.run() != candidate.run()) {
          throw new IllegalStateException("Cutoff protocol differs from JDK: " + cutoffTrial);
        }
      }
    }
    task = prepare(trial.variant(), workload, documents, hitEvery, readEvery);
  }

  private CaptureCutoffTask prepare(
      RegexEngineVariant variant,
      CrossEngineWorkload workload,
      int documents,
      int hitEvery,
      int readEvery) {
    return new CaptureCutoffTask(
        variant,
        workload.patterns().getFirst(),
        variant.prepareInputs(BenchmarkData.get(), workload.inputKeys()),
        operation,
        documents,
        hitEvery,
        readEvery);
  }

  /** Runs the declared document batch; input construction and encoding are outside timing. */
  @Benchmark
  public long run() {
    return task.run();
  }

  /** Releases the compiled pattern after the trial. */
  @TearDown
  public void tearDown() {
    if (task != null) {
      task.close();
    }
  }
}
