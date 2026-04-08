// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for JDK-compatible Unicode property syntax in {@code \p{...}} and {@code \P{...}}.
 *
 * <p>Covers: {@code Is} prefix (scripts, categories, binary properties), {@code In} prefix
 * (blocks), and keyword forms ({@code script=}, {@code block=}, {@code general_category=} and their
 * short forms).
 */
class UnicodePropertySyntaxTest {

  // ---- Helper methods ----

  /** Returns true if the regex matches anywhere in the input. */
  private static boolean find(String regex, String input) {
    return Pattern.compile(regex).matcher(input).find();
  }

  /** Returns true if the regex matches the entire input. */
  private static boolean matches(String regex, String input) {
    return Pattern.compile(regex).matcher(input).matches();
  }

  /** Cross-validates against JDK: asserts SafeRE and JDK agree on find(). */
  private static void assertMatchesJdk(String regex, String input) {
    boolean safere = find(regex, input);
    boolean jdk = java.util.regex.Pattern.compile(regex).matcher(input).find();
    assertThat(safere)
        .as("SafeRE and JDK should agree for %s on \"%s\"", regex, input)
        .isEqualTo(jdk);
  }

  // =========================================================================
  // Is prefix — scripts
  // =========================================================================

  @Nested
  @DisplayName("\\p{IsScript} — Is prefix for scripts")
  class IsScriptTest {

    @Test
    @DisplayName("\\p{IsLatin} matches Latin letters")
    void isLatinMatchesLatinLetters() {
      assertThat(find("\\p{IsLatin}", "A")).isTrue();
      assertThat(find("\\p{IsLatin}", "z")).isTrue();
      assertThat(find("\\p{IsLatin}", "é")).isTrue();
    }

    @Test
    @DisplayName("\\p{IsLatin} does not match non-Latin characters")
    void isLatinDoesNotMatchNonLatin() {
      assertThat(find("\\p{IsLatin}", "α")).isFalse(); // Greek
      assertThat(find("\\p{IsLatin}", "Б")).isFalse(); // Cyrillic
      assertThat(find("\\p{IsLatin}", "中")).isFalse(); // Han
    }

    @Test
    @DisplayName("\\p{IsGreek} matches Greek letters")
    void isGreekMatchesGreekLetters() {
      assertThat(find("\\p{IsGreek}", "α")).isTrue();
      assertThat(find("\\p{IsGreek}", "Ω")).isTrue();
      assertThat(find("\\p{IsGreek}", "A")).isFalse();
    }

    @Test
    @DisplayName("\\p{IsCyrillic} matches Cyrillic letters")
    void isCyrillicMatchesCyrillicLetters() {
      assertThat(find("\\p{IsCyrillic}", "Б")).isTrue();
      assertThat(find("\\p{IsCyrillic}", "A")).isFalse();
    }

    @Test
    @DisplayName("\\p{IsArabic} matches Arabic characters")
    void isArabicMatchesArabicCharacters() {
      assertThat(find("\\p{IsArabic}", "\u0627")).isTrue(); // Arabic Alef
      assertThat(find("\\p{IsArabic}", "A")).isFalse();
    }

    @Test
    @DisplayName("\\p{IsHan} matches Han/CJK characters")
    void isHanMatchesHanCharacters() {
      assertThat(find("\\p{IsHan}", "中")).isTrue();
      assertThat(find("\\p{IsHan}", "A")).isFalse();
    }

    @Test
    @DisplayName("Is prefix is case-insensitive for the script name")
    void isPrefixCaseInsensitiveForScriptName() {
      assertThat(find("\\p{IsLATIN}", "A")).isTrue();
      assertThat(find("\\p{Islatin}", "A")).isTrue();
      assertThat(find("\\p{IsLatin}", "A")).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
      "\\p{IsLatin}, A",
      "\\p{IsLatin}, z",
      "\\p{IsGreek}, α",
      "\\p{IsCyrillic}, Б",
      "\\p{IsLATIN}, A",
      "\\p{Islatin}, A",
    })
    @DisplayName("Is prefix scripts cross-validate against JDK")
    void isScriptCrossValidation(String regex, String input) {
      assertMatchesJdk(regex, input);
    }
  }

  // =========================================================================
  // Is prefix — categories
  // =========================================================================

  @Nested
  @DisplayName("\\p{IsCategory} — Is prefix for general categories")
  class IsCategoryTest {

    @Test
    @DisplayName("\\p{IsL} matches letters")
    void isLMatchesLetters() {
      assertThat(find("\\p{IsL}", "A")).isTrue();
      assertThat(find("\\p{IsL}", "1")).isFalse();
    }

    @Test
    @DisplayName("\\p{IsLu} matches uppercase letters")
    void isLuMatchesUppercase() {
      assertThat(find("\\p{IsLu}", "A")).isTrue();
      assertThat(find("\\p{IsLu}", "a")).isFalse();
    }

    @Test
    @DisplayName("\\p{IsLl} matches lowercase letters")
    void isLlMatchesLowercase() {
      assertThat(find("\\p{IsLl}", "a")).isTrue();
      assertThat(find("\\p{IsLl}", "A")).isFalse();
    }

    @Test
    @DisplayName("\\p{IsN} matches numbers")
    void isNMatchesNumbers() {
      assertThat(find("\\p{IsN}", "5")).isTrue();
      assertThat(find("\\p{IsN}", "A")).isFalse();
    }

    @Test
    @DisplayName("\\p{IsNd} matches decimal digits")
    void isNdMatchesDecimalDigits() {
      assertThat(find("\\p{IsNd}", "5")).isTrue();
      assertThat(find("\\p{IsNd}", "A")).isFalse();
    }

    @Test
    @DisplayName("\\p{IsP} matches punctuation")
    void isPMatchesPunctuation() {
      assertThat(find("\\p{IsP}", ".")).isTrue();
      assertThat(find("\\p{IsP}", "A")).isFalse();
    }

    @Test
    @DisplayName("\\p{IsS} matches symbols")
    void isSMatchesSymbols() {
      assertThat(find("\\p{IsS}", "$")).isTrue();
      assertThat(find("\\p{IsS}", "A")).isFalse();
    }

    @Test
    @DisplayName("\\p{IsZ} matches separators")
    void isZMatchesSeparators() {
      assertThat(find("\\p{IsZ}", " ")).isTrue();
      assertThat(find("\\p{IsZ}", "A")).isFalse();
    }

    @Test
    @DisplayName("\\p{IsC} matches control/other characters")
    void isCMatchesControlChars() {
      assertThat(find("\\p{IsC}", "\u0000")).isTrue();
      assertThat(find("\\p{IsC}", "A")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
      "\\p{IsL}, A",
      "\\p{IsLu}, A",
      "\\p{IsLl}, a",
      "\\p{IsN}, 5",
      "\\p{IsNd}, 5",
      "\\p{IsP}, .",
    })
    @DisplayName("Is prefix categories cross-validate against JDK")
    void isCategoryCrossValidation(String regex, String input) {
      assertMatchesJdk(regex, input);
    }
  }

  // =========================================================================
  // Is prefix — binary properties
  // =========================================================================

  @Nested
  @DisplayName("\\p{IsBinaryProperty} — binary properties")
  class BinaryPropertyTest {

    @Test
    @DisplayName("\\p{IsAlphabetic}")
    void isAlphabetic() {
      assertThat(find("\\p{IsAlphabetic}", "A")).isTrue();
      assertThat(find("\\p{IsAlphabetic}", "1")).isFalse();
      assertThat(find("\\p{IsAlphabetic}", ".")).isFalse();
      assertMatchesJdk("\\p{IsAlphabetic}", "A");
      assertMatchesJdk("\\p{IsAlphabetic}", "1");
    }

    @Test
    @DisplayName("\\p{IsLetter}")
    void isLetter() {
      assertThat(find("\\p{IsLetter}", "A")).isTrue();
      assertThat(find("\\p{IsLetter}", "1")).isFalse();
      assertMatchesJdk("\\p{IsLetter}", "A");
      assertMatchesJdk("\\p{IsLetter}", "1");
    }

    @Test
    @DisplayName("\\p{IsLowercase}")
    void isLowercase() {
      assertThat(find("\\p{IsLowercase}", "a")).isTrue();
      assertThat(find("\\p{IsLowercase}", "A")).isFalse();
      assertMatchesJdk("\\p{IsLowercase}", "a");
      assertMatchesJdk("\\p{IsLowercase}", "A");
    }

    @Test
    @DisplayName("\\p{IsUppercase}")
    void isUppercase() {
      assertThat(find("\\p{IsUppercase}", "A")).isTrue();
      assertThat(find("\\p{IsUppercase}", "a")).isFalse();
      assertMatchesJdk("\\p{IsUppercase}", "A");
      assertMatchesJdk("\\p{IsUppercase}", "a");
    }

    @Test
    @DisplayName("\\p{IsTitlecase}")
    void isTitlecase() {
      assertThat(find("\\p{IsTitlecase}", "\u01C5")).isTrue(); // Dz with caron
      assertThat(find("\\p{IsTitlecase}", "A")).isFalse();
      assertMatchesJdk("\\p{IsTitlecase}", "\u01C5");
    }

    @Test
    @DisplayName("\\p{IsDigit}")
    void isDigit() {
      assertThat(find("\\p{IsDigit}", "5")).isTrue();
      assertThat(find("\\p{IsDigit}", "A")).isFalse();
      assertMatchesJdk("\\p{IsDigit}", "5");
      assertMatchesJdk("\\p{IsDigit}", "A");
    }

    @Test
    @DisplayName("\\p{IsHex_Digit}")
    void isHexDigit() {
      assertThat(find("\\p{IsHex_Digit}", "A")).isTrue();
      assertThat(find("\\p{IsHex_Digit}", "f")).isTrue();
      assertThat(find("\\p{IsHex_Digit}", "0")).isTrue();
      assertThat(find("\\p{IsHex_Digit}", "G")).isFalse();
      assertMatchesJdk("\\p{IsHex_Digit}", "A");
      assertMatchesJdk("\\p{IsHex_Digit}", "G");
    }

    @Test
    @DisplayName("\\p{IsWhite_Space}")
    void isWhiteSpace() {
      assertThat(find("\\p{IsWhite_Space}", " ")).isTrue();
      assertThat(find("\\p{IsWhite_Space}", "\t")).isTrue();
      assertThat(find("\\p{IsWhite_Space}", "\n")).isTrue();
      assertThat(find("\\p{IsWhite_Space}", "\u00A0")).isTrue(); // NBSP
      assertThat(find("\\p{IsWhite_Space}", "A")).isFalse();
      assertMatchesJdk("\\p{IsWhite_Space}", " ");
      assertMatchesJdk("\\p{IsWhite_Space}", "\u00A0");
    }

    @Test
    @DisplayName("\\p{IsPunctuation}")
    void isPunctuation() {
      assertThat(find("\\p{IsPunctuation}", ".")).isTrue();
      assertThat(find("\\p{IsPunctuation}", ",")).isTrue();
      assertThat(find("\\p{IsPunctuation}", "(")).isTrue();
      assertThat(find("\\p{IsPunctuation}", "A")).isFalse();
      assertMatchesJdk("\\p{IsPunctuation}", ".");
      assertMatchesJdk("\\p{IsPunctuation}", "A");
    }

    @Test
    @DisplayName("\\p{IsControl}")
    void isControl() {
      assertThat(find("\\p{IsControl}", "\u0001")).isTrue();
      assertThat(find("\\p{IsControl}", "\u0000")).isTrue();
      assertThat(find("\\p{IsControl}", "A")).isFalse();
      assertMatchesJdk("\\p{IsControl}", "\u0001");
      assertMatchesJdk("\\p{IsControl}", "A");
    }

    @Test
    @DisplayName("\\p{IsIdeographic}")
    void isIdeographic() {
      assertThat(find("\\p{IsIdeographic}", "\u4E00")).isTrue(); // CJK
      assertThat(find("\\p{IsIdeographic}", "A")).isFalse();
      assertMatchesJdk("\\p{IsIdeographic}", "\u4E00");
    }

    @Test
    @DisplayName("\\p{IsJoin_Control}")
    void isJoinControl() {
      assertThat(find("\\p{IsJoin_Control}", "\u200C")).isTrue(); // ZWNJ
      assertThat(find("\\p{IsJoin_Control}", "\u200D")).isTrue(); // ZWJ
      assertThat(find("\\p{IsJoin_Control}", "A")).isFalse();
      assertMatchesJdk("\\p{IsJoin_Control}", "\u200C");
    }

    @Test
    @DisplayName("\\p{IsNoncharacter_Code_Point}")
    void isNoncharacterCodePoint() {
      assertThat(find("\\p{IsNoncharacter_Code_Point}", "\uFDD0")).isTrue();
      assertThat(find("\\p{IsNoncharacter_Code_Point}", "\uFDEF")).isTrue();
      assertThat(find("\\p{IsNoncharacter_Code_Point}", "\uFFFE")).isTrue();
      assertThat(find("\\p{IsNoncharacter_Code_Point}", "A")).isFalse();
      assertMatchesJdk("\\p{IsNoncharacter_Code_Point}", "\uFDD0");
    }

    @Test
    @DisplayName("\\p{IsAssigned}")
    void isAssigned() {
      assertThat(find("\\p{IsAssigned}", "A")).isTrue();
      assertMatchesJdk("\\p{IsAssigned}", "A");
    }

    @Test
    @DisplayName("\\p{IsEmoji}")
    void isEmoji() {
      // U+1F600 GRINNING FACE
      String grin = new String(Character.toChars(0x1F600));
      assertThat(find("\\p{IsEmoji}", grin)).isTrue();
      assertMatchesJdk("\\p{IsEmoji}", grin);
    }

    @Test
    @DisplayName("\\p{IsEmoji_Presentation}")
    void isEmojiPresentation() {
      String grin = new String(Character.toChars(0x1F600));
      assertThat(find("\\p{IsEmoji_Presentation}", grin)).isTrue();
      assertMatchesJdk("\\p{IsEmoji_Presentation}", grin);
    }

    @Test
    @DisplayName("\\p{IsExtended_Pictographic}")
    void isExtendedPictographic() {
      String grin = new String(Character.toChars(0x1F600));
      assertThat(find("\\p{IsExtended_Pictographic}", grin)).isTrue();
      assertMatchesJdk("\\p{IsExtended_Pictographic}", grin);
    }
  }

  // =========================================================================
  // In prefix — blocks
  // =========================================================================

  @Nested
  @DisplayName("\\p{InBlock} — In prefix for Unicode blocks")
  class InBlockTest {

    @Test
    @DisplayName("\\p{InBasicLatin} matches ASCII range")
    void inBasicLatinMatchesAscii() {
      assertThat(find("\\p{InBasicLatin}", "A")).isTrue();
      assertThat(find("\\p{InBasicLatin}", "z")).isTrue();
      assertThat(find("\\p{InBasicLatin}", "0")).isTrue();
      assertThat(find("\\p{InBasicLatin}", " ")).isTrue();
    }

    @Test
    @DisplayName("\\p{InBasicLatin} does not match non-ASCII")
    void inBasicLatinDoesNotMatchNonAscii() {
      assertThat(find("\\p{InBasicLatin}", "é")).isFalse();
      assertThat(find("\\p{InBasicLatin}", "α")).isFalse();
    }

    @Test
    @DisplayName("\\p{InGreek} matches Greek and Coptic block")
    void inGreekMatchesGreekBlock() {
      assertThat(find("\\p{InGreek}", "α")).isTrue();
      assertThat(find("\\p{InGreek}", "A")).isFalse();
    }

    @Test
    @DisplayName("\\p{InGreekandCoptic} matches Greek and Coptic block")
    void inGreekAndCopticMatchesGreekBlock() {
      assertThat(find("\\p{InGreekandCoptic}", "α")).isTrue();
      assertMatchesJdk("\\p{InGreekandCoptic}", "α");
    }

    @Test
    @DisplayName("\\p{InCyrillic} matches Cyrillic block")
    void inCyrillicMatchesCyrillicBlock() {
      assertThat(find("\\p{InCyrillic}", "Б")).isTrue();
      assertThat(find("\\p{InCyrillic}", "A")).isFalse();
    }

    @Test
    @DisplayName("In prefix is case-insensitive for the block name")
    void inPrefixCaseInsensitive() {
      assertThat(find("\\p{InBASICLATIN}", "A")).isTrue();
      assertThat(find("\\p{InBASIC_LATIN}", "A")).isTrue();
      assertThat(find("\\p{Inbasiclatin}", "A")).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
      "\\p{InBasicLatin}, A",
      "\\p{InBasicLatin}, z",
      "\\p{InCyrillic}, Б",
      "\\p{InBASICLATIN}, A",
      "\\p{Inbasiclatin}, A",
    })
    @DisplayName("In prefix blocks cross-validate against JDK")
    void inBlockCrossValidation(String regex, String input) {
      assertMatchesJdk(regex, input);
    }
  }

  // =========================================================================
  // Keyword forms — script= / sc=
  // =========================================================================

  @Nested
  @DisplayName("\\p{script=X} and \\p{sc=X} — keyword forms for scripts")
  class ScriptKeywordTest {

    @Test
    @DisplayName("\\p{script=Latin} matches Latin characters")
    void scriptEqualsLatin() {
      assertThat(find("\\p{script=Latin}", "A")).isTrue();
      assertThat(find("\\p{script=Latin}", "α")).isFalse();
      assertMatchesJdk("\\p{script=Latin}", "A");
      assertMatchesJdk("\\p{script=Latin}", "α");
    }

    @Test
    @DisplayName("\\p{sc=Latin} matches Latin characters")
    void scEqualsLatin() {
      assertThat(find("\\p{sc=Latin}", "A")).isTrue();
      assertThat(find("\\p{sc=Latin}", "α")).isFalse();
      assertMatchesJdk("\\p{sc=Latin}", "A");
    }

    @Test
    @DisplayName("\\p{sc=Greek} matches Greek characters")
    void scEqualsGreek() {
      assertThat(find("\\p{sc=Greek}", "α")).isTrue();
      assertThat(find("\\p{sc=Greek}", "A")).isFalse();
      assertMatchesJdk("\\p{sc=Greek}", "α");
    }

    @Test
    @DisplayName("Script keyword is case-insensitive for both key and value")
    void scriptKeywordCaseInsensitive() {
      assertThat(find("\\p{SCRIPT=Latin}", "A")).isTrue();
      assertThat(find("\\p{Script=Latin}", "A")).isTrue();
      assertThat(find("\\p{script=latin}", "A")).isTrue();
      assertThat(find("\\p{SC=Latin}", "A")).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
      "\\p{SCRIPT=Latin}, A",
      "\\p{script=latin}, A",
      "\\p{SC=Latin}, A",
    })
    @DisplayName("Script keyword cross-validates against JDK")
    void scriptKeywordCrossValidation(String regex, String input) {
      assertMatchesJdk(regex, input);
    }
  }

  // =========================================================================
  // Keyword forms — block= / blk=
  // =========================================================================

  @Nested
  @DisplayName("\\p{block=X} and \\p{blk=X} — keyword forms for blocks")
  class BlockKeywordTest {

    @Test
    @DisplayName("\\p{block=BasicLatin} matches ASCII")
    void blockEqualsBasicLatin() {
      assertThat(find("\\p{block=BasicLatin}", "A")).isTrue();
      assertThat(find("\\p{block=BasicLatin}", "é")).isFalse();
      assertMatchesJdk("\\p{block=BasicLatin}", "A");
    }

    @Test
    @DisplayName("\\p{blk=BasicLatin} matches ASCII")
    void blkEqualsBasicLatin() {
      assertThat(find("\\p{blk=BasicLatin}", "A")).isTrue();
      assertMatchesJdk("\\p{blk=BasicLatin}", "A");
    }

    @Test
    @DisplayName("\\p{block=GreekandCoptic} matches Greek block")
    void blockEqualsGreek() {
      assertThat(find("\\p{block=GreekandCoptic}", "α")).isTrue();
      assertMatchesJdk("\\p{block=GreekandCoptic}", "α");
    }

    @Test
    @DisplayName("Block keyword is case-insensitive")
    void blockKeywordCaseInsensitive() {
      assertThat(find("\\p{BLOCK=BasicLatin}", "A")).isTrue();
      assertThat(find("\\p{Block=BasicLatin}", "A")).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
      "\\p{block=BasicLatin}, A",
      "\\p{blk=BasicLatin}, A",
      "\\p{BLOCK=BasicLatin}, A",
      "\\p{block=GreekandCoptic}, α",
      "\\p{blk=GreekandCoptic}, α",
    })
    @DisplayName("Block keyword cross-validates against JDK")
    void blockKeywordCrossValidation(String regex, String input) {
      assertMatchesJdk(regex, input);
    }
  }

  // =========================================================================
  // Keyword forms — general_category= / gc=
  // =========================================================================

  @Nested
  @DisplayName("\\p{general_category=X} and \\p{gc=X}")
  class CategoryKeywordTest {

    @Test
    @DisplayName("\\p{general_category=Lu} matches uppercase letters")
    void gcLu() {
      assertThat(find("\\p{general_category=Lu}", "A")).isTrue();
      assertThat(find("\\p{general_category=Lu}", "a")).isFalse();
      assertMatchesJdk("\\p{general_category=Lu}", "A");
      assertMatchesJdk("\\p{general_category=Lu}", "a");
    }

    @Test
    @DisplayName("\\p{gc=Lu} matches uppercase letters")
    void gcShortLu() {
      assertThat(find("\\p{gc=Lu}", "A")).isTrue();
      assertThat(find("\\p{gc=Lu}", "a")).isFalse();
      assertMatchesJdk("\\p{gc=Lu}", "A");
    }

    @Test
    @DisplayName("\\p{gc=Ll} matches lowercase letters")
    void gcLl() {
      assertThat(find("\\p{gc=Ll}", "a")).isTrue();
      assertThat(find("\\p{gc=Ll}", "A")).isFalse();
      assertMatchesJdk("\\p{gc=Ll}", "a");
    }

    @Test
    @DisplayName("\\p{gc=L} matches all letters")
    void gcL() {
      assertThat(find("\\p{gc=L}", "A")).isTrue();
      assertThat(find("\\p{gc=L}", "a")).isTrue();
      assertThat(find("\\p{gc=L}", "1")).isFalse();
      assertMatchesJdk("\\p{gc=L}", "A");
    }

    @Test
    @DisplayName("\\p{gc=Nd} matches decimal digits")
    void gcNd() {
      assertThat(find("\\p{gc=Nd}", "5")).isTrue();
      assertThat(find("\\p{gc=Nd}", "A")).isFalse();
      assertMatchesJdk("\\p{gc=Nd}", "5");
    }

    @Test
    @DisplayName("Category keyword is case-insensitive for the key")
    void categoryKeywordCaseInsensitive() {
      assertThat(find("\\p{GENERAL_CATEGORY=Lu}", "A")).isTrue();
      assertThat(find("\\p{General_Category=Lu}", "A")).isTrue();
      assertThat(find("\\p{GC=Lu}", "A")).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
      "\\p{general_category=Lu}, A",
      "\\p{GENERAL_CATEGORY=Lu}, A",
      "\\p{gc=Lu}, A",
      "\\p{GC=Lu}, A",
      "\\p{gc=Ll}, a",
      "\\p{gc=L}, A",
      "\\p{gc=Nd}, 5",
    })
    @DisplayName("Category keyword cross-validates against JDK")
    void categoryKeywordCrossValidation(String regex, String input) {
      assertMatchesJdk(regex, input);
    }
  }

  // =========================================================================
  // Negation — \P{...} and \p{^...}
  // =========================================================================

  @Nested
  @DisplayName("Negation with \\P{} and \\p{^}")
  class NegationTest {

    @Test
    @DisplayName("\\P{IsLatin} matches non-Latin characters")
    void negatedIsLatin() {
      assertThat(find("\\P{IsLatin}", "α")).isTrue();
      assertThat(find("\\P{IsLatin}", "A")).isFalse();
      assertMatchesJdk("\\P{IsLatin}", "α");
      assertMatchesJdk("\\P{IsLatin}", "A");
    }

    @Test
    @DisplayName("\\P{InBasicLatin} matches non-ASCII characters")
    void negatedInBasicLatin() {
      assertThat(find("\\P{InBasicLatin}", "é")).isTrue();
      assertThat(find("\\P{InBasicLatin}", "A")).isFalse();
      assertMatchesJdk("\\P{InBasicLatin}", "é");
    }

    @Test
    @DisplayName("\\P{script=Latin} matches non-Latin characters")
    void negatedScriptKeyword() {
      assertThat(find("\\P{script=Latin}", "α")).isTrue();
      assertThat(find("\\P{script=Latin}", "A")).isFalse();
      assertMatchesJdk("\\P{script=Latin}", "α");
    }

    @Test
    @DisplayName("\\P{gc=Lu} matches non-uppercase characters")
    void negatedGcKeyword() {
      assertThat(find("\\P{gc=Lu}", "a")).isTrue();
      assertThat(find("\\P{gc=Lu}", "A")).isFalse();
      assertMatchesJdk("\\P{gc=Lu}", "a");
    }

    @Test
    @DisplayName("\\p{^IsLatin} negation via caret")
    void caretNegation() {
      assertThat(find("\\p{^IsLatin}", "α")).isTrue();
      assertThat(find("\\p{^IsLatin}", "A")).isFalse();
    }

    @Test
    @DisplayName("\\P{IsAlphabetic} matches non-alphabetic characters")
    void negatedBinaryProperty() {
      assertThat(find("\\P{IsAlphabetic}", "1")).isTrue();
      assertThat(find("\\P{IsAlphabetic}", "A")).isFalse();
      assertMatchesJdk("\\P{IsAlphabetic}", "1");
    }
  }

  // =========================================================================
  // Error cases
  // =========================================================================

  @Nested
  @DisplayName("Error cases")
  class ErrorCaseTest {

    @Test
    @DisplayName("lowercase 'is' prefix is not recognized")
    void lowercaseIsPrefix() {
      // "isLatin" doesn't match any script/category, "is" is not a recognized prefix.
      assertThatThrownBy(() -> Pattern.compile("\\p{isLatin}"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    @DisplayName("lowercase 'in' prefix is not recognized")
    void lowercaseInPrefix() {
      assertThatThrownBy(() -> Pattern.compile("\\p{inBasicLatin}"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    @DisplayName("Invalid script name throws")
    void invalidScriptName() {
      assertThatThrownBy(() -> Pattern.compile("\\p{IsNotARealScript}"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    @DisplayName("Invalid block name throws")
    void invalidBlockName() {
      assertThatThrownBy(() -> Pattern.compile("\\p{InNotARealBlock}"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    @DisplayName("Invalid binary property name throws")
    void invalidBinaryProperty() {
      assertThatThrownBy(() -> Pattern.compile("\\p{IsNotAProperty}"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    @DisplayName("Invalid keyword key throws")
    void invalidKeywordKey() {
      assertThatThrownBy(() -> Pattern.compile("\\p{foo=bar}"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    @DisplayName("Invalid keyword value throws")
    void invalidKeywordValue() {
      assertThatThrownBy(() -> Pattern.compile("\\p{script=NotAScript}"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    @DisplayName("Invalid block keyword value throws")
    void invalidBlockKeywordValue() {
      assertThatThrownBy(() -> Pattern.compile("\\p{block=NotABlock}"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    @DisplayName("Invalid gc keyword value throws")
    void invalidGcKeywordValue() {
      assertThatThrownBy(() -> Pattern.compile("\\p{gc=XX}"))
          .isInstanceOf(PatternSyntaxException.class);
    }
  }

  // =========================================================================
  // In character class context — [...\p{...}...]
  // =========================================================================

  @Nested
  @DisplayName("Unicode properties inside character classes")
  class InCharClassTest {

    @Test
    @DisplayName("[\\p{IsLatin}] in character class")
    void isLatinInCharClass() {
      assertThat(find("[\\p{IsLatin}]", "A")).isTrue();
      assertThat(find("[\\p{IsLatin}]", "α")).isFalse();
    }

    @Test
    @DisplayName("[\\p{InBasicLatin}] in character class")
    void blockInCharClass() {
      assertThat(find("[\\p{InBasicLatin}]", "A")).isTrue();
      assertThat(find("[\\p{InBasicLatin}]", "é")).isFalse();
    }

    @Test
    @DisplayName("[\\p{IsAlphabetic}\\p{IsDigit}] union")
    void binaryPropertyUnion() {
      assertThat(find("[\\p{IsAlphabetic}\\p{IsDigit}]", "A")).isTrue();
      assertThat(find("[\\p{IsAlphabetic}\\p{IsDigit}]", "5")).isTrue();
      assertThat(find("[\\p{IsAlphabetic}\\p{IsDigit}]", ".")).isFalse();
    }
  }

  // =========================================================================
  // Backward compatibility — existing \p{...} forms still work
  // =========================================================================

  @Nested
  @DisplayName("Backward compatibility with existing \\p{} forms")
  class BackwardCompatibilityTest {

    @Test
    @DisplayName("\\p{Latin} still works (no prefix)")
    void directScriptName() {
      assertThat(find("\\p{Latin}", "A")).isTrue();
      assertThat(find("\\p{Latin}", "α")).isFalse();
    }

    @Test
    @DisplayName("\\p{Lu} still works (no prefix)")
    void directCategoryName() {
      assertThat(find("\\p{Lu}", "A")).isTrue();
      assertThat(find("\\p{Lu}", "a")).isFalse();
    }

    @Test
    @DisplayName("\\p{L} still works (no prefix)")
    void directMajorCategory() {
      assertThat(find("\\p{L}", "A")).isTrue();
      assertThat(find("\\p{L}", "1")).isFalse();
    }

    @Test
    @DisplayName("\\p{Braille} still works")
    void directBraille() {
      assertThat(find("\\p{Braille}", "\u2800")).isTrue();
      assertThat(find("\\p{Braille}", "A")).isFalse();
    }

    @Test
    @DisplayName("\\pL single-char syntax still works")
    void singleCharSyntax() {
      assertThat(find("\\pL", "A")).isTrue();
      assertThat(find("\\pL", "1")).isFalse();
    }

    @Test
    @DisplayName("\\P{Lu} negation still works")
    void directNegation() {
      assertThat(find("\\P{Lu}", "a")).isTrue();
      assertThat(find("\\P{Lu}", "A")).isFalse();
    }

    @Test
    @DisplayName("\\p{javaLowerCase} still works")
    void javaCharacterClass() {
      assertThat(find("\\p{javaLowerCase}", "a")).isTrue();
      assertThat(find("\\p{javaLowerCase}", "A")).isFalse();
    }

    @Test
    @DisplayName("\\p{Any} still works")
    void anyClass() {
      assertThat(find("\\p{Any}", "A")).isTrue();
      assertThat(find("\\p{Any}", "α")).isTrue();
    }
  }
}
