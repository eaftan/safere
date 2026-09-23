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

/** Search-start compatibility when a nullable pattern begins inside a surrogate pair. */
class DfaNullableSurrogateFindTest {

  static Stream<Arguments> cases() {
    return Stream.of(
        Arguments.of(".|", "\uDB7D\uDF02A", 1),
        Arguments.of(".|", "x\uDB7D\uDF02A", 2),
        Arguments.of(".|", "\uDB7D\uDF02", 1),
        Arguments.of(".|", "\uDB7D\uDF02A", 2),
        Arguments.of(".|", "\uDB7D\uDF02" + "A".repeat(300), 1),
        Arguments.of("a|", "\uDB7D\uDF02A", 1),
        Arguments.of(".?", "\uDB7D\uDF02A", 1),
        Arguments.of("(?:.|)A?", "\uDB7D\uDF02A", 1),
        Arguments.of("(.)|", "\uDB7D\uDF02A", 1));
  }

  @ParameterizedTest
  @MethodSource("cases")
  void findFromSurrogateInteriorMatchesJdk(String regex, String input, int start) {
    java.util.regex.Matcher expected = java.util.regex.Pattern.compile(regex).matcher(input);
    Matcher actual = Pattern.compile(regex).matcher(input);
    assertThat(actual.find(start)).isEqualTo(expected.find(start));
    assertThat(actual.start()).isEqualTo(expected.start());
    assertThat(actual.end()).isEqualTo(expected.end());
    assertThat(actual.group()).isEqualTo(expected.group());
  }
}
