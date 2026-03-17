// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Predicate;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
  }
}
