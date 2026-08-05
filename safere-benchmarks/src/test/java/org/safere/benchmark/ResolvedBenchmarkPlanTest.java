// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResolvedBenchmarkPlanTest {

  @Test
  void materializationAccountsForCompleteSyntheticWorkloadAndEngineJoin() {
    JsonObject plan =
        ResolvedBenchmarkPlan.create(
            normalizedData(),
            List.of(
                engine(
                    "find-only",
                    "java",
                    EnumSet.of(DeclarativeBenchmarkPlan.Feature.FIND),
                    EnumSet.of(DeclarativeBenchmarkPlan.Operation.FIND)),
                engine(
                    "find-and-replace",
                    "alternate",
                    EnumSet.of(
                        DeclarativeBenchmarkPlan.Feature.FIND,
                        DeclarativeBenchmarkPlan.Feature.REPLACE,
                        DeclarativeBenchmarkPlan.Feature.NUMBERED_REPLACEMENT),
                    EnumSet.of(
                        DeclarativeBenchmarkPlan.Operation.FIND,
                        DeclarativeBenchmarkPlan.Operation.REPLACE_ALL))));

    assertThat(plan.get("version").getAsInt()).isEqualTo(ResolvedBenchmarkPlan.VERSION);
    assertThat(plan.get("workloadCount").getAsInt()).isEqualTo(2);
    assertThat(plan.get("engineCount").getAsInt()).isEqualTo(2);
    JsonArray entries = plan.getAsJsonArray("entries");
    assertThat(entries).hasSize(4);
    assertThat(
            entries.asList().stream()
                .map(JsonObject.class::cast)
                .map(ResolvedBenchmarkPlanTest::id))
        .containsExactly(
            "Synthetic.find@find-only",
            "Synthetic.find@find-and-replace",
            "Synthetic.replace@find-only",
            "Synthetic.replace@find-and-replace");
    assertThat(
            entries.asList().stream()
                .map(JsonObject.class::cast)
                .filter(entry -> entry.get("status").getAsString().equals("runnable")))
        .hasSize(3);
    assertThat(
            entries.asList().stream()
                .map(JsonObject.class::cast)
                .filter(entry -> entry.get("status").getAsString().equals("excluded")))
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(id(entry)).isEqualTo("Synthetic.replace@find-only");
              assertThat(entry.getAsJsonObject("exclusion").get("kind").getAsString())
                  .isEqualTo("unsupportedOperation");
            });
  }

  @Test
  void runnableEntriesContainExactSelectedSyntaxAndUnchangedFallback() {
    JsonObject plan =
        ResolvedBenchmarkPlan.create(
            normalizedData(),
            List.of(
                engine(
                    "alternate-engine",
                    "alternate",
                    EnumSet.of(
                        DeclarativeBenchmarkPlan.Feature.FIND,
                        DeclarativeBenchmarkPlan.Feature.REPLACE,
                        DeclarativeBenchmarkPlan.Feature.NUMBERED_REPLACEMENT),
                    EnumSet.of(
                        DeclarativeBenchmarkPlan.Operation.FIND,
                        DeclarativeBenchmarkPlan.Operation.REPLACE_ALL))));

    JsonObject find = entry(plan, "Synthetic.find@alternate-engine");
    JsonObject replace = entry(plan, "Synthetic.replace@alternate-engine");
    assertThat(find.getAsJsonArray("patterns").get(0).getAsString()).isEqualTo("alternate-pattern");
    assertThat(replace.getAsJsonArray("patterns").get(0).getAsString())
        .isEqualTo("unchanged-pattern");
    assertThat(replace.getAsJsonObject("arguments").get("replacement").getAsString())
        .isEqualTo("alternate-replacement");
  }

  @Test
  void explicitTrialExclusionsSurviveResolutionAndMaterializedConsumption() {
    JsonObject data = normalizedData();
    JsonObject find = data.getAsJsonArray("workloads").get(0).getAsJsonObject();
    find.add(
        "trialExclusions",
        JsonParser.parseString(
            """
            [{
              "engineIds": ["jdk-string"],
              "reason": "OpenJDK 26.0.2 throws StackOverflowError for this trial"
            }]
            """));

    JsonObject resolved =
        ResolvedBenchmarkPlan.create(
            data,
            List.of(
                engine(
                    "safere-string",
                    "alternate",
                    EnumSet.of(DeclarativeBenchmarkPlan.Feature.FIND),
                    EnumSet.of(DeclarativeBenchmarkPlan.Operation.FIND)),
                engine(
                    "jdk-string",
                    "alternate",
                    EnumSet.of(DeclarativeBenchmarkPlan.Feature.FIND),
                    EnumSet.of(DeclarativeBenchmarkPlan.Operation.FIND))));

    JsonObject exclusion = entry(resolved, "Synthetic.find@jdk-string");
    assertThat(exclusion.get("status").getAsString()).isEqualTo("excluded");
    assertThat(exclusion.getAsJsonObject("exclusion").get("kind").getAsString())
        .isEqualTo("explicitTrialExclusion");
    assertThat(exclusion.getAsJsonObject("exclusion").get("reason").getAsString())
        .isEqualTo("OpenJDK 26.0.2 throws StackOverflowError for this trial");
    assertThat(entry(resolved, "Synthetic.find@safere-string").get("status").getAsString())
        .isEqualTo("runnable");

    MaterializedExecutionPlan materialized = MaterializedExecutionPlan.parse(resolved);
    assertThat(materialized.resolve("Synthetic.find@jdk-string").exclusion())
        .isEqualTo(
            new MaterializedExecutionPlan.Exclusion(
                "explicitTrialExclusion",
                "OpenJDK 26.0.2 throws StackOverflowError for this trial"));
    CrossEngineBenchmarkPlan collection =
        CrossEngineBenchmarkPlan.fromMaterialized(materialized.entriesForRunner("java"));
    assertThat(collection.trials(CrossEngineWorkload.TimingGroup.NANOSECONDS))
        .extracting(CrossEngineBenchmarkPlan.Trial::id)
        .contains("Synthetic.find@safere-string")
        .doesNotContain("Synthetic.find@jdk-string");
    assertThat(collection.exclusions())
        .extracting(MaterializedExecutionPlan.Entry::id)
        .contains("Synthetic.find@jdk-string");
  }

  private static ResolvedBenchmarkPlan.Engine engine(
      String id,
      String profile,
      EnumSet<DeclarativeBenchmarkPlan.Feature> features,
      EnumSet<DeclarativeBenchmarkPlan.Operation> operations) {
    return new ResolvedBenchmarkPlan.Engine(
        id,
        id,
        "java",
        profile,
        profile,
        new DeclarativeBenchmarkPlan.EngineDeclaration(
            id,
            DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
            features,
            EnumSet.noneOf(DeclarativeBenchmarkPlan.Flag.class),
            true),
        operations,
        EnumSet.of(DeclarativeBenchmarkPlan.MeasurementMode.AVERAGE_TIME),
        Set.of("0"));
  }

  private static JsonObject normalizedData() {
    return JsonParser.parseString(
            """
            {
              "schemaVersion": 1,
              "inputs": [{
                "id": "input",
                "recipe": {"kind": "literal", "text": "text"},
                "shared": true
              }],
              "workloads": [
                {
                  "id": "Synthetic.find",
                  "operation": "find",
                  "patterns": ["canonical-pattern"],
                  "inputs": ["input"],
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                },
                {
                  "id": "Synthetic.replace",
                  "operation": "replaceAll",
                  "patterns": ["unchanged-pattern"],
                  "inputs": ["input"],
                  "arguments": {"replacement": "canonical-replacement"},
                  "requirements": ["numberedReplacement"],
                  "resultConsumption": "string",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }
              ],
              "patternProfiles": {
                "alternate": [{
                  "java": "canonical-pattern",
                  "alternate": "alternate-pattern",
                  "reason": "synthetic alternate"
                }]
              },
              "replacementProfiles": {
                "alternate": [{
                  "java": "canonical-replacement",
                  "alternate": "alternate-replacement",
                  "reason": "synthetic alternate"
                }]
              }
            }
            """)
        .getAsJsonObject();
  }

  private static JsonObject entry(JsonObject plan, String id) {
    return plan.getAsJsonArray("entries").asList().stream()
        .map(JsonObject.class::cast)
        .filter(entry -> id(entry).equals(id))
        .findFirst()
        .orElseThrow();
  }

  private static String id(JsonObject entry) {
    return entry.get("id").getAsString();
  }
}
