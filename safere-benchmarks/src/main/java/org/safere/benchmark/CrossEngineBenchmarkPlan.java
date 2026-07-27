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
  private static final EnumSet<DeclarativeBenchmarkPlan.Operation> IMPLEMENTED_OPERATIONS =
      EnumSet.of(
          DeclarativeBenchmarkPlan.Operation.MATCHES,
          DeclarativeBenchmarkPlan.Operation.FIND,
          DeclarativeBenchmarkPlan.Operation.FIND_ALL_COUNT,
          DeclarativeBenchmarkPlan.Operation.MATCHES_CORPUS,
          DeclarativeBenchmarkPlan.Operation.MATCHES_GROUP_LENGTH_SUM,
          DeclarativeBenchmarkPlan.Operation.FIND_ALL_LENGTH_SUM,
          DeclarativeBenchmarkPlan.Operation.FIND_ALL_GROUP_LENGTH_SUM,
          DeclarativeBenchmarkPlan.Operation.REPLACE_ALL,
          DeclarativeBenchmarkPlan.Operation.FIND_GROUP_PRESENT,
          DeclarativeBenchmarkPlan.Operation.FIND_GROUP);

  private final Map<String, CrossEngineWorkload> workloads;
  private final Map<String, Trial> trials;
  private final List<DeclarativeBenchmarkPlan.Exclusion> exclusions;

  private CrossEngineBenchmarkPlan(
      Map<String, CrossEngineWorkload> workloads,
      Map<String, Trial> trials,
      List<DeclarativeBenchmarkPlan.Exclusion> exclusions) {
    this.workloads = Collections.unmodifiableMap(new LinkedHashMap<>(workloads));
    this.trials = Collections.unmodifiableMap(new LinkedHashMap<>(trials));
    this.exclusions = List.copyOf(exclusions);
  }

  static CrossEngineBenchmarkPlan load() {
    BenchmarkData data = BenchmarkData.get();
    DeclarativeBenchmarkPlan plan = DeclarativeBenchmarkPlan.parse(data.declarativePlan());
    List<DeclarativeBenchmarkPlan.EngineDeclaration> engines =
        Arrays.stream(RegexEngineVariant.values()).map(RegexEngineVariant::declaration).toList();
    return fromExpanded(plan.expand(engines, IMPLEMENTED_OPERATIONS));
  }

  private static CrossEngineBenchmarkPlan fromExpanded(
      DeclarativeBenchmarkPlan.ExpandedPlan expanded) {
    Map<String, CrossEngineWorkload> workloads = new LinkedHashMap<>();
    for (DeclarativeBenchmarkPlan.ExpandedWorkload workload : expanded.workloads()) {
      CrossEngineWorkload converted = convert(workload);
      if (workloads.put(converted.id(), converted) != null) {
        throw new IllegalArgumentException("Duplicate cross-engine workload ID: " + converted.id());
      }
    }

    Map<String, Trial> trials = new LinkedHashMap<>();
    for (DeclarativeBenchmarkPlan.Trial trial : expanded.trials()) {
      CrossEngineWorkload workload = workloads.get(trial.workload().id());
      RegexEngineVariant variant = RegexEngineVariant.fromId(trial.engine().id());
      Trial converted = new Trial(workload, variant);
      if (trials.put(converted.id(), converted) != null) {
        throw new IllegalArgumentException("Duplicate cross-engine trial ID: " + converted.id());
      }
    }
    return new CrossEngineBenchmarkPlan(workloads, trials, expanded.exclusions());
  }

  List<Trial> trials(CrossEngineWorkload.TimingGroup timingGroup) {
    return trials.values().stream()
        .filter(trial -> trial.workload().timingGroup() == timingGroup)
        .toList();
  }

  List<CrossEngineWorkload> workloads() {
    return List.copyOf(workloads.values());
  }

  List<DeclarativeBenchmarkPlan.Exclusion> exclusions() {
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
    DeclarativeBenchmarkPlan.Exclusion exclusion =
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
            + exclusion.kind()
            + ": "
            + exclusion.reason());
  }

  static void main(String[] args) {
    if (args.length < 1) {
      throw new IllegalArgumentException(
          "Usage: CrossEngineBenchmarkPlan <nanoseconds|microseconds> [workload-prefix ...]");
    }
    CrossEngineWorkload.TimingGroup timingGroup =
        switch (args[0]) {
          case "nanoseconds" -> CrossEngineWorkload.TimingGroup.NANOSECONDS;
          case "microseconds" -> CrossEngineWorkload.TimingGroup.MICROSECONDS;
          default ->
              throw new IllegalArgumentException("Unknown cross-engine timing group: " + args[0]);
        };
    String trialIds =
        load().trials(timingGroup).stream()
            .filter(
                trial ->
                    args.length == 1
                        || Arrays.stream(args, 1, args.length)
                            .anyMatch(prefix -> trial.workload().id().startsWith(prefix)))
            .map(Trial::id)
            .collect(Collectors.joining(","));
    if (trialIds.isEmpty()) {
      throw new IllegalStateException("No cross-engine trials for " + timingGroup);
    }
    System.out.println(trialIds);
  }

  private static CrossEngineWorkload convert(DeclarativeBenchmarkPlan.ExpandedWorkload workload) {
    if (workload.patterns().size() != 1) {
      throw new IllegalArgumentException(
          workload.id() + " ordinary cross-engine workload requires exactly one pattern");
    }
    int[] groups = groups(workload.arguments());
    String replacement = stringArgument(workload.arguments(), "replacement");
    CrossEngineWorkload.TimingGroup timingGroup =
        switch (workload.measurement().timingUnit()) {
          case NANOSECONDS -> CrossEngineWorkload.TimingGroup.NANOSECONDS;
          case MICROSECONDS -> CrossEngineWorkload.TimingGroup.MICROSECONDS;
          default ->
              throw new IllegalArgumentException(
                  workload.id() + " is not an ordinary nanosecond or scaling microsecond workload");
        };
    return new CrossEngineWorkload(
        workload.id(),
        BenchmarkOperation.fromDeclarative(workload.operation()),
        workload.patterns().getFirst(),
        workload.inputIds(),
        groups,
        replacement,
        expectedValue(workload.expected()),
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
