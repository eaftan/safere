// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
  void patternIdentityIsStableAndDistinctWithoutDiagnostics() {
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
    Pattern first = Pattern.compile("abc");
    Pattern second = Pattern.compile("abc");

    long firstId = first.patternId();
    assertThat(firstId).isPositive();
    assertThat(second.patternId()).isPositive().isNotEqualTo(firstId);
    assertThat(first.matcher("abc").matches()).isTrue();
    assertThat(first.patternId()).isEqualTo(firstId);
  }

  @Test
  void deserializedPatternHasANewDiagnosticsIdentity() throws Exception {
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
    Pattern original = Pattern.compile("(a+)b", Pattern.CASE_INSENSITIVE);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
      output.writeObject(original);
    }

    Pattern restored;
    try (ObjectInputStream input =
        new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      restored = (Pattern) input.readObject();
    }

    assertThat(restored.patternId()).isPositive().isNotEqualTo(original.patternId());
    assertThat(restored.pattern()).isEqualTo(original.pattern());
    assertThat(restored.flags()).isEqualTo(original.flags());
    Pattern.setDiagnostics(diagnostics);
    assertThat(restored.matcher("AAB").matches()).isTrue();
    assertThat(diagnostics.operations)
        .singleElement()
        .satisfies(
            event -> assertThat(event.pattern().patternId()).isEqualTo(restored.patternId()));
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
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
    Pattern pattern = Pattern.compile("abc");
    long patternId = pattern.patternId();
    Pattern.setDiagnostics(diagnostics);

    assertThat(pattern.matcher("abc").matches()).isTrue();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.pattern().analysis()).isSameAs(pattern.analysis());
              assertThat(event.pattern().patternId()).isEqualTo(patternId);
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.LITERAL);
              assertThat(event.forwardDfaSearchCount()).isZero();
              assertThat(event.reverseDfaSearchCount()).isZero();
            });
    assertThat(diagnostics.compilations).isEmpty();
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
    assertThat(pattern.patternId()).isEqualTo(patternId);
  }

  @Test
  void asPredicateReportsOneFindOperation() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("[0-9]+x");

    assertThat(pattern.asPredicate().test("ab 12x cd")).isTrue();
    assertThat(pattern.asPredicate().test("ab 12 cd")).isFalse();

    assertThat(operationsFor(pattern))
        .extracting(OperationDiagnostics::operation, OperationDiagnostics::outcome)
        .containsExactly(
            tuple(MatchOperation.FIND, MatchOutcome.MATCH),
            tuple(MatchOperation.FIND, MatchOutcome.NO_MATCH));
  }

  @Test
  void literalAlternationSearchesReportADistinctStrategy() {
    Pattern.setDiagnostics(diagnostics);
    for (String regex : List.of("apple|banana|cherry", "foo|barfoo")) {
      Pattern pattern = Pattern.compile(regex);
      for (String input : List.of("barfoo apple", "xxxxx")) {
        assertThat(pattern.matcher(input).find()).isEqualTo(input.equals("barfoo apple"));
        OperationDiagnostics event = operationsFor(pattern).getLast();
        assertThat(event.boundaryStrategy()).as(regex).isEqualTo(MatchStrategy.LITERAL_ALTERNATION);
        assertThat(event.forwardDfaSearchCount()).isZero();
        assertThat(event.reverseDfaSearchCount()).isZero();
      }
    }
  }

  @Test
  void compilationAndOperationsShareDescriptor() {
    Pattern.setDiagnostics(diagnostics);

    Pattern pattern = Pattern.compile("^GET ([^ ]+)");
    assertThat(pattern.matcher("GET /index").lookingAt()).isTrue();

    PatternCompiledEvent compilation =
        diagnostics.compilations.stream()
            .filter(event -> event.pattern().patternId() == pattern.patternId())
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
        .isEqualTo(MatchStrategy.ONE_PASS);

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

  @ParameterizedTest
  @MethodSource("lazyShiftDfaSearchCases")
  void lazyShiftDfaSearchesRetainOnePassFallback(
      String regex, String input, MatchOperation operation, boolean utf8) {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile(regex);
    assertThat(pattern.shiftDfaAnalyzed()).isFalse();

    boolean matched;
    if (utf8) {
      Utf8Matcher matcher = pattern.matcher(Utf8Input.validated(input.getBytes(UTF_8)));
      matched = operation == MatchOperation.FIND ? matcher.find() : matcher.lookingAt();
    } else {
      Matcher matcher = pattern.matcher(input);
      matched = operation == MatchOperation.FIND ? matcher.find() : matcher.lookingAt();
    }

    assertThat(matched).isTrue();
    assertThat(operationsFor(pattern).getLast().boundaryStrategy())
        .isEqualTo(MatchStrategy.ONE_PASS);
    assertThat(pattern.shiftDfaAnalyzed()).isFalse();
  }

  private static Stream<Arguments> lazyShiftDfaSearchCases() {
    Stream.Builder<Arguments> cases = Stream.builder();
    for (boolean utf8 : List.of(false, true)) {
      for (MatchOperation operation : List.of(MatchOperation.FIND, MatchOperation.LOOKING_AT)) {
        cases.add(Arguments.of("^a[é]*", "aéé", operation, utf8));
        cases.add(Arguments.of("^a{9}b?c?", "aaaaaaaaabc", operation, utf8));
        cases.add(Arguments.of("^a[bc]*", "abcbc", operation, utf8));
      }
    }
    return cases.build();
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

  private static Stream<Arguments> reverseCandidateVerificationCases() {
    return Stream.of(false, true)
        .flatMap(
            bitState ->
                Stream.of(
                    Arguments.of(bitState, "(?:foo|fax)$", "x".repeat(1400) + "foo"),
                    Arguments.of(bitState, "(?:foo|fax)$", "x".repeat(1400) + "foo\r\n"),
                    Arguments.of(bitState, "(?:foo|fax)\\z", "x".repeat(1400) + "fax"),
                    Arguments.of(bitState, "foo[ab]*$", "zzfoo" + "a".repeat(2048))));
  }

  @ParameterizedTest
  @MethodSource("reverseCandidateVerificationCases")
  void utf8ReverseCandidateReportsItsExactEngine(boolean bitState, String regex, String input) {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern =
        Pattern.compile(regex, 0, EnginePathOptions.builder().bitState(bitState).build());

    assertThat(pattern.find(Utf8Input.validated(input.getBytes(UTF_8)))).isTrue();
    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.reverseDfaSearchCount()).isPositive();
              assertThat(event.boundaryStrategy())
                  .isEqualTo(bitState ? MatchStrategy.BIT_STATE : MatchStrategy.NFA);
              assertThat(event.auxiliaryStrategies())
                  .contains(
                      new StrategyParticipation(
                          bitState ? MatchStrategy.BIT_STATE : MatchStrategy.NFA,
                          StrategyRole.CANDIDATE_VERIFICATION));
              if (regex.equals("foo[ab]*$")) {
                assertThat(event.reverseDfaSearchCount()).isEqualTo(2);
              }
            });
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
      // Reach the composite prefilter rather than the earlier minimum-length rejection.
      assertCompositeRejection(operation, "needle ", MatchStrategy.CHARACTER_CLASS);
      assertCompositeRejection(operation, "xxxxxxx", MatchStrategy.LITERAL);
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
  void citationScrubberReplacementRejectsOnCharacterClassPrefilter() {
    Pattern.setDiagnostics(diagnostics);

    String regex = " ?[\\[\uFF3B](?:(?:\\d+\\.){2,}\\d+(?:, )?)+[\\]\uFF3D]";
    Pattern pattern = Pattern.compile(regex);

    for (String input :
        List.of("digits 1.2.3.4 and dots but no brackets", "letters and spaces only")) {
      for (MatchOperation operation :
          List.of(MatchOperation.REPLACE_FIRST, MatchOperation.REPLACE_ALL)) {
        diagnostics.operations.clear();
        Matcher matcher = pattern.matcher(input);
        String result =
            operation == MatchOperation.REPLACE_FIRST
                ? matcher.replaceFirst("replacement")
                : matcher.replaceAll("replacement");

        assertThat(result).isEqualTo(input);
        assertThat(operationsFor(pattern))
            .singleElement()
            .satisfies(
                event -> {
                  assertThat(event.operation()).isEqualTo(operation);
                  assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.CHARACTER_CLASS);
                  assertThat(event.auxiliaryStrategies())
                      .contains(
                          new StrategyParticipation(
                              MatchStrategy.CHARACTER_CLASS, StrategyRole.REJECT_PREFILTER));
                });
      }
    }

    // Brackets present with no matching digits return the right result without false-positive
    // match.
    for (String input :
        List.of(
            "[brackets without digits]",
            "prefix [1.2 incomplete] suffix",
            "［full width brackets without digits］")) {
      for (MatchOperation operation :
          List.of(MatchOperation.REPLACE_FIRST, MatchOperation.REPLACE_ALL)) {
        diagnostics.operations.clear();
        Matcher matcher = pattern.matcher(input);
        String result =
            operation == MatchOperation.REPLACE_FIRST
                ? matcher.replaceFirst("replacement")
                : matcher.replaceAll("replacement");

        assertThat(result).isEqualTo(input);
        assertThat(operationsFor(pattern))
            .singleElement()
            .satisfies(
                event -> {
                  assertThat(event.operation()).isEqualTo(operation);
                  assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
                });
      }
    }
  }

  @Test
  void startAnchoredReplacementRejectsOnTheAnchoredPrefix() {
    Pattern.setDiagnostics(diagnostics);

    // Start-anchored scrubbers with a literal anchored prefix of "(" and "[" respectively.
    for (String regex :
        List.of(
            "^\\([Ss]ource:\\s*\\d+(?:\\s*,\\s*(?:[Ss]ource:\\s*)?\\d+)*\\s*\\)\\s*",
            "^\\[[Ss]ource:\\s*\\d+(?:\\s*,\\s*(?:[Ss]ource:\\s*)?\\d+)*\\s*\\]\\s*")) {
      for (MatchOperation operation :
          List.of(MatchOperation.REPLACE_FIRST, MatchOperation.REPLACE_ALL)) {
        Pattern pattern = Pattern.compile(regex);
        String input = "No citation marker begins this paragraph.";
        Matcher matcher = pattern.matcher(input);

        String result =
            operation == MatchOperation.REPLACE_FIRST
                ? matcher.replaceFirst("replacement")
                : matcher.replaceAll("replacement");

        assertThat(result).isEqualTo(input);
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
  }

  @Test
  void startAnchoredReplacementRejectsOnTheAnchoredCharacterClass() {
    Pattern.setDiagnostics(diagnostics);

    for (MatchOperation operation :
        List.of(MatchOperation.REPLACE_FIRST, MatchOperation.REPLACE_ALL)) {
      Pattern pattern = Pattern.compile("^[0-9]+ items");
      String input = "no leading digit here";
      Matcher matcher = pattern.matcher(input);

      String result =
          operation == MatchOperation.REPLACE_FIRST
              ? matcher.replaceFirst("replacement")
              : matcher.replaceAll("replacement");

      assertThat(result).isEqualTo(input);
      assertThat(operationsFor(pattern))
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.operation()).isEqualTo(operation);
                assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.CHARACTER_CLASS);
                assertThat(event.auxiliaryStrategies())
                    .containsExactly(
                        new StrategyParticipation(
                            MatchStrategy.CHARACTER_CLASS, StrategyRole.REJECT_PREFILTER));
              });
    }
  }

  @Test
  void multilineStartAnchorDoesNotTakeTheStartAnchoredRejectPath() {
    Pattern.setDiagnostics(diagnostics);

    // (?m)^ lowers to a begin-line assertion, not prog().anchorStart(), so the new reject arm must
    // not fire: a match can legitimately begin at a later line.
    Pattern pattern = Pattern.compile("(?m)^\\(Source: \\d+\\)\\s*");
    Matcher matcher = pattern.matcher("first line\n(Source: 7) second line");

    assertThat(matcher.replaceAll("")).isEqualTo("first line\nsecond line");
    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event ->
                assertThat(event.auxiliaryStrategies())
                    .doesNotContain(
                        new StrategyParticipation(
                            MatchStrategy.LITERAL, StrategyRole.REJECT_PREFILTER)));
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
              // The terminal start-accelerator scan rejects without another DFA search.
              assertThat(event.forwardDfaSearchCount()).isEqualTo(2);
              assertThat(event.reverseDfaSearchCount()).isEqualTo(2);
              assertThat(event.auxiliaryStrategies())
                  .contains(
                      new StrategyParticipation(
                          MatchStrategy.DFA, StrategyRole.CANDIDATE_VERIFICATION));
            });
  }

  @Test
  void abandonedCaptureSpeculationPreservesEarlierReplacementBoundary() {
    Pattern pattern = Pattern.compile("(error:\\[)[A-Z](\\] code:500)");
    Matcher priming = pattern.matcher("error:[C] code:500");
    assertThat(priming.find()).isTrue();
    assertThat(priming.group(1)).isEqualTo("error:[");

    Pattern.setDiagnostics(diagnostics);
    assertThat(
            pattern.matcher("error:[C] code:500 xxx error:[x] code:500").replaceAll(result -> "ok"))
        .isEqualTo("ok xxx error:[x] code:500");

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.operation()).isEqualTo(MatchOperation.REPLACE_ALL);
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.BIT_STATE);
              assertThat(event.matchCount()).isEqualTo(1);
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
  void captureHistoryFromEagerResultsPreservesAcceleratedDfaRejection() {
    Pattern.setDiagnostics(diagnostics);
    for (boolean utf8 : new boolean[] {false, true}) {
      Pattern pattern = Pattern.compile("(error:\\[)[A-Z](\\] code:500)");
      String successful = "error:[C] code:500";
      String rejected = "error:[x] code:500 ".repeat(20);
      if (utf8) {
        Utf8Matcher primer = pattern.matcher(Utf8Input.trusted(successful.getBytes(UTF_8)));
        assertThat(primer.matches()).isTrue();
        assertThat(primer.start(1)).isZero();
      } else {
        Matcher primer = pattern.matcher(successful);
        assertThat(primer.matches()).isTrue();
        assertThat(primer.start(1)).isZero();
      }
      assertThat(pattern.innerCapturesObserved()).isTrue();
      if (utf8) {
        assertThat(pattern.matcher(Utf8Input.trusted(rejected.getBytes(UTF_8))).find()).isFalse();
      } else {
        assertThat(pattern.matcher(rejected).find()).isFalse();
      }
      assertThat(operationsFor(pattern))
          .last()
          .satisfies(
              event -> {
                assertThat(event.outcome()).isEqualTo(MatchOutcome.NO_MATCH);
                assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
                assertThat(event.forwardDfaSearchCount()).isPositive();
              });
    }
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
  void variableGapChainsFallBackToGeneralEngine() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile(".*error:\\[[A-Z]+\\]\\s+code:500\\s+msg:crash");
    String input = "2026-08-27 12:00:00 [worker-1] error:[CRITICAL] code:500 msg:crash\n";
    Matcher matcher = pattern.matcher(input);
    assertThat(matcher.find()).isTrue();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
              assertThat(event.forwardDfaSearchCount()).isPositive();
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
  void primaryBitStateAndNfaCaptureSearchesReportEagerCaptureAttribution() {
    Pattern.setDiagnostics(diagnostics);

    Pattern nullableLoopBitState = Pattern.compile("(?:(a)?)*b");
    Matcher matchesMatcher = nullableLoopBitState.matcher("aab");
    assertThat(matchesMatcher.matches()).isTrue();
    assertThat(matchesMatcher.capturesResolved()).isTrue();

    Matcher lookingAtMatcher = nullableLoopBitState.matcher("aabx");
    assertThat(lookingAtMatcher.lookingAt()).isTrue();
    assertThat(lookingAtMatcher.capturesResolved()).isTrue();

    Pattern regionBitState = Pattern.compile("(a+?)b");
    Matcher regionMatcher = regionBitState.matcher("xaabx").region(1, 4);
    assertThat(regionMatcher.find()).isTrue();
    assertThat(regionMatcher.capturesResolved()).isTrue();

    assertThat(operationsFor(nullableLoopBitState))
        .hasSize(2)
        .allSatisfy(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.BIT_STATE);
              assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.BIT_STATE);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.EAGER);
            });
    assertThat(operationsFor(regionBitState))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.BIT_STATE);
              assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.BIT_STATE);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.EAGER);
            });

    Pattern nfaPattern = Pattern.compile("(\\X)b");
    Matcher nfaMatches = nfaPattern.matcher("ab");
    assertThat(nfaMatches.matches()).isTrue();
    assertThat(nfaMatches.capturesResolved()).isTrue();

    Matcher nfaFind = nfaPattern.matcher("xxab");
    assertThat(nfaFind.find()).isTrue();
    assertThat(nfaFind.capturesResolved()).isTrue();

    assertThat(operationsFor(nfaPattern))
        .hasSize(2)
        .allSatisfy(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.NFA);
              assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.NFA);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.EAGER);
            });
  }

  @Test
  void deferredExactBoundarySearchesKeepDeferredCaptureAttribution() {
    Pattern.setDiagnostics(diagnostics);

    EnginePathOptions noDfa = EnginePathOptions.builder().dfa(false).build();
    Pattern lazyFallbackPattern = Pattern.compile("(a+?)b", 0, noDfa);
    Matcher lazyFallbackMatcher = lazyFallbackPattern.matcher("xxaab");
    assertThat(lazyFallbackMatcher.find()).isTrue();
    assertThat(lazyFallbackMatcher.capturesResolved()).isFalse();

    assertThat(operationsFor(lazyFallbackPattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.BIT_STATE);
              assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.NONE);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.DEFERRED);
            });
    assertThat(lazyFallbackMatcher.group(1)).isEqualTo("aa");

    Pattern endAnchoredPattern = Pattern.compile("foo(a+?)b$");
    Matcher endAnchoredMatcher = endAnchoredPattern.matcher("zzfooaab");
    assertThat(endAnchoredMatcher.find()).isTrue();
    assertThat(endAnchoredMatcher.capturesResolved()).isFalse();

    assertThat(operationsFor(endAnchoredPattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.NONE);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.DEFERRED);
            });
    assertThat(endAnchoredMatcher.group(1)).isEqualTo("aa");
  }

  @Test
  void speculativeCaptureCandidateMissRestoresAttributionWhenDfaFindsLaterMatch() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("foo(a+)b");

    Matcher warmup = pattern.matcher("fooaab");
    assertThat(warmup.find()).isTrue();
    assertThat(warmup.group(1)).isEqualTo("aa");
    diagnostics.operations.clear();

    Matcher speculativeHit = pattern.matcher("xxfooaab");
    assertThat(speculativeHit.find()).isTrue();
    assertThat(speculativeHit.capturesResolved()).isTrue();
    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.BIT_STATE);
              assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.BIT_STATE);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.EAGER);
            });
    diagnostics.operations.clear();

    Matcher speculativeMissThenDfa = pattern.matcher("fooxxx xxfooaab");
    assertThat(speculativeMissThenDfa.find()).isTrue();
    assertThat(speculativeMissThenDfa.capturesResolved()).isFalse();
    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
              assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.NONE);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.DEFERRED);
            });
    assertThat(speculativeMissThenDfa.group(1)).isEqualTo("aa");
  }

  @Test
  void matchesTrailingNewlineNfaFallbackOverridesCaptureAttribution() {
    Pattern.setDiagnostics(diagnostics);
    EnginePathOptions noDfa =
        EnginePathOptions.builder().dfa(false).onePass(false).shiftDfa(false).build();

    Pattern fullMatchViaNfa = Pattern.compile("^(a+)(\\n??)$", 0, noDfa);
    Matcher matched = fullMatchViaNfa.matcher("aa\n");
    assertThat(matched.matches()).isTrue();
    assertThat(matched.group(1)).isEqualTo("aa");
    assertThat(matched.group(2)).isEqualTo("\n");
    assertThat(operationsFor(fullMatchViaNfa))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.outcome()).isEqualTo(MatchOutcome.MATCH);
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.NFA);
              assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.NFA);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.EAGER);
            });

    Pattern rejectedViaNfa = Pattern.compile("^(a+?)$", 0, noDfa);
    Matcher rejected = rejectedViaNfa.matcher("aa\n");
    assertThat(rejected.matches()).isFalse();
    assertThat(operationsFor(rejectedViaNfa))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.outcome()).isEqualTo(MatchOutcome.NO_MATCH);
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.NFA);
              assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.NONE);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.NONE);
            });
  }

  @Test
  void failedOnePassAndKeywordOperationsReportNoCaptureStrategy() {
    Pattern.setDiagnostics(diagnostics);

    Pattern onePassPattern = Pattern.compile("^([a-z]+):(\\d+)$");
    assertThat(onePassPattern.matcher("abc:nope").matches()).isFalse();
    assertThat(onePassPattern.matcher("abc:nope").find()).isFalse();
    assertThat(onePassPattern.matcher("abc:nope").replaceFirst("$1")).isEqualTo("abc:nope");
    assertThat(onePassPattern.matcher("abc:nope").replaceAll("$1")).isEqualTo("abc:nope");

    Pattern onePassPrefixPattern = Pattern.compile("^([a-z]+):(\\d+)");
    assertThat(onePassPrefixPattern.matcher("abc:nope").lookingAt()).isFalse();

    assertThat(operationsFor(onePassPattern))
        .hasSize(4)
        .allSatisfy(
            event -> {
              assertThat(event.outcome()).isEqualTo(MatchOutcome.NO_MATCH);
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.ONE_PASS);
              assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.NONE);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.NONE);
            });
    assertThat(operationsFor(onePassPrefixPattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.outcome()).isEqualTo(MatchOutcome.NO_MATCH);
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.ONE_PASS);
              assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.NONE);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.NONE);
            });

    Pattern anchoredKeywordPattern = Pattern.compile("(?is).*\\b(you|your)\\b.*");
    assertThat(anchoredKeywordPattern.matcher("nothing matching here").find()).isFalse();
    Pattern unanchoredKeywordPattern = Pattern.compile("(?i)\\b(you|your)\\b");
    assertThat(unanchoredKeywordPattern.matcher("nothing matching here").find()).isFalse();

    for (Pattern pattern : List.of(anchoredKeywordPattern, unanchoredKeywordPattern)) {
      assertThat(operationsFor(pattern))
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.outcome()).isEqualTo(MatchOutcome.NO_MATCH);
                assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.KEYWORD);
                assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.NONE);
                assertThat(event.captureMode()).isEqualTo(CaptureMode.NONE);
              });
    }
  }

  @ParameterizedTest
  @MethodSource("captureAttributionAlignmentCases")
  void captureModeAndCaptureStrategyAgreeWithMatcherStateAcrossOperations(
      String regex, String matchingInput, String nonMatchingInput) {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile(regex);
    boolean hasCaptures = pattern.analysis().captureCount() > 0;

    for (String input : List.of(matchingInput, nonMatchingInput)) {
      diagnostics.operations.clear();
      Matcher mMatches = pattern.matcher(input);
      boolean matched = mMatches.matches();
      assertSingleOperationCaptureAgreement(
          operationsFor(pattern), matched, hasCaptures, mMatches.capturesResolved());

      diagnostics.operations.clear();
      Matcher mLookingAt = pattern.matcher(input);
      boolean lookedAt = mLookingAt.lookingAt();
      assertSingleOperationCaptureAgreement(
          operationsFor(pattern), lookedAt, hasCaptures, mLookingAt.capturesResolved());

      diagnostics.operations.clear();
      Matcher mFind = pattern.matcher(input);
      boolean found = mFind.find();
      assertSingleOperationCaptureAgreement(
          operationsFor(pattern), found, hasCaptures, mFind.capturesResolved());
      if (found && hasCaptures) {
        mFind.group(1);
        diagnostics.operations.clear();
        Matcher mPostDemandFind = pattern.matcher(input);
        boolean foundAgain = mPostDemandFind.find();
        assertSingleOperationCaptureAgreement(
            operationsFor(pattern), foundAgain, hasCaptures, mPostDemandFind.capturesResolved());
      }

      String captureReplacement = hasCaptures ? "$1" : "x";
      for (String replacement : List.of("x", captureReplacement)) {
        diagnostics.operations.clear();
        assertThat(pattern.matcher(input).replaceFirst(replacement)).isNotNull();
        assertCaptureStrategyMatchesMode(operationsFor(pattern));

        diagnostics.operations.clear();
        assertThat(pattern.matcher(input).replaceAll(replacement)).isNotNull();
        assertCaptureStrategyMatchesMode(operationsFor(pattern));
      }
    }
  }

  private static Stream<Arguments> captureAttributionAlignmentCases() {
    return Stream.of(
        Arguments.of("^([a-z]+):(\\d+)$", "abc:123", "abc:nope"),
        Arguments.of("([a-z]+):(\\d+)", "abc:123", "nope"),
        Arguments.of("(a+?)b", "aab", "zzz"),
        Arguments.of("(a+|ab)c", "abc", "zzz"),
        Arguments.of("foo(a+)b", "xxfooaab", "fooxxx"),
        Arguments.of("(?is).*\\b(you|your)\\b.*", "here is your token", "nothing"),
        Arguments.of("(\\X)b", "ab", "zz"),
        Arguments.of("\\d{4}-\\d{2}-\\d{2}", "2026-10-30", "nope"));
  }

  private static void assertSingleOperationCaptureAgreement(
      List<OperationDiagnostics> events,
      boolean matched,
      boolean hasCaptures,
      boolean matcherCapturesResolved) {
    assertCaptureStrategyMatchesMode(events);
    assertThat(events)
        .singleElement()
        .satisfies(
            event -> {
              if (!matched || !hasCaptures) {
                assertThat(event.captureMode()).isEqualTo(CaptureMode.NONE);
                assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.NONE);
              } else {
                assertThat(event.captureMode() == CaptureMode.EAGER)
                    .isEqualTo(matcherCapturesResolved);
              }
            });
  }

  private static void assertCaptureStrategyMatchesMode(List<OperationDiagnostics> events) {
    assertThat(events)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.captureStrategy() != MatchStrategy.NONE)
                  .isEqualTo(event.captureMode() == CaptureMode.EAGER);
              if (event.outcome() == MatchOutcome.NO_MATCH
                  || event.pattern().analysis().captureCount() == 0) {
                assertThat(event.captureMode()).isEqualTo(CaptureMode.NONE);
                assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.NONE);
              }
            });
  }

  private List<OperationDiagnostics> operationsFor(Pattern pattern) {
    long patternId = pattern.patternId();
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
