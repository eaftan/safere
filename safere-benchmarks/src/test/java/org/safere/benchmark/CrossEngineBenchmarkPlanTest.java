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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
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
    assertThat(ids).hasSize(408);
  }

  @Test
  void everyWorkloadEnginePairIsScheduledOrExplicitlyExcluded() {
    CrossEngineBenchmarkPlan plan = CrossEngineBenchmarkPlan.load();
    List<CrossEngineBenchmarkPlan.Trial> allTrials =
        List.of(
                plan.trials(CrossEngineWorkload.TimingGroup.NANOSECONDS),
                plan.trials(CrossEngineWorkload.TimingGroup.MICROSECONDS),
                plan.trials(CrossEngineWorkload.TimingGroup.MILLISECONDS))
            .stream()
            .flatMap(List::stream)
            .toList();
    Set<String> accounted = new HashSet<>();
    allTrials.forEach(trial -> assertThat(accounted.add(trial.id())).isTrue());
    plan.exclusions()
        .forEach(
            exclusion ->
                assertThat(accounted.add(exclusion.workloadId() + "@" + exclusion.engineId()))
                    .isTrue());

    for (CrossEngineWorkload workload : plan.workloads()) {
      assertThat(
              allTrials.stream()
                  .filter(trial -> trial.workload().id().equals(workload.id()))
                  .map(CrossEngineBenchmarkPlan.Trial::variant))
          .containsAnyOf(RegexEngineVariant.SAFERE_STRING, RegexEngineVariant.SAFERE_UTF8);
    }
    assertThat(allTrials).hasSize(1406);
    assertThat(plan.exclusions()).hasSize(634);
    assertThat(accounted).hasSize(408 * RegexEngineVariant.values().length);
  }

  @Test
  void trialExpansionIsDeterministicAndPreservesTimingGroups() {
    CrossEngineBenchmarkPlan first = CrossEngineBenchmarkPlan.load();
    CrossEngineBenchmarkPlan second = CrossEngineBenchmarkPlan.load();

    assertThat(first.trials(CrossEngineWorkload.TimingGroup.NANOSECONDS))
        .extracting(CrossEngineBenchmarkPlan.Trial::id)
        .containsExactlyElementsOf(
            second.trials(CrossEngineWorkload.TimingGroup.NANOSECONDS).stream()
                .map(CrossEngineBenchmarkPlan.Trial::id)
                .toList())
        .hasSize(809);
    assertThat(first.trials(CrossEngineWorkload.TimingGroup.MICROSECONDS))
        .extracting(CrossEngineBenchmarkPlan.Trial::id)
        .containsExactlyElementsOf(
            second.trials(CrossEngineWorkload.TimingGroup.MICROSECONDS).stream()
                .map(CrossEngineBenchmarkPlan.Trial::id)
                .toList())
        .hasSize(525);
    assertThat(first.trials(CrossEngineWorkload.TimingGroup.MILLISECONDS))
        .extracting(CrossEngineBenchmarkPlan.Trial::id)
        .containsExactlyElementsOf(
            second.trials(CrossEngineWorkload.TimingGroup.MILLISECONDS).stream()
                .map(CrossEngineBenchmarkPlan.Trial::id)
                .toList())
        .hasSize(72);
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
  void re2BasedAdaptersShareARegexSyntaxProfile() {
    assertThat(RegexEngineVariant.RE2J_STRING.patternProfile()).isEqualTo("re2");
    assertThat(RegexEngineVariant.RE2_FFM_STRING_CONVERSION.patternProfile()).isEqualTo("re2");
  }

  @Test
  void javaOnlyUnicodePropertyCompileWorkloadsRemainExcludedFromRe2Adapters() {
    CrossEngineBenchmarkPlan plan = CrossEngineBenchmarkPlan.load();

    assertThatThrownBy(
            () -> plan.resolve("UnicodeCompileBenchmark.compile.alphabetic.0@re2j-string"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("engine lacks [flaggedCompile]");
    assertThatThrownBy(
            () -> plan.resolve("UnicodeCompileBenchmark.compile.ideographic.0@re2j-string"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("engine lacks [flaggedCompile]");
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
        BenchmarkOperation.FIND.bind(
            RegexEngineVariant.SAFERE_STRING,
            List.of("x"),
            List.of(pattern),
            List.of(preparedInput),
            new int[0],
            null,
            0,
            DeclarativeBenchmarkPlan.MatcherLifecycle.NONE,
            null,
            0,
            0);
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

  @Test
  void coldStartPreparationDefersCompilationUntilTheMeasuredTask() {
    CrossEngineWorkload workload =
        new CrossEngineWorkload(
            "Synthetic.coldCompile",
            BenchmarkOperation.COMPILE,
            List.of("("),
            List.of(),
            new int[0],
            null,
            null,
            0,
            DeclarativeBenchmarkPlan.MatcherLifecycle.NONE,
            "",
            0,
            1,
            new DeclarativeBenchmarkPlan.Measurement(
                DeclarativeBenchmarkPlan.MeasurementMode.SINGLE_SHOT_COLD_START,
                DeclarativeBenchmarkPlan.TimingUnit.MILLISECONDS,
                EnumSet.of(
                    DeclarativeBenchmarkPlan.ExecutionConstraint.FRESH_PROCESS_PER_INVOCATION)),
            CrossEngineWorkload.TimingGroup.MILLISECONDS);
    CrossEngineBenchmarkPlan.Trial trial =
        new CrossEngineBenchmarkPlan.Trial(workload, RegexEngineVariant.SAFERE_STRING);
    Blackhole blackhole =
        new Blackhole(
            "Today's password is swordfish. I understand instantiating Blackholes directly is"
                + " dangerous.");

    try (CrossEngineTrialRunner runner =
        CrossEngineTrialRunner.prepareColdStart(
            BenchmarkData.get(), trial, CrossEngineWorkload.TimingGroup.MILLISECONDS)) {
      assertThatThrownBy(() -> runner.run(blackhole)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void migratedOperationsReplayCorrectnessForEveryCompatibleEngine() {
    List<String> trials =
        List.of(
            "CompileBenchmark.compileSimple@safere-string",
            "CompileBenchmark.compileSimple@jdk-string",
            "CompileBenchmark.compileSimple@re2j-string",
            "CaptureScalingBenchmark.capture3@safere-string",
            "CaptureScalingBenchmark.capture3@jdk-string",
            "CaptureScalingBenchmark.capture3@re2j-string",
            "ReplaceBenchmark.literalReplaceFirst@safere-string",
            "ReplaceBenchmark.literalReplaceFirst@jdk-string",
            "ReplaceBenchmark.literalReplaceFirst@re2j-string",
            "ReplaceBenchmark.manualReplaceAll@safere-string",
            "ReplaceBenchmark.manualReplaceAll@jdk-string",
            "ReplaceBenchmark.manualReplaceAll@re2j-string",
            "Issue481ScalingBenchmark.splitWords.128@safere-string",
            "Issue481ScalingBenchmark.splitWords.128@jdk-string",
            "Issue481ScalingBenchmark.splitWords.128@re2j-string",
            "Issue481ScalingBenchmark.schemeExtract.128@safere-string",
            "Issue481ScalingBenchmark.schemeExtract.128@jdk-string",
            "Issue481ScalingBenchmark.schemeExtract.128@re2j-string",
            "ScrubberBenchmark.scrubWithDirectives@safere-string",
            "ScrubberBenchmark.scrubWithDirectives@jdk-string",
            "ScrubberBenchmark.scrubWithDirectives@re2j-string",
            "MatcherApiBenchmark.lookingAt@safere-string",
            "MatcherApiBenchmark.lookingAt@jdk-string",
            "MatcherApiBenchmark.lookingAt@re2j-string",
            "MatcherApiBenchmark.regionFind@safere-string",
            "MatcherApiBenchmark.regionFind@jdk-string",
            "MatcherApiBenchmark.resetAndFind@safere-string",
            "MatcherApiBenchmark.resetAndFind@jdk-string",
            "MatcherApiBenchmark.resetAndFind@re2j-string",
            "JavaCharacterClassBenchmark.compileAndFindJavaLetter@safere-string",
            "JavaCharacterClassBenchmark.compileAndFindJavaLetter@jdk-string",
            "JavaCharacterClassBenchmark.compileAndFindJavaLetter@re2j-string",
            "JavaCharacterClassBenchmark.findJavaLetter@safere-string",
            "JavaCharacterClassBenchmark.findJavaLetter@jdk-string",
            "JavaCharacterClassBenchmark.findJavaLetter@re2j-string");
    CrossEngineBenchmarkPlan plan = CrossEngineBenchmarkPlan.load();

    for (String trialId : trials) {
      CrossEngineWorkload.TimingGroup timingGroup = plan.resolve(trialId).workload().timingGroup();
      try (CrossEngineTrialRunner ignored = CrossEngineTrialRunner.prepare(trialId, timingGroup)) {
        assertThat(ignored).as(trialId).isNotNull();
      }
    }
  }

  @Test
  void specializedMeasurementModesSelectStableGenericTrials() {
    SpecializedBenchmarkPlan first = SpecializedBenchmarkPlan.load();
    SpecializedBenchmarkPlan second = SpecializedBenchmarkPlan.load();

    assertThat(first.averageTimeTrials())
        .extracting(SpecializedBenchmarkPlan.Trial::id)
        .containsExactlyElementsOf(
            second.averageTimeTrials().stream().map(SpecializedBenchmarkPlan.Trial::id).toList())
        .hasSize(63);
    assertThat(first.retainedMemoryTrials())
        .extracting(SpecializedBenchmarkPlan.Trial::id)
        .containsExactlyElementsOf(
            second.retainedMemoryTrials().stream().map(SpecializedBenchmarkPlan.Trial::id).toList())
        .hasSize(34);
  }

  @Test
  void collectionPlanDiscoversGenericRunnersAndStableReportRows() {
    BenchmarkCollectionPlan plan = BenchmarkCollectionPlan.load();

    assertThat(plan.runners())
        .extracting(BenchmarkCollectionPlan.Runner::benchmark)
        .containsExactly(
            "org.safere.benchmark.CrossEngineBenchmark.run",
            "org.safere.benchmark.CrossEngineScalingBenchmark.run",
            "org.safere.benchmark.CrossEngineNoForkBenchmark.run",
            "org.safere.benchmark.CrossEngineColdStartBenchmark.run",
            "org.safere.benchmark.SpecializedBenchmark.run");
    assertThat(plan.runners())
        .allMatch(runner -> !runner.trialIds().isEmpty())
        .flatExtracting(BenchmarkCollectionPlan.Runner::trialIds)
        .doesNotHaveDuplicates()
        .hasSize(1445);
    assertThat(plan.reportPlan().trials()).hasSize(1445);
    assertThat(plan.reportPlan().exclusions()).isNotEmpty().doesNotHaveDuplicates();
    assertThat(
            plan.reportPlan(true).trials().stream()
                .map(BenchmarkCollectionPlan.CollectionTrial::workloadId)
                .distinct())
        .hasSize(5);
  }

  @Test
  void allocationCollectionPreservesMemoryScalingWithoutAddingTimingTrials() {
    BenchmarkCollectionPlan plan = BenchmarkCollectionPlan.load();

    assertThat(plan.allocationRunners())
        .flatExtracting(BenchmarkCollectionPlan.Runner::trialIds)
        .contains(
            "MemoryScalingBenchmark.searchEasy.1024@safere-string",
            "MemoryScalingBenchmark.searchEasy.1024@jdk-string",
            "MemoryScalingBenchmark.searchEasy.1024@re2j-string",
            "MemoryScalingBenchmark.searchMedium.1048576@safere-string",
            "MemoryScalingBenchmark.searchMedium.1048576@jdk-string",
            "MemoryScalingBenchmark.searchMedium.1048576@re2j-string")
        .noneMatch(trial -> trial.startsWith("MemoryScalingBenchmark.") && trial.contains("utf8"));
    assertThat(plan.runners())
        .flatExtracting(BenchmarkCollectionPlan.Runner::trialIds)
        .noneMatch(trial -> trial.startsWith("MemoryScalingBenchmark."));
  }

  @Test
  void collectionPlanQueriesModeTimingPrefixAndExecutionVariant() {
    BenchmarkCollectionPlan plan = BenchmarkCollectionPlan.load();
    BenchmarkCollectionPlan.Query query =
        new BenchmarkCollectionPlan.Query(
            "averageTime", "nanoseconds", "RegexBenchmark.", "safere-utf8");

    assertThat(plan.trials(query))
        .isNotEmpty()
        .allMatch(
            trial ->
                trial.workloadId().startsWith("RegexBenchmark.")
                    && trial.executionVariant().equals("safere-utf8")
                    && trial.measurement().timingUnit()
                        == DeclarativeBenchmarkPlan.TimingUnit.NANOSECONDS);
  }

  @Test
  void everyJmhMethodIsGenericOrExplicitMeasurementInfrastructure() throws Exception {
    Path sourceDirectory =
        Files.exists(Path.of("src/main/java/org/safere/benchmark"))
            ? Path.of("src/main/java/org/safere/benchmark")
            : Path.of("safere-benchmarks/src/main/java/org/safere/benchmark");

    try (Stream<Path> files = Files.list(sourceDirectory)) {
      assertThat(
              files
                  .filter(path -> path.toString().endsWith(".java"))
                  .filter(
                      path -> {
                        try {
                          return Files.readString(path).contains("@Benchmark");
                        } catch (Exception exception) {
                          throw new IllegalStateException(exception);
                        }
                      })
                  .map(path -> path.getFileName().toString())
                  .sorted())
          .containsExactly(
              "CrossEngineBenchmark.java",
              "CrossEngineColdStartBenchmark.java",
              "CrossEngineNoForkBenchmark.java",
              "CrossEngineScalingBenchmark.java",
              "CrosscheckOverheadBenchmark.java",
              "SpecializedBenchmark.java");
    }
  }

  @Test
  void everySpecializedOperationPreparesAndRunsThroughOneEntryPoint() {
    List<String> trials =
        List.of(
            "PatternSetBenchmark.unanchoredMatch.4@safere-string",
            "Utf8MatchingBenchmark.captureBounds.numbered@safere-utf8",
            "Utf8MatchingBenchmark.captureFreeDecode.asciiEarly@safere-utf8",
            "ByteReplacementBenchmark.numbered@safere-utf8",
            "Utf8MatchingBenchmark.window@safere-utf8",
            "Utf8MatchingBenchmark.construction.validated@safere-utf8",
            "PatternAnalysisBenchmark.cachedAnalysis@safere-string",
            "PatternAnalysisBenchmark.compileOnly@safere-string",
            "PatternAnalysisBenchmark.compileAndAnalyze@safere-string",
            "DiagnosticsDisabledBenchmark.tinyLiteralFindHit@safere-string",
            "DiagnosticsEnabledBenchmark.charClassDenseReplaceAll.longAdder@safere-string");
    Blackhole blackhole =
        new Blackhole(
            "Today's password is swordfish. I understand instantiating Blackholes directly is"
                + " dangerous.");

    for (String trial : trials) {
      try (SpecializedTrialRunner runner = SpecializedTrialRunner.prepare(trial)) {
        runner.run(blackhole);
      }
    }
  }
}
