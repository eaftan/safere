// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for leftmost-match semantics when bounded repeats create multiple paths ending at the same
 * position.
 *
 * <p>The DFA sandwich can find an earliest match end, then use the reverse DFA to recover a start
 * position. Optional bounded prefixes are ambiguous in that reverse pass: the same overall match
 * may also be valid if the optional prefix is skipped, yielding a later start. These tests verify
 * that {@link Matcher#find()} preserves JDK-compatible leftmost-first boundaries.
 *
 * <p>See <a href="https://github.com/eaftan/safere/issues/159">issue #159</a>.
 */
@DisplayName("Bounded repeats: leftmost position")
class BoundedRepeatLeftmostPositionTest {

  @ParameterizedTest
  @CsvSource({
    "'(?:a{1,3})?a{3}', 'aaaa'",
    "'(?:\\+?\\d{1,3} ?)?\\d{3}', '+981 084'",
    "'(?:ab{0,2})?abb', 'abb'",
    "'(?:ab{0,2})?abb', 'ababb'"
  })
  @DisplayName("optional bounded prefixes keep the leftmost start")
  void optionalBoundedPrefixKeepsLeftmostStart(String pattern, String input) {
    assertFindSameAsJdk(pattern, input);
  }

  @Nested
  @DisplayName("Issue #159 regressions")
  class Issue159Regressions {

    @Test
    @DisplayName("phone-number pattern with bounded repeats")
    void phoneNumberPatternWithBoundedRepeats() {
      String pattern =
          "("
              + "(\\+?\\d{1,3}( )?)?"
              + "[0-9][ ~,\\-'`_]*[0-9O][ ~,\\-'`_]*[0-9O],?\\s?"
              + "[\\r\\n() '_~\\-.*]{0,4}\\s?"
              + "[0-9O][ \\-'`_]*[0-9O][ \\-'`_]*[0-9O]\\s?"
              + "[() '_\\-~.*]{0,4}\\s?"
              + "[0-9O][ \\-'`_]*[0-9O][ \\-'`_]*[0-9O][ \\-'`_]*[0-9O]"
              + ")";
      String input = "user message: Email me on hi@gmail.com and call +981 084 482 1192";

      assertFindSameAsJdk(pattern, input);
    }
  }

  private static void assertFindSameAsJdk(String pattern, String input) {
    java.util.regex.Matcher jdkMatcher =
        java.util.regex.Pattern.compile(pattern).matcher(input);
    Matcher safereMatcher = Pattern.compile(pattern).matcher(input);

    assertThat(jdkMatcher.find()).isTrue();
    assertThat(safereMatcher.find()).isTrue();
    assertThat(safereMatcher.start()).isEqualTo(jdkMatcher.start());
    assertThat(safereMatcher.end()).isEqualTo(jdkMatcher.end());
    assertThat(safereMatcher.group()).isEqualTo(jdkMatcher.group());
  }
}
