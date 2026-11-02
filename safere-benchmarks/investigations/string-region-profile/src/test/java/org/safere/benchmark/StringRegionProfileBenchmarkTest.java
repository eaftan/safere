// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;
import org.openjdk.jmh.runner.options.TimeValue;
import org.safere.OperationDiagnostics;
import org.safere.Pattern;
import org.safere.SafeReMatchDiagnostics;

class StringRegionProfileBenchmarkTest {
  private static CrossEngineBenchmarkPlan plan;
  private static Map<String, byte[]> inputs;

  @BeforeAll
  static void loadInvestigation() throws Exception {
    Path file = Path.of("investigations/string-region-profile/benchmark-data.json");
    if (!Files.exists(file)) {
      file = Path.of("safere-benchmarks").resolve(file);
    }
    JsonObject data = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    inputs = BenchmarkInputMaterializer.materialize(data);
    plan =
        CrossEngineBenchmarkPlan.fromMaterialized(
            MaterializedExecutionPlan.parse(ResolvedBenchmarkPlan.create(data))
                .entriesForRunner("java"));
  }

  private static void setup(StringRegionProfileBenchmark benchmark) {
    benchmark.setup(plan, key -> new String(inputs.get(key), StandardCharsets.UTF_8));
  }

  static Stream<Arguments> histories() {
    return Stream.of("literal", "nullable")
        .flatMap(
            pattern ->
                Stream.of(32, 4096)
                    .flatMap(
                        size ->
                            Stream.of("plain", "mixed")
                                .map(history -> Arguments.of(pattern, size, history))));
  }

  @ParameterizedTest
  @MethodSource("histories")
  void bothHistoriesSearchEquivalentTextBeforeMeasuringOnlyTheTarget(
      String pattern, int size, String history) {
    StringRegionProfileBenchmark benchmark = paired(pattern, size, history);
    setup(benchmark);
    Blackhole blackhole =
        new Blackhole(
            "Today's password is swordfish. I understand instantiating Blackholes directly is"
                + " dangerous.");
    List<OperationDiagnostics> operations = new ArrayList<>();
    Pattern.setDiagnostics(
        new SafeReMatchDiagnostics() {
          @Override
          public void onOperationCompleted(OperationDiagnostics event) {
            operations.add(event);
          }
        });
    try {
      for (IterationType phase :
          new IterationType[] {IterationType.WARMUP, IterationType.MEASUREMENT}) {
        benchmark.iteration(new IterationParams(phase, 1, new TimeValue(1, TimeUnit.SECONDS), 1));
        for (int invocation = 0; invocation < 3; invocation++) {
          operations.clear();
          assertThat(benchmark.find(blackhole)).isTrue();
          assertThat(operations).hasSize(phase == IterationType.WARMUP ? 2 : 1);
          assertThat(operations.getLast().inputLength()).isEqualTo(size);
          if (phase == IterationType.WARMUP) {
            assertThat(operations.getFirst().inputLength())
                .isEqualTo(history.equals("mixed") ? size + 2 : size);
          }
        }
      }
    } finally {
      Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
    }
  }

  @Test
  void rejectsDifferentPatternsAndDifferentVisibleText() {
    StringRegionProfileBenchmark patterns = paired("literal", 32, "plain");
    patterns.regionTrial = "StringRegionProfile.nullable.32.region@safere-string";
    assertThatThrownBy(() -> setup(patterns))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("equivalent");

    StringRegionProfileBenchmark inputs = paired("literal", 32, "plain");
    inputs.regionTrial = "StringRegionProfile.literal.4096.region@safere-string";
    assertThatThrownBy(() -> setup(inputs))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exactly the target text");
  }

  private static StringRegionProfileBenchmark paired(String pattern, int size, String history) {
    StringRegionProfileBenchmark benchmark = new StringRegionProfileBenchmark();
    String prefix = "StringRegionProfile." + pattern + "." + size;
    benchmark.plainTrial = prefix + ".plain@safere-string";
    benchmark.regionTrial = prefix + ".region@safere-string";
    benchmark.history = history;
    return benchmark;
  }
}
