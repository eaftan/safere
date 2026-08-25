// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkInputMaterializerTest {
  // Historical floors guard against accidental corpus loss. Additions do not raise them.
  private static final int MINIMUM_MATERIALIZED_INPUTS = 419;
  private static final int MINIMUM_DECLARED_WORKLOADS = 354;
  private static final int MINIMUM_EXPANDED_WORKLOADS = 683;

  @TempDir Path tempDirectory;

  @Test
  void declaredBenchmarkCorpusMaterializesDeterministically() throws IOException {
    JsonObject benchmarkData =
        JsonParser.parseString(Files.readString(Path.of("benchmark-data.json"))).getAsJsonObject();

    Map<String, byte[]> first = BenchmarkInputMaterializer.materialize(benchmarkData);
    Map<String, byte[]> second = BenchmarkInputMaterializer.materialize(benchmarkData);

    Set<String> declaredInputIds =
        DeclarativeBenchmarkPlan.parseInputDeclarations(benchmarkData.getAsJsonArray("inputs"))
            .keySet();
    assertThat(first).hasSizeGreaterThanOrEqualTo(MINIMUM_MATERIALIZED_INPUTS);
    assertThat(first.keySet()).containsExactlyElementsOf(declaredInputIds);
    assertThat(second.keySet()).containsExactlyElementsOf(first.keySet());
    first.forEach((id, bytes) -> assertThat(second.get(id)).as(id).containsExactly(bytes));
    assertThat(text(first, "crossEngine.RegexBenchmark.literalMatch.input")).isEqualTo("hello");
    assertThat(text(first, "pathological.pattern.10")).isEqualTo("a?".repeat(10) + "a".repeat(10));
    assertThat(text(first, "searchScaling.success.1024"))
        .hasSize(1050)
        .endsWith("ABCDEFGHIJKLMNOPQRSTUVWXYZ");
    assertThat(text(first, "fanout.unicode.1024")).hasSize(1024);
  }

  @Test
  void appendRecipeRejectsUnknownInput() {
    JsonObject benchmarkData =
        JsonParser.parseString(
                """
                {
                  "schemaVersion": 1,
                  "inputs": [{
                    "id": "derived",
                    "recipe": {"kind": "appendInput", "input": "missing", "suffix": "!"},
                    "shared": true
                  }]
                }
                """)
            .getAsJsonObject();

    assertThatThrownBy(() -> BenchmarkInputMaterializer.materialize(benchmarkData))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Input recipe references unknown materialized input: missing");
  }

  @Test
  void appendRecipeRejectsDependencyCycle() {
    JsonObject benchmarkData =
        JsonParser.parseString(
                """
                {
                  "schemaVersion": 1,
                  "inputs": [
                    {
                      "id": "first",
                      "recipe": {"kind": "appendInput", "input": "second", "suffix": "!"},
                      "shared": true
                    },
                    {
                      "id": "second",
                      "recipe": {"kind": "appendInput", "input": "first", "suffix": "?"},
                      "shared": true
                    }
                  ]
                }
                """)
            .getAsJsonObject();

    assertThatThrownBy(() -> BenchmarkInputMaterializer.materialize(benchmarkData))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cyclic materialized input recipe dependency: first -> second -> first");
  }

  @Test
  void deeplyChainedAppendRecipesMaterializeWithoutUsingTheCallStack() {
    int dependencyCount = 5_000;
    JsonObject benchmarkData = new JsonObject();
    benchmarkData.addProperty("schemaVersion", 1);
    JsonArray inputs = new JsonArray();
    for (int index = dependencyCount; index >= 1; index--) {
      JsonObject declaration = new JsonObject();
      declaration.addProperty("id", "input" + index);
      JsonObject recipe = new JsonObject();
      recipe.addProperty("kind", "appendInput");
      recipe.addProperty("input", "input" + (index - 1));
      recipe.addProperty("suffix", "x");
      declaration.add("recipe", recipe);
      declaration.addProperty("shared", true);
      inputs.add(declaration);
    }
    JsonObject base = new JsonObject();
    base.addProperty("id", "input0");
    JsonObject baseRecipe = new JsonObject();
    baseRecipe.addProperty("kind", "literal");
    baseRecipe.addProperty("text", "");
    base.add("recipe", baseRecipe);
    base.addProperty("shared", true);
    inputs.add(base);
    benchmarkData.add("inputs", inputs);

    Map<String, byte[]> materialized = BenchmarkInputMaterializer.materialize(benchmarkData);

    assertThat(text(materialized, "input" + dependencyCount))
        .isEqualTo("x".repeat(dependencyCount));
  }

  @Test
  void manifestAttributesInputsAndRecordsExactEncodingMetadata() throws Exception {
    JsonObject declaredData =
        JsonParser.parseString(Files.readString(Path.of("benchmark-data.json"))).getAsJsonObject();
    BenchmarkInputMaterializer.main(
        new String[] {
          Path.of(".").toAbsolutePath().normalize().toString(), tempDirectory.toString()
        });

    JsonObject manifest =
        JsonParser.parseString(Files.readString(tempDirectory.resolve("manifest.json")))
            .getAsJsonObject();
    JsonObject entry =
        manifest
            .getAsJsonObject("inputs")
            .getAsJsonObject("crossEngine.RegexBenchmark.literalMatch.input");

    assertThat(entry.get("file").getAsString())
        .isEqualTo("crossEngine/RegexBenchmark/literalMatch/input.txt");
    assertThat(entry.get("shared").getAsBoolean()).isTrue();
    assertThat(entry.get("utf8Bytes").getAsInt()).isEqualTo(5);
    assertThat(entry.get("utf16CodeUnits").getAsInt()).isEqualTo(5);
    assertThat(entry.get("unicodeScalars").getAsInt()).isEqualTo(5);
    assertThat(entry.get("sha256").getAsString())
        .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    JsonObject resolvedData = manifest.getAsJsonObject("benchmarkData");
    // These are historical floors: adding syntax alternates must not require a test update.
    assertThat(resolvedData.getAsJsonObject("patternProfiles").getAsJsonArray("re2"))
        .hasSizeGreaterThanOrEqualTo(8);
    assertThat(resolvedData.getAsJsonObject("patternProfiles").getAsJsonArray("rust-regex"))
        .hasSizeGreaterThanOrEqualTo(28);
    assertThat(resolvedData.getAsJsonObject("replacementProfiles").getAsJsonArray("go-regexp"))
        .hasSizeGreaterThanOrEqualTo(1);
    assertThat(resolvedData.getAsJsonObject("replacementProfiles").getAsJsonArray("re2-cpp"))
        .hasSizeGreaterThanOrEqualTo(8);
    assertThat(resolvedData.getAsJsonObject("replacementProfiles").getAsJsonArray("rust-regex"))
        .hasSizeGreaterThanOrEqualTo(1);
    assertThat(resolvedData.getAsJsonObject("patternProfiles").getAsJsonArray("dotnet"))
        .hasSizeGreaterThanOrEqualTo(40);
    assertThat(declarationIds(resolvedData.getAsJsonArray("inputs")))
        .containsExactlyElementsOf(declarationIds(declaredData.getAsJsonArray("inputs")));
    assertThat(manifest.getAsJsonObject("inputs").keySet())
        .containsExactlyElementsOf(
            DeclarativeBenchmarkPlan.parseInputDeclarations(declaredData.getAsJsonArray("inputs"))
                .keySet());
    Set<String> declaredWorkloadIds = declarationIds(declaredData.getAsJsonArray("workloads"));
    assertThat(declaredWorkloadIds).hasSizeGreaterThanOrEqualTo(MINIMUM_DECLARED_WORKLOADS);
    assertThat(declarationIds(resolvedData.getAsJsonArray("workloads")))
        .containsExactlyElementsOf(declaredWorkloadIds);
    JsonObject executionPlan = manifest.getAsJsonObject("executionPlan");
    assertThat(executionPlan.get("version").getAsInt()).isEqualTo(1);
    Set<String> workloadIds = executionPlanIds(executionPlan, "workloadId");
    Set<String> expectedWorkloadIds = expandedWorkloadIds(resolvedData);
    assertThat(expectedWorkloadIds).hasSizeGreaterThanOrEqualTo(MINIMUM_EXPANDED_WORKLOADS);
    assertThat(workloadIds).containsExactlyInAnyOrderElementsOf(expectedWorkloadIds);
    Set<String> engineIds = declarationIds(executionPlan.getAsJsonArray("engines"));
    Set<String> expectedExecutionIds = new LinkedHashSet<>();
    for (String workloadId : workloadIds) {
      for (String engineId : engineIds) {
        expectedExecutionIds.add(workloadId + "@" + engineId);
      }
    }
    assertThat(executionPlan.get("workloadCount").getAsInt()).isEqualTo(expectedWorkloadIds.size());
    assertThat(executionPlan.get("engineCount").getAsInt()).isEqualTo(engineIds.size());
    assertThat(executionPlan.getAsJsonArray("entries")).hasSize(expectedExecutionIds.size());
    assertThat(executionPlanIds(executionPlan, "id"))
        .containsExactlyInAnyOrderElementsOf(expectedExecutionIds);
    assertThat(
            executionEntry(
                    executionPlan, "UnicodeCompileBenchmark.compile.word.0@dotnet_nonbacktracking")
                .getAsJsonArray("patterns")
                .get(0)
                .getAsString())
        .isEqualTo("[A-Za-z0-9_]+");
    assertThat(
            executionEntry(
                    executionPlan,
                    "RealWorldRegexBenchmark.runBenchmark.fruitSearchQuery.match.1000@dotnet_nonbacktracking")
                .get("exclusion")
                .getAsJsonObject()
                .get("kind")
                .getAsString())
        .isEqualTo("unsupportedSyntax");
    for (String input : new String[] {"absent", "late"}) {
      for (String engineId :
          new String[] {"re2_cpp", "go_regexp", "pcre2_jit", "dotnet_nonbacktracking"}) {
        JsonObject excludedAlphabetic =
            executionEntry(
                executionPlan, "UnicodePrefixBenchmark.alphabetic." + input + ".1024@" + engineId);
        assertThat(excludedAlphabetic.get("status").getAsString()).isEqualTo("excluded");
        assertThat(excludedAlphabetic.getAsJsonObject("exclusion").get("reason").getAsString())
            .contains("Alphabetic");
      }
      assertThat(
              executionEntry(
                      executionPlan,
                      "UnicodePrefixBenchmark.alphabetic." + input + ".1024@rust_regex")
                  .getAsJsonArray("patterns")
                  .get(0)
                  .getAsString())
          .isEqualTo("[\\p{Alphabetic}]+[0-9]+");

      assertThat(
              executionEntry(executionPlan, "UnicodePrefixBenchmark.cjk." + input + ".1024@re2_cpp")
                  .getAsJsonArray("patterns")
                  .get(0)
                  .getAsString())
          .isEqualTo("[\\x{4E00}-\\x{9FFF}]+[0-9]+");
      assertThat(
              executionEntry(
                      executionPlan, "UnicodePrefixBenchmark.cjk." + input + ".1024@go_regexp")
                  .getAsJsonArray("patterns")
                  .get(0)
                  .getAsString())
          .isEqualTo("[\\x{4E00}-\\x{9FFF}]+[0-9]+");
      assertThat(
              executionEntry(
                      executionPlan, "UnicodePrefixBenchmark.cjk." + input + ".1024@rust_regex")
                  .getAsJsonArray("patterns")
                  .get(0)
                  .getAsString())
          .isEqualTo("[\\x{4E00}-\\x{9FFF}]+[0-9]+");
      assertThat(
              executionEntry(
                      executionPlan, "UnicodePrefixBenchmark.cjk." + input + ".1024@pcre2_jit")
                  .getAsJsonArray("patterns")
                  .get(0)
                  .getAsString())
          .isEqualTo("[\\x{4E00}-\\x{9FFF}]+[0-9]+");
      assertThat(
              executionEntry(
                      executionPlan,
                      "UnicodePrefixBenchmark.cjk." + input + ".1024@dotnet_nonbacktracking")
                  .getAsJsonArray("patterns")
                  .get(0)
                  .getAsString())
          .isEqualTo("[\\u4E00-\\u9FFF]+[0-9]+");
    }
    for (JsonElement engineElement : executionPlan.getAsJsonArray("engines")) {
      String engineId = engineElement.getAsJsonObject().get("id").getAsString();
      assertThat(
              executionPlan.getAsJsonArray("entries").asList().stream()
                  .map(JsonElement::getAsJsonObject)
                  .filter(planEntry -> planEntry.get("engineId").getAsString().equals(engineId))
                  .filter(
                      planEntry ->
                          Set.of("runnable", "excluded")
                              .contains(planEntry.get("status").getAsString())))
          .as(engineId)
          .extracting(planEntry -> planEntry.get("workloadId").getAsString())
          .containsExactlyInAnyOrderElementsOf(workloadIds);
    }
    assertThat(
            executionPlan.getAsJsonArray("entries").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(
                    planEntry ->
                        planEntry
                            .get("workloadId")
                            .getAsString()
                            .equals("CompileBenchmark.compileSimple"))
                .filter(
                    planEntry ->
                        Set.of("re2_cpp", "pcre2_jit", "go_regexp", "rust_regex")
                            .contains(planEntry.get("engineId").getAsString())))
        .hasSize(4)
        .allSatisfy(
            planEntry -> assertThat(planEntry.get("status").getAsString()).isEqualTo("runnable"));
    assertThat(
            resolvedData.getAsJsonArray("workloads").asList().stream()
                .filter(
                    workload ->
                        workload
                            .getAsJsonObject()
                            .get("id")
                            .getAsString()
                            .equals("UnicodeCompileBenchmark.compile.{regex}.{flagSet}"))
                .findFirst()
                .orElseThrow()
                .getAsJsonObject()
                .getAsJsonObject("axes")
                .getAsJsonArray("regex")
                .get(4)
                .getAsJsonObject()
                .get("value")
                .getAsString())
        .isEqualTo("\\p{IsAlphabetic}+");
  }

  @Test
  void emptyRepeatUnitIsRejectedWhenOutputIsRequired() {
    assertThatThrownBy(() -> BenchmarkInputMaterializer.repeatToSize("", 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Repeat unit must not be empty when size is positive");
  }

  @Test
  void emptyRepeatUnitCanProduceEmptyOutput() {
    assertThat(BenchmarkInputMaterializer.repeatToSize("", 0)).isEmpty();
  }

  private static String text(Map<String, byte[]> inputs, String id) {
    return new String(inputs.get(id), StandardCharsets.UTF_8);
  }

  private static JsonObject executionEntry(JsonObject plan, String id) {
    return plan.getAsJsonArray("entries").asList().stream()
        .map(JsonElement::getAsJsonObject)
        .filter(entry -> entry.get("id").getAsString().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private static Set<String> declarationIds(JsonArray declarations) {
    return declarations.asList().stream()
        .map(JsonElement::getAsJsonObject)
        .map(declaration -> declaration.get("id").getAsString())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static Set<String> executionPlanIds(JsonObject plan, String property) {
    return plan.getAsJsonArray("entries").asList().stream()
        .map(JsonElement::getAsJsonObject)
        .map(entry -> entry.get(property).getAsString())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static Set<String> expandedWorkloadIds(JsonObject data) {
    JsonObject planData = new JsonObject();
    planData.add("schemaVersion", data.get("schemaVersion").deepCopy());
    planData.add("inputs", data.getAsJsonArray("inputs").deepCopy());
    planData.add("workloads", data.getAsJsonArray("workloads").deepCopy());
    return DeclarativeBenchmarkPlan.parse(planData).expandedWorkloads().stream()
        .map(DeclarativeBenchmarkPlan.ExpandedWorkload::id)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }
}
