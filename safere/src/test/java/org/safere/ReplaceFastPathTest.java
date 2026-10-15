// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code replaceAll} and {@code replaceFirst} against the JDK for patterns that have a {@code
 * find()} fast path or an end anchor, which skip the DFA-driven replace loop.
 */
class ReplaceFastPathTest {

  private static final List<String> REGEXES =
      List.of(
          "[0-9]",
          "\\p{L}",
          "[é😀]",
          "[\\x{1F600}-\\x{1F64F}]",
          "^[0-9]",
          "(?i)\\b(?:foo|bar|baz)\\b",
          "\\s*(?:foo|fax)\\z",
          "\\s*(?:foo|fax)$");

  private static final List<String> INPUTS =
      List.of(
          "",
          "no digits or keywords here",
          "order 12345 shipped on 2025-03-22",
          "7 at the start",
          "été 😀 and 😃 smile",
          "Foo bar BAZ food barn",
          "ends with a fax",
          "ends with foo\n",
          "ends with foo\r\n",
          "  foo");

  private static final List<String> REPLACEMENTS = List.of("X", "", "<$0>");

  @Test
  void replaceMatchesTheJdk() {
    for (String regex : REGEXES) {
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);
      Pattern safere = Pattern.compile(regex);
      for (String input : INPUTS) {
        for (String replacement : REPLACEMENTS) {
          assertThat(safere.matcher(input).replaceAll(replacement))
              .as("replaceAll /%s/ on %s with %s", regex, input, replacement)
              .isEqualTo(jdk.matcher(input).replaceAll(replacement));
          assertThat(safere.matcher(input).replaceFirst(replacement))
              .as("replaceFirst /%s/ on %s with %s", regex, input, replacement)
              .isEqualTo(jdk.matcher(input).replaceFirst(replacement));
        }
      }
    }
  }

  @Test
  void matcherStateAfterReplaceFirstMatchesTheJdk() {
    for (String regex : List.of("[0-9]", "\\p{L}", "[é😀]")) {
      String input = "ab 😀 é 42";
      java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regex).matcher(input);
      Matcher safere = Pattern.compile(regex).matcher(input);
      jdk.replaceFirst("X");
      safere.replaceFirst("X");
      jdk.reset();
      safere.reset();
      while (jdk.find()) {
        assertThat(safere.find()).as("/%s/ find after replaceFirst", regex).isTrue();
        assertThat(safere.start()).isEqualTo(jdk.start());
        assertThat(safere.end()).isEqualTo(jdk.end());
      }
      assertThat(safere.find()).isFalse();
    }
  }
}
