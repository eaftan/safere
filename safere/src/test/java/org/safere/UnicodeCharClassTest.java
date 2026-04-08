// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Pattern#UNICODE_CHARACTER_CLASS} flag behavior. When this flag is set,
 * {@code \d}, {@code \w}, {@code \s}, and {@code \b} use Unicode definitions instead of
 * ASCII-only.
 */
class UnicodeCharClassTest {

  private static final int UCC = Pattern.UNICODE_CHARACTER_CLASS;

  // -------------------------------------------------------------------------
  // \d — Unicode digit (category Nd)
  // -------------------------------------------------------------------------

  @Nested
  class DigitTests {

    @Test
    void asciiDigitsMatchWithAndWithoutFlag() {
      Pattern pAscii = Pattern.compile("\\d+");
      Pattern pUnicode = Pattern.compile("\\d+", UCC);
      assertThat(pAscii.matcher("12345").matches()).isTrue();
      assertThat(pUnicode.matcher("12345").matches()).isTrue();
    }

    @Test
    void arabicIndicDigitsMatchWithFlag() {
      // Arabic-Indic digits: ٠١٢٣ (U+0660–U+0663)
      Pattern p = Pattern.compile("\\d+", UCC);
      assertThat(p.matcher("\u0660\u0661\u0662\u0663").matches()).isTrue();
    }

    @Test
    void arabicIndicDigitsDoNotMatchWithoutFlag() {
      Pattern p = Pattern.compile("\\d+");
      assertThat(p.matcher("\u0660\u0661\u0662\u0663").matches()).isFalse();
    }

    @Test
    void fullwidthDigitsMatchWithFlag() {
      // Fullwidth digits: ０１２ (U+FF10–U+FF12)
      Pattern p = Pattern.compile("\\d+", UCC);
      assertThat(p.matcher("\uFF10\uFF11\uFF12").matches()).isTrue();
    }

    @Test
    void fullwidthDigitsDoNotMatchWithoutFlag() {
      Pattern p = Pattern.compile("\\d+");
      assertThat(p.matcher("\uFF10\uFF11\uFF12").matches()).isFalse();
    }

    @Test
    void devanagariDigitsMatchWithFlag() {
      // Devanagari digits: ०१२ (U+0966–U+0968)
      Pattern p = Pattern.compile("\\d+", UCC);
      assertThat(p.matcher("\u0966\u0967\u0968").matches()).isTrue();
    }

    @Test
    void superscriptDigitsDoNotMatch() {
      // Superscript ² (U+00B2) is NOT Unicode Nd category — it's No.
      Pattern p = Pattern.compile("\\d", UCC);
      assertThat(p.matcher("\u00B2").matches()).isFalse();
    }

    @Test
    void negatedUnicodeDigit() {
      Pattern p = Pattern.compile("\\D+", UCC);
      // Latin letters should match \D
      assertThat(p.matcher("abc").matches()).isTrue();
      // Arabic-Indic digits should NOT match \D
      assertThat(p.matcher("\u0660").matches()).isFalse();
    }
  }

  // -------------------------------------------------------------------------
  // \s — Unicode whitespace (White_Space property)
  // -------------------------------------------------------------------------

  @Nested
  class SpaceTests {

    @Test
    void asciiWhitespaceMatchesWithAndWithoutFlag() {
      Pattern pAscii = Pattern.compile("\\s");
      Pattern pUnicode = Pattern.compile("\\s", UCC);
      assertThat(pAscii.matcher(" ").matches()).isTrue();
      assertThat(pUnicode.matcher(" ").matches()).isTrue();
      assertThat(pAscii.matcher("\t").matches()).isTrue();
      assertThat(pUnicode.matcher("\t").matches()).isTrue();
    }

    @Test
    void noBreakSpaceMatchesWithFlag() {
      // NBSP (U+00A0) is Unicode White_Space.
      Pattern p = Pattern.compile("\\s", UCC);
      assertThat(p.matcher("\u00A0").matches()).isTrue();
    }

    @Test
    void noBreakSpaceDoesNotMatchWithoutFlag() {
      Pattern p = Pattern.compile("\\s");
      assertThat(p.matcher("\u00A0").matches()).isFalse();
    }

    @Test
    void emSpaceMatchesWithFlag() {
      // Em space (U+2003) is Unicode White_Space.
      Pattern p = Pattern.compile("\\s", UCC);
      assertThat(p.matcher("\u2003").matches()).isTrue();
    }

    @Test
    void nextLineMatchesWithFlag() {
      // NEL (U+0085) is Unicode White_Space.
      Pattern p = Pattern.compile("\\s", UCC);
      assertThat(p.matcher("\u0085").matches()).isTrue();
    }

    @Test
    void lineSeparatorMatchesWithFlag() {
      // Line separator (U+2028) is Unicode White_Space.
      Pattern p = Pattern.compile("\\s", UCC);
      assertThat(p.matcher("\u2028").matches()).isTrue();
    }

    @Test
    void paragraphSeparatorMatchesWithFlag() {
      // Paragraph separator (U+2029) is Unicode White_Space.
      Pattern p = Pattern.compile("\\s", UCC);
      assertThat(p.matcher("\u2029").matches()).isTrue();
    }

    @Test
    void zeroWidthSpaceDoesNotMatch() {
      // Zero-width space (U+200B) is NOT Unicode White_Space.
      Pattern p = Pattern.compile("\\s", UCC);
      assertThat(p.matcher("\u200B").matches()).isFalse();
    }

    @Test
    void negatedUnicodeSpace() {
      Pattern p = Pattern.compile("\\S+", UCC);
      assertThat(p.matcher("abc").matches()).isTrue();
      // NBSP should NOT match \S
      assertThat(p.matcher("\u00A0").matches()).isFalse();
    }
  }

  // -------------------------------------------------------------------------
  // \w — Unicode word character (L + M + Nd + Nl + Pc + Join_Control)
  // -------------------------------------------------------------------------

  @Nested
  class WordTests {

    @Test
    void asciiWordCharsMatchWithAndWithoutFlag() {
      Pattern pAscii = Pattern.compile("\\w+");
      Pattern pUnicode = Pattern.compile("\\w+", UCC);
      assertThat(pAscii.matcher("abc123_").matches()).isTrue();
      assertThat(pUnicode.matcher("abc123_").matches()).isTrue();
    }

    @Test
    void accentedLettersMatchWithFlag() {
      // é (U+00E9) is category L — should match Unicode \w.
      Pattern p = Pattern.compile("\\w+", UCC);
      assertThat(p.matcher("café").matches()).isTrue();
      assertThat(p.matcher("naïve").matches()).isTrue();
    }

    @Test
    void accentedLettersDoNotMatchWithoutFlag() {
      Pattern p = Pattern.compile("\\w+");
      // Without UCC, \w is [A-Za-z0-9_], so 'é' doesn't match.
      assertThat(p.matcher("é").matches()).isFalse();
    }

    @Test
    void cyrillicMatchesWithFlag() {
      Pattern p = Pattern.compile("\\w+", UCC);
      assertThat(p.matcher("Привет").matches()).isTrue();
    }

    @Test
    void cjkMatchesWithFlag() {
      // CJK Unified Ideographs are category Lo (Letter, Other) — part of L.
      Pattern p = Pattern.compile("\\w+", UCC);
      assertThat(p.matcher("漢字").matches()).isTrue();
    }

    @Test
    void connectorPunctuationMatchesWithFlag() {
      // Undertie ‿ (U+203F) and ﹏ (U+FE4F) are category Pc.
      Pattern p = Pattern.compile("\\w", UCC);
      assertThat(p.matcher("\u203F").matches()).isTrue();
      assertThat(p.matcher("\uFE4F").matches()).isTrue();
    }

    @Test
    void hyphenDoesNotMatch() {
      // Hyphen-minus is NOT \w even with Unicode.
      Pattern p = Pattern.compile("\\w", UCC);
      assertThat(p.matcher("-").matches()).isFalse();
    }

    @Test
    void negatedUnicodeWord() {
      Pattern p = Pattern.compile("\\W+", UCC);
      // Spaces and punctuation should match \W
      assertThat(p.matcher(" !@#").matches()).isTrue();
      // Accented letters should NOT match \W
      assertThat(p.matcher("é").matches()).isFalse();
    }
  }

  // -------------------------------------------------------------------------
  // \b — Unicode word boundary
  // -------------------------------------------------------------------------

  @Nested
  class WordBoundaryTests {

    @Test
    void asciiWordBoundaryWorksBothWays() {
      Pattern pAscii = Pattern.compile("\\bword\\b");
      Pattern pUnicode = Pattern.compile("\\bword\\b", UCC);
      assertThat(pAscii.matcher("a word here").find()).isTrue();
      assertThat(pUnicode.matcher("a word here").find()).isTrue();
    }

    @Test
    void unicodeWordBoundaryWithAccentedWord() {
      // With UCC, \b should recognize accented characters as word chars.
      Pattern p = Pattern.compile("\\bcafé\\b", UCC);
      assertThat(p.matcher("a café here").find()).isTrue();
      assertThat(p.matcher("café").matches()).isTrue();
    }

    @Test
    void unicodeWordBoundaryDoesNotSplitAccentedWord() {
      // Without UCC, \b sees 'é' as a non-word char, so \bcaf\b matches inside "café".
      Pattern pAscii = Pattern.compile("\\bcaf\\b");
      assertThat(pAscii.matcher("café").find()).isTrue();

      // With UCC, 'é' is a word char so \bcaf\b should NOT match inside "café" —
      // there's no word boundary between 'f' and 'é'.
      Pattern pUnicode = Pattern.compile("\\bcaf\\b", UCC);
      assertThat(pUnicode.matcher("café").find()).isFalse();
    }

    @Test
    void unicodeWordBoundaryWithCyrillic() {
      Pattern p = Pattern.compile("\\bслово\\b", UCC);
      assertThat(p.matcher("это слово тут").find()).isTrue();
    }

    @Test
    void unicodeNonWordBoundary() {
      // \B is the negation of \b.
      Pattern p = Pattern.compile("\\Bfé\\B", UCC);
      // In "café", between 'a' and 'f' there's no boundary (\B matches),
      // and between 'é' and end... actually "café" at the end has a boundary.
      // Let's use a word that has \B in the middle.
      assertThat(p.matcher("caféine").find()).isTrue();
    }
  }

  // -------------------------------------------------------------------------
  // Combined behavior and edge cases
  // -------------------------------------------------------------------------

  @Nested
  class CombinedTests {

    @Test
    void findAllUnicodeDigits() {
      Pattern p = Pattern.compile("\\d+", UCC);
      Matcher m = p.matcher("price: ١٢٣ or 456");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("\u0661\u0662\u0663");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("456");
      assertThat(m.find()).isFalse();
    }

    @Test
    void replaceWithUnicodeCharClass() {
      Pattern p = Pattern.compile("\\s+", UCC);
      // Replace all whitespace including NBSP with a single space.
      String input = "hello\u00A0world\u2003here";
      assertThat(p.matcher(input).replaceAll(" ")).isEqualTo("hello world here");
    }

    @Test
    void splitOnUnicodeWhitespace() {
      Pattern p = Pattern.compile("\\s+", UCC);
      String input = "a\u00A0b\u2003c";
      String[] parts = p.split(input);
      assertThat(parts).containsExactly("a", "b", "c");
    }

    @Test
    void unicodeCharClassWithCaseInsensitive() {
      Pattern p = Pattern.compile("\\w+", UCC | Pattern.CASE_INSENSITIVE);
      assertThat(p.matcher("Café").matches()).isTrue();
      assertThat(p.matcher("CAFÉ").matches()).isTrue();
    }

    @Test
    void withoutFlagDigitIsAsciiOnly() {
      // Baseline: without UCC, \d matches only [0-9].
      Pattern p = Pattern.compile("^\\d+$");
      assertThat(p.matcher("123").matches()).isTrue();
      assertThat(p.matcher("\u0660").matches()).isFalse();
    }

    @Test
    void withoutFlagWordIsAsciiOnly() {
      // Baseline: without UCC, \w matches only [A-Za-z0-9_].
      Pattern p = Pattern.compile("^\\w+$");
      assertThat(p.matcher("abc123").matches()).isTrue();
      assertThat(p.matcher("café").matches()).isFalse();
    }

    @Test
    void withoutFlagSpaceIsAsciiOnly() {
      // Baseline: without UCC, \s matches only [ \t\n\r\f\x0B].
      Pattern p = Pattern.compile("\\s");
      assertThat(p.matcher(" ").matches()).isTrue();
      assertThat(p.matcher("\u00A0").matches()).isFalse();
    }
  }
}
