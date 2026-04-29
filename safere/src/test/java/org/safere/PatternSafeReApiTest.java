// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tests for Pattern APIs that are SafeRE-specific extensions. */
@DisabledForCrosscheck("SafeRE-only Pattern APIs and (?P<name>...) syntax have no JDK equivalent")
class PatternSafeReApiTest {

  @Test
  void extractsNamedGroups() {
    Pattern p = Pattern.compile("(?P<user>\\w+)@(?P<host>\\w+)");
    assertThat(p.namedGroups()).containsEntry("user", 1);
    assertThat(p.namedGroups()).containsEntry("host", 2);
  }

  @Test
  void noNamedGroups() {
    Pattern p = Pattern.compile("(\\w+)@(\\w+)");
    assertThat(p.namedGroups()).isEmpty();
  }

  @Test
  void numGroupsCounting() {
    Pattern p = Pattern.compile("(a)(b)(c)");
    assertThat(p.numGroups()).isEqualTo(3);
  }

  @Test
  void numGroupsNoCaptures() {
    Pattern p = Pattern.compile("abc");
    assertThat(p.numGroups()).isZero();
  }

  @Test
  @DisplayName("namedGroups() returns unmodifiable map")
  void namedGroupsUnmodifiable() {
    Pattern p = Pattern.compile("(?P<user>\\w+)@(?P<host>\\w+)");
    assertThatThrownBy(() -> p.namedGroups().put("foo", 99))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("duplicate named capturing groups are rejected")
  void duplicateNamedGroupsRejected() {
    assertThatThrownBy(() -> Pattern.compile("(?<word>a)(?<word>b)"))
        .isInstanceOf(PatternSyntaxException.class);
    assertThatThrownBy(() -> Pattern.compile("(?P<word>a)(?P<word>b)"))
        .isInstanceOf(PatternSyntaxException.class);
  }

  @ParameterizedTest(name = "compile(\"{0}\").numGroups() == {1}")
  @CsvSource({
      "'',         0",
      "'.*',        0",
      "'abba',      0",
      "'ab(b)a',    1",
      "'ab(.*)a',   1",
      "'(.*)ab(.*)a',  2",
      "'(.*)(ab)(.*)a', 3",
      "'(.*)((a)b)(.*)a', 4",
      "'(.*)(\\(ab)(.*)a', 3",
      "'(.*)(\\(a\\)b)(.*)a', 3",
  })
  void numGroups(String pattern, int expected) {
    assertThat(Pattern.compile(pattern).numGroups()).isEqualTo(expected);
  }
}
