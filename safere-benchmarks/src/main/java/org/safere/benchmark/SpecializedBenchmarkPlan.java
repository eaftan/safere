// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Resolves data-declared workloads that use specialized measurement machinery. */
final class SpecializedBenchmarkPlan {
  private final Map<String, Trial> trials;
  private final List<DeclarativeBenchmarkPlan.Exclusion> exclusions;

  private SpecializedBenchmarkPlan(
      Map<String, Trial> trials, List<DeclarativeBenchmarkPlan.Exclusion> exclusions) {
    this.trials = Collections.unmodifiableMap(new LinkedHashMap<>(trials));
    this.exclusions = List.copyOf(exclusions);
  }

  static SpecializedBenchmarkPlan load() {
    DeclarativeBenchmarkPlan plan =
        DeclarativeBenchmarkPlan.parse(BenchmarkData.get().declarativePlan());
    List<DeclarativeBenchmarkPlan.EngineDeclaration> engines =
        Arrays.stream(RegexEngineVariant.values()).map(RegexEngineVariant::declaration).toList();
    DeclarativeBenchmarkPlan.ExpandedPlan expanded =
        plan.expand(engines, EnumSet.allOf(DeclarativeBenchmarkPlan.Operation.class));
    Map<String, Trial> trials = new LinkedHashMap<>();
    for (DeclarativeBenchmarkPlan.Trial trial : expanded.trials()) {
      if (!isSpecialized(trial.workload())) {
        continue;
      }
      Trial converted = new Trial(trial.workload(), RegexEngineVariant.fromId(trial.engine().id()));
      if (trials.put(converted.id(), converted) != null) {
        throw new IllegalArgumentException("Duplicate specialized trial ID: " + converted.id());
      }
    }
    var workloadIds =
        trials.values().stream().map(trial -> trial.workload().id()).collect(Collectors.toSet());
    List<DeclarativeBenchmarkPlan.Exclusion> exclusions =
        expanded.exclusions().stream()
            .filter(exclusion -> workloadIds.contains(exclusion.workloadId()))
            .toList();
    return new SpecializedBenchmarkPlan(trials, exclusions);
  }

  private static boolean isSpecialized(DeclarativeBenchmarkPlan.ExpandedWorkload workload) {
    return switch (workload.operation()) {
          case PATTERN_SET_MATCHES,
              UTF8_CAPTURE_BOUNDS,
              UTF8_DECODE_FIND,
              UTF8_REPLACEMENT,
              FIND_IN_WINDOW,
              MATCHER_CONSTRUCTION,
              ANALYZE_PATTERN,
              CACHED_ANALYSIS,
              COMPILE_AND_ANALYZE,
              DIAGNOSTICS_FIND ->
              true;
          default -> false;
        }
        || workload.measurement().mode() == DeclarativeBenchmarkPlan.MeasurementMode.RETAINED_MEMORY
        || workload.measurement().mode()
            == DeclarativeBenchmarkPlan.MeasurementMode.SUBPROCESS_MEMORY;
  }

  Trial resolve(String id) {
    Trial trial = trials.get(id);
    if (trial == null) {
      throw new IllegalArgumentException("Unknown specialized benchmark trial: " + id);
    }
    return trial;
  }

  List<Trial> patternSetTrials() {
    return trials.values().stream()
        .filter(
            trial ->
                trial.workload().operation()
                    == DeclarativeBenchmarkPlan.Operation.PATTERN_SET_MATCHES)
        .toList();
  }

  List<Trial> averageTimeTrials() {
    return trials.values().stream()
        .filter(
            trial ->
                trial.workload().measurement().mode()
                    == DeclarativeBenchmarkPlan.MeasurementMode.AVERAGE_TIME)
        .toList();
  }

  List<Trial> retainedMemoryTrials() {
    return trials.values().stream()
        .filter(
            trial ->
                trial.workload().measurement().mode()
                    == DeclarativeBenchmarkPlan.MeasurementMode.RETAINED_MEMORY)
        .toList();
  }

  List<DeclarativeBenchmarkPlan.Exclusion> exclusions() {
    return exclusions;
  }

  static void main(String[] args) {
    if (args.length != 1) {
      throw new IllegalArgumentException(
          "Usage: SpecializedBenchmarkPlan <average-time|pattern-set|retained-memory>");
    }
    SpecializedBenchmarkPlan plan = load();
    List<Trial> selected =
        switch (args[0]) {
          case "average-time" -> plan.averageTimeTrials();
          case "pattern-set" -> plan.patternSetTrials();
          case "retained-memory" -> plan.retainedMemoryTrials();
          default -> throw new IllegalArgumentException("Unknown specialized mode: " + args[0]);
        };
    if (selected.isEmpty()) {
      throw new IllegalStateException("No specialized trials for " + args[0]);
    }
    System.out.println(selected.stream().map(Trial::id).collect(Collectors.joining(",")));
  }

  record Trial(DeclarativeBenchmarkPlan.ExpandedWorkload workload, RegexEngineVariant variant) {
    Trial {
      Objects.requireNonNull(workload);
      Objects.requireNonNull(variant);
    }

    String id() {
      return workload.id() + "@" + variant.id();
    }
  }
}
