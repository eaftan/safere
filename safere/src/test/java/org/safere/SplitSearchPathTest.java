// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/// Split searches that start from the input end or scan for keywords must agree with the JDK.
///
/// End-anchored probes only run on inputs of at least 1,024 code units, so most inputs are padded
/// with filler; the short inputs remain as controls.
class SplitSearchPathTest {
  private static final String FILLER =
      "the quick brown fox jumps over the lazy dog ".repeat(50).trim();

  @ParameterizedTest
  @MethodSource("cases")
  void splitMatchesJdk(String regex, int flags, String input) {
    Pattern pattern = Pattern.compile(regex, flags);
    java.util.regex.Pattern expected = java.util.regex.Pattern.compile(regex, flags);
    for (int limit : new int[] {-1, 0, 1, 2, 3}) {
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

  private static Stream<Arguments> cases() {
    List<String> regexes =
        List.of(
            "(?:foo|fax)$",
            "\\s*(?:foo|fax)\\z",
            "(?:foo|fax)\\Z",
            "\\s*$",
            "o*\\z",
            "\\b(?:foo|bar|baz)\\b");
    List<String> inputs =
        List.of(
            FILLER + " foo",
            FILLER + " foo\n",
            FILLER + " fax\r\n",
            FILLER + "foo",
            FILLER + "  \n",
            FILLER + " Bar " + FILLER + " baz",
            "foo " + FILLER + " BAZ\nfoo",
            FILLER,
            " foo",
            "foo\n",
            "");
    return regexes.stream()
        .flatMap(
            regex ->
                Stream.of(0, Pattern.CASE_INSENSITIVE)
                    .flatMap(
                        flags -> inputs.stream().map(input -> Arguments.of(regex, flags, input))));
  }
}
