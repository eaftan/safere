// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.List;
import org.openjdk.jmh.infra.Blackhole;

/** Prepared execution state for one supported cross-engine benchmark trial. */
final class CrossEngineTrialRunner implements AutoCloseable {

  private final RegexEngineVariant.CompiledRegex pattern;
  private final BenchmarkOperation.BenchmarkTask task;

  private CrossEngineTrialRunner(
      RegexEngineVariant.CompiledRegex pattern, BenchmarkOperation.BenchmarkTask task) {
    this.pattern = pattern;
    this.task = task;
  }

  static CrossEngineTrialRunner prepare(
      String trialId, CrossEngineWorkload.TimingGroup expectedTimingGroup) {
    BenchmarkData data = BenchmarkData.get();
    CrossEngineBenchmarkPlan.Trial trial = CrossEngineBenchmarkPlan.load().resolve(trialId);
    CrossEngineWorkload workload = trial.workload();
    if (workload.timingGroup() != expectedTimingGroup) {
      throw new IllegalArgumentException(
          trialId + " belongs to " + workload.timingGroup() + ", not " + expectedTimingGroup);
    }

    List<RegexEngineVariant.RegexInput> inputs =
        trial.variant().prepareInputs(data, workload.inputKeys());
    RegexEngineVariant.CompiledRegex pattern = trial.variant().compile(workload.pattern());
    try {
      Object actual =
          workload.operation().execute(pattern, inputs, workload.groups(), workload.replacement());
      trial.validate(actual);
      BenchmarkOperation.BenchmarkTask task =
          pattern.bind(workload.operation(), inputs, workload.groups(), workload.replacement());
      return new CrossEngineTrialRunner(pattern, task);
    } catch (RuntimeException | Error exception) {
      pattern.close();
      throw exception;
    }
  }

  void run(Blackhole blackhole) {
    task.run(blackhole);
  }

  @Override
  public void close() {
    pattern.close();
  }
}
