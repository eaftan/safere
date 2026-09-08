// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Compatibility coverage for CRLF-sensitive multiline anchors in cached DFA transitions. */
class MultilineCrLfDfaCachingTest {
  private static final String LONG_PREFIX = "a".repeat(500);

  @Test
  void reusedDollarStopsBeforeWholeCrLfTerminator() {
    // JDK 26 Pattern defines CRLF as one line terminator. Refs #713.
    Pattern pattern = Pattern.compile("\\W*(?m:$)");
    pattern.matcher(" \na").find();
    Matcher matcher = pattern.matcher("xb\r\n" + "a".repeat(30));
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(2);
    assertThat(matcher.end()).isEqualTo(2);
  }

  @ParameterizedTest
  @MethodSource("reusedDollarCases")
  void reusedDollarPreservesLineTerminatorBounds(
      String regex, String warmTerminator, String targetTerminator, int suffixLength) {
    Pattern pattern = Pattern.compile(regex);
    pattern.matcher(" " + warmTerminator + "a").find();
    String input = "xb" + targetTerminator + "a".repeat(suffixLength);
    assertFindSequence(pattern.matcher(input), regex, input);
  }

  @ParameterizedTest
  @MethodSource("reusedDollarCases")
  @DisabledForCrosscheck("UTF-8 input API is SafeRE-specific")
  void reusedUtf8DollarPreservesLineTerminatorBounds(
      String regex, String warmTerminator, String targetTerminator, int suffixLength) {
    Pattern pattern = Pattern.compile(regex);
    pattern.matcher(Utf8Input.validated((" " + warmTerminator + "a").getBytes(UTF_8))).find();
    String input = "xb" + targetTerminator + "a".repeat(suffixLength);
    Utf8Matcher actual = pattern.matcher(Utf8Input.validated(input.getBytes(UTF_8)));
    java.util.regex.Matcher expected = java.util.regex.Pattern.compile(regex).matcher(input);
    while (expected.find()) {
      assertThat(actual.find()).isTrue();
      assertThat(actual.start())
          .isEqualTo(input.substring(0, expected.start()).getBytes(UTF_8).length);
      assertThat(actual.end()).isEqualTo(input.substring(0, expected.end()).getBytes(UTF_8).length);
      for (int group = 0; group <= expected.groupCount(); group++) {
        assertThat(actual.start(group))
            .isEqualTo(input.substring(0, expected.start(group)).getBytes(UTF_8).length);
        assertThat(actual.end(group))
            .isEqualTo(input.substring(0, expected.end(group)).getBytes(UTF_8).length);
      }
    }
    assertThat(actual.find()).isFalse();
  }

  private static void assertFindSequence(Matcher actual, String regex, String input) {
    java.util.regex.Matcher expected = java.util.regex.Pattern.compile(regex).matcher(input);
    while (expected.find()) {
      assertThat(actual.find()).isTrue();
      assertThat(actual.start()).isEqualTo(expected.start());
      assertThat(actual.end()).isEqualTo(expected.end());
      for (int group = 0; group <= expected.groupCount(); group++) {
        assertThat(actual.group(group)).isEqualTo(expected.group(group));
      }
    }
    assertThat(actual.find()).isFalse();
  }

  private static Stream<Arguments> reusedDollarCases() {
    return Stream.of("\\W*(?m:$)", "\\s*(?m:$)", "(\\W*)(?m:$)", "\\W*?(?m:$)")
        .flatMap(
            regex ->
                Stream.of("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029")
                    .flatMap(
                        warm ->
                            Stream.of("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029")
                                .flatMap(
                                    target ->
                                        Stream.of(30, 500)
                                            .map(
                                                length ->
                                                    Arguments.of(regex, warm, target, length)))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("mixedCarriageReturnCases")
  @DisplayName("multiline ^ distinguishes CRLF from standalone carriage returns")
  void multilineBeginningAnchorDistinguishesCrLfFromStandaloneCarriageReturns(
      String description, String regex, String input, boolean expected) {
    boolean jdkResult = java.util.regex.Pattern.compile(regex).matcher(input).find();
    assertThat(jdkResult).as("JDK result for %s", description).isEqualTo(expected);

    Pattern pattern = Pattern.compile(regex);
    assertThat(pattern.matcher(input).find())
        .as("String result for %s", description)
        .isEqualTo(jdkResult);
    assertThat(pattern.matcher(Utf8Input.validated(input.getBytes(UTF_8))).find())
        .as("UTF-8 result for %s", description)
        .isEqualTo(jdkResult);
  }

  private static Stream<Arguments> mixedCarriageReturnCases() {
    return Stream.of(
        Arguments.of(
            "CRLF before standalone CR must not suppress a line start",
            "(?m)^.X",
            LONG_PREFIX + "\r\nqq\rqX",
            true),
        Arguments.of(
            "standalone CR before CRLF must not create a line start inside CRLF",
            "(?m)^\\nX",
            LONG_PREFIX + "\rqY\r\nX",
            false),
        Arguments.of(
            "repeated CRLF before standalone CR must preserve the standalone line start",
            "(?m)^qX",
            LONG_PREFIX + "\r\nqY\r\nqY\rqX",
            true),
        Arguments.of(
            "repeated standalone CR before CRLF must not split the CRLF pair",
            "(?m)^\\nX",
            LONG_PREFIX + "\rqY\rqY\r\nX",
            false));
  }
}
