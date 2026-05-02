// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tests for package-private {@link Pattern} metadata. */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class PatternInternalTest {

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
  void transparentGroupsPreserveLiteralAccelerators() {
    Pattern p = Pattern.compile("(?:abcdef)");

    assertThat(p.literalMatch()).isEqualTo("abcdef");
    assertThat(p.prefix()).isEqualTo("abcdef");
  }

  @Test
  void transparentGroupsPreserveCharacterClassAccelerators() {
    Pattern p = Pattern.compile("(?:[A-Z]+)");

    boolean[] prefix = p.charClassPrefixAscii();
    assertThat(prefix).isNotNull();
    assertThat(prefix['A']).isTrue();
    assertThat(p.charClassMatchRanges()).isNotNull();
  }

  @Test
  void transparentGroupsPreserveKeywordAlternationAccelerator() {
    Pattern p = Pattern.compile("(?i)\\b(?:error|warning)\\b");

    assertThat(p.keywordAlternation()).isNotNull();
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
