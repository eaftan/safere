// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.ArrayList;
import java.util.List;
import org.openjdk.jmh.infra.Blackhole;

/** Prepared execution state for one supported cross-engine benchmark trial. */
final class CrossEngineTrialRunner implements AutoCloseable {

  private final List<RegexEngineVariant.CompiledRegex> patterns;
  private final BenchmarkOperation.BenchmarkTask task;

  private CrossEngineTrialRunner(
      List<RegexEngineVariant.CompiledRegex> patterns, BenchmarkOperation.BenchmarkTask task) {
    this.patterns = patterns;
    this.task = task;
  }

  static CrossEngineTrialRunner prepare(
      String trialId, CrossEngineWorkload.TimingGroup expectedTimingGroup) {
    BenchmarkData data = BenchmarkData.get();
    CrossEngineBenchmarkPlan.Trial trial = CrossEngineBenchmarkPlan.load().resolve(trialId);
    return prepare(data, trial, expectedTimingGroup, true);
  }

  static CrossEngineTrialRunner prepareColdStart(
      String trialId, CrossEngineWorkload.TimingGroup expectedTimingGroup) {
    BenchmarkData data = BenchmarkData.get();
    CrossEngineBenchmarkPlan.Trial trial = CrossEngineBenchmarkPlan.load().resolve(trialId);
    return prepareColdStart(data, trial, expectedTimingGroup);
  }

  static CrossEngineTrialRunner prepareColdStart(
      BenchmarkData data,
      CrossEngineBenchmarkPlan.Trial trial,
      CrossEngineWorkload.TimingGroup expectedTimingGroup) {
    if (trial.workload().operation() != BenchmarkOperation.COMPILE) {
      throw new IllegalArgumentException(
          trial.id() + " cold-start execution requires the compile operation");
    }
    return prepare(data, trial, expectedTimingGroup, false);
  }

  private static CrossEngineTrialRunner prepare(
      BenchmarkData data,
      CrossEngineBenchmarkPlan.Trial trial,
      CrossEngineWorkload.TimingGroup expectedTimingGroup,
      boolean validateBeforeMeasurement) {
    String trialId = trial.id();
    CrossEngineWorkload workload = trial.workload();
    if (workload.timingGroup() != expectedTimingGroup) {
      throw new IllegalArgumentException(
          trialId + " belongs to " + workload.timingGroup() + ", not " + expectedTimingGroup);
    }

    List<RegexEngineVariant.RegexInput> inputs =
        trial.variant().prepareInputs(data, workload.inputKeys());
    RegexEngineVariant.PreparedReplacement replacement =
        trial.variant().prepareReplacement(workload.replacement());
    List<String> patternSources = workload.patterns();
    List<RegexEngineVariant.CompiledRegex> patterns = new ArrayList<>();
    try {
      if (workload.operation() != BenchmarkOperation.COMPILE) {
        for (String pattern : patternSources) {
          patterns.add(trial.variant().compile(pattern));
        }
      }
      if (validateBeforeMeasurement) {
        Object actual =
            workload
                .operation()
                .execute(
                    trial.variant(),
                    patternSources,
                    patterns,
                    inputs,
                    workload.groups(),
                    replacement,
                    workload.limit(),
                    workload.lifecycle(),
                    workload.flagSet(),
                    workload.seed(),
                    workload.count());
        trial.validate(actual);
      }
      BenchmarkOperation.BenchmarkTask task =
          workload
              .operation()
              .bind(
                  trial.variant(),
                  patternSources,
                  patterns,
                  inputs,
                  workload.groups(),
                  replacement,
                  workload.limit(),
                  workload.lifecycle(),
                  workload.flagSet(),
                  workload.seed(),
                  workload.count());
      return new CrossEngineTrialRunner(List.copyOf(patterns), task);
    } catch (RuntimeException | Error exception) {
      close(patterns);
      throw exception;
    }
  }

  void run(Blackhole blackhole) {
    task.run(blackhole);
  }

  @Override
  public void close() {
    close(patterns);
  }

  private static void close(List<RegexEngineVariant.CompiledRegex> patterns) {
    for (RegexEngineVariant.CompiledRegex pattern : patterns.reversed()) {
      pattern.close();
    }
  }
}
