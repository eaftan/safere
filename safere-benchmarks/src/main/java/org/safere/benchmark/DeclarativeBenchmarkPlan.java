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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Strict, versioned model for the normalized declarative benchmark plan.
 *
 * <p>This model deliberately contains no benchmark-family names. Workload declarations describe
 * required behavior, while engine declarations describe capabilities. {@link #expand} computes
 * their compatibility join.
 */
final class DeclarativeBenchmarkPlan {

  static final int SCHEMA_VERSION = 1;
  private static final java.util.regex.Pattern PLACEHOLDER =
      java.util.regex.Pattern.compile("(?<!\\$)\\{([A-Za-z][A-Za-z0-9_]*)}");

  private final Map<String, InputDeclaration> inputs;
  private final List<WorkloadDeclaration> workloads;

  private DeclarativeBenchmarkPlan(
      Map<String, InputDeclaration> inputs, List<WorkloadDeclaration> workloads) {
    this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
    this.workloads = List.copyOf(workloads);
  }

  static DeclarativeBenchmarkPlan parse(JsonObject json) {
    JsonReader root = new JsonReader("plan", json);
    root.requireOnly("schemaVersion", "inputs", "workloads");
    int schemaVersion = root.requiredInt("schemaVersion");
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException(
          "Unsupported benchmark plan schema version: " + schemaVersion);
    }

    Map<String, InputDeclaration> inputs = parseInputDeclarations(root.requiredArray("inputs"));

    List<WorkloadDeclaration> workloads = new ArrayList<>();
    Set<String> workloadTemplates = new LinkedHashSet<>();
    for (JsonElement element : root.requiredArray("workloads")) {
      WorkloadDeclaration workload = parseWorkload(element);
      if (!workloadTemplates.add(workload.idTemplate())) {
        throw new IllegalArgumentException(
            "Duplicate benchmark workload ID template: " + workload.idTemplate());
      }
      workloads.add(workload);
    }
    if (workloads.isEmpty()) {
      throw new IllegalArgumentException("Benchmark plan requires at least one workload");
    }

    DeclarativeBenchmarkPlan plan = new DeclarativeBenchmarkPlan(inputs, workloads);
    plan.validateReferencesAndIdentities();
    return plan;
  }

  Map<String, InputDeclaration> inputs() {
    return inputs;
  }

  static Map<String, InputDeclaration> parseInputDeclarations(JsonArray declarations) {
    Map<String, InputDeclaration> inputs = new LinkedHashMap<>();
    for (JsonElement element : declarations) {
      for (InputDeclaration input : parseInputs(element)) {
        if (inputs.put(input.id(), input) != null) {
          throw new IllegalArgumentException("Duplicate benchmark input ID: " + input.id());
        }
      }
    }
    return Collections.unmodifiableMap(inputs);
  }

  List<WorkloadDeclaration> workloads() {
    return workloads;
  }

  List<ExpandedWorkload> expandedWorkloads() {
    return expandWorkloads();
  }

  ExpandedPlan expand(List<EngineDeclaration> engines, Set<Operation> implementedOperations) {
    Objects.requireNonNull(engines);
    Objects.requireNonNull(implementedOperations);
    if (engines.isEmpty()) {
      throw new IllegalArgumentException("Benchmark plan expansion requires at least one engine");
    }

    Set<String> engineIds = new LinkedHashSet<>();
    for (EngineDeclaration engine : engines) {
      if (!engineIds.add(engine.id())) {
        throw new IllegalArgumentException("Duplicate benchmark engine ID: " + engine.id());
      }
    }

    List<ExpandedWorkload> expandedWorkloads = expandWorkloads();
    List<Trial> trials = new ArrayList<>();
    List<Exclusion> exclusions = new ArrayList<>();
    for (ExpandedWorkload workload : expandedWorkloads) {
      if (workload.disabledReason() != null) {
        for (EngineDeclaration engine : engines) {
          exclusions.add(
              new Exclusion(
                  workload.id(),
                  engine.id(),
                  ExclusionKind.WORKLOAD_DISABLED,
                  workload.disabledReason()));
        }
        continue;
      }
      for (EngineDeclaration engine : engines) {
        Exclusion exclusion = exclusion(workload, engine, implementedOperations);
        if (exclusion == null) {
          trials.add(new Trial(workload, engine));
        } else {
          exclusions.add(exclusion);
        }
      }
    }

    ExpandedPlan result =
        new ExpandedPlan(expandedWorkloads, List.copyOf(trials), List.copyOf(exclusions));
    result.validateComplete(engines.size());
    return result;
  }

  private static Exclusion exclusion(
      ExpandedWorkload workload, EngineDeclaration engine, Set<Operation> implementedOperations) {
    if (!engine.adapterAvailable()) {
      return new Exclusion(
          workload.id(),
          engine.id(),
          ExclusionKind.MISSING_ENGINE_ADAPTER,
          "engine adapter is not available");
    }
    if (!implementedOperations.contains(workload.operation())) {
      return new Exclusion(
          workload.id(),
          engine.id(),
          ExclusionKind.MISSING_OPERATION_IMPLEMENTATION,
          "operation " + workload.operation().jsonName() + " is not implemented");
    }
    if (!workload.inputRepresentations().contains(engine.inputRepresentation())) {
      return new Exclusion(
          workload.id(),
          engine.id(),
          ExclusionKind.UNSUPPORTED_INPUT_REPRESENTATION,
          "workload does not accept " + engine.inputRepresentation().jsonName());
    }
    EnumSet<Flag> unsupportedFlags = workload.flags().clone();
    unsupportedFlags.removeAll(engine.supportedFlags());
    if (!unsupportedFlags.isEmpty()) {
      return new Exclusion(
          workload.id(),
          engine.id(),
          ExclusionKind.UNSUPPORTED_FLAG,
          "engine lacks flags " + enumNames(unsupportedFlags));
    }
    EnumSet<Feature> missing = workload.requirements().clone();
    missing.removeAll(engine.features());
    if (!missing.isEmpty()) {
      return new Exclusion(
          workload.id(),
          engine.id(),
          ExclusionKind.UNSUPPORTED_FEATURE,
          "engine lacks " + enumNames(missing));
    }
    return null;
  }

  private List<ExpandedWorkload> expandWorkloads() {
    List<ExpandedWorkload> result = new ArrayList<>();
    Set<String> ids = new LinkedHashSet<>();
    for (WorkloadDeclaration workload : workloads) {
      for (Map<String, ParameterValue> parameters : parameterBindings(workload.axes())) {
        String id = substitute(workload.idTemplate(), parameters, "workload ID");
        if (id.isBlank() || id.indexOf('@') >= 0) {
          throw new IllegalArgumentException("Invalid expanded benchmark workload ID: " + id);
        }
        if (!ids.add(id)) {
          throw new IllegalArgumentException("Duplicate expanded benchmark workload ID: " + id);
        }
        List<String> inputIds =
            workload.inputTemplates().stream()
                .map(template -> substitute(template, parameters, id + " input"))
                .toList();
        for (String inputId : inputIds) {
          if (!inputs.containsKey(inputId)) {
            throw new IllegalArgumentException(
                id + " references unknown expanded benchmark input: " + inputId);
          }
        }
        List<String> patterns =
            workload.patternTemplates().stream()
                .map(template -> substitutePattern(template, parameters))
                .toList();
        Map<String, RecipeValue> arguments = new LinkedHashMap<>();
        for (Map.Entry<String, RecipeValue> argument : workload.arguments().entrySet()) {
          arguments.put(
              argument.getKey(), argument.getValue().substitute(parameters, id, argument.getKey()));
        }
        workload.operation().validateArguments(id, arguments);
        ExpectedResult expected =
            workload.expected() == null ? null : workload.expected().substitute(parameters, id);
        result.add(
            new ExpandedWorkload(
                id,
                workload.operation(),
                patterns,
                inputIds,
                parameters,
                arguments,
                workload.flags(),
                workload.requirements(),
                workload.inputRepresentations(),
                workload.inputRepresentationReason(),
                workload.resultConsumption(),
                expected,
                workload.lifecycle(),
                workload.measurement(),
                workload.disabledReason()));
      }
    }
    return List.copyOf(result);
  }

  private void validateReferencesAndIdentities() {
    for (WorkloadDeclaration workload : workloads) {
      Set<String> axisNames = workload.axes().keySet();
      requirePlaceholderCoverage(workload.idTemplate(), axisNames, workload.idTemplate());
      for (String inputTemplate : workload.inputTemplates()) {
        requireKnownPlaceholders(inputTemplate, axisNames, workload.idTemplate() + " input");
      }
    }
    Set<String> referencedInputs = new LinkedHashSet<>();
    for (ExpandedWorkload workload : expandWorkloads()) {
      referencedInputs.addAll(workload.inputIds());
    }
    for (InputDeclaration input : inputs.values()) {
      if (!input.shared() && !referencedInputs.contains(input.id())) {
        throw new IllegalArgumentException(
            "Benchmark input is not referenced by a workload and is not shared: " + input.id());
      }
    }
  }

  private static List<InputDeclaration> parseInputs(JsonElement element) {
    JsonReader input = JsonReader.object("input", element);
    input.requireOnly("id", "axes", "recipe", "shared");
    String idTemplate = input.requiredString("id");
    Map<String, List<ParameterValue>> axes = parseAxes(input.optionalObject("axes"));
    requirePlaceholderCoverage(idTemplate, axes.keySet(), idTemplate);
    InputRecipe recipe = parseRecipe(input.requiredObject("recipe"), idTemplate);
    recipe.validatePlaceholders(idTemplate, axes.keySet());

    List<InputDeclaration> declarations = new ArrayList<>();
    for (Map<String, ParameterValue> parameters : parameterBindings(axes)) {
      String id = substitute(idTemplate, parameters, "input ID");
      requireSimpleId(id, "benchmark input");
      declarations.add(
          new InputDeclaration(
              id,
              recipe.substitute(parameters, id),
              input.optionalBoolean("shared", false),
              parameters));
    }
    return List.copyOf(declarations);
  }

  private static InputRecipe parseRecipe(JsonObject object, String inputId) {
    JsonReader recipe = new JsonReader(inputId + " recipe", object);
    String kindName = recipe.requiredString("kind");
    RecipeKind kind = RecipeKind.fromJson(kindName);
    recipe.requireOnly(kind.allowedFields());
    Map<String, RecipeValue> arguments = new LinkedHashMap<>();
    for (RecipeField field : kind.fields()) {
      JsonElement value = object.get(field.name());
      if (value == null) {
        if (field.required()) {
          throw new IllegalArgumentException(
              inputId + " " + kindName + " recipe requires " + field.name());
        }
        continue;
      }
      arguments.put(
          field.name(), RecipeValue.parse(value, field.type(), inputId + " recipe", field.name()));
    }
    return new InputRecipe(kind, arguments);
  }

  private static WorkloadDeclaration parseWorkload(JsonElement element) {
    JsonReader workload = JsonReader.object("workload", element);
    workload.requireOnly(
        "id",
        "operation",
        "patterns",
        "inputs",
        "axes",
        "arguments",
        "flags",
        "requirements",
        "inputRepresentations",
        "inputRepresentationReason",
        "resultConsumption",
        "expected",
        "lifecycle",
        "measurement",
        "disabledReason");
    String id = workload.requiredString("id");
    Operation operation = Operation.fromJson(workload.requiredString("operation"));
    List<String> patterns = workload.requiredStringList("patterns");
    List<String> inputs = workload.optionalStringList("inputs");
    Map<String, List<ParameterValue>> axes = parseAxes(workload.optionalObject("axes"));
    Map<String, RecipeValue> arguments =
        parseOperationArguments(workload.optionalObject("arguments"), operation, id, axes.keySet());
    EnumSet<Flag> flags = enumSet(workload.optionalStringList("flags"), Flag::fromJson, Flag.class);
    EnumSet<Feature> requirements =
        enumSet(workload.optionalStringList("requirements"), Feature::fromJson, Feature.class);
    boolean representationsDeclared = workload.has("inputRepresentations");
    EnumSet<InputRepresentation> representations;
    if (representationsDeclared) {
      representations =
          enumSet(
              workload.requiredStringList("inputRepresentations"),
              InputRepresentation::fromJson,
              InputRepresentation.class);
    } else {
      representations = EnumSet.allOf(InputRepresentation.class);
    }
    String inputRepresentationReason = workload.optionalString("inputRepresentationReason");
    ResultConsumption consumption =
        ResultConsumption.fromJson(workload.requiredString("resultConsumption"));
    ExpectedResult expected = parseExpected(workload.optionalObject("expected"), axes.keySet());
    MatcherLifecycle lifecycle = parseLifecycle(workload.optionalObject("lifecycle"));
    Measurement measurement = parseMeasurement(workload.requiredObject("measurement"));
    String disabledReason = workload.optionalString("disabledReason");

    if (patterns.isEmpty()) {
      throw new IllegalArgumentException(id + " requires at least one pattern");
    }
    if (representations.isEmpty()) {
      throw new IllegalArgumentException(id + " requires at least one input representation");
    }
    if (representationsDeclared
        && representations.equals(EnumSet.allOf(InputRepresentation.class))) {
      throw new IllegalArgumentException(
          id + " accepts every input representation; omit inputRepresentations instead");
    }
    if (representationsDeclared
        && (inputRepresentationReason == null || inputRepresentationReason.isBlank())) {
      throw new IllegalArgumentException(
          id + " requires a nonblank inputRepresentationReason when inputRepresentations is set");
    }
    if (!representationsDeclared && inputRepresentationReason != null) {
      throw new IllegalArgumentException(
          id + " must not declare inputRepresentationReason without inputRepresentations");
    }
    if (disabledReason != null && disabledReason.isBlank()) {
      throw new IllegalArgumentException(id + " disabledReason must not be blank");
    }
    requirements.addAll(operation.requiredFeatures());
    requirements.addAll(lifecycle.requiredFeatures());
    operation.validate(id, patterns, inputs, consumption, lifecycle, measurement);
    consumption.validateExpected(id, expected);
    return new WorkloadDeclaration(
        id,
        operation,
        patterns,
        inputs,
        axes,
        arguments,
        flags,
        requirements,
        representations,
        inputRepresentationReason,
        consumption,
        expected,
        lifecycle,
        measurement,
        disabledReason);
  }

  private static Map<String, List<ParameterValue>> parseAxes(JsonObject object) {
    if (object == null) {
      return Map.of();
    }
    Map<String, List<ParameterValue>> axes = new LinkedHashMap<>();
    for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
      requireSimpleId(entry.getKey(), "benchmark axis");
      JsonArray values;
      try {
        values = entry.getValue().getAsJsonArray();
      } catch (RuntimeException exception) {
        throw new IllegalArgumentException(
            "Benchmark axis must be an array: " + entry.getKey(), exception);
      }
      if (values.isEmpty()) {
        throw new IllegalArgumentException("Benchmark axis must not be empty: " + entry.getKey());
      }
      List<ParameterValue> parsed = new ArrayList<>();
      Set<String> stableValues = new LinkedHashSet<>();
      for (JsonElement value : values) {
        ParameterValue parameter = ParameterValue.parse(value, entry.getKey());
        if (!stableValues.add(parameter.stableText())) {
          throw new IllegalArgumentException(
              "Duplicate benchmark axis value: " + entry.getKey() + "=" + parameter.stableText());
        }
        parsed.add(parameter);
      }
      axes.put(entry.getKey(), List.copyOf(parsed));
    }
    return Collections.unmodifiableMap(axes);
  }

  private static Map<String, RecipeValue> parseOperationArguments(
      JsonObject object, Operation operation, String workloadId, Set<String> axes) {
    Map<String, RecipeValueType> fieldTypes = operation.argumentTypes();
    Set<String> required = operation.requiredArguments();
    if (object == null) {
      if (!required.isEmpty()) {
        throw new IllegalArgumentException(
            workloadId + " operation " + operation.jsonName() + " requires arguments " + required);
      }
      return Map.of();
    }
    for (String field : object.keySet()) {
      if (!fieldTypes.containsKey(field)) {
        throw new IllegalArgumentException(
            workloadId + " operation " + operation.jsonName() + " has unknown argument: " + field);
      }
    }
    Map<String, RecipeValue> arguments = new LinkedHashMap<>();
    for (Map.Entry<String, RecipeValueType> field : fieldTypes.entrySet()) {
      JsonElement value = object.get(field.getKey());
      if (value == null || value.isJsonNull()) {
        if (required.contains(field.getKey())) {
          throw new IllegalArgumentException(
              workloadId
                  + " operation "
                  + operation.jsonName()
                  + " requires argument "
                  + field.getKey());
        }
        continue;
      }
      RecipeValue argument =
          RecipeValue.parse(value, field.getValue(), workloadId + " arguments", field.getKey());
      Set<String> unknownAxes = new LinkedHashSet<>(argument.placeholders());
      unknownAxes.removeAll(axes);
      if (!unknownAxes.isEmpty()) {
        throw new IllegalArgumentException(
            workloadId
                + " argument "
                + field.getKey()
                + " references unknown axes: "
                + unknownAxes);
      }
      arguments.put(field.getKey(), argument);
    }
    return Collections.unmodifiableMap(arguments);
  }

  private static ExpectedResult parseExpected(JsonObject object, Set<String> axes) {
    if (object == null) {
      return null;
    }
    JsonReader expected = new JsonReader("expected result", object);
    expected.requireOnly("type", "value", "axis");
    ResultType type = ResultType.fromJson(expected.requiredString("type"));
    JsonElement value = object.get("value");
    String axis = expected.optionalString("axis");
    if ((value == null || value.isJsonNull()) == (axis == null)) {
      throw new IllegalArgumentException("Expected result requires exactly one value or axis");
    }
    if (axis != null) {
      if (!axes.contains(axis)) {
        throw new IllegalArgumentException("Expected result references unknown axis: " + axis);
      }
      return new ExpectedResult(type, null, axis);
    }
    type.validate(value);
    return new ExpectedResult(type, value.deepCopy(), null);
  }

  private static MatcherLifecycle parseLifecycle(JsonObject object) {
    if (object == null) {
      return MatcherLifecycle.NONE;
    }
    JsonReader lifecycle = new JsonReader("matcher lifecycle", object);
    lifecycle.requireOnly("matcher", "steps");
    MatcherReuse matcherReuse = MatcherReuse.fromJson(lifecycle.requiredString("matcher"));
    List<LifecycleStep> steps = new ArrayList<>();
    for (JsonElement element : lifecycle.optionalArray("steps")) {
      JsonReader step = JsonReader.object("matcher lifecycle step", element);
      step.requireOnly("kind", "start", "end", "enabled");
      LifecycleStepKind kind = LifecycleStepKind.fromJson(step.requiredString("kind"));
      Integer start = step.optionalInt("start");
      Integer end = step.optionalInt("end");
      Boolean enabled = step.optionalBoolean("enabled");
      kind.validate(start, end, enabled);
      steps.add(new LifecycleStep(kind, start, end, enabled));
    }
    if (matcherReuse == MatcherReuse.NONE && !steps.isEmpty()) {
      throw new IllegalArgumentException("Matcher lifecycle NONE cannot declare steps");
    }
    return new MatcherLifecycle(matcherReuse, steps);
  }

  private static Measurement parseMeasurement(JsonObject object) {
    JsonReader measurement = new JsonReader("measurement", object);
    measurement.requireOnly("mode", "timingUnit", "constraints");
    MeasurementMode mode = MeasurementMode.fromJson(measurement.requiredString("mode"));
    TimingUnit timingUnit = TimingUnit.fromJson(measurement.requiredString("timingUnit"));
    EnumSet<ExecutionConstraint> constraints =
        enumSet(
            measurement.optionalStringList("constraints"),
            ExecutionConstraint::fromJson,
            ExecutionConstraint.class);
    mode.validate(timingUnit, constraints);
    return new Measurement(mode, timingUnit, constraints);
  }

  private static <E extends Enum<E>> EnumSet<E> enumSet(
      List<String> names, EnumParser<E> parser, Class<E> type) {
    EnumSet<E> result = EnumSet.noneOf(type);
    for (String name : names) {
      E value = parser.parse(name);
      if (!result.add(value)) {
        throw new IllegalArgumentException("Duplicate declaration value: " + name);
      }
    }
    return result;
  }

  private static List<Map<String, ParameterValue>> parameterBindings(
      Map<String, List<ParameterValue>> axes) {
    List<Map<String, ParameterValue>> bindings = new ArrayList<>();
    bindings.add(new LinkedHashMap<>());
    for (Map.Entry<String, List<ParameterValue>> axis : axes.entrySet()) {
      List<Map<String, ParameterValue>> next = new ArrayList<>();
      for (Map<String, ParameterValue> binding : bindings) {
        for (ParameterValue value : axis.getValue()) {
          Map<String, ParameterValue> expanded = new LinkedHashMap<>(binding);
          expanded.put(axis.getKey(), value);
          next.add(expanded);
        }
      }
      bindings = next;
    }
    return bindings.stream()
        .map(binding -> Collections.unmodifiableMap(new LinkedHashMap<>(binding)))
        .toList();
  }

  private static String substitute(
      String template, Map<String, ParameterValue> parameters, String context) {
    return substitute(template, parameters, context, true);
  }

  private static String substituteValue(
      String template, Map<String, ParameterValue> parameters, String context) {
    return substitute(template, parameters, context, false);
  }

  private static String substitute(
      String template,
      Map<String, ParameterValue> parameters,
      String context,
      boolean useStableText) {
    Matcher matcher = PLACEHOLDER.matcher(template);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      ParameterValue value = parameters.get(matcher.group(1));
      if (value == null) {
        throw new IllegalArgumentException(
            context + " references unknown axis: " + matcher.group(1));
      }
      String replacement = useStableText ? value.stableText() : value.valueText();
      matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  private static String substitutePattern(String template, Map<String, ParameterValue> parameters) {
    Matcher matcher = PLACEHOLDER.matcher(template);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      ParameterValue value = parameters.get(matcher.group(1));
      if (value == null) {
        matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
      } else {
        matcher.appendReplacement(result, Matcher.quoteReplacement(value.valueText()));
      }
    }
    matcher.appendTail(result);
    return result.toString();
  }

  private static void requirePlaceholderCoverage(
      String template, Set<String> axes, String context) {
    Set<String> placeholders = placeholders(template);
    requireKnownPlaceholders(placeholders, axes, context);
    if (!placeholders.containsAll(axes)) {
      Set<String> missing = new LinkedHashSet<>(axes);
      missing.removeAll(placeholders);
      throw new IllegalArgumentException(
          context + " ID omits expansion axes and would not be stable: " + missing);
    }
  }

  private static void requireKnownPlaceholders(String template, Set<String> axes, String context) {
    requireKnownPlaceholders(placeholders(template), axes, context);
  }

  private static void requireKnownPlaceholders(
      Set<String> placeholders, Set<String> axes, String context) {
    Set<String> unknown = new LinkedHashSet<>(placeholders);
    unknown.removeAll(axes);
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException(context + " references unknown axes: " + unknown);
    }
  }

  private static Set<String> placeholders(String template) {
    Set<String> placeholders = new LinkedHashSet<>();
    Matcher matcher = PLACEHOLDER.matcher(template);
    while (matcher.find()) {
      placeholders.add(matcher.group(1));
    }
    return placeholders;
  }

  private static void requireSimpleId(String id, String kind) {
    if (id == null || id.isBlank() || !id.matches("[A-Za-z][A-Za-z0-9_.-]*")) {
      throw new IllegalArgumentException("Invalid " + kind + " ID: " + id);
    }
  }

  private static String enumNames(Set<? extends JsonNamed> values) {
    return values.stream().map(JsonNamed::jsonName).sorted().toList().toString();
  }

  record InputDeclaration(
      String id, InputRecipe recipe, boolean shared, Map<String, ParameterValue> parameters) {
    InputDeclaration {
      Objects.requireNonNull(id);
      Objects.requireNonNull(recipe);
      parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }
  }

  record InputRecipe(RecipeKind kind, Map<String, RecipeValue> arguments) {
    InputRecipe {
      Objects.requireNonNull(kind);
      arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
      if (arguments.values().stream().noneMatch(RecipeAxisReference.class::isInstance)) {
        kind.validate(arguments);
      }
    }

    void validatePlaceholders(String inputId, Set<String> axes) {
      for (Map.Entry<String, RecipeValue> argument : arguments.entrySet()) {
        Set<String> unknown = new LinkedHashSet<>(argument.getValue().placeholders());
        unknown.removeAll(axes);
        if (!unknown.isEmpty()) {
          throw new IllegalArgumentException(
              inputId
                  + " recipe argument "
                  + argument.getKey()
                  + " references unknown axes: "
                  + unknown);
        }
      }
    }

    InputRecipe substitute(Map<String, ParameterValue> parameters, String inputId) {
      Map<String, RecipeValue> resolved = new LinkedHashMap<>();
      for (Map.Entry<String, RecipeValue> argument : arguments.entrySet()) {
        resolved.put(
            argument.getKey(),
            argument.getValue().substitute(parameters, inputId, argument.getKey()));
      }
      return new InputRecipe(kind, resolved);
    }
  }

  record WorkloadDeclaration(
      String idTemplate,
      Operation operation,
      List<String> patternTemplates,
      List<String> inputTemplates,
      Map<String, List<ParameterValue>> axes,
      Map<String, RecipeValue> arguments,
      EnumSet<Flag> flags,
      EnumSet<Feature> requirements,
      EnumSet<InputRepresentation> inputRepresentations,
      String inputRepresentationReason,
      ResultConsumption resultConsumption,
      ExpectedResult expected,
      MatcherLifecycle lifecycle,
      Measurement measurement,
      String disabledReason) {
    WorkloadDeclaration {
      patternTemplates = List.copyOf(patternTemplates);
      inputTemplates = List.copyOf(inputTemplates);
      axes = Collections.unmodifiableMap(new LinkedHashMap<>(axes));
      arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
      flags = flags.clone();
      requirements = requirements.clone();
      inputRepresentations = inputRepresentations.clone();
    }

    @Override
    public EnumSet<Flag> flags() {
      return flags.clone();
    }

    @Override
    public EnumSet<Feature> requirements() {
      return requirements.clone();
    }

    @Override
    public EnumSet<InputRepresentation> inputRepresentations() {
      return inputRepresentations.clone();
    }
  }

  record ExpandedWorkload(
      String id,
      Operation operation,
      List<String> patterns,
      List<String> inputIds,
      Map<String, ParameterValue> parameters,
      Map<String, RecipeValue> arguments,
      EnumSet<Flag> flags,
      EnumSet<Feature> requirements,
      EnumSet<InputRepresentation> inputRepresentations,
      String inputRepresentationReason,
      ResultConsumption resultConsumption,
      ExpectedResult expected,
      MatcherLifecycle lifecycle,
      Measurement measurement,
      String disabledReason) {
    ExpandedWorkload {
      patterns = List.copyOf(patterns);
      inputIds = List.copyOf(inputIds);
      parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
      arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
      flags = flags.clone();
      requirements = requirements.clone();
      inputRepresentations = inputRepresentations.clone();
    }

    @Override
    public EnumSet<Flag> flags() {
      return flags.clone();
    }

    @Override
    public EnumSet<Feature> requirements() {
      return requirements.clone();
    }

    @Override
    public EnumSet<InputRepresentation> inputRepresentations() {
      return inputRepresentations.clone();
    }
  }

  record ExpectedResult(ResultType type, JsonElement value, String axisReference) {
    ExpectedResult {
      Objects.requireNonNull(type);
      if ((value == null) == (axisReference == null)) {
        throw new IllegalArgumentException(
            "Expected result requires exactly one value or axis reference");
      }
      if (value != null) {
        value = value.deepCopy();
      }
    }

    @Override
    public JsonElement value() {
      return value == null ? null : value.deepCopy();
    }

    ExpectedResult substitute(Map<String, ParameterValue> parameters, String workloadId) {
      if (axisReference == null) {
        return this;
      }
      ParameterValue parameter = parameters.get(axisReference);
      if (parameter == null) {
        throw new IllegalArgumentException(
            workloadId + " expected result references unknown axis: " + axisReference);
      }
      JsonElement resolved =
          switch (parameter.type()) {
            case STRING -> new JsonPrimitive((String) parameter.value());
            case INTEGER -> new JsonPrimitive((Integer) parameter.value());
            case BOOLEAN -> new JsonPrimitive((Boolean) parameter.value());
          };
      type.validate(resolved);
      return new ExpectedResult(type, resolved, null);
    }
  }

  record MatcherLifecycle(MatcherReuse matcher, List<LifecycleStep> steps) {
    static final MatcherLifecycle NONE = new MatcherLifecycle(MatcherReuse.NONE, List.of());

    MatcherLifecycle {
      steps = List.copyOf(steps);
    }

    EnumSet<Feature> requiredFeatures() {
      EnumSet<Feature> result = EnumSet.noneOf(Feature.class);
      if (matcher != MatcherReuse.NONE) {
        result.add(Feature.MATCHER_STATE);
      }
      for (LifecycleStep step : steps) {
        switch (step.kind()) {
          case RESET -> result.add(Feature.MATCHER_STATE);
          case REGION -> result.add(Feature.REGIONS);
          case TRANSPARENT_BOUNDS, ANCHORING_BOUNDS -> result.add(Feature.BOUNDS);
        }
      }
      return result;
    }
  }

  record LifecycleStep(LifecycleStepKind kind, Integer start, Integer end, Boolean enabled) {}

  record Measurement(
      MeasurementMode mode, TimingUnit timingUnit, EnumSet<ExecutionConstraint> constraints) {
    Measurement {
      constraints = constraints.clone();
    }

    @Override
    public EnumSet<ExecutionConstraint> constraints() {
      return constraints.clone();
    }
  }

  record EngineDeclaration(
      String id,
      InputRepresentation inputRepresentation,
      EnumSet<Feature> features,
      EnumSet<Flag> supportedFlags,
      boolean adapterAvailable) {
    EngineDeclaration {
      requireSimpleId(id, "benchmark engine");
      Objects.requireNonNull(inputRepresentation);
      features = features.clone();
      supportedFlags = supportedFlags.clone();
    }

    @Override
    public EnumSet<Feature> features() {
      return features.clone();
    }

    @Override
    public EnumSet<Flag> supportedFlags() {
      return supportedFlags.clone();
    }
  }

  record Trial(ExpandedWorkload workload, EngineDeclaration engine) {
    String id() {
      return workload.id() + "@" + engine.id();
    }
  }

  record Exclusion(String workloadId, String engineId, ExclusionKind kind, String reason) {}

  record ExpandedPlan(
      List<ExpandedWorkload> workloads, List<Trial> trials, List<Exclusion> exclusions) {
    ExpandedPlan {
      workloads = List.copyOf(workloads);
      trials = List.copyOf(trials);
      exclusions = List.copyOf(exclusions);
    }

    private void validateComplete(int engineCount) {
      Map<String, Integer> scheduled = new LinkedHashMap<>();
      for (Trial trial : trials) {
        scheduled.merge(trial.workload().id(), 1, Integer::sum);
      }
      Map<String, Integer> excluded = new LinkedHashMap<>();
      for (Exclusion exclusion : exclusions) {
        excluded.merge(exclusion.workloadId(), 1, Integer::sum);
      }
      for (ExpandedWorkload workload : workloads) {
        int accounted =
            scheduled.getOrDefault(workload.id(), 0) + excluded.getOrDefault(workload.id(), 0);
        if (accounted != engineCount) {
          throw new IllegalStateException(
              workload.id()
                  + " has "
                  + accounted
                  + " accounted engine pairs, expected "
                  + engineCount);
        }
        if (scheduled.getOrDefault(workload.id(), 0) == 0 && workload.disabledReason() == null) {
          List<ExclusionKind> reasons =
              exclusions.stream()
                  .filter(exclusion -> exclusion.workloadId().equals(workload.id()))
                  .map(Exclusion::kind)
                  .distinct()
                  .toList();
          throw new IllegalArgumentException(
              workload.id()
                  + " produces no supported trials and has no disabledReason; exclusions: "
                  + reasons);
        }
      }
    }
  }

  sealed interface RecipeValue
      permits RecipeString,
          RecipeInteger,
          RecipeStringList,
          RecipeIntegerList,
          RecipeAxisReference {
    static RecipeValue parse(
        JsonElement value, RecipeValueType type, String context, String field) {
      try {
        if (type != RecipeValueType.STRING
            && value.isJsonPrimitive()
            && value.getAsJsonPrimitive().isString()) {
          String reference = value.getAsString();
          Matcher matcher = PLACEHOLDER.matcher(reference);
          if (matcher.matches()) {
            return new RecipeAxisReference(matcher.group(1), type);
          }
        }
        return switch (type) {
          case STRING -> {
            if (!isString(value)) {
              throw new IllegalArgumentException();
            }
            yield new RecipeString(value.getAsString());
          }
          case INTEGER -> {
            if (!isIntegerPrimitive(value)) {
              throw new IllegalArgumentException();
            }
            yield new RecipeInteger(value.getAsBigDecimal().intValueExact());
          }
          case STRING_LIST -> {
            List<String> values = new ArrayList<>();
            value
                .getAsJsonArray()
                .forEach(
                    item -> {
                      if (!isString(item)) {
                        throw new IllegalArgumentException();
                      }
                      values.add(item.getAsString());
                    });
            yield new RecipeStringList(values);
          }
          case INTEGER_LIST -> {
            List<Integer> values = new ArrayList<>();
            value
                .getAsJsonArray()
                .forEach(
                    item -> {
                      if (!isIntegerPrimitive(item)) {
                        throw new IllegalArgumentException();
                      }
                      values.add(item.getAsBigDecimal().intValueExact());
                    });
            yield new RecipeIntegerList(values);
          }
        };
      } catch (RuntimeException exception) {
        throw new IllegalArgumentException(
            context + " field " + field + " must be " + type.jsonName(), exception);
      }
    }

    Set<String> placeholders();

    RecipeValue substitute(
        Map<String, ParameterValue> parameters, String inputId, String argumentName);
  }

  record RecipeString(String value) implements RecipeValue {
    @Override
    public Set<String> placeholders() {
      return DeclarativeBenchmarkPlan.placeholders(value);
    }

    @Override
    public RecipeValue substitute(
        Map<String, ParameterValue> parameters, String inputId, String argumentName) {
      return new RecipeString(
          DeclarativeBenchmarkPlan.substituteValue(
              value, parameters, inputId + " recipe " + argumentName));
    }
  }

  record RecipeInteger(int value) implements RecipeValue {
    @Override
    public Set<String> placeholders() {
      return Set.of();
    }

    @Override
    public RecipeValue substitute(
        Map<String, ParameterValue> parameters, String inputId, String argumentName) {
      return this;
    }
  }

  record RecipeStringList(List<String> values) implements RecipeValue {
    RecipeStringList {
      values = List.copyOf(values);
    }

    @Override
    public Set<String> placeholders() {
      Set<String> result = new LinkedHashSet<>();
      values.forEach(value -> result.addAll(DeclarativeBenchmarkPlan.placeholders(value)));
      return result;
    }

    @Override
    public RecipeValue substitute(
        Map<String, ParameterValue> parameters, String inputId, String argumentName) {
      return new RecipeStringList(
          values.stream()
              .map(
                  value ->
                      DeclarativeBenchmarkPlan.substituteValue(
                          value, parameters, inputId + " recipe " + argumentName))
              .toList());
    }
  }

  record RecipeIntegerList(List<Integer> values) implements RecipeValue {
    RecipeIntegerList {
      values = List.copyOf(values);
    }

    @Override
    public Set<String> placeholders() {
      return Set.of();
    }

    @Override
    public RecipeValue substitute(
        Map<String, ParameterValue> parameters, String inputId, String argumentName) {
      return this;
    }
  }

  record RecipeAxisReference(String axis, RecipeValueType type) implements RecipeValue {
    @Override
    public Set<String> placeholders() {
      return Set.of(axis);
    }

    @Override
    public RecipeValue substitute(
        Map<String, ParameterValue> parameters, String inputId, String argumentName) {
      ParameterValue value = parameters.get(axis);
      if (value == null) {
        throw new IllegalArgumentException(
            inputId + " recipe " + argumentName + " references unknown axis: " + axis);
      }
      return switch (type) {
        case INTEGER -> {
          if (value.type() != ParameterType.INTEGER) {
            throw new IllegalArgumentException(
                inputId + " recipe " + argumentName + " requires integer axis " + axis);
          }
          yield new RecipeInteger((Integer) value.value());
        }
        case STRING -> new RecipeString(value.valueText());
        case STRING_LIST, INTEGER_LIST ->
            throw new IllegalArgumentException(
                inputId + " recipe " + argumentName + " cannot reference a scalar axis");
      };
    }
  }

  record ParameterValue(ParameterType type, Object value, String stableText) {
    static ParameterValue parse(JsonElement element, String axis) {
      return parse(element, axis, true);
    }

    private static ParameterValue parse(
        JsonElement element, String axis, boolean validateStableValue) {
      if (element.isJsonObject()) {
        JsonReader labeled = JsonReader.object("benchmark axis value", element);
        labeled.requireOnly("id", "value");
        String id = labeled.requiredString("id");
        validateStableText(id);
        ParameterValue value = parse(element.getAsJsonObject().get("value"), axis, false);
        return new ParameterValue(value.type(), value.value(), id);
      }
      if (!element.isJsonPrimitive()) {
        throw new IllegalArgumentException(
            "Benchmark axis values must be scalars or labeled {id,value} objects: " + axis);
      }
      JsonPrimitive primitive = element.getAsJsonPrimitive();
      if (primitive.isBoolean()) {
        boolean value = primitive.getAsBoolean();
        return new ParameterValue(ParameterType.BOOLEAN, value, Boolean.toString(value));
      }
      if (primitive.isNumber()) {
        try {
          int value = primitive.getAsBigDecimal().intValueExact();
          return new ParameterValue(ParameterType.INTEGER, value, Integer.toString(value));
        } catch (ArithmeticException | NumberFormatException exception) {
          throw new IllegalArgumentException(
              "Benchmark axis numeric values must be integers: " + axis, exception);
        }
      }
      String value = primitive.getAsString();
      if (validateStableValue) {
        validateStableText(value);
      }
      return new ParameterValue(ParameterType.STRING, value, value);
    }

    private static void validateStableText(String value) {
      if (value.isBlank() || value.contains("@") || value.contains("{") || value.contains("}")) {
        throw new IllegalArgumentException("Invalid stable benchmark axis value: " + value);
      }
    }

    String valueText() {
      return String.valueOf(value);
    }
  }

  enum ParameterType {
    STRING,
    INTEGER,
    BOOLEAN
  }

  enum RecipeKind implements JsonNamed {
    LITERAL("literal", field("text", RecipeValueType.STRING)),
    REPEAT(
        "repeat", field("value", RecipeValueType.STRING), field("count", RecipeValueType.INTEGER)),
    REPEAT_TO_LENGTH(
        "repeatToLength",
        field("unit", RecipeValueType.STRING),
        field("length", RecipeValueType.INTEGER)),
    REPEAT_AT_LEAST_LENGTH(
        "repeatAtLeastLength",
        field("unit", RecipeValueType.STRING),
        field("minimumLength", RecipeValueType.INTEGER)),
    DELIMITED_REPEAT_TO_LENGTH(
        "delimitedRepeatToLength",
        field("value", RecipeValueType.STRING),
        field("delimiterAlphabet", RecipeValueType.STRING),
        field("seed", RecipeValueType.INTEGER),
        field("length", RecipeValueType.INTEGER)),
    APPEND_INPUT(
        "appendInput",
        field("input", RecipeValueType.STRING),
        field("suffix", RecipeValueType.STRING)),
    RANDOM_CHARS(
        "randomChars",
        field("alphabet", RecipeValueType.STRING),
        field("length", RecipeValueType.INTEGER),
        field("seed", RecipeValueType.INTEGER)),
    RANDOM_CODE_POINTS(
        "randomCodePoints",
        field("codePoints", RecipeValueType.INTEGER_LIST),
        field("minimumCodeUnits", RecipeValueType.INTEGER),
        field("seed", RecipeValueType.INTEGER)),
    SURROUND_TO_LENGTH(
        "surroundToLength",
        field("prefix", RecipeValueType.STRING),
        field("unit", RecipeValueType.STRING),
        field("suffix", RecipeValueType.STRING),
        field("length", RecipeValueType.INTEGER)),
    SUFFIX_TO_LENGTH(
        "suffixToLength",
        field("prefixUnit", RecipeValueType.STRING),
        field("suffix", RecipeValueType.STRING),
        field("length", RecipeValueType.INTEGER)),
    PREFIXED_REPEAT_TO_LENGTH(
        "prefixedRepeatToLength",
        field("prefix", RecipeValueType.STRING),
        field("value", RecipeValueType.STRING),
        field("delimiterAlphabet", RecipeValueType.STRING),
        field("seed", RecipeValueType.INTEGER),
        field("length", RecipeValueType.INTEGER)),
    SPARSE_MATCH_TO_LENGTH(
        "sparseMatchToLength",
        field("match", RecipeValueType.STRING),
        field("nonMatch", RecipeValueType.STRING),
        field("nonMatchRepeats", RecipeValueType.INTEGER),
        field("delimiterAlphabet", RecipeValueType.STRING),
        field("seed", RecipeValueType.INTEGER),
        field("length", RecipeValueType.INTEGER)),
    CENTER_IN_SPACES(
        "centerInSpaces",
        field("body", RecipeValueType.STRING),
        field("length", RecipeValueType.INTEGER)),
    SCALED_CENTER_IN_SPACES(
        "scaledCenterInSpaces",
        field("bodyPrefix", RecipeValueType.STRING),
        field("bodySuffix", RecipeValueType.STRING),
        field("bodyFill", RecipeValueType.STRING),
        field("bodyScalePercent", RecipeValueType.INTEGER),
        field("length", RecipeValueType.INTEGER)),
    LAZY_ALTERNATION_TO_LENGTH(
        "lazyAlternationToLength",
        field("prefixUnit", RecipeValueType.STRING),
        field("match", RecipeValueType.STRING),
        field("suffixUnit", RecipeValueType.STRING),
        field("length", RecipeValueType.INTEGER)),
    PERIODIC_ALTERNATION_TO_LENGTH(
        "periodicAlternationToLength",
        field("hitUnit", RecipeValueType.STRING),
        field("missUnit", RecipeValueType.STRING),
        field("hitInterval", RecipeValueType.INTEGER),
        field("length", RecipeValueType.INTEGER)),
    OPTIONAL_REQUIRED_REPEAT_PATTERN(
        "optionalRequiredRepeatPattern",
        field("literal", RecipeValueType.STRING),
        field("count", RecipeValueType.INTEGER));

    private final String jsonName;
    private final List<RecipeField> fields;

    RecipeKind(String jsonName, RecipeField... fields) {
      this.jsonName = jsonName;
      this.fields = List.of(fields);
    }

    @Override
    public String jsonName() {
      return jsonName;
    }

    List<RecipeField> fields() {
      return fields;
    }

    String[] allowedFields() {
      List<String> names = new ArrayList<>();
      names.add("kind");
      fields.forEach(field -> names.add(field.name()));
      return names.toArray(String[]::new);
    }

    void validate(Map<String, RecipeValue> arguments) {
      switch (this) {
        case LITERAL -> {}
        case REPEAT -> requireNonNegative(arguments, "count");
        case REPEAT_TO_LENGTH -> {
          requireNonNegative(arguments, "length");
          requireUnitWhenOutputIsRequired(arguments, "unit", "length", 0);
        }
        case REPEAT_AT_LEAST_LENGTH -> {
          requireNonNegative(arguments, "minimumLength");
          requireUnitWhenOutputIsRequired(arguments, "unit", "minimumLength", 0);
        }
        case DELIMITED_REPEAT_TO_LENGTH -> {
          requireNonNegative(arguments, "length");
          requireNonEmpty(arguments, "value");
          requireNonEmpty(arguments, "delimiterAlphabet");
        }
        case APPEND_INPUT -> requireNonEmpty(arguments, "input");
        case RANDOM_CHARS -> {
          requireNonNegative(arguments, "length");
          requireNonEmpty(arguments, "alphabet");
        }
        case RANDOM_CODE_POINTS -> {
          requireNonNegative(arguments, "minimumCodeUnits");
          List<Integer> codePoints = integers(arguments, "codePoints");
          if (codePoints.isEmpty()
              || codePoints.stream()
                  .anyMatch(
                      codePoint ->
                          !Character.isValidCodePoint(codePoint)
                              || (codePoint >= Character.MIN_SURROGATE
                                  && codePoint <= Character.MAX_SURROGATE))) {
            throw new IllegalArgumentException(
                "randomCodePoints recipe requires valid Unicode scalar codePoints");
          }
        }
        case SURROUND_TO_LENGTH -> {
          requireNonNegative(arguments, "length");
          int fixedLength =
              string(arguments, "prefix").length() + string(arguments, "suffix").length();
          requireUnitWhenOutputIsRequired(arguments, "unit", "length", fixedLength);
        }
        case SUFFIX_TO_LENGTH -> {
          requireNonNegative(arguments, "length");
          requireUnitWhenOutputIsRequired(
              arguments, "prefixUnit", "length", string(arguments, "suffix").length());
        }
        case PREFIXED_REPEAT_TO_LENGTH -> {
          requireNonNegative(arguments, "length");
          requireNonEmpty(arguments, "value");
          requireNonEmpty(arguments, "delimiterAlphabet");
        }
        case SPARSE_MATCH_TO_LENGTH -> {
          requireNonNegative(arguments, "length");
          requireNonEmpty(arguments, "match");
          requireNonEmpty(arguments, "nonMatch");
          requirePositive(arguments, "nonMatchRepeats");
          requireNonEmpty(arguments, "delimiterAlphabet");
        }
        case CENTER_IN_SPACES -> requireNonNegative(arguments, "length");
        case SCALED_CENTER_IN_SPACES -> {
          requireNonNegative(arguments, "length");
          requireNonEmpty(arguments, "bodyFill");
          int percent = integer(arguments, "bodyScalePercent");
          if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException(
                "scaledCenterInSpaces recipe bodyScalePercent must be between 0 and 100");
          }
        }
        case LAZY_ALTERNATION_TO_LENGTH -> {
          requireNonNegative(arguments, "length");
          requireNonEmpty(arguments, "prefixUnit");
          requireNonEmpty(arguments, "match");
          requireNonEmpty(arguments, "suffixUnit");
        }
        case PERIODIC_ALTERNATION_TO_LENGTH -> {
          requireNonNegative(arguments, "length");
          requireNonEmpty(arguments, "hitUnit");
          requireNonEmpty(arguments, "missUnit");
          requirePositive(arguments, "hitInterval");
        }
        case OPTIONAL_REQUIRED_REPEAT_PATTERN -> {
          requireNonEmpty(arguments, "literal");
          requireNonNegative(arguments, "count");
        }
      }
    }

    private static void requireUnitWhenOutputIsRequired(
        Map<String, RecipeValue> arguments, String unitField, String lengthField, int fixedLength) {
      if (integer(arguments, lengthField) > fixedLength) {
        requireNonEmpty(arguments, unitField);
      }
    }

    private static void requireNonEmpty(Map<String, RecipeValue> arguments, String field) {
      if (string(arguments, field).isEmpty()) {
        throw new IllegalArgumentException("Recipe field " + field + " must not be empty");
      }
    }

    private static void requireNonNegative(Map<String, RecipeValue> arguments, String field) {
      if (integer(arguments, field) < 0) {
        throw new IllegalArgumentException("Recipe field " + field + " must not be negative");
      }
    }

    private static void requirePositive(Map<String, RecipeValue> arguments, String field) {
      if (integer(arguments, field) <= 0) {
        throw new IllegalArgumentException("Recipe field " + field + " must be positive");
      }
    }

    private static String string(Map<String, RecipeValue> arguments, String field) {
      return ((RecipeString) arguments.get(field)).value();
    }

    private static int integer(Map<String, RecipeValue> arguments, String field) {
      return ((RecipeInteger) arguments.get(field)).value();
    }

    private static List<Integer> integers(Map<String, RecipeValue> arguments, String field) {
      return ((RecipeIntegerList) arguments.get(field)).values();
    }

    static RecipeKind fromJson(String name) {
      return enumFromJson(values(), name, "input recipe kind");
    }
  }

  record RecipeField(String name, RecipeValueType type, boolean required) {}

  private static RecipeField field(String name, RecipeValueType type) {
    return new RecipeField(name, type, true);
  }

  enum RecipeValueType implements JsonNamed {
    STRING("a string"),
    INTEGER("an integer"),
    STRING_LIST("an array of strings"),
    INTEGER_LIST("an array of integers");

    private final String jsonName;

    RecipeValueType(String jsonName) {
      this.jsonName = jsonName;
    }

    @Override
    public String jsonName() {
      return jsonName;
    }
  }

  enum Operation implements JsonNamed {
    MATCHES("matches", false, true, Feature.MATCHES),
    FIND("find", false, true, Feature.FIND),
    LOOKING_AT("lookingAt", true, true, Feature.LOOKING_AT),
    FIND_ALL_COUNT("findAllCount", false, true, Feature.FIND),
    FIND_ALL_LENGTH_SUM("findAllLengthSum", false, true, Feature.FIND, Feature.CAPTURE_TEXT),
    FIND_ALL_GROUP_LENGTH_SUM(
        "findAllGroupLengthSum", false, true, Feature.FIND, Feature.CAPTURE_TEXT),
    MATCHES_CORPUS("matchesCorpus", false, true, Feature.MATCHES),
    MATCHES_GROUP_LENGTH_SUM(
        "matchesGroupLengthSum", false, true, Feature.MATCHES, Feature.CAPTURE_TEXT),
    FIND_GROUP_PRESENT(
        "findGroupPresent", false, true, Feature.FIND, Feature.CAPTURE_PARTICIPATION),
    FIND_GROUP("findGroup", false, true, Feature.FIND, Feature.CAPTURE_TEXT),
    CAPTURE_GROUPS("captureGroups", false, true, Feature.MATCHES, Feature.CAPTURE_TEXT),
    REPLACE_FIRST("replaceFirst", false, true, Feature.REPLACE),
    REPLACE_ALL("replaceAll", false, true, Feature.REPLACE),
    REPLACE_ALL_LENGTH_SUM("replaceAllLengthSum", false, true, Feature.REPLACE),
    APPEND_REPLACEMENT("appendReplacement", false, true, Feature.REPLACE),
    MANUAL_REPLACE_ALL(
        "manualReplaceAll", false, true, Feature.REPLACE, Feature.APPEND_REPLACEMENT),
    SPLIT("split", false, true, Feature.SPLIT),
    SPLIT_LENGTH_SUM("splitLengthSum", false, true, Feature.SPLIT),
    COMPILE("compile", false, false),
    COMPILE_AND_FIND("compileAndFind", false, true, Feature.FIND),
    FIND_ROTATING_UTF16("findRotatingUtf16", false, false, Feature.FIND),
    COMPILE_AND_FIND_ROTATING_UTF16("compileAndFindRotatingUtf16", false, false, Feature.FIND),
    MATCHER_CONSTRUCTION("matcherConstruction", false, true, Feature.UTF8_INPUT),
    MATCHER_RESET_FIND("matcherResetFind", true, true, Feature.FIND, Feature.MATCHER_STATE),
    MATCHER_REGION_FIND(
        "matcherRegionFind", true, true, Feature.FIND, Feature.MATCHER_STATE, Feature.REGIONS),
    FIND_IN_WINDOW("findInWindow", true, true, Feature.FIND, Feature.MATCHER_STATE),
    PATTERN_SET_COMPILE("patternSetCompile", false, false, Feature.PATTERN_SET),
    PATTERN_SET_FIND("patternSetFind", false, true, Feature.PATTERN_SET),
    PATTERN_SET_MATCHES("patternSetMatches", false, true, Feature.PATTERN_SET),
    UTF8_CAPTURE_BOUNDS("utf8CaptureBounds", false, true, Feature.FIND, Feature.UTF8_INPUT),
    UTF8_DECODE_FIND("utf8DecodeFind", false, true, Feature.FIND, Feature.UTF8_INPUT),
    UTF8_REPLACEMENT("utf8Replacement", false, true, Feature.UTF8_INPUT, Feature.UTF8_REPLACEMENT),
    ANALYZE_PATTERN("analyzePattern", false, false, Feature.DIAGNOSTICS),
    CACHED_ANALYSIS("cachedAnalysis", false, false, Feature.DIAGNOSTICS),
    COMPILE_AND_ANALYZE("compileAndAnalyze", false, false, Feature.DIAGNOSTICS),
    DFA_CACHE_GROWTH("dfaCacheGrowth", false, true, Feature.DFA_CACHE),
    DIAGNOSTICS_FIND("diagnosticsFind", false, true, Feature.FIND, Feature.DIAGNOSTICS);

    private final String jsonName;
    private final boolean lifecycleRequired;
    private final boolean inputRequired;
    private final EnumSet<Feature> requiredFeatures;

    Operation(
        String jsonName,
        boolean lifecycleRequired,
        boolean inputRequired,
        Feature... requiredFeatures) {
      this.jsonName = jsonName;
      this.lifecycleRequired = lifecycleRequired;
      this.inputRequired = inputRequired;
      this.requiredFeatures = EnumSet.noneOf(Feature.class);
      Collections.addAll(this.requiredFeatures, requiredFeatures);
    }

    @Override
    public String jsonName() {
      return jsonName;
    }

    void validate(
        String id,
        List<String> patterns,
        List<String> inputs,
        ResultConsumption consumption,
        MatcherLifecycle lifecycle,
        Measurement measurement) {
      if (inputRequired && inputs.isEmpty()) {
        throw new IllegalArgumentException(id + " operation " + jsonName + " requires inputs");
      }
      if (!inputRequired
          && !inputs.isEmpty()
          && (this == COMPILE
              || this == PATTERN_SET_COMPILE
              || this == ANALYZE_PATTERN
              || this == CACHED_ANALYSIS
              || this == COMPILE_AND_ANALYZE)) {
        throw new IllegalArgumentException(
            id + " operation " + jsonName + " must not declare inputs");
      }
      if (lifecycleRequired && lifecycle.matcher() == MatcherReuse.NONE) {
        throw new IllegalArgumentException(id + " operation " + jsonName + " requires lifecycle");
      }
      if (!lifecycleRequired
          && lifecycle.matcher() != MatcherReuse.NONE
          && this != FIND
          && this != FIND_ALL_COUNT
          && this != DIAGNOSTICS_FIND) {
        throw new IllegalArgumentException(
            id + " operation " + jsonName + " is incompatible with matcher lifecycle");
      }
      if ((this == PATTERN_SET_COMPILE || this == PATTERN_SET_FIND || this == PATTERN_SET_MATCHES)
          && patterns.size() < 2) {
        throw new IllegalArgumentException(
            id + " pattern-set operation requires multiple patterns");
      }
      if (!supports(consumption)) {
        throw new IllegalArgumentException(
            id
                + " operation "
                + jsonName
                + " cannot use result consumption "
                + consumption.jsonName());
      }
      boolean compileOperation = this == COMPILE || this == PATTERN_SET_COMPILE;
      if (compileOperation
          && measurement.mode() != MeasurementMode.COMPILE_ONLY
          && measurement.mode() != MeasurementMode.SINGLE_SHOT_COLD_START
          && measurement.mode() != MeasurementMode.RETAINED_MEMORY
          && measurement.mode() != MeasurementMode.SUBPROCESS_MEMORY) {
        throw new IllegalArgumentException(
            id + " compile operation requires compileOnly, singleShotColdStart, or memory mode");
      }
      if (!compileOperation && measurement.mode() == MeasurementMode.COMPILE_ONLY) {
        throw new IllegalArgumentException(
            id + " operation " + jsonName + " is incompatible with compileOnly mode");
      }
    }

    EnumSet<Feature> requiredFeatures() {
      return requiredFeatures.clone();
    }

    private boolean supports(ResultConsumption consumption) {
      return switch (this) {
        case MATCHES,
            FIND,
            LOOKING_AT,
            FIND_GROUP_PRESENT,
            COMPILE_AND_FIND,
            FIND_ROTATING_UTF16,
            COMPILE_AND_FIND_ROTATING_UTF16,
            MATCHER_REGION_FIND,
            FIND_IN_WINDOW,
            UTF8_DECODE_FIND ->
            consumption == ResultConsumption.BOOLEAN;
        case DIAGNOSTICS_FIND ->
            consumption == ResultConsumption.BOOLEAN
                || consumption == ResultConsumption.BLACKHOLE_OBJECT;
        case PATTERN_SET_FIND, PATTERN_SET_MATCHES ->
            consumption == ResultConsumption.BOOLEAN
                || consumption == ResultConsumption.BLACKHOLE_OBJECT;
        case FIND_ALL_COUNT,
            FIND_ALL_LENGTH_SUM,
            FIND_ALL_GROUP_LENGTH_SUM,
            MATCHES_CORPUS,
            MATCHES_GROUP_LENGTH_SUM,
            MATCHER_RESET_FIND,
            REPLACE_ALL_LENGTH_SUM,
            SPLIT_LENGTH_SUM,
            UTF8_CAPTURE_BOUNDS,
            UTF8_REPLACEMENT ->
            consumption == ResultConsumption.INTEGER;
        case FIND_GROUP, REPLACE_FIRST, REPLACE_ALL, APPEND_REPLACEMENT, MANUAL_REPLACE_ALL ->
            consumption == ResultConsumption.STRING;
        case CAPTURE_GROUPS ->
            consumption == ResultConsumption.STRING || consumption == ResultConsumption.STRING_LIST;
        case SPLIT ->
            consumption == ResultConsumption.STRING_LIST
                || consumption == ResultConsumption.BLACKHOLE_OBJECT;
        case COMPILE, PATTERN_SET_COMPILE -> consumption == ResultConsumption.COMPILED_OBJECT;
        case MATCHER_CONSTRUCTION, ANALYZE_PATTERN, CACHED_ANALYSIS, COMPILE_AND_ANALYZE ->
            consumption == ResultConsumption.BLACKHOLE_OBJECT;
        case DFA_CACHE_GROWTH -> consumption == ResultConsumption.INTEGER;
      };
    }

    Map<String, RecipeValueType> argumentTypes() {
      return switch (this) {
        case FIND_ALL_GROUP_LENGTH_SUM, MATCHES_GROUP_LENGTH_SUM, CAPTURE_GROUPS ->
            Map.of("groups", RecipeValueType.INTEGER_LIST);
        case UTF8_CAPTURE_BOUNDS ->
            Map.of(
                "groups", RecipeValueType.INTEGER_LIST,
                "bounds", RecipeValueType.STRING);
        case FIND_GROUP_PRESENT, FIND_GROUP -> Map.of("group", RecipeValueType.INTEGER);
        case REPLACE_FIRST,
            REPLACE_ALL,
            REPLACE_ALL_LENGTH_SUM,
            APPEND_REPLACEMENT,
            MANUAL_REPLACE_ALL,
            UTF8_REPLACEMENT ->
            Map.of("replacement", RecipeValueType.STRING);
        case SPLIT, SPLIT_LENGTH_SUM -> Map.of("limit", RecipeValueType.INTEGER);
        case COMPILE -> Map.of("flagSet", RecipeValueType.STRING);
        case MATCHER_CONSTRUCTION -> Map.of("mode", RecipeValueType.STRING);
        case FIND_ROTATING_UTF16, COMPILE_AND_FIND_ROTATING_UTF16 ->
            Map.of(
                "seed", RecipeValueType.INTEGER,
                "count", RecipeValueType.INTEGER);
        case DIAGNOSTICS_FIND ->
            Map.of(
                "action", RecipeValueType.STRING,
                "listener", RecipeValueType.STRING,
                "replacement", RecipeValueType.STRING);
        case PATTERN_SET_COMPILE, PATTERN_SET_FIND, PATTERN_SET_MATCHES ->
            Map.of(
                "anchor", RecipeValueType.STRING,
                "patternCount", RecipeValueType.INTEGER);
        default -> Map.of();
      };
    }

    Set<String> requiredArguments() {
      return switch (this) {
        case FIND_ALL_GROUP_LENGTH_SUM,
            MATCHES_GROUP_LENGTH_SUM,
            CAPTURE_GROUPS,
            UTF8_CAPTURE_BOUNDS ->
            Set.of("groups");
        case FIND_GROUP_PRESENT, FIND_GROUP -> Set.of("group");
        case REPLACE_FIRST,
            REPLACE_ALL,
            REPLACE_ALL_LENGTH_SUM,
            APPEND_REPLACEMENT,
            MANUAL_REPLACE_ALL,
            UTF8_REPLACEMENT ->
            Set.of("replacement");
        case PATTERN_SET_COMPILE, PATTERN_SET_FIND, PATTERN_SET_MATCHES -> Set.of("anchor");
        case FIND_ROTATING_UTF16, COMPILE_AND_FIND_ROTATING_UTF16 -> Set.of("seed", "count");
        default -> Set.of();
      };
    }

    void validateArguments(String id, Map<String, RecipeValue> arguments) {
      RecipeValue group = arguments.get("group");
      if (group != null && ((RecipeInteger) group).value() < 0) {
        throw new IllegalArgumentException(id + " group argument must not be negative");
      }
      RecipeValue groups = arguments.get("groups");
      if (groups != null) {
        List<Integer> values = ((RecipeIntegerList) groups).values();
        if (values.isEmpty() || values.stream().anyMatch(value -> value < 0)) {
          throw new IllegalArgumentException(
              id + " groups argument must contain nonnegative group numbers");
        }
      }
      RecipeValue anchor = arguments.get("anchor");
      if (anchor != null) {
        String value = ((RecipeString) anchor).value();
        if (!value.equals("unanchored") && !value.equals("anchored")) {
          throw new IllegalArgumentException(
              id + " anchor argument must be unanchored or anchored");
        }
      }
      RecipeValue patternCount = arguments.get("patternCount");
      if (patternCount != null && ((RecipeInteger) patternCount).value() <= 0) {
        throw new IllegalArgumentException(id + " patternCount argument must be positive");
      }
      RecipeValue count = arguments.get("count");
      if (count != null && ((RecipeInteger) count).value() <= 0) {
        throw new IllegalArgumentException(id + " count argument must be positive");
      }
      RecipeValue bounds = arguments.get("bounds");
      if (bounds != null) {
        String value = ((RecipeString) bounds).value();
        if (!value.equals("start") && !value.equals("startEnd")) {
          throw new IllegalArgumentException(id + " bounds argument must be start or startEnd");
        }
      }
    }

    static Operation fromJson(String name) {
      return enumFromJson(values(), name, "benchmark operation");
    }
  }

  enum Flag implements JsonNamed {
    CASE_INSENSITIVE("caseInsensitive"),
    MULTILINE("multiline"),
    DOTALL("dotAll"),
    UNICODE_CASE("unicodeCase"),
    COMMENTS("comments"),
    LITERAL("literal"),
    UNICODE_CHARACTER_CLASS("unicodeCharacterClass");

    private final String jsonName;

    Flag(String jsonName) {
      this.jsonName = jsonName;
    }

    @Override
    public String jsonName() {
      return jsonName;
    }

    static Flag fromJson(String name) {
      return enumFromJson(values(), name, "regex flag");
    }
  }

  enum Feature implements JsonNamed {
    FIND("find"),
    MATCHES("matches"),
    LOOKING_AT("lookingAt"),
    CAPTURE_PARTICIPATION("captureParticipation"),
    CAPTURE_TEXT("captureText"),
    NAMED_GROUPS("namedGroups"),
    REPLACE("replace"),
    NUMBERED_REPLACEMENT("numberedReplacement"),
    NAMED_REPLACEMENT("namedReplacement"),
    APPEND_REPLACEMENT("appendReplacement"),
    FUNCTIONAL_REPLACEMENT("functionalReplacement"),
    SPLIT("split"),
    MATCHER_STATE("matcherState"),
    REGIONS("regions"),
    BOUNDS("bounds"),
    PATTERN_SET("patternSet"),
    UTF8_INPUT("utf8Input"),
    UTF8_REPLACEMENT("utf8Replacement"),
    DIAGNOSTICS("diagnostics"),
    DFA_CACHE("dfaCache"),
    FLAGGED_COMPILE("flaggedCompile"),
    JAVA_CHARACTER_CLASS("javaCharacterClass"),
    LINEAR_TIME("linearTime"),
    RETAINED_HEAP("retainedHeap");

    private final String jsonName;

    Feature(String jsonName) {
      this.jsonName = jsonName;
    }

    @Override
    public String jsonName() {
      return jsonName;
    }

    static Feature fromJson(String name) {
      return enumFromJson(values(), name, "engine-neutral feature requirement");
    }
  }

  enum InputRepresentation implements JsonNamed {
    JAVA_STRING("javaString"),
    PREEXISTING_UTF8("preexistingUtf8"),
    JAVA_STRING_WITH_TIMED_UTF8_CONVERSION("javaStringWithTimedUtf8Conversion");

    private final String jsonName;

    InputRepresentation(String jsonName) {
      this.jsonName = jsonName;
    }

    @Override
    public String jsonName() {
      return jsonName;
    }

    static InputRepresentation fromJson(String name) {
      return enumFromJson(values(), name, "input representation");
    }
  }

  enum ResultConsumption implements JsonNamed {
    BOOLEAN("boolean", ResultType.BOOLEAN),
    INTEGER("integer", ResultType.INTEGER),
    STRING("string", ResultType.STRING),
    STRING_LIST("stringList", ResultType.STRING_LIST),
    COMPILED_OBJECT("compiledObject", null),
    BLACKHOLE_OBJECT("blackholeObject", null);

    private final String jsonName;
    private final ResultType expectedType;

    ResultConsumption(String jsonName, ResultType expectedType) {
      this.jsonName = jsonName;
      this.expectedType = expectedType;
    }

    @Override
    public String jsonName() {
      return jsonName;
    }

    void validateExpected(String id, ExpectedResult expected) {
      if (expected == null) {
        return;
      }
      if (expectedType == null) {
        throw new IllegalArgumentException(
            id + " result consumption " + jsonName + " cannot declare expected");
      }
      if (expected.type() != expectedType) {
        throw new IllegalArgumentException(
            id
                + " result consumption "
                + jsonName
                + " requires expected type "
                + expectedType.jsonName());
      }
    }

    static ResultConsumption fromJson(String name) {
      return enumFromJson(values(), name, "result consumption");
    }
  }

  enum ResultType implements JsonNamed {
    BOOLEAN("boolean"),
    INTEGER("integer"),
    STRING("string"),
    STRING_LIST("stringList");

    private final String jsonName;

    ResultType(String jsonName) {
      this.jsonName = jsonName;
    }

    @Override
    public String jsonName() {
      return jsonName;
    }

    void validate(JsonElement value) {
      boolean valid =
          switch (this) {
            case BOOLEAN -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case INTEGER ->
                value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isNumber()
                    && isInteger(value);
            case STRING -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            case STRING_LIST ->
                value.isJsonArray()
                    && value.getAsJsonArray().asList().stream()
                        .allMatch(
                            item -> item.isJsonPrimitive() && item.getAsJsonPrimitive().isString());
          };
      if (!valid) {
        throw new IllegalArgumentException("Malformed " + jsonName + " expected result: " + value);
      }
    }

    private static boolean isInteger(JsonElement value) {
      try {
        value.getAsBigDecimal().toBigIntegerExact();
        return true;
      } catch (ArithmeticException | NumberFormatException exception) {
        return false;
      }
    }

    static ResultType fromJson(String name) {
      return enumFromJson(values(), name, "expected result type");
    }
  }

  enum MatcherReuse implements JsonNamed {
    NONE("none"),
    NEW_PER_INVOCATION("newPerInvocation"),
    RETAINED("retained");

    private final String jsonName;

    MatcherReuse(String jsonName) {
      this.jsonName = jsonName;
    }

    @Override
    public String jsonName() {
      return jsonName;
    }

    static MatcherReuse fromJson(String name) {
      return enumFromJson(values(), name, "matcher reuse policy");
    }
  }

  enum LifecycleStepKind implements JsonNamed {
    RESET("reset"),
    REGION("region"),
    TRANSPARENT_BOUNDS("transparentBounds"),
    ANCHORING_BOUNDS("anchoringBounds");

    private final String jsonName;

    LifecycleStepKind(String jsonName) {
      this.jsonName = jsonName;
    }

    @Override
    public String jsonName() {
      return jsonName;
    }

    void validate(Integer start, Integer end, Boolean enabled) {
      switch (this) {
        case RESET -> requireAbsent(start, end, enabled);
        case REGION -> {
          if (start == null || end == null || start < 0 || end < start || enabled != null) {
            throw new IllegalArgumentException(
                "region lifecycle step requires valid start/end and no enabled field");
          }
        }
        case TRANSPARENT_BOUNDS, ANCHORING_BOUNDS -> {
          if (enabled == null || start != null || end != null) {
            throw new IllegalArgumentException(
                jsonName + " lifecycle step requires enabled and no start/end");
          }
        }
      }
    }

    private static void requireAbsent(Integer start, Integer end, Boolean enabled) {
      if (start != null || end != null || enabled != null) {
        throw new IllegalArgumentException("reset lifecycle step takes no parameters");
      }
    }

    static LifecycleStepKind fromJson(String name) {
      return enumFromJson(values(), name, "matcher lifecycle step");
    }
  }

  enum MeasurementMode implements JsonNamed {
    AVERAGE_TIME("averageTime"),
    COMPILE_ONLY("compileOnly"),
    SINGLE_SHOT_COLD_START("singleShotColdStart"),
    RETAINED_MEMORY("retainedMemory"),
    SUBPROCESS_MEMORY("subprocessMemory");

    private final String jsonName;

    MeasurementMode(String jsonName) {
      this.jsonName = jsonName;
    }

    @Override
    public String jsonName() {
      return jsonName;
    }

    void validate(TimingUnit unit, EnumSet<ExecutionConstraint> constraints) {
      switch (this) {
        case AVERAGE_TIME, COMPILE_ONLY -> {
          if (!unit.isTime()) {
            throw new IllegalArgumentException(jsonName + " requires a time unit");
          }
        }
        case SINGLE_SHOT_COLD_START -> {
          if (!unit.isTime()
              || !constraints.contains(ExecutionConstraint.FRESH_PROCESS_PER_INVOCATION)) {
            throw new IllegalArgumentException(
                jsonName + " requires a time unit and freshProcessPerInvocation");
          }
        }
        case RETAINED_MEMORY, SUBPROCESS_MEMORY -> {
          if (unit != TimingUnit.BYTES) {
            throw new IllegalArgumentException(jsonName + " requires bytes timingUnit");
          }
        }
      }
      if (constraints.contains(ExecutionConstraint.NO_FORK)
          && constraints.contains(ExecutionConstraint.FRESH_PROCESS_PER_INVOCATION)) {
        throw new IllegalArgumentException(
            "Measurement constraints noFork and freshProcessPerInvocation are incompatible");
      }
    }

    static MeasurementMode fromJson(String name) {
      return enumFromJson(values(), name, "measurement mode");
    }
  }

  enum TimingUnit implements JsonNamed {
    NANOSECONDS("nanoseconds", true),
    MICROSECONDS("microseconds", true),
    MILLISECONDS("milliseconds", true),
    BYTES("bytes", false);

    private final String jsonName;
    private final boolean time;

    TimingUnit(String jsonName, boolean time) {
      this.jsonName = jsonName;
      this.time = time;
    }

    @Override
    public String jsonName() {
      return jsonName;
    }

    boolean isTime() {
      return time;
    }

    static TimingUnit fromJson(String name) {
      return enumFromJson(values(), name, "measurement timing unit");
    }
  }

  enum ExecutionConstraint implements JsonNamed {
    NO_FORK("noFork"),
    FRESH_PROCESS_PER_INVOCATION("freshProcessPerInvocation"),
    RETAIN_STATE("retainState"),
    PREMATERIALIZED_INPUT("prematerializedInput"),
    ALLOCATION_PROFILE("allocationProfile");

    private final String jsonName;

    ExecutionConstraint(String jsonName) {
      this.jsonName = jsonName;
    }

    @Override
    public String jsonName() {
      return jsonName;
    }

    static ExecutionConstraint fromJson(String name) {
      return enumFromJson(values(), name, "execution constraint");
    }
  }

  enum ExclusionKind {
    WORKLOAD_DISABLED,
    UNSUPPORTED_FEATURE,
    UNSUPPORTED_FLAG,
    UNSUPPORTED_INPUT_REPRESENTATION,
    MISSING_OPERATION_IMPLEMENTATION,
    MISSING_ENGINE_ADAPTER
  }

  private interface JsonNamed {
    String jsonName();
  }

  private interface EnumParser<E> {
    E parse(String value);
  }

  private static <E extends Enum<E> & JsonNamed> E enumFromJson(
      E[] values, String name, String kind) {
    for (E value : values) {
      if (value.jsonName().equals(name)) {
        return value;
      }
    }
    throw new IllegalArgumentException("Unknown " + kind + ": " + name);
  }

  private static final class JsonReader {
    private final String context;
    private final JsonObject object;

    private JsonReader(String context, JsonObject object) {
      this.context = context;
      this.object = object;
    }

    static JsonReader object(String context, JsonElement element) {
      if (element == null || !element.isJsonObject()) {
        throw new IllegalArgumentException(context + " must be an object");
      }
      return new JsonReader(context, element.getAsJsonObject());
    }

    void requireOnly(String... allowedNames) {
      Set<String> allowed = Set.of(allowedNames);
      for (String name : object.keySet()) {
        if (!allowed.contains(name)) {
          throw new IllegalArgumentException(context + " has unknown field: " + name);
        }
      }
    }

    boolean has(String name) {
      return object.has(name) && !object.get(name).isJsonNull();
    }

    JsonElement required(String name) {
      JsonElement value = object.get(name);
      if (value == null || value.isJsonNull()) {
        throw new IllegalArgumentException(context + " requires " + name);
      }
      return value;
    }

    String requiredString(String name) {
      JsonElement value = required(name);
      if (!isString(value)) {
        throw new IllegalArgumentException(context + " field " + name + " must be a string");
      }
      return value.getAsString();
    }

    String optionalString(String name) {
      JsonElement value = object.get(name);
      if (value == null || value.isJsonNull()) {
        return null;
      }
      if (!isString(value)) {
        throw new IllegalArgumentException(context + " field " + name + " must be a string");
      }
      return value.getAsString();
    }

    int requiredInt(String name) {
      JsonElement value = required(name);
      if (!isIntegerPrimitive(value)) {
        throw new IllegalArgumentException(context + " field " + name + " must be an integer");
      }
      return value.getAsBigDecimal().intValueExact();
    }

    Integer optionalInt(String name) {
      JsonElement value = object.get(name);
      if (value == null || value.isJsonNull()) {
        return null;
      }
      if (!isIntegerPrimitive(value)) {
        throw new IllegalArgumentException(context + " field " + name + " must be an integer");
      }
      return value.getAsBigDecimal().intValueExact();
    }

    boolean optionalBoolean(String name, boolean defaultValue) {
      Boolean value = optionalBoolean(name);
      return value == null ? defaultValue : value;
    }

    Boolean optionalBoolean(String name) {
      JsonElement value = object.get(name);
      if (value == null || value.isJsonNull()) {
        return null;
      }
      if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
        throw new IllegalArgumentException(context + " field " + name + " must be a boolean");
      }
      return value.getAsBoolean();
    }

    JsonArray requiredArray(String name) {
      try {
        return required(name).getAsJsonArray();
      } catch (RuntimeException exception) {
        throw new IllegalArgumentException(
            context + " field " + name + " must be an array", exception);
      }
    }

    JsonArray optionalArray(String name) {
      JsonElement value = object.get(name);
      if (value == null || value.isJsonNull()) {
        return new JsonArray();
      }
      try {
        return value.getAsJsonArray();
      } catch (RuntimeException exception) {
        throw new IllegalArgumentException(
            context + " field " + name + " must be an array", exception);
      }
    }

    JsonObject requiredObject(String name) {
      try {
        return required(name).getAsJsonObject();
      } catch (RuntimeException exception) {
        throw new IllegalArgumentException(
            context + " field " + name + " must be an object", exception);
      }
    }

    JsonObject optionalObject(String name) {
      JsonElement value = object.get(name);
      if (value == null || value.isJsonNull()) {
        return null;
      }
      try {
        return value.getAsJsonObject();
      } catch (RuntimeException exception) {
        throw new IllegalArgumentException(
            context + " field " + name + " must be an object", exception);
      }
    }

    List<String> requiredStringList(String name) {
      required(name);
      return stringList(name);
    }

    List<String> optionalStringList(String name) {
      JsonElement value = object.get(name);
      if (value == null || value.isJsonNull()) {
        return List.of();
      }
      return stringList(name);
    }

    private List<String> stringList(String name) {
      JsonElement value = object.get(name);
      try {
        List<String> result = new ArrayList<>();
        value
            .getAsJsonArray()
            .forEach(
                item -> {
                  if (!isString(item)) {
                    throw new IllegalArgumentException();
                  }
                  result.add(item.getAsString());
                });
        return List.copyOf(result);
      } catch (RuntimeException exception) {
        throw new IllegalArgumentException(
            context + " field " + name + " must be an array of strings", exception);
      }
    }
  }

  private static boolean isString(JsonElement value) {
    return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
  }

  private static boolean isIntegerPrimitive(JsonElement value) {
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      return false;
    }
    try {
      value.getAsBigDecimal().intValueExact();
      return true;
    } catch (ArithmeticException | NumberFormatException exception) {
      return false;
    }
  }
}
