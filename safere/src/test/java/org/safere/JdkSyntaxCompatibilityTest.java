// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Systematic test coverage for every syntax feature documented in the JDK 21
 * {@link java.util.regex.Pattern} Javadoc.
 *
 * <p>For each feature, verifies that:
 * <ul>
 *   <li>JDK accepts the pattern (sanity check)
 *   <li>SafeRE compiles it and produces the same match result, OR
 *   <li>SafeRE intentionally rejects it (for features that violate linear-time guarantees)
 * </ul>
 *
 * <p>Refs: <a href="https://github.com/eaftan/safere/issues/131">#131</a>,
 * <a href="https://github.com/eaftan/safere/issues/127">#127</a>
 */
@DisplayName("JDK syntax compatibility")
class JdkSyntaxCompatibilityTest {

  // ---- Helpers ----

  /** Asserts SafeRE compiles the pattern without error. */
  private static void assertCompiles(String regex) {
    // Sanity: JDK must accept it too.
    assertThatNoException()
        .as("JDK should accept: %s", regex)
        .isThrownBy(() -> java.util.regex.Pattern.compile(regex));
    assertThatNoException()
        .as("SafeRE should accept: %s", regex)
        .isThrownBy(() -> Pattern.compile(regex));
  }

  /** Asserts SafeRE compiles and matches identically to JDK on the given input. */
  private static void assertMatchesSame(String regex, String input) {
    // Sanity: JDK must accept it.
    java.util.regex.Matcher jdkM = java.util.regex.Pattern.compile(regex).matcher(input);
    boolean jdkFound = jdkM.find();

    Matcher safeM = Pattern.compile(regex).matcher(input);
    boolean safeFound = safeM.find();

    assertThat(safeFound)
        .as("find() for /%s/ on \"%s\"", regex, input)
        .isEqualTo(jdkFound);

    if (jdkFound && safeFound) {
      assertThat(safeM.group())
          .as("group() for /%s/ on \"%s\"", regex, input)
          .isEqualTo(jdkM.group());
    }
  }

  /** Asserts SafeRE compiles and full-matches identically to JDK on the given input. */
  private static void assertMatchesFull(String regex, String input) {
    java.util.regex.Matcher jdkM = java.util.regex.Pattern.compile(regex).matcher(input);
    boolean jdkMatches = jdkM.matches();

    Matcher safeM = Pattern.compile(regex).matcher(input);
    boolean safeMatches = safeM.matches();

    assertThat(safeMatches)
        .as("matches() for /%s/ on \"%s\"", regex, input)
        .isEqualTo(jdkMatches);
  }

  /**
   * Asserts SafeRE compiles with the given flags and matches identically to JDK on the given
   * input.
   */
  private static void assertMatchesSameWithFlags(String regex, int jdkFlags, String input) {
    java.util.regex.Matcher jdkM =
        java.util.regex.Pattern.compile(regex, jdkFlags).matcher(input);
    boolean jdkFound = jdkM.find();

    // Map JDK flags to SafeRE flags (same values by design).
    Matcher safeM = Pattern.compile(regex, jdkFlags).matcher(input);
    boolean safeFound = safeM.find();

    assertThat(safeFound)
        .as("find() for /%s/ (flags=%d) on \"%s\"", regex, jdkFlags, input)
        .isEqualTo(jdkFound);

    if (jdkFound && safeFound) {
      assertThat(safeM.group())
          .as("group() for /%s/ (flags=%d) on \"%s\"", regex, jdkFlags, input)
          .isEqualTo(jdkM.group());
    }
  }

  // ===========================================================================
  // 1. Characters & Escapes
  // ===========================================================================

  @Nested
  @DisplayName("Characters and escapes")
  class CharactersAndEscapes {

    @Test
    @DisplayName("literal character")
    void literalCharacter() {
      assertMatchesSame("x", "x");
    }

    @Test
    @DisplayName("escaped backslash: \\\\")
    void escapedBackslash() {
      assertMatchesSame("\\\\", "a\\b");
    }

    // -- Octal escapes --

    @Test
    @DisplayName("octal \\\\0n (single digit)")
    void octalSingleDigit() {
      assertMatchesSame("\\07", "\u0007"); // bell
    }

    @Test
    @DisplayName("octal \\\\0nn (two digits)")
    void octalTwoDigits() {
      assertMatchesSame("\\041", "!");  // 041 octal = 33 = '!'
    }

    @Test
    @Disabled("https://github.com/eaftan/safere/issues/138")
    @DisplayName("octal \\\\0mnn (three digits)")
    void octalThreeDigits() {
      assertMatchesSame("\\0101", "A");  // 0101 octal = 65 = 'A'
    }

    // -- Hex escapes --

    @Test
    @DisplayName("hex \\\\xhh")
    void hexTwoDigit() {
      assertMatchesSame("\\x41", "A");
    }

    @Test
    @DisplayName("hex \\\\x{h...h} (BMP)")
    void hexBracedBmp() {
      assertMatchesSame("\\x{41}", "A");
    }

    @Test
    @DisplayName("hex \\\\x{h...h} (supplementary)")
    void hexBracedSupplementary() {
      // U+1F600 = grinning face emoji
      assertMatchesSame("\\x{1F600}", "\uD83D\uDE00");
    }

    // -- Unicode escape (backslash-u) --

    @Test
    @DisplayName("unicode escape \\\\uhhhh (BMP)")
    void unicodeEscapeBmp() {
      assertMatchesSame("\\u0041", "A");
    }

    @Test
    @DisplayName("unicode escape \\\\uhhhh (Thai character)")
    void unicodeEscapeThai() {
      assertMatchesSame("\\u0E01", "\u0E01");
    }

    @Test
    @DisplayName("unicode escape range in character class")
    void unicodeEscapeRange() {
      assertMatchesSame("[\\u0E00-\\u0E7F]", "\u0E01");
    }

    @Test
    @DisplayName("unicode escape \\\\uhhhh (supplementary via surrogate pair)")
    void unicodeEscapeSurrogatePair() {
      // JDK treats surrogate pair escapes as U+1F600
      assertMatchesSame("\\uD83D\\uDE00", "\uD83D\uDE00");
    }

    // -- Named Unicode character --

    @Test
    @DisplayName("named unicode character \\\\N{name}")
    void namedUnicodeCharacter() {
      assertMatchesSame("\\N{WHITE SMILING FACE}", "\u263A");
    }

    @Test
    @DisplayName("named unicode character \\\\N{name} (Latin letter)")
    void namedUnicodeCharacterLatin() {
      assertMatchesSame("\\N{LATIN SMALL LETTER A}", "a");
    }

    // -- C escapes --

    @Test
    @DisplayName("tab \\\\t")
    void tabEscape() {
      assertMatchesSame("\\t", "\t");
    }

    @Test
    @DisplayName("newline \\\\n")
    void newlineEscape() {
      assertMatchesSame("\\n", "\n");
    }

    @Test
    @DisplayName("carriage return \\\\r")
    void crEscape() {
      assertMatchesSame("\\r", "\r");
    }

    @Test
    @DisplayName("form feed \\\\f")
    void formFeedEscape() {
      assertMatchesSame("\\f", "\f");
    }

    @Test
    @DisplayName("alert/bell \\\\a")
    void alertEscape() {
      assertMatchesSame("\\a", "\u0007");
    }

    @Test
    @DisplayName("escape \\\\e")
    void escapeEscape() {
      assertMatchesSame("\\e", "\u001B");
    }

    // -- Control character --

    @Test
    @DisplayName("control character \\\\cA")
    void controlCharA() {
      assertMatchesSame("\\cA", "\u0001");
    }

    @Test
    @DisplayName("control character \\\\cZ")
    void controlCharZ() {
      assertMatchesSame("\\cZ", "\u001A");
    }

    @Test
    @DisplayName("control character \\\\cM (carriage return)")
    void controlCharM() {
      assertMatchesSame("\\cM", "\r");
    }
  }

  // ===========================================================================
  // 2. Predefined Character Classes
  // ===========================================================================

  @Nested
  @DisplayName("Predefined character classes")
  class PredefinedCharacterClasses {

    @Test
    @DisplayName("dot matches non-newline")
    void dot() {
      assertMatchesSame(".", "a");
      assertMatchesSame(".", "\r");
    }

    @Test
    @DisplayName("\\\\d matches digit")
    void digitClass() {
      assertMatchesSame("\\d", "5");
      assertMatchesFull("\\d", "a");  // should not match
    }

    @Test
    @DisplayName("\\\\D matches non-digit")
    void nonDigitClass() {
      assertMatchesSame("\\D", "a");
      assertMatchesFull("\\D", "5");  // should not match
    }

    @Test
    @DisplayName("\\\\h matches horizontal whitespace")
    void horizontalWhitespace() {
      assertMatchesSame("\\h", " ");
      assertMatchesSame("\\h", "\t");
      assertMatchesFull("\\h", "a");  // should not match
    }

    @Test
    @DisplayName("\\\\H matches non-horizontal whitespace")
    void nonHorizontalWhitespace() {
      assertMatchesSame("\\H", "a");
      assertMatchesFull("\\H", " ");  // should not match
    }

    @Test
    @DisplayName("\\\\s matches whitespace")
    void whitespaceClass() {
      assertMatchesSame("\\s", " ");
      assertMatchesSame("\\s", "\n");
      assertMatchesFull("\\s", "a");  // should not match
    }

    @Test
    @DisplayName("\\\\S matches non-whitespace")
    void nonWhitespaceClass() {
      assertMatchesSame("\\S", "a");
      assertMatchesFull("\\S", " ");  // should not match
    }

    @Test
    @DisplayName("\\\\v matches vertical whitespace")
    void verticalWhitespace() {
      assertMatchesSame("\\v", "\n");
      assertMatchesSame("\\v", "\u000B");
      assertMatchesSame("\\v", "\f");
      assertMatchesSame("\\v", "\r");
      assertMatchesFull("\\v", " ");  // should not match
    }

    @Test
    @DisplayName("\\\\V matches non-vertical whitespace")
    void nonVerticalWhitespace() {
      assertMatchesSame("\\V", "a");
      assertMatchesFull("\\V", "\n");  // should not match
    }

    @Test
    @DisplayName("\\\\w matches word character")
    void wordClass() {
      assertMatchesSame("\\w", "a");
      assertMatchesSame("\\w", "5");
      assertMatchesSame("\\w", "_");
      assertMatchesFull("\\w", "!");  // should not match
    }

    @Test
    @DisplayName("\\\\W matches non-word character")
    void nonWordClass() {
      assertMatchesSame("\\W", "!");
      assertMatchesFull("\\W", "a");  // should not match
    }

    @Test
    @DisplayName("\\\\R matches linebreak sequence")
    void linebreakMatcher() {
      assertMatchesSame("\\R", "\n");
      assertMatchesSame("\\R", "\r\n");
      assertMatchesSame("\\R", "\r");
      assertMatchesSame("\\R", "\u0085");
      assertMatchesSame("\\R", "\u2028");
      assertMatchesSame("\\R", "\u2029");
      assertMatchesFull("\\R", "a");  // should not match
    }

    @Test
    @DisplayName("\\\\X matches extended grapheme cluster")
    void extendedGraphemeCluster() {
      assertCompiles("\\X");
      // Basic: single character
      assertMatchesSame("\\X", "a");
    }
  }

  // ===========================================================================
  // 3. Character Classes
  // ===========================================================================

  @Nested
  @DisplayName("Character classes")
  class CharacterClasses {

    @Test
    @DisplayName("simple class [abc]")
    void simpleClass() {
      assertMatchesSame("[abc]", "b");
      assertMatchesFull("[abc]", "d");
    }

    @Test
    @DisplayName("negation [^abc]")
    void negation() {
      assertMatchesSame("[^abc]", "d");
      assertMatchesFull("[^abc]", "b");
    }

    @Test
    @DisplayName("range [a-zA-Z]")
    void range() {
      assertMatchesSame("[a-zA-Z]", "m");
      assertMatchesFull("[a-zA-Z]", "5");
    }

    @Test
    @DisplayName("union [a-d[m-p]]")
    void union() {
      assertMatchesSame("[a-d[m-p]]", "n");
      assertMatchesFull("[a-d[m-p]]", "f");
    }

    @Test
    @Disabled("https://github.com/eaftan/safere/issues/139")
    @DisplayName("intersection [a-z&&[def]]")
    void intersection() {
      assertMatchesSame("[a-z&&[def]]", "d");
      assertMatchesFull("[a-z&&[def]]", "a");
    }

    @Test
    @Disabled("https://github.com/eaftan/safere/issues/139")
    @DisplayName("subtraction [a-z&&[^bc]]")
    void subtraction() {
      assertMatchesSame("[a-z&&[^bc]]", "a");
      assertMatchesFull("[a-z&&[^bc]]", "b");
    }

    @Test
    @Disabled("https://github.com/eaftan/safere/issues/139")
    @DisplayName("subtraction [a-z&&[^m-p]]")
    void subtractionRange() {
      assertMatchesSame("[a-z&&[^m-p]]", "a");
      assertMatchesFull("[a-z&&[^m-p]]", "n");
    }

    @Test
    @Disabled("https://github.com/eaftan/safere/issues/140")
    @DisplayName("surrogate pair range in character class")
    void surrogatePairRange() {
      // From issue #127 comment: surrogate pairs encoding supplementary ranges
      assertCompiles("[\\uD800\\uDC00-\\uDBFF\\uDFFF]");
    }

    @Test
    @Disabled("https://github.com/eaftan/safere/issues/140")
    @DisplayName("complex Unicode range from issue #127")
    void complexUnicodeRange() {
      // Pattern from issue #127 comment
      assertCompiles("([\\u0020-\\uD7FF\\uE000-\\uFFFD\\uD800\\uDC00-\\uDBFF\\uDFFF\\t]*)$");
    }
  }

  // ===========================================================================
  // 4. POSIX Character Classes (\p{...})
  // ===========================================================================

  @Nested
  @DisplayName("POSIX character classes")
  class PosixCharacterClasses {

    static Stream<Arguments> posixClasses() {
      return Stream.of(
          Arguments.of("\\p{Lower}", "a", "5"),
          Arguments.of("\\p{Upper}", "A", "a"),
          Arguments.of("\\p{ASCII}", "x", "\u0080"),
          Arguments.of("\\p{Alpha}", "a", "5"),
          Arguments.of("\\p{Digit}", "5", "a"),
          Arguments.of("\\p{Alnum}", "a", "!"),
          Arguments.of("\\p{Punct}", "!", "a"),
          Arguments.of("\\p{Graph}", "a", " "),
          Arguments.of("\\p{Print}", "a", "\u0000"),
          Arguments.of("\\p{Blank}", " ", "a"),
          Arguments.of("\\p{Cntrl}", "\u0000", "a"),
          Arguments.of("\\p{XDigit}", "f", "g"),
          Arguments.of("\\p{Space}", " ", "a"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("posixClasses")
    @DisplayName("POSIX class")
    void posixClass(String regex, String shouldMatch, String shouldNotMatch) {
      assertMatchesSame(regex, shouldMatch);
      assertMatchesFull(regex, shouldNotMatch);
    }
  }

  // ===========================================================================
  // 5. java.lang.Character Classes
  // ===========================================================================

  @Nested
  @DisplayName("java.lang.Character classes")
  class JavaCharacterClasses {

    static Stream<Arguments> javaClasses() {
      return Stream.of(
          Arguments.of("\\p{javaLowerCase}", "a", "A"),
          Arguments.of("\\p{javaUpperCase}", "A", "a"),
          Arguments.of("\\p{javaWhitespace}", " ", "a"),
          Arguments.of("\\p{javaMirrored}", "(", "a"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("javaClasses")
    @DisplayName("java.lang.Character class")
    void javaCharClass(String regex, String shouldMatch, String shouldNotMatch) {
      assertMatchesSame(regex, shouldMatch);
      assertMatchesFull(regex, shouldNotMatch);
    }
  }

  // ===========================================================================
  // 6. Unicode Scripts, Blocks, Categories, Binary Properties
  // ===========================================================================

  @Nested
  @DisplayName("Unicode scripts")
  class UnicodeScripts {

    @Test
    @DisplayName("\\\\p{IsLatin}")
    void isLatinScript() {
      assertMatchesSame("\\p{IsLatin}", "A");
      assertMatchesFull("\\p{IsLatin}", "\u4E00"); // CJK char
    }

    @Test
    @DisplayName("\\\\p{IsHiragana}")
    void isHiraganaScript() {
      assertMatchesSame("\\p{IsHiragana}", "\u3042"); // Hiragana 'a'
    }

    @Test
    @DisplayName("\\\\p{script=Hiragana}")
    void scriptKeywordHiragana() {
      assertMatchesSame("\\p{script=Hiragana}", "\u3042");
    }

    @Test
    @DisplayName("\\\\p{sc=Hiragana}")
    void scKeywordHiragana() {
      assertMatchesSame("\\p{sc=Hiragana}", "\u3042");
    }

    @Test
    @DisplayName("\\\\p{sc=Latin}")
    void scKeywordLatin() {
      assertMatchesSame("\\p{sc=Latin}", "A");
    }
  }

  @Nested
  @DisplayName("Unicode blocks")
  class UnicodeBlocks {

    @Test
    @DisplayName("\\\\p{InGreek}")
    void inGreek() {
      assertMatchesSame("\\p{InGreek}", "\u0391"); // Alpha
    }

    @Test
    @DisplayName("\\\\p{InBasicLatin}")
    void inBasicLatin() {
      assertMatchesSame("\\p{InBasicLatin}", "A");
    }

    @Test
    @DisplayName("\\\\p{block=Mongolian}")
    void blockKeywordMongolian() {
      assertMatchesSame("\\p{block=Mongolian}", "\u1820");
    }

    @Test
    @DisplayName("\\\\p{blk=Greek}")
    void blkKeywordGreek() {
      assertMatchesSame("\\p{blk=Greek}", "\u0391");
    }

    @Test
    @DisplayName("\\\\P{InGreek} (negated)")
    void notInGreek() {
      assertMatchesSame("\\P{InGreek}", "A");
      assertMatchesFull("\\P{InGreek}", "\u0391");
    }
  }

  @Nested
  @DisplayName("Unicode categories")
  class UnicodeCategories {

    @Test
    @DisplayName("\\\\p{Lu} (uppercase letter)")
    void luCategory() {
      assertMatchesSame("\\p{Lu}", "A");
      assertMatchesFull("\\p{Lu}", "a");
    }

    @Test
    @DisplayName("\\\\p{Ll} (lowercase letter)")
    void llCategory() {
      assertMatchesSame("\\p{Ll}", "a");
    }

    @Test
    @DisplayName("\\\\p{L} (letter)")
    void lCategory() {
      assertMatchesSame("\\p{L}", "a");
      assertMatchesFull("\\p{L}", "5");
    }

    @Test
    @DisplayName("\\\\p{IsLu} (category with Is prefix)")
    void isLuCategory() {
      assertMatchesSame("\\p{IsLu}", "A");
      assertMatchesFull("\\p{IsLu}", "a");
    }

    @Test
    @DisplayName("\\\\p{IsL} (category with Is prefix)")
    void isLCategory() {
      assertMatchesSame("\\p{IsL}", "a");
      assertMatchesFull("\\p{IsL}", "5");
    }

    @Test
    @DisplayName("\\\\p{Sc} (currency symbol)")
    void scCategory() {
      assertMatchesSame("\\p{Sc}", "$");
      assertMatchesFull("\\p{Sc}", "a");
    }

    @Test
    @DisplayName("\\\\p{Nd} (decimal digit number)")
    void ndCategory() {
      assertMatchesSame("\\p{Nd}", "5");
    }

    @Test
    @DisplayName("\\\\p{general_category=Lu}")
    void gcKeywordLu() {
      assertMatchesSame("\\p{general_category=Lu}", "A");
    }

    @Test
    @DisplayName("\\\\p{gc=Lu}")
    void gcShortKeywordLu() {
      assertMatchesSame("\\p{gc=Lu}", "A");
    }

    @Test
    @Disabled("https://github.com/eaftan/safere/issues/139")
    @DisplayName("[\\\\p{L}&&[^\\\\p{Lu}]] (category subtraction)")
    void categorySubtraction() {
      assertMatchesSame("[\\p{L}&&[^\\p{Lu}]]", "a");
      assertMatchesFull("[\\p{L}&&[^\\p{Lu}]]", "A");
    }
  }

  @Nested
  @DisplayName("Unicode binary properties")
  class UnicodeBinaryProperties {

    static Stream<Arguments> binaryProperties() {
      return Stream.of(
          Arguments.of("\\p{IsAlphabetic}", "a", "5"),
          Arguments.of("\\p{IsIdeographic}", "\u4E00", "a"),
          Arguments.of("\\p{IsLetter}", "a", "5"),
          Arguments.of("\\p{IsLowercase}", "a", "A"),
          Arguments.of("\\p{IsUppercase}", "A", "a"),
          Arguments.of("\\p{IsTitlecase}", "\u01C5", "a"), // Dz with caron
          Arguments.of("\\p{IsPunctuation}", "!", "a"),
          Arguments.of("\\p{IsControl}", "\u0000", "a"),
          Arguments.of("\\p{IsWhite_Space}", " ", "a"),
          Arguments.of("\\p{IsDigit}", "5", "a"),
          Arguments.of("\\p{IsHex_Digit}", "f", "g"),
          Arguments.of("\\p{IsJoin_Control}", "\u200C", "a"), // ZWNJ
          Arguments.of("\\p{IsNoncharacter_Code_Point}", "\uFDD0", "a"),
          Arguments.of("\\p{IsAssigned}", "a", "\uFFFF"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("binaryProperties")
    @DisplayName("binary property")
    void binaryProperty(String regex, String shouldMatch, String shouldNotMatch) {
      assertMatchesSame(regex, shouldMatch);
      assertMatchesFull(regex, shouldNotMatch);
    }

    @Test
    @Disabled("https://github.com/eaftan/safere/issues/136")
    @DisplayName("\\\\p{IsWhiteSpace} (no underscore, from issue #127)")
    void isWhiteSpaceNoUnderscore() {
      // JDK is flexible about underscores in property names
      assertMatchesSame("\\p{IsWhiteSpace}", " ");
    }

    @Test
    @DisplayName("\\\\p{IsEmoji}")
    void isEmoji() {
      assertMatchesSame("\\p{IsEmoji}", "\u263A"); // white smiling face
    }

    @Test
    @DisplayName("\\\\p{IsEmoji_Presentation}")
    void isEmojiPresentation() {
      assertCompiles("\\p{IsEmoji_Presentation}");
    }

    @Test
    @DisplayName("\\\\p{IsEmoji_Modifier}")
    void isEmojiModifier() {
      assertCompiles("\\p{IsEmoji_Modifier}");
    }

    @Test
    @DisplayName("\\\\p{IsEmoji_Modifier_Base}")
    void isEmojiModifierBase() {
      assertCompiles("\\p{IsEmoji_Modifier_Base}");
    }

    @Test
    @DisplayName("\\\\p{IsEmoji_Component}")
    void isEmojiComponent() {
      assertCompiles("\\p{IsEmoji_Component}");
    }

    @Test
    @DisplayName("\\\\p{IsExtended_Pictographic}")
    void isExtendedPictographic() {
      assertCompiles("\\p{IsExtended_Pictographic}");
    }
  }

  // ===========================================================================
  // 7. Boundary Matchers
  // ===========================================================================

  @Nested
  @DisplayName("Boundary matchers")
  class BoundaryMatchers {

    @Test
    @DisplayName("^ beginning of line")
    void beginLine() {
      assertMatchesSame("^abc", "abc");
    }

    @Test
    @DisplayName("$ end of line")
    void endLine() {
      assertMatchesSame("abc$", "abc");
    }

    @Test
    @DisplayName("\\\\b word boundary")
    void wordBoundary() {
      assertMatchesSame("\\bword\\b", "a word here");
    }

    @Test
    @DisplayName("\\\\B non-word boundary")
    void nonWordBoundary() {
      assertMatchesSame("\\Bord", "word");
    }

    @Test
    @Disabled("https://github.com/eaftan/safere/issues/137")
    @DisplayName("\\\\b{g} grapheme cluster boundary")
    void graphemeClusterBoundary() {
      assertCompiles("\\b{g}");
    }

    @Test
    @DisplayName("\\\\A beginning of input")
    void beginInput() {
      assertMatchesSame("\\Aabc", "abc");
    }

    @Test
    @DisplayName("\\\\Z end of input (before final terminator)")
    void endInputBeforeTerminator() {
      assertMatchesSame("abc\\Z", "abc\n");
    }

    @Test
    @DisplayName("\\\\z end of input")
    void endInput() {
      assertMatchesSame("abc\\z", "abc");
    }

    @Test
    @DisplayName("\\\\G end of previous match (should reject)")
    void endPreviousMatch() {
      // \G requires state from previous matches; SafeRE should reject.
      // But JDK accepts it.
      assertThatNoException()
          .as("JDK should accept \\G")
          .isThrownBy(() -> java.util.regex.Pattern.compile("\\G"));
      assertThatThrownBy(() -> Pattern.compile("\\G"))
          .as("SafeRE should reject \\G")
          .isInstanceOf(PatternSyntaxException.class);
    }
  }

  // ===========================================================================
  // 8. Quantifiers
  // ===========================================================================

  @Nested
  @DisplayName("Quantifiers")
  class Quantifiers {

    // -- Greedy --

    @Test
    @DisplayName("greedy ? (zero or one)")
    void greedyQuestion() {
      assertMatchesSame("ab?c", "ac");
      assertMatchesSame("ab?c", "abc");
    }

    @Test
    @DisplayName("greedy * (zero or more)")
    void greedyStar() {
      assertMatchesSame("ab*c", "ac");
      assertMatchesSame("ab*c", "abbc");
    }

    @Test
    @DisplayName("greedy + (one or more)")
    void greedyPlus() {
      assertMatchesSame("ab+c", "abc");
      assertMatchesSame("ab+c", "abbc");
    }

    @Test
    @DisplayName("greedy {n}")
    void greedyExact() {
      assertMatchesSame("a{3}", "aaa");
    }

    @Test
    @DisplayName("greedy {n,}")
    void greedyAtLeast() {
      assertMatchesSame("a{2,}", "aaaa");
    }

    @Test
    @DisplayName("greedy {n,m}")
    void greedyRange() {
      assertMatchesSame("a{2,4}", "aaaaa");
    }

    // -- Reluctant --

    @Test
    @DisplayName("reluctant ??")
    void reluctantQuestion() {
      assertMatchesSame("ab??c", "ac");
    }

    @Test
    @DisplayName("reluctant *?")
    void reluctantStar() {
      assertMatchesSame("a.*?c", "abcbc");
    }

    @Test
    @DisplayName("reluctant +?")
    void reluctantPlus() {
      assertMatchesSame("a.+?c", "abcbc");
    }

    @Test
    @DisplayName("reluctant {n,m}?")
    void reluctantRange() {
      assertMatchesSame("a{2,4}?", "aaaaa");
    }

    // -- Possessive (SafeRE should reject) --

    @ParameterizedTest
    @ValueSource(strings = {"a?+", "a*+", "a++", "a{2}+", "a{2,}+", "a{2,4}+"})
    @DisplayName("possessive quantifiers (should reject)")
    void possessiveQuantifiers(String regex) {
      // JDK accepts these.
      assertThatNoException()
          .as("JDK should accept: %s", regex)
          .isThrownBy(() -> java.util.regex.Pattern.compile(regex));
      // SafeRE should reject — possessive quantifiers violate linear-time guarantees.
      assertThatThrownBy(() -> Pattern.compile(regex))
          .as("SafeRE should reject possessive quantifier: %s", regex)
          .isInstanceOf(PatternSyntaxException.class);
    }

    // -- Nested repetitions (from issue #127) --

    @Test
    @DisplayName("nested repetition {0,99} inside {0,5}")
    void nestedRepetition() {
      assertCompiles("(?:a (?:b{0,99}|c{0,9})){0,5}");
    }

    @Test
    @DisplayName("simple nested repetition")
    void simpleNestedRepetition() {
      assertMatchesSame("(ab{2,3}){2}", "abbbabb");
    }
  }

  // ===========================================================================
  // 9. Logical Operators
  // ===========================================================================

  @Nested
  @DisplayName("Logical operators")
  class LogicalOperators {

    @Test
    @DisplayName("concatenation XY")
    void concatenation() {
      assertMatchesSame("ab", "ab");
    }

    @Test
    @DisplayName("alternation X|Y")
    void alternation() {
      assertMatchesSame("cat|dog", "I have a dog");
    }

    @Test
    @DisplayName("capturing group (X)")
    void capturingGroup() {
      assertMatchesSame("(ab)+", "ababab");
    }
  }

  // ===========================================================================
  // 10. Back References (SafeRE should reject)
  // ===========================================================================

  @Nested
  @DisplayName("Back references (should reject)")
  class BackReferences {

    @ParameterizedTest
    @ValueSource(strings = {"(a)\\1", "(a)(b)\\2", "(?<name>a)\\k<name>"})
    @DisplayName("back reference")
    void backReference(String regex) {
      // JDK accepts these.
      assertThatNoException()
          .as("JDK should accept: %s", regex)
          .isThrownBy(() -> java.util.regex.Pattern.compile(regex));
      // SafeRE should reject — back references require backtracking.
      assertThatThrownBy(() -> Pattern.compile(regex))
          .as("SafeRE should reject back reference: %s", regex)
          .isInstanceOf(PatternSyntaxException.class);
    }
  }

  // ===========================================================================
  // 11. Quotation
  // ===========================================================================

  @Nested
  @DisplayName("Quotation")
  class Quotation {

    @Test
    @DisplayName("\\\\Q...\\\\E quotes metacharacters")
    void quotation() {
      assertMatchesSame("\\Q.+*\\E", ".+*");
    }

    @Test
    @DisplayName("\\\\Q...\\\\E at end of pattern (no \\\\E)")
    void quotationNoEnd() {
      assertMatchesSame("\\Q.+*", ".+*");
    }

    @Test
    @DisplayName("\\\\Q...\\\\E with normal regex after")
    void quotationWithRegexAfter() {
      assertMatchesSame("\\Q.+\\E.+", ".+ab");
    }
  }

  // ===========================================================================
  // 12. Special Constructs
  // ===========================================================================

  @Nested
  @DisplayName("Special constructs")
  class SpecialConstructs {

    @Test
    @DisplayName("named capturing group (?<name>X)")
    void namedCapturingGroup() {
      assertMatchesSame("(?<word>\\w+)", "hello");
    }

    @Test
    @DisplayName("non-capturing group (?:X)")
    void nonCapturingGroup() {
      assertMatchesSame("(?:ab)+", "ababab");
    }

    // -- Lookahead/Lookbehind (SafeRE should reject) --

    @ParameterizedTest
    @ValueSource(
        strings = {
          "a(?=b)", // positive lookahead
          "a(?!b)", // negative lookahead
          "(?<=a)b", // positive lookbehind
          "(?<!a)b" // negative lookbehind
        })
    @DisplayName("lookahead/lookbehind (should reject)")
    void lookaround(String regex) {
      assertThatNoException()
          .as("JDK should accept: %s", regex)
          .isThrownBy(() -> java.util.regex.Pattern.compile(regex));
      assertThatThrownBy(() -> Pattern.compile(regex))
          .as("SafeRE should reject lookaround: %s", regex)
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    @DisplayName("independent non-capturing group (?>X) (should reject)")
    void atomicGroup() {
      String regex = "(?>a+)";
      assertThatNoException()
          .as("JDK should accept: %s", regex)
          .isThrownBy(() -> java.util.regex.Pattern.compile(regex));
      assertThatThrownBy(() -> Pattern.compile(regex))
          .as("SafeRE should reject atomic group: %s", regex)
          .isInstanceOf(PatternSyntaxException.class);
    }
  }

  // ===========================================================================
  // 13. Inline Flags
  // ===========================================================================

  @Nested
  @DisplayName("Inline flags")
  class InlineFlags {

    @Test
    @DisplayName("(?i) case insensitive")
    void flagI() {
      assertMatchesSame("(?i)abc", "ABC");
    }

    @Test
    @DisplayName("(?d) UNIX_LINES")
    void flagD() {
      assertCompiles("(?d).");
      // With (?d), only \n is a line terminator; \r should be matched by dot.
      assertMatchesSame("(?d).", "\r");
    }

    @Test
    @DisplayName("(?m) multiline")
    void flagM() {
      assertMatchesSame("(?m)^abc$", "xyz\nabc\ndef");
    }

    @Test
    @DisplayName("(?s) dotall")
    void flagS() {
      assertMatchesSame("(?s)a.b", "a\nb");
    }

    @Test
    @DisplayName("(?u) unicode case")
    void flagU() {
      assertCompiles("(?u)(?i)abc");
    }

    @Test
    @DisplayName("(?x) comments mode")
    void flagX() {
      assertMatchesSame("(?x) a b c ", "abc");
    }

    @Test
    @DisplayName("(?U) UNICODE_CHARACTER_CLASS")
    void flagBigU() {
      assertCompiles("(?U)\\w");
    }

    @Test
    @DisplayName("combined flags (?dm)")
    void combinedFlags() {
      assertCompiles("(?dm)^test$");
    }

    @Test
    @DisplayName("negated flags (?-i)")
    void negatedFlags() {
      assertCompiles("(?i)abc(?-i)def");
    }

    @Test
    @DisplayName("flags on non-capturing group (?i:abc)")
    void flagsOnGroup() {
      assertMatchesSame("(?i:abc)def", "ABCdef");
    }

    @Test
    @DisplayName("(?d) combined with (?m) from issue #127")
    void flagDWithM() {
      assertCompiles("(?m)(?d)^(####? .+|---)$");
    }

    @Test
    @DisplayName("all JDK flags combined (?idmsuxU)")
    void allFlags() {
      assertCompiles("(?idmsuxU)test");
    }

    @Test
    @DisplayName("all JDK flags negated (?-idmsuxU)")
    void allFlagsNegated() {
      assertCompiles("(?idmsuxU)(?-idmsuxU)test");
    }

    @Test
    @DisplayName("flag group (?idmsuxU:X)")
    void flagGroup() {
      assertCompiles("(?ims:abc)");
    }
  }

  // ===========================================================================
  // 14. API Compile Flags
  // ===========================================================================

  @Nested
  @DisplayName("API compile flags")
  class ApiCompileFlags {

    @Test
    @DisplayName("Pattern.CASE_INSENSITIVE")
    void caseInsensitive() {
      assertMatchesSameWithFlags("abc", Pattern.CASE_INSENSITIVE, "ABC");
    }

    @Test
    @DisplayName("Pattern.MULTILINE")
    void multiline() {
      assertMatchesSameWithFlags("^abc$", Pattern.MULTILINE, "xyz\nabc\ndef");
    }

    @Test
    @DisplayName("Pattern.DOTALL")
    void dotall() {
      assertMatchesSameWithFlags("a.b", Pattern.DOTALL, "a\nb");
    }

    @Test
    @DisplayName("Pattern.UNIX_LINES")
    void unixLines() {
      assertMatchesSameWithFlags(".", Pattern.UNIX_LINES, "\r");
    }

    @Test
    @DisplayName("Pattern.COMMENTS")
    void comments() {
      assertMatchesSameWithFlags("a b c # comment", Pattern.COMMENTS, "abc");
    }

    @Test
    @DisplayName("Pattern.UNICODE_CASE")
    void unicodeCase() {
      assertMatchesSameWithFlags(
          "abc", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE, "ABC");
    }

    @Test
    @DisplayName("Pattern.UNICODE_CHARACTER_CLASS")
    void unicodeCharacterClass() {
      assertMatchesSameWithFlags("\\w", Pattern.UNICODE_CHARACTER_CLASS, "\u00E9"); // e-acute
    }
  }

  // ===========================================================================
  // 15. Linebreak and Grapheme (matching behavior)
  // ===========================================================================

  @Nested
  @DisplayName("Linebreak and grapheme matching")
  class LinebreakAndGrapheme {

    @Test
    @DisplayName("\\\\R prefers \\\\r\\\\n over bare \\\\r")
    void linebreakCrLfPreference() {
      // \R should match \r\n as a single unit, not just \r
      java.util.regex.Matcher jdkM = java.util.regex.Pattern.compile("\\R").matcher("\r\n");
      assertThat(jdkM.find()).isTrue();
      String jdkGroup = jdkM.group();

      Matcher safeM = Pattern.compile("\\R").matcher("\r\n");
      assertThat(safeM.find()).isTrue();
      assertThat(safeM.group()).isEqualTo(jdkGroup);
    }

    @Test
    @DisplayName("\\\\R does not match ordinary characters")
    void linebreakNoOrdinary() {
      assertMatchesFull("\\R", "a");
    }

    @Test
    @DisplayName("dot respects line terminators by default")
    void dotDefaultLineTerminators() {
      assertMatchesFull(".", "\n");
    }

    @Test
    @DisplayName("dot with DOTALL matches newline")
    void dotDotallMatchesNewline() {
      assertMatchesSameWithFlags(".", Pattern.DOTALL, "\n");
    }
  }

  // ===========================================================================
  // 16. Line terminators
  // ===========================================================================

  @Nested
  @DisplayName("Line terminators")
  class LineTerminators {

    static Stream<Arguments> lineTerminators() {
      return Stream.of(
          Arguments.of("\\n (newline)", "\n"),
          Arguments.of("\\r\\n (CRLF)", "\r\n"),
          Arguments.of("\\r (carriage return)", "\r"),
          Arguments.of("\\u0085 (next line)", "\u0085"),
          Arguments.of("\\u2028 (line separator)", "\u2028"),
          Arguments.of("\\u2029 (paragraph separator)", "\u2029"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lineTerminators")
    @DisplayName("dot does not match line terminator")
    void dotDoesNotMatch(String desc, String terminator) {
      // dot should NOT match line terminators (without DOTALL)
      java.util.regex.Matcher jdkM = java.util.regex.Pattern.compile(".").matcher(terminator);
      Matcher safeM = Pattern.compile(".").matcher(terminator);
      assertThat(safeM.find())
          .as("dot on %s", desc)
          .isEqualTo(jdkM.find());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lineTerminators")
    @DisplayName("(?m)$ matches before line terminator")
    void multilineDollar(String desc, String terminator) {
      String input = "abc" + terminator + "def";
      assertMatchesSameWithFlags("abc$", Pattern.MULTILINE, input);
    }
  }

  // ===========================================================================
  // 17. Escaped metacharacters
  // ===========================================================================

  @Nested
  @DisplayName("Escaped metacharacters")
  class EscapedMetacharacters {

    @ParameterizedTest
    @ValueSource(strings = {"\\.", "\\*", "\\+", "\\?", "\\(", "\\)", "\\[", "\\]",
        "\\{", "\\}", "\\|", "\\^", "\\$", "\\\\"})
    @DisplayName("escaped metacharacter is literal")
    void escapedMetacharacter(String regex) {
      assertCompiles(regex);
    }
  }

  // ===========================================================================
  // 18. Edge Cases from Issue #127
  // ===========================================================================

  @Nested
  @DisplayName("Edge cases from issue #127")
  class Issue127EdgeCases {

    @Test
    @DisplayName("(?m)(?d)^(####? .+|---)$")
    void inlineFlagDWithMultiline() {
      assertMatchesSame("(?m)(?d)^(####? .+|---)$", "## Hello");
    }

    @Test
    @DisplayName("Thai character range with \\\\u escapes")
    void thaiCharacterRange() {
      assertMatchesSame("([\\u0E00-\\u0E7F])([0-9a-zA-Z])", "\u0E01a");
    }

    @Test
    @DisplayName("nested repetition (?:a (?:b{0,99}|c{0,9})){0,5}")
    void nestedRepetitionFromIssue() {
      assertMatchesSame("(?:a (?:b{0,99}|c{0,9})){0,5}", "a bbb");
    }

    @Test
    @Disabled("https://github.com/eaftan/safere/issues/136")
    @DisplayName("\\\\p{IsWhiteSpace} (no underscore)")
    void isWhiteSpaceNoUnderscore() {
      assertMatchesSame("\\p{IsWhiteSpace}", " ");
    }

    @Test
    @Disabled("https://github.com/eaftan/safere/issues/140")
    @DisplayName("complex Unicode range with surrogates from issue #127 comment")
    void complexSurrogateRange() {
      assertCompiles("([\\u0020-\\uD7FF\\uE000-\\uFFFD\\uD800\\uDC00-\\uDBFF\\uDFFF\\t]*)$");
    }
  }

  // ===========================================================================
  // 19. Miscellaneous Patterns
  // ===========================================================================

  @Nested
  @DisplayName("Miscellaneous patterns")
  class MiscPatterns {

    @Test
    @DisplayName("empty pattern")
    void emptyPattern() {
      assertMatchesSame("", "abc");
    }

    @Test
    @DisplayName("empty alternation branch")
    void emptyAlternationBranch() {
      assertMatchesSame("a|", "b");
    }

    @Test
    @DisplayName("nested groups")
    void nestedGroups() {
      assertMatchesSame("((a)(b(c)))", "abc");
    }

    @Test
    @DisplayName("complex real-world pattern: email-like")
    void emailLike() {
      assertMatchesSame("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", "test@example.com");
    }

    @Test
    @DisplayName("complex real-world pattern: IPv4")
    void ipv4() {
      assertMatchesSame(
          "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "192.168.1.1");
    }

    @Test
    @DisplayName("large character class with union and intersection")
    void largeCharClassOps() {
      assertMatchesSame("[a-z[A-Z]&&[^aeiouAEIOU]]", "b"); // consonants only
    }
  }
}
