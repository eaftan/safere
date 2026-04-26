// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Pattern#COMMENTS} flag and {@code (?x)} inline verbose mode.
 *
 * <p>Verifies that SafeRE matches JDK {@link java.util.regex.Pattern#COMMENTS} behavior: unescaped
 * whitespace is ignored, {@code #} starts a comment to end of line, and these rules apply both
 * outside and inside character classes.
 */
class CommentsTest {

  // ---------------------------------------------------------------------------
  // Pattern.COMMENTS compile flag
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Pattern.COMMENTS compile flag")
  class CompileFlag {

    @Test
    @DisplayName("whitespace in pattern is ignored")
    void whitespaceIgnored() {
      Pattern p = Pattern.compile("a b c", Pattern.COMMENTS);
      assertThat(p.matcher("abc").matches()).isTrue();
      assertThat(p.matcher("a b c").matches()).isFalse();
    }

    @Test
    @DisplayName("tabs and newlines are ignored")
    void tabsAndNewlinesIgnored() {
      Pattern p = Pattern.compile("a\tb\nc", Pattern.COMMENTS);
      assertThat(p.matcher("abc").matches()).isTrue();
    }

    @Test
    @DisplayName("# starts a comment to end of line")
    void hashComment() {
      Pattern p = Pattern.compile("a # match 'a'\nb", Pattern.COMMENTS);
      assertThat(p.matcher("ab").matches()).isTrue();
      assertThat(p.matcher("a # match 'a'\nb").matches()).isFalse();
    }

    @Test
    @DisplayName("# comment at end of pattern (no trailing newline)")
    void hashCommentAtEnd() {
      Pattern p = Pattern.compile("ab # trailing comment", Pattern.COMMENTS);
      assertThat(p.matcher("ab").matches()).isTrue();
    }

    @Test
    @DisplayName("escaped space is literal")
    void escapedSpaceLiteral() {
      Pattern p = Pattern.compile("a\\ b", Pattern.COMMENTS);
      assertThat(p.matcher("a b").matches()).isTrue();
      assertThat(p.matcher("ab").matches()).isFalse();
    }

    @Test
    @DisplayName("escaped # is literal")
    void escapedHashLiteral() {
      Pattern p = Pattern.compile("a\\#b", Pattern.COMMENTS);
      assertThat(p.matcher("a#b").matches()).isTrue();
    }

    @Test
    @DisplayName("multiline verbose pattern")
    void multilineVerbosePattern() {
      Pattern p =
          Pattern.compile(
              """
              ^           # start of string
              (\\d{3})    # area code
              -           # separator
              (\\d{4})    # number
              $           # end of string
              """,
              Pattern.COMMENTS);
      Matcher m = p.matcher("555-1234");
      assertThat(m.matches()).isTrue();
      assertThat(m.group(1)).isEqualTo("555");
      assertThat(m.group(2)).isEqualTo("1234");
    }

    @Test
    @DisplayName("verbose block-comment replacement preserves captured text")
    void verboseBlockCommentReplacementPreservesCapturedText() {
      // Regression for issue #105.
      var p =
          Pattern.compile(
              """
                        /[*]
                        [\\s*]*
                        \\bMOE:insert\\b

                        (.*?)

                        [*]/
                        """,
              Pattern.DOTALL | Pattern.COMMENTS);

      String content =
          """
      @Deprecated
        /* MOE:insert public */ void trimToSize() {
      """;
      content = p.matcher(content).replaceAll("$1");
      assertThat(content)
          .isEqualTo("@Deprecated\n   public  void trimToSize() {\n");
    }
  }

  // ---------------------------------------------------------------------------
  // Character classes under COMMENTS
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Character classes under COMMENTS")
  class CharClasses {

    @Test
    @DisplayName("whitespace inside character class is ignored")
    void whitespaceInCharClassIgnored() {
      Pattern p = Pattern.compile("[a b]", Pattern.COMMENTS);
      assertThat(p.matcher("a").matches()).isTrue();
      assertThat(p.matcher("b").matches()).isTrue();
      assertThat(p.matcher(" ").matches()).isFalse();
    }

    @Test
    @DisplayName("escaped space in character class is literal")
    void escapedSpaceInCharClass() {
      Pattern p = Pattern.compile("[\\ ]", Pattern.COMMENTS);
      assertThat(p.matcher(" ").matches()).isTrue();
      assertThat(p.matcher("a").matches()).isFalse();
    }

    @Test
    @DisplayName("range in character class with whitespace")
    void rangeWithWhitespace() {
      Pattern p = Pattern.compile("[ a - z ]", Pattern.COMMENTS);
      assertThat(p.matcher("m").matches()).isTrue();
      assertThat(p.matcher("A").matches()).isFalse();
    }

    @Test
    @DisplayName("negated character class with whitespace")
    void negatedCharClassWithWhitespace() {
      Pattern p = Pattern.compile("[^ a - z ]", Pattern.COMMENTS);
      assertThat(p.matcher("A").matches()).isTrue();
      assertThat(p.matcher("m").matches()).isFalse();
    }
  }

  // ---------------------------------------------------------------------------
  // Inline (?x) flag
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Inline (?x) flag")
  class InlineFlag {

    @Test
    @DisplayName("(?x) enables comments mode")
    void inlineXEnablesComments() {
      Pattern p = Pattern.compile("(?x) a b c");
      assertThat(p.matcher("abc").matches()).isTrue();
    }

    @Test
    @DisplayName("(?-x) disables comments mode")
    void inlineMinusXDisablesComments() {
      Pattern p = Pattern.compile("(?x)a b(?-x) c");
      assertThat(p.matcher("ab c").matches()).isTrue();
      assertThat(p.matcher("abc").matches()).isFalse();
    }

    @Test
    @DisplayName("(?x:...) scoped comments mode")
    void scopedInlineX() {
      Pattern p = Pattern.compile("(?x: a b) c");
      assertThat(p.matcher("ab c").matches()).isTrue();
      assertThat(p.matcher("abc").matches()).isFalse();
    }

    @Test
    @DisplayName("(?x) with # comment")
    void inlineXWithComment() {
      Pattern p = Pattern.compile("(?x)a # match a\nb # match b");
      assertThat(p.matcher("ab").matches()).isTrue();
    }
  }

  // ---------------------------------------------------------------------------
  // COMMENTS combined with other flags
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("COMMENTS combined with other flags")
  class CombinedFlags {

    @Test
    @DisplayName("COMMENTS | DOTALL")
    void commentsAndDotall() {
      Pattern p = Pattern.compile("a . b", Pattern.COMMENTS | Pattern.DOTALL);
      assertThat(p.matcher("a\nb").matches()).isTrue();
    }

    @Test
    @DisplayName("COMMENTS | CASE_INSENSITIVE")
    void commentsAndCaseInsensitive() {
      Pattern p = Pattern.compile("a b c", Pattern.COMMENTS | Pattern.CASE_INSENSITIVE);
      assertThat(p.matcher("ABC").matches()).isTrue();
    }

    @Test
    @DisplayName("COMMENTS | MULTILINE")
    void commentsAndMultiline() {
      Pattern p = Pattern.compile("^ a $", Pattern.COMMENTS | Pattern.MULTILINE);
      assertThat(p.matcher("a").find()).isTrue();
      assertThat(p.matcher("x\na\ny").find()).isTrue();
    }
  }

  // ---------------------------------------------------------------------------
  // Edge cases
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Edge cases")
  class EdgeCases {

    @Test
    @DisplayName("empty pattern with COMMENTS")
    void emptyPattern() {
      Pattern p = Pattern.compile("", Pattern.COMMENTS);
      assertThat(p.matcher("").matches()).isTrue();
    }

    @Test
    @DisplayName("pattern of only whitespace with COMMENTS")
    void onlyWhitespace() {
      Pattern p = Pattern.compile("   \t\n   ", Pattern.COMMENTS);
      assertThat(p.matcher("").matches()).isTrue();
    }

    @Test
    @DisplayName("pattern of only comment with COMMENTS")
    void onlyComment() {
      Pattern p = Pattern.compile("# just a comment", Pattern.COMMENTS);
      assertThat(p.matcher("").matches()).isTrue();
    }

    @Test
    @DisplayName("\\Q...\\E quoting preserves whitespace under COMMENTS")
    void quotingPreservesWhitespace() {
      Pattern p = Pattern.compile("\\Q a b \\E", Pattern.COMMENTS);
      assertThat(p.matcher(" a b ").matches()).isTrue();
      assertThat(p.matcher("ab").matches()).isFalse();
    }

    @Test
    @DisplayName("quantifiers with whitespace in COMMENTS mode")
    void quantifiersWithWhitespace() {
      Pattern p = Pattern.compile("a +", Pattern.COMMENTS);
      assertThat(p.matcher("aaa").matches()).isTrue();
    }

    @Test
    @DisplayName("alternation with whitespace in COMMENTS mode")
    void alternationWithWhitespace() {
      Pattern p = Pattern.compile("a | b", Pattern.COMMENTS);
      assertThat(p.matcher("a").matches()).isTrue();
      assertThat(p.matcher("b").matches()).isTrue();
    }

    @Test
    @DisplayName("group with whitespace in COMMENTS mode")
    void groupWithWhitespace() {
      Pattern p = Pattern.compile("( a ) ( b )", Pattern.COMMENTS);
      Matcher m = p.matcher("ab");
      assertThat(m.matches()).isTrue();
      assertThat(m.group(1)).isEqualTo("a");
      assertThat(m.group(2)).isEqualTo("b");
    }

    @Test
    @DisplayName("COMMENTS has no effect with LITERAL flag")
    void commentsWithLiteral() {
      Pattern p = Pattern.compile("a b", Pattern.COMMENTS | Pattern.LITERAL);
      // LITERAL takes precedence — the pattern is the literal string "a b"
      assertThat(p.matcher("a b").matches()).isTrue();
      assertThat(p.matcher("ab").matches()).isFalse();
    }

    @Test
    @DisplayName("find mode with COMMENTS")
    void findModeWithComments() {
      Pattern p = Pattern.compile("\\d + \\. \\d +", Pattern.COMMENTS);
      Matcher m = p.matcher("price is 3.14 dollars");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("3.14");
    }

    @Test
    @DisplayName("replaceAll with COMMENTS")
    void replaceAllWithComments() {
      Pattern p = Pattern.compile("\\s +", Pattern.COMMENTS);
      String result = p.matcher("hello   world").replaceAll(" ");
      assertThat(result).isEqualTo("hello world");
    }
  }
}
