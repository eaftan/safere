// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Possessive repetition of operands whose consuming descendants execute zero times. */
@DisabledForCrosscheck("differential test already compares SafeRE with java.util.regex")
class ZeroCountPossessiveTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "a{0}",
        "a{0,0}",
        "a{0}?",
        "a{0,0}?",
        "(?:(a)+){0}",
        "(?:(a){0}){2}",
        "(?:(a){0})*",
        "a{0}|b{0}",
        "a{0}b{0}",
        "(?:a|(){2}){0}?",
        "(?:.|(){2}){0}",
        "(?<word>a|aa){0}",
        "(?i:a){0}",
        "\\x{1F600}{0}",
        "a{0}|^",
        "a{0}\\b"
      })
  void zeroCountOperandsMatchLikeJdk(String operand) {
    // JDK 26 Pattern defines {0} as exactly zero executions. Refs #1130.
    // The outer quantifier's immediate operand is therefore statically zero-width.
    for (String quantifier : List.of("*+", "?+", "++", "{0}+", "{1}+", "{2}+", "{0,2}+", "{1,}+")) {
      String repeated = "(?:" + operand + ")" + quantifier;
      for (String regex : List.of(repeated, "x(" + repeated + ")(y)", "(" + repeated + ")|a")) {
        for (int flags : List.of(0, Pattern.COMMENTS)) {
          for (String input : List.of("", "a", "xy", "xay", "\uD83D\uDE00")) {
            assertOperationsSame(regex, flags, input);
          }
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "(?:x)?(?:(?:(?<word>a|aa)){0})*+",
        "(?:(?:.|(){2}){0})*+",
        "x(?:(?:a|(){2}){0}?)*+y",
        "x(?:(?:(?<word>a|aa)){0})*+",
        "x(?:(?:a|(?:()){2}){0}?)*+c?"
      })
  void archivedZeroCountShapesMatchLikeJdk(String regex) {
    for (String input : List.of("", "x", "xy", "xc", "xaa", "xx")) {
      assertOperationsSame(regex, 0, input);
    }
  }

  @Test
  void skippedCapturesKeepTheirNumbersAndRemainUnmatched() {
    Matcher matcher = Pattern.compile("((?<skipped>a){0})++(b)").matcher("b");
    assertThat(matcher.matches()).isTrue();
    assertThat(matcher.groupCount()).isEqualTo(3);
    assertThat(matcher.group(1)).isEmpty();
    assertThat(matcher.group("skipped")).isNull();
    assertThat(matcher.start("skipped")).isEqualTo(-1);
    assertThat(matcher.end("skipped")).isEqualTo(-1);
    assertThat(matcher.group(3)).isEqualTo("b");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "a{0}+",
        "a{0,0}+",
        "(?:a{0}|b)*+",
        "(?:a{0}b)?+",
        "(?:a{0,1})++",
        "(?:a?){0}+",
        "(?:(a){0}b){1}+"
      })
  void consumingImmediateOperandsStayUnsupported(String regex) {
    java.util.regex.Pattern.compile(regex);
    assertThatThrownBy(() -> Pattern.compile(regex))
        .isInstanceOf(PatternSyntaxException.class)
        .hasMessageContaining("possessive quantifiers are not supported");
  }

  private static void assertOperationsSame(String regex, int flags, String input) {
    Pattern safe = Pattern.compile(regex, flags);
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex, flags);
    Matcher actual = safe.matcher(input);
    java.util.regex.Matcher expected = jdk.matcher(input);
    boolean matched = expected.matches();
    assertThat(actual.matches()).as("matches /%s/ on %s", regex, input).isEqualTo(matched);
    if (matched) {
      assertGroupsSame(actual, expected);
    }
    actual.reset();
    expected.reset();
    matched = expected.lookingAt();
    assertThat(actual.lookingAt()).as("lookingAt /%s/ on %s", regex, input).isEqualTo(matched);
    if (matched) {
      assertGroupsSame(actual, expected);
    }
    actual.reset();
    expected.reset();
    while (expected.find()) {
      assertThat(actual.find()).as("find /%s/ on %s", regex, input).isTrue();
      assertGroupsSame(actual, expected);
    }
    assertThat(actual.find()).isFalse();
  }

  private static void assertGroupsSame(Matcher actual, java.util.regex.Matcher expected) {
    assertThat(actual.groupCount()).isEqualTo(expected.groupCount());
    for (int group = 0; group <= expected.groupCount(); group++) {
      assertThat(actual.group(group)).as("group %s", group).isEqualTo(expected.group(group));
      assertThat(actual.start(group)).as("start %s", group).isEqualTo(expected.start(group));
      assertThat(actual.end(group)).as("end %s", group).isEqualTo(expected.end(group));
    }
  }
}
