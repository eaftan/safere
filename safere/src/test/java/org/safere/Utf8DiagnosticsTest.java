// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@DisabledForCrosscheck("UTF-8 match diagnostics are a SafeRE-specific public API")
class Utf8DiagnosticsTest {
  private final RecordingDiagnostics diagnostics = new RecordingDiagnostics();

  @AfterEach
  void disableDiagnostics() {
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
  }

  @ParameterizedTest
  @EnumSource(
      value = MatchOperation.class,
      names = {"MATCHES", "LOOKING_AT", "FIND"})
  void operationsReportDiagnosticsWithSlicedByteInputLength(MatchOperation operation) {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile(".*é.*");
    byte[] logicalInput = "xéy".getBytes(UTF_8);
    byte[] storage = new byte[logicalInput.length + 5];
    System.arraycopy(logicalInput, 0, storage, 2, logicalInput.length);
    Utf8Matcher matcher = pattern.matcher(Utf8Input.trusted(storage, 2, logicalInput.length));

    boolean matched =
        switch (operation) {
          case MATCHES -> matcher.matches();
          case LOOKING_AT -> matcher.lookingAt();
          case FIND -> matcher.find();
          default -> throw new AssertionError("Unexpected operation: " + operation);
        };

    assertThat(matched).isTrue();
    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.operation()).isEqualTo(operation);
              assertThat(event.outcome()).isEqualTo(MatchOutcome.MATCH);
              assertThat(event.inputLength()).isEqualTo(logicalInput.length);
              assertThat(event.matchCount()).isEqualTo(1);
            });
  }

  @Test
  void onePassReportsDescriptorAndEagerCaptures() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("^GET ([^ ]+)");

    assertThat(matcher(pattern, "GET /café").lookingAt()).isTrue();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.pattern()).isSameAs(pattern.descriptor());
              assertThat(event.operation()).isEqualTo(MatchOperation.LOOKING_AT);
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.ONE_PASS);
              assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.ONE_PASS);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.EAGER);
            });
  }

  @Test
  void literalFindReportsUtf8FastPath() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("é");

    assertThat(matcher(pattern, "xéy").find()).isTrue();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.LITERAL);
              assertThat(event.forwardDfaSearchCount()).isZero();
              assertThat(event.reverseDfaSearchCount()).isZero();
            });
  }

  @Test
  void patternBooleanFindReportsUtf8FastPath() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("é");
    Utf8Input input = Utf8Input.validated("xéy".getBytes(UTF_8));

    assertThat(pattern.find(input)).isTrue();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.operation()).isEqualTo(MatchOperation.FIND);
              assertThat(event.outcome()).isEqualTo(MatchOutcome.MATCH);
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.LITERAL);
              assertThat(event.inputLength()).isEqualTo(4);
              assertThat(event.matchCount()).isEqualTo(1);
            });
  }

  @Test
  void patternBooleanFindReportsAccelerationAndDfaSearch() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("é[ab]+c");

    assertThat(pattern.find(input("xxéabc"))).isTrue();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
              assertThat(event.forwardDfaSearchCount()).isEqualTo(1);
              assertThat(event.reverseDfaSearchCount()).isZero();
              assertThat(event.auxiliaryStrategies())
                  .containsExactly(
                      new StrategyParticipation(
                          MatchStrategy.LITERAL, StrategyRole.START_ACCELERATION),
                      new StrategyParticipation(MatchStrategy.DFA, StrategyRole.REJECT_PREFILTER));
            });
  }

  @Test
  void patternBooleanFindReportsAsciiPrefixClassRejection() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("[xy]q?");

    assertThat(pattern.find(input("bbbb"))).isFalse();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.CHARACTER_CLASS);
              assertThat(event.auxiliaryStrategies())
                  .containsExactly(
                      new StrategyParticipation(
                          MatchStrategy.CHARACTER_CLASS, StrategyRole.START_ACCELERATION));
            });
  }

  @Test
  void patternBooleanFindReportsNfaForGraphemeSemantics() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("\\Xz");

    assertThat(pattern.find(input("a\u0301z"))).isTrue();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.NFA);
              assertThat(event.forwardDfaSearchCount()).isZero();
              assertThat(event.captureMode()).isEqualTo(CaptureMode.NONE);
            });
  }

  @Test
  void patternBooleanFindReportsAcceleratedRejection() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("é[ab]+c");

    assertThat(pattern.find(input("ordinary ASCII text"))).isFalse();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.outcome()).isEqualTo(MatchOutcome.NO_MATCH);
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.LITERAL);
              assertThat(event.auxiliaryStrategies())
                  .containsExactly(
                      new StrategyParticipation(
                          MatchStrategy.LITERAL, StrategyRole.START_ACCELERATION));
              assertThat(event.matchCount()).isZero();
            });
  }

  @ParameterizedTest
  @MethodSource("patternBooleanFindScenarios")
  void patternBooleanFindDiagnosticsPreserveResults(
      String regex, String text, boolean expectedResult) {
    Pattern pattern = Pattern.compile(regex);
    Utf8Input input = input(text);

    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
    assertThat(pattern.find(input)).isEqualTo(expectedResult);

    Pattern.setDiagnostics(diagnostics);
    assertThat(pattern.find(input)).isEqualTo(expectedResult);
    assertThat(operationsFor(pattern)).hasSize(1);
  }

  private static Stream<Arguments> patternBooleanFindScenarios() {
    return Stream.of(
        Arguments.of("é", "xéy", true),
        Arguments.of("é", "xyz", false),
        Arguments.of("é[ab]+c", "xxéabc", true),
        Arguments.of("é[ab]+c", "xxézz", false),
        Arguments.of("[ab]+é", "xxabé", true),
        Arguments.of("[一-龥]{3,}", "ordinary ASCII text", false),
        Arguments.of("\\Xz", "a\u0301z", true),
        Arguments.of(".*z$", "abc", false),
        Arguments.of("(a?)*X", "aaaaX", true));
  }

  @Test
  void literalPrefixCandidateReportsAccelerationAndVerification() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("é[ab]+c");

    assertThat(matcher(pattern, "xxéabc").find()).isTrue();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
              assertThat(event.forwardDfaSearchCount()).isEqualTo(2);
              assertThat(event.reverseDfaSearchCount()).isZero();
              assertThat(event.auxiliaryStrategies())
                  .containsExactly(
                      new StrategyParticipation(
                          MatchStrategy.LITERAL, StrategyRole.START_ACCELERATION),
                      new StrategyParticipation(MatchStrategy.DFA, StrategyRole.REJECT_PREFILTER),
                      new StrategyParticipation(
                          MatchStrategy.DFA, StrategyRole.CANDIDATE_VERIFICATION));
            });
  }

  @Test
  void dfaSandwichReportsForwardAndReverseSearchCounts() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("[ab]+é");

    assertThat(matcher(pattern, "xxabé").find()).isTrue();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
              assertThat(event.forwardDfaSearchCount()).isEqualTo(1);
              assertThat(event.reverseDfaSearchCount()).isEqualTo(1);
            });
  }

  @Test
  void forcedBitStateAndNfaReportTheExactEngine() {
    Pattern.setDiagnostics(diagnostics);
    EnginePathOptions bitStateOnly = exactEngineOptions(true);
    Pattern bitState = Pattern.compile("(?:é)+", 0, bitStateOnly);

    assertThat(matcher(bitState, "xéé").find()).isTrue();
    assertThat(operationsFor(bitState).getLast().boundaryStrategy())
        .isEqualTo(MatchStrategy.BIT_STATE);

    EnginePathOptions nfaOnly = exactEngineOptions(false);
    Pattern nfa = Pattern.compile("(?:é)+", 0, nfaOnly);

    assertThat(matcher(nfa, "xéé").find()).isTrue();
    assertThat(operationsFor(nfa).getLast().boundaryStrategy()).isEqualTo(MatchStrategy.NFA);
  }

  @Test
  void dfaBoundsCanLeaveCapturesDeferred() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("(é+)x");
    Utf8Matcher matcher = matcher(pattern, "zzééx");

    assertThat(matcher.find()).isTrue();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
              assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.NONE);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.DEFERRED);
            });
    assertThat(matcher.start(1)).isEqualTo(2);
    assertThat(matcher.end(1)).isEqualTo(6);
    assertThat(operationsFor(pattern)).hasSize(1);
  }

  @Test
  void failedMatchReportsNoMatchWithoutCaptures() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("(é)+");

    assertThat(matcher(pattern, "abc").find()).isFalse();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.outcome()).isEqualTo(MatchOutcome.NO_MATCH);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.NONE);
              assertThat(event.matchCount()).isZero();
            });
  }

  @Test
  void largeInputReportsBitStateBypassAndNfaFallback() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("a+b", 0, exactEngineOptions(true));

    assertThat(matcher(pattern, "a".repeat(100_000) + "b").matches()).isTrue();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.NFA);
              assertThat(event.strategyDecisions())
                  .contains(
                      new StrategyDecision(
                          MatchStrategy.BIT_STATE,
                          StrategyDisposition.BYPASSED,
                          StrategyReason.INPUT_TOO_LARGE));
            });
  }

  @Test
  void graphemeSemanticsReportNfaFallback() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("\\X");

    assertThat(matcher(pattern, "a\u0301").matches()).isTrue();

    assertThat(operationsFor(pattern).getFirst().boundaryStrategy()).isEqualTo(MatchStrategy.NFA);
  }

  @Test
  void listenerFailurePropagatesAfterMatcherStateIsFinalized() {
    Pattern pattern = Pattern.compile("é");
    Utf8Matcher matcher = matcher(pattern, "é");
    Pattern.setDiagnostics(
        new SafeReMatchDiagnostics() {
          @Override
          public void onOperationCompleted(OperationDiagnostics event) {
            throw new IllegalStateException("listener failed");
          }
        });

    assertThatThrownBy(matcher::matches)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("listener failed");
    assertThat(matcher.start()).isZero();
    assertThat(matcher.end()).isEqualTo(2);
  }

  @Test
  void listenerCanAggregateOperationsFromMultipleThreads() {
    Pattern pattern = Pattern.compile("é");
    ConcurrentLinkedQueue<OperationDiagnostics> events = new ConcurrentLinkedQueue<>();
    Pattern.setDiagnostics(
        new SafeReMatchDiagnostics() {
          @Override
          public void onOperationCompleted(OperationDiagnostics event) {
            events.add(event);
          }
        });

    IntStream.range(0, 32)
        .parallel()
        .forEach(ignored -> assertThat(matcher(pattern, "é").matches()).isTrue());

    assertThat(events)
        .filteredOn(event -> event.pattern().patternId() == pattern.descriptor().patternId())
        .hasSize(32);
  }

  private static EnginePathOptions exactEngineOptions(boolean bitState) {
    return EnginePathOptions.builder()
        .literalFastPaths(false)
        .charClassMatchFastPaths(false)
        .keywordAlternationFastPath(false)
        .startAcceleration(false)
        .onePass(false)
        .dfa(false)
        .bitState(bitState)
        .build();
  }

  private static Utf8Matcher matcher(Pattern pattern, String input) {
    return pattern.matcher(input(input));
  }

  private static Utf8Input input(String input) {
    return Utf8Input.validated(input.getBytes(UTF_8));
  }

  private List<OperationDiagnostics> operationsFor(Pattern pattern) {
    long patternId = pattern.descriptor().patternId();
    return diagnostics.operations.stream()
        .filter(event -> event.pattern().patternId() == patternId)
        .toList();
  }

  private static final class RecordingDiagnostics extends SafeReMatchDiagnostics {
    final List<OperationDiagnostics> operations = new ArrayList<>();

    @Override
    public void onOperationCompleted(OperationDiagnostics event) {
      operations.add(event);
    }
  }
}
