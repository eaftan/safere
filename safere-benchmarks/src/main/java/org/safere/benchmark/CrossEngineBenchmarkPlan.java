// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Expands and validates the declarative ordinary cross-engine workload matrix. */
final class CrossEngineBenchmarkPlan {
  private static final EnumSet<DeclarativeBenchmarkPlan.Operation> CROSS_ENGINE_OPERATIONS =
      EnumSet.of(
          DeclarativeBenchmarkPlan.Operation.MATCHES,
          DeclarativeBenchmarkPlan.Operation.FIND,
          DeclarativeBenchmarkPlan.Operation.LOOKING_AT,
          DeclarativeBenchmarkPlan.Operation.FIND_ALL_COUNT,
          DeclarativeBenchmarkPlan.Operation.MATCHES_CORPUS,
          DeclarativeBenchmarkPlan.Operation.MATCHES_GROUP_LENGTH_SUM,
          DeclarativeBenchmarkPlan.Operation.FIND_ALL_LENGTH_SUM,
          DeclarativeBenchmarkPlan.Operation.FIND_ALL_GROUP_LENGTH_SUM,
          DeclarativeBenchmarkPlan.Operation.CAPTURE_GROUPS,
          DeclarativeBenchmarkPlan.Operation.REPLACE_FIRST,
          DeclarativeBenchmarkPlan.Operation.REPLACE_ALL,
          DeclarativeBenchmarkPlan.Operation.REPLACE_ALL_LENGTH_SUM,
          DeclarativeBenchmarkPlan.Operation.MANUAL_REPLACE_ALL,
          DeclarativeBenchmarkPlan.Operation.SPLIT_LENGTH_SUM,
          DeclarativeBenchmarkPlan.Operation.COMPILE,
          DeclarativeBenchmarkPlan.Operation.COMPILE_AND_FIND,
          DeclarativeBenchmarkPlan.Operation.FIND_ROTATING_UTF16,
          DeclarativeBenchmarkPlan.Operation.COMPILE_AND_FIND_ROTATING_UTF16,
          DeclarativeBenchmarkPlan.Operation.MATCHER_RESET_FIND,
          DeclarativeBenchmarkPlan.Operation.MATCHER_REGION_FIND,
          DeclarativeBenchmarkPlan.Operation.FIND_GROUP_PRESENT,
          DeclarativeBenchmarkPlan.Operation.FIND_GROUP);
  private final Map<String, CrossEngineWorkload> workloads;
  private final Map<String, Trial> trials;
  private final List<MaterializedExecutionPlan.Entry> exclusions;

  private CrossEngineBenchmarkPlan(
      Map<String, CrossEngineWorkload> workloads,
      Map<String, Trial> trials,
      List<MaterializedExecutionPlan.Entry> exclusions) {
    this.workloads = Collections.unmodifiableMap(new LinkedHashMap<>(workloads));
    this.trials = Collections.unmodifiableMap(new LinkedHashMap<>(trials));
    this.exclusions = List.copyOf(exclusions);
  }

  static CrossEngineBenchmarkPlan load() {
    return fromMaterialized(MaterializedExecutionPlan.load().entriesForRunner("java"));
  }

  static CrossEngineBenchmarkPlan fromMaterialized(List<MaterializedExecutionPlan.Entry> entries) {
    Map<String, CrossEngineWorkload> workloads = new LinkedHashMap<>();
    Map<String, Trial> trials = new LinkedHashMap<>();
    List<MaterializedExecutionPlan.Entry> exclusions = new ArrayList<>();
    for (MaterializedExecutionPlan.Entry entry : entries) {
      if (!CROSS_ENGINE_OPERATIONS.contains(entry.operation())
          || !entry.measurement().timingUnit().isTime()) {
        continue;
      }
      if (!entry.runnable()) {
        exclusions.add(entry);
        continue;
      }
      CrossEngineWorkload workload = convert(entry.workload());
      workloads.putIfAbsent(entry.workloadId(), workload);
      RegexEngineVariant variant = RegexEngineVariant.fromId(entry.engineId());
      Trial converted = new Trial(workload, variant);
      if (trials.put(converted.id(), converted) != null) {
        throw new IllegalArgumentException("Duplicate cross-engine trial ID: " + converted.id());
      }
    }
    return new CrossEngineBenchmarkPlan(workloads, trials, exclusions);
  }

  List<Trial> trials(CrossEngineWorkload.TimingGroup timingGroup) {
    return trials.values().stream()
        .filter(trial -> trial.workload().timingGroup() == timingGroup)
        .toList();
  }

  List<Trial> trials(
      CrossEngineWorkload.TimingGroup timingGroup,
      DeclarativeBenchmarkPlan.MeasurementMode mode,
      boolean noFork) {
    return trials(timingGroup).stream()
        .filter(trial -> trial.workload().measurement().mode() == mode)
        .filter(
            trial ->
                trial
                        .workload()
                        .measurement()
                        .constraints()
                        .contains(DeclarativeBenchmarkPlan.ExecutionConstraint.NO_FORK)
                    == noFork)
        .toList();
  }

  List<CrossEngineWorkload> workloads() {
    return List.copyOf(workloads.values());
  }

  List<MaterializedExecutionPlan.Entry> exclusions() {
    return exclusions;
  }

  Trial resolve(String trialId) {
    int separator = trialId.lastIndexOf('@');
    if (separator <= 0 || separator == trialId.length() - 1) {
      throw new IllegalArgumentException(
          "Cross-engine trial must be <workload-id>@<variant-id>: " + trialId);
    }
    Trial trial = trials.get(trialId);
    if (trial != null) {
      return trial;
    }
    String workloadId = trialId.substring(0, separator);
    String variantId = trialId.substring(separator + 1);
    CrossEngineWorkload workload = workloads.get(workloadId);
    if (workload == null) {
      throw new IllegalArgumentException("Unknown cross-engine workload ID: " + workloadId);
    }
    RegexEngineVariant.fromId(variantId);
    MaterializedExecutionPlan.Entry exclusion =
        exclusions.stream()
            .filter(
                candidate ->
                    candidate.workloadId().equals(workloadId)
                        && candidate.engineId().equals(variantId))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Unaccounted cross-engine combination: " + workloadId + "@" + variantId));
    throw new IllegalArgumentException(
        "Unsupported cross-engine combination: "
            + trialId
            + " uses "
            + workload.operation()
            + "; "
            + exclusion.exclusion().kind()
            + ": "
            + exclusion.exclusion().reason());
  }

  public static void main(String[] args) {
    if (args.length < 1) {
      throw new IllegalArgumentException(
          "Usage: CrossEngineBenchmarkPlan "
              + "<nanoseconds|microseconds|no-fork-microseconds|cold-start> "
              + "[workload-prefix ...]");
    }
    Query query =
        switch (args[0]) {
          case "nanoseconds" -> new Query(CrossEngineWorkload.TimingGroup.NANOSECONDS, null, false);
          case "microseconds" ->
              new Query(CrossEngineWorkload.TimingGroup.MICROSECONDS, null, false);
          case "no-fork-microseconds" ->
              new Query(
                  CrossEngineWorkload.TimingGroup.MICROSECONDS,
                  DeclarativeBenchmarkPlan.MeasurementMode.AVERAGE_TIME,
                  true);
          case "cold-start" ->
              new Query(
                  CrossEngineWorkload.TimingGroup.MILLISECONDS,
                  DeclarativeBenchmarkPlan.MeasurementMode.SINGLE_SHOT_COLD_START,
                  false);
          default ->
              throw new IllegalArgumentException("Unknown cross-engine timing group: " + args[0]);
        };
    CrossEngineBenchmarkPlan plan = load();
    String trialIds =
        plan.trials(query.timingGroup()).stream()
            .filter(
                trial ->
                    query.mode() == null || trial.workload().measurement().mode() == query.mode())
            .filter(
                trial ->
                    trial
                            .workload()
                            .measurement()
                            .constraints()
                            .contains(DeclarativeBenchmarkPlan.ExecutionConstraint.NO_FORK)
                        == query.noFork())
            .filter(
                trial ->
                    args.length == 1
                        || Arrays.stream(args, 1, args.length)
                            .anyMatch(prefix -> trial.workload().id().startsWith(prefix)))
            .map(Trial::id)
            .collect(Collectors.joining(","));
    if (trialIds.isEmpty()) {
      throw new IllegalStateException("No cross-engine trials for " + args[0]);
    }
    System.out.println(trialIds);
  }

  private record Query(
      CrossEngineWorkload.TimingGroup timingGroup,
      DeclarativeBenchmarkPlan.MeasurementMode mode,
      boolean noFork) {}

  private static CrossEngineWorkload convert(DeclarativeBenchmarkPlan.ExpandedWorkload workload) {
    int[] groups = groups(workload.arguments());
    String replacement = stringArgument(workload.arguments(), "replacement");
    int limit = integerArgument(workload.arguments(), "limit", 0);
    CrossEngineWorkload.TimingGroup timingGroup =
        switch (workload.measurement().timingUnit()) {
          case NANOSECONDS -> CrossEngineWorkload.TimingGroup.NANOSECONDS;
          case MICROSECONDS -> CrossEngineWorkload.TimingGroup.MICROSECONDS;
          case MILLISECONDS -> CrossEngineWorkload.TimingGroup.MILLISECONDS;
          default ->
              throw new IllegalArgumentException(
                  workload.id() + " is not a time-based cross-engine workload");
        };
    return new CrossEngineWorkload(
        workload.id(),
        BenchmarkOperation.fromDeclarative(workload.operation()),
        workload.patterns(),
        workload.inputIds(),
        groups,
        replacement,
        expectedValue(workload.expected()),
        limit,
        workload.lifecycle(),
        stringArgument(workload.arguments(), "flagSet"),
        integerArgument(workload.arguments(), "seed", 0),
        integerArgument(workload.arguments(), "count", 0),
        workload.measurement(),
        timingGroup);
  }

  private static int[] groups(Map<String, DeclarativeBenchmarkPlan.RecipeValue> arguments) {
    DeclarativeBenchmarkPlan.RecipeValue groups = arguments.get("groups");
    if (groups != null) {
      return ((DeclarativeBenchmarkPlan.RecipeIntegerList) groups)
          .values().stream().mapToInt(Integer::intValue).toArray();
    }
    DeclarativeBenchmarkPlan.RecipeValue group = arguments.get("group");
    if (group != null) {
      return new int[] {((DeclarativeBenchmarkPlan.RecipeInteger) group).value()};
    }
    return new int[0];
  }

  private static String stringArgument(
      Map<String, DeclarativeBenchmarkPlan.RecipeValue> arguments, String name) {
    DeclarativeBenchmarkPlan.RecipeValue value = arguments.get(name);
    return value == null ? null : ((DeclarativeBenchmarkPlan.RecipeString) value).value();
  }

  private static int integerArgument(
      Map<String, DeclarativeBenchmarkPlan.RecipeValue> arguments, String name, int defaultValue) {
    DeclarativeBenchmarkPlan.RecipeValue value = arguments.get(name);
    return value == null ? defaultValue : ((DeclarativeBenchmarkPlan.RecipeInteger) value).value();
  }

  private static Object expectedValue(DeclarativeBenchmarkPlan.ExpectedResult expected) {
    if (expected == null) {
      return null;
    }
    JsonElement value = expected.value();
    return switch (expected.type()) {
      case BOOLEAN -> value.getAsBoolean();
      case INTEGER -> value.getAsInt();
      case STRING -> value.getAsString();
      case STRING_LIST -> {
        List<String> values = new ArrayList<>();
        value.getAsJsonArray().forEach(element -> values.add(element.getAsString()));
        yield List.copyOf(values);
      }
    };
  }

  record Trial(CrossEngineWorkload workload, RegexEngineVariant variant) {
    Trial {
      Objects.requireNonNull(workload);
      Objects.requireNonNull(variant);
    }

    String id() {
      return workload.id() + "@" + variant.id();
    }

    void validate(Object actual) {
      if (workload.expected() != null && !Objects.equals(workload.expected(), actual)) {
        throw new IllegalArgumentException(
            workload.id()
                + " "
                + variant.id()
                + " expected "
                + workload.expected()
                + " but was "
                + actual);
      }
    }
  }
}
