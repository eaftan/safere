// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdaptiveCaptureCaseTest {
  @Test
  void orderingChangesClusteringWithoutChangingHitCounts() {
    for (int every : new int[] {0, 1, 2, 16}) {
      for (String order : List.of("periodic", "blocked", "shuffled")) {
        int[] actual = AdaptiveCaptureCase.schedule(512, every, order, 64, 19);
        assertThat(Arrays.stream(actual).filter(slot -> slot == 0).count())
            .isEqualTo(every == 0 ? 0 : 512 / every);
        assertThat(actual).isEqualTo(AdaptiveCaptureCase.schedule(512, every, order, 64, 19));
      }
    }
    assertThat(AdaptiveCaptureCase.schedule(512, 2, "shuffled", 64, 19))
        .isNotEqualTo(AdaptiveCaptureCase.schedule(512, 2, "shuffled", 64, 20));
  }

  @Test
  void everyDeclaredLifetimeAndPhaseScheduleMatchesIndependentJdkCaptures() throws Exception {
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
    JsonObject config =
        source
            .getAsJsonObject("configuration")
            .getAsJsonObject("investigation")
            .getAsJsonObject("adaptive");
    for (String scenario : config.getAsJsonObject("scenarios").keySet()) {
      for (String representation : List.of("string", "utf8")) {
        AdaptiveCaptureCase selected =
            new AdaptiveCaptureCase(
                config,
                scenario,
                representation,
                plan::resolve,
                key -> new CapturePolicyProtocol.Input(inputs.get(key)));
        selected.validate();
        AdaptiveCaptureCase.Task steps = selected.task(false);
        long sampled = 0;
        for (int i = 0; i < 1536; i++) {
          sampled += steps.runOne();
        }
        assertThat(sampled).isEqualTo(selected.oracle(false));
        AdaptiveCaptureCase.Task task = selected.task(false);
        assertThat(task.run(null))
            .as(scenario + " " + representation)
            .isEqualTo(selected.oracle(false));
        assertThat(task.run(null)).isEqualTo(selected.oracle(false));
        AdaptivePhaseCounters counters = new AdaptivePhaseCounters();
        assertThat(task.run(counters)).isEqualTo(selected.oracle(false));
        assertThat(counters.phase0Documents).isEqualTo(512);
        assertThat(counters.phase1Documents).isEqualTo(512);
        assertThat(counters.phase2Documents).isEqualTo(512);
        AdaptiveCaptureCase.Shared shared = selected.shared();
        assertThat(shared.task(true).run(null)).isEqualTo(selected.oracle(true));
        assertThat(shared.task(false).run(null)).isEqualTo(selected.oracle(false));
      }
    }
  }
}
