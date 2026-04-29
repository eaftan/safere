// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for {@link Matcher#usePattern(Pattern)}. */
class MatcherUsePatternTest {

  @Test
  @DisplayName("usePattern swaps pattern and preserves position")
  void usePatternSwapsPattern() {
    Pattern p1 = Pattern.compile("\\d+");
    Pattern p2 = Pattern.compile("[a-z]+");
    Matcher m = p1.matcher("123abc456def");
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("123");
    m.usePattern(p2);
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("abc");
  }

  @Test
  @DisabledForCrosscheck("#225 search position differs after usePattern")
  @DisplayName("usePattern continues searching after the previous match")
  void usePatternContinuesAfterPreviousMatchEnd() {
    Matcher m = Pattern.compile("a").matcher("ab");
    assertThat(m.find()).isTrue();

    m.usePattern(Pattern.compile("."));

    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(1);
    assertThat(m.group()).isEqualTo("b");
  }

  @Test
  @DisplayName("usePattern returns this matcher")
  void usePatternReturnsSelf() {
    Pattern p1 = Pattern.compile("a");
    Pattern p2 = Pattern.compile("b");
    Matcher m = p1.matcher("ab");
    assertThat(m.usePattern(p2)).isSameAs(m);
  }

  @Test
  @DisplayName("usePattern with null throws")
  void usePatternNullThrows() {
    Pattern p = Pattern.compile("a");
    Matcher m = p.matcher("a");
    assertThatThrownBy(() -> m.usePattern(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("pattern() reflects usePattern change")
  void patternReflectsChange() {
    Pattern p1 = Pattern.compile("a");
    Pattern p2 = Pattern.compile("b");
    Matcher m = p1.matcher("ab");
    assertThat(m.pattern()).isSameAs(p1);
    m.usePattern(p2);
    assertThat(m.pattern()).isSameAs(p2);
  }
}
