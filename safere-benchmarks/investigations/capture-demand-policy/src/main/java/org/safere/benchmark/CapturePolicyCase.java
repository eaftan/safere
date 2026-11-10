// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.function.Function;

/** Joins materialized inputs and investigation-owned protocol settings. */
final class CapturePolicyCase {
  private final CapturePolicyProtocol.Compiled pattern;
  private final List<CapturePolicyProtocol.Input> inputs;
  private final int group;
  private final boolean retained;
  private final int rounds;
  private final CapturePolicyProtocol.Usage usage;
  private final int expected;

  CapturePolicyCase(
      CrossEngineBenchmarkPlan.Trial trial,
      Function<String, CapturePolicyProtocol.Input> inputSource,
      JsonObject configuration,
      String consumption) {
    CrossEngineWorkload workload = trial.workload();
    if (workload.operation() != BenchmarkOperation.FIND_SAMPLED_GROUP) {
      throw new IllegalArgumentException("Expected a find-and-capture-participation workload");
    }
    JsonObject declared = configuration.getAsJsonObject("usages");
    if (!declared.has(consumption)) {
      throw new IllegalArgumentException("Unknown capture usage: " + consumption);
    }
    JsonObject settings = declared.getAsJsonObject(consumption);
    usage =
        new CapturePolicyProtocol.Usage(
            settings.get("mode").getAsString(), settings.get("readEvery").getAsInt());
    rounds = configuration.get("rounds").getAsInt();
    group = workload.groups()[0];
    retained = workload.lifecycle().matcher() == DeclarativeBenchmarkPlan.MatcherReuse.RETAINED;
    inputs = workload.inputKeys().stream().map(inputSource).toList();
    pattern = CapturePolicyProtocol.compile(workload.patterns().getFirst(), trial.variant());
    expected =
        configuration
            .getAsJsonObject("checksums")
            .getAsJsonObject(workload.id())
            .get(consumption)
            .getAsInt();
  }

  static CapturePolicyCase load(String trialId, String consumption) {
    BenchmarkData data = BenchmarkData.get();
    return new CapturePolicyCase(
        CrossEngineBenchmarkPlan.load().resolve(trialId),
        key -> new CapturePolicyProtocol.Input(data.getInputString(key), data.getInputBytes(key)),
        data.investigationConfiguration(),
        consumption);
  }

  CapturePolicyProtocol.Task task() {
    return CapturePolicyProtocol.bind(pattern, inputs, group, retained, rounds, usage);
  }

  CapturePolicyProtocol.Task booleanTask() {
    return CapturePolicyProtocol.bind(
        pattern, inputs, group, retained, rounds, new CapturePolicyProtocol.Usage("boolean", 1));
  }

  void validate() {
    if (task().run() != expected) {
      throw new IllegalArgumentException(
          "Capture-policy cycle did not match its declared checksum");
    }
  }
}
