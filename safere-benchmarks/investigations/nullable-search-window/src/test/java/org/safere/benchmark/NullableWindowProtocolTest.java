// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NullableWindowProtocolTest {
  @Test
  void captureReplacementControlsProduceTheJdkOutputInBothRepresentations() throws Exception {
    JsonObject data = read(Path.of("investigations/nullable-search-window/benchmark-data.json"));
    Map<String, byte[]> inputs = BenchmarkInputMaterializer.materialize(data);
    for (JsonElement element : data.getAsJsonArray("workloads")) {
      JsonObject workload = element.getAsJsonObject();
      if (workload.get("operation").getAsString().equals("replaceAll")) {
        String inputId = workload.getAsJsonArray("inputs").get(0).getAsString();
        assertReplacement(workload, inputs.get(inputId));
      }
    }
  }

  @Test
  void protocolLinkReplacementOutputsAgreeWithTheJdkOnTheMeasuredInputs() throws Exception {
    JsonObject data = read(Path.of("benchmark-data.json"));
    JsonArray selected = new JsonArray();
    for (JsonElement element : data.getAsJsonArray("inputs")) {
      if (element
          .getAsJsonObject()
          .get("id")
          .getAsString()
          .startsWith("realWorldRegex.customProtocolLink.")) {
        selected.add(element);
      }
    }
    data.add("inputs", selected);
    Map<String, byte[]> inputs = BenchmarkInputMaterializer.materialize(data);
    JsonObject workload = null;
    for (JsonElement element : data.getAsJsonArray("workloads")) {
      if (element
          .getAsJsonObject()
          .get("id")
          .getAsString()
          .startsWith("RealWorldRegexBenchmark.runBenchmark.customProtocolLinkReplace.")) {
        workload = element.getAsJsonObject();
        break;
      }
    }
    assertThat(workload).isNotNull();
    for (byte[] input : inputs.values()) {
      assertReplacement(workload, input);
    }
  }

  private static void assertReplacement(JsonObject workload, byte[] bytes) {
    String regex = workload.getAsJsonArray("patterns").get(0).getAsString();
    String replacement = workload.getAsJsonObject("arguments").get("replacement").getAsString();
    String text = new String(bytes, UTF_8);
    String expected = java.util.regex.Pattern.compile(regex).matcher(text).replaceAll(replacement);
    for (RegexEngineVariant variant :
        List.of(RegexEngineVariant.SAFERE_STRING, RegexEngineVariant.SAFERE_UTF8)) {
      RegexEngineVariant.RegexInput input =
          variant == RegexEngineVariant.SAFERE_UTF8
              ? RegexEngineVariant.utf8Input(bytes)
              : new RegexEngineVariant.StringRegexInput(text);
      try (RegexEngineVariant.CompiledRegex compiled = variant.compile(regex)) {
        assertThat(
                compiled
                    .replaceAll(input, variant.prepareReplacement(replacement))
                    .validationValue())
            .as("%s, %s", workload.get("id").getAsString(), variant)
            .isEqualTo(expected);
      }
    }
  }

  private static JsonObject read(Path path) throws Exception {
    return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
  }
}
