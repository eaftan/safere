// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link PatternSet}. */
class PatternSetTest {

  // ---------------------------------------------------------------------------
  // Builder
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Builder")
  class BuilderTests {
    @Test
    void addReturnsSequentialIndices() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      assertThat(b.add("foo")).isEqualTo(0);
      assertThat(b.add("bar")).isEqualTo(1);
      assertThat(b.add("baz")).isEqualTo(2);
    }

    @Test
    void addRejectsInvalidPattern() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      assertThatThrownBy(() -> b.add("[invalid"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void addAfterCompileThrows() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      b.compile();
      assertThatThrownBy(() -> b.add("bar"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Cannot add");
    }

    @Test
    void compileWithNoPatternsThrows() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      assertThatThrownBy(b::compile)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("No patterns");
    }

    @Test
    void doubleCompileThrows() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      b.compile();
      assertThatThrownBy(b::compile)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("already been called");
    }
  }

  // ---------------------------------------------------------------------------
  // Unanchored matching
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Unanchored")
  class UnanchoredTests {
    @Test
    void singlePatternMatches() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      PatternSet set = b.compile();

      assertThat(set.match("foo")).containsExactly(0);
      assertThat(set.match("hello foo world")).containsExactly(0);
      assertThat(set.match("bar")).isEmpty();
    }

    @Test
    void multiplePatterns() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      b.add("bar");
      b.add("baz");
      PatternSet set = b.compile();

      assertThat(set.match("foo")).containsExactly(0);
      assertThat(set.match("bar")).containsExactly(1);
      assertThat(set.match("baz")).containsExactly(2);
      assertThat(set.match("nothing")).isEmpty();
    }

    @Test
    void multipleMatchesInSameText() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      b.add("bar");
      b.add("baz");
      PatternSet set = b.compile();

      assertThat(set.match("foobar")).containsExactly(0, 1);
      assertThat(set.match("foobarbaz")).containsExactly(0, 1, 2);
    }

    @Test
    void overlappingPatterns() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("abc");
      b.add("bcd");
      PatternSet set = b.compile();

      assertThat(set.match("abcd")).containsExactly(0, 1);
    }

    @Test
    void patternWithQuantifiers() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("[0-9]+");
      b.add("[a-z]+");
      PatternSet set = b.compile();

      assertThat(set.match("abc123")).containsExactly(0, 1);
      assertThat(set.match("!!!")).isEmpty();
    }

    @Test
    void matchesConvenience() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      PatternSet set = b.compile();

      assertThat(set.matches("foo bar")).isTrue();
      assertThat(set.matches("nothing")).isFalse();
    }
  }

  // ---------------------------------------------------------------------------
  // Anchor start
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Anchor start")
  class AnchorStartTests {
    @Test
    void matchesAtStart() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_START);
      b.add("foo");
      b.add("bar");
      PatternSet set = b.compile();

      assertThat(set.match("foobar")).containsExactly(0);
      assertThat(set.match("barfoo")).containsExactly(1);
      assertThat(set.match("xfoo")).isEmpty();
    }

    @Test
    void prefixMatch() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_START);
      b.add("[0-9]+");
      b.add("[a-z]+");
      PatternSet set = b.compile();

      assertThat(set.match("123abc")).containsExactly(0);
      assertThat(set.match("abc123")).containsExactly(1);
      assertThat(set.match("!!!")).isEmpty();
    }
  }

  // ---------------------------------------------------------------------------
  // Anchor both
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Anchor both")
  class AnchorBothTests {
    @Test
    void fullMatchOnly() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH);
      b.add("foo");
      b.add("bar");
      PatternSet set = b.compile();

      assertThat(set.match("foo")).containsExactly(0);
      assertThat(set.match("bar")).containsExactly(1);
      assertThat(set.match("foobar")).isEmpty();
      assertThat(set.match("fo")).isEmpty();
    }

    @Test
    void multipleFullMatches() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH);
      b.add("abc");
      b.add("[a-z]+");
      b.add("...");
      PatternSet set = b.compile();

      assertThat(set.match("abc")).containsExactly(0, 1, 2);
      assertThat(set.match("xyz")).containsExactly(1, 2);
      assertThat(set.match("abcd")).containsExactly(1);
    }
  }

  // ---------------------------------------------------------------------------
  // Metadata
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Metadata")
  class MetadataTests {
    @Test
    void sizeReturnsCount() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("a");
      b.add("b");
      b.add("c");
      PatternSet set = b.compile();

      assertThat(set.size()).isEqualTo(3);
    }

    @Test
    void patternReturnsOriginal() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("hello");
      b.add("[0-9]+");
      PatternSet set = b.compile();

      assertThat(set.pattern(0)).isEqualTo("hello");
      assertThat(set.pattern(1)).isEqualTo("[0-9]+");
    }
  }

  // ---------------------------------------------------------------------------
  // Edge cases
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Edge cases")
  class EdgeCases {
    @Test
    void emptyPattern() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("");
      PatternSet set = b.compile();

      assertThat(set.match("anything")).containsExactly(0);
      assertThat(set.match("")).containsExactly(0);
    }

    @Test
    void emptyText() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      b.add("");
      PatternSet set = b.compile();

      assertThat(set.match("")).containsExactly(1);
    }

    @Test
    void duplicatePatterns() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      b.add("foo");
      PatternSet set = b.compile();

      List<Integer> result = set.match("foo");
      assertThat(result).containsExactly(0, 1);
    }

    @Test
    void unicodePatterns() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("caf\u00e9");
      b.add("\\p{Greek}+");
      PatternSet set = b.compile();

      assertThat(set.match("caf\u00e9")).containsExactly(0);
      assertThat(set.match("\u03b1\u03b2\u03b3")).containsExactly(1);
    }

    @Test
    void manyPatterns() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH);
      for (int i = 0; i < 100; i++) {
        b.add("pattern" + i);
      }
      PatternSet set = b.compile();

      assertThat(set.match("pattern42")).containsExactly(42);
      assertThat(set.match("pattern99")).containsExactly(99);
      assertThat(set.match("pattern100")).isEmpty();
    }

    @Test
    void singlePattern() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH);
      b.add("hello");
      PatternSet set = b.compile();

      assertThat(set.match("hello")).containsExactly(0);
      assertThat(set.match("world")).isEmpty();
    }
  }

  // ---------------------------------------------------------------------------
  // Builder edge cases
  // ---------------------------------------------------------------------------

  @Test
  void compileEmptyBuilderThrows() {
    PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
    assertThatThrownBy(b::compile).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void compileAlreadyCompiledThrows() {
    PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
    b.add("abc");
    b.compile();
    assertThatThrownBy(b::compile).isInstanceOf(IllegalStateException.class);
  }

  // ---------------------------------------------------------------------------
  // Anchor-mode coverage (non-fallback paths)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Anchor mode coverage")
  class AnchorModeCoverage {

    @Test
    void unanchoredMultipleOverlapping() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("[a-z]+");
      b.add("abc");
      b.add("xyz");
      PatternSet set = b.compile();

      assertThat(set.match("abc")).containsExactly(0, 1);
      assertThat(set.match("xyz")).containsExactly(0, 2);
      assertThat(set.match("abcxyz")).containsExactly(0, 1, 2);
      assertThat(set.match("123")).isEmpty();
    }

    @Test
    void anchorStartPartialMatch() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_START);
      b.add("foo");
      b.add("bar");
      PatternSet set = b.compile();

      assertThat(set.match("foobar")).containsExactly(0);
      assertThat(set.match("barfoo")).containsExactly(1);
      assertThat(set.match("xfoo")).isEmpty();
    }

    @Test
    void anchorBothNoPartial() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH);
      b.add("foo");
      b.add("foobar");
      PatternSet set = b.compile();

      assertThat(set.match("foo")).containsExactly(0);
      assertThat(set.match("foobar")).containsExactly(1);
      assertThat(set.match("foob")).isEmpty();
    }

    @Test
    void matchesConvenience() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("hello");
      PatternSet set = b.compile();

      assertThat(set.matches("say hello")).isTrue();
      assertThat(set.matches("goodbye")).isFalse();
    }

    @Test
    void sizeAndPatternAccessors() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("alpha");
      b.add("beta");
      PatternSet set = b.compile();

      assertThat(set.size()).isEqualTo(2);
      assertThat(set.pattern(0)).isEqualTo("alpha");
      assertThat(set.pattern(1)).isEqualTo("beta");
    }

    @Test
    void addAfterCompileThrows() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("x");
      b.compile();
      assertThatThrownBy(() -> b.add("y")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidPatternThrows() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      assertThatThrownBy(() -> b.add("[invalid"))
          .isInstanceOf(PatternSyntaxException.class);
    }
  }

  // ---------------------------------------------------------------------------
  // DFA fallback (matchFallback)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("DFA fallback")
  class FallbackTests {

    @Test
    void fallbackUnanchored() {
      // Use maxDfaStates=1 to force immediate DFA bailout → NFA fallback
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      b.add("bar");
      b.add("[0-9]+");
      PatternSet set = b.compile(1);
      List<Integer> matches = set.match("foobar123");
      assertThat(matches).containsExactly(0, 1, 2);
    }

    @Test
    void fallbackAnchorStart() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_START);
      b.add("foo");
      b.add("bar");
      PatternSet set = b.compile(1);
      List<Integer> matches = set.match("foobar");
      assertThat(matches).containsExactly(0);
    }

    @Test
    void fallbackAnchorBoth() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH);
      b.add("hello");
      b.add("world");
      PatternSet set = b.compile(1);
      assertThat(set.match("hello")).containsExactly(0);
      assertThat(set.match("world")).containsExactly(1);
      assertThat(set.match("other")).isEmpty();
    }

    @Test
    void fallbackNoMatch() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("xyz");
      PatternSet set = b.compile(1);
      assertThat(set.match("abc")).isEmpty();
    }
  }

  // ---------------------------------------------------------------------------
  // Tests ported from RE2 C++ set_test.cc (P3)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("RE2 C++ set_test.cc ports")
  class RE2SetTests {

    @Test
    @DisplayName("Unanchored with overlapping factored patterns")
    void unanchoredFactored() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      b.add("foobar");
      PatternSet set = b.compile();

      assertThat(set.match("foobar")).containsExactly(0, 1);
      assertThat(set.match("obarfoobaroo")).containsExactly(0, 1);
      assertThat(set.match("fooba")).containsExactly(0);
      assertThat(set.match("oobar")).isEmpty();
    }

    @Test
    @DisplayName("Unanchored with $ (end-of-string) anchor")
    void unanchoredDollar() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo$");
      PatternSet set = b.compile();

      assertThat(set.match("foo")).containsExactly(0);
      assertThat(set.match("foobar")).isEmpty();
    }

    @Test
    @DisplayName("Unanchored with \\b (word boundary)")
    void unanchoredWordBoundary() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo\\b");
      PatternSet set = b.compile();

      assertThat(set.match("foo")).containsExactly(0);
      assertThat(set.match("foobar")).isEmpty();
      assertThat(set.match("foo bar")).containsExactly(0);
    }

    @Test
    @DisplayName("Anchored with prefix and digit quantifier")
    void anchoredPrefix() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH);
      b.add("/prefix/\\d*");
      PatternSet set = b.compile();

      assertThat(set.match("/prefix")).isEmpty();
      assertThat(set.match("/prefix/")).containsExactly(0);
      assertThat(set.match("/prefix/42")).containsExactly(0);
    }

    @Test
    @DisplayName("Empty set unanchored rejects compile")
    void emptySetUnanchored() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      assertThatThrownBy(b::compile).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Empty set anchored rejects compile")
    void emptySetAnchored() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH);
      assertThatThrownBy(b::compile).isInstanceOf(IllegalStateException.class);
    }
  }
}
