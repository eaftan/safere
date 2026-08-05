// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonParser;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeclarativeBenchmarkPlanTest {

  @Test
  void syntheticWorkloadAndEnginesExtendPlanWithoutPlannerBranches() {
    DeclarativeBenchmarkPlan plan =
        parse(
            """
            {
              "schemaVersion": 1,
              "inputs": [{
                "id": "generated.input.{size}",
                "axes": {"size": [8, 16]},
                "recipe": {
                  "kind": "repeatToLength",
                  "unit": "ab",
                  "length": "{size}"
                }
              }],
              "workloads": [{
                "id": "ScalingBenchmark.find.{size}.{match}",
                "operation": "find",
                "patterns": ["needle-{match}"],
                "inputs": ["generated.input.{size}"],
                "axes": {
                  "size": [8, 16],
                  "match": [true, false]
                },
                "requirements": ["find"],
                "resultConsumption": "boolean",
                "expected": {"type": "boolean", "axis": "match"},
                "measurement": {
                  "mode": "averageTime",
                  "timingUnit": "microseconds",
                  "constraints": ["prematerializedInput"]
                }
              }]
            }
            """);

    DeclarativeBenchmarkPlan.EngineDeclaration engine =
        engine(
            "test-string",
            DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
            DeclarativeBenchmarkPlan.Feature.FIND);
    DeclarativeBenchmarkPlan.EngineDeclaration additionalEngine =
        engine(
            "additional-string",
            DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
            DeclarativeBenchmarkPlan.Feature.FIND);
    DeclarativeBenchmarkPlan.ExpandedPlan first =
        plan.expand(
            List.of(engine, additionalEngine), EnumSet.of(DeclarativeBenchmarkPlan.Operation.FIND));
    DeclarativeBenchmarkPlan.ExpandedPlan second =
        plan.expand(
            List.of(engine, additionalEngine), EnumSet.of(DeclarativeBenchmarkPlan.Operation.FIND));

    assertThat(plan.inputs().keySet()).containsExactly("generated.input.8", "generated.input.16");
    assertThat(plan.inputs().get("generated.input.8").recipe().arguments().get("length"))
        .isEqualTo(new DeclarativeBenchmarkPlan.RecipeInteger(8));
    assertThat(first.workloads().stream().map(DeclarativeBenchmarkPlan.ExpandedWorkload::id))
        .containsExactly(
            "ScalingBenchmark.find.8.true",
            "ScalingBenchmark.find.8.false",
            "ScalingBenchmark.find.16.true",
            "ScalingBenchmark.find.16.false");
    assertThat(first.trials().stream().map(DeclarativeBenchmarkPlan.Trial::id))
        .containsExactlyElementsOf(
            second.trials().stream().map(DeclarativeBenchmarkPlan.Trial::id).toList())
        .hasSize(8)
        .allMatch(id -> id.endsWith("@test-string") || id.endsWith("@additional-string"));
    assertThat(
            first.workloads().stream().map(workload -> workload.expected().value().getAsBoolean()))
        .containsExactly(true, false, true, false);
  }

  @Test
  void patternAxisSubstitutionPreservesRegexBraceSyntax() {
    DeclarativeBenchmarkPlan plan =
        parse(
            planJson(
                """
                [{
                  "id": "Synthetic.braces.{suffix}",
                  "operation": "find",
                  "patterns": ["\\\\x{feff}{suffix}"],
                  "inputs": ["literal.input"],
                  "axes": {"suffix": ["x"]},
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }]
                """));
    DeclarativeBenchmarkPlan.EngineDeclaration engine =
        engine(
            "string",
            DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
            DeclarativeBenchmarkPlan.Feature.FIND);

    DeclarativeBenchmarkPlan.ExpandedPlan expanded =
        plan.expand(List.of(engine), EnumSet.of(DeclarativeBenchmarkPlan.Operation.FIND));

    assertThat(expanded.workloads())
        .singleElement()
        .extracting(workload -> workload.patterns().getFirst())
        .isEqualTo("\\x{feff}x");
  }

  @Test
  void labeledAxisSeparatesStableIdentityFromPatternValue() {
    DeclarativeBenchmarkPlan plan =
        parse(
            """
            {
              "schemaVersion": 1,
              "inputs": [],
              "workloads": [{
                  "id": "Synthetic.unicode.{regex}",
                  "operation": "compile",
                  "patterns": ["{regex}"],
                  "axes": {
                    "regex": [{"id": "letter", "value": "\\\\p{L}+"}]
                  },
                  "resultConsumption": "compiledObject",
                  "measurement": {"mode": "compileOnly", "timingUnit": "microseconds"}
              }]
            }
            """);
    DeclarativeBenchmarkPlan.EngineDeclaration engine =
        engine("string", DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING);

    DeclarativeBenchmarkPlan.ExpandedPlan expanded =
        plan.expand(List.of(engine), EnumSet.of(DeclarativeBenchmarkPlan.Operation.COMPILE));

    assertThat(expanded.workloads())
        .singleElement()
        .satisfies(
            workload -> {
              assertThat(workload.id()).isEqualTo("Synthetic.unicode.letter");
              assertThat(workload.patterns()).containsExactly("\\p{L}+");
            });
  }

  @Test
  void labeledAxisUsesRuntimeValueInRecipeAndOperationArguments() {
    DeclarativeBenchmarkPlan plan =
        parse(
            """
            {
              "schemaVersion": 1,
              "inputs": [{
                "id": "generated.{text}",
                "axes": {
                  "text": [{"id": "greeting", "value": "hello"}]
                },
                "recipe": {"kind": "literal", "text": "{text}"}
              }],
              "workloads": [{
                "id": "Synthetic.replace.{replacement}",
                "operation": "replaceAll",
                "patterns": ["x"],
                "inputs": ["generated.greeting"],
                "axes": {
                  "replacement": [{"id": "bang", "value": "$0!"}]
                },
                "arguments": {"replacement": "{replacement}"},
                "resultConsumption": "string",
                "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
              }]
            }
            """);
    DeclarativeBenchmarkPlan.EngineDeclaration engine =
        engine(
            "string",
            DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
            DeclarativeBenchmarkPlan.Feature.REPLACE);

    DeclarativeBenchmarkPlan.ExpandedPlan expanded =
        plan.expand(List.of(engine), EnumSet.of(DeclarativeBenchmarkPlan.Operation.REPLACE_ALL));

    assertThat(plan.inputs()).containsKey("generated.greeting");
    assertThat(plan.inputs().get("generated.greeting").recipe().arguments().get("text"))
        .isEqualTo(new DeclarativeBenchmarkPlan.RecipeString("hello"));
    assertThat(expanded.workloads())
        .singleElement()
        .satisfies(
            workload -> {
              assertThat(workload.id()).isEqualTo("Synthetic.replace.bang");
              assertThat(workload.arguments().get("replacement"))
                  .isEqualTo(new DeclarativeBenchmarkPlan.RecipeString("$0!"));
            });
  }

  @Test
  void omittedInputRepresentationsAcceptsEveryEngineRepresentation() {
    DeclarativeBenchmarkPlan plan =
        parse(
            planJson(
                """
                [{
                  "id": "Synthetic.find",
                  "operation": "find",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "requirements": ["find"],
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }]
                """));

    DeclarativeBenchmarkPlan.ExpandedPlan expanded =
        plan.expand(
            List.of(
                engine(
                    "string",
                    DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
                    DeclarativeBenchmarkPlan.Feature.FIND),
                engine(
                    "utf8",
                    DeclarativeBenchmarkPlan.InputRepresentation.PREEXISTING_UTF8,
                    DeclarativeBenchmarkPlan.Feature.FIND),
                engine(
                    "conversion",
                    DeclarativeBenchmarkPlan.InputRepresentation
                        .JAVA_STRING_WITH_TIMED_UTF8_CONVERSION,
                    DeclarativeBenchmarkPlan.Feature.FIND)),
            EnumSet.of(DeclarativeBenchmarkPlan.Operation.FIND));

    assertThat(expanded.trials().stream().map(DeclarativeBenchmarkPlan.Trial::id))
        .containsExactly(
            "Synthetic.find@string", "Synthetic.find@utf8", "Synthetic.find@conversion");
  }

  @Test
  void explicitInputRepresentationRestrictionFiltersEnginesAndPreservesReason() {
    DeclarativeBenchmarkPlan plan =
        parse(
            planJson(
                """
                [{
                  "id": "Synthetic.find",
                  "operation": "find",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "requirements": ["find"],
                  "inputRepresentations": ["javaString"],
                  "inputRepresentationReason":
                      "This workload measures an API whose input is a Java String.",
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }]
                """));

    DeclarativeBenchmarkPlan.ExpandedPlan expanded =
        plan.expand(
            List.of(
                engine(
                    "string",
                    DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
                    DeclarativeBenchmarkPlan.Feature.FIND),
                engine(
                    "utf8",
                    DeclarativeBenchmarkPlan.InputRepresentation.PREEXISTING_UTF8,
                    DeclarativeBenchmarkPlan.Feature.FIND)),
            EnumSet.of(DeclarativeBenchmarkPlan.Operation.FIND));

    assertThat(expanded.workloads())
        .singleElement()
        .extracting(DeclarativeBenchmarkPlan.ExpandedWorkload::inputRepresentationReason)
        .isEqualTo("This workload measures an API whose input is a Java String.");
    assertThat(expanded.trials())
        .singleElement()
        .extracting(DeclarativeBenchmarkPlan.Trial::id)
        .isEqualTo("Synthetic.find@string");
    assertThat(expanded.exclusions())
        .singleElement()
        .satisfies(
            exclusion -> {
              assertThat(exclusion.engineId()).isEqualTo("utf8");
              assertThat(exclusion.kind())
                  .isEqualTo(
                      DeclarativeBenchmarkPlan.ExclusionKind.UNSUPPORTED_INPUT_REPRESENTATION);
            });
  }

  @Test
  void explicitInputRepresentationsRequireANonblankReason() {
    String workload =
        """
        [{
          "id": "Synthetic.find",
          "operation": "find",
          "patterns": ["x"],
          "inputs": ["literal.input"],
          "requirements": ["find"],
          "inputRepresentations": ["javaString"],
          "resultConsumption": "boolean",
          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
        }]
        """;

    assertThatThrownBy(() -> parse(planJson(workload)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "requires a nonblank inputRepresentationReason when inputRepresentations is set");
    assertThatThrownBy(
            () ->
                parse(
                    planJson(
                        workload.replace(
                            "\"resultConsumption\"",
                            "\"inputRepresentationReason\": \"  \","
                                + System.lineSeparator()
                                + "  \"resultConsumption\""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "requires a nonblank inputRepresentationReason when inputRepresentations is set");
  }

  @Test
  void inputRepresentationReasonWithoutARestrictionIsRejected() {
    assertThatThrownBy(
            () ->
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.find",
                          "operation": "find",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "requirements": ["find"],
                          "inputRepresentationReason": "There is no corresponding restriction.",
                          "resultConsumption": "boolean",
                          "measurement": {
                            "mode": "averageTime",
                            "timingUnit": "nanoseconds"
                          }
                        }]
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "must not declare inputRepresentationReason without inputRepresentations");
  }

  @Test
  void explicitlyListingEveryInputRepresentationIsRejected() {
    assertThatThrownBy(
            () ->
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.find",
                          "operation": "find",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "requirements": ["find"],
                          "inputRepresentations": [
                            "javaString",
                            "preexistingUtf8",
                            "javaStringWithTimedUtf8Conversion"
                          ],
                          "inputRepresentationReason":
                              "This fixture verifies that redundant all-value lists are rejected.",
                          "resultConsumption": "boolean",
                          "measurement": {
                            "mode": "averageTime",
                            "timingUnit": "nanoseconds"
                          }
                        }]
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("omit inputRepresentations instead");
  }

  @Test
  void addingWorkloadAndEngineAxesProducesEveryCompatiblePair() {
    DeclarativeBenchmarkPlan plan =
        parse(
            planJson(
                """
                [{
                  "id": "Synthetic.find",
                  "operation": "find",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "requirements": ["find"],
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }, {
                  "id": "Synthetic.matches",
                  "operation": "matches",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "requirements": ["matches"],
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }]
                """));
    DeclarativeBenchmarkPlan.EngineDeclaration stringEngine =
        engine(
            "string",
            DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
            DeclarativeBenchmarkPlan.Feature.FIND,
            DeclarativeBenchmarkPlan.Feature.MATCHES);
    DeclarativeBenchmarkPlan.EngineDeclaration utf8Engine =
        engine(
            "utf8",
            DeclarativeBenchmarkPlan.InputRepresentation.PREEXISTING_UTF8,
            DeclarativeBenchmarkPlan.Feature.FIND);

    DeclarativeBenchmarkPlan.ExpandedPlan expanded =
        plan.expand(
            List.of(stringEngine, utf8Engine),
            EnumSet.of(
                DeclarativeBenchmarkPlan.Operation.FIND,
                DeclarativeBenchmarkPlan.Operation.MATCHES));

    assertThat(expanded.trials().stream().map(DeclarativeBenchmarkPlan.Trial::id))
        .containsExactly(
            "Synthetic.find@string", "Synthetic.find@utf8", "Synthetic.matches@string");
    assertThat(expanded.exclusions())
        .singleElement()
        .satisfies(
            exclusion -> {
              assertThat(exclusion.workloadId()).isEqualTo("Synthetic.matches");
              assertThat(exclusion.engineId()).isEqualTo("utf8");
              assertThat(exclusion.kind())
                  .isEqualTo(DeclarativeBenchmarkPlan.ExclusionKind.UNSUPPORTED_FEATURE);
            });
  }

  @Test
  void operationsFlagsAndLifecycleDeriveEngineNeutralRequirements() {
    DeclarativeBenchmarkPlan plan =
        parse(
            planJson(
                """
                [{
                  "id": "Synthetic.region",
                  "operation": "matcherRegionFind",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "flags": ["caseInsensitive"],
                  "resultConsumption": "boolean",
                  "lifecycle": {
                    "matcher": "retained",
                    "steps": [{"kind": "region", "start": 0, "end": 1}]
                  },
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }]
                """));

    assertThat(plan.workloads())
        .singleElement()
        .satisfies(
            workload ->
                assertThat(workload.requirements())
                    .containsExactlyInAnyOrder(
                        DeclarativeBenchmarkPlan.Feature.FIND,
                        DeclarativeBenchmarkPlan.Feature.MATCHER_STATE,
                        DeclarativeBenchmarkPlan.Feature.REGIONS));
  }

  @Test
  void schemaRepresentsOrdinaryStatefulPatternSetMemoryAndColdStartWorkloads() {
    DeclarativeBenchmarkPlan plan =
        parse(
            planJson(
                """
                [{
                  "id": "Synthetic.ordinary",
                  "operation": "replaceAll",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "arguments": {"replacement": "y"},
                  "resultConsumption": "string",
                  "expected": {"type": "string", "value": "y"},
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }, {
                  "id": "Synthetic.patternSet",
                  "operation": "patternSetFind",
                  "patterns": ["x", "y"],
                  "inputs": ["literal.input"],
                  "arguments": {"anchor": "unanchored"},
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }, {
                  "id": "Synthetic.memory",
                  "operation": "find",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "retainedMemory", "timingUnit": "bytes"}
                }, {
                  "id": "Synthetic.compile",
                  "operation": "compile",
                  "patterns": ["x"],
                  "resultConsumption": "compiledObject",
                  "measurement": {"mode": "compileOnly", "timingUnit": "nanoseconds"}
                }, {
                  "id": "Synthetic.cold",
                  "operation": "compile",
                  "patterns": ["x"],
                  "resultConsumption": "compiledObject",
                  "measurement": {
                    "mode": "singleShotColdStart",
                    "timingUnit": "milliseconds",
                    "constraints": ["freshProcessPerInvocation"]
                  }
                }]
                """));

    assertThat(plan.workloads())
        .extracting(DeclarativeBenchmarkPlan.WorkloadDeclaration::idTemplate)
        .containsExactly(
            "Synthetic.ordinary",
            "Synthetic.patternSet",
            "Synthetic.memory",
            "Synthetic.compile",
            "Synthetic.cold");
  }

  @Test
  void exclusionsDistinguishMissingAdapterFromSupportedTrials() {
    DeclarativeBenchmarkPlan plan =
        parse(
            planJson(
                """
                [{
                  "id": "Synthetic.find",
                  "operation": "find",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "requirements": ["find"],
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }]
                """));

    DeclarativeBenchmarkPlan.EngineDeclaration supported =
        engine(
            "supported",
            DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
            DeclarativeBenchmarkPlan.Feature.FIND);
    DeclarativeBenchmarkPlan.EngineDeclaration missingAdapter =
        new DeclarativeBenchmarkPlan.EngineDeclaration(
            "missing-adapter",
            DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
            EnumSet.of(DeclarativeBenchmarkPlan.Feature.FIND),
            EnumSet.allOf(DeclarativeBenchmarkPlan.Flag.class),
            false);
    DeclarativeBenchmarkPlan.ExpandedPlan expanded =
        plan.expand(
            List.of(supported, missingAdapter),
            EnumSet.of(DeclarativeBenchmarkPlan.Operation.FIND));

    assertThat(expanded.trials().stream().map(DeclarativeBenchmarkPlan.Trial::id))
        .containsExactly("Synthetic.find@supported");
    assertThat(expanded.exclusions())
        .extracting(DeclarativeBenchmarkPlan.Exclusion::kind)
        .containsExactly(DeclarativeBenchmarkPlan.ExclusionKind.MISSING_ENGINE_ADAPTER);

    assertThatThrownBy(
            () ->
                plan.expand(
                    List.of(supported), EnumSet.of(DeclarativeBenchmarkPlan.Operation.MATCHES)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("produces no supported trials")
        .hasMessageContaining("MISSING_OPERATION_IMPLEMENTATION");
  }

  @Test
  void trialExclusionsSelectExactExpandedEnginePairs() {
    DeclarativeBenchmarkPlan plan =
        parse(
            planJson(
                """
                [{
                  "id": "RealWorldRegexBenchmark.runBenchmark.recitation.{matchLabel}.{size}",
                  "operation": "replaceAll",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "arguments": {"replacement": ""},
                  "axes": {
                    "matchLabel": ["match", "noMatch"],
                    "size": [1000, 10000, 100000]
                  },
                  "trialExclusions": [{
                    "engineIds": ["jdk-string"],
                    "when": {"size": [10000, 100000]},
                    "reason": "OpenJDK 26.0.2 throws StackOverflowError for the nested quantifiers"
                  }],
                  "resultConsumption": "string",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }]
                """));

    DeclarativeBenchmarkPlan.ExpandedPlan expanded =
        plan.expand(
            List.of(
                engine(
                    "safere-string",
                    DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
                    DeclarativeBenchmarkPlan.Feature.REPLACE),
                engine(
                    "jdk-string",
                    DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
                    DeclarativeBenchmarkPlan.Feature.REPLACE)),
            EnumSet.of(DeclarativeBenchmarkPlan.Operation.REPLACE_ALL));

    assertThat(expanded.exclusions())
        .extracting(
            exclusion -> exclusion.workloadId() + "@" + exclusion.engineId(),
            DeclarativeBenchmarkPlan.Exclusion::kind,
            DeclarativeBenchmarkPlan.Exclusion::reason)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "RealWorldRegexBenchmark.runBenchmark.recitation.match.10000@jdk-string",
                DeclarativeBenchmarkPlan.ExclusionKind.EXPLICIT_TRIAL_EXCLUSION,
                "OpenJDK 26.0.2 throws StackOverflowError for the nested quantifiers"),
            org.assertj.core.groups.Tuple.tuple(
                "RealWorldRegexBenchmark.runBenchmark.recitation.match.100000@jdk-string",
                DeclarativeBenchmarkPlan.ExclusionKind.EXPLICIT_TRIAL_EXCLUSION,
                "OpenJDK 26.0.2 throws StackOverflowError for the nested quantifiers"),
            org.assertj.core.groups.Tuple.tuple(
                "RealWorldRegexBenchmark.runBenchmark.recitation.noMatch.10000@jdk-string",
                DeclarativeBenchmarkPlan.ExclusionKind.EXPLICIT_TRIAL_EXCLUSION,
                "OpenJDK 26.0.2 throws StackOverflowError for the nested quantifiers"),
            org.assertj.core.groups.Tuple.tuple(
                "RealWorldRegexBenchmark.runBenchmark.recitation.noMatch.100000@jdk-string",
                DeclarativeBenchmarkPlan.ExclusionKind.EXPLICIT_TRIAL_EXCLUSION,
                "OpenJDK 26.0.2 throws StackOverflowError for the nested quantifiers"));
    assertThat(expanded.trials().stream().map(DeclarativeBenchmarkPlan.Trial::id))
        .hasSize(8)
        .contains(
            "RealWorldRegexBenchmark.runBenchmark.recitation.match.1000@jdk-string",
            "RealWorldRegexBenchmark.runBenchmark.recitation.noMatch.1000@jdk-string")
        .filteredOn(id -> id.endsWith("@safere-string"))
        .hasSize(6);
  }

  @Test
  void trialExclusionsSupportConjunctiveLabeledSelectorsAndWorkloadWideRules() {
    DeclarativeBenchmarkPlan plan =
        parse(
            planJson(
                """
                [{
                  "id": "Synthetic.find.{case}.{size}",
                  "operation": "find",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "axes": {
                    "case": [{"id": "hit", "value": true}, {"id": "miss", "value": false}],
                    "size": [8, 16]
                  },
                  "trialExclusions": [{
                    "engineIds": ["first", "second"],
                    "when": {"case": [{"id": "hit", "value": true}], "size": [16]},
                    "reason": "conjunctive selector"
                  }, {
                    "engineIds": ["third"],
                    "when": {},
                    "reason": "all workload expansions"
                  }],
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }]
                """));

    DeclarativeBenchmarkPlan.ExpandedPlan expanded =
        plan.expand(
            List.of(
                engine(
                    "first",
                    DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
                    DeclarativeBenchmarkPlan.Feature.FIND),
                engine(
                    "second",
                    DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
                    DeclarativeBenchmarkPlan.Feature.FIND),
                engine(
                    "third",
                    DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
                    DeclarativeBenchmarkPlan.Feature.FIND),
                engine(
                    "unexcluded",
                    DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
                    DeclarativeBenchmarkPlan.Feature.FIND)),
            EnumSet.of(DeclarativeBenchmarkPlan.Operation.FIND));

    assertThat(expanded.exclusions())
        .extracting(exclusion -> exclusion.workloadId() + "@" + exclusion.engineId())
        .containsExactly(
            "Synthetic.find.hit.8@third",
            "Synthetic.find.hit.16@first",
            "Synthetic.find.hit.16@second",
            "Synthetic.find.hit.16@third",
            "Synthetic.find.miss.8@third",
            "Synthetic.find.miss.16@third");
  }

  @Test
  void trialExclusionsRejectInvalidSelectorsAndOverlaps() {
    assertInvalidTrialExclusion("{}", "requires engineIds");
    assertInvalidTrialExclusion(
        "{\"engineIds\": [], \"reason\": \"reason\"}", "requires at least one engine ID");
    assertInvalidTrialExclusion("{\"engineIds\": [\"jdk-string\"]}", "requires reason");
    assertInvalidTrialExclusion(
        "{\"engineIds\": [\"jdk-string\", \"jdk-string\"], \"reason\": \"reason\"}",
        "duplicate engine ID");
    assertInvalidTrialExclusion(
        "{\"engineIds\": [\"jdk-string\"], \"reason\": \"  \"}", "reason must not be blank");
    assertInvalidTrialExclusion(
        "{\"engineIds\": [\"jdk-string\"], \"when\": {\"unknown\": [8]},"
            + " \"reason\": \"reason\"}",
        "references unknown axis");
    assertInvalidTrialExclusion(
        "{\"engineIds\": [\"jdk-string\"], \"when\": {\"size\": []}," + " \"reason\": \"reason\"}",
        "selector must not be empty");
    assertInvalidTrialExclusion(
        "{\"engineIds\": [\"jdk-string\"], \"when\": {\"size\": [32]},"
            + " \"reason\": \"reason\"}",
        "selects undeclared axis value");
    assertInvalidTrialExclusion(
        "{\"engineIds\": [\"jdk-string\"], \"when\": {\"size\": [8, 8]},"
            + " \"reason\": \"reason\"}",
        "duplicate axis value");
    assertInvalidTrialExclusion(
        "{\"engineIds\": [\"jdk-string\"], \"reason\": \"reason\"," + " \"unknown\": true}",
        "has unknown field: unknown");
    assertThatThrownBy(
            () ->
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.find",
                          "operation": "find",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "trialExclusions": {},
                          "resultConsumption": "boolean",
                          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                        }]
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trialExclusions must be an array");
    assertInvalidTrialExclusion(
        "{\"engineIds\": [\"jdk-string\"], \"when\": [], \"reason\": \"reason\"}",
        "when must be an object");

    assertThatThrownBy(
            () ->
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.find",
                          "operation": "find",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "trialExclusions": [{
                            "engineIds": ["jdk-string"],
                            "reason": "reason"
                          }],
                          "disabledReason": "disabled",
                          "resultConsumption": "boolean",
                          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                        }]
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not declare both disabledReason and trialExclusions");

    DeclarativeBenchmarkPlan unknownEngine =
        planWithTrialExclusions("{\"engineIds\": [\"jdk-string\"], \"reason\": \"reason\"}");
    assertThatThrownBy(
            () ->
                unknownEngine.expand(
                    List.of(
                        engine(
                            "safere-string",
                            DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING)),
                    EnumSet.of(DeclarativeBenchmarkPlan.Operation.FIND)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("references unknown engine ID: jdk-string");

    DeclarativeBenchmarkPlan overlap =
        planWithTrialExclusions(
            "{\"engineIds\": [\"jdk-string\"], \"when\": {\"size\": [8]},"
                + " \"reason\": \"first\"},"
                + "{\"engineIds\": [\"jdk-string\"], \"reason\": \"second\"}");
    assertThatThrownBy(
            () ->
                overlap.expand(
                    List.of(
                        engine(
                            "jdk-string",
                            DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING)),
                    EnumSet.of(DeclarativeBenchmarkPlan.Operation.FIND)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Synthetic.find.8 has overlapping trial exclusions for engine jdk-string");
  }

  @Test
  void unsupportedFlagsHaveAMachineReadableExclusion() {
    DeclarativeBenchmarkPlan plan =
        parse(
            planJson(
                """
                [{
                  "id": "Synthetic.comments",
                  "operation": "find",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "flags": ["comments"],
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }]
                """));
    DeclarativeBenchmarkPlan.EngineDeclaration engine =
        new DeclarativeBenchmarkPlan.EngineDeclaration(
            "without-comments",
            DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
            EnumSet.of(DeclarativeBenchmarkPlan.Feature.FIND),
            EnumSet.noneOf(DeclarativeBenchmarkPlan.Flag.class),
            true);

    assertThatThrownBy(
            () -> plan.expand(List.of(engine), EnumSet.of(DeclarativeBenchmarkPlan.Operation.FIND)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("UNSUPPORTED_FLAG");
  }

  @Test
  void disabledReasonExplainsAnEmptyTrialMatrix() {
    DeclarativeBenchmarkPlan plan =
        parse(
            planJson(
                """
                [{
                  "id": "Synthetic.disabled",
                  "operation": "find",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "requirements": ["utf8Input"],
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"},
                  "disabledReason": "awaiting the dedicated UTF-8 runner"
                }]
                """));

    DeclarativeBenchmarkPlan.ExpandedPlan expanded =
        plan.expand(
            List.of(
                engine(
                    "string",
                    DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
                    DeclarativeBenchmarkPlan.Feature.FIND)),
            EnumSet.of(DeclarativeBenchmarkPlan.Operation.FIND));

    assertThat(expanded.trials()).isEmpty();
    assertThat(expanded.exclusions())
        .singleElement()
        .satisfies(
            exclusion ->
                assertThat(exclusion.kind())
                    .isEqualTo(DeclarativeBenchmarkPlan.ExclusionKind.WORKLOAD_DISABLED));
  }

  @Test
  void unexplainedEmptyTrialMatrixIsRejected() {
    DeclarativeBenchmarkPlan plan =
        parse(
            planJson(
                """
                [{
                  "id": "Synthetic.utf8Only",
                  "operation": "find",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "requirements": ["utf8Input"],
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }]
                """));

    assertThatThrownBy(
            () ->
                plan.expand(
                    List.of(
                        engine(
                            "string",
                            DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
                            DeclarativeBenchmarkPlan.Feature.FIND)),
                    EnumSet.of(DeclarativeBenchmarkPlan.Operation.FIND)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("produces no supported trials")
        .hasMessageContaining("disabledReason");
  }

  @Test
  void strictLoadingRejectsUnknownFieldsAndEnumValues() {
    assertThatThrownBy(
            () ->
                parse(
                    """
                    {
                      "schemaVersion": 2,
                      "inputs": [],
                      "workloads": []
                    }
                    """))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsupported benchmark plan schema version: 2");

    assertThatThrownBy(
            () ->
                parse(
                    """
                    {
                      "schemaVersion": 1,
                      "inputs": [],
                      "workloads": [],
                      "family": "Regex"
                    }
                    """))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("plan has unknown field: family");

    assertThatThrownBy(
            () ->
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.unknown",
                          "operation": "familySpecificOperation",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "resultConsumption": "boolean",
                          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                        }]
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unknown benchmark operation: familySpecificOperation");
  }

  @Test
  void loadingRejectsDuplicateIdsInvalidReferencesAndUnstableAxes() {
    assertThatThrownBy(
            () ->
                parse(
                    """
                    {
                      "schemaVersion": 1,
                      "inputs": [
                        {"id": "same", "recipe": {"kind": "literal", "text": "a"}},
                        {"id": "same", "recipe": {"kind": "literal", "text": "b"}}
                      ],
                      "workloads": [{
                        "id": "Synthetic.find",
                        "operation": "find",
                        "patterns": ["x"],
                        "inputs": ["same"],
                        "resultConsumption": "boolean",
                        "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                      }]
                    }
                    """))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Duplicate benchmark input ID: same");

    assertThatThrownBy(
            () ->
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.find",
                          "operation": "find",
                          "patterns": ["x"],
                          "inputs": ["missing.input"],
                          "resultConsumption": "boolean",
                          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                        }]
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Synthetic.find references unknown expanded benchmark input: missing.input");

    assertThatThrownBy(
            () ->
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.find",
                          "operation": "find",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "axes": {"size": [8, 16]},
                          "resultConsumption": "boolean",
                          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                        }]
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ID omits expansion axes")
        .hasMessageContaining("size");

    assertThatThrownBy(
            () ->
                parse(
                    """
                    {
                      "schemaVersion": 1,
                      "inputs": [{
                        "id": "orphaned.input",
                        "recipe": {"kind": "literal", "text": "x"}
                      }],
                      "workloads": [{
                        "id": "Synthetic.compile",
                        "operation": "compile",
                        "patterns": ["x"],
                        "resultConsumption": "compiledObject",
                        "measurement": {"mode": "compileOnly", "timingUnit": "nanoseconds"}
                      }]
                    }
                    """))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "Benchmark input is not referenced by a workload and is not shared: orphaned.input");
  }

  @Test
  void loadingRejectsMalformedExpectedLifecycleAndMeasurementDeclarations() {
    assertThatThrownBy(
            () ->
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.expected",
                          "operation": "find",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "resultConsumption": "boolean",
                          "expected": {"type": "integer", "value": 1},
                          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                        }]
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires expected type boolean");

    assertThatThrownBy(
            () ->
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.region",
                          "operation": "matcherRegionFind",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "resultConsumption": "boolean",
                          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                        }]
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires lifecycle");

    assertThatThrownBy(
            () ->
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.cold",
                          "operation": "compile",
                          "patterns": ["x"],
                          "resultConsumption": "compiledObject",
                          "measurement": {
                            "mode": "singleShotColdStart",
                            "timingUnit": "milliseconds"
                          }
                        }]
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("freshProcessPerInvocation");

    assertThatThrownBy(
            () ->
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.badConsumption",
                          "operation": "find",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "resultConsumption": "integer",
                          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                        }]
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("find cannot use result consumption integer");

    assertThatThrownBy(
            () ->
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.replace",
                          "operation": "replaceAll",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "resultConsumption": "string",
                          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                        }]
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("replaceAll requires arguments [replacement]");

    assertThatThrownBy(
            () ->
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.find",
                          "operation": "find",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "arguments": {"family": "Regex"},
                          "resultConsumption": "boolean",
                          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                        }]
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Synthetic.find operation find has unknown argument: family");
  }

  @Test
  void boundedRecipeVocabularyRejectsUnknownKindsAndArguments() {
    assertThatThrownBy(
            () ->
                parse(
                    """
                    {
                      "schemaVersion": 1,
                      "inputs": [{
                        "id": "bad.input",
                        "recipe": {"kind": "executeJava", "class": "FamilyGenerator"}
                      }],
                      "workloads": [{
                        "id": "Synthetic.find",
                        "operation": "find",
                        "patterns": ["x"],
                        "inputs": ["bad.input"],
                        "resultConsumption": "boolean",
                        "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                      }]
                    }
                    """))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unknown input recipe kind: executeJava");

    assertThatThrownBy(
            () ->
                parse(
                    """
                    {
                      "schemaVersion": 1,
                      "inputs": [{
                        "id": "bad.input",
                        "recipe": {
                          "kind": "literal",
                          "text": "x",
                          "family": "Regex"
                        }
                      }],
                      "workloads": [{
                        "id": "Synthetic.find",
                        "operation": "find",
                        "patterns": ["x"],
                        "inputs": ["bad.input"],
                        "resultConsumption": "boolean",
                        "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                      }]
                    }
                    """))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("bad.input recipe has unknown field: family");

    assertThatThrownBy(
            () ->
                parse(
                    """
                    {
                      "schemaVersion": 1,
                      "inputs": [{
                        "id": "bad.input",
                        "recipe": {
                          "kind": "repeatToLength",
                          "unit": "",
                          "length": 1
                        }
                      }],
                      "workloads": [{
                        "id": "Synthetic.find",
                        "operation": "find",
                        "patterns": ["x"],
                        "inputs": ["bad.input"],
                        "resultConsumption": "boolean",
                        "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                      }]
                    }
                    """))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Recipe field unit must not be empty");
  }

  private static DeclarativeBenchmarkPlan parse(String json) {
    return DeclarativeBenchmarkPlan.parse(JsonParser.parseString(json).getAsJsonObject());
  }

  private static void assertInvalidTrialExclusion(String exclusion, String message) {
    assertThatThrownBy(() -> planWithTrialExclusions(exclusion))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(message);
  }

  private static DeclarativeBenchmarkPlan planWithTrialExclusions(String exclusions) {
    return parse(
        planJson(
            """
            [{
              "id": "Synthetic.find.{size}",
              "operation": "find",
              "patterns": ["x"],
              "inputs": ["literal.input"],
              "axes": {"size": [8, 16]},
              "trialExclusions": [%s],
              "resultConsumption": "boolean",
              "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
            }]
            """
                .formatted(exclusions)));
  }

  private static String planJson(String workloads) {
    return """
    {
      "schemaVersion": 1,
      "inputs": [{
        "id": "literal.input",
        "recipe": {"kind": "literal", "text": "x"}
      }],
      "workloads": %s
    }
    """
        .formatted(workloads);
  }

  private static DeclarativeBenchmarkPlan.EngineDeclaration engine(
      String id,
      DeclarativeBenchmarkPlan.InputRepresentation representation,
      DeclarativeBenchmarkPlan.Feature... features) {
    EnumSet<DeclarativeBenchmarkPlan.Feature> featureSet =
        EnumSet.noneOf(DeclarativeBenchmarkPlan.Feature.class);
    featureSet.addAll(List.of(features));
    return new DeclarativeBenchmarkPlan.EngineDeclaration(
        id, representation, featureSet, EnumSet.allOf(DeclarativeBenchmarkPlan.Flag.class), true);
  }
}
