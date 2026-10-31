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
import org.junit.jupiter.params.provider.ValueSource;

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

  @ParameterizedTest
  @MethodSource("literalFindOptions")
  void patternBooleanFindHonorsLiteralFastPathOption(
      String literal, boolean literalFastPaths, boolean present) {
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
    // Disable independent start acceleration so misses also reach the chosen boundary engine.
    Pattern pattern =
        Pattern.compile(
            literal,
            0,
            EnginePathOptions.builder()
                .literalFastPaths(literalFastPaths)
                .startAcceleration(false)
                .build());
    Utf8Input input = input(present ? "prefix " + literal + " suffix" : "unrelated text");

    // With diagnostics disabled, lazy DFA creation proves the literal shortcut was bypassed.
    assertThat(pattern.cachedForwardFirstMatchDfa()).isNull();
    assertThat(pattern.find(input)).isEqualTo(present);
    if (literalFastPaths) {
      assertThat(pattern.cachedForwardFirstMatchDfa()).isNull();
    } else {
      assertThat(pattern.cachedForwardFirstMatchDfa()).isNotNull();
    }

    Pattern.setDiagnostics(diagnostics);
    assertThat(pattern.find(input)).isEqualTo(present);

    MatchStrategy expected = literalFastPaths ? MatchStrategy.LITERAL : MatchStrategy.DFA;
    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(expected);
              assertThat(event.forwardDfaSearchCount()).isEqualTo(literalFastPaths ? 0 : 1);
              assertThat(event.outcome())
                  .isEqualTo(present ? MatchOutcome.MATCH : MatchOutcome.NO_MATCH);
            });
  }

  private static Stream<Arguments> literalFindOptions() {
    return Stream.of("hello", "é", "😀", "café😀")
        .flatMap(
            literal ->
                Stream.of(false, true)
                    .flatMap(
                        literalFastPaths ->
                            Stream.of(false, true)
                                .map(present -> Arguments.of(literal, literalFastPaths, present))));
  }

  @Test
  void keywordAlternationFindReportsUtf8FastPath() {
    Pattern.setDiagnostics(diagnostics);
    Pattern matcherPattern = Pattern.compile("(?is).*\\b(you|your)\\b.*");
    Pattern booleanPattern = Pattern.compile("(?is).*\\b(you|your)\\b.*");
    Utf8Input input = input("a long prefix ending with YOUR answer");

    assertThat(matcherPattern.matcher(input).find()).isTrue();
    assertThat(booleanPattern.find(input)).isTrue();

    assertThat(operationsFor(matcherPattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.KEYWORD);
              assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.KEYWORD);
              assertThat(event.forwardDfaSearchCount()).isZero();
              assertThat(event.reverseDfaSearchCount()).isZero();
            });
    assertThat(operationsFor(booleanPattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.KEYWORD);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.NONE);
              assertThat(event.forwardDfaSearchCount()).isZero();
              assertThat(event.reverseDfaSearchCount()).isZero();
            });
  }

  @Test
  void startAnchoredLiteralFindWithDiagnosticsRejectsLaterOccurrence() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("^target");

    assertThat(pattern.find(input("prefix target"))).isFalse();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(event -> assertThat(event.outcome()).isEqualTo(MatchOutcome.NO_MATCH));
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
  void quantifiedSingleCharClassUtf8MatchesUsesCharacterClassFastPath() {
    EnginePathOptions noCharClassFastPath =
        EnginePathOptions.builder().charClassMatchFastPaths(false).build();
    List<String> regexes = List.of("[a-zA-Z0-9]+", "\\d*", "\\p{L}+", "[^a]+");
    List<String> inputs =
        List.of("", "abcXYZ123", "12345", "12a45", "élanβ", "élan42", "😀", "é😀b", "éab");

    for (String regex : regexes) {
      Pattern utf8Fast = Pattern.compile(regex);
      Pattern stringFast = Pattern.compile(regex);
      Pattern fallback = Pattern.compile(regex, 0, noCharClassFastPath);
      for (String text : inputs) {
        Pattern.setDiagnostics(diagnostics);
        boolean expected = stringFast.matcher(text).matches();
        OperationDiagnostics stringEvent = operationsFor(stringFast).getLast();
        Utf8Matcher utf8Matcher = matcher(utf8Fast, text);
        assertThat(utf8Matcher.matches()).as("/%s/ on '%s'", regex, text).isEqualTo(expected);
        if (expected) {
          assertThat(utf8Matcher.start()).isZero();
          assertThat(utf8Matcher.end()).isEqualTo(text.getBytes(UTF_8).length);
        }
        assertThat(operationsFor(utf8Fast).getLast())
            .satisfies(
                event -> {
                  assertThat(event.boundaryStrategy()).isEqualTo(stringEvent.boundaryStrategy());
                  if (!text.isEmpty() || expected) {
                    assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.CHARACTER_CLASS);
                  }
                  assertThat(event.forwardDfaSearchCount()).isZero();
                });
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
        assertThat(fallback.matcher(input(text)).matches()).isEqualTo(expected);

        // Region slicing preserves byte coordinates.
        byte[] framed = ("!a!" + text + "!a!").getBytes(UTF_8);
        int start = 3;
        int end = framed.length - 3;
        Utf8Matcher sliced = utf8Fast.matcher(Utf8Input.validated(framed)).region(start, end);
        assertThat(sliced.matches()).isEqualTo(expected);
        if (expected) {
          assertThat(sliced.start()).isEqualTo(start);
          assertThat(sliced.end()).isEqualTo(end);
        }
      }
    }

    // Malformed trusted UTF-8 bytes decode as U+FFFD, matching the fallback engine on both
    // ASCII-only classes (rejected) and negated classes (accepted when U+FFFD is in the class).
    byte[] malformed = new byte[] {(byte) 0x80, 'b', (byte) 0xED, (byte) 0xA0, (byte) 0x80};
    for (String regex : List.of("[a-z]+", "[^a]+", "[^\\uFFFD]+")) {
      Pattern fast = Pattern.compile(regex);
      Pattern fallback = Pattern.compile(regex, 0, noCharClassFastPath);
      boolean expected = fallback.matcher(Utf8Input.trusted(malformed)).matches();
      assertThat(fast.matcher(Utf8Input.trusted(malformed)).matches())
          .as("malformed trusted UTF-8 for /%s/", regex)
          .isEqualTo(expected);
    }
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
  void endAnchoredBooleanFindWithCapturesReportsOneCaptureFreeEvent() {
    // The existence-only end-anchored path verifies without computing captures.
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("(foo|fax)$");

    assertThat(pattern.find(input("say fax"))).isTrue();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.operation()).isEqualTo(MatchOperation.FIND);
              assertThat(event.outcome()).isEqualTo(MatchOutcome.MATCH);
              assertThat(event.matchCount()).isEqualTo(1);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.NONE);
            });
  }

  @Test
  void endAnchoredBooleanFindWithoutMatchReportsOneEvent() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("(foo|fax)$");

    assertThat(pattern.find(input("say fix"))).isFalse();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.outcome()).isEqualTo(MatchOutcome.NO_MATCH);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.NONE);
              assertThat(event.matchCount()).isZero();
            });
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void fullyAnchoredBooleanFindUsesKnownStart(boolean bitState) {
    Pattern.setDiagnostics(diagnostics);
    for (String terminal : List.of("END", "[0-9]")) {
      for (boolean valid : List.of(true, false)) {
        Pattern pattern =
            Pattern.compile(
                "^([a-z]+)" + terminal + "\\z",
                0,
                EnginePathOptions.builder().bitState(bitState).build());
        String text =
            "a".repeat(61) + (valid ? "" : "!") + "a" + (terminal.equals("END") ? "END" : "5");

        assertThat(pattern.find(input(text))).isEqualTo(valid);
        assertThat(operationsFor(pattern))
            .singleElement()
            .satisfies(
                event -> {
                  assertThat(event.outcome())
                      .isEqualTo(valid ? MatchOutcome.MATCH : MatchOutcome.NO_MATCH);
                  assertThat(event.reverseDfaSearchCount()).isZero();
                  assertThat(event.captureMode()).isEqualTo(CaptureMode.NONE);
                });
      }
    }
  }

  @Test
  void directBooleanFindWithCapturesReportsNoCaptureMode() {
    // Without an end anchor, Pattern.find(Utf8Input) stays on its matcher-free path, which never
    // computes captures.
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("(foo|fax)");

    assertThat(pattern.find(input("say fax"))).isTrue();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.outcome()).isEqualTo(MatchOutcome.MATCH);
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
        Arguments.of("終点😀$", "é😀".repeat(400) + "終点😀", true),
        Arguments.of("終点😀$", "é😀".repeat(400) + "終点😀\r\n", true),
        Arguments.of("終点😀$", "é😀".repeat(400) + "終点😀x", false),
        Arguments.of("(?:a+?|[^x]*)$", "x".repeat(1_024) + "a\n", true),
        Arguments.of("(a?)*X", "aaaaX", true));
  }

  @Test
  void absentRequiredLiteralRejectsBeforeUnboundedReverseWalk() {
    Pattern.setDiagnostics(diagnostics);
    Utf8Input input = input("x".repeat(20_000));
    for (String regex : List.of("needle.*$", "needle[a-z]*\\z")) {
      for (boolean existenceOnly : List.of(false, true)) {
        Pattern pattern = Pattern.compile(regex);
        assertThat(existenceOnly ? pattern.find(input) : pattern.matcher(input).find()).isFalse();
        assertThat(operationsFor(pattern))
            .singleElement()
            .satisfies(
                event -> {
                  assertThat(event.outcome()).isEqualTo(MatchOutcome.NO_MATCH);
                  assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.LITERAL);
                });
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void endAnchoredBooleanFindReportsReverseNarrowingAndExactVerification(boolean bitState) {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern =
        Pattern.compile("終点😀$", 0, EnginePathOptions.builder().bitState(bitState).build());
    MatchStrategy verification = bitState ? MatchStrategy.BIT_STATE : MatchStrategy.NFA;

    assertThat(pattern.find(input("é😀".repeat(400) + "終点😀\r\n"))).isTrue();

    assertThat(operationsFor(pattern))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.outcome()).isEqualTo(MatchOutcome.MATCH);
              assertThat(event.boundaryStrategy()).isEqualTo(verification);
              assertThat(event.reverseDfaSearchCount()).isEqualTo(2);
              assertThat(event.forwardDfaSearchCount()).isZero();
              assertThat(event.auxiliaryStrategies())
                  .containsExactly(
                      new StrategyParticipation(MatchStrategy.DFA, StrategyRole.REJECT_PREFILTER),
                      new StrategyParticipation(verification, StrategyRole.CANDIDATE_VERIFICATION));
            });
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
  void appendReplacementWithoutInnerGroupsKeepsSubsequentFindsOnDfa() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile("(é+)x");
    Utf8Matcher matcher = matcher(pattern, "zzééx yyééx");
    Utf8Sink noopSink = (bytes, offset, length) -> {};
    Utf8Input replacement = input("[$0]");

    while (matcher.find()) {
      matcher.appendReplacement(noopSink, replacement);
    }
    matcher.appendTail(noopSink);

    assertThat(operationsFor(pattern))
        .filteredOn(event -> event.outcome() == MatchOutcome.MATCH)
        .hasSize(2)
        .allSatisfy(
            event -> {
              // Resolving inner captures records demand for them, which moves later finds off the
              // DFA.
              assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
              assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.NONE);
              assertThat(event.captureMode()).isEqualTo(CaptureMode.DEFERRED);
            });
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
        .shiftDfa(false)
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
