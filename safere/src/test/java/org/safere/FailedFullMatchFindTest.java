// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Specification-based coverage for the first find after a failed full match. */
@DisabledForCrosscheck(
    "SafeRE follows the documented initial find position; JDK leaks failed full-match state")
class FailedFullMatchFindTest {

  @Test
  void failedFullMatchesPreserveInitialFindPosition() {
    // Issue #818: Matcher.find() starts at the region beginning when no find has succeeded.
    // Preserve that documented behavior, even where JDK 26.0.1 skips the initial match.
    for (String anchor : List.of("\\A", "^")) {
      for (String atom : List.of("a", "😀")) {
        for (String quantifier : List.of("?", "*", "{0,2}", "??", "*?")) {
          for (boolean empty : List.of(false, true)) {
            for (int offset : List.of(0, 2)) {
              for (int attempts : List.of(1, 2)) {
                String regex = anchor + "(" + atom + quantifier + ")";
                String input = (empty ? "" : atom) + "!";
                String text = offset == 0 ? input : "xx" + input + "yy";
                Matcher matcher = Pattern.compile(regex).matcher(text);
                if (offset != 0) {
                  matcher.region(offset, offset + input.length());
                }
                for (int attempt = 0; attempt < attempts; attempt++) {
                  assertThat(matcher.matches()).as("full match: %s", regex).isFalse();
                  assertThat(matcher.hasMatch()).isFalse();
                  assertThatThrownBy(matcher::group).isInstanceOf(IllegalStateException.class);
                }

                boolean reluctant = quantifier.equals("??") || quantifier.equals("*?");
                String expected = empty || reluctant ? "" : atom;
                assertThat(matcher.find())
                    .as("first find: %s, input=%s, offset=%s", regex, input, offset)
                    .isTrue();
                assertThat(matcher.start()).isEqualTo(offset);
                assertThat(matcher.end()).isEqualTo(offset + expected.length());
                assertThat(matcher.group()).isEqualTo(expected);
                assertThat(matcher.group(1)).isEqualTo(expected);
                assertThat(matcher.start(1)).isEqualTo(offset);
                assertThat(matcher.end(1)).isEqualTo(offset + expected.length());
                assertThat(matcher.find()).isFalse();
                assertThat(matcher.find()).isFalse();
              }
            }
          }
        }
      }
    }
  }
}
