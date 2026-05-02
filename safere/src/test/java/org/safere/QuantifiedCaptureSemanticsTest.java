// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
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
        Arguments.of("(?:(a){1,2})*", "aaa"),
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
}
