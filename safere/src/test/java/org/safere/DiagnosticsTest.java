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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
              assertThat(event.forwardDfaSearchCount()).isZero();
              assertThat(event.reverseDfaSearchCount()).isZero();
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
  void capturedLiteralRetainsEnginesNeededWhenLiteralRunnerIsIneligible() {
    Pattern.setDiagnostics(diagnostics);

    Pattern lookingAtPattern = Pattern.compile("(foo)");
    assertThat(lookingAtPattern.matcher("foobar").lookingAt()).isTrue();
    assertThat(operationsFor(lookingAtPattern).getLast().boundaryStrategy())
        .isEqualTo(MatchStrategy.ONE_PASS);

    Pattern foldedUtf8LookingAt = Pattern.compile("(?i)foo");
    assertThat(
            foldedUtf8LookingAt.matcher(Utf8Input.validated("FOObar".getBytes(UTF_8))).lookingAt())
        .isTrue();
    assertThat(operationsFor(foldedUtf8LookingAt).getLast().boundaryStrategy())
        .isEqualTo(MatchStrategy.DFA);

    Pattern foldedUtf8Matches = Pattern.compile("(?i)foo");
    assertThat(foldedUtf8Matches.matcher(Utf8Input.validated("FOO".getBytes(UTF_8))).matches())
        .isTrue();
    assertThat(operationsFor(foldedUtf8Matches).getLast().boundaryStrategy())
        .isEqualTo(MatchStrategy.SHIFT_DFA);

    Pattern foldedUtf8Find = Pattern.compile("(?i)foo");
    assertThat(foldedUtf8Find.matcher(Utf8Input.validated("xxFOO".getBytes(UTF_8))).find())
        .isTrue();
    assertThat(operationsFor(foldedUtf8Find).getLast().boundaryStrategy())
        .isEqualTo(MatchStrategy.DFA);
  }

  @Test
  void forcedBitStateAndNfaReportTheExactEngine() {
    Pattern.setDiagnostics(diagnostics);
    EnginePathOptions bitStateOnly =
        EnginePathOptions.builder()
            .literalFastPaths(false)
            .charClassMatchFastPaths(false)
            .keywordAlternationFastPath(false)
            .shiftDfa(false)
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
            .shiftDfa(false)
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
  void compositeRejectPrefilterReportsTheChildThatRejected() {
    Pattern.setDiagnostics(diagnostics);

    for (MatchOperation operation : List.of(MatchOperation.FIND, MatchOperation.MATCHES)) {
      assertCompositeRejection(operation, "needle", MatchStrategy.CHARACTER_CLASS);
      assertCompositeRejection(operation, "x", MatchStrategy.LITERAL);
    }
  }

  @Test
  void compositeRejectPrefilterReplacementReportsTheChildThatRejected() {
    Pattern.setDiagnostics(diagnostics);

    for (MatchOperation operation :
        List.of(MatchOperation.REPLACE_FIRST, MatchOperation.REPLACE_ALL)) {
      Pattern pattern = Pattern.compile("foo.*bar.*[0-9]$");
      Matcher matcher = pattern.matcher("foo---7");

      String result =
          operation == MatchOperation.REPLACE_FIRST
              ? matcher.replaceFirst("replacement")
              : matcher.replaceAll("replacement");

      assertThat(result).isEqualTo("foo---7");
      assertThat(operationsFor(pattern))
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.operation()).isEqualTo(operation);
                assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.LITERAL);
                assertThat(event.auxiliaryStrategies())
                    .containsExactly(
                        new StrategyParticipation(
                            MatchStrategy.LITERAL, StrategyRole.REJECT_PREFILTER));
              });
    }
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
            .shiftDfa(false)
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
              assertThat(event.forwardDfaSearchCount()).isEqualTo(3);
              assertThat(event.reverseDfaSearchCount()).isEqualTo(2);
              assertThat(event.auxiliaryStrategies())
                  .contains(
                      new StrategyParticipation(
                          MatchStrategy.DFA, StrategyRole.CANDIDATE_VERIFICATION));
            });
  }

  @Test
  void nullableDfaReplacementCountsTerminalEmptyMatch() {
    Pattern.setDiagnostics(diagnostics);

    for (String input : List.of("", "b", "bbb")) {
      Pattern pattern = Pattern.compile("a?");
      long expectedMatches = input.length() + 1L;

      assertThat(pattern.matcher(input).replaceAll("x"))
          .isEqualTo(java.util.regex.Pattern.compile("a?").matcher(input).replaceAll("x"));

      assertThat(operationsFor(pattern))
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.operation()).isEqualTo(MatchOperation.REPLACE_ALL);
                assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
                assertThat(event.matchCount()).isEqualTo(expectedMatches);
              });
    }
  }

  @Test
  void dfaSandwichReportsForwardAndReverseSearchCounts() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("[ab]+c");

    assertThat(pattern.matcher("xxabc").find()).isTrue();

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
  void usePatternRunsDisjointLiteralPrefilterForReplacementPattern() {
    Pattern.setDiagnostics(diagnostics);
    Pattern first = Pattern.compile("(?:banana\\d|apple\\d)");
    Pattern replacement = Pattern.compile("(?:cherry\\d|pear\\d)");
    Matcher matcher = first.matcher("apple0 remainder");

    assertThat(matcher.find()).isTrue();
    matcher.usePattern(replacement);
    matcher.reset("remainder");
    assertThat(matcher.find()).isFalse();

    assertThat(operationsFor(replacement))
        .singleElement()
        .satisfies(
            event ->
                assertThat(event.auxiliaryStrategies())
                    .contains(
                        new StrategyParticipation(
                            MatchStrategy.LITERAL, StrategyRole.REJECT_PREFILTER)));
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
            .shiftDfa(false)
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
    AtomicInteger observedForwardSearchCount = new AtomicInteger();
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
              observedForwardSearchCount.set(event.forwardDfaSearchCount());
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
    assertThat(observedForwardSearchCount).hasPositiveValue();
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
  void exhaustedStartAnchorDoesNotReportPrefilterParticipation() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("^a");
    Matcher matcher = pattern.matcher("za").region(1, 2).useAnchoringBounds(true);

    assertThat(matcher.find()).isTrue();
    assertThat(matcher.find()).isFalse();

    assertThat(operationsFor(pattern).get(1).auxiliaryStrategies()).isEmpty();
  }

  @Test
  void startAnchoredRejectionReportsPrefilterStrategy() {
    Pattern.setDiagnostics(diagnostics);
    Map<String, MatchStrategy> cases =
        Map.of("^target", MatchStrategy.LITERAL, "^[a-z]", MatchStrategy.CHARACTER_CLASS);

    cases.forEach(
        (regex, expectedStrategy) -> {
          Pattern pattern = Pattern.compile(regex);

          assertThat(pattern.matcher("9target").find()).isFalse();
          assertThat(operationsFor(pattern))
              .singleElement()
              .satisfies(
                  event -> {
                    assertThat(event.boundaryStrategy()).isEqualTo(expectedStrategy);
                    assertThat(event.auxiliaryStrategies())
                        .containsExactly(
                            new StrategyParticipation(
                                expectedStrategy, StrategyRole.REJECT_PREFILTER));
                  });
        });
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

  @Test
  void variableGapChainsExecuteViaMultiAnchor() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile(".*error:\\[[A-Z]+\\]\\s+code:500\\s+msg:crash");
    String input = "2026-08-27 12:00:00 [worker-1] error:[CRITICAL] code:500 msg:crash\n";
    Matcher matcher = pattern.matcher(input);
    assertThat(matcher.find()).isTrue();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.MULTI_ANCHOR);
              assertThat(event.forwardDfaSearchCount()).isZero();
            });
  }

  @Test
  void multiAnchorNegativeShortCircuitBypassesDfa() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile(".*error:\\[[A-Z]+\\]\\s+code:500\\s+msg:crash");
    String input = "2026-08-27 12:00:00 [worker-1] error:[NORMAL] code:200 msg:ok\n";
    Matcher matcher = pattern.matcher(input);
    assertThat(matcher.find()).isFalse();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.forwardDfaSearchCount()).isZero();
            });
  }

  @Test
  void executableMultiAnchorMismatchRecordsParticipation() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("AAA[0-9]BB");

    assertThat(pattern.matcher("AAA-BB").find()).isFalse();
    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.MULTI_ANCHOR);
              assertThat(event.auxiliaryStrategies())
                  .contains(
                      new StrategyParticipation(
                          MatchStrategy.MULTI_ANCHOR, StrategyRole.CANDIDATE_VERIFICATION));
            });
  }

  private List<OperationDiagnostics> operationsFor(Pattern pattern) {
    long patternId = pattern.descriptor().patternId();
    return diagnostics.operations.stream()
        .filter(event -> event.pattern().patternId() == patternId)
        .toList();
  }

  private void assertCompositeRejection(
      MatchOperation operation, String input, MatchStrategy expectedStrategy) {
    Pattern pattern = Pattern.compile(".*x.*needle.*");
    Matcher matcher = pattern.matcher(input);

    boolean matched =
        switch (operation) {
          case FIND -> matcher.find();
          case MATCHES -> matcher.matches();
          default -> throw new AssertionError("unsupported operation: " + operation);
        };

    assertThat(matched).isFalse();
    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(expectedStrategy);
              assertThat(event.auxiliaryStrategies())
                  .containsExactly(
                      new StrategyParticipation(expectedStrategy, StrategyRole.REJECT_PREFILTER));
            });
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
