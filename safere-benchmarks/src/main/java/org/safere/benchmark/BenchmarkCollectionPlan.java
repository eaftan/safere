// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Discovers generic benchmark runners, trials, and report identities from the declared plan. */
final class BenchmarkCollectionPlan {
  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

  private final CrossEngineBenchmarkPlan crossEngine;
  private final SpecializedBenchmarkPlan specialized;

  private BenchmarkCollectionPlan(
      CrossEngineBenchmarkPlan crossEngine, SpecializedBenchmarkPlan specialized) {
    this.crossEngine = crossEngine;
    this.specialized = specialized;
  }

  static BenchmarkCollectionPlan load() {
    return new BenchmarkCollectionPlan(
        CrossEngineBenchmarkPlan.load(), SpecializedBenchmarkPlan.load());
  }

  List<Runner> runners() {
    Set<String> allocationOnly = allocationOnlyTrialIds();
    return filterRunners(allRunners(), trialId -> !allocationOnly.contains(trialId), false);
  }

  private List<Runner> allRunners() {
    return List.of(
        runner(
            "standard",
            CrossEngineBenchmark.class,
            "crossEngineTrial",
            crossEngine.trials(
                CrossEngineWorkload.TimingGroup.NANOSECONDS,
                DeclarativeBenchmarkPlan.MeasurementMode.AVERAGE_TIME,
                false)),
        runner(
            "standard",
            CrossEngineScalingBenchmark.class,
            "crossEngineScalingTrial",
            crossEngine.trials(CrossEngineWorkload.TimingGroup.MICROSECONDS).stream()
                .filter(
                    trial ->
                        !trial
                            .workload()
                            .measurement()
                            .constraints()
                            .contains(DeclarativeBenchmarkPlan.ExecutionConstraint.NO_FORK))
                .toList()),
        runner(
            "noFork",
            CrossEngineNoForkBenchmark.class,
            "crossEngineNoForkTrial",
            crossEngine.trials(
                CrossEngineWorkload.TimingGroup.MICROSECONDS,
                DeclarativeBenchmarkPlan.MeasurementMode.AVERAGE_TIME,
                true)),
        runner(
            "coldStart",
            CrossEngineColdStartBenchmark.class,
            "crossEngineColdStartTrial",
            crossEngine.trials(
                CrossEngineWorkload.TimingGroup.MILLISECONDS,
                DeclarativeBenchmarkPlan.MeasurementMode.SINGLE_SHOT_COLD_START,
                false)),
        new Runner(
            "standard",
            SpecializedBenchmark.class.getName() + ".run",
            "specializedTrial",
            specialized.averageTimeTrials().stream()
                .map(SpecializedBenchmarkPlan.Trial::id)
                .toList()));
  }

  List<Runner> allocationRunners() {
    List<String> prefixes =
        BenchmarkData.get().getStringList("configuration.collection.allocationWorkloadPrefixes");
    Set<String> allocationOnly = allocationOnlyTrialIds();
    return filterRunners(
        allRunners(),
        trialId ->
            allocationOnly.contains(trialId)
                || prefixes.stream()
                    .anyMatch(trialId.substring(0, trialId.lastIndexOf('@'))::startsWith),
        true);
  }

  private static List<Runner> filterRunners(
      List<Runner> runners,
      java.util.function.Predicate<String> includeTrial,
      boolean standardOnly) {
    return runners.stream()
        .filter(runner -> !standardOnly || runner.profile().equals("standard"))
        .map(
            runner ->
                new Runner(
                    runner.profile(),
                    runner.benchmark(),
                    runner.parameter(),
                    runner.trialIds().stream().filter(includeTrial).toList()))
        .filter(runner -> !runner.trialIds().isEmpty())
        .toList();
  }

  private Set<String> allocationOnlyTrialIds() {
    return trials(Query.ALL).stream()
        .filter(
            trial ->
                trial
                    .measurement()
                    .constraints()
                    .contains(DeclarativeBenchmarkPlan.ExecutionConstraint.ALLOCATION_PROFILE))
        .map(CollectionTrial::id)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Runner runner(
      String profile,
      Class<?> runnerClass,
      String parameter,
      List<CrossEngineBenchmarkPlan.Trial> trials) {
    return new Runner(
        profile,
        runnerClass.getName() + ".run",
        parameter,
        trials.stream().map(CrossEngineBenchmarkPlan.Trial::id).toList());
  }

  List<CollectionTrial> trials(Query query) {
    List<CollectionTrial> trials = new ArrayList<>();
    for (CrossEngineWorkload.TimingGroup group : CrossEngineWorkload.TimingGroup.values()) {
      for (CrossEngineBenchmarkPlan.Trial trial : crossEngine.trials(group)) {
        trials.add(
            new CollectionTrial(
                trial.id(),
                trial.workload().id(),
                trial.variant().id(),
                trial.workload().measurement()));
      }
    }
    for (SpecializedBenchmarkPlan.Trial trial : specialized.averageTimeTrials()) {
      trials.add(
          new CollectionTrial(
              trial.id(),
              trial.workload().id(),
              trial.variant().id(),
              trial.workload().measurement()));
    }
    return trials.stream().filter(query::matches).toList();
  }

  ReportPlan reportPlan() {
    return reportPlan(false);
  }

  ReportPlan reportPlan(boolean smoke) {
    Set<String> collectedTrialIds =
        runners().stream()
            .flatMap(runner -> (smoke ? smokeTrialIds(runner) : runner.trialIds()).stream())
            .collect(Collectors.toUnmodifiableSet());
    List<CollectionTrial> trials =
        trials(Query.ALL).stream().filter(trial -> collectedTrialIds.contains(trial.id())).toList();
    Set<String> workloadIds =
        trials.stream()
            .map(CollectionTrial::workloadId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    List<ReportExclusion> exclusions = new ArrayList<>();
    crossEngine.exclusions().stream()
        .filter(exclusion -> workloadIds.contains(exclusion.workloadId()))
        .map(ReportExclusion::from)
        .forEach(exclusions::add);
    specialized.exclusions().stream()
        .filter(exclusion -> workloadIds.contains(exclusion.workloadId()))
        .map(ReportExclusion::from)
        .forEach(exclusions::add);
    return new ReportPlan(trials, exclusions.stream().distinct().toList());
  }

  public static void main(String[] args) {
    if (args.length == 0) {
      throw new IllegalArgumentException(
          "Usage: BenchmarkCollectionPlan "
              + "<runners|allocation-runners|trials|report-plan> [query options]");
    }
    BenchmarkCollectionPlan plan = load();
    switch (args[0]) {
      case "runners", "allocation-runners" -> {
        boolean smoke = args.length == 2 && args[1].equals("--smoke");
        if (args.length > 2 || (args.length == 2 && !smoke)) {
          throw new IllegalArgumentException(
              "Usage: BenchmarkCollectionPlan " + args[0] + " [--smoke]");
        }
        List<Runner> runners =
            args[0].equals("runners") ? plan.runners() : plan.allocationRunners();
        runners.forEach(
            runner ->
                System.out.printf(
                    "%s\t%s\t%s\t%s%n",
                    runner.profile(),
                    runner.benchmark(),
                    runner.parameter(),
                    String.join(",", smoke ? smokeTrialIds(runner) : runner.trialIds())));
      }
      case "trials" -> {
        Query query = Query.parse(Arrays.copyOfRange(args, 1, args.length));
        List<CollectionTrial> selected = plan.trials(query);
        if (selected.isEmpty()) {
          throw new IllegalStateException("No declared trials match the query");
        }
        System.out.println(
            selected.stream().map(CollectionTrial::id).collect(Collectors.joining(",")));
      }
      case "report-plan" -> {
        boolean smoke = args.length == 2 && args[1].equals("--smoke");
        if (args.length > 2 || (args.length == 2 && !smoke)) {
          throw new IllegalArgumentException(
              "Usage: BenchmarkCollectionPlan report-plan [--smoke]");
        }
        System.out.println(GSON.toJson(plan.reportPlan(smoke)));
      }
      default -> throw new IllegalArgumentException("Unknown collection-plan command: " + args[0]);
    }
  }

  private static List<String> smokeTrialIds(Runner runner) {
    String firstTrial = runner.trialIds().getFirst();
    String firstWorkload = firstTrial.substring(0, firstTrial.lastIndexOf('@'));
    return runner.trialIds().stream()
        .filter(trialId -> trialId.startsWith(firstWorkload + "@"))
        .toList();
  }

  record Runner(String profile, String benchmark, String parameter, List<String> trialIds) {}

  record CollectionTrial(
      String id,
      String workloadId,
      String executionVariant,
      DeclarativeBenchmarkPlan.Measurement measurement) {}

  record ReportExclusion(String workloadId, String executionVariant, String kind, String reason) {
    static ReportExclusion from(MaterializedExecutionPlan.Entry exclusion) {
      return new ReportExclusion(
          exclusion.workloadId(),
          exclusion.engineId(),
          exclusion.exclusion().kind(),
          exclusion.exclusion().reason());
    }
  }

  record ReportPlan(List<CollectionTrial> trials, List<ReportExclusion> exclusions) {}

  record Query(String mode, String timing, String prefix, String variant) {
    private static final Query ALL = new Query(null, null, null, null);

    static Query parse(String[] args) {
      String mode = null;
      String timing = null;
      String prefix = null;
      String variant = null;
      for (int index = 0; index < args.length; index += 2) {
        if (index + 1 >= args.length) {
          throw new IllegalArgumentException("Missing value for query option " + args[index]);
        }
        switch (args[index]) {
          case "--mode" -> mode = args[index + 1];
          case "--timing" -> timing = args[index + 1];
          case "--prefix" -> prefix = args[index + 1];
          case "--variant" -> variant = args[index + 1];
          default -> throw new IllegalArgumentException("Unknown query option: " + args[index]);
        }
      }
      return new Query(mode, timing, prefix, variant);
    }

    boolean matches(CollectionTrial trial) {
      return (mode == null || trial.measurement().mode().jsonName().equals(mode))
          && (timing == null || trial.measurement().timingUnit().jsonName().equals(timing))
          && (prefix == null || trial.workloadId().startsWith(prefix))
          && (variant == null || trial.executionVariant().equals(variant));
    }
  }
}
