// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Pattern#UNICODE_CASE} flag behavior.
 *
 * <p>{@code UNICODE_CASE} controls <i>how</i> case folding works (Unicode vs ASCII-only) when
 * {@link Pattern#CASE_INSENSITIVE} is also set. By itself, it should have no effect on matching.
 *
 * <p>{@code CASE_INSENSITIVE} alone folds ASCII only, matching the JDK. Add
 * {@code UNICODE_CASE} for Unicode-aware folding.
 */
class UnicodeCaseTest {

  @Test
  void asciiCaseFoldingWithCaseInsensitive() {
    // Basic ASCII case folding — works the same in SafeRE and JDK.
    Pattern p = Pattern.compile("hello", Pattern.CASE_INSENSITIVE);
    assertThat(p.matcher("HELLO").matches()).isTrue();
    assertThat(p.matcher("Hello").matches()).isTrue();
    assertThat(p.matcher("hElLo").matches()).isTrue();
  }

  @Test
  void unicodeCaseFoldingWithBothFlags() {
    // With both CASE_INSENSITIVE | UNICODE_CASE, Unicode folding should work.
    Pattern p = Pattern.compile("café",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    assertThat(p.matcher("CAFÉ").matches()).isTrue();
    assertThat(p.matcher("Café").matches()).isTrue();
  }

  @Test
  void unicodeCaseFoldingRequiresUnicodeCase() {
    Pattern p = Pattern.compile("café", Pattern.CASE_INSENSITIVE);
    assertThat(p.matcher("CAFÉ").matches()).isFalse();
    assertThat(p.matcher("Café").matches()).isTrue();
  }

  @Test
  void germanEszett() {
    // German ß — SafeRE folds case for non-ASCII characters.
    Pattern p = Pattern.compile("straße",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    assertThat(p.matcher("STRASSE").matches()).isFalse(); // ß doesn't fold to SS in simple folding
    assertThat(p.matcher("STRAßE").matches()).isTrue();
  }

  @Test
  void greekLettersFolding() {
    Pattern p = Pattern.compile("αβγ",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    assertThat(p.matcher("ΑΒΓ").matches()).isTrue();
    assertThat(p.matcher("αβγ").matches()).isTrue();
  }

  @Test
  void cyrillicLettersFolding() {
    Pattern p = Pattern.compile("привет",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    assertThat(p.matcher("ПРИВЕТ").matches()).isTrue();
    assertThat(p.matcher("Привет").matches()).isTrue();
  }

  @Test
  void characterClassWithUnicodeCase() {
    Pattern p = Pattern.compile("[à-ã]",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    assertThat(p.matcher("á").matches()).isTrue();
    assertThat(p.matcher("Á").matches()).isTrue();
    assertThat(p.matcher("ã").matches()).isTrue();
    assertThat(p.matcher("Ã").matches()).isTrue();
  }

  @Test
  void characterClassUnicodeCaseRequiresUnicodeCase() {
    Pattern p = Pattern.compile("[à-ã]", Pattern.CASE_INSENSITIVE);
    assertThat(p.matcher("á").matches()).isTrue();
    assertThat(p.matcher("Á").matches()).isFalse();
  }

  @Test
  void findWithUnicodeCase() {
    Pattern p = Pattern.compile("über",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    Matcher m = p.matcher("Das ist ÜBER cool");
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("ÜBER");
  }

  @Test
  void splitWithUnicodeCase() {
    Pattern p = Pattern.compile("café",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    String[] parts = p.split("xCAFÉyCaféz");
    assertThat(parts).containsExactly("x", "y", "z");
  }

  @Test
  void replaceAllWithUnicodeCase() {
    Pattern p = Pattern.compile("naïve",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    assertThat(p.matcher("He is NAÏVE").replaceAll("smart")).isEqualTo("He is smart");
  }

  @Test
  void caseInsensitiveWithoutUnicodeCaseMatchesAscii() {
    // Even without UNICODE_CASE, ASCII folding works.
    Pattern p = Pattern.compile("abc", Pattern.CASE_INSENSITIVE);
    assertThat(p.matcher("ABC").matches()).isTrue();
    assertThat(p.matcher("Abc").matches()).isTrue();
  }

  @Test
  void unicodeCaseWithoutCaseInsensitiveDoesNotEnableFolding() {
    // UNICODE_CASE alone should NOT enable case folding — matches JDK behavior.
    Pattern p = Pattern.compile("hello", Pattern.UNICODE_CASE);
    assertThat(p.matcher("HELLO").matches()).isFalse();
    assertThat(p.matcher("hello").matches()).isTrue();
  }

  @Test
  void inlineFlagCaseInsensitive() {
    Pattern p = Pattern.compile("(?i)café");
    assertThat(p.matcher("CAFÉ").matches()).isFalse();
    assertThat(p.matcher("Café").matches()).isTrue();
  }

  @Test
  void inlineFlagUnicodeCaseEnablesUnicodeFolding() {
    Pattern p = Pattern.compile("(?iu)café");
    assertThat(p.matcher("CAFÉ").matches()).isTrue();
  }

  @Test
  void turkishDotlessI() {
    // Turkish dotless ı (U+0131) and İ (U+0130) — these are special in Turkish locale
    // but SafeRE uses simple case folding which maps İ→i and I→ı through Unicode tables.
    Pattern p = Pattern.compile("i",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    assertThat(p.matcher("I").matches()).isTrue();
  }
}
