// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Builds and validates the supported cross-engine workload matrix. */
final class CrossEngineBenchmarkPlan {

  private final Map<String, CrossEngineWorkload> workloads;

  private CrossEngineBenchmarkPlan(List<CrossEngineWorkload> workloads) {
    Map<String, CrossEngineWorkload> byId = new LinkedHashMap<>();
    for (CrossEngineWorkload workload : workloads) {
      if (byId.put(workload.id(), workload) != null) {
        throw new IllegalArgumentException("Duplicate cross-engine workload ID: " + workload.id());
      }
    }
    this.workloads = Collections.unmodifiableMap(new LinkedHashMap<>(byId));
  }

  static CrossEngineBenchmarkPlan load() {
    return new CrossEngineBenchmarkPlan(loadWorkloads(BenchmarkData.get()));
  }

  List<Trial> trials(CrossEngineWorkload.TimingGroup timingGroup) {
    List<Trial> trials = new ArrayList<>();
    for (CrossEngineWorkload workload : workloads.values()) {
      if (workload.timingGroup() != timingGroup) {
        continue;
      }
      for (RegexEngineVariant variant : RegexEngineVariant.values()) {
        if (workload.operation().isSupportedBy(variant)) {
          trials.add(new Trial(workload, variant));
        }
      }
    }
    return List.copyOf(trials);
  }

  List<CrossEngineWorkload> workloads() {
    return List.copyOf(workloads.values());
  }

  Trial resolve(String trialId) {
    int separator = trialId.lastIndexOf('@');
    if (separator <= 0 || separator == trialId.length() - 1) {
      throw new IllegalArgumentException(
          "Cross-engine trial must be <workload-id>@<variant-id>: " + trialId);
    }
    String workloadId = trialId.substring(0, separator);
    String variantId = trialId.substring(separator + 1);
    CrossEngineWorkload workload = workloads.get(workloadId);
    if (workload == null) {
      throw new IllegalArgumentException("Unknown cross-engine workload ID: " + workloadId);
    }
    RegexEngineVariant variant = RegexEngineVariant.fromId(variantId);
    if (!workload.operation().isSupportedBy(variant)) {
      throw new IllegalArgumentException(
          "Unsupported cross-engine combination: "
              + workloadId
              + " uses "
              + workload.operation()
              + ", which "
              + variantId
              + " does not support");
    }
    return new Trial(workload, variant);
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

  private static List<CrossEngineWorkload> loadWorkloads(BenchmarkData data) {
    List<CrossEngineWorkload> workloads = new ArrayList<>();
    addRegexWorkloads(workloads, data);
    addApplicationWorkloads(workloads, data);
    addRealWorldWorkloads(workloads, data);
    addHttpWorkloads(workloads, data);
    addSearchScalingWorkloads(workloads, data);
    addFanoutWorkloads(workloads, data);
    return workloads;
  }

  private static void addRegexWorkloads(List<CrossEngineWorkload> workloads, BenchmarkData data) {
    addRegexWorkload(workloads, data, "literalMatch", BenchmarkOperation.MATCHES, new int[0]);
    addRegexWorkload(workloads, data, "charClassMatch", BenchmarkOperation.MATCHES, new int[0]);
    addRegexWorkload(
        workloads, data, "alternationFind", BenchmarkOperation.FIND_ALL_COUNT, new int[0]);
    addRegexWorkload(workloads, data, "findInText", BenchmarkOperation.FIND_ALL_COUNT, new int[0]);
    addRegexWorkload(workloads, data, "emailFind", BenchmarkOperation.FIND, new int[0]);
  }

  private static void addRegexWorkload(
      List<CrossEngineWorkload> workloads,
      BenchmarkData data,
      String name,
      BenchmarkOperation operation,
      int[] groups) {
    String path = "regex." + name;
    String id = data.getString(path + ".id");
    workloads.add(
        workload(
            id,
            operation,
            data.getString(path + ".pattern"),
            List.of(BenchmarkInputMaterializer.crossEngineInputKey(id)),
            groups,
            null,
            null,
            CrossEngineWorkload.TimingGroup.NANOSECONDS));
  }

  private static void addApplicationWorkloads(
      List<CrossEngineWorkload> workloads, BenchmarkData data) {
    for (ApplicationCase applicationCase : data.getApplicationCases().values()) {
      BenchmarkOperation operation =
          switch (applicationCase.op) {
            case "matchesCorpus" -> BenchmarkOperation.MATCHES_CORPUS;
            case "matchesGroupLengthSum" -> BenchmarkOperation.MATCHES_GROUP_LENGTH_SUM;
            case "findAllCount" -> BenchmarkOperation.FIND_ALL_COUNT;
            case "findAllLengthSum" -> BenchmarkOperation.FIND_ALL_LENGTH_SUM;
            case "findAllGroupLengthSum" -> BenchmarkOperation.FIND_ALL_GROUP_LENGTH_SUM;
            case "replaceAll" -> BenchmarkOperation.REPLACE_ALL;
            default ->
                throw new IllegalArgumentException(
                    "Unknown application benchmark op: " + applicationCase.op);
          };
      if (applicationCase.op.startsWith("findAll")
          && org.safere.Pattern.compile(applicationCase.pattern).matcher("").find()) {
        throw new IllegalArgumentException(
            applicationCase.id + " uses an empty-width pattern with " + applicationCase.op);
      }
      String baseInputKey = BenchmarkInputMaterializer.crossEngineInputKey(applicationCase.id);
      List<String> inputKeys = new ArrayList<>();
      if (applicationCase.texts.isEmpty()) {
        inputKeys.add(baseInputKey);
      } else {
        for (int index = 0; index < applicationCase.texts.size(); index++) {
          inputKeys.add(baseInputKey + "." + index);
        }
      }
      Object expected =
          applicationCase.expectsString()
              ? applicationCase.expectedString()
              : applicationCase.expectedInt();
      workloads.add(
          workload(
              applicationCase.id,
              operation,
              applicationCase.pattern,
              inputKeys,
              applicationCase.groups,
              applicationCase.replacement,
              expected,
              CrossEngineWorkload.TimingGroup.NANOSECONDS));
    }
  }

  private static void addRealWorldWorkloads(
      List<CrossEngineWorkload> workloads, BenchmarkData data) {
    int[] inputSizes = data.getIntArray("realWorldRegex.textSizes");
    for (RealWorldRegexCase regexCase : data.getRealWorldRegexCases().values()) {
      BenchmarkOperation operation =
          switch (regexCase.op) {
            case "find" -> BenchmarkOperation.FIND;
            case "matches" -> BenchmarkOperation.MATCHES;
            case "replaceAllEmpty", "replaceAllGroup1", "replaceAllLiteral" ->
                BenchmarkOperation.REPLACE_ALL;
            default ->
                throw new IllegalArgumentException(
                    "Unknown real-world regex benchmark op: " + regexCase.op);
          };
      String replacement =
          switch (regexCase.op) {
            case "replaceAllEmpty" -> "";
            case "replaceAllGroup1" -> "$1";
            case "replaceAllLiteral" -> "xyz";
            default -> null;
          };
      for (boolean match : new boolean[] {true, false}) {
        String matchLabel = match ? "match" : "noMatch";
        for (int inputSize : inputSizes) {
          String id = regexCase.id + "." + matchLabel + "." + inputSize;
          workloads.add(
              workload(
                  id,
                  operation,
                  regexCase.pattern,
                  List.of("realWorldRegex." + regexCase.name + "." + matchLabel + "." + inputSize),
                  new int[0],
                  replacement,
                  null,
                  CrossEngineWorkload.TimingGroup.NANOSECONDS));
        }
      }
    }
  }

  private static void addHttpWorkloads(List<CrossEngineWorkload> workloads, BenchmarkData data) {
    String pattern = data.getString("http.pattern");
    String fullId = data.getString("http.workloadIds.full");
    workloads.add(
        workload(
            fullId,
            BenchmarkOperation.FIND_GROUP_PRESENT,
            pattern,
            List.of(BenchmarkInputMaterializer.crossEngineInputKey(fullId)),
            new int[] {1},
            null,
            null,
            CrossEngineWorkload.TimingGroup.NANOSECONDS));
    String smallId = data.getString("http.workloadIds.small");
    workloads.add(
        workload(
            smallId,
            BenchmarkOperation.FIND_GROUP_PRESENT,
            pattern,
            List.of(BenchmarkInputMaterializer.crossEngineInputKey(smallId)),
            new int[] {1},
            null,
            null,
            CrossEngineWorkload.TimingGroup.NANOSECONDS));
    String extractId = data.getString("http.workloadIds.extract");
    workloads.add(
        workload(
            extractId,
            BenchmarkOperation.FIND_GROUP,
            pattern,
            List.of(BenchmarkInputMaterializer.crossEngineInputKey(fullId)),
            new int[] {1},
            null,
            null,
            CrossEngineWorkload.TimingGroup.NANOSECONDS));
  }

  private static void addSearchScalingWorkloads(
      List<CrossEngineWorkload> workloads, BenchmarkData data) {
    int[] inputSizes = data.getIntArray("searchScaling.textSizes");
    for (int inputSize : inputSizes) {
      addScalingFind(
          workloads,
          data,
          "searchScaling.workloadIds.easyFail",
          "searchScaling.patterns.easy",
          "searchScaling.random." + inputSize,
          inputSize);
      addScalingFind(
          workloads,
          data,
          "searchScaling.workloadIds.easySuccess",
          "searchScaling.patterns.easy",
          "searchScaling.success." + inputSize,
          inputSize);
      addScalingFind(
          workloads,
          data,
          "searchScaling.workloadIds.mediumFail",
          "searchScaling.patterns.medium",
          "searchScaling.random." + inputSize,
          inputSize);
      addScalingFind(
          workloads,
          data,
          "searchScaling.workloadIds.hardFail",
          "searchScaling.patterns.hard",
          "searchScaling.random." + inputSize,
          inputSize);
      String findAllId =
          data.getString("searchScaling.workloadIds.findIngScaled") + "." + inputSize;
      workloads.add(
          workload(
              findAllId,
              BenchmarkOperation.FIND_ALL_COUNT,
              data.getString("searchScaling.findIngPattern"),
              List.of("searchScaling.prose." + inputSize),
              new int[0],
              null,
              null,
              CrossEngineWorkload.TimingGroup.MICROSECONDS));
    }
  }

  private static void addScalingFind(
      List<CrossEngineWorkload> workloads,
      BenchmarkData data,
      String idPath,
      String patternPath,
      String inputKey,
      int inputSize) {
    workloads.add(
        workload(
            data.getString(idPath) + "." + inputSize,
            BenchmarkOperation.FIND,
            data.getString(patternPath),
            List.of(inputKey),
            new int[0],
            null,
            null,
            CrossEngineWorkload.TimingGroup.MICROSECONDS));
  }

  private static void addFanoutWorkloads(List<CrossEngineWorkload> workloads, BenchmarkData data) {
    int[] inputSizes = data.getIntArray("fanout.textSizes");
    for (int inputSize : inputSizes) {
      addScalingFind(
          workloads,
          data,
          "fanout.unicodeFanout.id",
          "fanout.unicodeFanout.pattern",
          "fanout.unicode." + inputSize,
          inputSize);
      addScalingFind(
          workloads,
          data,
          "fanout.nestedQuantifier.id",
          "fanout.nestedQuantifier.pattern",
          "fanout.ascii." + inputSize,
          inputSize);
    }
  }

  private static CrossEngineWorkload workload(
      String id,
      BenchmarkOperation operation,
      String pattern,
      List<String> inputKeys,
      int[] groups,
      String replacement,
      Object expected,
      CrossEngineWorkload.TimingGroup timingGroup) {
    return new CrossEngineWorkload(
        id, operation, pattern, inputKeys, groups, replacement, expected, timingGroup);
  }

  record Trial(CrossEngineWorkload workload, RegexEngineVariant variant) {
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
