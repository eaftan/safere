// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests ported from RE2 C++ {@code re2_test.cc}. Focuses on bug regression tests, edge cases,
 * Unicode classes, replace operations, named groups, and error handling.
 */
@DisplayName("RE2 C++ re2_test.cc Ports")
class RE2RegressionTest {

  // ---------------------------------------------------------------------------
  // Replace / ReplaceAll
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Replace operations")
  class ReplaceTests {

    record ReplaceCase(
        String pattern,
        String rewrite,
        String original,
        String expectedFirst,
        String expectedAll,
        int expectedCount) {}

    static Stream<Arguments> replaceCases() {
      return Stream.of(
          Arguments.of(
              "(qu|[b-df-hj-np-tv-z]*)([a-z]+)",
              "$2$1ay",
              "the quick brown fox jumps over the lazy dogs.",
              "ethay quick brown fox jumps over the lazy dogs.",
              "ethay ickquay ownbray oxfay umpsjay overay ethay azylay ogsday.",
              9),
          Arguments.of(
              "\\w+",
              "$0-NOSPAM",
              "abcd.efghi@google.com",
              "abcd-NOSPAM.efghi@google.com",
              "abcd-NOSPAM.efghi-NOSPAM@google-NOSPAM.com-NOSPAM",
              4),
          Arguments.of(
              "b",
              "bb",
              "ababababab",
              "abbabababab",
              "abbabbabbabbabb",
              5),
          Arguments.of(
              "b",
              "bb",
              "bbbbbb",
              "bbbbbbb",
              "bbbbbbbbbbbb",
              6),
          Arguments.of(
              "b+",
              "bb",
              "bbbbbb",
              "bb",
              "bb",
              1),
          // Note: C++ RE2 gives replaceAll="bb"/count=1, but Java gives "bbbb"/count=2
          // because Java includes the empty match at the end after a non-empty match.
          Arguments.of(
              "b*",
              "bb",
              "bbbbbb",
              "bb",
              "bbbb",
              2),
          Arguments.of(
              "b*",
              "bb",
              "aaaaa",
              "bbaaaaa",
              "bbabbabbabbabbabb",
              6));
    }

    @ParameterizedTest
    @MethodSource("replaceCases")
    void replaceFirstAndAll(
        String pattern,
        String rewrite,
        String original,
        String expectedFirst,
        String expectedAll,
        int expectedCount) {
      Matcher m = Pattern.compile(pattern).matcher(original);

      // replaceFirst
      String first = m.replaceFirst(rewrite);
      assertThat(first).isEqualTo(expectedFirst);

      // replaceAll — Java semantics (differs from C++ RE2 for empty matches after non-empty)
      m.reset();
      String all = m.replaceAll(rewrite);
      assertThat(all).isEqualTo(expectedAll);
    }

    @Test
    @DisplayName("Replace with $ anchor on empty string")
    void replaceEndAnchorEmpty() {
      Matcher m = Pattern.compile("$").matcher("");
      assertThat(m.replaceFirst("(END)")).isEqualTo("(END)");
      m.reset();
      assertThat(m.replaceAll("(END)")).isEqualTo("(END)");
    }

    @Test
    @DisplayName("Replace with newline handling (dot doesn't match newline)")
    void replaceNewlineHandling() {
      Matcher m = Pattern.compile("a.*a").matcher("aba\naba");
      assertThat(m.replaceFirst("($0)")).isEqualTo("(aba)\naba");
      m.reset();
      assertThat(m.replaceAll("($0)")).isEqualTo("(aba)\n(aba)");
    }
  }

  // ---------------------------------------------------------------------------
  // Empty charsets
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Empty charsets")
  class EmptyCharsetTests {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "[^\\S\\s]",
          "[^\\S\\p{Zs}]",
          "[^\\D\\d]",
          "[^\\D\\p{Nd}]"
        })
    void emptyCharsetNeverMatches(String pattern) {
      Matcher m = Pattern.compile(pattern).matcher("abc");
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("BitState assumptions: empty charset in captures")
    void bitstateAssumptions() {
      // Captures trigger BitState. Empty charset alternated with nothing should still match.
      String[] patterns = {
          "((((()))))[^\\S\\s]?",
          "((((()))))([^\\S\\s])?",
          "((((()))))([^\\S\\s]|[^\\S\\s])?",
          "((((()))))((([^\\S\\s]|[^\\S\\s])|))"
      };
      for (String pat : patterns) {
        assertThat(Pattern.compile(pat).matcher("").matches())
            .as("Pattern %s should match empty string", pat)
            .isTrue();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Named groups
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Named capturing groups")
  class NamedGroupTests {

    @Test
    @DisplayName("Unnamed group has no named captures")
    void unnamedGroupHasNoNames() {
      Pattern p = Pattern.compile("(hello world)");
      Matcher m = p.matcher("");
      assertThat(m.groupCount()).isEqualTo(1);
      assertThat(p.namedGroups()).isEmpty();
    }

    @Test
    @DisabledForCrosscheck("(?P<name>...) named groups are SafeRE syntax, not JDK syntax")
    @DisplayName("Multiple named and unnamed groups")
    void multipleNamedGroups() {
      Pattern p = Pattern.compile("(?P<A>expr(?P<B>expr)(?P<C>expr))((expr)(?P<D>expr))");
      Matcher m = p.matcher("");
      assertThat(m.groupCount()).isEqualTo(6);
      Map<String, Integer> names = p.namedGroups();
      assertThat(names).hasSize(4);
      assertThat(names.get("A")).isEqualTo(1);
      assertThat(names.get("B")).isEqualTo(2);
      assertThat(names.get("C")).isEqualTo(3);
      assertThat(names.get("D")).isEqualTo(6); // $4 and $5 are anonymous
    }

    @Test
    @DisabledForCrosscheck("(?P<name>...) named groups are SafeRE syntax, not JDK syntax")
    @DisplayName("Named group match extraction")
    void namedGroupMatch() {
      Pattern p = Pattern.compile("directions from (?P<S>.*) to (?P<D>.*)");
      Matcher m = p.matcher("directions from mountain view to san jose");
      assertThat(m.groupCount()).isEqualTo(2);
      assertThat(m.matches()).isTrue();

      Map<String, Integer> names = p.namedGroups();
      assertThat(names.get("S")).isEqualTo(1);
      assertThat(names.get("D")).isEqualTo(2);

      assertThat(m.group(names.get("S"))).isEqualTo("mountain view");
      assertThat(m.group(names.get("D"))).isEqualTo("san jose");
    }
  }

  // ---------------------------------------------------------------------------
  // Unicode classes (Bug 609710 port)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Unicode character classes")
  class UnicodeClassTests {

    @Test
    @DisplayName("\\p{L}, \\p{Lu}, \\p{Ll} for ASCII uppercase")
    void asciiUppercase() {
      assertThat(Pattern.matches("\\p{L}", "A")).isTrue();
      assertThat(Pattern.matches("\\p{Lu}", "A")).isTrue();
      assertThat(Pattern.matches("\\p{Ll}", "A")).isFalse();
      assertThat(Pattern.matches("\\P{L}", "A")).isFalse();
      assertThat(Pattern.matches("\\P{Lu}", "A")).isFalse();
      assertThat(Pattern.matches("\\P{Ll}", "A")).isTrue();
    }

    @Test
    @DisplayName("\\p{L} for CJK characters (Lo category)")
    void cjkCharacters() {
      for (String ch : new String[] {"譚", "永", "鋒"}) {
        assertThat(Pattern.matches("\\p{L}", ch))
            .as("\\p{L} matches %s", ch)
            .isTrue();
        assertThat(Pattern.matches("\\p{Lu}", ch))
            .as("\\p{Lu} does not match %s", ch)
            .isFalse();
        assertThat(Pattern.matches("\\p{Ll}", ch))
            .as("\\p{Ll} does not match %s", ch)
            .isFalse();
        assertThat(Pattern.matches("\\P{L}", ch))
            .as("\\P{L} does not match %s", ch)
            .isFalse();
        assertThat(Pattern.matches("\\P{Lu}", ch))
            .as("\\P{Lu} matches %s", ch)
            .isTrue();
        assertThat(Pattern.matches("\\P{Ll}", ch))
            .as("\\P{Ll} matches %s", ch)
            .isTrue();
      }
    }

    @Test
    @DisplayName("Partial match with Unicode and \\p{Lu}\\p{Lo}")
    void partialMatchUnicode() {
      String str = "ABCDEFGHI譚永鋒";
      Pattern pLazy = Pattern.compile("(.).*?(.).*?(.)");
      Matcher m = pLazy.matcher(str);
      assertThat(m.find()).isTrue();
      assertThat(m.group(1)).isEqualTo("A");
      assertThat(m.group(2)).isEqualTo("B");
      assertThat(m.group(3)).isEqualTo("C");

      Pattern pUniLetter = Pattern.compile("(.).*?([\\p{L}]).*?(.)");
      m = pUniLetter.matcher(str);
      assertThat(m.find()).isTrue();
      assertThat(m.group(1)).isEqualTo("A");
      assertThat(m.group(2)).isEqualTo("B");
      assertThat(m.group(3)).isEqualTo("C");

      assertThat(Pattern.compile("\\P{L}").matcher(str).find())
          .as("No non-letter characters in the string")
          .isFalse();

      // Greedy .*  then Lu|Lo captures Chinese chars
      Pattern pGreedyLu = Pattern.compile(".*(.).*?([\\p{Lu}\\p{Lo}]).*?(.)");
      m = pGreedyLu.matcher(str);
      assertThat(m.find()).isTrue();
      assertThat(m.group(1)).isEqualTo("譚");
      assertThat(m.group(2)).isEqualTo("永");
      assertThat(m.group(3)).isEqualTo("鋒");
    }
  }

  // ---------------------------------------------------------------------------
  // Complicated patterns and edge cases
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Complicated patterns and edge cases")
  class EdgeCaseTests {

    @Test
    @DisplayName("Full match with alternations and char classes")
    void complicatedAlternation() {
      assertThat(Pattern.matches("foo|bar|[A-Z]", "foo")).isTrue();
      assertThat(Pattern.matches("foo|bar|[A-Z]", "bar")).isTrue();
      assertThat(Pattern.matches("foo|bar|[A-Z]", "X")).isTrue();
      assertThat(Pattern.matches("foo|bar|[A-Z]", "XY")).isFalse();
    }

    @Test
    @DisplayName("Full match with $ in alternation")
    void fullMatchEnd() {
      assertThat(Pattern.matches("fo|foo", "fo")).isTrue();
      assertThat(Pattern.matches("fo|foo", "foo")).isTrue();
      assertThat(Pattern.matches("fo|foo$", "fo")).isTrue();
      assertThat(Pattern.matches("fo|foo$", "foo")).isTrue();
      assertThat(Pattern.matches("foo$", "foo")).isTrue();
      assertThat(Pattern.matches("fo|bar", "fox")).isFalse();
    }

    @Test
    @DisplayName("UTF-8 three-character string matches dot patterns")
    void utf8ThreeChars() {
      // Three Japanese characters: 日本語
      String nihongo = "\u65E5\u672C\u8A9E";
      assertThat(Pattern.matches("...", nihongo)).isTrue();
      // Each dot matches one code point
      Matcher m = Pattern.compile("(.)").matcher(nihongo);
      assertThat(m.find()).isTrue();
      assertThat(m.group(1)).isEqualTo("\u65E5");
    }

    @Test
    @DisplayName("Case-insensitive match (OnePass case folding)")
    void caseInsensitiveMatch() {
      // Port of CaseInsensitive.MatchAndConsume — validates OnePass case-folding.
      Pattern p = Pattern.compile("(?i)([wand]{5})");
      String text = "A fish named *Wanda*";
      Matcher m = p.matcher(text);
      assertThat(m.find()).isTrue();
      assertThat(m.group(1)).isEqualTo("Wanda");
    }

    @Test
    @DisplayName("Deep nesting doesn't crash")
    void deepNesting() {
      // .{512}x should compile and match
      Pattern p = Pattern.compile(".{512}x");
      String s = "c".repeat(515) + "x";
      assertThat(p.matcher(s).find()).isTrue();
    }

    @Test
    @DisabledForCrosscheck("JDK stack overflows on this linear-time SafeRE stress case")
    @DisplayName("Long input with deep recursion pattern")
    void longInputDeepRecursion() {
      // RE2 C++ test: ((?:\s|xx.*\n|x[*](?:\n|.)*?[*]x)*)
      String comment = "x*" + "a".repeat(8192) + "*x";
      Pattern p = Pattern.compile("((?:\\s|xx.*\\n|x[*](?:\\n|.)*?[*]x)*)");
      assertThat(p.matcher(comment).matches()).isTrue();
    }

    @Test
    @DisplayName("Big counted repetition")
    void bigCountedRepetition() {
      Pattern p = Pattern.compile("[a-z]{1000}x");
      String s = "a".repeat(1000) + "x";
      assertThat(p.matcher(s).matches()).isTrue();
      assertThat(p.matcher("a".repeat(999) + "x").matches()).isFalse();
    }

    @Test
    @DisplayName("Null vs empty string")
    void nullVsEmptyString() {
      Pattern p = Pattern.compile(".*");
      assertThat(p.matcher("").matches()).isTrue();
    }
  }

  // ---------------------------------------------------------------------------
  // Bug regressions
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Bug regressions")
  class BugRegressions {

    @Test
    @DisplayName("Bug 3061120: Kelvin and long-s case folding")
    void bug3061120KelvinCaseFold() {
      // (?i)\W should not match 'k' or 's' — they are word characters.
      // Bug was caused by Kelvin sign K (U+212A) and Latin long s ſ (U+017F).
      Pattern p = Pattern.compile("(?i)\\W");
      assertThat(p.matcher("x").find()).isFalse();
      assertThat(p.matcher("k").find()).isFalse();
      assertThat(p.matcher("s").find()).isFalse();
    }

    @Test
    @DisplayName("CL8622304: backslash in negated char class with capture")
    void cl8622304BackslashCapture() {
      Matcher m = Pattern.compile("([^\\\\])").matcher("D");
      assertThat(m.matches()).isTrue();
      assertThat(m.group(1)).isEqualTo("D");
    }

    @Test
    @DisplayName("CL8622304: complex pattern with escaped chars")
    void cl8622304ComplexPattern() {
      String text = "bar:1,0x2F,030,4,5;baz:true;fooby:false,true";
      Pattern p = Pattern.compile("(\\w+)(?::((?:[^;\\\\]|\\\\.)*))?;?");
      Matcher m = p.matcher(text);
      assertThat(m.find()).isTrue();
      assertThat(m.group(1)).isEqualTo("bar");
      assertThat(m.group(2)).isEqualTo("1,0x2F,030,4,5");
    }

    @Test
    @DisplayName("nullable alternation repeats match the empty branch first")
    void nullableAlternationRepeatsMatchEmptyBranchFirst() {
      // RE2 regression issue 310.
      // (?:|a)* should match empty (first-match semantics).
      // Bug was that * matched more than + did.
      Pattern star = Pattern.compile("(?:|a)*");
      Matcher mStar = star.matcher("aaa");
      assertThat(mStar.find()).isTrue();
      assertThat(mStar.group()).isEmpty();

      Pattern plus = Pattern.compile("(?:|a)+");
      Matcher mPlus = plus.matcher("aaa");
      assertThat(mPlus.find()).isTrue();
      assertThat(mPlus.group()).isEmpty();
    }

    @Test
    @DisplayName("replaceAll advances correctly past non-ASCII characters")
    void replaceAllAdvancesPastNonAsciiCharacters() {
      // RE2 regression issue 104.
      // Replacing with a* (matches empty too) should not clobber multibyte chars.
      Pattern p = Pattern.compile("a*");
      Matcher m = p.matcher("bc");
      assertThat(m.replaceAll("d")).isEqualTo("dbdcd");

      // Unicode multibyte
      Pattern p2 = Pattern.compile("\u0106*"); // Ć*
      Matcher m2 = p2.matcher("\u0105\u0107");  // ąć
      assertThat(m2.replaceAll("\u0108")).isEqualTo("\u0108\u0105\u0108\u0107\u0108"); // ĈąĈćĈ

      // CJK characters
      Pattern p3 = Pattern.compile("\u5927*"); // 大*
      Matcher m3 = p3.matcher("\u4EBA\u7C7B");  // 人类
      assertThat(m3.replaceAll("\u5C0F")).isEqualTo("\u5C0F\u4EBA\u5C0F\u7C7B\u5C0F"); // 小人小类小
    }

    @Test
    @DisplayName("Bug 1816809: complex nested repetition with captures")
    void bug1816809NestedRepetition() {
      Pattern p = Pattern.compile("(((((llx((-3)|(4)))(;(llx((-3)|(4))))*))))");
      Matcher m = p.matcher("llx-3;llx4");
      assertThat(m.find()).isTrue();
    }
  }

  // ---------------------------------------------------------------------------
  // Rejection of invalid patterns
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Pattern rejection")
  class RejectionTests {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "a\\1",     // backreference
          "a[x",      // unclosed bracket
          "a[z-a]",   // invalid range
          "a(b",      // unclosed paren
          "a\\"       // trailing backslash
        })
    void rejectsInvalidPatterns(String pattern) {
      assertThatThrownBy(() -> Pattern.compile(pattern))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    @DisplayName("No crash on invalid pattern match attempt")
    void noCrashOnBadPattern() {
      assertThatThrownBy(() -> Pattern.compile("a\\"))
          .isInstanceOf(PatternSyntaxException.class);
    }
  }

  // ---------------------------------------------------------------------------
  // Braces and special syntax
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Braces and special syntax")
  class BracesTests {

    @Test
    @DisplayName("Full match with braces")
    void fullMatchBraces() {
      assertThat(Pattern.matches("0[xX][0-9a-fA-F]+\\{?", "0x21")).isTrue();
      assertThat(Pattern.matches("0[xX][0-9a-fA-F]+\\{?", "0x21{")).isTrue();
      assertThat(Pattern.matches("0[xX][0-9a-fA-F]+\\}?", "0x21}")).isTrue();
    }
  }
}
