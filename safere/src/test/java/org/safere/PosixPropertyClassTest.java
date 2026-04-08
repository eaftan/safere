// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for POSIX character classes accessed via the {@code \p{...}} property syntax (e.g., {@code
 * \p{Lower}}, {@code \p{Upper}}, etc.). These are the 13 POSIX classes defined in the JDK's {@code
 * java.util.regex.Pattern} documentation. Regression test for <a
 * href="https://github.com/eaftan/safere/issues/112">#112</a>.
 */
@DisplayName("POSIX \\p{...} property classes")
class PosixPropertyClassTest {

  /** All 13 POSIX character class names from the JDK Pattern javadoc. */
  private static final String[] ALL_POSIX_CLASS_NAMES = {
    "Lower", "Upper", "ASCII", "Alpha", "Digit", "Alnum", "Punct",
    "Graph", "Print", "Blank", "Cntrl", "XDigit", "Space",
  };

  record TestCase(String className, String input, boolean expectedMatch) {
    @Override
    public String toString() {
      String displayInput = input.length() == 1 && input.charAt(0) < 0x20
          ? String.format("\\x%02X", (int) input.charAt(0))
          : input;
      return "\\p{" + className + "} on \"" + displayInput + "\" → " + expectedMatch;
    }
  }

  /**
   * Cross-checks SafeRE against {@code java.util.regex} for representative inputs across all 13
   * POSIX classes. Each class is tested with positive matches, negative matches, and boundary
   * cases.
   */
  static Stream<TestCase> crossCheckCases() {
    return Stream.of(
        // Lower: [a-z]
        new TestCase("Lower", "a", true),
        new TestCase("Lower", "z", true),
        new TestCase("Lower", "m", true),
        new TestCase("Lower", "A", false),
        new TestCase("Lower", "Z", false),
        new TestCase("Lower", "0", false),
        new TestCase("Lower", " ", false),
        new TestCase("Lower", "\u00E9", false), // é — NOT matched without UNICODE_CHARACTER_CLASS

        // Upper: [A-Z]
        new TestCase("Upper", "A", true),
        new TestCase("Upper", "Z", true),
        new TestCase("Upper", "M", true),
        new TestCase("Upper", "a", false),
        new TestCase("Upper", "z", false),
        new TestCase("Upper", "0", false),
        new TestCase("Upper", "\u00C9", false), // É — NOT matched without UNICODE_CHARACTER_CLASS

        // ASCII: [\x00-\x7F]
        new TestCase("ASCII", "\u0000", true),
        new TestCase("ASCII", " ", true),
        new TestCase("ASCII", "a", true),
        new TestCase("ASCII", "~", true),
        new TestCase("ASCII", "\u007F", true),
        new TestCase("ASCII", "\u0080", false),
        new TestCase("ASCII", "\u00FF", false),

        // Alpha: [a-zA-Z]
        new TestCase("Alpha", "a", true),
        new TestCase("Alpha", "Z", true),
        new TestCase("Alpha", "0", false),
        new TestCase("Alpha", " ", false),
        new TestCase("Alpha", "!", false),

        // Digit: [0-9]
        new TestCase("Digit", "0", true),
        new TestCase("Digit", "9", true),
        new TestCase("Digit", "5", true),
        new TestCase("Digit", "a", false),
        new TestCase("Digit", " ", false),
        new TestCase("Digit", "\u0660", false), // Arabic-Indic digit — NOT matched (ASCII only)

        // Alnum: [a-zA-Z0-9]
        new TestCase("Alnum", "a", true),
        new TestCase("Alnum", "Z", true),
        new TestCase("Alnum", "5", true),
        new TestCase("Alnum", " ", false),
        new TestCase("Alnum", "!", false),

        // Punct: !"#$%&'()*+,-./:;<=>?@[\]^_`{|}~
        new TestCase("Punct", "!", true),
        new TestCase("Punct", ".", true),
        new TestCase("Punct", "@", true),
        new TestCase("Punct", "~", true),
        new TestCase("Punct", "{", true),
        new TestCase("Punct", "a", false),
        new TestCase("Punct", "0", false),
        new TestCase("Punct", " ", false),

        // Graph: [\p{Alnum}\p{Punct}] i.e. [!-~]
        new TestCase("Graph", "a", true),
        new TestCase("Graph", "0", true),
        new TestCase("Graph", "!", true),
        new TestCase("Graph", "~", true),
        new TestCase("Graph", " ", false),
        new TestCase("Graph", "\t", false),

        // Print: [\p{Graph}\x20] i.e. [ -~]
        new TestCase("Print", "a", true),
        new TestCase("Print", "0", true),
        new TestCase("Print", "!", true),
        new TestCase("Print", " ", true),
        new TestCase("Print", "\t", false),
        new TestCase("Print", "\n", false),

        // Blank: [ \t]
        new TestCase("Blank", " ", true),
        new TestCase("Blank", "\t", true),
        new TestCase("Blank", "a", false),
        new TestCase("Blank", "\n", false),
        new TestCase("Blank", "\r", false),

        // Cntrl: [\x00-\x1F\x7F]
        new TestCase("Cntrl", "\u0000", true),
        new TestCase("Cntrl", "\u001F", true),
        new TestCase("Cntrl", "\u007F", true),
        new TestCase("Cntrl", "\u0001", true),
        new TestCase("Cntrl", " ", false),
        new TestCase("Cntrl", "a", false),

        // XDigit: [0-9a-fA-F]
        new TestCase("XDigit", "0", true),
        new TestCase("XDigit", "9", true),
        new TestCase("XDigit", "a", true),
        new TestCase("XDigit", "f", true),
        new TestCase("XDigit", "A", true),
        new TestCase("XDigit", "F", true),
        new TestCase("XDigit", "g", false),
        new TestCase("XDigit", "G", false),
        new TestCase("XDigit", " ", false),

        // Space: [ \t\n\x0B\f\r]
        new TestCase("Space", " ", true),
        new TestCase("Space", "\t", true),
        new TestCase("Space", "\n", true),
        new TestCase("Space", "\u000B", true), // vertical tab
        new TestCase("Space", "\f", true),
        new TestCase("Space", "\r", true),
        new TestCase("Space", "a", false),
        new TestCase("Space", "0", false));
  }

  @ParameterizedTest
  @MethodSource("crossCheckCases")
  void matchesJdkBehavior(TestCase tc) {
    // Verify JDK expectation first
    var jdkPattern = java.util.regex.Pattern.compile("\\p{" + tc.className + "}");
    assertThat(jdkPattern.matcher(tc.input).matches())
        .as("JDK baseline for \\p{%s} on \"%s\"", tc.className, tc.input)
        .isEqualTo(tc.expectedMatch);

    // SafeRE must match
    var safePattern = Pattern.compile("\\p{" + tc.className + "}");
    assertThat(safePattern.matcher(tc.input).matches())
        .as("SafeRE for \\p{%s} on \"%s\"", tc.className, tc.input)
        .isEqualTo(tc.expectedMatch);
  }

  @Test
  @DisplayName("All 13 POSIX class names are accepted by Pattern.compile")
  void allClassNamesAccepted() {
    for (String name : ALL_POSIX_CLASS_NAMES) {
      Pattern.compile("\\p{" + name + "}");
    }
  }

  @Nested
  @DisplayName("Negation")
  class NegationTests {

    @Test
    @DisplayName("\\P{Lower} matches non-lowercase")
    void upperPNegation() {
      var p = Pattern.compile("\\P{Lower}");
      assertThat(p.matcher("A").matches()).isTrue();
      assertThat(p.matcher("0").matches()).isTrue();
      assertThat(p.matcher(" ").matches()).isTrue();
      assertThat(p.matcher("a").matches()).isFalse();
    }

    @Test
    @DisplayName("\\P{Digit} matches non-digit")
    void upperPDigitNegation() {
      var p = Pattern.compile("\\P{Digit}");
      assertThat(p.matcher("a").matches()).isTrue();
      assertThat(p.matcher(" ").matches()).isTrue();
      assertThat(p.matcher("0").matches()).isFalse();
      assertThat(p.matcher("9").matches()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Lower", "Upper", "ASCII", "Alpha", "Digit", "Alnum", "Punct",
        "Graph", "Print", "Blank", "Cntrl", "XDigit", "Space"})
    @DisplayName("\\P{...} negation compiles for all POSIX classes")
    void allNegationsCompile(String className) {
      Pattern.compile("\\P{" + className + "}");
    }
  }

  @Nested
  @DisplayName("In character classes")
  class InCharacterClassTests {

    @Test
    @DisplayName("\\p{Lower} works inside a character class")
    void posixInsideCharClass() {
      var p = Pattern.compile("[\\p{Lower}\\p{Digit}]+");
      assertThat(p.matcher("abc123").matches()).isTrue();
      assertThat(p.matcher("ABC").matches()).isFalse();
    }

    @Test
    @DisplayName("\\p{Punct} combined with literal characters")
    void posixCombinedWithLiterals() {
      var p = Pattern.compile("[\\p{Punct}a]+");
      assertThat(p.matcher("a!.@").matches()).isTrue();
      assertThat(p.matcher("b").matches()).isFalse();
    }
  }

  @Nested
  @DisplayName("In compound patterns")
  class CompoundPatternTests {

    @Test
    @DisplayName("\\p{Alpha}\\p{Digit}+ matches identifier-like strings")
    void alphaDigit() {
      var p = Pattern.compile("\\p{Alpha}\\p{Digit}+");
      assertThat(p.matcher("a123").matches()).isTrue();
      assertThat(p.matcher("123").matches()).isFalse();
      assertThat(p.matcher("abc").matches()).isFalse();
    }

    @Test
    @DisplayName("Quantified POSIX class in find mode")
    void findMode() {
      var m = Pattern.compile("\\p{XDigit}+").matcher("zz=DEADBEEF;");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("DEADBEEF");
    }

    @Test
    @DisplayName("Alternation with POSIX classes")
    void alternation() {
      var p = Pattern.compile("\\p{Lower}+|\\p{Digit}+");
      assertThat(p.matcher("abc").matches()).isTrue();
      assertThat(p.matcher("123").matches()).isTrue();
      assertThat(p.matcher("ABC").matches()).isFalse();
    }
  }

  @Nested
  @DisplayName("Exhaustive ASCII scan")
  class ExhaustiveAsciiScanTests {

    /**
     * For each POSIX class, scan all 128 ASCII code points and verify SafeRE matches the JDK on
     * every single one.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Lower", "Upper", "ASCII", "Alpha", "Digit", "Alnum", "Punct",
        "Graph", "Print", "Blank", "Cntrl", "XDigit", "Space"})
    @DisplayName("Exhaustive ASCII scan matches JDK for all 128 code points")
    void exhaustiveAsciiScan(String className) {
      var jdkPattern = java.util.regex.Pattern.compile("\\p{" + className + "}");
      var safePattern = Pattern.compile("\\p{" + className + "}");

      for (int cp = 0; cp <= 127; cp++) {
        String s = new String(Character.toChars(cp));
        boolean jdkMatch = jdkPattern.matcher(s).matches();
        boolean safeMatch = safePattern.matcher(s).matches();
        assertThat(safeMatch)
            .as("\\p{%s} on U+%04X: JDK=%s SafeRE=%s", className, cp, jdkMatch, safeMatch)
            .isEqualTo(jdkMatch);
      }
    }
  }
}
