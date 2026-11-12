// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.CharBuffer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Adversarial differential tests for DFA-loop start acceleration under position-dependent
 * assertions.
 */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class StartAcceleratorDifferentialTest {

  private static final EnginePathOptions ACCELERATED =
      EnginePathOptions.builder().startAcceleration(true).build();
  private static final EnginePathOptions UNACCELERATED =
      EnginePathOptions.builder().startAcceleration(false).build();

  @ParameterizedTest
  @ValueSource(strings = {"😀", "🧪", "👍"})
  void wideExpansionsPreserveGraphemeProgramLowSurrogateStarts(String excluded) {
    String padding = "p".repeat(300);
    String text = padding + excluded + "error!";
    for (String repetition : List.of("+", "+?", "{1,3}")) {
      String regex = "([^" + excluded + "]" + repetition + ")error\\X";
      Pattern accelerated = Pattern.compile(regex, 0, ACCELERATED);
      Pattern control = Pattern.compile(regex, 0, UNACCELERATED);
      Matcher expected = control.matcher(text);
      assertThat(expected.find()).isTrue();
      assertThat(expected.start()).isEqualTo(padding.length() + 1);
      assertThat(expected.group(1)).isEqualTo(excluded.substring(1));
      for (CharSequence input : List.of(text, CharBuffer.wrap(text))) {
        Matcher actual = accelerated.matcher(input);
        assertThat(actual.find()).as("%s on %s", regex, input.getClass()).isTrue();
        assertThat(actual.start()).isEqualTo(expected.start());
        assertThat(actual.end()).isEqualTo(expected.end());
        assertThat(actual.group(1)).isEqualTo(expected.group(1));
        assertThat(actual.find()).isFalse();
      }
      assertThat(accelerated.asPredicate().test(text)).isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "(b|(?m:^a))cd[0-9]",
        "((?m:^first)|second)token",
        "(b|\\Aa)cd[0-9]",
        "(\\Aprefix|unanchored)token",
        "(b|\\ba)cd[0-9]",
        "(b|\\Ba)cd[0-9]",
        "(\\bword|regular)match",
        "(b|(?m:a$))cd",
        "(b|a\\z)cd"
      })
  @DisplayName("Ablation: accelerated DFA matches unaccelerated baseline on false candidates")
  void acceleratedDfaMatchesBaselineOnFalseCandidates(String regex) {
    List<String> adversarialInputs =
        List.of(
            "x".repeat(200) + "0cb\r1bacd19c1__19x y_",
            "x".repeat(200) + "0cb1bacd19c1__19x y_",
            "x".repeat(200) + "foo_acd19bar",
            "x".repeat(200) + "foo acd19bar",
            "x".repeat(200) + "prefixfirsttoken_rest",
            "x".repeat(200) + "123firsttoken_rest",
            "x".repeat(200) + "nonwordmatch_rest",
            "x".repeat(200) + "wordmatch rest");

    Pattern accelerated = Pattern.compile(regex, 0, ACCELERATED);
    Pattern control = Pattern.compile(regex, 0, UNACCELERATED);
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);

    for (String input : adversarialInputs) {
      boolean expected = control.matcher(input).find();
      boolean actual = accelerated.matcher(input).find();
      assertThat(actual)
          .as("String find() divergence for /%s/ on input %s", regex, input)
          .isEqualTo(expected);

      Utf8Input utf8 = Utf8Input.validated(input.getBytes(UTF_8));
      boolean actualUtf8 = accelerated.find(utf8);
      assertThat(actualUtf8)
          .as("UTF-8 find() divergence for /%s/ on input %s", regex, input)
          .isEqualTo(expected);

      // Verify consistency against JDK regex engine
      boolean jdkExpected = jdk.matcher(input).find();
      assertThat(actual)
          .as("SafeRE vs JDK divergence for /%s/ on input %s", regex, input)
          .isEqualTo(jdkExpected);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "(b|(?m:^a))cd[0-9]",
        "((?m:^first)|second)token",
        "(b|\\Aa)cd[0-9]",
        "(b|\\ba)cd[0-9]",
        "(\\bword|regular)match"
      })
  @DisplayName("Ablation: accelerated DFA matches unaccelerated baseline on true candidates")
  void acceleratedDfaMatchesBaselineOnTrueCandidates(String regex) {
    List<String> matchingInputs =
        List.of(
            "x".repeat(200) + "\nacd19c1__19x y_",
            "x".repeat(200) + "\r\nfirsttoken rest",
            "firsttoken at start of text",
            "x".repeat(200) + " wordmatch rest",
            "x".repeat(200) + "\nwordmatch rest");

    Pattern accelerated = Pattern.compile(regex, 0, ACCELERATED);
    Pattern control = Pattern.compile(regex, 0, UNACCELERATED);

    for (String input : matchingInputs) {
      boolean expected = control.matcher(input).find();
      boolean actual = accelerated.matcher(input).find();
      assertThat(actual)
          .as("String find() divergence for /%s/ on input %s", regex, input)
          .isEqualTo(expected);

      Utf8Input utf8 = Utf8Input.validated(input.getBytes(UTF_8));
      boolean actualUtf8 = accelerated.find(utf8);
      assertThat(actualUtf8)
          .as("UTF-8 find() divergence for /%s/ on input %s", regex, input)
          .isEqualTo(expected);
    }
  }

  @Test
  void exactIssue711Reproducer() {
    String regex = "(b|(?m:^a))c[0-9]";
    String input = "0cb\r1bac19c1__19x y_";
    Pattern accelerated = Pattern.compile(regex, 0, ACCELERATED);
    Pattern control = Pattern.compile(regex, 0, UNACCELERATED);
    Utf8Input utf8 = Utf8Input.validated(input.getBytes(UTF_8));

    assertThat(control.find(utf8)).isFalse();
    assertThat(accelerated.find(utf8)).isFalse();
    assertThat(java.util.regex.Pattern.compile(regex).matcher(input).find()).isFalse();
  }
}
