// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeferredAssertionFullMatchTest {
  @ParameterizedTest
  @ValueSource(strings = {"a", "!", "α", "😀", "\n", "\r\n", "\u2028"})
  void deferredMatchKeepsConsumingAlternatives(String atom) {
    String literal = Pattern.quote(atom);
    for (String assertion : List.of("\\b", "\\B", "(?m:$)")) {
      for (String quantifier : List.of("*", "*?")) {
        for (boolean assertionFirst : new boolean[] {false, true}) {
          String consuming = literal + literal;
          String alternatives =
              assertionFirst ? assertion + "|" + consuming : consuming + "|" + assertion;
          String regex = "(?U)(?:" + literal + ")" + quantifier + "(?:" + alternatives + ")";
          Pattern pattern = Pattern.compile(regex);
          for (int size : new int[] {2, 3, 300, 2}) {
            String input = atom.repeat(size);
            // The consuming alternative covers the entire input, regardless of assertion priority.
            assertThat(pattern.matcher(input).matches()).as("%s, size=%s", regex, size).isTrue();
            assertThat(pattern.matcher("x" + input + "x").region(1, 1 + input.length()).matches())
                .as("region: %s, size=%s", regex, size)
                .isTrue();
            Matcher actual = pattern.matcher(input);
            java.util.regex.Matcher expected =
                java.util.regex.Pattern.compile(regex).matcher(input);
            assertThat(actual.lookingAt()).isEqualTo(expected.lookingAt());
            assertThat(actual.end()).as("lookingAt: %s", regex).isEqualTo(expected.end());
            actual.reset();
            expected.reset();
            while (expected.find()) {
              assertThat(actual.find()).as("find: %s", regex).isTrue();
              assertThat(actual.start()).isEqualTo(expected.start());
              assertThat(actual.end()).isEqualTo(expected.end());
            }
            assertThat(actual.find()).isFalse();
          }
          assertThat(pattern.matcher(atom + "x").matches()).as(regex).isFalse();
        }
      }
    }
  }
}
