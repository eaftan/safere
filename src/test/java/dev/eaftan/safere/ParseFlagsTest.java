// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for {@link ParseFlags}. */
class ParseFlagsTest {

  @Test
  void flagsArePowersOfTwo() {
    assertEquals(0, ParseFlags.NONE);
    assertEquals(1, ParseFlags.FOLD_CASE);
    assertEquals(2, ParseFlags.LITERAL);
    assertEquals(4, ParseFlags.CLASS_NL);
    assertEquals(8, ParseFlags.DOT_NL);
    assertEquals(16, ParseFlags.ONE_LINE);
    assertEquals(32, ParseFlags.LATIN1);
    assertEquals(64, ParseFlags.NON_GREEDY);
    assertEquals(128, ParseFlags.PERL_CLASSES);
    assertEquals(256, ParseFlags.PERL_B);
    assertEquals(512, ParseFlags.PERL_X);
    assertEquals(1024, ParseFlags.UNICODE_GROUPS);
    assertEquals(2048, ParseFlags.NEVER_NL);
    assertEquals(4096, ParseFlags.NEVER_CAPTURE);
    assertEquals(8192, ParseFlags.WAS_DOLLAR);
  }

  @Test
  void matchNlCombinesClassNlAndDotNl() {
    assertEquals(ParseFlags.CLASS_NL | ParseFlags.DOT_NL, ParseFlags.MATCH_NL);
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
    assertEquals(expected, ParseFlags.LIKE_PERL);
  }

  @Test
  void allFlagsCoversAllBits() {
    assertEquals((1 << 14) - 1, ParseFlags.ALL_FLAGS);
  }

  @Test
  void likePerlIncludesPerlFeatures() {
    assertTrue((ParseFlags.LIKE_PERL & ParseFlags.PERL_CLASSES) != 0);
    assertTrue((ParseFlags.LIKE_PERL & ParseFlags.PERL_B) != 0);
    assertTrue((ParseFlags.LIKE_PERL & ParseFlags.PERL_X) != 0);
    assertTrue((ParseFlags.LIKE_PERL & ParseFlags.UNICODE_GROUPS) != 0);
  }
}
