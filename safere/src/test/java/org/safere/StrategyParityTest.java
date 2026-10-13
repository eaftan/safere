// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies strategy-selection parity across SafeRE's matching and replacement entry points using
 * {@link SafeReMatchDiagnostics}:
 *
 * <ol>
 *   <li>{@link Matcher#find()} ({@code String}) vs {@link Utf8Matcher#find()} ({@link Utf8Input}),
 *       plus {@code matches()} and {@code lookingAt()}.
 *   <li>{@link Utf8Matcher#find()} vs {@link Pattern#find(Utf8Input)} (boolean find).
 *   <li>{@link Matcher#find()} vs {@link Matcher#replaceFirst(String)} and {@link
 *       Matcher#replaceAll(String)}.
 * </ol>
 */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@DisabledForCrosscheck("Strategy parity diagnostics are a SafeRE-specific public API")
class StrategyParityTest {
  private static final String PAD_80 = "x".repeat(80);
  private static final String PAD_1100 = "x".repeat(1100);

  private final RecordingDiagnostics diagnostics = new RecordingDiagnostics();

  @AfterEach
  void disableDiagnostics() {
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
  }

  static Stream<Arguments> stringAndUtf8FindParityScenarios() {
    return Stream.of(
        // Exact case-sensitive literals (ASCII and multi-byte UTF-8)
        Arguments.of("literal-ascii-hit", "hello", "xxhelloyy", MatchStrategy.LITERAL),
        Arguments.of("literal-ascii-miss", "hello", "xxworldyy", MatchStrategy.LITERAL),
        Arguments.of("literal-utf8-hit", "café", "xxcaféyy", MatchStrategy.LITERAL),
        Arguments.of("literal-utf8-miss", "café", "xxworldyy", MatchStrategy.LITERAL),
        // Start-, end-, and both-anchored literals
        Arguments.of("start-anchored-literal-hit", "^hello", "helloyy", MatchStrategy.LITERAL),
        Arguments.of("start-anchored-literal-miss", "^hello", "xxhelloyy", MatchStrategy.LITERAL),
        Arguments.of("end-anchored-literal-hit", "hello$", "xxhello", MatchStrategy.DFA),
        Arguments.of("end-anchored-literal-miss", "hello$", "xxhelloyy", MatchStrategy.LITERAL),
        Arguments.of("both-anchored-literal-hit", "^hello$", "hello", MatchStrategy.ONE_PASS),
        Arguments.of("both-anchored-literal-miss", "^hello$", "helloyy", MatchStrategy.ONE_PASS),
        // Single character classes (ASCII, Unicode property, mixed ASCII/non-ASCII pair)
        Arguments.of("single-cc-ascii-hit", "[0-9]", "abc5def", MatchStrategy.CHARACTER_CLASS),
        Arguments.of("single-cc-ascii-miss", "[0-9]", "abcdef", MatchStrategy.CHARACTER_CLASS),
        Arguments.of("single-cc-unicode-hit", "\\p{L}", "123é456", MatchStrategy.CHARACTER_CLASS),
        Arguments.of("single-cc-unicode-miss", "\\p{L}", "123456", MatchStrategy.CHARACTER_CLASS),
        Arguments.of(
            "single-cc-mixed-pair-hit",
            "[\\]\\uFF3D]",
            "abc\uFF3Ddef",
            MatchStrategy.CHARACTER_CLASS),
        Arguments.of(
            "single-cc-mixed-pair-miss", "[\\]\\uFF3D]", "abcdef", MatchStrategy.CHARACTER_CLASS),
        // Quantified character class in find()
        Arguments.of("quant-cc-hit", "[0-9]+", "abc123def", MatchStrategy.DFA),
        Arguments.of("quant-cc-miss", "[0-9]+", "abcdef", MatchStrategy.DFA),
        // Start-anchored character class
        Arguments.of("start-anchored-cc-hit", "^[0-9]+ items", "42 items left", MatchStrategy.DFA),
        Arguments.of(
            "start-anchored-cc-miss-first",
            "^[0-9]+ items",
            "no items left",
            MatchStrategy.CHARACTER_CLASS),
        Arguments.of(
            "start-anchored-cc-miss-tail", "^[0-9]+ items", "42 things left", MatchStrategy.DFA),
        // Case-insensitive word-bounded keyword alternation
        Arguments.of(
            "keyword-alt-hit", "(?i)\\b(?:foo|bar|baz)\\b", "xx Bar yy", MatchStrategy.KEYWORD),
        Arguments.of(
            "keyword-alt-miss", "(?i)\\b(?:foo|bar|baz)\\b", "xx Barred yy", MatchStrategy.KEYWORD),
        // Start-anchored prefix reject
        Arguments.of("start-prefix-reject-hit", "^foo[0-9]+", "foo123bar", MatchStrategy.DFA),
        Arguments.of(
            "start-prefix-reject-miss-prefix", "^foo[0-9]+", "bar123", MatchStrategy.LITERAL),
        Arguments.of("start-prefix-reject-miss-body", "^foo[0-9]+", "foobar", MatchStrategy.DFA),
        // Composite, char-class, and end-anchored suffix reject prefilters
        Arguments.of(
            "composite-prefilter-miss-end-cc",
            "foo.*bar.*[0-9]$",
            "foo only no bar",
            MatchStrategy.CHARACTER_CLASS),
        Arguments.of(
            "composite-prefilter-miss-literal",
            "foo.*bar.*[0-9]$",
            "foo only no 7",
            MatchStrategy.LITERAL),
        Arguments.of(
            "composite-prefilter-hit", "foo.*bar.*[0-9]$", "foo and bar 7", MatchStrategy.DFA),
        Arguments.of("cc-prefilter-miss", "[xy]q?", "abcdefg", MatchStrategy.CHARACTER_CLASS),
        Arguments.of("cc-prefilter-hit", "[xy]q?", "abcxdef", MatchStrategy.DFA),
        Arguments.of("end-suffix-reject-miss", "[a-z]+foo$", PAD_80 + "bar", MatchStrategy.LITERAL),
        Arguments.of("end-suffix-reject-hit", "[a-z]+foo$", PAD_80 + "abcfoo", MatchStrategy.DFA),
        // End-anchored reverse-first DFA and LeadingExpansion
        Arguments.of("end-reverse-first-hit", "(?:foo|fax)$", PAD_80 + "foo", MatchStrategy.DFA),
        Arguments.of(
            "end-leading-exp-miss-end-cc",
            "(?i)\\s*(?:foo|fax)$",
            PAD_80 + "   bar",
            MatchStrategy.CHARACTER_CLASS),
        Arguments.of(
            "end-leading-exp-early-cand-miss-rev-dfa",
            "(?i)\\s*(?:foo|fax)$",
            "   foo" + PAD_80 + "   box",
            MatchStrategy.DFA),
        Arguments.of(
            "end-leading-exp-hit", "(?i)\\s*(?:foo|fax)$", PAD_80 + "   foo", MatchStrategy.DFA),
        // Start acceleration: Literal, FixedOffset, MultiLiteral, CharClass, LeadingExpansion
        Arguments.of("start-accel-literal-hit", "é[ab]+c", "zzzzéabczz", MatchStrategy.DFA),
        Arguments.of("start-accel-literal-miss-cand", "é[ab]+c", "zzzzzzzz", MatchStrategy.LITERAL),
        Arguments.of(
            "start-accel-literal-false-cand", "é[ab]+c", "zzzzézzzéabczz", MatchStrategy.DFA),
        Arguments.of(
            "start-accel-fixed-offset-hit",
            "[a-z]{3}foo[0-9]+",
            "123abcfoo42zz",
            MatchStrategy.DFA),
        Arguments.of(
            "start-accel-fixed-offset-miss",
            "[a-z]{3}foo[0-9]+",
            "1234567890",
            MatchStrategy.LITERAL),
        Arguments.of(
            "start-accel-multilit-hit",
            "(?:alpha|bravo)_[0-9]+",
            "zzzbravo_99zz",
            MatchStrategy.DFA),
        Arguments.of("start-accel-cc-hit", "[xy][ab]+c", "zzzzxabczz", MatchStrategy.DFA),
        Arguments.of(
            "start-accel-cc-miss-cand", "[xy][ab]+c", "zzzzzzzz", MatchStrategy.CHARACTER_CLASS),
        Arguments.of(
            "start-accel-leading-exp-hit", "\\s*# noqa", "code   # noqa", MatchStrategy.DFA),
        Arguments.of(
            "start-accel-leading-exp-miss",
            "\\s*# noqa",
            "code # without pragma",
            MatchStrategy.LITERAL),
        // General DFA sandwich
        Arguments.of("dfa-sandwich-hit", "a*[bc]d", "zzzzbdzz", MatchStrategy.DFA),
        Arguments.of("dfa-sandwich-miss", "a*[bc]d", "zzzzbzzz", MatchStrategy.CHARACTER_CLASS),
        Arguments.of(
            "shift-dfa-shape-in-find-hit", "[a-z]{2,4}[0-9]{2}", "abc12", MatchStrategy.DFA),
        Arguments.of(
            "shift-dfa-shape-in-find-miss", "[a-z]{2,4}[0-9]{2}", "abc1x", MatchStrategy.DFA),
        // OnePass anchored with and without capturing groups
        Arguments.of(
            "onepass-group-hit",
            "^GET ([^ ]+)",
            "GET /index.html HTTP/1.1",
            MatchStrategy.ONE_PASS),
        Arguments.of(
            "onepass-group-miss",
            "^GET ([^ ]+)",
            "POST /index.html HTTP/1.1",
            MatchStrategy.LITERAL),
        Arguments.of(
            "onepass-nogroup-hit",
            "^GET [^ ]+",
            "GET /index.html HTTP/1.1",
            MatchStrategy.ONE_PASS),
        Arguments.of(
            "onepass-nogroup-miss",
            "^GET [^ ]+",
            "POST /index.html HTTP/1.1",
            MatchStrategy.LITERAL),
        // Grapheme cluster NFA and nullable loop-register BitState
        Arguments.of("grapheme-hit", "\\Xz", "a\u0301z", MatchStrategy.NFA),
        Arguments.of("grapheme-miss", "\\Xz", "a\u0301y", MatchStrategy.CHARACTER_CLASS),
        Arguments.of("loop-reg-hit", "(a?)*b", "aaab", MatchStrategy.BIT_STATE),
        Arguments.of("loop-reg-miss", "(a?)*b", "aaac", MatchStrategy.CHARACTER_CLASS));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("stringAndUtf8FindParityScenarios")
  void stringAndUtf8MatcherFindSelectSameStrategies(
      String name, String regex, String input, MatchStrategy expectedBoundary) {
    Pattern pattern = Pattern.compile(regex);
    Utf8Input utf8 = Utf8Input.validated(input.getBytes(UTF_8));
    Pattern.setDiagnostics(diagnostics);

    diagnostics.operations.clear();
    boolean stringMatched = pattern.matcher(input).find();
    OperationDiagnostics stringDiag = diagnostics.operations.getLast();

    diagnostics.operations.clear();
    boolean utf8Matched = pattern.matcher(utf8).find();
    OperationDiagnostics utf8Diag = diagnostics.operations.getLast();

    diagnostics.operations.clear();
    boolean predicateMatched = pattern.asPredicate().test(input);
    OperationDiagnostics predicateDiag = diagnostics.operations.getLast();

    assertThat(utf8Matched).as("%s match result", name).isEqualTo(stringMatched);
    assertThat(predicateMatched).as("%s asPredicate result", name).isEqualTo(stringMatched);
    assertThat(stringDiag.boundaryStrategy())
        .as("%s string boundary", name)
        .isEqualTo(expectedBoundary);
    assertThat(utf8Diag.boundaryStrategy())
        .as("%s utf8 boundary", name)
        .isEqualTo(expectedBoundary);
    assertThat(predicateDiag.boundaryStrategy())
        .as("%s asPredicate boundary", name)
        .isEqualTo(expectedBoundary);
    assertThat(predicateDiag.captureMode())
        .as("%s asPredicate captureMode", name)
        .isEqualTo(CaptureMode.NONE);
    assertThat(utf8Diag.captureStrategy())
        .as("%s captureStrategy", name)
        .isEqualTo(stringDiag.captureStrategy());
    assertThat(utf8Diag.auxiliaryStrategies())
        .as("%s auxiliaryStrategies", name)
        .isEqualTo(stringDiag.auxiliaryStrategies());
    assertThat(utf8Diag.strategyDecisions())
        .as("%s strategyDecisions", name)
        .isEqualTo(stringDiag.strategyDecisions());

    List<StrategyParticipation> stringNonVerifyAux =
        stringDiag.auxiliaryStrategies().stream()
            .filter(p -> p.role() != StrategyRole.CANDIDATE_VERIFICATION)
            .toList();
    List<StrategyParticipation> predicateNonVerifyAux =
        predicateDiag.auxiliaryStrategies().stream()
            .filter(p -> p.role() != StrategyRole.CANDIDATE_VERIFICATION)
            .toList();
    assertThat(predicateNonVerifyAux)
        .as("%s asPredicate non-verification auxiliaryStrategies", name)
        .isEqualTo(stringNonVerifyAux);
  }

  static Stream<Arguments> stringAndUtf8MatchesAndLookingAtParityScenarios() {
    return Stream.of(
        Arguments.of(
            "literal-ascii-hit", "hello", "hello", MatchStrategy.LITERAL, MatchStrategy.LITERAL),
        Arguments.of(
            "literal-ascii-miss", "hello", "world", MatchStrategy.LITERAL, MatchStrategy.LITERAL),
        Arguments.of(
            "literal-utf8-hit", "café", "café", MatchStrategy.LITERAL, MatchStrategy.LITERAL),
        Arguments.of(
            "literal-utf8-miss", "café", "world", MatchStrategy.LITERAL, MatchStrategy.LITERAL),
        Arguments.of(
            "single-cc-ascii-hit",
            "[0-9]",
            "5",
            MatchStrategy.CHARACTER_CLASS,
            MatchStrategy.CHARACTER_CLASS),
        Arguments.of(
            "single-cc-ascii-miss",
            "[0-9]",
            "a",
            MatchStrategy.CHARACTER_CLASS,
            MatchStrategy.CHARACTER_CLASS),
        Arguments.of(
            "single-cc-unicode-hit",
            "\\p{L}",
            "é",
            MatchStrategy.CHARACTER_CLASS,
            MatchStrategy.CHARACTER_CLASS),
        Arguments.of(
            "keyword-alt-unanchored-hit",
            "(?i)\\b(?:foo|bar|baz)\\b",
            "Bar",
            MatchStrategy.DFA,
            MatchStrategy.DFA),
        Arguments.of(
            "keyword-alt-unanchored-miss",
            "(?i)\\b(?:foo|bar|baz)\\b",
            "Barred",
            MatchStrategy.DFA,
            MatchStrategy.DFA),
        Arguments.of(
            "shift-dfa-hit",
            "[a-z]{2,4}[0-9]{2}",
            "abc12",
            MatchStrategy.SHIFT_DFA,
            MatchStrategy.DFA),
        Arguments.of(
            "shift-dfa-miss",
            "[a-z]{2,4}[0-9]{2}",
            "abcde",
            MatchStrategy.SHIFT_DFA,
            MatchStrategy.DFA),
        Arguments.of(
            "onepass-group-hit",
            "^GET ([^ ]+)",
            "GET /café",
            MatchStrategy.ONE_PASS,
            MatchStrategy.ONE_PASS),
        Arguments.of(
            "onepass-group-miss",
            "^GET ([^ ]+)",
            "POST /café",
            MatchStrategy.ONE_PASS,
            MatchStrategy.ONE_PASS),
        Arguments.of("grapheme-hit", "\\Xz", "a\u0301z", MatchStrategy.NFA, MatchStrategy.NFA),
        Arguments.of(
            "loop-reg-hit", "(a?)*b", "aaab", MatchStrategy.BIT_STATE, MatchStrategy.BIT_STATE));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("stringAndUtf8MatchesAndLookingAtParityScenarios")
  void stringAndUtf8MatcherMatchesAndLookingAtSelectSameStrategies(
      String name,
      String regex,
      String input,
      MatchStrategy expectedMatchesBoundary,
      MatchStrategy expectedLookingAtBoundary) {
    Pattern pattern = Pattern.compile(regex);
    Utf8Input utf8 = Utf8Input.validated(input.getBytes(UTF_8));
    Pattern.setDiagnostics(diagnostics);

    diagnostics.operations.clear();
    boolean strMatches = pattern.matcher(input).matches();
    OperationDiagnostics strMatchesDiag = diagnostics.operations.getLast();

    diagnostics.operations.clear();
    boolean utf8Matches = pattern.matcher(utf8).matches();
    OperationDiagnostics utf8MatchesDiag = diagnostics.operations.getLast();

    assertThat(utf8Matches).as("%s matches result", name).isEqualTo(strMatches);
    assertThat(strMatchesDiag.boundaryStrategy())
        .as("%s string matches boundary", name)
        .isEqualTo(expectedMatchesBoundary);
    assertThat(utf8MatchesDiag.boundaryStrategy())
        .as("%s utf8 matches boundary", name)
        .isEqualTo(expectedMatchesBoundary);
    assertThat(utf8MatchesDiag.auxiliaryStrategies())
        .as("%s matches auxiliaryStrategies", name)
        .isEqualTo(strMatchesDiag.auxiliaryStrategies());

    diagnostics.operations.clear();
    boolean strLookingAt = pattern.matcher(input).lookingAt();
    OperationDiagnostics strLookingAtDiag = diagnostics.operations.getLast();

    diagnostics.operations.clear();
    boolean utf8LookingAt = pattern.matcher(utf8).lookingAt();
    OperationDiagnostics utf8LookingAtDiag = diagnostics.operations.getLast();

    assertThat(utf8LookingAt).as("%s lookingAt result", name).isEqualTo(strLookingAt);
    assertThat(strLookingAtDiag.boundaryStrategy())
        .as("%s string lookingAt boundary", name)
        .isEqualTo(expectedLookingAtBoundary);
    assertThat(utf8LookingAtDiag.boundaryStrategy())
        .as("%s utf8 lookingAt boundary", name)
        .isEqualTo(expectedLookingAtBoundary);
    assertThat(utf8LookingAtDiag.auxiliaryStrategies())
        .as("%s lookingAt auxiliaryStrategies", name)
        .isEqualTo(strLookingAtDiag.auxiliaryStrategies());
  }

  static Stream<Arguments> utf8MatcherAndPatternBooleanFindParityScenarios() {
    return Stream.concat(
        stringAndUtf8FindParityScenarios(),
        Stream.of(
            Arguments.of(
                "end-reverse-first-miss",
                "(?:foo|fax)$",
                PAD_80 + "boo",
                MatchStrategy.CHARACTER_CLASS)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("utf8MatcherAndPatternBooleanFindParityScenarios")
  void utf8MatcherFindAndPatternBooleanFindSelectSameBoundaryAndPrefilterStrategies(
      String name, String regex, String input, MatchStrategy expectedBoundary) {
    Pattern pattern = Pattern.compile(regex);
    Utf8Input utf8 = Utf8Input.validated(input.getBytes(UTF_8));
    Pattern.setDiagnostics(diagnostics);

    diagnostics.operations.clear();
    boolean matcherFound = pattern.matcher(utf8).find();
    OperationDiagnostics matcherDiag = diagnostics.operations.getLast();

    diagnostics.operations.clear();
    boolean booleanFound = pattern.find(utf8);
    OperationDiagnostics booleanDiag = diagnostics.operations.getLast();

    assertThat(booleanFound).as("%s boolean find result", name).isEqualTo(matcherFound);
    assertThat(matcherDiag.boundaryStrategy())
        .as("%s matcher boundary", name)
        .isEqualTo(expectedBoundary);
    assertThat(booleanDiag.boundaryStrategy())
        .as("%s boolean find boundary", name)
        .isEqualTo(expectedBoundary);
    assertThat(booleanDiag.captureMode())
        .as("%s boolean find captureMode", name)
        .isEqualTo(CaptureMode.NONE);

    // Filter out CANDIDATE_VERIFICATION when comparing auxiliary participations: Pattern.find(utf8)
    // only demands a boolean answer and intentionally skips bounds-finding verification/reverse-DFA
    // passes on unanchored DFA matches.
    List<StrategyParticipation> matcherNonVerifyAux =
        matcherDiag.auxiliaryStrategies().stream()
            .filter(p -> p.role() != StrategyRole.CANDIDATE_VERIFICATION)
            .toList();
    List<StrategyParticipation> booleanNonVerifyAux =
        booleanDiag.auxiliaryStrategies().stream()
            .filter(p -> p.role() != StrategyRole.CANDIDATE_VERIFICATION)
            .toList();
    assertThat(booleanNonVerifyAux)
        .as("%s non-verification auxiliaryStrategies", name)
        .isEqualTo(matcherNonVerifyAux);
  }

  static Stream<Arguments> findAndReplaceBoundaryParityScenarios() {
    return Stream.of(
        Arguments.of("literal-ascii-hit", "hello", "xxhelloyy", MatchStrategy.LITERAL),
        Arguments.of("literal-ascii-miss", "hello", "xxworldyy", MatchStrategy.LITERAL),
        Arguments.of("literal-utf8-hit", "café", "xxcaféyy", MatchStrategy.LITERAL),
        Arguments.of("literal-utf8-miss", "café", "xxworldyy", MatchStrategy.LITERAL),
        Arguments.of("foldcase-literal-hit", "(?i)hello", "xxHeLLoyy", MatchStrategy.LITERAL),
        Arguments.of("foldcase-literal-miss", "(?i)hello", "xxworldyy", MatchStrategy.LITERAL),
        Arguments.of("start-anchored-literal-hit", "^hello", "helloyy", MatchStrategy.LITERAL),
        Arguments.of("start-anchored-literal-miss", "^hello", "xxhelloyy", MatchStrategy.LITERAL),
        Arguments.of("end-anchored-literal-hit", "hello$", "xxhello", MatchStrategy.DFA),
        Arguments.of("end-anchored-literal-miss", "hello$", "xxhelloyy", MatchStrategy.LITERAL),
        Arguments.of("end-z-literal-hit", "hello\\z", "xxhello", MatchStrategy.DFA),
        Arguments.of("end-z-literal-miss", "hello\\z", "xxhelloyy", MatchStrategy.LITERAL),
        Arguments.of("both-anchored-literal-hit", "^hello$", "hello", MatchStrategy.ONE_PASS),
        Arguments.of("both-anchored-literal-miss", "^hello$", "helloyy", MatchStrategy.ONE_PASS),
        Arguments.of("single-cc-ascii-hit", "[0-9]", "abc5def", MatchStrategy.CHARACTER_CLASS),
        Arguments.of("single-cc-ascii-miss", "[0-9]", "abcdef", MatchStrategy.CHARACTER_CLASS),
        Arguments.of("single-cc-unicode-hit", "\\p{L}", "123é456", MatchStrategy.CHARACTER_CLASS),
        Arguments.of("single-cc-unicode-miss", "\\p{L}", "123456", MatchStrategy.CHARACTER_CLASS),
        Arguments.of(
            "single-cc-mixed-pair-hit",
            "[\\]\\uFF3D]",
            "abc\uFF3Ddef",
            MatchStrategy.CHARACTER_CLASS),
        Arguments.of(
            "single-cc-mixed-pair-miss", "[\\]\\uFF3D]", "abcdef", MatchStrategy.CHARACTER_CLASS),
        Arguments.of("start-anchored-cc-hit", "^[0-9]+ items", "42 items left", MatchStrategy.DFA),
        Arguments.of(
            "start-anchored-cc-miss-first",
            "^[0-9]+ items",
            "no items left",
            MatchStrategy.CHARACTER_CLASS),
        Arguments.of(
            "start-anchored-cc-miss-tail", "^[0-9]+ items", "42 things left", MatchStrategy.DFA),
        Arguments.of(
            "keyword-alt-hit", "(?i)\\b(?:foo|bar|baz)\\b", "xx Bar yy", MatchStrategy.KEYWORD),
        Arguments.of(
            "keyword-alt-miss", "(?i)\\b(?:foo|bar|baz)\\b", "xx Barred yy", MatchStrategy.KEYWORD),
        Arguments.of("start-prefix-reject-hit", "^foo[0-9]+", "foo123bar", MatchStrategy.DFA),
        Arguments.of(
            "start-prefix-reject-miss-prefix", "^foo[0-9]+", "bar123", MatchStrategy.LITERAL),
        Arguments.of("start-prefix-reject-miss-body", "^foo[0-9]+", "foobar", MatchStrategy.DFA),
        Arguments.of(
            "composite-prefilter-miss-end-cc",
            "foo.*bar.*[0-9]$",
            "foo only no bar",
            MatchStrategy.CHARACTER_CLASS),
        Arguments.of(
            "composite-prefilter-miss-literal",
            "foo.*bar.*[0-9]$",
            "foo only no 7",
            MatchStrategy.LITERAL),
        Arguments.of(
            "composite-prefilter-hit", "foo.*bar.*[0-9]$", "foo and bar 7", MatchStrategy.DFA),
        Arguments.of(
            "disjoint-literals-miss",
            "alpha.*beta",
            "alpha without the other",
            MatchStrategy.LITERAL),
        Arguments.of("disjoint-literals-hit", "alpha.*beta", "alpha then beta", MatchStrategy.DFA),
        Arguments.of("cc-prefilter-miss", "[xy]q?", "abcdefg", MatchStrategy.CHARACTER_CLASS),
        Arguments.of("cc-prefilter-hit", "[xy]q?", "abcxdef", MatchStrategy.DFA),
        Arguments.of("end-suffix-reject-miss", "[a-z]+foo$", PAD_80 + "bar", MatchStrategy.LITERAL),
        Arguments.of("end-suffix-reject-hit", "[a-z]+foo$", PAD_80 + "abcfoo", MatchStrategy.DFA),
        Arguments.of(
            "end-reverse-first-miss", "(?:foo|fax)$", PAD_80 + "boo", MatchStrategy.LITERAL),
        Arguments.of("end-reverse-first-hit", "(?:foo|fax)$", PAD_80 + "foo", MatchStrategy.DFA),
        Arguments.of(
            "end-z-leading-exp-miss-rev-dfa",
            "\\s*(?:foo|fax)\\z",
            "   foo" + PAD_1100 + "   box",
            MatchStrategy.DFA),
        Arguments.of(
            "end-z-leading-exp-hit-rev-dfa",
            "\\s*(?:foo|fax)\\z",
            PAD_1100 + "   foo",
            MatchStrategy.BIT_STATE),
        Arguments.of("start-accel-literal-hit", "é[ab]+c", "zzzzéabczz", MatchStrategy.DFA),
        Arguments.of("start-accel-literal-miss-cand", "é[ab]+c", "zzzzzzzz", MatchStrategy.LITERAL),
        Arguments.of(
            "start-accel-literal-false-cand", "é[ab]+c", "zzzzézzzéabczz", MatchStrategy.DFA),
        Arguments.of(
            "start-accel-fixed-offset-hit",
            "[a-z]{3}foo[0-9]+",
            "123abcfoo42zz",
            MatchStrategy.DFA),
        Arguments.of(
            "start-accel-fixed-offset-miss",
            "[a-z]{3}foo[0-9]+",
            "1234567890",
            MatchStrategy.LITERAL),
        Arguments.of(
            "start-accel-multilit-hit",
            "(?:alpha|bravo)_[0-9]+",
            "zzzbravo_99zz",
            MatchStrategy.DFA),
        Arguments.of("start-accel-cc-hit", "[xy][ab]+c", "zzzzxabczz", MatchStrategy.DFA),
        Arguments.of(
            "start-accel-cc-miss-cand", "[xy][ab]+c", "zzzzzzzz", MatchStrategy.CHARACTER_CLASS),
        Arguments.of(
            "start-accel-leading-exp-hit", "\\s*# noqa", "code   # noqa", MatchStrategy.DFA),
        Arguments.of(
            "start-accel-leading-exp-miss",
            "\\s*# noqa",
            "code # without pragma",
            MatchStrategy.LITERAL),
        Arguments.of("dfa-sandwich-hit", "a*[bc]d", "zzzzbdzz", MatchStrategy.DFA),
        Arguments.of("dfa-sandwich-miss", "a*[bc]d", "zzzzbzzz", MatchStrategy.CHARACTER_CLASS),
        Arguments.of("grapheme-hit", "\\Xz", "a\u0301z", MatchStrategy.NFA),
        Arguments.of("grapheme-miss", "\\Xz", "a\u0301y", MatchStrategy.CHARACTER_CLASS),
        Arguments.of("loop-reg-hit", "(a?)*b", "aaab", MatchStrategy.BIT_STATE),
        Arguments.of("loop-reg-miss", "(a?)*b", "aaac", MatchStrategy.CHARACTER_CLASS));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("findAndReplaceBoundaryParityScenarios")
  void matcherFindAndReplaceSelectSameBoundaryStrategy(
      String name, String regex, String input, MatchStrategy expectedBoundary) {
    Pattern pattern = Pattern.compile(regex);
    Pattern.setDiagnostics(diagnostics);

    diagnostics.operations.clear();
    boolean found = pattern.matcher(input).find();
    OperationDiagnostics findDiag = diagnostics.operations.getLast();

    diagnostics.operations.clear();
    String repFirst = pattern.matcher(input).replaceFirst("X");
    OperationDiagnostics repFirstDiag = diagnostics.operations.getLast();

    diagnostics.operations.clear();
    String repAll = pattern.matcher(input).replaceAll("X");
    OperationDiagnostics repAllDiag = diagnostics.operations.getLast();

    assertThat(repFirstDiag.outcome())
        .as("%s replaceFirst outcome", name)
        .isEqualTo(findDiag.outcome());
    assertThat(repAllDiag.outcome())
        .as("%s replaceAll outcome", name)
        .isEqualTo(findDiag.outcome());
    assertThat(!repFirst.equals(input)).as("%s replaceFirst changed input", name).isEqualTo(found);
    assertThat(!repAll.equals(input)).as("%s replaceAll changed input", name).isEqualTo(found);
    assertThat(findDiag.boundaryStrategy())
        .as("%s find boundary", name)
        .isEqualTo(expectedBoundary);
    assertThat(repFirstDiag.boundaryStrategy())
        .as("%s replaceFirst boundary", name)
        .isEqualTo(expectedBoundary);
    assertThat(repAllDiag.boundaryStrategy())
        .as("%s replaceAll boundary", name)
        .isEqualTo(expectedBoundary);

    // Every START_ACCELERATION strategy that participates in find() must also participate in
    // replaceFirst() and replaceAll().
    Set<MatchStrategy> findStartAccel = rolesFor(findDiag, StrategyRole.START_ACCELERATION);
    assertThat(rolesFor(repFirstDiag, StrategyRole.START_ACCELERATION))
        .as("%s replaceFirst start acceleration", name)
        .isEqualTo(findStartAccel);
    assertThat(rolesFor(repAllDiag, StrategyRole.START_ACCELERATION))
        .as("%s replaceAll start acceleration", name)
        .isEqualTo(findStartAccel);
  }

  @Test
  void enginePathOptionsDfaFalseSelectsSameStrategyAcrossFindAndReplace() {
    Pattern noDfa = Pattern.compile("a*[bc]d", 0, EnginePathOptions.builder().dfa(false).build());
    Pattern.setDiagnostics(diagnostics);

    for (String input : List.of("zzzzbdzz", "zzzzbzzz")) {
      MatchStrategy expected =
          input.equals("zzzzbdzz") ? MatchStrategy.BIT_STATE : MatchStrategy.CHARACTER_CLASS;
      assertThat(runFind(noDfa, input).boundaryStrategy()).isEqualTo(expected);
      assertThat(runUtf8Find(noDfa, input).boundaryStrategy()).isEqualTo(expected);
      assertThat(runPatternBooleanFind(noDfa, input).boundaryStrategy()).isEqualTo(expected);
      assertThat(runPredicate(noDfa, input).boundaryStrategy()).isEqualTo(expected);
      assertThat(runReplaceFirst(noDfa, input).boundaryStrategy()).isEqualTo(expected);
      assertThat(runReplaceAll(noDfa, input).boundaryStrategy()).isEqualTo(expected);
    }
  }

  /**
   * Codifies current strategy divergences between {@code String} and {@link Utf8Input} operations
   * so that any change to UTF-8 parity is immediately visible in tests.
   */
  @Test
  void currentStringVsUtf8StrategyDivergences() {
    Pattern.setDiagnostics(diagnostics);

    // 1. Case-insensitive literal: String uses LiteralPreparedRunner; Utf8Input uses start-accel +
    // DFA on find(hit) and ShiftDFA on matches().
    Pattern foldCaseLit = Pattern.compile("(?i)hello");
    assertThat(runFind(foldCaseLit, "xxHeLLoyy").boundaryStrategy())
        .isEqualTo(MatchStrategy.LITERAL);
    assertThat(runUtf8Find(foldCaseLit, "xxHeLLoyy").boundaryStrategy())
        .isEqualTo(MatchStrategy.DFA);
    assertThat(runMatches(foldCaseLit, "xxHeLLoyy").boundaryStrategy())
        .isEqualTo(MatchStrategy.LITERAL);
    assertThat(runUtf8Matches(foldCaseLit, "xxHeLLoyy").boundaryStrategy())
        .isEqualTo(MatchStrategy.SHIFT_DFA);

    // 2. LiteralAlternationPreparedRunner: wired for String find(), not yet wired for Utf8Input.
    Pattern litAlt = Pattern.compile("foo|bar|baz");
    assertThat(runFind(litAlt, "xxbaryy").boundaryStrategy())
        .isEqualTo(MatchStrategy.LITERAL_ALTERNATION);
    assertThat(runUtf8Find(litAlt, "xxbaryy").boundaryStrategy()).isEqualTo(MatchStrategy.DFA);

    // 3. SingleCharClassPreparedRunner.matches() on quantified char class ([a-z]+): String uses
    // CHARACTER_CLASS fast path on full match; Utf8Input falls through to DFA.
    Pattern quantCc = Pattern.compile("[a-z]+");
    assertThat(runMatches(quantCc, "abcdef").boundaryStrategy())
        .isEqualTo(MatchStrategy.CHARACTER_CLASS);
    assertThat(runUtf8Matches(quantCc, "abcdef").boundaryStrategy()).isEqualTo(MatchStrategy.DFA);

    // 4. RejectPrefilter.DisjointLiterals in find() and matchesCore rejectPrefilter on Utf8Input:
    // On (?:foo|fax)$ with input ending in 'o' ("...boo"), EndAnchoredCharClass([ox]) passes;
    // String rejects via DisjointLiterals(["foo", "fax"]) (LITERAL REJECT_PREFILTER), whereas
    // Utf8Input skips DisjointLiterals and rejects via CharClass([f]) START_ACCELERATION.
    Pattern disjointEnd = Pattern.compile("(?:foo|fax)$");
    OperationDiagnostics strDisjoint = runFind(disjointEnd, PAD_80 + "boo");
    OperationDiagnostics utf8Disjoint = runUtf8Find(disjointEnd, PAD_80 + "boo");
    assertThat(strDisjoint.boundaryStrategy()).isEqualTo(MatchStrategy.LITERAL);
    assertThat(rolesFor(strDisjoint, StrategyRole.REJECT_PREFILTER))
        .contains(MatchStrategy.LITERAL);
    assertThat(utf8Disjoint.boundaryStrategy()).isEqualTo(MatchStrategy.CHARACTER_CLASS);
    assertThat(rolesFor(utf8Disjoint, StrategyRole.START_ACCELERATION))
        .contains(MatchStrategy.CHARACTER_CLASS);

    // In matchesCore, rejectPrefilter only runs when text != null, so Utf8Matcher.matches() misses
    // whole-input prefilter rejection.
    Pattern suffixPattern = Pattern.compile("[a-z]+foo$");
    assertThat(runMatches(suffixPattern, PAD_80 + "bar").boundaryStrategy())
        .isEqualTo(MatchStrategy.LITERAL);
    assertThat(runUtf8Matches(suffixPattern, PAD_80 + "bar").boundaryStrategy())
        .isEqualTo(MatchStrategy.DFA);
  }

  /**
   * Verifies that {@link Pattern#find(Utf8Input)} and {@link Pattern#asPredicate()} dispatch
   * through {@code PreparedMatchRunner.contains} with {@link CaptureMode#NONE}, using {@code
   * SingleCharClassPreparedRunner}, {@code OnePassAnchoredPreparedRunner}, and start-anchored
   * prefix rejection directly without allocating a matcher or running the forward DFA.
   */
  @Test
  void booleanContainsSelectsPreparedRunnersAndPrefilterRoles() {
    Pattern.setDiagnostics(diagnostics);

    // 1. SingleCharClassPreparedRunner ([0-9], \p{L}): uses CHARACTER_CLASS without DFA.
    Pattern singleCc = Pattern.compile("[0-9]");
    for (OperationDiagnostics diag :
        List.of(runPatternBooleanFind(singleCc, "abc5def"), runPredicate(singleCc, "abc5def"))) {
      assertThat(diag.boundaryStrategy()).isEqualTo(MatchStrategy.CHARACTER_CLASS);
      assertThat(diag.forwardDfaSearchCount()).isZero();
      assertThat(diag.captureMode()).isEqualTo(CaptureMode.NONE);
    }

    // 2. Start-anchored OnePass (^GET ([^ ]+)): uses ONE_PASS with CaptureMode.NONE.
    Pattern onePass = Pattern.compile("^GET ([^ ]+)");
    for (OperationDiagnostics diag :
        List.of(
            runPatternBooleanFind(onePass, "GET /index.html"),
            runPredicate(onePass, "GET /index.html"))) {
      assertThat(diag.boundaryStrategy()).isEqualTo(MatchStrategy.ONE_PASS);
      assertThat(diag.forwardDfaSearchCount()).isZero();
      assertThat(diag.captureMode()).isEqualTo(CaptureMode.NONE);
      assertThat(diag.captureStrategy()).isEqualTo(MatchStrategy.NONE);
    }

    // 3. Start-anchored literal reject (^hello on "xxhelloyy"): records REJECT_PREFILTER=LITERAL.
    Pattern startAnchoredLit = Pattern.compile("^hello");
    for (OperationDiagnostics diag :
        List.of(
            runPatternBooleanFind(startAnchoredLit, "xxhelloyy"),
            runPredicate(startAnchoredLit, "xxhelloyy"))) {
      assertThat(diag.boundaryStrategy()).isEqualTo(MatchStrategy.LITERAL);
      assertThat(rolesFor(diag, StrategyRole.REJECT_PREFILTER)).contains(MatchStrategy.LITERAL);
      assertThat(diag.captureMode()).isEqualTo(CaptureMode.NONE);
    }
  }

  /**
   * Codifies current strategy divergences between {@link Matcher#find()} and {@link
   * Matcher#replaceFirst(String)} / {@link Matcher#replaceAll(String)}.
   */
  @Test
  void currentFindVsReplaceStrategyDivergences() {
    Pattern.setDiagnostics(diagnostics);

    // 1. Start-anchored OnePass without end anchor (^GET ([^ ]+)): find() uses ONE_PASS;
    // replaceFirst/replaceAll use replaceDfaOptimized (DFA).
    Pattern onePass = Pattern.compile("^GET ([^ ]+)");
    assertThat(runFind(onePass, "GET /index.html").boundaryStrategy())
        .isEqualTo(MatchStrategy.ONE_PASS);
    assertThat(runReplaceFirst(onePass, "GET /index.html").boundaryStrategy())
        .isEqualTo(MatchStrategy.DFA);

    // 2. LiteralAlternation (foo|bar|baz): find() uses LITERAL_ALTERNATION; replace uses DFA.
    Pattern litAlt = Pattern.compile("foo|bar|baz");
    assertThat(runFind(litAlt, "xxbaryy").boundaryStrategy())
        .isEqualTo(MatchStrategy.LITERAL_ALTERNATION);
    assertThat(runReplaceFirst(litAlt, "xxbaryy").boundaryStrategy()).isEqualTo(MatchStrategy.DFA);

    // 3. Quantified char class ([0-9]+): replace has a dedicated charClassReplaceFastPath
    // (CHARACTER_CLASS), whereas find() uses the DFA sandwich (DFA).
    Pattern quantCc = Pattern.compile("[0-9]+");
    assertThat(runFind(quantCc, "abc123def").boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
    assertThat(runReplaceAll(quantCc, "abc123def").boundaryStrategy())
        .isEqualTo(MatchStrategy.CHARACTER_CLASS);
  }

  private OperationDiagnostics runFind(Pattern pattern, String input) {
    diagnostics.operations.clear();
    pattern.matcher(input).find();
    return diagnostics.operations.getLast();
  }

  private OperationDiagnostics runUtf8Find(Pattern pattern, String input) {
    diagnostics.operations.clear();
    pattern.matcher(Utf8Input.validated(input.getBytes(UTF_8))).find();
    return diagnostics.operations.getLast();
  }

  private OperationDiagnostics runPatternBooleanFind(Pattern pattern, String input) {
    diagnostics.operations.clear();
    pattern.find(Utf8Input.validated(input.getBytes(UTF_8)));
    return diagnostics.operations.getLast();
  }

  private OperationDiagnostics runPredicate(Pattern pattern, String input) {
    diagnostics.operations.clear();
    boolean matched = pattern.asPredicate().test(input);
    OperationDiagnostics diag = diagnostics.operations.getLast();
    assertThat(diag.outcome()).isEqualTo(matched ? MatchOutcome.MATCH : MatchOutcome.NO_MATCH);
    return diag;
  }

  private OperationDiagnostics runMatches(Pattern pattern, String input) {
    diagnostics.operations.clear();
    pattern.matcher(input).matches();
    return diagnostics.operations.getLast();
  }

  private OperationDiagnostics runUtf8Matches(Pattern pattern, String input) {
    diagnostics.operations.clear();
    pattern.matcher(Utf8Input.validated(input.getBytes(UTF_8))).matches();
    return diagnostics.operations.getLast();
  }

  private OperationDiagnostics runReplaceFirst(Pattern pattern, String input) {
    diagnostics.operations.clear();
    pattern.matcher(input).replaceFirst("X");
    return diagnostics.operations.getLast();
  }

  private OperationDiagnostics runReplaceAll(Pattern pattern, String input) {
    diagnostics.operations.clear();
    pattern.matcher(input).replaceAll("X");
    return diagnostics.operations.getLast();
  }

  private static Set<MatchStrategy> rolesFor(OperationDiagnostics diag, StrategyRole role) {
    return diag.auxiliaryStrategies().stream()
        .filter(p -> p.role() == role)
        .map(StrategyParticipation::strategy)
        .collect(Collectors.toSet());
  }

  private static final class RecordingDiagnostics extends SafeReMatchDiagnostics {
    final List<OperationDiagnostics> operations = new ArrayList<>();

    @Override
    public void onOperationCompleted(OperationDiagnostics event) {
      operations.add(event);
    }
  }
}
