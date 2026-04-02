// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link ParseFlags}. */
class ParseFlagsTest {

  @Test
  void flagsArePowersOfTwo() {
    assertThat(ParseFlags.NONE).isEqualTo(0);
    assertThat(ParseFlags.FOLD_CASE).isEqualTo(1);
    assertThat(ParseFlags.LITERAL).isEqualTo(2);
    assertThat(ParseFlags.CLASS_NL).isEqualTo(4);
    assertThat(ParseFlags.DOT_NL).isEqualTo(8);
    assertThat(ParseFlags.ONE_LINE).isEqualTo(16);
    assertThat(ParseFlags.LATIN1).isEqualTo(32);
    assertThat(ParseFlags.NON_GREEDY).isEqualTo(64);
    assertThat(ParseFlags.PERL_CLASSES).isEqualTo(128);
    assertThat(ParseFlags.PERL_B).isEqualTo(256);
    assertThat(ParseFlags.PERL_X).isEqualTo(512);
    assertThat(ParseFlags.UNICODE_GROUPS).isEqualTo(1024);
    assertThat(ParseFlags.NEVER_NL).isEqualTo(2048);
    assertThat(ParseFlags.NEVER_CAPTURE).isEqualTo(4096);
    assertThat(ParseFlags.WAS_DOLLAR).isEqualTo(8192);
  }

  @Test
  void matchNlCombinesClassNlAndDotNl() {
    assertThat(ParseFlags.MATCH_NL).isEqualTo(ParseFlags.CLASS_NL | ParseFlags.DOT_NL);
  }

  @Test
  void likePerlCombinesExpectedFlags() {
    int expected =
        ParseFlags.CLASS_NL
            | ParseFlags.ONE_LINE
            | ParseFlags.PERL_CLASSES
            | ParseFlags.PERL_B
            | ParseFlags.PERL_X
            | ParseFlags.UNICODE_GROUPS;
    assertThat(ParseFlags.LIKE_PERL).isEqualTo(expected);
  }

  @Test
  void allFlagsCoversAllBits() {
    assertThat(ParseFlags.ALL_FLAGS).isEqualTo((1 << 15) - 1);
  }

  @Test
  void likePerlIncludesPerlFeatures() {
    assertThat((ParseFlags.LIKE_PERL & ParseFlags.PERL_CLASSES) != 0).isTrue();
    assertThat((ParseFlags.LIKE_PERL & ParseFlags.PERL_B) != 0).isTrue();
    assertThat((ParseFlags.LIKE_PERL & ParseFlags.PERL_X) != 0).isTrue();
    assertThat((ParseFlags.LIKE_PERL & ParseFlags.UNICODE_GROUPS) != 0).isTrue();
  }
}
