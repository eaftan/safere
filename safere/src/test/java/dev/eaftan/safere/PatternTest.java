// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void unsupportedFlagThrows() {
      // CANON_EQ = 128
      assertThatThrownBy(() -> Pattern.compile("abc", 128))
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
  }
}
