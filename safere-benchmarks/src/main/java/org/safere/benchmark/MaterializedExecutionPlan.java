// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Typed Java consumer of the common materialized execution-plan artifact. */
final class MaterializedExecutionPlan {
  private final Map<String, Engine> engines;
  private final Map<String, Entry> entries;

  private MaterializedExecutionPlan(Map<String, Engine> engines, Map<String, Entry> entries) {
    this.engines = Collections.unmodifiableMap(new LinkedHashMap<>(engines));
    this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
  }

  static MaterializedExecutionPlan load() {
    return parse(BenchmarkData.get().executionPlan());
  }

  static MaterializedExecutionPlan parse(JsonObject json) {
    int version = required(json, "version").getAsInt();
    if (version != ResolvedBenchmarkPlan.VERSION) {
      throw new IllegalArgumentException("Unsupported execution-plan version: " + version);
    }
    Map<String, Engine> engines = new LinkedHashMap<>();
    for (JsonElement element : required(json, "engines").getAsJsonArray()) {
      JsonObject object = element.getAsJsonObject();
      Engine engine =
          new Engine(
              string(object, "id"), string(object, "reportEngine"), string(object, "runner"));
      if (engines.put(engine.id(), engine) != null) {
        throw new IllegalArgumentException("Duplicate materialized engine ID: " + engine.id());
      }
    }

    Map<String, Entry> entries = new LinkedHashMap<>();
    Map<String, Integer> workloadCounts = new LinkedHashMap<>();
    for (JsonElement element : required(json, "entries").getAsJsonArray()) {
      Entry entry = parseEntry(element.getAsJsonObject(), engines);
      if (entries.put(entry.id(), entry) != null) {
        throw new IllegalArgumentException("Duplicate materialized execution ID: " + entry.id());
      }
      workloadCounts.merge(entry.workloadId(), 1, Integer::sum);
    }
    int expectedWorkloads = required(json, "workloadCount").getAsInt();
    int expectedEngines = required(json, "engineCount").getAsInt();
    if (engines.size() != expectedEngines
        || workloadCounts.size() != expectedWorkloads
        || entries.size() != expectedWorkloads * expectedEngines
        || workloadCounts.values().stream().anyMatch(count -> count != expectedEngines)) {
      throw new IllegalArgumentException(
          "Execution plan does not contain the complete workload/engine join");
    }
    return new MaterializedExecutionPlan(engines, entries);
  }

  List<Entry> entries() {
    return List.copyOf(entries.values());
  }

  List<Entry> entriesForRunner(String runner) {
    Set<String> engineIds = new LinkedHashSet<>();
    engines.values().stream()
        .filter(engine -> engine.runner().equals(runner))
        .map(Engine::id)
        .forEach(engineIds::add);
    return entries.values().stream().filter(entry -> engineIds.contains(entry.engineId())).toList();
  }

  Entry resolve(String id) {
    Entry entry = entries.get(id);
    if (entry == null) {
      throw new IllegalArgumentException("Unknown materialized execution ID: " + id);
    }
    return entry;
  }

  private static Entry parseEntry(JsonObject object, Map<String, Engine> engines) {
    String id = string(object, "id");
    String workloadId = string(object, "workloadId");
    String engineId = string(object, "engineId");
    Engine engine = engines.get(engineId);
    if (engine == null) {
      throw new IllegalArgumentException(
          id + " references unknown materialized engine " + engineId);
    }
    if (!id.equals(workloadId + "@" + engineId)) {
      throw new IllegalArgumentException("Malformed materialized execution ID: " + id);
    }
    if (!string(object, "reportEngine").equals(engine.reportEngine())) {
      throw new IllegalArgumentException(id + " report engine differs from engine declaration");
    }
    String status = string(object, "status");
    DeclarativeBenchmarkPlan.Operation operation =
        DeclarativeBenchmarkPlan.Operation.fromJson(string(object, "operation"));
    DeclarativeBenchmarkPlan.Measurement measurement =
        parseMeasurement(required(object, "measurement").getAsJsonObject());
    return switch (status) {
      case "runnable" ->
          new Entry(
              id,
              workloadId,
              engineId,
              engine.reportEngine(),
              operation,
              measurement,
              parseWorkload(object),
              null);
      case "excluded" -> {
        JsonObject exclusion = required(object, "exclusion").getAsJsonObject();
        yield new Entry(
            id,
            workloadId,
            engineId,
            engine.reportEngine(),
            operation,
            measurement,
            null,
            new Exclusion(string(exclusion, "kind"), string(exclusion, "reason")));
      }
      default -> throw new IllegalArgumentException(id + " has unknown status " + status);
    };
  }

  private static DeclarativeBenchmarkPlan.ExpandedWorkload parseWorkload(JsonObject object) {
    String id = string(object, "workloadId");
    DeclarativeBenchmarkPlan.Operation operation =
        DeclarativeBenchmarkPlan.Operation.fromJson(string(object, "operation"));
    Map<String, DeclarativeBenchmarkPlan.RecipeValue> arguments =
        parseArguments(required(object, "arguments").getAsJsonObject());
    DeclarativeBenchmarkPlan.ExpectedResult expected = null;
    if (object.has("expected")) {
      JsonObject expectedJson = object.getAsJsonObject("expected");
      expected =
          new DeclarativeBenchmarkPlan.ExpectedResult(
              DeclarativeBenchmarkPlan.ResultType.fromJson(string(expectedJson, "type")),
              required(expectedJson, "value").deepCopy(),
              null);
    }
    DeclarativeBenchmarkPlan.MatcherLifecycle lifecycle =
        parseLifecycle(required(object, "lifecycle").getAsJsonObject());
    DeclarativeBenchmarkPlan.Measurement measurement =
        parseMeasurement(required(object, "measurement").getAsJsonObject());
    DeclarativeBenchmarkPlan.InputRepresentation representation =
        DeclarativeBenchmarkPlan.InputRepresentation.fromJson(
            string(object, "inputRepresentation"));
    EnumSet<DeclarativeBenchmarkPlan.Flag> options =
        EnumSet.noneOf(DeclarativeBenchmarkPlan.Flag.class);
    for (String option : strings(object, "options")) {
      options.add(DeclarativeBenchmarkPlan.Flag.fromJson(option));
    }
    return new DeclarativeBenchmarkPlan.ExpandedWorkload(
        id,
        operation,
        strings(object, "patterns"),
        strings(object, "inputs"),
        Map.of(),
        arguments,
        options,
        EnumSet.noneOf(DeclarativeBenchmarkPlan.Feature.class),
        EnumSet.of(representation),
        null,
        DeclarativeBenchmarkPlan.ResultConsumption.fromJson(string(object, "resultConsumption")),
        expected,
        lifecycle,
        measurement,
        null);
  }

  private static Map<String, DeclarativeBenchmarkPlan.RecipeValue> parseArguments(
      JsonObject object) {
    Map<String, DeclarativeBenchmarkPlan.RecipeValue> result = new LinkedHashMap<>();
    for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
      JsonElement value = entry.getValue();
      DeclarativeBenchmarkPlan.RecipeValue parsed;
      if (value.isJsonArray()) {
        JsonArray array = value.getAsJsonArray();
        if (array.isEmpty() || array.get(0).getAsJsonPrimitive().isString()) {
          List<String> strings = new ArrayList<>();
          array.forEach(item -> strings.add(item.getAsString()));
          parsed = new DeclarativeBenchmarkPlan.RecipeStringList(strings);
        } else {
          List<Integer> integers = new ArrayList<>();
          array.forEach(item -> integers.add(item.getAsInt()));
          parsed = new DeclarativeBenchmarkPlan.RecipeIntegerList(integers);
        }
      } else if (value.getAsJsonPrimitive().isString()) {
        parsed = new DeclarativeBenchmarkPlan.RecipeString(value.getAsString());
      } else {
        parsed = new DeclarativeBenchmarkPlan.RecipeInteger(value.getAsInt());
      }
      result.put(entry.getKey(), parsed);
    }
    return Collections.unmodifiableMap(result);
  }

  private static DeclarativeBenchmarkPlan.MatcherLifecycle parseLifecycle(JsonObject object) {
    DeclarativeBenchmarkPlan.MatcherReuse matcher =
        DeclarativeBenchmarkPlan.MatcherReuse.fromJson(string(object, "matcher"));
    List<DeclarativeBenchmarkPlan.LifecycleStep> steps = new ArrayList<>();
    for (JsonElement element : required(object, "steps").getAsJsonArray()) {
      JsonObject step = element.getAsJsonObject();
      steps.add(
          new DeclarativeBenchmarkPlan.LifecycleStep(
              DeclarativeBenchmarkPlan.LifecycleStepKind.fromJson(string(step, "kind")),
              optionalInt(step, "start"),
              optionalInt(step, "end"),
              optionalBoolean(step, "enabled")));
    }
    return new DeclarativeBenchmarkPlan.MatcherLifecycle(matcher, steps);
  }

  private static DeclarativeBenchmarkPlan.Measurement parseMeasurement(JsonObject object) {
    EnumSet<DeclarativeBenchmarkPlan.ExecutionConstraint> constraints =
        EnumSet.noneOf(DeclarativeBenchmarkPlan.ExecutionConstraint.class);
    for (String constraint : strings(object, "constraints")) {
      constraints.add(DeclarativeBenchmarkPlan.ExecutionConstraint.fromJson(constraint));
    }
    return new DeclarativeBenchmarkPlan.Measurement(
        DeclarativeBenchmarkPlan.MeasurementMode.fromJson(string(object, "mode")),
        DeclarativeBenchmarkPlan.TimingUnit.fromJson(string(object, "timingUnit")),
        constraints);
  }

  private static JsonElement required(JsonObject object, String name) {
    JsonElement value = object.get(name);
    if (value == null || value.isJsonNull()) {
      throw new IllegalArgumentException("Materialized execution plan requires " + name);
    }
    return value;
  }

  private static String string(JsonObject object, String name) {
    return required(object, name).getAsString();
  }

  private static List<String> strings(JsonObject object, String name) {
    List<String> result = new ArrayList<>();
    required(object, name).getAsJsonArray().forEach(item -> result.add(item.getAsString()));
    return List.copyOf(result);
  }

  private static Integer optionalInt(JsonObject object, String name) {
    return object.has(name) ? object.get(name).getAsInt() : null;
  }

  private static Boolean optionalBoolean(JsonObject object, String name) {
    return object.has(name) ? object.get(name).getAsBoolean() : null;
  }

  record Engine(String id, String reportEngine, String runner) {}

  record Exclusion(String kind, String reason) {}

  record Entry(
      String id,
      String workloadId,
      String engineId,
      String reportEngine,
      DeclarativeBenchmarkPlan.Operation operation,
      DeclarativeBenchmarkPlan.Measurement measurement,
      DeclarativeBenchmarkPlan.ExpandedWorkload workload,
      Exclusion exclusion) {
    boolean runnable() {
      return workload != null;
    }
  }
}
