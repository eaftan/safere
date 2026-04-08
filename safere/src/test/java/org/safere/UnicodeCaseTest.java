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
 * <p><b>Known divergence from JDK:</b> SafeRE always performs Unicode case folding when
 * {@link Pattern#CASE_INSENSITIVE} is set (because the RE2-derived engine uses Unicode tables by
 * default). In the JDK, {@code CASE_INSENSITIVE} alone only folds ASCII, and you must also set
 * {@code UNICODE_CASE} for Unicode folding. SafeRE's behavior is a superset — it always matches
 * Unicode regardless of whether {@code UNICODE_CASE} is set. These tests document this divergence.
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
  void unicodeCaseFoldingWithCaseInsensitiveOnly() {
    // SafeRE divergence: Unicode folding works with CASE_INSENSITIVE alone.
    // In JDK, this would NOT match "CAFÉ" without UNICODE_CASE.
    Pattern p = Pattern.compile("café", Pattern.CASE_INSENSITIVE);
    assertThat(p.matcher("CAFÉ").matches()).isTrue();
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
  void unicodeCaseWithoutCaseInsensitiveEnablesFolding() {
    // SafeRE divergence: UNICODE_CASE alone enables case folding (because it maps to FOLD_CASE
    // internally). In JDK, UNICODE_CASE without CASE_INSENSITIVE has no effect on matching.
    Pattern p = Pattern.compile("hello", Pattern.UNICODE_CASE);
    assertThat(p.matcher("HELLO").matches()).isTrue();
    assertThat(p.matcher("hello").matches()).isTrue();
  }

  @Test
  void inlineFlagCaseInsensitive() {
    // (?i) enables case insensitive matching. SafeRE always does Unicode folding.
    Pattern p = Pattern.compile("(?i)café");
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
