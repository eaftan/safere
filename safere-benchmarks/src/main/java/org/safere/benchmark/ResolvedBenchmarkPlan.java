// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Materializes the complete engine/workload compatibility join for every benchmark runner.
 *
 * <p>The resulting execution plan is the only workload contract consumed by Java and cross-runtime
 * runners. Every concrete workload/engine pair is represented exactly once as either runnable, with
 * engine-selected syntax and resolved arguments, or excluded with a durable reason.
 */
final class ResolvedBenchmarkPlan {
  static final int VERSION = 1;

  private static final EnumSet<DeclarativeBenchmarkPlan.Operation> PORTABLE_OPERATIONS =
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
          DeclarativeBenchmarkPlan.Operation.SPLIT_LENGTH_SUM,
          DeclarativeBenchmarkPlan.Operation.COMPILE,
          DeclarativeBenchmarkPlan.Operation.COMPILE_AND_FIND,
          DeclarativeBenchmarkPlan.Operation.FIND_GROUP_PRESENT,
          DeclarativeBenchmarkPlan.Operation.FIND_GROUP);

  private static final EnumSet<DeclarativeBenchmarkPlan.MeasurementMode> PORTABLE_MODES =
      EnumSet.of(
          DeclarativeBenchmarkPlan.MeasurementMode.AVERAGE_TIME,
          DeclarativeBenchmarkPlan.MeasurementMode.COMPILE_ONLY,
          DeclarativeBenchmarkPlan.MeasurementMode.RETAINED_MEMORY);

  private ResolvedBenchmarkPlan() {}

  static JsonObject create(JsonObject normalizedBenchmarkData) {
    return create(normalizedBenchmarkData, engines());
  }

  static JsonObject create(JsonObject normalizedBenchmarkData, List<Engine> engines) {
    JsonObject planData = new JsonObject();
    planData.add("schemaVersion", normalizedBenchmarkData.get("schemaVersion").deepCopy());
    planData.add("inputs", normalizedBenchmarkData.getAsJsonArray("inputs").deepCopy());
    planData.add("workloads", normalizedBenchmarkData.getAsJsonArray("workloads").deepCopy());

    DeclarativeBenchmarkPlan plan = DeclarativeBenchmarkPlan.parse(planData);
    PatternProfiles patternProfiles =
        PatternProfiles.parse(normalizedBenchmarkData.get("patternProfiles"));
    PatternProfiles replacementProfiles =
        PatternProfiles.parse(normalizedBenchmarkData.get("replacementProfiles"));

    validateEngines(engines);
    JsonObject result = new JsonObject();
    result.addProperty("version", VERSION);
    result.add(
        "engines",
        array(
            engines,
            engine -> {
              JsonObject json = new JsonObject();
              json.addProperty("id", engine.id());
              json.addProperty("reportEngine", engine.reportEngine());
              json.addProperty("runner", engine.runner());
              return json;
            }));

    List<DeclarativeBenchmarkPlan.ExpandedWorkload> workloads = plan.expandedWorkloads();
    patternProfiles.validateReferences(
        workloads.stream()
            .flatMap(workload -> workload.patterns().stream())
            .collect(java.util.stream.Collectors.toSet()),
        "pattern");
    replacementProfiles.validateReferences(
        workloads.stream()
            .map(workload -> workload.arguments().get("replacement"))
            .filter(DeclarativeBenchmarkPlan.RecipeString.class::isInstance)
            .map(DeclarativeBenchmarkPlan.RecipeString.class::cast)
            .map(DeclarativeBenchmarkPlan.RecipeString::value)
            .collect(java.util.stream.Collectors.toSet()),
        "replacement");
    JsonArray entries = new JsonArray();
    for (DeclarativeBenchmarkPlan.ExpandedWorkload workload : workloads) {
      for (Engine engine : engines) {
        entries.add(toEntry(workload, engine, patternProfiles, replacementProfiles));
      }
    }
    result.addProperty("workloadCount", workloads.size());
    result.addProperty("engineCount", engines.size());
    result.add("entries", entries);
    return result;
  }

  static List<Engine> engines() {
    List<Engine> result = new ArrayList<>();
    for (RegexEngineVariant variant : RegexEngineVariant.values()) {
      result.add(
          new Engine(
              variant.id(),
              variant.reportEngine(),
              "java",
              variant.patternProfile(),
              "java",
              variant.declaration(),
              EnumSet.allOf(DeclarativeBenchmarkPlan.Operation.class),
              EnumSet.allOf(DeclarativeBenchmarkPlan.MeasurementMode.class),
              variant == RegexEngineVariant.SAFERE_STRING
                      || variant == RegexEngineVariant.SAFERE_UTF8
                      || variant == RegexEngineVariant.JDK_STRING
                  ? allFlagSets()
                  : Set.of("0")));
    }

    result.add(
        portableEngine(
            "re2_cpp",
            "re2_cpp",
            "cpp",
            "re2",
            "re2-cpp",
            true,
            true,
            false,
            PORTABLE_OPERATIONS,
            PORTABLE_MODES));
    result.add(
        portableEngine(
            "pcre2_jit",
            "pcre2_jit",
            "cpp",
            "pcre2",
            "pcre2",
            false,
            false,
            false,
            withoutReplacement(PORTABLE_OPERATIONS),
            PORTABLE_MODES));
    result.add(
        portableEngine(
            "go_regexp",
            "go_regexp",
            "go",
            "re2",
            "go-regexp",
            true,
            true,
            true,
            PORTABLE_OPERATIONS,
            PORTABLE_MODES));
    result.add(
        portableEngine(
            "rust_regex",
            "rust_regex",
            "rust",
            "rust-regex",
            "rust-regex",
            true,
            true,
            true,
            PORTABLE_OPERATIONS,
            PORTABLE_MODES));

    EnumSet<DeclarativeBenchmarkPlan.Feature> dotnetFeatures = portableFeatures(false, true, false);
    dotnetFeatures.add(DeclarativeBenchmarkPlan.Feature.FLAGGED_COMPILE);
    dotnetFeatures.add(DeclarativeBenchmarkPlan.Feature.JAVA_CHARACTER_CLASS);
    EnumSet<DeclarativeBenchmarkPlan.Operation> dotnetOperations =
        EnumSet.copyOf(PORTABLE_OPERATIONS);
    dotnetOperations.add(DeclarativeBenchmarkPlan.Operation.FIND_ROTATING_UTF16);
    dotnetOperations.add(DeclarativeBenchmarkPlan.Operation.COMPILE_AND_FIND_ROTATING_UTF16);
    result.add(
        new Engine(
            "dotnet_nonbacktracking",
            "dotnet_nonbacktracking",
            "dotnet",
            "dotnet",
            "dotnet",
            new DeclarativeBenchmarkPlan.EngineDeclaration(
                "dotnet_nonbacktracking",
                DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
                dotnetFeatures,
                EnumSet.noneOf(DeclarativeBenchmarkPlan.Flag.class),
                true),
            dotnetOperations,
            EnumSet.of(
                DeclarativeBenchmarkPlan.MeasurementMode.AVERAGE_TIME,
                DeclarativeBenchmarkPlan.MeasurementMode.COMPILE_ONLY,
                DeclarativeBenchmarkPlan.MeasurementMode.SINGLE_SHOT_COLD_START),
            Set.of(
                "0",
                "CASE_INSENSITIVE_UNICODE_CASE",
                "UNICODE_CHARACTER_CLASS",
                "CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS")));
    return List.copyOf(result);
  }

  private static Engine portableEngine(
      String id,
      String reportEngine,
      String runner,
      String patternProfile,
      String replacementProfile,
      boolean linearTime,
      boolean replacement,
      boolean retainedHeap,
      EnumSet<DeclarativeBenchmarkPlan.Operation> operations,
      EnumSet<DeclarativeBenchmarkPlan.MeasurementMode> modes) {
    return new Engine(
        id,
        reportEngine,
        runner,
        patternProfile,
        replacementProfile,
        new DeclarativeBenchmarkPlan.EngineDeclaration(
            id,
            DeclarativeBenchmarkPlan.InputRepresentation.PREEXISTING_UTF8,
            portableFeatures(linearTime, replacement, retainedHeap),
            EnumSet.noneOf(DeclarativeBenchmarkPlan.Flag.class),
            true),
        operations,
        modes,
        Set.of("0"));
  }

  private static EnumSet<DeclarativeBenchmarkPlan.Feature> portableFeatures(
      boolean linearTime, boolean replacement, boolean retainedHeap) {
    EnumSet<DeclarativeBenchmarkPlan.Feature> features =
        EnumSet.of(
            DeclarativeBenchmarkPlan.Feature.FIND,
            DeclarativeBenchmarkPlan.Feature.MATCHES,
            DeclarativeBenchmarkPlan.Feature.LOOKING_AT,
            DeclarativeBenchmarkPlan.Feature.CAPTURE_PARTICIPATION,
            DeclarativeBenchmarkPlan.Feature.CAPTURE_TEXT,
            DeclarativeBenchmarkPlan.Feature.NAMED_GROUPS,
            DeclarativeBenchmarkPlan.Feature.SPLIT);
    if (linearTime) {
      features.add(DeclarativeBenchmarkPlan.Feature.LINEAR_TIME);
    }
    if (replacement) {
      features.add(DeclarativeBenchmarkPlan.Feature.REPLACE);
      features.add(DeclarativeBenchmarkPlan.Feature.NUMBERED_REPLACEMENT);
      features.add(DeclarativeBenchmarkPlan.Feature.NAMED_REPLACEMENT);
    }
    if (retainedHeap) {
      features.add(DeclarativeBenchmarkPlan.Feature.RETAINED_HEAP);
    }
    return features;
  }

  private static EnumSet<DeclarativeBenchmarkPlan.Operation> withoutReplacement(
      EnumSet<DeclarativeBenchmarkPlan.Operation> operations) {
    EnumSet<DeclarativeBenchmarkPlan.Operation> result = EnumSet.copyOf(operations);
    result.remove(DeclarativeBenchmarkPlan.Operation.REPLACE_FIRST);
    result.remove(DeclarativeBenchmarkPlan.Operation.REPLACE_ALL);
    result.remove(DeclarativeBenchmarkPlan.Operation.REPLACE_ALL_LENGTH_SUM);
    return result;
  }

  private static Set<String> allFlagSets() {
    return Set.of(
        "0",
        "CASE_INSENSITIVE",
        "CASE_INSENSITIVE_UNICODE_CASE",
        "UNICODE_CHARACTER_CLASS",
        "CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS");
  }

  private static void validateEngines(List<Engine> engines) {
    if (engines.isEmpty()) {
      throw new IllegalArgumentException("Execution plan requires at least one engine");
    }
    Set<String> ids = new LinkedHashSet<>();
    for (Engine engine : engines) {
      if (!ids.add(engine.id())) {
        throw new IllegalArgumentException("Duplicate execution-plan engine ID: " + engine.id());
      }
      if (!engine.id().equals(engine.declaration().id())) {
        throw new IllegalArgumentException(
            "Execution-plan engine declaration ID differs from engine ID: " + engine.id());
      }
    }
  }

  private static JsonObject toEntry(
      DeclarativeBenchmarkPlan.ExpandedWorkload workload,
      Engine engine,
      PatternProfiles patternProfiles,
      PatternProfiles replacementProfiles) {
    JsonObject entry = new JsonObject();
    entry.addProperty("id", workload.id() + "@" + engine.id());
    entry.addProperty("workloadId", workload.id());
    entry.addProperty("engineId", engine.id());
    entry.addProperty("reportEngine", engine.reportEngine());
    entry.addProperty("operation", workload.operation().jsonName());
    entry.add("measurement", measurement(workload.measurement()));
    String flagSet = flagSet(workload);

    Exclusion exclusion = exclusion(workload, engine, patternProfiles, replacementProfiles);
    if (exclusion != null) {
      entry.addProperty("status", "excluded");
      JsonObject reason = new JsonObject();
      reason.addProperty("kind", exclusion.kind());
      reason.addProperty("reason", exclusion.reason());
      entry.add("exclusion", reason);
      return entry;
    }

    entry.addProperty("status", "runnable");
    entry.add(
        "patterns",
        strings(
            workload.patterns().stream()
                .map(pattern -> patternProfiles.select(engine.patternProfile(), pattern, flagSet))
                .toList()));
    entry.add("inputs", strings(workload.inputIds()));
    entry.add("arguments", arguments(workload.arguments(), engine, replacementProfiles, flagSet));
    entry.add("options", strings(options(flagSet)));
    entry.addProperty("inputRepresentation", engine.declaration().inputRepresentation().jsonName());
    entry.addProperty("resultConsumption", workload.resultConsumption().jsonName());
    if (workload.expected() != null) {
      JsonObject expected = new JsonObject();
      expected.addProperty("type", workload.expected().type().jsonName());
      expected.add("value", workload.expected().value());
      entry.add("expected", expected);
    }
    entry.add("lifecycle", lifecycle(workload.lifecycle()));
    return entry;
  }

  private static Exclusion exclusion(
      DeclarativeBenchmarkPlan.ExpandedWorkload workload,
      Engine engine,
      PatternProfiles patternProfiles,
      PatternProfiles replacementProfiles) {
    if (workload.disabledReason() != null) {
      return new Exclusion("workloadDisabled", workload.disabledReason());
    }
    if (!engine.declaration().adapterAvailable()) {
      return new Exclusion("missingEngineAdapter", "engine adapter is not available");
    }
    if (!engine.operations().contains(workload.operation())) {
      return new Exclusion(
          "unsupportedOperation",
          "engine does not implement operation " + workload.operation().jsonName());
    }
    if (!engine.measurementModes().contains(workload.measurement().mode())) {
      return new Exclusion(
          "unsupportedMeasurementMode",
          "engine does not implement measurement mode " + workload.measurement().mode().jsonName());
    }
    if ((!workload.inputIds().isEmpty() || engine.runner().equals("java"))
        && !workload.inputRepresentations().contains(engine.declaration().inputRepresentation())) {
      return new Exclusion(
          "unsupportedInputRepresentation",
          "workload does not accept " + engine.declaration().inputRepresentation().jsonName());
    }
    EnumSet<DeclarativeBenchmarkPlan.Flag> unsupportedFlags = workload.flags();
    unsupportedFlags.removeAll(engine.declaration().supportedFlags());
    if (!unsupportedFlags.isEmpty()) {
      return new Exclusion("unsupportedFlag", "engine lacks flags " + flagNames(unsupportedFlags));
    }
    String flagSet = flagSet(workload);
    if (!engine.flagSets().contains(flagSet)) {
      return new Exclusion("unsupportedOptions", "engine does not support flag set " + flagSet);
    }
    EnumSet<DeclarativeBenchmarkPlan.Feature> missing = workload.requirements();
    missing.removeAll(engine.declaration().features());
    if (!missing.isEmpty()) {
      return new Exclusion("unsupportedFeature", "engine lacks " + featureNames(missing));
    }
    for (String pattern : workload.patterns()) {
      String reason =
          patternProfiles.resolve(engine.patternProfile(), pattern, flagSet).unsupportedReason();
      if (reason != null) {
        return new Exclusion("unsupportedSyntax", reason);
      }
    }
    DeclarativeBenchmarkPlan.RecipeValue replacement = workload.arguments().get("replacement");
    if (replacement != null) {
      String value = ((DeclarativeBenchmarkPlan.RecipeString) replacement).value();
      String reason =
          replacementProfiles
              .resolve(engine.replacementProfile(), value, flagSet)
              .unsupportedReason();
      if (reason != null) {
        return new Exclusion("unsupportedSyntax", reason);
      }
    }
    return null;
  }

  private static JsonObject arguments(
      Map<String, DeclarativeBenchmarkPlan.RecipeValue> arguments,
      Engine engine,
      PatternProfiles replacementProfiles,
      String flagSet) {
    JsonObject result = new JsonObject();
    arguments.forEach(
        (name, value) -> {
          JsonElement json = recipeValue(value);
          if (name.equals("replacement")) {
            json =
                new JsonPrimitive(
                    replacementProfiles.select(
                        engine.replacementProfile(), json.getAsString(), flagSet));
          }
          result.add(name, json);
        });
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

  private static String flagSet(DeclarativeBenchmarkPlan.ExpandedWorkload workload) {
    DeclarativeBenchmarkPlan.RecipeValue value = workload.arguments().get("flagSet");
    return value == null ? "0" : ((DeclarativeBenchmarkPlan.RecipeString) value).value();
  }

  private static List<String> options(String flagSet) {
    return switch (flagSet) {
      case "0" -> List.of();
      case "CASE_INSENSITIVE" -> List.of("caseInsensitive");
      case "CASE_INSENSITIVE_UNICODE_CASE" -> List.of("caseInsensitive", "unicodeCase");
      case "UNICODE_CHARACTER_CLASS" -> List.of("unicodeCharacterClass");
      case "CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS" ->
          List.of("caseInsensitive", "unicodeCase", "unicodeCharacterClass");
      default -> throw new IllegalArgumentException("Unknown benchmark flag set: " + flagSet);
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

  private static String flagNames(Set<DeclarativeBenchmarkPlan.Flag> values) {
    return values.stream()
        .map(DeclarativeBenchmarkPlan.Flag::jsonName)
        .sorted()
        .toList()
        .toString();
  }

  private static String featureNames(Set<DeclarativeBenchmarkPlan.Feature> values) {
    return values.stream()
        .map(DeclarativeBenchmarkPlan.Feature::jsonName)
        .sorted()
        .toList()
        .toString();
  }

  private static JsonArray strings(Iterable<String> values) {
    JsonArray result = new JsonArray();
    values.forEach(result::add);
    return result;
  }

  private static <T> JsonArray array(Iterable<T> values, Function<T, JsonElement> converter) {
    JsonArray result = new JsonArray();
    values.forEach(value -> result.add(converter.apply(value)));
    return result;
  }

  private static <T> JsonArray names(Iterable<T> values, Function<T, String> name) {
    return array(values, value -> new JsonPrimitive(name.apply(value)));
  }

  record Engine(
      String id,
      String reportEngine,
      String runner,
      String patternProfile,
      String replacementProfile,
      DeclarativeBenchmarkPlan.EngineDeclaration declaration,
      EnumSet<DeclarativeBenchmarkPlan.Operation> operations,
      EnumSet<DeclarativeBenchmarkPlan.MeasurementMode> measurementModes,
      Set<String> flagSets) {
    Engine {
      operations = operations.clone();
      measurementModes = measurementModes.clone();
      flagSets = Collections.unmodifiableSet(new LinkedHashSet<>(flagSets));
    }

    @Override
    public EnumSet<DeclarativeBenchmarkPlan.Operation> operations() {
      return operations.clone();
    }

    @Override
    public EnumSet<DeclarativeBenchmarkPlan.MeasurementMode> measurementModes() {
      return measurementModes.clone();
    }
  }

  private record Exclusion(String kind, String reason) {}
}
