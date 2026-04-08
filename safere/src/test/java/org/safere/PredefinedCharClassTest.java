// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for predefined character classes {@code \h}, {@code \H}, {@code \v}, {@code \V}.
 *
 * <p>These correspond to the JDK's horizontal and vertical whitespace character classes as
 * documented in {@link java.util.regex.Pattern}.
 */
@DisplayName("Predefined character classes: \\h, \\H, \\v, \\V")
class PredefinedCharClassTest {

  // --- Horizontal whitespace code points (JDK definition of \h) ---
  private static final int[] HORIZ_SPACE_CODEPOINTS = {
    0x09,   // CHARACTER TABULATION (tab)
    0x20,   // SPACE
    0xA0,   // NO-BREAK SPACE
    0x1680, // OGHAM SPACE MARK
    0x180E, // MONGOLIAN VOWEL SEPARATOR
    0x2000, // EN QUAD
    0x2001, // EM QUAD
    0x2002, // EN SPACE
    0x2003, // EM SPACE
    0x2004, // THREE-PER-EM SPACE
    0x2005, // FOUR-PER-EM SPACE
    0x2006, // SIX-PER-EM SPACE
    0x2007, // FIGURE SPACE
    0x2008, // PUNCTUATION SPACE
    0x2009, // THIN SPACE
    0x200A, // HAIR SPACE
    0x202F, // NARROW NO-BREAK SPACE
    0x205F, // MEDIUM MATHEMATICAL SPACE
    0x3000, // IDEOGRAPHIC SPACE
  };

  // --- Vertical whitespace code points (JDK definition of \v) ---
  private static final int[] VERT_SPACE_CODEPOINTS = {
    0x0A, // LINE FEED
    0x0B, // VERTICAL TABULATION
    0x0C, // FORM FEED
    0x0D, // CARRIAGE RETURN
    0x85, // NEXT LINE (NEL)
    0x2028, // LINE SEPARATOR
    0x2029, // PARAGRAPH SEPARATOR
  };

  // --- Non-whitespace code points for negative testing ---
  private static final int[] NON_WHITESPACE_CODEPOINTS = {
    'a', 'Z', '0', '!', '@', '#', 0x0100, 0x4E00, // CJK character
  };

  @Nested
  @DisplayName("\\h (horizontal whitespace)")
  class HorizWhitespace {

    @Test
    @DisplayName("matches all JDK horizontal whitespace characters")
    void matchesAllHorizWhitespace() {
      Pattern p = Pattern.compile("\\h");
      for (int cp : HORIZ_SPACE_CODEPOINTS) {
        String s = new String(Character.toChars(cp));
        Matcher m = p.matcher(s);
        assertThat(m.matches())
            .as("\\h should match U+%04X", cp)
            .isTrue();
      }
    }

    @Test
    @DisplayName("does not match vertical whitespace")
    void doesNotMatchVertWhitespace() {
      Pattern p = Pattern.compile("\\h");
      for (int cp : VERT_SPACE_CODEPOINTS) {
        String s = new String(Character.toChars(cp));
        Matcher m = p.matcher(s);
        assertThat(m.matches())
            .as("\\h should not match U+%04X", cp)
            .isFalse();
      }
    }

    @Test
    @DisplayName("does not match non-whitespace characters")
    void doesNotMatchNonWhitespace() {
      Pattern p = Pattern.compile("\\h");
      for (int cp : NON_WHITESPACE_CODEPOINTS) {
        String s = new String(Character.toChars(cp));
        Matcher m = p.matcher(s);
        assertThat(m.matches())
            .as("\\h should not match U+%04X", cp)
            .isFalse();
      }
    }

    @Test
    @DisplayName("finds all horizontal whitespace in mixed string")
    void findsInMixedString() {
      Pattern p = Pattern.compile("\\h+");
      Matcher m = p.matcher("hello\t world\u00A0!");
      List<String> matches = new ArrayList<>();
      while (m.find()) {
        matches.add(m.group());
      }
      assertThat(matches).containsExactly("\t ", "\u00A0");
    }

    @Test
    @DisplayName("works with quantifiers")
    void worksWithQuantifiers() {
      assertThat(Pattern.matches("\\h*", "")).isTrue();
      assertThat(Pattern.matches("\\h+", "\t \u00A0")).isTrue();
      assertThat(Pattern.matches("\\h{2}", "\t ")).isTrue();
      assertThat(Pattern.matches("\\h{2}", "\t")).isFalse();
    }
  }

  @Nested
  @DisplayName("\\H (non-horizontal whitespace)")
  class NonHorizWhitespace {

    @Test
    @DisplayName("does not match horizontal whitespace")
    void doesNotMatchHorizWhitespace() {
      Pattern p = Pattern.compile("\\H");
      for (int cp : HORIZ_SPACE_CODEPOINTS) {
        String s = new String(Character.toChars(cp));
        Matcher m = p.matcher(s);
        assertThat(m.matches())
            .as("\\H should not match U+%04X", cp)
            .isFalse();
      }
    }

    @Test
    @DisplayName("matches vertical whitespace")
    void matchesVertWhitespace() {
      Pattern p = Pattern.compile("\\H");
      for (int cp : VERT_SPACE_CODEPOINTS) {
        String s = new String(Character.toChars(cp));
        Matcher m = p.matcher(s);
        assertThat(m.matches())
            .as("\\H should match U+%04X", cp)
            .isTrue();
      }
    }

    @Test
    @DisplayName("matches non-whitespace characters")
    void matchesNonWhitespace() {
      Pattern p = Pattern.compile("\\H");
      for (int cp : NON_WHITESPACE_CODEPOINTS) {
        String s = new String(Character.toChars(cp));
        Matcher m = p.matcher(s);
        assertThat(m.matches())
            .as("\\H should match U+%04X", cp)
            .isTrue();
      }
    }

    @Test
    @DisplayName("finds non-horizontal-whitespace runs")
    void findsInMixedString() {
      Pattern p = Pattern.compile("\\H+");
      Matcher m = p.matcher("hi\tthere");
      List<String> matches = new ArrayList<>();
      while (m.find()) {
        matches.add(m.group());
      }
      assertThat(matches).containsExactly("hi", "there");
    }
  }

  @Nested
  @DisplayName("\\v (vertical whitespace)")
  class VertWhitespace {

    @Test
    @DisplayName("matches all JDK vertical whitespace characters")
    void matchesAllVertWhitespace() {
      Pattern p = Pattern.compile("\\v");
      for (int cp : VERT_SPACE_CODEPOINTS) {
        String s = new String(Character.toChars(cp));
        Matcher m = p.matcher(s);
        assertThat(m.matches())
            .as("\\v should match U+%04X", cp)
            .isTrue();
      }
    }

    @Test
    @DisplayName("does not match horizontal whitespace")
    void doesNotMatchHorizWhitespace() {
      Pattern p = Pattern.compile("\\v");
      for (int cp : HORIZ_SPACE_CODEPOINTS) {
        String s = new String(Character.toChars(cp));
        Matcher m = p.matcher(s);
        assertThat(m.matches())
            .as("\\v should not match U+%04X", cp)
            .isFalse();
      }
    }

    @Test
    @DisplayName("does not match non-whitespace characters")
    void doesNotMatchNonWhitespace() {
      Pattern p = Pattern.compile("\\v");
      for (int cp : NON_WHITESPACE_CODEPOINTS) {
        String s = new String(Character.toChars(cp));
        Matcher m = p.matcher(s);
        assertThat(m.matches())
            .as("\\v should not match U+%04X", cp)
            .isFalse();
      }
    }

    @Test
    @DisplayName("finds all vertical whitespace in mixed string")
    void findsInMixedString() {
      Pattern p = Pattern.compile("\\v");
      Matcher m = p.matcher("line1\nline2\rline3");
      List<String> matches = new ArrayList<>();
      while (m.find()) {
        matches.add(String.format("U+%04X", (int) m.group().charAt(0)));
      }
      assertThat(matches).containsExactly("U+000A", "U+000D");
    }

    @Test
    @DisplayName("matches NEL (U+0085) and Unicode line/paragraph separators")
    void matchesUnicodeLineSeparators() {
      Pattern p = Pattern.compile("\\v");
      assertThat(p.matcher("\u0085").matches()).isTrue();
      assertThat(p.matcher("\u2028").matches()).isTrue();
      assertThat(p.matcher("\u2029").matches()).isTrue();
    }

    @Test
    @DisplayName("works with quantifiers")
    void worksWithQuantifiers() {
      assertThat(Pattern.matches("\\v*", "")).isTrue();
      assertThat(Pattern.matches("\\v+", "\n\r\u000B")).isTrue();
      assertThat(Pattern.matches("\\v{2}", "\n\r")).isTrue();
      assertThat(Pattern.matches("\\v{2}", "\n")).isFalse();
    }
  }

  @Nested
  @DisplayName("\\V (non-vertical whitespace)")
  class NonVertWhitespace {

    @Test
    @DisplayName("does not match vertical whitespace")
    void doesNotMatchVertWhitespace() {
      Pattern p = Pattern.compile("\\V");
      for (int cp : VERT_SPACE_CODEPOINTS) {
        String s = new String(Character.toChars(cp));
        Matcher m = p.matcher(s);
        assertThat(m.matches())
            .as("\\V should not match U+%04X", cp)
            .isFalse();
      }
    }

    @Test
    @DisplayName("matches horizontal whitespace")
    void matchesHorizWhitespace() {
      Pattern p = Pattern.compile("\\V");
      for (int cp : HORIZ_SPACE_CODEPOINTS) {
        String s = new String(Character.toChars(cp));
        Matcher m = p.matcher(s);
        assertThat(m.matches())
            .as("\\V should match U+%04X", cp)
            .isTrue();
      }
    }

    @Test
    @DisplayName("matches non-whitespace characters")
    void matchesNonWhitespace() {
      Pattern p = Pattern.compile("\\V");
      for (int cp : NON_WHITESPACE_CODEPOINTS) {
        String s = new String(Character.toChars(cp));
        Matcher m = p.matcher(s);
        assertThat(m.matches())
            .as("\\V should match U+%04X", cp)
            .isTrue();
      }
    }

    @Test
    @DisplayName("finds non-vertical-whitespace runs")
    void findsInMixedString() {
      Pattern p = Pattern.compile("\\V+");
      Matcher m = p.matcher("abc\ndef\rghi");
      List<String> matches = new ArrayList<>();
      while (m.find()) {
        matches.add(m.group());
      }
      assertThat(matches).containsExactly("abc", "def", "ghi");
    }
  }

  @Nested
  @DisplayName("Inside character classes [...]")
  class InsideCharClass {

    @Test
    @DisplayName("\\h inside [...] matches horizontal whitespace")
    void hInsideCharClass() {
      Pattern p = Pattern.compile("[\\h]");
      assertThat(p.matcher("\t").matches()).isTrue();
      assertThat(p.matcher(" ").matches()).isTrue();
      assertThat(p.matcher("\u00A0").matches()).isTrue();
      assertThat(p.matcher("a").matches()).isFalse();
      assertThat(p.matcher("\n").matches()).isFalse();
    }

    @Test
    @DisplayName("\\H inside [...] matches non-horizontal whitespace")
    void hNegatedInsideCharClass() {
      Pattern p = Pattern.compile("[\\H]");
      assertThat(p.matcher("a").matches()).isTrue();
      assertThat(p.matcher("\n").matches()).isTrue();
      assertThat(p.matcher("\t").matches()).isFalse();
      assertThat(p.matcher(" ").matches()).isFalse();
    }

    @Test
    @DisplayName("\\v inside [...] matches vertical whitespace")
    void vInsideCharClass() {
      Pattern p = Pattern.compile("[\\v]");
      assertThat(p.matcher("\n").matches()).isTrue();
      assertThat(p.matcher("\r").matches()).isTrue();
      assertThat(p.matcher("\u000B").matches()).isTrue();
      assertThat(p.matcher("\u0085").matches()).isTrue();
      assertThat(p.matcher("a").matches()).isFalse();
      assertThat(p.matcher("\t").matches()).isFalse();
    }

    @Test
    @DisplayName("\\V inside [...] matches non-vertical whitespace")
    void vNegatedInsideCharClass() {
      Pattern p = Pattern.compile("[\\V]");
      assertThat(p.matcher("a").matches()).isTrue();
      assertThat(p.matcher("\t").matches()).isTrue();
      assertThat(p.matcher("\n").matches()).isFalse();
      assertThat(p.matcher("\r").matches()).isFalse();
    }

    @Test
    @DisplayName("[\\h\\v] matches any whitespace (horizontal or vertical)")
    void hAndVUnion() {
      Pattern p = Pattern.compile("[\\h\\v]+");
      assertThat(p.matcher("\t \n\r\u000B\f").matches()).isTrue();
      assertThat(p.matcher("abc").matches()).isFalse();
    }

    @Test
    @DisplayName("[^\\h] is equivalent to \\H")
    void negatedHInCharClass() {
      Pattern negH = Pattern.compile("[^\\h]");
      Pattern bigH = Pattern.compile("\\H");
      for (int cp : HORIZ_SPACE_CODEPOINTS) {
        String s = new String(Character.toChars(cp));
        assertThat(negH.matcher(s).matches()).isEqualTo(bigH.matcher(s).matches());
      }
      for (int cp : NON_WHITESPACE_CODEPOINTS) {
        String s = new String(Character.toChars(cp));
        assertThat(negH.matcher(s).matches()).isEqualTo(bigH.matcher(s).matches());
      }
    }

    @Test
    @DisplayName("[^\\v] is equivalent to \\V")
    void negatedVInCharClass() {
      Pattern negV = Pattern.compile("[^\\v]");
      Pattern bigV = Pattern.compile("\\V");
      for (int cp : VERT_SPACE_CODEPOINTS) {
        String s = new String(Character.toChars(cp));
        assertThat(negV.matcher(s).matches()).isEqualTo(bigV.matcher(s).matches());
      }
      for (int cp : NON_WHITESPACE_CODEPOINTS) {
        String s = new String(Character.toChars(cp));
        assertThat(negV.matcher(s).matches()).isEqualTo(bigV.matcher(s).matches());
      }
    }

    @Test
    @DisplayName("\\h in range union: [a\\h] matches 'a' and horizontal whitespace")
    void hInRangeUnion() {
      Pattern p = Pattern.compile("[a\\h]");
      assertThat(p.matcher("a").matches()).isTrue();
      assertThat(p.matcher("\t").matches()).isTrue();
      assertThat(p.matcher(" ").matches()).isTrue();
      assertThat(p.matcher("b").matches()).isFalse();
    }

    @Test
    @DisplayName("\\v in range union: [a\\v] matches 'a' and vertical whitespace")
    void vInRangeUnion() {
      Pattern p = Pattern.compile("[a\\v]");
      assertThat(p.matcher("a").matches()).isTrue();
      assertThat(p.matcher("\n").matches()).isTrue();
      assertThat(p.matcher("\r").matches()).isTrue();
      assertThat(p.matcher("b").matches()).isFalse();
    }
  }

  @Nested
  @DisplayName("Combined patterns")
  class CombinedPatterns {

    @Test
    @DisplayName("\\h and \\v in alternation")
    void alternation() {
      Pattern p = Pattern.compile("\\h|\\v");
      assertThat(p.matcher("\t").matches()).isTrue();
      assertThat(p.matcher("\n").matches()).isTrue();
      assertThat(p.matcher("a").matches()).isFalse();
    }

    @Test
    @DisplayName("\\h in complex pattern: split on horizontal whitespace")
    void splitOnHorizWhitespace() {
      Pattern p = Pattern.compile("\\h+");
      String[] parts = p.split("hello\tworld foo");
      assertThat(parts).containsExactly("hello", "world", "foo");
    }

    @Test
    @DisplayName("\\v in complex pattern: split on vertical whitespace")
    void splitOnVertWhitespace() {
      Pattern p = Pattern.compile("\\v+");
      String[] parts = p.split("line1\nline2\r\nline3");
      assertThat(parts).containsExactly("line1", "line2", "line3");
    }

    @Test
    @DisplayName("capture groups with \\h and \\v")
    void captureGroups() {
      Pattern p = Pattern.compile("(\\H+)(\\h+)(\\H+)");
      Matcher m = p.matcher("hello\tworld");
      assertThat(m.matches()).isTrue();
      assertThat(m.group(1)).isEqualTo("hello");
      assertThat(m.group(2)).isEqualTo("\t");
      assertThat(m.group(3)).isEqualTo("world");
    }

    @Test
    @DisplayName("\\v with CASE_INSENSITIVE flag")
    void caseInsensitive() {
      Pattern p = Pattern.compile("\\v+", Pattern.CASE_INSENSITIVE);
      assertThat(p.matcher("\n\r").matches()).isTrue();
    }

    @Test
    @DisplayName("\\h with DOTALL flag")
    void dotall() {
      Pattern p = Pattern.compile("\\h+", Pattern.DOTALL);
      assertThat(p.matcher("\t ").matches()).isTrue();
    }
  }

  @Nested
  @DisplayName("JDK compatibility cross-check")
  class JdkCompatibility {

    @Test
    @DisplayName("\\h matches same characters as JDK")
    void hMatchesSameAsJdk() {
      Pattern safeP = Pattern.compile("\\h");
      java.util.regex.Pattern jdkP = java.util.regex.Pattern.compile("\\h");
      // Test BMP code points up to 0x3100 (covers all \h members plus surroundings)
      for (int cp = 0; cp <= 0x3100; cp++) {
        String s = new String(Character.toChars(cp));
        boolean safeMatch = safeP.matcher(s).matches();
        boolean jdkMatch = jdkP.matcher(s).matches();
        assertThat(safeMatch)
            .as("\\h U+%04X: SafeRE=%s, JDK=%s", cp, safeMatch, jdkMatch)
            .isEqualTo(jdkMatch);
      }
    }

    @Test
    @DisplayName("\\v matches same characters as JDK")
    void vMatchesSameAsJdk() {
      Pattern safeP = Pattern.compile("\\v");
      java.util.regex.Pattern jdkP = java.util.regex.Pattern.compile("\\v");
      // Test BMP code points up to 0x3000 (covers all \v members plus surroundings)
      for (int cp = 0; cp <= 0x3000; cp++) {
        String s = new String(Character.toChars(cp));
        boolean safeMatch = safeP.matcher(s).matches();
        boolean jdkMatch = jdkP.matcher(s).matches();
        assertThat(safeMatch)
            .as("\\v U+%04X: SafeRE=%s, JDK=%s", cp, safeMatch, jdkMatch)
            .isEqualTo(jdkMatch);
      }
    }

    @Test
    @DisplayName("\\H matches same characters as JDK")
    void bigHMatchesSameAsJdk() {
      Pattern safeP = Pattern.compile("\\H");
      java.util.regex.Pattern jdkP = java.util.regex.Pattern.compile("\\H");
      for (int cp = 0; cp <= 0x3100; cp++) {
        String s = new String(Character.toChars(cp));
        boolean safeMatch = safeP.matcher(s).matches();
        boolean jdkMatch = jdkP.matcher(s).matches();
        assertThat(safeMatch)
            .as("\\H U+%04X: SafeRE=%s, JDK=%s", cp, safeMatch, jdkMatch)
            .isEqualTo(jdkMatch);
      }
    }

    @Test
    @DisplayName("\\V matches same characters as JDK")
    void bigVMatchesSameAsJdk() {
      Pattern safeP = Pattern.compile("\\V");
      java.util.regex.Pattern jdkP = java.util.regex.Pattern.compile("\\V");
      for (int cp = 0; cp <= 0x3000; cp++) {
        String s = new String(Character.toChars(cp));
        boolean safeMatch = safeP.matcher(s).matches();
        boolean jdkMatch = jdkP.matcher(s).matches();
        assertThat(safeMatch)
            .as("\\V U+%04X: SafeRE=%s, JDK=%s", cp, safeMatch, jdkMatch)
            .isEqualTo(jdkMatch);
      }
    }
  }
}
