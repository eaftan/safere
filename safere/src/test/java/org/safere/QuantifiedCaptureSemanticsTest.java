// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Differential coverage for JDK-visible captures inside quantified expressions. */
@DisabledForCrosscheck("differential test already compares SafeRE with java.util.regex")
class QuantifiedCaptureSemanticsTest {
  private static final Duration CAPTURE_OBSERVATION_TIMEOUT = Duration.ofSeconds(5);

  private static Stream<Arguments> quantifiedCaptureCases() {
    return Stream.of(
        Arguments.of("(?:(a){1,}){2}", "aaa"),
        Arguments.of("(?:(a){1,}){2,3}", "aaaaa"),
        Arguments.of("(?:(a){0,2})*", "aaa"),
        Arguments.of("(?:(a){0,2})*?", "aaa"),
        Arguments.of("(?:(?:(a){0,2})*?)", "aaa"),
        Arguments.of("(?:(a){1,2})*", "aaa"),
        Arguments.of("(?:(a){1,2})*?", "aaa"),
        Arguments.of("(?:(a){1,2})+?", "aaa"),
        Arguments.of("(?:(a){1,2}){2,}?", "aaa"),
        Arguments.of("[x](?:(a){1,2})*", "xaaa"),
        Arguments.of("(?:(a|aa){1,2})*", "aaa"),
        Arguments.of("(?:(a|aa){1,}){2}", "aaaa"),
        Arguments.of("(?:(a|aa)+){2}", "aaaa"),
        Arguments.of("(?:(a|aa)+){2}", "aaa"),
        Arguments.of("(?:(a){1,2}){2}", "aaa"),
        Arguments.of("((a)+){2}", "aaa"),
        Arguments.of("((a|aa)+){2}", "aaaa"),
        Arguments.of("(?:((a))+){2}", "aaa"),
        Arguments.of("(?:((a)){1,}){2}", "aaa"),
        Arguments.of("(?:((a)){0,2})*", "aaa"),
        Arguments.of("(?:(a)|(b))*", "abba"),
        Arguments.of("(?:(a)?b)*", "abab"),
        Arguments.of("(?:(a){1,2}?)*", "aaa"),
        Arguments.of("((ab)?)*", "abab"),
        Arguments.of("((a?))*", "aa"),
        Arguments.of(
            "(.*)+/([a-zA-Z]+)/([^/]+)", "projects/123/locations/test-location/foo/bar"),
        Arguments.of("(.?)+/([a-z]+)/([^/]+)", "abc/foo/bar"),
        Arguments.of("(.?)+([a-z]+)", "abc"),
        Arguments.of("x(?:(a){1,2})*y", "xaaay"),
        Arguments.of("x(?:(a){1,}){2}y", "xaaay"));
  }

  private static Stream<Arguments> failedStartLeakageCases() {
    return Stream.of(
        Arguments.of("(?:(a){1})*$", "ab"),
        Arguments.of("(?:(a){2})*$", "aab"),
        Arguments.of("(?:(a){2})*$", "aaab"));
  }

  private static Stream<Arguments> lazyNullableGroupZeroCases() {
    return Stream.of(
        Arguments.of("(?:(?:(a){0,2})*?)", "a"),
        Arguments.of("(?:(?:(a){0,2})*?)", "aa"),
        Arguments.of("(?:(?:(a){0,2})*?)", "ab"),
        Arguments.of("(?:(?:(a){0,2})*?)", "aaa"));
  }

  private static Stream<Arguments> generatedQuantifiedCaptureCases() {
    List<GeneratedCase> cases = new ArrayList<>();
    addGeneratedCases(cases, "simple repeated capture", "(a)", List.of("", "a", "aa", "aaa"));
    addGeneratedCases(cases, "nullable repeated capture", "(a?)", List.of("", "a", "aa"));
    addGeneratedCases(cases, "optional repeated capture", "(a)?", List.of("", "a", "aa"));
    addGeneratedCases(
        cases, "alternating repeated capture", "(a|aa)", List.of("", "a", "aa", "aaa"));
    addGeneratedCases(cases, "nested repeated capture", "((a))", List.of("", "a", "aa", "aaa"));
    addGeneratedCases(cases, "suffix repeated capture", "(a)b", List.of("", "ab", "abab"));
    addGeneratedCases(
        cases, "optional-branch repeated capture", "(a)?b", List.of("", "b", "ab", "abab"));
    addGeneratedCases(
        cases, "empty-alternative repeated capture", "(?:|(a))", List.of("", "a", "aa"));

    cases.add(new GeneratedCase("named repeated capture", "(?:(?<word>a)){1,2}", "aa", "word"));
    cases.add(
        new GeneratedCase("named nullable repeated capture", "(?:(?<word>a)?){1,2}", "a",
            "word"));
    cases.add(
        new GeneratedCase("named alternation repeated capture", "(?:(?<word>a|aa)){1,2}", "aaa",
            "word"));

    return cases.stream().map(Arguments::of);
  }

  private static void addGeneratedCases(
      List<GeneratedCase> cases, String family, String atom, List<String> inputs) {
    List<String> quantifiers = List.of("*", "+", "?", "{0,2}", "{1,2}", "*?", "+?", "??",
        "{0,2}?", "{1,2}?");
    for (String quantifier : quantifiers) {
      String regex = "(?:" + atom + ")" + quantifier;
      for (String input : inputs) {
        cases.add(new GeneratedCase(family + " " + quantifier, regex, input, null));
        cases.add(new GeneratedCase(
            family + " " + quantifier + " with suffix", regex + "c", input + "c", null));
        cases.add(new GeneratedCase(
            family + " " + quantifier + " in context", "x" + regex + "y", "x" + input + "y",
            null));
      }
    }
  }

  @ParameterizedTest(name = "[{index}] find captures for /{0}/ on \"{1}\"")
  @MethodSource("quantifiedCaptureCases")
  @DisplayName("find() exposes quantified captures like java.util.regex")
  void findCapturesMatchJdk(String regex, String input) {
    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regex).matcher(input);
    Matcher safere = Pattern.compile(regex).matcher(input);

    boolean jdkMatched = jdk.find();
    assertThat(safere.find()).isEqualTo(jdkMatched);
    assertGroupsMatch(regex, input, jdkMatched, jdk, safere);
  }

  @ParameterizedTest(name = "[{index}] matches captures for /{0}/ on \"{1}\"")
  @MethodSource("quantifiedCaptureCases")
  @DisplayName("matches() exposes quantified captures like java.util.regex")
  void matchesCapturesMatchJdk(String regex, String input) {
    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regex).matcher(input);
    Matcher safere = Pattern.compile(regex).matcher(input);

    boolean jdkMatched = jdk.matches();
    assertThat(safere.matches()).isEqualTo(jdkMatched);
    assertGroupsMatch(regex, input, jdkMatched, jdk, safere);
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("generatedQuantifiedCaptureCases")
  @DisplayName("generated quantified-capture cases match java.util.regex")
  void generatedQuantifiedCaptureCasesMatchJdk(GeneratedCase testCase) {
    assertOperationTraceMatchesJdk(testCase);
    assertReplacementTraceMatchesJdk(testCase);
  }

  @ParameterizedTest(name = "[{index}] replacement APIs for /{0}/ on \"{1}\"")
  @MethodSource("quantifiedCaptureCases")
  @DisplayName("replacement APIs consume quantified captures like java.util.regex")
  void replacementApisMatchJdk(String regex, String input) {
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);
    Pattern saferePattern = Pattern.compile(regex);

    assertThat(saferePattern.matcher(input).replaceAll("[$1]"))
        .as("replaceAll(String) for /%s/ on %s", regex, input)
        .isEqualTo(jdkPattern.matcher(input).replaceAll("[$1]"));

    assertThat(saferePattern.matcher(input).replaceAll(match -> "[" + match.group(1) + "]"))
        .as("replaceAll(Function) for /%s/ on %s", regex, input)
        .isEqualTo(jdkPattern.matcher(input).replaceAll(match -> "[" + match.group(1) + "]"));

    assertThat(appendReplacementResult(saferePattern.matcher(input)))
        .as("appendReplacement for /%s/ on %s", regex, input)
        .isEqualTo(appendReplacementResult(jdkPattern.matcher(input)));
  }

  @Test
  @DisplayName("observing retained quantified captures does not enumerate repeat partitions")
  void observingRetainedQuantifiedCapturesDoesNotEnumerateRepeatPartitions() {
    String prefix = "b".repeat(12_000);
    String suffix = "a".repeat(100);
    String input = prefix + "x" + suffix + "y";

    assertTimeoutPreemptively(
        CAPTURE_OBSERVATION_TIMEOUT,
        () -> {
          Matcher matcher = Pattern.compile(".*x(?:(a){1,}){2}y").matcher(input);

          assertThat(matcher.find()).isTrue();
          assertThat(matcher.group(1)).isEqualTo("a");
          assertThat(matcher.start(1)).isEqualTo(prefix.length() + suffix.length() - 1);
          assertThat(matcher.end(1)).isEqualTo(prefix.length() + suffix.length());
        });
  }

  @ParameterizedTest(name = "[{index}] lazy nullable group 0 for /{0}/ on \"{1}\"")
  @MethodSource("lazyNullableGroupZeroCases")
  @DisplayName("capture-retention lowering preserves lazy nullable group zero")
  void captureRetentionLoweringPreservesLazyNullableGroupZero(String regex, String input) {
    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regex).matcher(input);
    Matcher safere = Pattern.compile(regex).matcher(input);

    boolean jdkMatched = jdk.find();
    assertThat(safere.find()).isEqualTo(jdkMatched);
    assertGroupsMatch(regex, input, jdkMatched, jdk, safere);
  }

  @ParameterizedTest(name = "[{index}] #52 divergence for /{0}/ on \"{1}\"")
  @MethodSource("failedStartLeakageCases")
  @DisplayName("failed starting-position capture leakage remains an intentional divergence")
  void failedStartCaptureLeakageDoesNotMatchJdk(String regex, String input) {
    Matcher safere = Pattern.compile(regex).matcher(input);

    assertThat(safere.find()).isTrue();
    assertThat(safere.group(1)).isNull();
    assertThat(safere.start(1)).isEqualTo(-1);
    assertThat(safere.end(1)).isEqualTo(-1);
  }

  private static void assertGroupsMatch(
      String regex, String input, boolean matched, java.util.regex.Matcher jdk, Matcher safere) {
    if (!matched) {
      return;
    }
    assertThat(safere.groupCount()).isEqualTo(jdk.groupCount());
    for (int group = 0; group <= jdk.groupCount(); group++) {
      assertThat(safere.group(group))
          .as("group(%d) for /%s/ on %s", group, regex, input)
          .isEqualTo(jdk.group(group));
      assertThat(safere.start(group))
          .as("start(%d) for /%s/ on %s", group, regex, input)
          .isEqualTo(jdk.start(group));
      assertThat(safere.end(group))
          .as("end(%d) for /%s/ on %s", group, regex, input)
          .isEqualTo(jdk.end(group));
    }
  }

  private static void assertOperationTraceMatchesJdk(GeneratedCase testCase) {
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(testCase.regex());
    Pattern saferePattern = Pattern.compile(testCase.regex());

    assertSingleOperationMatchesJdk(testCase, jdkPattern, saferePattern, Operation.FIND);
    assertSingleOperationMatchesJdk(testCase, jdkPattern, saferePattern, Operation.MATCHES);
    assertSingleOperationMatchesJdk(testCase, jdkPattern, saferePattern, Operation.LOOKING_AT);
    assertFindSequenceMatchesJdk(testCase, jdkPattern, saferePattern);
  }

  private static void assertSingleOperationMatchesJdk(
      GeneratedCase testCase,
      java.util.regex.Pattern jdkPattern,
      Pattern saferePattern,
      Operation operation) {
    java.util.regex.Matcher jdk = jdkPattern.matcher(testCase.input());
    Matcher safere = saferePattern.matcher(testCase.input());

    boolean jdkMatched =
        switch (operation) {
          case FIND -> jdk.find();
          case MATCHES -> jdk.matches();
          case LOOKING_AT -> jdk.lookingAt();
        };
    boolean safereMatched =
        switch (operation) {
          case FIND -> safere.find();
          case MATCHES -> safere.matches();
          case LOOKING_AT -> safere.lookingAt();
        };

    assertThat(safereMatched)
        .as("%s result for %s", operation, testCase)
        .isEqualTo(jdkMatched);
    assertGroupsMatch(testCase.regex(), testCase.input(), jdkMatched, jdk, safere);
    if (jdkMatched) {
      assertMatchResultMatchesJdk(testCase, jdk.toMatchResult(), safere.toMatchResult());
    }
  }

  private static void assertFindSequenceMatchesJdk(
      GeneratedCase testCase, java.util.regex.Pattern jdkPattern, Pattern saferePattern) {
    java.util.regex.Matcher jdk = jdkPattern.matcher(testCase.input());
    Matcher safere = saferePattern.matcher(testCase.input());

    int matchIndex = 0;
    while (true) {
      boolean jdkMatched = jdk.find();
      boolean safereMatched = safere.find();
      assertThat(safereMatched)
          .as("find sequence result %d for %s", matchIndex, testCase)
          .isEqualTo(jdkMatched);
      assertGroupsMatch(testCase.regex(), testCase.input(), jdkMatched, jdk, safere);
      if (!jdkMatched) {
        return;
      }
      matchIndex++;
    }
  }

  private static void assertMatchResultMatchesJdk(
      GeneratedCase testCase, java.util.regex.MatchResult jdk, MatchResult safere) {
    assertThat(safere.groupCount()).isEqualTo(jdk.groupCount());
    for (int group = 0; group <= jdk.groupCount(); group++) {
      assertThat(safere.group(group))
          .as("toMatchResult group(%d) for %s", group, testCase)
          .isEqualTo(jdk.group(group));
      assertThat(safere.start(group))
          .as("toMatchResult start(%d) for %s", group, testCase)
          .isEqualTo(jdk.start(group));
      assertThat(safere.end(group))
          .as("toMatchResult end(%d) for %s", group, testCase)
          .isEqualTo(jdk.end(group));
    }
  }

  private static void assertReplacementTraceMatchesJdk(GeneratedCase testCase) {
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(testCase.regex());
    Pattern saferePattern = Pattern.compile(testCase.regex());
    String numberedReplacement = "[$1]";

    assertThat(saferePattern.matcher(testCase.input()).replaceAll(numberedReplacement))
        .as("generated replaceAll(String) for %s", testCase)
        .isEqualTo(jdkPattern.matcher(testCase.input()).replaceAll(numberedReplacement));
    assertThat(saferePattern.matcher(testCase.input()).replaceFirst(numberedReplacement))
        .as("generated replaceFirst(String) for %s", testCase)
        .isEqualTo(jdkPattern.matcher(testCase.input()).replaceFirst(numberedReplacement));
    assertThat(appendReplacementResult(saferePattern.matcher(testCase.input())))
        .as("generated appendReplacement for %s", testCase)
        .isEqualTo(appendReplacementResult(jdkPattern.matcher(testCase.input())));
    assertThat(saferePattern.matcher(testCase.input()).replaceAll(match -> "[" + match.group(1)
        + "]"))
        .as("generated replaceAll(Function) for %s", testCase)
        .isEqualTo(jdkPattern.matcher(testCase.input()).replaceAll(match -> "[" + match.group(1)
            + "]"));

    if (testCase.namedGroup() != null) {
      String namedReplacement = "[${" + testCase.namedGroup() + "}]";
      assertThat(saferePattern.matcher(testCase.input()).replaceAll(namedReplacement))
          .as("generated named replaceAll(String) for %s", testCase)
          .isEqualTo(jdkPattern.matcher(testCase.input()).replaceAll(namedReplacement));
    }
  }

  private static String appendReplacementResult(Matcher matcher) {
    StringBuffer result = new StringBuffer();
    while (matcher.find()) {
      matcher.appendReplacement(result, "[$1]");
    }
    matcher.appendTail(result);
    return result.toString();
  }

  private static String appendReplacementResult(java.util.regex.Matcher matcher) {
    StringBuffer result = new StringBuffer();
    while (matcher.find()) {
      matcher.appendReplacement(result, "[$1]");
    }
    matcher.appendTail(result);
    return result.toString();
  }

  private enum Operation {
    FIND,
    MATCHES,
    LOOKING_AT
  }

  private record GeneratedCase(String family, String regex, String input, String namedGroup) {
    @Override
    public String toString() {
      return family + " /" + regex + "/ on \"" + input + "\"";
    }
  }
}
