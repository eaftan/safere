// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Behavioral tests for patterns containing literal surrogate code points. */
class SurrogateLiteralTest {

  static Stream<Arguments> nonMatchingValidUtf16Cases() {
    return Stream.of(
        Arguments.of("\"\\uDBFB", "\"?"),
        Arguments.of("^\"\\uDBFB", "\"?"),
        Arguments.of("\\uDBFB", "\uDBFB\uDDBD"),
        Arguments.of("\\uDDBD", "\uDBFB\uDDBD"),
        Arguments.of("\\x{D800}\\x{DC00}", "\uD800\uDC00"),
        Arguments.of("\\uD800(?:\\uDC00)", "\uD800\uDC00"),
        Arguments.of("a\\x{D800}\\x{DC00}b", "a\uD800\uDC00b"),
        Arguments.of("\\uDC00[a-z]", "\uD800\uDC00a"),
        Arguments.of("a\\uDBFB[a-z]", "a?z"),
        Arguments.of("[a-z]+\\x{D800}\\x{DC00}[a-z]+", "a\uD800\uDC00b"),
        Arguments.of(".*a\\uDBFB$", "xa?"),
        Arguments.of(".*\\uDDBD$", "\uDBFB\uDDBD"),
        Arguments.of("(?:a\\x{D800}\\x{DC00}|banana)", "a\uD800\uDC00"),
        Arguments.of("(?:a\\uDBFB|banana)", "a?"));
  }

  @ParameterizedTest
  @MethodSource("nonMatchingValidUtf16Cases")
  void surrogateLiteralsDoNotFalselyMatchOnStringOrUtf8(String regex, String input) {
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);
    assertThat(jdkPattern.matcher(input).find()).isFalse();
    assertThat(jdkPattern.matcher(input).matches()).isFalse();
    assertThat(jdkPattern.matcher(input).lookingAt()).isFalse();

    Pattern pattern = Pattern.compile(regex);
    assertThat(pattern.matcher(input).find()).isFalse();
    assertThat(pattern.matcher(input).matches()).isFalse();
    assertThat(pattern.matcher(input).lookingAt()).isFalse();

    Utf8Input utf8 = Utf8Input.validated(input.getBytes(UTF_8));
    assertThat(pattern.matcher(utf8).find()).isFalse();
    assertThat(pattern.matcher(utf8).matches()).isFalse();
    assertThat(pattern.matcher(utf8).lookingAt()).isFalse();
    assertThat(pattern.find(utf8)).isFalse();
  }

  static Stream<Arguments> matchingUnpairedSurrogateStringCases() {
    return Stream.of(
        Arguments.of("\"\\uDBFB", "\"\uDBFB", 0, 2),
        Arguments.of("\\uDBFB", "x\uDBFBy", 1, 2),
        Arguments.of("\\uDC00[a-z]", "x\uDC00a", 1, 3),
        Arguments.of(".*a\\uDBFB$", "xa\uDBFB", 0, 3),
        Arguments.of("(?:a\\uDBFB|banana)", "xa\uDBFBy", 1, 3));
  }

  @ParameterizedTest
  @MethodSource("matchingUnpairedSurrogateStringCases")
  void unpairedSurrogateLiteralsMatchUnpairedSurrogatesInString(
      String regex, String input, int expectedStart, int expectedEnd) {
    java.util.regex.Matcher expected = java.util.regex.Pattern.compile(regex).matcher(input);
    Matcher actual = Pattern.compile(regex).matcher(input);

    assertThat(actual.find()).isEqualTo(expected.find()).isTrue();
    assertThat(actual.start()).isEqualTo(expected.start()).isEqualTo(expectedStart);
    assertThat(actual.end()).isEqualTo(expected.end()).isEqualTo(expectedEnd);
    assertThat(actual.group()).isEqualTo(expected.group());
  }

  static Stream<Arguments> entryPointCases() {
    return Stream.of(
        Arguments.of("\\uDBFB", "a\uDBFB\uDDBDb\uDBFBc"),
        Arguments.of("\\uDDBD", "a\uDBFB\uDDBDb\uDDBDc"),
        Arguments.of("\\x{D800}\\x{DC00}", "a\uD800\uDC00b\uD800c\uDC00d"),
        Arguments.of("a\\uDBFB", "a?a\uDBFBa\uDBFB\uDDBD"),
        Arguments.of("(?i)a\\uDBFB", "A?A\uDBFBa\uDBFB\uDDBD"),
        Arguments.of("(?i)\\uDC00[a-z]", "\uD800\uDC00A\uDC00B"));
  }

  @ParameterizedTest
  @MethodSource("entryPointCases")
  void surrogateLiteralsAgreeWithJdkAcrossEntryPoints(String regex, String input) {
    java.util.regex.Pattern expected = java.util.regex.Pattern.compile(regex);
    Pattern actual = Pattern.compile(regex);

    assertThat(actual.matcher(input).replaceAll("_"))
        .isEqualTo(expected.matcher(input).replaceAll("_"));
    assertThat(actual.matcher(input).replaceFirst("_"))
        .isEqualTo(expected.matcher(input).replaceFirst("_"));
    assertThat(actual.split(input)).containsExactly(expected.split(input));
    assertThat(actual.matcher(input).results().map(r -> r.start() + "-" + r.end()).toList())
        .isEqualTo(expected.matcher(input).results().map(r -> r.start() + "-" + r.end()).toList());
  }
}
