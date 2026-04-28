// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link Pattern}. */
class PatternTest {
  private static final class LiteralCharSequence implements CharSequence {
    private final String value;

    LiteralCharSequence(String value) {
      this.value = value;
    }

    @Override
    public int length() {
      return value.length();
    }

    @Override
    public char charAt(int index) {
      return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return value.subSequence(start, end);
    }
  }

  private static final class MutableCharSequence implements CharSequence {
    private final StringBuilder value;

    MutableCharSequence(String value) {
      this.value = new StringBuilder(value);
    }

    void set(String newValue) {
      value.setLength(0);
      value.append(newValue);
    }

    @Override
    public int length() {
      return value.length();
    }

    @Override
    public char charAt(int index) {
      return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return value.subSequence(start, end);
    }
  }

  @Nested
  @DisplayName("compile()")
  class Compile {
    @Test
    void simplePattern() {
      Pattern p = Pattern.compile("abc");
      assertThat(p.pattern()).isEqualTo("abc");
    }

    @Test
    void withQuantifiers() {
      Pattern p = Pattern.compile("a+b*c?");
      assertThat(p.pattern()).isEqualTo("a+b*c?");
    }

    @Test
    void invalidPatternThrows() {
      assertThatThrownBy(() -> Pattern.compile("[unclosed"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void invalidPatternThrowsUnmatchedParen() {
      assertThatThrownBy(() -> Pattern.compile("(abc"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void emptyPattern() {
      Pattern p = Pattern.compile("");
      assertThat(p.pattern()).isEmpty();
      assertThat(p.matcher("").matches()).isTrue();
      assertThat(p.matcher("abc").find()).isTrue();
    }
  }

  @Nested
  @DisplayName("Unsupported features")
  class UnsupportedFeatures {
    @Test
    void lookaheadRejected() {
      assertThatThrownBy(() -> Pattern.compile("(?=a)"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void negativeLookaheadRejected() {
      assertThatThrownBy(() -> Pattern.compile("(?!a)"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void lookbehindRejected() {
      assertThatThrownBy(() -> Pattern.compile("(?<=a)"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void negativeLookbehindRejected() {
      assertThatThrownBy(() -> Pattern.compile("(?<!a)"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void atomicGroupRejected() {
      assertThatThrownBy(() -> Pattern.compile("(?>a)"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void backreferenceRejected() {
      assertThatThrownBy(() -> Pattern.compile("(a)\\1"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void namedBackreferenceRejected() {
      assertThatThrownBy(() -> Pattern.compile("(?<name>a)\\k<name>"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void namedBackreferenceWithoutGroupRejected() {
      assertThatThrownBy(() -> Pattern.compile("\\k<name>"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void possessivePlusRejected() {
      assertThatThrownBy(() -> Pattern.compile("a++"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void possessiveStarRejected() {
      assertThatThrownBy(() -> Pattern.compile("a*+"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void possessiveQuestRejected() {
      assertThatThrownBy(() -> Pattern.compile("a?+"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void possessiveBracedExactRejected() {
      assertThatThrownBy(() -> Pattern.compile("a{2}+"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void possessiveBracedMinRejected() {
      assertThatThrownBy(() -> Pattern.compile("a{2,}+"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void possessiveBracedRangeRejected() {
      assertThatThrownBy(() -> Pattern.compile("a{2,5}+"))
          .isInstanceOf(PatternSyntaxException.class);
    }
  }

  @Nested
  @DisplayName("compile(regex, flags)")
  class CompileWithFlags {
    @Test
    void caseInsensitive() {
      Pattern p = Pattern.compile("abc", Pattern.CASE_INSENSITIVE);
      assertThat(p.flags()).isEqualTo(Pattern.CASE_INSENSITIVE);
      Matcher m = p.matcher("ABC");
      assertThat(m.matches()).isTrue();
    }

    @Test
    void dotall() {
      Pattern p = Pattern.compile("a.b", Pattern.DOTALL);
      assertThat(p.flags()).isEqualTo(Pattern.DOTALL);
      Matcher m = p.matcher("a\nb");
      assertThat(m.matches()).isTrue();
    }

    @Test
    void dotallWithoutFlag() {
      Pattern p = Pattern.compile("a.b");
      Matcher m = p.matcher("a\nb");
      assertThat(m.matches()).isFalse();
    }

    @Test
    void literal() {
      Pattern p = Pattern.compile("a.b", Pattern.LITERAL);
      assertThat(p.matcher("a.b").matches()).isTrue();
      assertThat(p.matcher("axb").matches()).isFalse();
    }

    @Test
    void multipleFlags() {
      Pattern p = Pattern.compile("abc", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
      assertThat(p.flags()).isEqualTo(Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    @Test
    void canonEqAloneThrows() {
      assertThatThrownBy(
              () -> Pattern.compile("abc", java.util.regex.Pattern.CANON_EQ))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("CANON_EQ");
    }

    @Test
    void canonEqCombinedWithSupportedFlagsThrows() {
      int flags = java.util.regex.Pattern.CANON_EQ | Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
      assertThatThrownBy(() -> Pattern.compile("abc", flags))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("CANON_EQ");
    }

    @Test
    void unknownFlagBitsThrow() {
      // Bit 9 (0x200) is not assigned to any JDK flag.
      assertThatThrownBy(() -> Pattern.compile("abc", 0x200))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Unsupported");
    }

    @Test
    void zeroFlags() {
      Pattern p = Pattern.compile("abc", 0);
      assertThat(p.flags()).isZero();
      assertThat(p.matcher("abc").matches()).isTrue();
    }
  }

  @Nested
  @DisplayName("matches()")
  class StaticMatches {
    @Test
    void matchesSuccess() {
      assertThat(Pattern.matches("\\d+", "12345")).isTrue();
    }

    @Test
    void matchesFailure() {
      assertThat(Pattern.matches("\\d+", "abc")).isFalse();
    }

    @Test
    void matchesPartialReturnsFalse() {
      assertThat(Pattern.matches("\\d+", "123abc")).isFalse();
    }
  }

  @Nested
  @DisplayName("quote()")
  class Quote {
    @Test
    void quotesMetacharacters() {
      String quoted = Pattern.quote("a.b+c*");
      assertThat(quoted).isEqualTo("\\Qa.b+c*\\E");
      // The quoted pattern should match the literal string.
      assertThat(Pattern.matches(quoted, "a.b+c*")).isTrue();
    }

    @Test
    void quotesEmptyString() {
      String quoted = Pattern.quote("");
      assertThat(quoted).isEqualTo("\\Q\\E");
    }

    @Test
    void quotesStringWithBackslashE() {
      String quoted = Pattern.quote("a\\Eb");
      // Should handle the embedded \E properly.
      assertThat(Pattern.matches(quoted, "a\\Eb")).isTrue();
    }

    @Test
    @DisplayName("\\Q...\\E works inside character classes")
    void quoteInsideCharacterClass() {
      // \Q...\E inside [...] should add each char as a literal member of the class
      Pattern p = Pattern.compile("[\\Qabc\\E]+");
      Matcher m = p.matcher("xxxcbaxxx");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("cba");
    }

    @Test
    @DisplayName("\\Q...\\E with metacharacters inside character class")
    void quoteMetacharsInsideCharacterClass() {
      // Pattern.quote produces \Q...\E; inside a char class it should still work
      String p = "[" + Pattern.quote("\\") + Pattern.quote("/") + Pattern.quote("*") + "]+";
      Pattern pat = Pattern.compile(p);
      Matcher m = pat.matcher("hello/world*foo\\bar");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("/");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("*");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("\\");
    }
  }

  @Nested
  @DisplayName("split()")
  class Split {
    @Test
    void splitSimple() {
      Pattern p = Pattern.compile(",");
      String[] parts = p.split("a,b,c");
      assertThat(parts).containsExactly("a", "b", "c");
    }

    @Test
    void splitWithLimit() {
      Pattern p = Pattern.compile(",");
      String[] parts = p.split("a,b,c,d", 3);
      assertThat(parts).containsExactly("a", "b", "c,d");
    }

    @Test
    void splitTrailingEmpty() {
      Pattern p = Pattern.compile(",");
      String[] parts = p.split("a,b,,");
      // limit=0: trailing empty strings are removed.
      assertThat(parts).containsExactly("a", "b");
    }

    @Test
    void splitNegativeLimit() {
      Pattern p = Pattern.compile(",");
      String[] parts = p.split("a,b,,", -1);
      // Negative limit: trailing empty strings are retained.
      assertThat(parts).containsExactly("a", "b", "", "");
    }

    @Test
    void splitNoMatch() {
      Pattern p = Pattern.compile(",");
      String[] parts = p.split("abc");
      assertThat(parts).containsExactly("abc");
    }

    @Test
    void splitLimit1() {
      Pattern p = Pattern.compile(",");
      String[] parts = p.split("a,b,c", 1);
      assertThat(parts).containsExactly("a,b,c");
    }

    @Test
    void splitRegex() {
      Pattern p = Pattern.compile("\\s+");
      String[] parts = p.split("hello   world  foo");
      assertThat(parts).containsExactly("hello", "world", "foo");
    }

    @Test
    @DisplayName("split reads custom CharSequence content via charAt()")
    void splitCustomCharSequence() {
      Pattern p = Pattern.compile(",");
      String[] parts = p.split(new LiteralCharSequence("a,b,c"));
      assertThat(parts).containsExactly("a", "b", "c");
    }
  }

  @Nested
  @DisplayName("splitWithDelimiters()")
  class SplitWithDelimiters {
    @Test
    void splitWithDelimitersSimple() {
      Pattern p = Pattern.compile(",");
      String[] parts = p.splitWithDelimiters("a,b,c");
      assertThat(parts).containsExactly("a", ",", "b", ",", "c");
    }

    @Test
    void splitWithDelimitersRegex() {
      Pattern p = Pattern.compile(":+");
      String[] parts = p.splitWithDelimiters("boo:::and::foo");
      assertThat(parts).containsExactly("boo", ":::", "and", "::", "foo");
    }

    @Test
    void splitWithDelimitersLimit2() {
      Pattern p = Pattern.compile(":+");
      String[] parts = p.splitWithDelimiters("boo:::and::foo", 2);
      assertThat(parts).containsExactly("boo", ":::", "and::foo");
    }

    @Test
    void splitWithDelimitersLimit5() {
      Pattern p = Pattern.compile(":+");
      String[] parts = p.splitWithDelimiters("boo:::and::foo", 5);
      assertThat(parts).containsExactly("boo", ":::", "and", "::", "foo");
    }

    @Test
    void splitWithDelimitersNegativeLimit() {
      Pattern p = Pattern.compile(",");
      String[] parts = p.splitWithDelimiters("a,b,,", -1);
      assertThat(parts).containsExactly("a", ",", "b", ",", "", ",", "");
    }

    @Test
    void splitWithDelimitersTrailingEmpty() {
      Pattern p = Pattern.compile(",");
      String[] parts = p.splitWithDelimiters("a,b,,");
      // limit=0: only the trailing empty substring is removed; delimiters are kept.
      assertThat(parts).containsExactly("a", ",", "b", ",", "", ",");
    }

    @Test
    void splitWithDelimitersNoMatch() {
      Pattern p = Pattern.compile(",");
      String[] parts = p.splitWithDelimiters("abc");
      assertThat(parts).containsExactly("abc");
    }

    @Test
    void splitWithDelimitersLimit1() {
      Pattern p = Pattern.compile(",");
      String[] parts = p.splitWithDelimiters("a,b,c", 1);
      assertThat(parts).containsExactly("a,b,c");
    }

    @Test
    void splitWithDelimitersWhitespace() {
      Pattern p = Pattern.compile("\\s+");
      String[] parts = p.splitWithDelimiters("hello   world  foo");
      assertThat(parts).containsExactly("hello", "   ", "world", "  ", "foo");
    }

    @Test
    void splitWithDelimitersMatchAtStart() {
      // Positive-width match at position 0 produces a leading empty substring.
      Pattern p = Pattern.compile(",");
      String[] parts = p.splitWithDelimiters(",a,b", -1);
      assertThat(parts).containsExactly("", ",", "a", ",", "b");
    }

    @Test
    @DisplayName("splitWithDelimiters reads custom CharSequence content via charAt()")
    void splitWithDelimitersCustomCharSequence() {
      Pattern p = Pattern.compile(",");
      String[] parts = p.splitWithDelimiters(new LiteralCharSequence("a,b"));
      assertThat(parts).containsExactly("a", ",", "b");
    }
  }

  @Nested
  @DisplayName("asPredicate() / asMatchPredicate()")
  class Predicates {
    @Test
    void asPredicatePartialMatch() {
      Predicate<String> pred = Pattern.compile("\\d+").asPredicate();
      assertThat(pred.test("abc123def")).isTrue();
      assertThat(pred.test("abcdef")).isFalse();
    }

    @Test
    void asMatchPredicateFullMatch() {
      Predicate<String> pred = Pattern.compile("\\d+").asMatchPredicate();
      assertThat(pred.test("123")).isTrue();
      assertThat(pred.test("abc123")).isFalse();
    }
  }

  @Nested
  @DisplayName("toString() / pattern() / flags()")
  class Accessors {
    @Test
    void toStringReturnsPattern() {
      Pattern p = Pattern.compile("hello");
      assertThat(p.toString()).isEqualTo("hello");
    }

    @Test
    void patternReturnsSource() {
      Pattern p = Pattern.compile("hello");
      assertThat(p.pattern()).isEqualTo("hello");
    }

    @Test
    void flagsDefault() {
      Pattern p = Pattern.compile("hello");
      assertThat(p.flags()).isZero();
    }

    @Test
    void flagsPreserved() {
      int flags = Pattern.CASE_INSENSITIVE | Pattern.MULTILINE;
      Pattern p = Pattern.compile("hello", flags);
      assertThat(p.flags()).isEqualTo(flags);
    }
  }

  @Nested
  @DisplayName("Named groups")
  class NamedGroups {
    @Test
    void extractsNamedGroups() {
      Pattern p = Pattern.compile("(?P<user>\\w+)@(?P<host>\\w+)");
      assertThat(p.namedGroups()).containsEntry("user", 1);
      assertThat(p.namedGroups()).containsEntry("host", 2);
    }

    @Test
    void noNamedGroups() {
      Pattern p = Pattern.compile("(\\w+)@(\\w+)");
      assertThat(p.namedGroups()).isEmpty();
    }

    @Test
    void numGroupsCounting() {
      Pattern p = Pattern.compile("(a)(b)(c)");
      assertThat(p.numGroups()).isEqualTo(3);
    }

    @Test
    void numGroupsNoCaptures() {
      Pattern p = Pattern.compile("abc");
      assertThat(p.numGroups()).isZero();
    }
    @Test
    @DisplayName("namedGroups() returns unmodifiable map")
    void namedGroupsUnmodifiable() {
      Pattern p = Pattern.compile("(?P<user>\\w+)@(?P<host>\\w+)");
      assertThatThrownBy(() -> p.namedGroups().put("foo", 99))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Nested
  @DisplayName("End-to-end")
  class EndToEnd {
    @Test
    void emailExtraction() {
      Pattern p = Pattern.compile("(\\w+)@(\\w+\\.\\w+)");
      Matcher m = p.matcher("Contact: user@example.com for details");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("user@example.com");
      assertThat(m.group(1)).isEqualTo("user");
      assertThat(m.group(2)).isEqualTo("example.com");
    }

    @Test
    void replaceAll() {
      Pattern p = Pattern.compile("\\d+");
      Matcher m = p.matcher("a1b22c333");
      assertThat(m.replaceAll("N")).isEqualTo("aNbNcN");
    }

    @Test
    void splitAndRejoin() {
      Pattern p = Pattern.compile("-");
      String[] parts = p.split("2025-03-17");
      assertThat(parts).containsExactly("2025", "03", "17");
      assertThat(String.join("/", parts)).isEqualTo("2025/03/17");
    }

    @Test
    void multilineFindAll() {
      Pattern p = Pattern.compile("^\\w+", Pattern.MULTILINE);
      Matcher m = p.matcher("hello\nworld\nfoo");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("hello");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("world");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("foo");
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("MULTILINE ^|$ finds starts and ends for every line")
    void multilineAnchorsFindAll() {
      // Regression for issue #42: $ before a newline must not be skipped when collecting
      // successive zero-width matches.
      Matcher m = Pattern.compile("(?:^|$)", Pattern.MULTILINE).matcher("aa\na");
      List<int[]> matches = new ArrayList<>();
      while (m.find()) {
        matches.add(new int[]{m.start(), m.end()});
      }

      assertThat(matches).hasSize(4);
      assertThat(matches.get(0)).containsExactly(0, 0);
      assertThat(matches.get(1)).containsExactly(2, 2);
      assertThat(matches.get(2)).containsExactly(3, 3);
      assertThat(matches.get(3)).containsExactly(4, 4);
    }

    @Test
    @DisplayName("MULTILINE $|^ finds starts and ends for every line")
    void multilineAnchorsFindAllReversedAlternation() {
      Matcher m = Pattern.compile("(?:$|^)", Pattern.MULTILINE).matcher("aa\na");
      List<int[]> matches = new ArrayList<>();
      while (m.find()) {
        matches.add(new int[]{m.start(), m.end()});
      }

      assertThat(matches).hasSize(4);
      assertThat(matches.get(0)).containsExactly(0, 0);
      assertThat(matches.get(1)).containsExactly(2, 2);
      assertThat(matches.get(2)).containsExactly(3, 3);
      assertThat(matches.get(3)).containsExactly(4, 4);
    }

    @Test
    @DisplayName("MULTILINE ^|$ finds every line boundary in multi-line text")
    void multilineAnchorsFindMultipleLines() {
      Matcher m = Pattern.compile("(?:^|$)", Pattern.MULTILINE).matcher("a\nb\nc");
      List<int[]> matches = new ArrayList<>();
      while (m.find()) {
        matches.add(new int[]{m.start(), m.end()});
      }

      assertThat(matches).hasSize(6);
      assertThat(matches.get(0)).containsExactly(0, 0);
      assertThat(matches.get(1)).containsExactly(1, 1);
      assertThat(matches.get(2)).containsExactly(2, 2);
      assertThat(matches.get(3)).containsExactly(3, 3);
      assertThat(matches.get(4)).containsExactly(4, 4);
      assertThat(matches.get(5)).containsExactly(5, 5);
    }

    @Test
    @DisplayName("MULTILINE ^|$ first find() returns the start of input")
    void multilineAnchorsFirstFind() {
      Matcher m = Pattern.compile("(?:^|$)", Pattern.MULTILINE).matcher("aa\na");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(0);
    }

    @Test
    @DisplayName("^.*$ with MULTILINE skips the final empty line after a trailing newline")
    void multilineDotStarWithTrailingNewline() {
      String text = "\na\n";
      Pattern safePattern = Pattern.compile("^.*$", Pattern.MULTILINE);
      java.util.regex.Pattern jdkPattern =
          java.util.regex.Pattern.compile("^.*$", Pattern.MULTILINE);

      Matcher safeMatcher = safePattern.matcher(text);
      java.util.regex.Matcher jdkMatcher = jdkPattern.matcher(text);

      List<String> safeMatches = new ArrayList<>();
      while (safeMatcher.find()) {
        safeMatches.add("[" + safeMatcher.start() + "," + safeMatcher.end() + ")");
      }

      List<String> jdkMatches = new ArrayList<>();
      while (jdkMatcher.find()) {
        jdkMatches.add("[" + jdkMatcher.start() + "," + jdkMatcher.end() + ")");
      }

      assertThat(safeMatches).isEqualTo(jdkMatches).containsExactly("[0,0)", "[1,2)");
    }

    @Test
    @DisplayName("MULTILINE ^ works on long text (DFA code path)")
    void multilineBolLongText() {
      // Regression test: the DFA path didn't give \n its own equivalence class,
      // so ^ after \n was not detected on text longer than the OnePass threshold.
      StringBuilder sb = new StringBuilder();
      sb.append("header line\n");
      for (int i = 0; i < 200; i++) {
        sb.append("some padding line ").append(i).append("\n");
      }
      sb.append("import foo\n");
      sb.append("import bar\n");
      String text = sb.toString();

      Pattern p = Pattern.compile("^import", Pattern.MULTILINE);
      Matcher m = p.matcher(text);
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("import");
      int first = m.start();
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("import");
      assertThat(m.start()).isGreaterThan(first);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("$ matches before \\r\\n in MULTILINE mode")
    void multilineEolCrLf() {
      // Regression: $ in MULTILINE only matched before \n, not before \r\n.
      Pattern p = Pattern.compile("^abc$", Pattern.MULTILINE);
      assertThat(p.matcher("abc\r\ndef").find()).isTrue();
      assertThat(p.matcher("abc\ndef").find()).isTrue();
    }

    @Test
    @DisplayName("MULTILINE + CASE_INSENSITIVE with \\r\\n line endings (WebSocket header)")
    void multilineCaseInsensitiveCrLf() {
      String header = "GET / HTTP/1.1\r\nSec-WebSocket-Key: abc123\r\n\r\n";
      Pattern p = Pattern.compile("^sec-websocket-key:(.*)$",
          Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
      Matcher m = p.matcher(header);
      assertThat(m.find()).isTrue();
      // . now excludes all line terminators including \r (matching JDK behavior).
      assertThat(m.group(1).trim()).isEqualTo("abc123");
    }

    @Test
    @DisplayName(". does not match \\r (issue #54)")
    void dotDoesNotMatchCr() {
      // Regression for #54: . should not match \r, matching JDK behavior.
      Pattern p = Pattern.compile(".");
      Matcher m = p.matcher("a\rb");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("a");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("b");
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("$ matches before \\r in MULTILINE mode (issue #54)")
    void multilineEolStandaloneCr() {
      // Regression for #54: $ in MULTILINE should match before standalone \r.
      Pattern p = Pattern.compile("$", Pattern.MULTILINE);
      Matcher m = p.matcher("a\rb");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(1);
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(3);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("^ matches after \\r in MULTILINE mode (issue #54)")
    void multilineBolStandaloneCr() {
      // Regression for #54: ^ in MULTILINE should match after standalone \r.
      Pattern p = Pattern.compile("^", Pattern.MULTILINE);
      Matcher m = p.matcher("a\rb");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(2);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("UNIX_LINES restores \\n-only line terminator behavior (issue #54)")
    void unixLinesFlag() {
      // With UNIX_LINES, only \n is a line terminator — \r is treated as ordinary char.
      Pattern dotPat = Pattern.compile(".", Pattern.UNIX_LINES);
      Matcher dm = dotPat.matcher("a\rb");
      assertThat(dm.find()).isTrue();
      assertThat(dm.group()).isEqualTo("a");
      assertThat(dm.find()).isTrue();
      assertThat(dm.group()).isEqualTo("\r");
      assertThat(dm.find()).isTrue();
      assertThat(dm.group()).isEqualTo("b");

      Pattern dollarPat = Pattern.compile("$", Pattern.MULTILINE | Pattern.UNIX_LINES);
      Matcher mm = dollarPat.matcher("a\rb");
      // $ should only match at end of text, not before \r
      assertThat(mm.find()).isTrue();
      assertThat(mm.start()).isEqualTo(3);
      assertThat(mm.find()).isFalse();
    }

    @Test
    @DisplayName("MULTILINE $ in long text: DFA transition caching must defer END_LINE")
    void multilineDollarDfaCaching() {
      // Regression: the DFA caches transitions per (state, equivalence-class). END_LINE
      // depends on the character at the NEXT position, which varies for the same
      // (state, class). When a character class was consumed at a position where the
      // following char was NOT '\n', the cached transition lacked END_LINE, so a later
      // encounter at a position followed by '\n' missed the $ match.
      //
      // This only manifests when the text is long enough (>256 chars) to bypass OnePass.
      Pattern p = Pattern.compile("^password: (.*)$", Pattern.MULTILINE);

      // Short text (uses OnePass, was not affected): sanity check.
      Matcher mShort = p.matcher("line1\npassword: abc\nline3\n");
      assertThat(mShort.find()).isTrue();
      assertThat(mShort.group(1)).isEqualTo("abc");

      // Long text (>256 chars, uses DFA): this was the failing case.
      String longText = "x".repeat(240) + "\npassword: secret-uuid\nsuffix\n";
      Matcher mLong = p.matcher(longText);
      assertThat(mLong.find()).isTrue();
      assertThat(mLong.group(1)).isEqualTo("secret-uuid");

      // Simulated Spring Boot log output with password line buried in long text.
      String logOutput = "01:04:45.416 [Test worker] INFO org.boot.TomcatWebServer"
          + " -- Tomcat initialized\n"
          + "Mar 29, 2026 1:04:45 AM org.apache.coyote.AbstractProtocol init\n"
          + "INFO: Initializing ProtocolHandler [\"http-nio-auto-1\"]\n"
          + "01:04:45.998 [Test worker] WARN ... -- \n"
          + "\n"
          + "Using generated security password: 2d27188d-396d-49bb-b1da-90a58fa94f61\n"
          + "\n"
          + "This generated password is for development use only.\n"
          + "01:04:46.031 [Test worker] INFO ... AuthenticationManager configured\n"
          + "01:04:46.162 [Test worker] INFO ... -- Tomcat started on port 40033\n";
      Pattern pwPattern = Pattern.compile(
          "^Using generated security password: (.*)$", Pattern.MULTILINE);
      Matcher mLog = pwPattern.matcher(logOutput);
      assertThat(mLog.find()).isTrue();
      assertThat(mLog.group(1)).isEqualTo("2d27188d-396d-49bb-b1da-90a58fa94f61");
    }
  }

  // -----------------------------------------------------------------------
  // Tests ported from RE2/J (P1 tests)
  // -----------------------------------------------------------------------

  @Nested
  @DisplayName("Compile validation (ported from RE2/J RE2CompileTest)")
  class CompileValidation {

    /** Patterns that must compile successfully. */
    @ParameterizedTest(name = "compile(\"{0}\") succeeds")
    @ValueSource(strings = {
        "",
        ".",
        "^.$",
        "a",
        "a*",
        "a+",
        "a?",
        "a|b",
        "a*|b*",
        "(a*|b)(c*|d)",
        "[a-z]",
        "[a-abc-c\\-\\]\\[]",
        "[a-z]+",
        "[abc]",
        "[^1234]",
        "[^\n]",
        "..|.#|..",
        "\\!\\\\",
        "abc]",
        "a??"
    })
    void validPatternsCompile(String pattern) {
      Pattern.compile(pattern); // should not throw
    }

    /** Patterns that must fail to compile. */
    @ParameterizedTest(name = "compile(\"{0}\") throws")
    @ValueSource(strings = {
        "*",
        "+",
        "?",
        "(abc",
        "x[a-z",
        "[z-a]",
        "abc\\"
    })
    void invalidPatternsThrow(String pattern) {
      assertThatThrownBy(() -> Pattern.compile(pattern))
          .isInstanceOf(PatternSyntaxException.class);
    }
  }

  @Nested
  @DisplayName("groupCount (ported from RE2/J RE2TestNumSubexps)")
  class GroupCount {

    @ParameterizedTest(name = "compile(\"{0}\").numGroups() == {1}")
    @CsvSource({
        "'',         0",
        "'.*',        0",
        "'abba',      0",
        "'ab(b)a',    1",
        "'ab(.*)a',   1",
        "'(.*)ab(.*)a',  2",
        "'(.*)(ab)(.*)a', 3",
        "'(.*)((a)b)(.*)a', 4",
        "'(.*)(\\(ab)(.*)a', 3",
        "'(.*)(\\(a\\)b)(.*)a', 3",
    })
    void numGroups(String pattern, int expected) {
      assertThat(Pattern.compile(pattern).numGroups()).isEqualTo(expected);
    }
  }

  @Nested
  @DisplayName("quote() round-trip (ported from RE2/J RE2QuoteMetaTest)")
  class QuoteRoundTrip {

    static Stream<Arguments> quoteMetaCases() {
      return Stream.of(
          Arguments.of("foo", "abcfoodef", "abcxyzdef"),
          Arguments.of("foo.$", "abcfoo.$def", "abcxyzdef"),
          Arguments.of(
              "!@#$%^&*()_+-=[{]}\\|,<.>/?~",
              "abc!@#$%^&*()_+-=[{]}\\|,<.>/?~def",
              "abcxyzdef")
      );
    }

    @ParameterizedTest(name = "quote(\"{0}\") replaceAll round-trip")
    @MethodSource("quoteMetaCases")
    void quoteAndReplace(String metachar, String source, String expected) {
      String quoted = Pattern.quote(metachar);
      Pattern p = Pattern.compile(quoted);
      String replaced = p.matcher(source).replaceAll("xyz");
      assertThat(replaced).isEqualTo(expected);
    }
  }

  @Nested
  @DisplayName("splitAsStream()")
  class SplitAsStreamTests {

    @Test
    @DisplayName("splitAsStream splits input around matches")
    void splitAsStreamBasic() {
      Pattern p = Pattern.compile(",");
      java.util.List<String> parts =
          p.splitAsStream("a,b,c").collect(java.util.stream.Collectors.toList());
      assertThat(parts).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("splitAsStream with no match returns entire input")
    void splitAsStreamNoMatch() {
      Pattern p = Pattern.compile(",");
      java.util.List<String> parts =
          p.splitAsStream("abc").collect(java.util.stream.Collectors.toList());
      assertThat(parts).containsExactly("abc");
    }

    @Test
    @DisplayName("splitAsStream with regex pattern")
    void splitAsStreamRegex() {
      Pattern p = Pattern.compile("\\s+");
      java.util.List<String> parts =
          p.splitAsStream("hello  world\tfoo").collect(java.util.stream.Collectors.toList());
      assertThat(parts).containsExactly("hello", "world", "foo");
    }

    @Test
    @DisplayName("splitAsStream count() works without collecting")
    void splitAsStreamCount() {
      Pattern p = Pattern.compile(",");
      long count = p.splitAsStream("a,b,c,d").count();
      assertThat(count).isEqualTo(4);
    }

    @Test
    @DisplayName("splitAsStream discards trailing empty strings")
    void splitAsStreamTrailingEmpty() {
      Pattern p = Pattern.compile(",");
      java.util.List<String> parts =
          p.splitAsStream("a,b,").collect(java.util.stream.Collectors.toList());
      assertThat(parts).containsExactly("a", "b");
    }

    @Test
    @DisplayName("splitAsStream reads custom CharSequence content via charAt()")
    void splitAsStreamCustomCharSequence() {
      Pattern p = Pattern.compile(",");
      java.util.List<String> parts =
          p.splitAsStream(new LiteralCharSequence("a,b,c"))
              .collect(java.util.stream.Collectors.toList());
      assertThat(parts).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("splitAsStream reads input lazily when stream is consumed")
    void splitAsStreamReadsInputLazily() {
      Pattern p = Pattern.compile(",");
      MutableCharSequence input = new MutableCharSequence("a,b");
      Stream<String> stream = p.splitAsStream(input);

      input.set("x,y,z");

      assertThat(stream.collect(java.util.stream.Collectors.toList()))
          .containsExactly("x", "y", "z");
    }
  }

  @Nested
  @DisplayName("nested character classes")
  class NestedCharClassTests {

    @Test
    @DisplayName("[[A-F]] matches same as [A-F] (Java-style union)")
    void nestedCharClassUnion() {
      Pattern p = Pattern.compile("[[A-F]]");
      assertThat(p.matcher("A").find()).isTrue();
      assertThat(p.matcher("C").find()).isTrue();
      assertThat(p.matcher("G").find()).isFalse();
    }

    @Test
    @DisplayName("[[A-Fa-f0-9]]{32,} matches hex strings (Docker digest pattern)")
    void nestedCharClassHexDigest() {
      Pattern p = Pattern.compile("[[A-Fa-f0-9]]{32,}");
      Matcher m =
          p.matcher("6e9f67fa63b0323e9a1e587fd71c561ba48a034504fb804fd26fd8800039835d");
      assertThat(m.find()).isTrue();
      assertThat(m.group())
          .isEqualTo("6e9f67fa63b0323e9a1e587fd71c561ba48a034504fb804fd26fd8800039835d");
    }

    @Test
    @DisplayName("[a-z[0-9]] matches both ranges")
    void nestedCharClassMixedRanges() {
      Pattern p = Pattern.compile("[a-z[0-9]]");
      assertThat(p.matcher("m").find()).isTrue();
      assertThat(p.matcher("5").find()).isTrue();
      assertThat(p.matcher("A").find()).isFalse();
    }

    @Test
    @DisplayName("deeply nested [[[[a-z]]]] works")
    void deeplyNestedCharClass() {
      Pattern p = Pattern.compile("[[[[a-z]]]]");
      assertThat(p.matcher("m").find()).isTrue();
      assertThat(p.matcher("A").find()).isFalse();
    }

    @Test
    @DisplayName("[^[A-F]] negates the union")
    void negatedNestedCharClass() {
      Pattern p = Pattern.compile("[^[A-F]]");
      assertThat(p.matcher("A").find()).isFalse();
      assertThat(p.matcher("G").find()).isTrue();
    }
  }
}
