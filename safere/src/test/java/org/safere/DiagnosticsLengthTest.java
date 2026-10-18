// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Isolated
@DisabledForCrosscheck("SafeRE-specific diagnostics and deterministic work counters")
class DiagnosticsLengthTest {
  private final List<OperationDiagnostics> events = new ArrayList<>();
  private final SafeReMatchDiagnostics listener =
      new SafeReMatchDiagnostics() {
        @Override
        public void onOperationCompleted(OperationDiagnostics event) {
          events.add(event);
        }
      };

  @AfterEach
  void disableDiagnostics() {
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "a{1000}",
        "[ab]{1000}",
        "[ab]{1000}\\z",
        "^[ab]{1000}",
        "([ab]){1000}",
        "(?i)a{1000}",
        "\\X[ab]{1000}"
      })
  void shortInputsReportLengthRejectionWithoutEngineWork(String regex) {
    Pattern pattern = Pattern.compile(regex);
    String text = "a".repeat(200);
    Utf8Input input = Utf8Input.trusted(text.getBytes(UTF_8));
    Pattern.setDiagnostics(listener);

    assertThat(pattern.find(input)).isFalse();
    assertThat(pattern.matcher(input).find()).isFalse();
    assertThat(pattern.matcher(input).matches()).isFalse();
    assertThat(pattern.matcher(input).lookingAt()).isFalse();
    assertThat(pattern.matcher(text).find()).isFalse();
    assertThat(pattern.matcher(text).matches()).isFalse();
    assertThat(pattern.matcher(text).lookingAt()).isFalse();

    assertThat(events).hasSize(7).allSatisfy(DiagnosticsLengthTest::assertLengthRejection);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"a{3}", "[ab]{3}", "[ab]{3}\\z", "^[ab]{3}", "([ab]){3}", "é{3}", "😀{3}"})
  void minimumLengthAndMultibyteMatchesArePreserved(String regex) {
    Pattern pattern = Pattern.compile(regex);
    String unit = regex.startsWith("é") ? "é" : regex.startsWith("😀") ? "😀" : "a";
    for (boolean enabled : List.of(false, true)) {
      Pattern.setDiagnostics(enabled ? listener : SafeReMatchDiagnostics.NONE);
      for (int size : List.of(2, 3, 4)) {
        String text = unit.repeat(size);
        byte[] storage = ("prefix" + text + "suffix").getBytes(UTF_8);
        Utf8Input input = Utf8Input.trusted(storage, 6, text.getBytes(UTF_8).length);
        boolean expected = size >= 3;
        assertThat(pattern.find(input)).as(regex).isEqualTo(expected);
        assertThat(pattern.matcher(input).find()).as(regex).isEqualTo(expected);
        assertThat(pattern.matcher(text).find()).as(regex).isEqualTo(expected);
      }
    }
  }

  @Test
  void lengthRejectionUsesRegionAndRemainingSearchLengthAndFinalizesState() {
    Pattern.setDiagnostics(listener);
    Pattern pattern = Pattern.compile("([ab]{3})");
    Matcher matcher = pattern.matcher("aaabbb");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.find()).isTrue();
    events.clear();

    assertThat(matcher.find()).isFalse();
    assertThatIllegalStateException().isThrownBy(matcher::start);
    matcher.region(1, 3);
    assertThat(matcher.find()).isFalse();
    assertThat(matcher.matches()).isFalse();
    assertThat(matcher.lookingAt()).isFalse();
    assertThat(events).hasSize(4).allSatisfy(DiagnosticsLengthTest::assertLengthRejection);

    matcher.reset();
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(1)).isEqualTo("aaa");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "a?", "(?:[ab]{3})?", "\\z"})
  void nullablePatternsStillMatchEmptyInput(String regex) {
    Pattern.setDiagnostics(listener);
    Pattern pattern = Pattern.compile(regex);
    Utf8Input input = Utf8Input.trusted(new byte[0]);

    assertThat(pattern.find(input)).isTrue();
    assertThat(pattern.matcher(input).find()).isTrue();
    assertThat(pattern.matcher("").find()).isTrue();
    assertThat(events)
        .hasSize(3)
        .allSatisfy(event -> assertThat(event.outcome()).isEqualTo(MatchOutcome.MATCH));
  }

  @Test
  void lengthRemainsBoundaryOnlyAlongsideEngineParticipationAndDecisions() {
    Pattern.setDiagnostics(listener);
    EnginePathOptions options =
        EnginePathOptions.builder()
            .literalFastPaths(false)
            .charClassMatchFastPaths(false)
            .keywordAlternationFastPath(false)
            .startAcceleration(false)
            .onePass(false)
            .bitState(false)
            .build();
    Pattern pattern = Pattern.compile("(?:(a)?)*X", 0, options);

    assertThat(pattern.find(Utf8Input.trusted(new byte[0]))).isFalse();
    assertThat(pattern.matcher("").find()).isFalse();
    assertThat(pattern.matcher("aaaaX").find()).isTrue();
    // Replacement aggregates an engine match and a terminal minimum-length rejection.
    assertThat(pattern.matcher("aaaaX").replaceAll("done")).isEqualTo("done");

    assertLengthRejection(events.get(0));
    assertLengthRejection(events.get(1));
    assertThat(events)
        .hasSize(4)
        .anySatisfy(event -> assertThat(event.auxiliaryStrategies()).isNotEmpty())
        .anySatisfy(event -> assertThat(event.strategyDecisions()).isNotEmpty())
        .allSatisfy(
            event -> {
              assertThat(event.captureStrategy()).isNotEqualTo(MatchStrategy.LENGTH);
              assertThat(event.auxiliaryStrategies())
                  .extracting(StrategyParticipation::strategy)
                  .doesNotContain(MatchStrategy.LENGTH);
              assertThat(event.strategyDecisions())
                  .extracting(StrategyDecision::strategy)
                  .doesNotContain(MatchStrategy.LENGTH);
            });
  }

  @Tag("work-counter")
  @ParameterizedTest
  @ValueSource(strings = {"a{1000}", "[ab]{1000}", "[ab]{1000}\\z", "^[ab]{1000}"})
  void diagnosticsPreserveConstantWorkForShortInputs(String regex) {
    Pattern pattern = Pattern.compile(regex);
    for (int size : List.of(200, 800)) {
      String text = "a".repeat(size);
      Utf8Input input = Utf8Input.trusted(text.getBytes(UTF_8));
      for (Runnable search :
          List.<Runnable>of(
              () -> assertThat(pattern.find(input)).isFalse(),
              () -> assertThat(pattern.matcher(input).find()).isFalse(),
              () -> assertThat(pattern.matcher(text).find()).isFalse())) {
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
        search.run();
        long withoutDiagnostics = WorkCounter.countForTesting(search);
        Pattern.setDiagnostics(listener);
        search.run();
        long withDiagnostics = WorkCounter.countForTesting(search);
        assertThat(withDiagnostics).as("%s, length %s", regex, size).isEqualTo(withoutDiagnostics);
        assertThat(withDiagnostics).isZero();
        events.clear();
      }
    }
  }

  private static void assertLengthRejection(OperationDiagnostics event) {
    assertThat(event.outcome()).isEqualTo(MatchOutcome.NO_MATCH);
    assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.LENGTH);
    assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.NONE);
    assertThat(event.captureMode()).isEqualTo(CaptureMode.NONE);
    assertThat(event.matchCount()).isZero();
    assertThat(event.forwardDfaSearchCount()).isZero();
    assertThat(event.reverseDfaSearchCount()).isZero();
    assertThat(event.auxiliaryStrategies()).isEmpty();
    assertThat(event.strategyDecisions()).isEmpty();
  }
}
