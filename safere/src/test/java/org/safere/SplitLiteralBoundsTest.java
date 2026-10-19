// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Literal split delimiters must fit in the input before their characters are compared. */
class SplitLiteralBoundsTest {
  @ParameterizedTest
  @MethodSource("literalCases")
  void splitApisHandleLiteralLengthBoundaries(String regex, int flags, String input) {
    Pattern pattern = Pattern.compile(regex, flags);
    java.util.regex.Pattern expected = java.util.regex.Pattern.compile(regex, flags);
    assertThat(pattern.split(input)).containsExactly(expected.split(input));
    assertThat(pattern.splitWithDelimiters(input))
        .containsExactly(expected.splitWithDelimiters(input, 0));
    for (int limit : new int[] {-2, -1, 0, 1, 2, 3, Integer.MAX_VALUE}) {
      assertThat(pattern.split(input, limit))
          .as("split limit=%s", limit)
          .containsExactly(expected.split(input, limit));
      assertThat(pattern.splitWithDelimiters(input, limit))
          .as("splitWithDelimiters limit=%s", limit)
          .containsExactly(expected.splitWithDelimiters(input, limit));
    }
    assertThat(pattern.splitAsStream(input).toList())
        .containsExactlyElementsOf(expected.splitAsStream(input).toList());
  }

  private static Stream<Arguments> literalCases() {
    // Refs #1050. Include unanchored and case-sensitive controls, both absolute anchors,
    // successful delimiters, and short prefixes ending at supplementary code point boundaries.
    return Stream.of("a", "abc", "a😀b")
        .flatMap(
            literal ->
                Stream.of("", "^", "\\A")
                    .flatMap(
                        anchor ->
                            Stream.of(
                                    0,
                                    Pattern.CASE_INSENSITIVE,
                                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                                .flatMap(
                                    flags ->
                                        Stream.of(
                                                "",
                                                "a",
                                                "ab",
                                                "AB",
                                                "a😀",
                                                "abc",
                                                "ABC",
                                                "AbCtail",
                                                "a😀b",
                                                "A😀Btail",
                                                "xabc",
                                                "xA😀B")
                                            .map(
                                                input ->
                                                    Arguments.of(
                                                        anchor + literal, flags, input)))));
  }
}
