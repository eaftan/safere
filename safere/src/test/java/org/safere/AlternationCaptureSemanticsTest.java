// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.MatchResult;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Differential coverage for JDK-visible captures across alternation branches. */
@DisabledForCrosscheck("differential test already compares SafeRE with java.util.regex")
class AlternationCaptureSemanticsTest {
  private static Stream<Arguments> alternationCaptureCases() {
    return Stream.of(
        Arguments.of("()|\\W+", 0, " "),
        Arguments.of("(a)|b", 0, "b"),
        Arguments.of("b|(a)", 0, "b"),
        Arguments.of("(|a)(b)", 0, "ab"),
        Arguments.of("a(|b)|ac", 0, "ac"),
        Arguments.of("(?:|(a))b", 0, "b"),
        Arguments.of("(?:a|(b)|())", 0, "a"),
        Arguments.of("(?:(a)|^)", 0, ""),
        Arguments.of(
            "|#\\^@G\uE000\\^@\\^A\\^@\\b(..|^)|"
                + "k\\^N\uFBA1aaaaaaaaa0aaaaaaa\\^@\\^@\\^@m",
            Pattern.CASE_INSENSITIVE | Pattern.COMMENTS, ""));
  }

  @ParameterizedTest(name = "[{index}] /{0}/ flags={1} on \"{2}\"")
  @MethodSource("alternationCaptureCases")
  @DisplayName("compiled alternations preserve JDK-visible capture group count")
  void compiledAlternationsPreserveCaptureGroupCount(String regex, int flags, String input) {
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex, flags);
    Pattern saferePattern = Pattern.compile(regex, flags);

    assertThat(saferePattern.matcher(input).groupCount())
        .as("groupCount before match for /%s/ on %s", regex, input)
        .isEqualTo(jdkPattern.matcher(input).groupCount());
  }

  @ParameterizedTest(name = "[{index}] /{0}/ flags={1} on \"{2}\"")
  @MethodSource("alternationCaptureCases")
  @DisplayName("find() exposes alternation captures like java.util.regex")
  void findExposesAlternationCapturesLikeJdk(String regex, int flags, String input) {
    assertSingleOperationMatchesJdk(regex, flags, input, Operation.FIND);
  }

  @ParameterizedTest(name = "[{index}] /{0}/ flags={1} on \"{2}\"")
  @MethodSource("alternationCaptureCases")
  @DisplayName("lookingAt() exposes alternation captures like java.util.regex")
  void lookingAtExposesAlternationCapturesLikeJdk(String regex, int flags, String input) {
    assertSingleOperationMatchesJdk(regex, flags, input, Operation.LOOKING_AT);
  }

  @ParameterizedTest(name = "[{index}] /{0}/ flags={1} on \"{2}\"")
  @MethodSource("alternationCaptureCases")
  @DisplayName("matches() exposes alternation captures like java.util.regex")
  void matchesExposesAlternationCapturesLikeJdk(String regex, int flags, String input) {
    assertSingleOperationMatchesJdk(regex, flags, input, Operation.MATCHES);
  }

  private static void assertSingleOperationMatchesJdk(
      String regex, int flags, String input, Operation operation) {
    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regex, flags).matcher(input);
    Matcher safere = Pattern.compile(regex, flags).matcher(input);

    boolean jdkMatched =
        switch (operation) {
          case FIND -> jdk.find();
          case LOOKING_AT -> jdk.lookingAt();
          case MATCHES -> jdk.matches();
        };
    boolean safereMatched =
        switch (operation) {
          case FIND -> safere.find();
          case LOOKING_AT -> safere.lookingAt();
          case MATCHES -> safere.matches();
        };

    assertThat(safereMatched)
        .as("%s result for /%s/ on %s", operation, regex, input)
        .isEqualTo(jdkMatched);
    assertGroupsMatch(regex, input, jdkMatched, jdk, safere);
    if (jdkMatched) {
      assertMatchResultMatchesJdk(regex, input, jdk.toMatchResult(), safere.toMatchResult());
    }
  }

  private static void assertGroupsMatch(
      String regex, String input, boolean matched, java.util.regex.Matcher jdk, Matcher safere) {
    assertThat(safere.groupCount()).isEqualTo(jdk.groupCount());
    if (!matched) {
      return;
    }
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

  private static void assertMatchResultMatchesJdk(
      String regex, String input, MatchResult jdk, MatchResult safere) {
    assertThat(safere.groupCount()).isEqualTo(jdk.groupCount());
    for (int group = 0; group <= jdk.groupCount(); group++) {
      assertThat(safere.group(group))
          .as("toMatchResult group(%d) for /%s/ on %s", group, regex, input)
          .isEqualTo(jdk.group(group));
      assertThat(safere.start(group))
          .as("toMatchResult start(%d) for /%s/ on %s", group, regex, input)
          .isEqualTo(jdk.start(group));
      assertThat(safere.end(group))
          .as("toMatchResult end(%d) for /%s/ on %s", group, regex, input)
          .isEqualTo(jdk.end(group));
    }
  }

  private enum Operation {
    FIND,
    LOOKING_AT,
    MATCHES
  }
}
