// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for replace functionality, ported from RE2/J's RE2ReplaceTest.
 *
 * <p>Each test case specifies a pattern, replacement, source text, expected output, and whether to
 * use {@code replaceFirst} (vs. {@code replaceAll}).
 */
@DisplayName("RE2 Replace Tests (ported from RE2/J)")
class RE2ReplaceTest {

  record ReplaceTestCase(
      String pattern,
      String replacement,
      String source,
      String expected,
      boolean replaceFirst) {
    @Override
    public String toString() {
      String method = replaceFirst ? "replaceFirst" : "replaceAll";
      return String.format(
          "%s(\"%s\", \"%s\", \"%s\") -> \"%s\"", method, pattern, replacement, source, expected);
    }
  }

  static Stream<Arguments> replaceTests() {
    return Stream.of(
        // Test empty input and/or replacement,
        // with pattern that matches the empty string.
        replaceAll("", "", "", ""),
        replaceAll("", "x", "", "x"),
        replaceAll("", "", "abc", "abc"),
        replaceAll("", "x", "abc", "xaxbxcx"),

        // Test empty input and/or replacement,
        // with pattern that does not match the empty string.
        replaceAll("b", "", "", ""),
        replaceAll("b", "x", "", ""),
        replaceAll("b", "", "abc", "ac"),
        replaceAll("b", "x", "abc", "axc"),
        replaceAll("y", "", "", ""),
        replaceAll("y", "x", "", ""),
        replaceAll("y", "", "abc", "abc"),
        replaceAll("y", "x", "abc", "abc"),

        // Multibyte characters -- verify that we don't try to match in the middle
        // of a character.
        replaceAll("[a-c]*", "x", "\u65e5", "x\u65e5x"),
        replaceAll("[^\u65e5]", "x", "abc\u65e5def", "xxx\u65e5xxx"),

        // Start and end of a string.
        // Note: SafeRE follows Java empty-match semantics (includes empty matches after
        // non-empty matches at the same position), which differs from RE2/Go behavior.
        // For ^, Java treats it as BEGIN_TEXT (not BEGIN_LINE) without MULTILINE, so ^
        // only matches at position 0. Expected values below reflect Java semantics.
        replaceAll("^[a-c]*", "x", "abcdabc", "xdabc"),
        replaceAll("[a-c]*$", "x", "abcdabc", "abcdxx"),
        replaceAll("^[a-c]*$", "x", "abcdabc", "abcdabc"),
        replaceAll("^[a-c]*", "x", "abc", "x"),
        replaceAll("[a-c]*$", "x", "abc", "xx"),
        replaceAll("^[a-c]*$", "x", "abc", "x"),
        replaceAll("^[a-c]*", "x", "dabce", "xdabce"),
        replaceAll("[a-c]*$", "x", "dabce", "dabcex"),
        replaceAll("^[a-c]*$", "x", "dabce", "dabce"),
        replaceAll("^[a-c]*", "x", "", "x"),
        replaceAll("[a-c]*$", "x", "", "x"),
        replaceAll("^[a-c]*$", "x", "", "x"),
        replaceAll("^[a-c]+", "x", "abcdabc", "xdabc"),
        replaceAll("[a-c]+$", "x", "abcdabc", "abcdx"),
        replaceAll("^[a-c]+$", "x", "abcdabc", "abcdabc"),
        replaceAll("^[a-c]+", "x", "abc", "x"),
        replaceAll("[a-c]+$", "x", "abc", "x"),
        replaceAll("^[a-c]+$", "x", "abc", "x"),
        replaceAll("^[a-c]+", "x", "dabce", "dabce"),
        replaceAll("[a-c]+$", "x", "dabce", "dabce"),
        replaceAll("^[a-c]+$", "x", "dabce", "dabce"),
        replaceAll("^[a-c]+", "x", "", ""),
        replaceAll("[a-c]+$", "x", "", ""),
        replaceAll("^[a-c]+$", "x", "", ""),

        // Other cases.
        replaceAll("abc", "def", "abcdefg", "defdefg"),
        replaceAll("bc", "BC", "abcbcdcdedef", "aBCBCdcdedef"),
        replaceAll("abc", "", "abcdabc", "d"),
        replaceAll("x", "xXx", "xxxXxxx", "xXxxXxxXxXxXxxXxxXx"),
        replaceAll("abc", "d", "", ""),
        replaceAll("abc", "d", "abc", "d"),
        replaceAll(".+", "x", "abc", "x"),
        replaceAll("[a-c]*", "x", "def", "xdxexfx"),
        replaceAll("[a-c]+", "x", "abcbcdcdedef", "xdxdedef"),
        replaceAll("[a-c]*", "x", "abcbcdcdedef", "xxdxxdxexdxexfx"),

        // replaceFirst tests:
        // Test empty input and/or replacement,
        // with pattern that matches the empty string.
        replaceFirst("", "", "", ""),
        replaceFirst("", "x", "", "x"),
        replaceFirst("", "", "abc", "abc"),
        replaceFirst("", "x", "abc", "xabc"),

        // Test empty input and/or replacement,
        // with pattern that does not match the empty string.
        replaceFirst("b", "", "", ""),
        replaceFirst("b", "x", "", ""),
        replaceFirst("b", "", "abc", "ac"),
        replaceFirst("b", "x", "abc", "axc"),
        replaceFirst("y", "", "", ""),
        replaceFirst("y", "x", "", ""),
        replaceFirst("y", "", "abc", "abc"),
        replaceFirst("y", "x", "abc", "abc"),

        // Multibyte characters -- verify that we don't try to match in the middle
        // of a character.
        replaceFirst("[a-c]*", "x", "\u65e5", "x\u65e5"),
        replaceFirst("[^\u65e5]", "x", "abc\u65e5def", "xbc\u65e5def"),

        // Start and end of a string.
        replaceFirst("^[a-c]*", "x", "abcdabc", "xdabc"),
        replaceFirst("[a-c]*$", "x", "abcdabc", "abcdx"),
        replaceFirst("^[a-c]*$", "x", "abcdabc", "abcdabc"),
        replaceFirst("^[a-c]*", "x", "abc", "x"),
        replaceFirst("[a-c]*$", "x", "abc", "x"),
        replaceFirst("^[a-c]*$", "x", "abc", "x"),
        replaceFirst("^[a-c]*", "x", "dabce", "xdabce"),
        replaceFirst("[a-c]*$", "x", "dabce", "dabcex"),
        replaceFirst("^[a-c]*$", "x", "dabce", "dabce"),
        replaceFirst("^[a-c]*", "x", "", "x"),
        replaceFirst("[a-c]*$", "x", "", "x"),
        replaceFirst("^[a-c]*$", "x", "", "x"),
        replaceFirst("^[a-c]+", "x", "abcdabc", "xdabc"),
        replaceFirst("[a-c]+$", "x", "abcdabc", "abcdx"),
        replaceFirst("^[a-c]+$", "x", "abcdabc", "abcdabc"),
        replaceFirst("^[a-c]+", "x", "abc", "x"),
        replaceFirst("[a-c]+$", "x", "abc", "x"),
        replaceFirst("^[a-c]+$", "x", "abc", "x"),
        replaceFirst("^[a-c]+", "x", "dabce", "dabce"),
        replaceFirst("[a-c]+$", "x", "dabce", "dabce"),
        replaceFirst("^[a-c]+$", "x", "dabce", "dabce"),
        replaceFirst("^[a-c]+", "x", "", ""),
        replaceFirst("[a-c]+$", "x", "", ""),
        replaceFirst("^[a-c]+$", "x", "", ""),

        // Other cases.
        replaceFirst("abc", "def", "abcdefg", "defdefg"),
        replaceFirst("bc", "BC", "abcbcdcdedef", "aBCbcdcdedef"),
        replaceFirst("abc", "", "abcdabc", "dabc"),
        replaceFirst("x", "xXx", "xxxXxxx", "xXxxxXxxx"),
        replaceFirst("abc", "d", "", ""),
        replaceFirst("abc", "d", "abc", "d"),
        replaceFirst(".+", "x", "abc", "x"),
        replaceFirst("[a-c]*", "x", "def", "xdef"),
        replaceFirst("[a-c]+", "x", "abcbcdcdedef", "xdcdedef"),
        replaceFirst("[a-c]*", "x", "abcbcdcdedef", "xdcdedef"));
  }

  private static Arguments replaceAll(
      String pattern, String replacement, String source, String expected) {
    return Arguments.of(new ReplaceTestCase(pattern, replacement, source, expected, false));
  }

  private static Arguments replaceFirst(
      String pattern, String replacement, String source, String expected) {
    return Arguments.of(new ReplaceTestCase(pattern, replacement, source, expected, true));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("replaceTests")
  void testReplace(ReplaceTestCase tc) {
    Pattern p = Pattern.compile(tc.pattern());
    Matcher m = p.matcher(tc.source());
    String actual =
        tc.replaceFirst() ? m.replaceFirst(tc.replacement()) : m.replaceAll(tc.replacement());
    assertThat(actual)
        .as(
            "%s(\"%s\", \"%s\", \"%s\")",
            tc.replaceFirst() ? "replaceFirst" : "replaceAll",
            tc.pattern(),
            tc.source(),
            tc.replacement())
        .isEqualTo(tc.expected());
  }
}
