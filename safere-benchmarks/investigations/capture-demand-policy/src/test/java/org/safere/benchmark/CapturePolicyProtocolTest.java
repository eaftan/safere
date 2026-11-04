// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CapturePolicyProtocolTest {
  @ParameterizedTest
  @CsvSource({"text,1,1404", "text,4,472", "phase,1,938", "boolean,1,6", "offset,1,12"})
  void readsAreConditionalOnHitsAndUseNativeOffsets(String mode, int every, int expected) {
    for (RegexEngineVariant variant :
        List.of(RegexEngineVariant.SAFERE_STRING, RegexEngineVariant.SAFERE_UTF8)) {
      for (boolean retained : new boolean[] {false, true}) {
        List<CapturePolicyProtocol.Input> inputs = List.of(input("é é"), input("x"));
        CapturePolicyProtocol.Task task =
            CapturePolicyProtocol.bind(
                CapturePolicyProtocol.compile("(é)", variant),
                inputs,
                1,
                retained,
                1,
                new CapturePolicyProtocol.Usage(mode, every));
        int actualExpected =
            mode.equals("offset") && variant == RegexEngineVariant.SAFERE_UTF8 ? 18 : expected;
        assertThat(task.run()).isEqualTo(actualExpected);
        assertThat(task.run()).isEqualTo(actualExpected);
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"true,2,24", "false,24,0"})
  void cursorsFollowTheDeclaredLifetimeAcrossCompleteCycles(
      boolean retained, int expectedCreations, int expectedResets) {
    int[] creations = {0};
    int[] resets = {0};
    int[] reads = {0};
    CapturePolicyProtocol.Compiled compiled =
        input -> {
          creations[0]++;
          return new CapturePolicyProtocol.Cursor() {
            private boolean exhausted;

            @Override
            public boolean find() {
              if (exhausted) {
                return false;
              }
              exhausted = true;
              return input.text.equals("hit");
            }

            @Override
            public void reset() {
              resets[0]++;
              exhausted = false;
            }

            @Override
            public int start(int group) {
              return 0;
            }

            @Override
            public int end(int group) {
              return 7;
            }

            @Override
            public String group(int group) {
              reads[0]++;
              return "payload";
            }
          };
        };
    CapturePolicyProtocol.Task task =
        CapturePolicyProtocol.bind(
            compiled,
            List.of(input("hit"), input("miss")),
            1,
            retained,
            2,
            new CapturePolicyProtocol.Usage("phase", 1));
    task.run();
    task.run();
    assertThat(creations[0]).isEqualTo(expectedCreations);
    assertThat(resets[0]).isEqualTo(expectedResets);
    assertThat(reads[0]).isEqualTo(8);
  }

  @Test
  void investigationCasesKeepTheirHistoricalCycleChecksums() throws Exception {
    Path path = Path.of("investigations/capture-demand-policy/benchmark-data.json");
    if (!Files.exists(path)) {
      path = Path.of("safere-benchmarks").resolve(path);
    }
    JsonObject source = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    Map<String, byte[]> inputs = BenchmarkInputMaterializer.materialize(source);
    CrossEngineBenchmarkPlan plan =
        CrossEngineBenchmarkPlan.fromMaterialized(
            MaterializedExecutionPlan.parse(ResolvedBenchmarkPlan.create(source))
                .entriesForRunner("java"));
    JsonObject config = source.getAsJsonObject("configuration").getAsJsonObject("investigation");
    for (String family : List.of("stack", "scheme")) {
      for (String encoding : List.of("ascii", "multibyte")) {
        for (String rate : List.of("all", "rare")) {
          for (String reuse : List.of("retained", "newPerInvocation")) {
            for (String engine : List.of("safere-string", "safere-utf8")) {
              String id =
                  "CapturePolicyBenchmark."
                      + family
                      + "."
                      + encoding
                      + ".128."
                      + rate
                      + "."
                      + reuse
                      + "@"
                      + engine;
              for (String usage : config.getAsJsonObject("usages").keySet()) {
                CapturePolicyCase selected =
                    new CapturePolicyCase(
                        plan.resolve(id),
                        key -> new CapturePolicyProtocol.Input(inputs.get(key)),
                        config,
                        usage);
                selected.validate();
              }
            }
          }
        }
      }
    }
  }

  private static CapturePolicyProtocol.Input input(String text) {
    return new CapturePolicyProtocol.Input(text.getBytes(StandardCharsets.UTF_8));
  }
}
