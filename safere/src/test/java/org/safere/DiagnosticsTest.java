// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@DisabledForCrosscheck("match diagnostics are a SafeRE-specific public API")
class DiagnosticsTest {
  private final RecordingDiagnostics diagnostics = new RecordingDiagnostics();

  @AfterEach
  void disableDiagnostics() {
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
  }

  @Test
  void staticAnalysisReportsFeaturesCapabilitiesAndLimitations() {
    Pattern pattern = Pattern.compile("^(?:(a)?)*$");

    PatternAnalysis analysis = pattern.analysis();

    assertThat(analysis.features())
        .contains(
            PatternFeature.CAPTURES,
            PatternFeature.ANCHOR,
            PatternFeature.START_ANCHOR,
            PatternFeature.END_ANCHOR,
            PatternFeature.NULLABLE,
            PatternFeature.NESTED_NULLABLE_QUANTIFIER,
            PatternFeature.PROGRESS_CHECK,
            PatternFeature.CAPTURES_IN_QUANTIFIER);
    assertThat(analysis.capabilities())
        .contains(PatternCapability.DFA_REJECT_PREFILTER, PatternCapability.NFA);
    assertThat(analysis.limitations())
        .contains(
            PatternLimitation.NULLABLE_LOOP_REQUIRES_EXACT_ENGINE,
            PatternLimitation.CAPTURE_PRIORITY_REQUIRES_EXACT_ENGINE);
    assertThat(analysis.captureCount()).isEqualTo(1);
    assertThatThrownBy(() -> analysis.features().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void listenerInstalledAfterCompilationStillReceivesPatternAnalysis() {
    Pattern pattern = Pattern.compile("abc");
    Pattern.setDiagnostics(diagnostics);

    assertThat(pattern.matcher("abc").matches()).isTrue();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.pattern().analysis()).isSameAs(pattern.analysis());
              assertThat(event.pattern().patternId()).isPositive();
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.LITERAL);
            });
  }

  @Test
  void compilationAndOperationsShareDescriptor() {
    Pattern.setDiagnostics(diagnostics);

    Pattern pattern = Pattern.compile("^GET ([^ ]+)");
    assertThat(pattern.matcher("GET /index").lookingAt()).isTrue();

    PatternCompiledEvent compilation =
        diagnostics.compilations.stream()
            .filter(event -> event.pattern().patternId() == pattern.descriptor().patternId())
            .findFirst()
            .orElseThrow();
    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.pattern()).isSameAs(compilation.pattern());
              assertThat(event.operation()).isEqualTo(MatchOperation.LOOKING_AT);
              assertThat(event.outcome()).isEqualTo(MatchOutcome.MATCH);
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.ONE_PASS);
              assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.ONE_PASS);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.EAGER);
            });
  }

  @Test
  void forcedBitStateAndNfaReportTheExactEngine() {
    Pattern.setDiagnostics(diagnostics);
    EnginePathOptions bitStateOnly =
        EnginePathOptions.builder()
            .literalFastPaths(false)
            .charClassMatchFastPaths(false)
            .keywordAlternationFastPath(false)
            .onePass(false)
            .dfa(false)
            .build();
    Pattern bitState = Pattern.compile("a+b", 0, bitStateOnly);

    assertThat(bitState.matcher("aaab").matches()).isTrue();
    assertThat(operationsFor(bitState).getLast().boundaryStrategy())
        .isEqualTo(MatchStrategy.BIT_STATE);

    EnginePathOptions nfaOnly =
        EnginePathOptions.builder()
            .literalFastPaths(false)
            .charClassMatchFastPaths(false)
            .keywordAlternationFastPath(false)
            .onePass(false)
            .dfa(false)
            .bitState(false)
            .build();
    Pattern nfa = Pattern.compile("a+b", 0, nfaOnly);

    assertThat(nfa.matcher("aaab").matches()).isTrue();
    assertThat(operationsFor(nfa).getLast().boundaryStrategy()).isEqualTo(MatchStrategy.NFA);
  }

  @Test
  void replacementFastPathEmitsOneOperationWithTotalMatchCount() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("[a-z]+");

    assertThat(pattern.matcher("a 22 bb").replaceAll("x")).isEqualTo("x 22 x");

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.operation()).isEqualTo(MatchOperation.REPLACE_ALL);
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.CHARACTER_CLASS);
              assertThat(event.matchCount()).isEqualTo(2);
              assertThat(event.auxiliaryStrategies())
                  .containsExactly(
                      new StrategyParticipation(
                          MatchStrategy.CHARACTER_CLASS, StrategyRole.CANDIDATE_VERIFICATION));
            });
  }

  @Test
  void ordinaryReplacementLoopSuppressesNestedFindEvents() {
    Pattern.setDiagnostics(diagnostics);
    EnginePathOptions exactOnly =
        EnginePathOptions.builder()
            .literalFastPaths(false)
            .charClassMatchFastPaths(false)
            .charClassReplacementFastPath(false)
            .keywordAlternationFastPath(false)
            .onePass(false)
            .dfa(false)
            .build();
    Pattern pattern = Pattern.compile("(a)", 0, exactOnly);

    assertThat(pattern.matcher("aba").replaceAll("$1x")).isEqualTo("axbax");

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.operation()).isEqualTo(MatchOperation.REPLACE_ALL);
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.BIT_STATE);
              assertThat(event.matchCount()).isEqualTo(2);
            });
  }

  @Test
  void dfaReplacementReportsOneEventAndAllMatches() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("a+b");

    assertThat(pattern.matcher("aaab xx ab").replaceAll("z")).isEqualTo("z xx z");

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.operation()).isEqualTo(MatchOperation.REPLACE_ALL);
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
              assertThat(event.matchCount()).isEqualTo(2);
              assertThat(event.auxiliaryStrategies())
                  .contains(
                      new StrategyParticipation(
                          MatchStrategy.DFA, StrategyRole.CANDIDATE_VERIFICATION));
            });
  }

  @Test
  void replacementCaptureModeAgreesWithCaptureStrategyAcrossTerminalFindFailure() {
    Pattern.setDiagnostics(diagnostics);
    Pattern deferredPattern = Pattern.compile("(a+)b");

    assertThat(deferredPattern.matcher("aaab xx ab").replaceAll("x")).isEqualTo("x xx x");

    assertThat(operationsFor(deferredPattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.NONE);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.DEFERRED);
            });

    Pattern eagerPattern = Pattern.compile("(a+)b");
    assertThat(eagerPattern.matcher("aaab xx ab").replaceAll("$1")).isEqualTo("aaa xx a");
    assertThat(operationsFor(eagerPattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.captureStrategy()).isNotEqualTo(MatchStrategy.NONE);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.EAGER);
            });
  }

  @Test
  void functionalReplacementAlsoSuppressesNestedFindEvents() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("a");

    assertThat(pattern.matcher("aba").replaceAll(result -> result.group() + "x"))
        .isEqualTo("axbax");

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.operation()).isEqualTo(MatchOperation.REPLACE_ALL);
              assertThat(event.matchCount()).isEqualTo(2);
            });
  }

  @Test
  void nullableLoopDfaCanAuthoritativelyReject() {
    Pattern.setDiagnostics(diagnostics);
    EnginePathOptions dfaOnly =
        EnginePathOptions.builder()
            .literalFastPaths(false)
            .charClassMatchFastPaths(false)
            .keywordAlternationFastPath(false)
            .startAcceleration(false)
            .onePass(false)
            .bitState(false)
            .build();
    Pattern pattern = Pattern.compile("(?:(a)?)*X", 0, dfaOnly);

    assertThat(pattern.matcher("aaaa").find()).isFalse();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
              assertThat(event.auxiliaryStrategies())
                  .contains(
                      new StrategyParticipation(MatchStrategy.DFA, StrategyRole.REJECT_PREFILTER));
            });

    assertThat(pattern.matcher("aaaaX").find()).isTrue();
    assertThat(operationsFor(pattern).getLast())
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.NFA);
              assertThat(event.strategyDecisions())
                  .contains(
                      new StrategyDecision(
                          MatchStrategy.DFA,
                          StrategyDisposition.BYPASSED,
                          StrategyReason.EXACT_NULLABLE_LOOP_SEMANTICS_REQUIRED));
            });
  }

  @Test
  void prefixCandidateFailureContinuesThroughDfa() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern =
        Pattern.compile("(?s)<block>\\n(\\s)*# (category|group)_defined:.*?\\n</block>");
    String input =
        "<block>\n  # comment\n</block> " + "<block>\n  # category_defined: alpha\n</block>";

    assertThat(pattern.matcher(input).find()).isTrue();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
              assertThat(event.auxiliaryStrategies())
                  .startsWith(
                      new StrategyParticipation(
                          MatchStrategy.LITERAL, StrategyRole.START_ACCELERATION))
                  .contains(
                      new StrategyParticipation(
                          MatchStrategy.DFA, StrategyRole.CANDIDATE_VERIFICATION));
            });
  }

  @Test
  void dfaBoundsCanLeaveCapturesDeferred() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("(a+)b");
    Matcher matcher = pattern.matcher("xxaaab");

    assertThat(matcher.find()).isTrue();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
              assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.NONE);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.DEFERRED);
            });
    assertThat(matcher.group(1)).isEqualTo("aaa");
    assertThat(operationsFor(pattern)).hasSize(1);
  }

  @Test
  void failedRegexOperationDoesNotEmitAnEvent() {
    Pattern.setDiagnostics(diagnostics);
    Matcher matcher = Pattern.compile("a").matcher("a");

    assertThatThrownBy(() -> matcher.find(2)).isInstanceOf(IndexOutOfBoundsException.class);

    assertThat(operationsFor(matcher.pattern())).isEmpty();
  }

  @Test
  void listenerCanAggregateOperationsFromMultipleThreads() {
    Pattern pattern = Pattern.compile("abc");
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
        .forEach(ignored -> assertThat(pattern.matcher("abc").matches()).isTrue());

    assertThat(events)
        .filteredOn(event -> event.pattern().patternId() == pattern.descriptor().patternId())
        .hasSize(32);
  }

  @Test
  void listenerFailurePropagatesAfterMatcherStateIsFinalized() {
    Pattern pattern = Pattern.compile("abc");
    Matcher matcher = pattern.matcher("abc");
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
    assertThat(matcher.group()).isEqualTo("abc");
  }

  @Test
  void listenerReplacementResetAndOperationSnapshotAreStable() {
    Pattern pattern = Pattern.compile("abc");
    RecordingDiagnostics replacement = new RecordingDiagnostics();
    SafeReMatchDiagnostics replacingListener =
        new SafeReMatchDiagnostics() {
          @Override
          public void onOperationCompleted(OperationDiagnostics event) {
            diagnostics.operations.add(event);
            Pattern.setDiagnostics(replacement);
          }
        };
    Pattern.setDiagnostics(replacingListener);

    assertThat(pattern.matcher("abc").matches()).isTrue();
    assertThat(diagnostics.operations).hasSize(1);
    assertThat(replacement.operations).isEmpty();

    assertThat(pattern.matcher("abc").matches()).isTrue();
    assertThat(replacement.operations).hasSize(1);

    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
    assertThat(pattern.matcher("abc").matches()).isTrue();
    assertThat(replacement.operations).hasSize(1);
  }

  @Test
  void eventsContainOnlyOpaqueIdentityAnalysisAndAggregateInputFacts() {
    String regexSecret = "diagnosticPatternSecret";
    String inputSecret = "prefix-diagnosticInputSecret-suffix";
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile(regexSecret);

    assertThat(pattern.matcher(inputSecret).find()).isFalse();

    OperationDiagnostics event = operationsFor(pattern).getFirst();
    assertThat(event.toString()).doesNotContain(regexSecret, inputSecret);
    assertThat(OperationDiagnostics.class.getRecordComponents())
        .noneMatch(component -> component.getType() == String.class);
    assertThat(PatternDescriptor.class.getRecordComponents())
        .noneMatch(component -> component.getType() == String.class);
    assertThat(event.inputLength()).isEqualTo(inputSecret.length());
  }

  @Test
  void keywordAlternationReportsKeywordStrategy() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("(?i)\\b(error|warning|timeout|failed)\\b");

    assertThat(pattern.matcher("Info: Warning").find()).isTrue();

    assertThat(operationsFor(pattern).getFirst().boundaryStrategy())
        .isEqualTo(MatchStrategy.KEYWORD);
  }

  @Test
  void largeInputBypassesBitStateAndReportsNfaFallback() {
    Pattern.setDiagnostics(diagnostics);
    EnginePathOptions exactOnly =
        EnginePathOptions.builder()
            .literalFastPaths(false)
            .charClassMatchFastPaths(false)
            .keywordAlternationFastPath(false)
            .onePass(false)
            .dfa(false)
            .build();
    Pattern pattern = Pattern.compile("a+b", 0, exactOnly);

    assertThat(pattern.matcher("a".repeat(100_000) + "b").matches()).isTrue();

    assertThat(operationsFor(pattern).getFirst())
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
  void graphemeSemanticsReportUnsupportedOptimizedPathsAndExactFallback() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("\\X");

    assertThat(pattern.matcher("a\u0301").matches()).isTrue();

    assertThat(pattern.analysis().features()).contains(PatternFeature.GRAPHEME);
    assertThat(pattern.analysis().limitations())
        .contains(PatternLimitation.GRAPHEME_REQUIRES_EXACT_ENGINE);
    assertThat(operationsFor(pattern).getFirst().boundaryStrategy()).isEqualTo(MatchStrategy.NFA);
  }

  @Test
  void dfaBudgetExhaustionReportsFallbackToAnExactEngine() {
    String regex = "[ab]*a" + "[ab]".repeat(14) + "X";
    Pattern pattern = Pattern.compile(regex);
    java.util.regex.Pattern oracle = java.util.regex.Pattern.compile(regex);
    AtomicBoolean observedBudgetFallback = new AtomicBoolean();
    Pattern.setDiagnostics(
        new SafeReMatchDiagnostics() {
          @Override
          public void onOperationCompleted(OperationDiagnostics event) {
            if (event
                .strategyDecisions()
                .contains(
                    new StrategyDecision(
                        MatchStrategy.DFA,
                        StrategyDisposition.FALLBACK,
                        StrategyReason.DFA_BUDGET_EXCEEDED))) {
              observedBudgetFallback.set(true);
            }
          }
        });

    for (int bits = 0; bits < 1 << 15 && !observedBudgetFallback.get(); bits++) {
      StringBuilder input = new StringBuilder(16);
      for (int index = 0; index < 15; index++) {
        input.append((bits & (1 << index)) == 0 ? 'a' : 'b');
      }
      input.append('X');
      String text = input.toString();
      assertThat(pattern.matcher(text).matches()).isEqualTo(oracle.matcher(text).matches());
    }

    assertThat(observedBudgetFallback).isTrue();
  }

  @Test
  void exactEngineRespectsRegionAndAnchoringBoundsAndReportsItsStrategy() {
    Pattern.setDiagnostics(diagnostics);
    EnginePathOptions exactOnly =
        EnginePathOptions.builder()
            .literalFastPaths(false)
            .charClassMatchFastPaths(false)
            .keywordAlternationFastPath(false)
            .onePass(false)
            .dfa(false)
            .build();
    Pattern pattern = Pattern.compile("^a", 0, exactOnly);
    Matcher matcher = pattern.matcher("za").region(1, 2).useAnchoringBounds(true);

    assertThat(matcher.matches()).isTrue();

    assertThat(operationsFor(pattern).getFirst().boundaryStrategy())
        .isEqualTo(MatchStrategy.BIT_STATE);
  }

  @Test
  void listenerCanAggregateStrategiesWithLongAdders() {
    Pattern pattern = Pattern.compile("abc");
    Map<MatchStrategy, LongAdder> counts = new ConcurrentHashMap<>();
    Pattern.setDiagnostics(
        new SafeReMatchDiagnostics() {
          @Override
          public void onOperationCompleted(OperationDiagnostics event) {
            counts
                .computeIfAbsent(event.boundaryStrategy(), ignored -> new LongAdder())
                .increment();
          }
        });

    IntStream.range(0, 64)
        .parallel()
        .forEach(ignored -> assertThat(pattern.matcher("abc").matches()).isTrue());

    assertThat(counts.get(MatchStrategy.LITERAL).sum()).isEqualTo(64);
  }

  private List<OperationDiagnostics> operationsFor(Pattern pattern) {
    long patternId = pattern.descriptor().patternId();
    return diagnostics.operations.stream()
        .filter(event -> event.pattern().patternId() == patternId)
        .toList();
  }

  private static final class RecordingDiagnostics extends SafeReMatchDiagnostics {
    final List<PatternCompiledEvent> compilations = new ArrayList<>();
    final List<OperationDiagnostics> operations = new ArrayList<>();

    @Override
    public void onPatternCompiled(PatternCompiledEvent event) {
      compilations.add(event);
    }

    @Override
    public void onOperationCompleted(OperationDiagnostics event) {
      operations.add(event);
    }
  }
}
