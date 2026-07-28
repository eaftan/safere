// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Map;
import java.util.function.Function;

/**
 * Serializes expanded engine-neutral workloads for cross-runtime benchmark harnesses.
 *
 * <p>The Java harness can consume the typed declarative plan directly, but external runtimes
 * cannot. This projection resolves axes and recipe references once during materialization so a
 * native runner can execute or explicitly exclude every concrete workload without interpreting the
 * checked-in plan schema. Pattern and replacement profile selection remains the responsibility of
 * each engine adapter.
 */
final class ResolvedBenchmarkPlan {
  private ResolvedBenchmarkPlan() {}

  static JsonArray create(JsonObject normalizedBenchmarkData) {
    JsonObject planData = new JsonObject();
    planData.add("schemaVersion", normalizedBenchmarkData.get("schemaVersion").deepCopy());
    planData.add("inputs", normalizedBenchmarkData.getAsJsonArray("inputs").deepCopy());
    planData.add("workloads", normalizedBenchmarkData.getAsJsonArray("workloads").deepCopy());

    JsonArray result = new JsonArray();
    DeclarativeBenchmarkPlan.parse(planData)
        .expandedWorkloads()
        .forEach(workload -> result.add(toJson(workload)));
    return result;
  }

  private static JsonObject toJson(DeclarativeBenchmarkPlan.ExpandedWorkload workload) {
    JsonObject result = new JsonObject();
    result.addProperty("id", workload.id());
    result.addProperty("operation", workload.operation().jsonName());
    result.add("patterns", strings(workload.patterns()));
    result.add("inputs", strings(workload.inputIds()));
    result.add("arguments", arguments(workload.arguments()));
    result.add("flags", names(workload.flags(), DeclarativeBenchmarkPlan.Flag::jsonName));
    result.add(
        "requirements", names(workload.requirements(), DeclarativeBenchmarkPlan.Feature::jsonName));
    result.add(
        "inputRepresentations",
        names(
            workload.inputRepresentations(),
            DeclarativeBenchmarkPlan.InputRepresentation::jsonName));
    result.addProperty("resultConsumption", workload.resultConsumption().jsonName());
    if (workload.expected() != null) {
      JsonObject expected = new JsonObject();
      expected.addProperty("type", workload.expected().type().jsonName());
      expected.add("value", workload.expected().value());
      result.add("expected", expected);
    }
    result.add("lifecycle", lifecycle(workload.lifecycle()));
    result.add("measurement", measurement(workload.measurement()));
    if (workload.disabledReason() != null) {
      result.addProperty("disabledReason", workload.disabledReason());
    }
    return result;
  }

  private static JsonObject arguments(Map<String, DeclarativeBenchmarkPlan.RecipeValue> arguments) {
    JsonObject result = new JsonObject();
    arguments.forEach((name, value) -> result.add(name, recipeValue(value)));
    return result;
  }

  private static JsonElement recipeValue(DeclarativeBenchmarkPlan.RecipeValue value) {
    return switch (value) {
      case DeclarativeBenchmarkPlan.RecipeString string -> new JsonPrimitive(string.value());
      case DeclarativeBenchmarkPlan.RecipeInteger integer -> new JsonPrimitive(integer.value());
      case DeclarativeBenchmarkPlan.RecipeStringList strings -> strings(strings.values());
      case DeclarativeBenchmarkPlan.RecipeIntegerList integers -> {
        JsonArray result = new JsonArray();
        integers.values().forEach(result::add);
        yield result;
      }
      case DeclarativeBenchmarkPlan.RecipeAxisReference reference ->
          throw new IllegalStateException(
              "Expanded workload retains unresolved argument axis: " + reference.axis());
    };
  }

  private static JsonObject lifecycle(DeclarativeBenchmarkPlan.MatcherLifecycle lifecycle) {
    JsonObject result = new JsonObject();
    result.addProperty("matcher", lifecycle.matcher().jsonName());
    JsonArray steps = new JsonArray();
    for (DeclarativeBenchmarkPlan.LifecycleStep step : lifecycle.steps()) {
      JsonObject item = new JsonObject();
      item.addProperty("kind", step.kind().jsonName());
      if (step.start() != null) {
        item.addProperty("start", step.start());
      }
      if (step.end() != null) {
        item.addProperty("end", step.end());
      }
      if (step.enabled() != null) {
        item.addProperty("enabled", step.enabled());
      }
      steps.add(item);
    }
    result.add("steps", steps);
    return result;
  }

  private static JsonObject measurement(DeclarativeBenchmarkPlan.Measurement measurement) {
    JsonObject result = new JsonObject();
    result.addProperty("mode", measurement.mode().jsonName());
    result.addProperty("timingUnit", measurement.timingUnit().jsonName());
    result.add(
        "constraints",
        names(measurement.constraints(), DeclarativeBenchmarkPlan.ExecutionConstraint::jsonName));
    return result;
  }

  private static JsonArray strings(Iterable<String> values) {
    JsonArray result = new JsonArray();
    values.forEach(result::add);
    return result;
  }

  private static <T> JsonArray names(Iterable<T> values, Function<T, String> name) {
    JsonArray result = new JsonArray();
    values.forEach(value -> result.add(name.apply(value)));
    return result;
  }
}
