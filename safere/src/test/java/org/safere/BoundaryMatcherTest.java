// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for boundary matcher constructs: {@code \b}, {@code \B}, {@code \A}, {@code \z},
 * {@code \Z}, {@code \G}, and {@code \b{g}}.
 *
 * <p>Covers issue #112 (boundary matchers section).
 */
class BoundaryMatcherTest {

  // ---------------------------------------------------------------------------
  // \Z — end of input before final terminator
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("\\Z (end before final terminator)")
  class BackslashZ {

    @Test
    @DisplayName("\\Z matches at end of string")
    void matchesAtEnd() {
      Pattern p = Pattern.compile("abc\\Z");
      Matcher m = p.matcher("abc");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("abc");
    }

    @Test
    @DisplayName("\\Z matches before trailing \\n")
    void matchesBeforeTrailingNewline() {
      Pattern p = Pattern.compile("abc\\Z");
      Matcher m = p.matcher("abc\n");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("abc");
    }

    @Test
    @DisplayName("\\Z does not match before two trailing newlines")
    void noMatchBeforeTwoTrailingNewlines() {
      Pattern p = Pattern.compile("abc\\Z");
      Matcher m = p.matcher("abc\n\n");
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("\\Z does not match in the middle of input")
    void noMatchInMiddle() {
      Pattern p = Pattern.compile("abc\\Z");
      Matcher m = p.matcher("abcdef");
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("\\Z alone matches at end of string")
    void aloneMatchesAtEnd() {
      Pattern p = Pattern.compile("\\Z");
      Matcher m = p.matcher("hello");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(5);
    }

    @Test
    @DisplayName("\\Z alone matches before trailing \\n")
    void aloneMatchesBeforeTrailingNewline() {
      Pattern p = Pattern.compile("\\Z");
      Matcher m = p.matcher("hello\n");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(5);
    }

    @Test
    @DisplayName("\\Z with MULTILINE still matches only at end, not at line boundaries")
    void multilineStillOnlyMatchesAtEnd() {
      Pattern p = Pattern.compile("\\w+\\Z", Pattern.MULTILINE);
      Matcher m = p.matcher("abc\ndef\nghi\n");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("ghi");
      // Should not find another match at abc or def
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("\\Z behaves like $ in non-MULTILINE mode")
    void sameAsDollarNonMultiline() {
      String[] inputs = {"foo", "foo\n", "foo\n\n", "foobar"};
      for (String input : inputs) {
        boolean zResult = Pattern.compile("foo\\Z").matcher(input).find();
        boolean dollarResult = Pattern.compile("foo$").matcher(input).find();
        assertThat(zResult)
            .as("\\Z vs $ on input '%s'", input.replace("\n", "\\n"))
            .isEqualTo(dollarResult);
      }
    }

    @Test
    @DisplayName("\\Z differs from \\z on trailing newline")
    void differsFromLowercaseZ() {
      Pattern pZ = Pattern.compile("abc\\Z");
      Pattern pz = Pattern.compile("abc\\z");

      // Both match at absolute end
      assertThat(pZ.matcher("abc").find()).isTrue();
      assertThat(pz.matcher("abc").find()).isTrue();

      // Only \Z matches before trailing \n
      assertThat(pZ.matcher("abc\n").find()).isTrue();
      assertThat(pz.matcher("abc\n").find()).isFalse();
    }

    @Test
    @DisplayName("\\Z sets requireEnd to true")
    void setsRequireEnd() {
      Pattern p = Pattern.compile("abc\\Z");
      Matcher m = p.matcher("abc");
      assertThat(m.find()).isTrue();
      assertThat(m.requireEnd()).isTrue();
    }

    @Test
    @DisplayName("matches() with \\Z — matches bare string")
    void matchesBareString() {
      assertThat(Pattern.compile("abc\\Z").matcher("abc").matches()).isTrue();
    }

    @Test
    @DisplayName("matches() with \\Z — does not match string with trailing newline")
    void matchesTrailingNewline() {
      // matches() requires the entire string to be consumed; \Z is zero-width
      // so "abc\n" has an unconsumed \n after the \Z assertion.
      assertThat(Pattern.compile("abc\\Z").matcher("abc\n").matches()).isFalse();
    }

    @Test
    @DisplayName("\\Z with captures")
    void withCaptures() {
      Pattern p = Pattern.compile("(\\w+)\\Z");
      Matcher m = p.matcher("hello\n");
      assertThat(m.find()).isTrue();
      assertThat(m.group(1)).isEqualTo("hello");
    }

    @Test
    @DisplayName("\\Z in alternation")
    void inAlternation() {
      Pattern p = Pattern.compile("end\\Z|end\\z");
      Matcher m = p.matcher("end\n");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("end");
    }
  }

  // ---------------------------------------------------------------------------
  // \G — end of previous match (not supported)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("\\G (end of previous match)")
  class BackslashG {

    @Test
    @DisplayName("\\G is rejected with a descriptive error")
    void rejected() {
      assertThatThrownBy(() -> Pattern.compile("\\G\\w+"))
          .isInstanceOf(PatternSyntaxException.class)
          .hasMessageContaining("\\G");
    }

    @Test
    @DisplayName("\\G alone is rejected")
    void rejectedAlone() {
      assertThatThrownBy(() -> Pattern.compile("\\G"))
          .isInstanceOf(PatternSyntaxException.class)
          .hasMessageContaining("\\G");
    }

    @Test
    @DisplayName("\\G in character class is rejected (not valid in JDK either)")
    void inCharClassIsRejected() {
      // Inside [...], \G is not a valid escape in JDK or SafeRE.
      assertThatThrownBy(() -> Pattern.compile("[\\GA]"))
          .isInstanceOf(PatternSyntaxException.class);
    }
  }

  // ---------------------------------------------------------------------------
  // \b{g} — grapheme cluster boundary (accepted for JDK compatibility)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("\\b{g} (grapheme cluster boundary)")
  class GraphemeClusterBoundary {

    @Test
    @DisplayName("\\b{g} compiles without error")
    void compiles() {
      assertThatNoException().isThrownBy(() -> Pattern.compile("\\b{g}"));
    }

    @Test
    @DisplayName("\\b{g} in a larger pattern compiles without error")
    void compilesInLargerPattern() {
      assertThatNoException().isThrownBy(() -> Pattern.compile("foo\\b{g}bar"));
    }

    @Test
    @DisplayName("\\b without {g} still works as word boundary")
    void plainWordBoundaryStillWorks() {
      Pattern p = Pattern.compile("\\bword\\b");
      assertThat(p.matcher("a word here").find()).isTrue();
      assertThat(p.matcher("awordhere").find()).isFalse();
    }

    @Test
    @DisplayName("\\B without {g} still works as non-word boundary")
    void plainNonWordBoundaryStillWorks() {
      Pattern p = Pattern.compile("\\Bor\\B");
      assertThat(p.matcher("word").find()).isTrue();
      assertThat(p.matcher("or").find()).isFalse();
    }

    @Test
    @DisplayName("\\b followed by literal brace is not rejected")
    void otherBracedContentNotRejected() {
      // \b{x} should be parsed as \b followed by literal {x}
      // (since {x} is not a valid repetition, it becomes literal)
      Pattern p = Pattern.compile("\\b\\{x}");
      assertThat(p.matcher("a{x}b").find()).isTrue();
    }
  }

  // ---------------------------------------------------------------------------
  // Existing boundary matchers — additional coverage
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("existing boundary matchers")
  class ExistingBoundaryMatchers {

    @Test
    @DisplayName("\\A matches only at start of text")
    void beginText() {
      Pattern p = Pattern.compile("\\Aabc");
      assertThat(p.matcher("abc").find()).isTrue();
      assertThat(p.matcher("xabc").find()).isFalse();
    }

    @Test
    @DisplayName("\\A with MULTILINE does not match at line start")
    void beginTextMultiline() {
      Pattern p = Pattern.compile("\\Aabc", Pattern.MULTILINE);
      assertThat(p.matcher("abc").find()).isTrue();
      assertThat(p.matcher("xyz\nabc").find()).isFalse();
    }

    @Test
    @DisplayName("\\z matches only at absolute end of text")
    void endText() {
      Pattern p = Pattern.compile("abc\\z");
      assertThat(p.matcher("abc").find()).isTrue();
      assertThat(p.matcher("abc\n").find()).isFalse();
      assertThat(p.matcher("abcx").find()).isFalse();
    }

    @Test
    @DisplayName("\\b matches at word/non-word transitions")
    void wordBoundary() {
      Pattern p = Pattern.compile("\\bcat\\b");
      assertThat(p.matcher("the cat sat").find()).isTrue();
      assertThat(p.matcher("concatenate").find()).isFalse();
    }

    @Test
    @DisplayName("\\B matches inside a word")
    void nonWordBoundary() {
      Pattern p = Pattern.compile("\\Bcat\\B");
      assertThat(p.matcher("concatenate").find()).isTrue();
      assertThat(p.matcher("the cat sat").find()).isFalse();
    }

    @Test
    @DisplayName("\\b at start of string")
    void wordBoundaryAtStart() {
      Pattern p = Pattern.compile("\\bword");
      assertThat(p.matcher("word").find()).isTrue();
      assertThat(p.matcher(" word").find()).isTrue();
    }

    @Test
    @DisplayName("\\b at end of string")
    void wordBoundaryAtEnd() {
      Pattern p = Pattern.compile("word\\b");
      assertThat(p.matcher("word").find()).isTrue();
      assertThat(p.matcher("word ").find()).isTrue();
    }
  }
}
