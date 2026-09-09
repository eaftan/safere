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

/** Regression coverage for Unicode word classification in cached DFA transitions. */
class UnicodeBoundaryDfaCachingTest {
  @ParameterizedTest
  @MethodSource("cases")
  void findSequenceMatchesJdk(String regex, String input) {
    var expected = java.util.regex.Pattern.compile(regex).matcher(input);
    var actual = Pattern.compile(regex).matcher(input);
    while (expected.find()) {
      assertThat(actual.find()).as("find for %s", regex).isTrue();
      assertThat(actual.start()).isEqualTo(expected.start());
      assertThat(actual.end()).isEqualTo(expected.end());
    }
    assertThat(actual.find()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("cases")
  @DisabledForCrosscheck("UTF-8 input is a SafeRE extension")
  void utf8FindSequenceMatchesJdk(String regex, String input) {
    var expected = java.util.regex.Pattern.compile(regex).matcher(input);
    var actual = Pattern.compile(regex).matcher(Utf8Input.validated(input.getBytes(UTF_8)));
    while (expected.find()) {
      assertThat(actual.find()).as("UTF-8 find for %s", regex).isTrue();
      assertThat(actual.start())
          .isEqualTo(input.substring(0, expected.start()).getBytes(UTF_8).length);
      assertThat(actual.end()).isEqualTo(input.substring(0, expected.end()).getBytes(UTF_8).length);
    }
    assertThat(actual.find()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("supplementaryCases")
  @DisabledForCrosscheck("SafeRE intentionally prevents matching bounds inside surrogate pairs")
  void supplementaryWordClassesPreserveScalarBounds(String regex, int[] bounds) {
    var actual = Pattern.compile(regex).matcher(" 𐐀`\u180e");
    for (int i = 0; i < bounds.length; i += 2) {
      assertThat(actual.find()).isTrue();
      assertThat(actual.start()).isEqualTo(bounds[i]);
      assertThat(actual.end()).isEqualTo(bounds[i + 1]);
    }
    assertThat(actual.find()).isFalse();
  }

  static Stream<Arguments> supplementaryCases() {
    return Stream.of(
        Arguments.of("(?U).\\b", new int[] {0, 1, 1, 3}),
        Arguments.of("(?U).\\B", new int[] {3, 4, 4, 5}),
        Arguments.of("(?U)\\b.", new int[] {1, 3, 3, 4}),
        Arguments.of("(?U)\\B.", new int[] {0, 1, 4, 5}));
  }

  static Stream<Arguments> cases() {
    return Stream.of("α", "中", "\u0301", "\u0660", "\u200c")
        .flatMap(
            word ->
                Stream.of("\\b", "\\B", "(?:(?-U:\\b)|\\B)")
                    .flatMap(
                        boundary ->
                            Stream.of("", "x".repeat(300))
                                .flatMap(
                                    prefix ->
                                        Stream.of(
                                            Arguments.of(
                                                "(?U)." + boundary,
                                                prefix + " " + word + "`\u180e"),
                                            Arguments.of(
                                                "(?U)" + boundary + ".",
                                                prefix + " " + word + "`\u180e")))));
  }
}
