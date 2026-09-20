// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Splitting must preserve matches when anchored and unanchored searches share cached states. */
class SplitDfaCachingTest {
  @Test
  void splitKeepsIntermediateDelimiterAfterGreedyContinuation() {
    // JDK 26 Pattern.split requires every delimiter, with trailing empty tokens omitted. Refs #889.
    assertThat(Pattern.compile("[0-9]*0").split("0xxx011a----------------0"))
        .containsExactly("", "xxx", "11a----------------");
  }

  @ParameterizedTest
  @MethodSource("splitCases")
  void splitApisPreserveDelimitersAcrossCacheReuse(String regex, String input, int limit) {
    Pattern pattern = Pattern.compile(regex);
    java.util.regex.Pattern expected = java.util.regex.Pattern.compile(regex);
    for (int reuse = 0; reuse < 3; reuse++) {
      assertThat(pattern.split(input, limit)).containsExactly(expected.split(input, limit));
      assertThat(pattern.splitWithDelimiters(input, limit))
          .containsExactly(expected.splitWithDelimiters(input, limit));
      assertThat(pattern.splitAsStream(input).toList())
          .containsExactlyElementsOf(expected.splitAsStream(input).toList());
      Matcher actualMatcher = pattern.matcher(input);
      java.util.regex.Matcher expectedMatcher = expected.matcher(input);
      while (expectedMatcher.find()) {
        assertThat(actualMatcher.find()).isTrue();
        assertThat(actualMatcher.start()).isEqualTo(expectedMatcher.start());
        assertThat(actualMatcher.end()).isEqualTo(expectedMatcher.end());
        assertThat(actualMatcher.group()).isEqualTo(expectedMatcher.group());
      }
      assertThat(actualMatcher.find()).isFalse();
      assertThat(pattern.matcher(input).lookingAt()).isEqualTo(expected.matcher(input).lookingAt());
      assertThat(pattern.matcher(input).matches()).isEqualTo(expected.matcher(input).matches());
    }
  }

  private static Stream<Arguments> splitCases() {
    return Stream.of(
            new String[] {"[0-9]", "0", "1"},
            new String[] {"[a-z]", "a", "b"},
            new String[] {"[\\x00-y]", "\0", "1"},
            new String[] {"[😀😁]", "😀", "😁"})
        .flatMap(
            alphabet ->
                Stream.of("*", "+", "*?", "{0,3}")
                    .flatMap(
                        quantifier ->
                            Stream.of(false, true)
                                .flatMap(
                                    capture -> {
                                      String body = alphabet[0] + quantifier + alphabet[1];
                                      String regex = capture ? "(" + body + ")" : body;
                                      return Stream.of(16, 512)
                                          .flatMap(
                                              gap -> {
                                                String input =
                                                    alphabet[1]
                                                        + "~~~"
                                                        + alphabet[1]
                                                        + alphabet[2].repeat(2)
                                                        + "~".repeat(gap)
                                                        + alphabet[1];
                                                return Stream.of(-1, 0, 1, 2, 3, 10)
                                                    .map(
                                                        limit -> Arguments.of(regex, input, limit));
                                              });
                                    })));
  }
}
