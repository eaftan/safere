// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@code \p{java...}} character classes that delegate to {@link java.lang.Character}
 * predicate methods. Regression test for <a
 * href="https://github.com/eaftan/safere/issues/106">#106</a>.
 */
@DisplayName("Java character classes (\\p{java...})")
class JavaCharacterClassesTest {

  /** All 18 java character class names supported by {@code java.util.regex.Pattern}. */
  private static final String[] ALL_JAVA_CLASS_NAMES = {
    "javaLowerCase",
    "javaUpperCase",
    "javaWhitespace",
    "javaMirrored",
    "javaAlphabetic",
    "javaIdeographic",
    "javaTitleCase",
    "javaDigit",
    "javaDefined",
    "javaLetter",
    "javaLetterOrDigit",
    "javaSpaceChar",
    "javaISOControl",
    "javaIdentifierIgnorable",
    "javaUnicodeIdentifierStart",
    "javaUnicodeIdentifierPart",
    "javaJavaIdentifierStart",
    "javaJavaIdentifierPart",
  };

  record TestCase(String className, String input, boolean expectedMatch) {
    @Override
    public String toString() {
      return "\\p{" + className + "} on \"" + input + "\" → " + expectedMatch;
    }
  }

  /** Cross-checks SafeRE against {@code java.util.regex} for a representative sample. */
  static Stream<TestCase> crossCheckCases() {
    return Stream.of(
        // javaLowerCase
        new TestCase("javaLowerCase", "abc", true),
        new TestCase("javaLowerCase", "ABC", false),
        new TestCase("javaLowerCase", "123", false),
        new TestCase("javaLowerCase", "\u00E9", true), // é (Latin small letter e with acute)

        // javaUpperCase
        new TestCase("javaUpperCase", "ABC", true),
        new TestCase("javaUpperCase", "abc", false),
        new TestCase("javaUpperCase", "\u00C9", true), // É

        // javaWhitespace
        new TestCase("javaWhitespace", " ", true),
        new TestCase("javaWhitespace", "\t", true),
        new TestCase("javaWhitespace", "\n", true),
        new TestCase("javaWhitespace", "a", false),
        new TestCase("javaWhitespace", "\u00A0", false), // NBSP is NOT whitespace per Character

        // javaMirrored
        new TestCase("javaMirrored", "(", true),
        new TestCase("javaMirrored", ")", true),
        new TestCase("javaMirrored", "[", true),
        new TestCase("javaMirrored", "a", false),

        // javaAlphabetic
        new TestCase("javaAlphabetic", "abc", true),
        new TestCase("javaAlphabetic", "123", false),
        new TestCase("javaAlphabetic", "\u00E9", true),

        // javaIdeographic
        new TestCase("javaIdeographic", "\u4E00", true), // CJK Unified Ideograph
        new TestCase("javaIdeographic", "a", false),

        // javaTitleCase
        new TestCase("javaTitleCase", "\u01C5", true), // Dz with caron (titlecase)
        new TestCase("javaTitleCase", "a", false),

        // javaDigit
        new TestCase("javaDigit", "0", true),
        new TestCase("javaDigit", "9", true),
        new TestCase("javaDigit", "a", false),
        new TestCase("javaDigit", "\u0660", true), // Arabic-Indic digit zero

        // javaDefined
        new TestCase("javaDefined", "a", true),
        new TestCase("javaDefined", "0", true),

        // javaLetter
        new TestCase("javaLetter", "a", true),
        new TestCase("javaLetter", "0", false),
        new TestCase("javaLetter", "\u00E9", true),

        // javaLetterOrDigit
        new TestCase("javaLetterOrDigit", "a", true),
        new TestCase("javaLetterOrDigit", "0", true),
        new TestCase("javaLetterOrDigit", " ", false),

        // javaSpaceChar
        new TestCase("javaSpaceChar", " ", true),
        new TestCase("javaSpaceChar", "\u00A0", true), // NBSP IS a space char
        new TestCase("javaSpaceChar", "a", false),

        // javaISOControl
        new TestCase("javaISOControl", "\u0000", true),
        new TestCase("javaISOControl", "\u001F", true),
        new TestCase("javaISOControl", "a", false),

        // javaIdentifierIgnorable
        new TestCase("javaIdentifierIgnorable", "\u0000", true),
        new TestCase("javaIdentifierIgnorable", "a", false),

        // javaUnicodeIdentifierStart
        new TestCase("javaUnicodeIdentifierStart", "a", true),
        new TestCase("javaUnicodeIdentifierStart", "0", false),

        // javaUnicodeIdentifierPart
        new TestCase("javaUnicodeIdentifierPart", "a", true),
        new TestCase("javaUnicodeIdentifierPart", "0", true),

        // javaJavaIdentifierStart
        new TestCase("javaJavaIdentifierStart", "a", true),
        new TestCase("javaJavaIdentifierStart", "$", true),
        new TestCase("javaJavaIdentifierStart", "_", true),
        new TestCase("javaJavaIdentifierStart", "0", false),

        // javaJavaIdentifierPart
        new TestCase("javaJavaIdentifierPart", "a", true),
        new TestCase("javaJavaIdentifierPart", "0", true),
        new TestCase("javaJavaIdentifierPart", " ", false));
  }

  @ParameterizedTest
  @MethodSource("crossCheckCases")
  void matchesJdkBehavior(TestCase tc) {
    // Verify JDK expectation first
    var jdkPattern = java.util.regex.Pattern.compile("\\p{" + tc.className + "}+");
    assertThat(jdkPattern.matcher(tc.input).find())
        .as("JDK baseline for \\p{%s} on \"%s\"", tc.className, tc.input)
        .isEqualTo(tc.expectedMatch);

    // SafeRE must match
    var safePattern = Pattern.compile("\\p{" + tc.className + "}+");
    assertThat(safePattern.matcher(tc.input).find())
        .as("SafeRE for \\p{%s} on \"%s\"", tc.className, tc.input)
        .isEqualTo(tc.expectedMatch);
  }

  @Test
  @DisplayName("All 18 java character class names are accepted")
  void allClassNamesAccepted() {
    for (String name : ALL_JAVA_CLASS_NAMES) {
      Pattern.compile("\\p{" + name + "}");
    }
  }

  @Test
  @DisplayName("Negation with \\P{...} works")
  void negationWorks() {
    var p = Pattern.compile("\\P{javaLowerCase}+");
    assertThat(p.matcher("ABC").find()).isTrue();
    assertThat(p.matcher("abc").find()).isFalse();
  }

  @Test
  @DisplayName("Caret negation in java character class names is rejected")
  void caretNegationRejected() {
    assertThatThrownBy(() -> Pattern.compile("\\p{^javaLowerCase}+"))
        .isInstanceOf(PatternSyntaxException.class);
  }

  @Test
  @DisplayName("\\p{javaLowerCase}+ matches lowercase text")
  void javaLowerCaseMatchesLowercaseText() {
    // Regression for issue #106.
    var p = Pattern.compile("\\p{javaLowerCase}+", Pattern.UNICODE_CHARACTER_CLASS);
    assertThat(p.matcher("foo").find()).isTrue();
  }
}
