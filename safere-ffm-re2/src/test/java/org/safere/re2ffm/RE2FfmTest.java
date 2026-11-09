// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.re2ffm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class RE2FfmTest {

  @Test
  void testSimpleSplit() {
    RE2FfmPattern p = RE2FfmPattern.compile(",");
    String[] parts = p.split("a,b,c");
    assertThat(parts).isEqualTo(new String[] {"a", "b", "c"});
  }

  @Test
  void testSplitLimit() {
    RE2FfmPattern p = RE2FfmPattern.compile(",");
    String[] parts = p.split("a,b,c", 2);
    assertThat(parts).isEqualTo(new String[] {"a", "b,c"});
  }

  @Test
  void testNonAsciiMapping() {
    // "hello, \u4e16\u754c" ("hello, 世界")
    // "世界" starts at char offset 7.
    // "世" in UTF-8 is 3 bytes: \xE4\xB8\x96.
    // "界" in UTF-8 is 3 bytes: \xE5\x85\x8C.
    RE2FfmPattern p = RE2FfmPattern.compile("世界");
    RE2FfmMatcher m = p.matcher("hello, 世界!");

    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(7);
    assertThat(m.end()).isEqualTo(9);
    assertThat(m.group()).isEqualTo("世界");
  }

  @Test
  void testSurrogatePairs() {
    // \uD83D\uDE00 is the emoji grinning face 😀 (1 character point, 2 Java chars, 4 UTF-8 bytes)
    String text = "hello \uD83D\uDE00 world";
    RE2FfmPattern p = RE2FfmPattern.compile("\uD83D\uDE00");
    RE2FfmMatcher m = p.matcher(text);

    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(6);
    assertThat(m.end()).isEqualTo(8);
    assertThat(m.group()).isEqualTo("\uD83D\uDE00");

    // Split around surrogate
    String[] parts = p.split(text);
    assertThat(parts).isEqualTo(new String[] {"hello ", " world"});
  }

  @Test
  void testReplaceAll() {
    RE2FfmPattern p = RE2FfmPattern.compile("(\\w+)");
    assertThat(p.matcher("hello world").replaceAll("X")).isEqualTo("X X");
    assertThat(p.matcher("hello world").replaceAll("[$1]")).isEqualTo("[hello] [world]");
  }

  @Test
  void testReplaceFirst() {
    RE2FfmPattern p = RE2FfmPattern.compile("(\\w+)");
    assertThat(p.matcher("hello world").replaceFirst("X")).isEqualTo("X world");
    assertThat(p.matcher("hello world").replaceFirst("[$1]")).isEqualTo("[hello] world");
  }

  @Test
  void testReplaceAllMultiDigitGroup() {
    RE2FfmPattern p = RE2FfmPattern.compile("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)");
    assertThat(p.matcher("xabcdefghijy").replaceAll("$10")).isEqualTo("xjy");
  }

  @Test
  void testReplaceFirstMultiDigitGroup() {
    RE2FfmPattern p = RE2FfmPattern.compile("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)");
    assertThat(p.matcher("xabcdefghijy").replaceFirst("$10")).isEqualTo("xjy");
  }

  @Test
  void testReplaceAllStateDiscrepancy() {
    RE2FfmPattern p = RE2FfmPattern.compile("a");
    RE2FfmMatcher m = p.matcher("aba");
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("a");

    assertThat(m.replaceAll("x")).isEqualTo("xbx");

    assertThatThrownBy(m::group).isInstanceOf(IllegalStateException.class);

    assertThat(m.find()).isFalse();
  }

  @Test
  void testReplaceAllStateDiscrepancyJavaFallback() {
    RE2FfmPattern p = RE2FfmPattern.compile("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)");
    RE2FfmMatcher m = p.matcher("xabcdefghijy");
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("abcdefghij");

    assertThat(m.replaceAll("x")).isEqualTo("xxy");

    assertThatThrownBy(m::group).isInstanceOf(IllegalStateException.class);

    assertThat(m.find()).isFalse();
  }

  @Test
  void testReplacementReferencesMatchJdk() {
    String regex = "(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)";
    String input = "abcdefghij-abcdefghij";
    String[] replacements = {"$10", "$11", "$101", "$00", "$01", "$09", "\\$1", "\\\\$1"};
    RE2FfmPattern actualPattern = RE2FfmPattern.compile(regex);
    java.util.regex.Pattern expectedPattern = java.util.regex.Pattern.compile(regex);

    for (String replacement : replacements) {
      assertThat(actualPattern.matcher(input).replaceFirst(replacement))
          .as(replacement)
          .isEqualTo(expectedPattern.matcher(input).replaceFirst(replacement));
      assertThat(actualPattern.matcher(input).replaceAll(replacement))
          .as(replacement)
          .isEqualTo(expectedPattern.matcher(input).replaceAll(replacement));
    }
  }

  @Test
  void testZeroWidthUnicodeReplacementMatchesJdk() {
    String regex = "()()()()()()()()()()";
    String input = "é";
    RE2FfmPattern actualPattern = RE2FfmPattern.compile(regex);
    java.util.regex.Pattern expectedPattern = java.util.regex.Pattern.compile(regex);

    assertThat(actualPattern.matcher(input).replaceAll("x"))
        .isEqualTo(expectedPattern.matcher(input).replaceAll("x"));
  }

  @Test
  void testReplaceAllExhaustsTerminalEmptyMatch() {
    RE2FfmMatcher matcher = RE2FfmPattern.compile("$").matcher("text");

    assertThat(matcher.replaceAll("x")).isEqualTo("textx");

    assertThat(matcher.find()).isFalse();
  }
}
