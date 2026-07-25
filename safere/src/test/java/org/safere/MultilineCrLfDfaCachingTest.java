// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Compatibility coverage for CRLF-sensitive multiline anchors in cached DFA transitions. */
class MultilineCrLfDfaCachingTest {
  private static final String LONG_PREFIX = "a".repeat(500);

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
