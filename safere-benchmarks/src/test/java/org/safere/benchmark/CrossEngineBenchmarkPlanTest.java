// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmh.infra.Blackhole;

class CrossEngineBenchmarkPlanTest {

  @TempDir static Path temporaryDirectory;

  @BeforeAll
  static void materializeCorpus() throws Exception {
    Path benchmarkDirectory =
        Files.exists(Path.of("benchmark-data.json")) ? Path.of(".") : Path.of("safere-benchmarks");
    BenchmarkInputMaterializer.main(
        new String[] {benchmarkDirectory.toString(), temporaryDirectory.toString()});
    System.setProperty("safere.benchmark.corpus", temporaryDirectory.toString());
  }

  @Test
  void workloadIdsAreUniqueStableReportIdentities() {
    List<String> ids =
        CrossEngineBenchmarkPlan.load().workloads().stream().map(CrossEngineWorkload::id).toList();

    assertThat(ids).doesNotHaveDuplicates();
    assertThat(ids)
        .contains(
            "RegexBenchmark.literalMatch",
            "ApplicationBenchmark.uuidValidation",
            "RealWorldRegexBenchmark.runBenchmark.mapFieldPath.match.1000",
            "HttpBenchmark.httpFull",
            "SearchScalingBenchmark.searchEasyFail.1024",
            "FanoutBenchmark.fanoutUnicode.1024");
  }

  @Test
  void everyWorkloadHasEveryStringExecutionVariant() {
    CrossEngineBenchmarkPlan plan = CrossEngineBenchmarkPlan.load();
    List<CrossEngineBenchmarkPlan.Trial> allTrials =
        List.of(
                plan.trials(CrossEngineWorkload.TimingGroup.NANOSECONDS),
                plan.trials(CrossEngineWorkload.TimingGroup.MICROSECONDS))
            .stream()
            .flatMap(List::stream)
            .toList();

    for (CrossEngineWorkload workload : plan.workloads()) {
      assertThat(
              allTrials.stream()
                  .filter(trial -> trial.workload().id().equals(workload.id()))
                  .map(CrossEngineBenchmarkPlan.Trial::variant))
          .contains(
              RegexEngineVariant.SAFERE_STRING,
              RegexEngineVariant.JDK_STRING,
              RegexEngineVariant.RE2J_STRING,
              RegexEngineVariant.RE2_FFM_STRING_CONVERSION);
    }
  }

  @Test
  void utf8ParticipatesOnlyInNativeSupportedOperations() {
    CrossEngineBenchmarkPlan plan = CrossEngineBenchmarkPlan.load();
    List<CrossEngineBenchmarkPlan.Trial> utf8Trials =
        List.of(
                plan.trials(CrossEngineWorkload.TimingGroup.NANOSECONDS),
                plan.trials(CrossEngineWorkload.TimingGroup.MICROSECONDS))
            .stream()
            .flatMap(List::stream)
            .filter(trial -> trial.variant() == RegexEngineVariant.SAFERE_UTF8)
            .toList();

    assertThat(utf8Trials).isNotEmpty();
    assertThat(utf8Trials)
        .allMatch(
            trial ->
                trial.workload().operation() == BenchmarkOperation.FIND
                    || trial.workload().operation() == BenchmarkOperation.FIND_ALL_COUNT);
  }

  @Test
  void unsupportedCombinationIsDistinctFromMissingImplementation() {
    CrossEngineBenchmarkPlan plan = CrossEngineBenchmarkPlan.load();

    assertThatThrownBy(() -> plan.resolve("RegexBenchmark.literalMatch@safere-utf8"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported cross-engine combination")
        .hasMessageContaining("MATCHES");
    assertThatThrownBy(() -> plan.resolve("missing.workload@safere-string"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unknown cross-engine workload ID: missing.workload");
  }

  @Test
  void inputRepresentationsExposeTheirTimingBoundaries() {
    assertThat(RegexEngineVariant.SAFERE_STRING.inputRepresentation())
        .isEqualTo(RegexEngineVariant.InputRepresentation.JAVA_STRING);
    assertThat(RegexEngineVariant.SAFERE_UTF8.inputRepresentation())
        .isEqualTo(RegexEngineVariant.InputRepresentation.PREEXISTING_UTF8);
    assertThat(RegexEngineVariant.RE2_FFM_STRING_CONVERSION.inputRepresentation())
        .isEqualTo(RegexEngineVariant.InputRepresentation.JAVA_STRING_WITH_TIMED_UTF8_CONVERSION);
  }

  @Test
  void boundTaskReusesInputPreparedBeforeTimedInvocation() {
    RegexEngineVariant.RegexInput preparedInput =
        new RegexEngineVariant.StringRegexInput("materialized before binding");
    List<RegexEngineVariant.RegexInput> observedInputs = new ArrayList<>();
    RegexEngineVariant.CompiledRegex pattern =
        new RegexEngineVariant.CompiledRegex() {
          @Override
          public boolean find(RegexEngineVariant.RegexInput input) {
            observedInputs.add(input);
            return true;
          }
        };
    BenchmarkOperation.BenchmarkTask task =
        BenchmarkOperation.FIND.bind(pattern, List.of(preparedInput), new int[0], null);
    Blackhole blackhole =
        new Blackhole(
            "Today's password is swordfish. I understand instantiating Blackholes directly is"
                + " dangerous.");

    task.run(blackhole);
    task.run(blackhole);

    assertThat(observedInputs)
        .hasSize(2)
        .allSatisfy(input -> assertThat(input).isSameAs(preparedInput));
  }

  @Test
  void preparationValidatesDeclaredExpectedResult() {
    try (CrossEngineTrialRunner ignored =
        CrossEngineTrialRunner.prepare(
            "ApplicationBenchmark.uuidValidation@safere-string",
            CrossEngineWorkload.TimingGroup.NANOSECONDS)) {
      assertThat(ignored).isNotNull();
    }
  }
}
