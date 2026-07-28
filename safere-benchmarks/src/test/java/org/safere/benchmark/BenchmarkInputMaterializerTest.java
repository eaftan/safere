// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkInputMaterializerTest {
  @TempDir Path tempDirectory;

  @Test
  void declaredBenchmarkCorpusMaterializesDeterministically() throws IOException {
    JsonObject benchmarkData =
        JsonParser.parseString(Files.readString(Path.of("benchmark-data.json"))).getAsJsonObject();

    Map<String, byte[]> first = BenchmarkInputMaterializer.materialize(benchmarkData);
    Map<String, byte[]> second = BenchmarkInputMaterializer.materialize(benchmarkData);

    assertThat(first).hasSize(304);
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
    assertThat(resolvedData.getAsJsonObject("patternProfiles").getAsJsonArray("re2")).hasSize(6);
    assertThat(resolvedData.getAsJsonObject("patternProfiles").getAsJsonArray("rust-regex"))
        .hasSize(26);
    assertThat(resolvedData.getAsJsonObject("replacementProfiles").getAsJsonArray("rust-regex"))
        .hasSize(1);
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
}
