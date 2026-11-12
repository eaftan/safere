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

class CaptureCutoffBenchmarkTest {
  @Test
  void schedulesAndCaptureTextAgreeWithJdkAcrossTheDeclaredCorpus() throws Exception {
    Path source = Path.of("investigations/capture-recovery/benchmark-data.json");
    if (!Files.exists(source)) {
      source = Path.of("safere-benchmarks").resolve(source);
    }
    JsonObject data = JsonParser.parseString(Files.readString(source)).getAsJsonObject();
    Map<String, byte[]> inputs = BenchmarkInputMaterializer.materialize(data);
    CrossEngineBenchmarkPlan plan =
        CrossEngineBenchmarkPlan.fromMaterialized(
            MaterializedExecutionPlan.parse(
                    ResolvedBenchmarkPlan.create(PatternProfiles.normalizeInline(data)))
                .entriesForRunner("java"));
    for (CrossEngineBenchmarkPlan.Trial trial :
        plan.trials(CrossEngineWorkload.TimingGroup.NANOSECONDS)) {
      if (!List.of(
              RegexEngineVariant.SAFERE_STRING,
              RegexEngineVariant.SAFERE_UTF8,
              RegexEngineVariant.JDK_STRING)
          .contains(trial.variant())) {
        continue;
      }
      CrossEngineWorkload workload = trial.workload();
      int size = Integer.parseInt(workload.id().substring(workload.id().lastIndexOf('.') + 1));
      List<RegexEngineVariant.RegexInput> prepared =
          workload.inputKeys().stream()
              .map(
                  key -> {
                    byte[] bytes = inputs.get(key);
                    assertThat(bytes.length).isEqualTo(size);
                    return (trial.variant() == RegexEngineVariant.SAFERE_UTF8
                        ? RegexEngineVariant.utf8Input(bytes)
                        : new RegexEngineVariant.StringRegexInput(
                            new String(bytes, StandardCharsets.UTF_8)));
                  })
              .toList();
      String captured = workload.id().contains(".stack.") ? "com.example.Service" : "word";
      for (String operation : List.of("matches", "lookingAt", "find")) {
        for (int hitEvery : new int[] {0, 1, 16}) {
          for (int readEvery : new int[] {0, 1, 4}) {
            long expected = 0;
            int hits = 0;
            for (int doc = 0; doc < 16; doc++) {
              if (hitEvery > 0 && doc % hitEvery == 0) {
                expected++;
                if (readEvery > 0 && hits++ % readEvery == 0) {
                  expected = expected * 31 + captured.hashCode();
                }
              }
            }
            try (var task =
                new CaptureCutoffTask(
                    trial.variant(),
                    workload.patterns().getFirst(),
                    prepared,
                    operation,
                    16,
                    hitEvery,
                    readEvery)) {
              assertThat(task.run())
                  .as("%s/%s/%s/%s", trial.id(), operation, hitEvery, readEvery)
                  .isEqualTo(expected);
              assertThat(task.run()).isEqualTo(expected);
            }
          }
        }
      }
    }
  }
}
