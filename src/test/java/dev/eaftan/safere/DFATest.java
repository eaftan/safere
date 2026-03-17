// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DFA}.
 *
 * <p>Many tests verify that the DFA produces the same match/no-match result as the NFA. The DFA
 * only reports match boundaries (not capture groups), so tests focus on match detection and end
 * position.
 */
class DFATest {

  private static final int FLAGS =
      ParseFlags.PERL_X | ParseFlags.PERL_CLASSES | ParseFlags.PERL_B
          | ParseFlags.UNICODE_GROUPS;

  /** Compiles a pattern and searches with the DFA (unanchored, first match). */
  private static DFA.SearchResult search(String pattern, String text) {
    Regexp re = Parser.parse(pattern, FLAGS);
    Prog prog = Compiler.compile(re);
    return DFA.search(prog, text, false, false);
  }

  /** Compiles a pattern and searches with the DFA (anchored, longest match = full match). */
  private static DFA.SearchResult fullMatch(String pattern, String text) {
    Regexp re = Parser.parse(pattern, FLAGS);
    Prog prog = Compiler.compile(re);
    DFA.SearchResult r = DFA.search(prog, text, true, true);
    if (r != null && r.matched() && r.pos() != text.length()) {
      // Match didn't cover the entire text — not a full match.
      return new DFA.SearchResult(false, r.pos());
    }
    return r;
  }

  /** Compiles a pattern and searches with the DFA (unanchored, longest match). */
  private static DFA.SearchResult longestMatch(String pattern, String text) {
    Regexp re = Parser.parse(pattern, FLAGS);
    Prog prog = Compiler.compile(re);
    return DFA.search(prog, text, false, true);
  }

  // ---------------------------------------------------------------------------
  // Basic matching
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Literals")
  class Literals {
    @Test
    void singleChar() {
      DFA.SearchResult r = search("a", "a");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void multiChar() {
      DFA.SearchResult r = search("abc", "xabcy");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void noMatch() {
      DFA.SearchResult r = search("abc", "def");
      assertThat(r.matched()).isFalse();
    }

    @Test
    void emptyPattern() {
      DFA.SearchResult r = search("", "hello");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void emptyText() {
      DFA.SearchResult r = search("", "");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void atEnd() {
      DFA.SearchResult r = search("xyz", "abcxyz");
      assertThat(r.matched()).isTrue();
      assertThat(r.pos()).isEqualTo(6);
    }
  }

  @Nested
  @DisplayName("Character classes")
  class CharClasses {
    @Test
    void digitClass() {
      DFA.SearchResult r = search("\\d+", "abc123def");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void wordClass() {
      DFA.SearchResult r = search("\\w+", "hello");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void range() {
      DFA.SearchResult r = search("[a-z]+", "HELLO world");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void negatedClass() {
      DFA.SearchResult r = search("[^0-9]+", "123abc456");
      assertThat(r.matched()).isTrue();
    }
  }

  @Nested
  @DisplayName("Quantifiers")
  class Quantifiers {
    @Test
    void star() {
      DFA.SearchResult r = fullMatch("a*", "aaa");
      assertThat(r.matched()).isTrue();
      assertThat(r.pos()).isEqualTo(3);
    }

    @Test
    void plus() {
      DFA.SearchResult r = fullMatch("a+", "aaa");
      assertThat(r.matched()).isTrue();
      assertThat(r.pos()).isEqualTo(3);
    }

    @Test
    void plusNoMatch() {
      DFA.SearchResult r = fullMatch("a+", "");
      assertThat(r.matched()).isFalse();
    }

    @Test
    void quest() {
      DFA.SearchResult r = fullMatch("a?", "a");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void questEmpty() {
      DFA.SearchResult r = fullMatch("a?", "");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void repeat() {
      DFA.SearchResult r = fullMatch("a{3}", "aaa");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void repeatRange() {
      DFA.SearchResult r = fullMatch("a{2,4}", "aaa");
      assertThat(r.matched()).isTrue();
    }
  }

  @Nested
  @DisplayName("Alternation")
  class Alternation {
    @Test
    void firstAlt() {
      DFA.SearchResult r = search("cat|dog", "I have a cat");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void secondAlt() {
      DFA.SearchResult r = search("cat|dog", "I have a dog");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void noAlt() {
      DFA.SearchResult r = search("cat|dog", "I have a bird");
      assertThat(r.matched()).isFalse();
    }
  }

  @Nested
  @DisplayName("Anchors")
  class Anchors {
    @Test
    void startAnchor() {
      DFA.SearchResult r = search("^hello", "hello world");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void startAnchorFail() {
      DFA.SearchResult r = search("^hello", "say hello");
      assertThat(r.matched()).isFalse();
    }

    @Test
    void endAnchor() {
      DFA.SearchResult r = search("world$", "hello world");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void endAnchorFail() {
      DFA.SearchResult r = search("world$", "world cup");
      assertThat(r.matched()).isFalse();
    }

    @Test
    void fullAnchor() {
      DFA.SearchResult r = search("^abc$", "abc");
      assertThat(r.matched()).isTrue();
    }
  }

  @Nested
  @DisplayName("Dot")
  class Dot {
    @Test
    void dotMatchesChar() {
      DFA.SearchResult r = fullMatch("a.c", "abc");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void dotDoesNotMatchNewline() {
      DFA.SearchResult r = fullMatch("a.c", "a\nc");
      assertThat(r.matched()).isFalse();
    }

    @Test
    void dotPlus() {
      DFA.SearchResult r = fullMatch(".+", "hello");
      assertThat(r.matched()).isTrue();
      assertThat(r.pos()).isEqualTo(5);
    }
  }

  @Nested
  @DisplayName("Word boundary")
  class WordBoundary {
    @Test
    void wordBoundaryMatch() {
      DFA.SearchResult r = search("\\bfoo\\b", "foo bar");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void wordBoundaryNoMatch() {
      DFA.SearchResult r = search("\\bfoo\\b", "foobar");
      assertThat(r.matched()).isFalse();
    }

    @Test
    void wordBoundaryInMiddle() {
      DFA.SearchResult r = search("\\bbar\\b", "foo bar baz");
      assertThat(r.matched()).isTrue();
      assertThat(r.pos()).isEqualTo(7);
    }
  }

  @Nested
  @DisplayName("Match modes")
  class MatchModes {
    @Test
    void fullMatchSuccess() {
      DFA.SearchResult r = fullMatch("abc", "abc");
      assertThat(r.matched()).isTrue();
      assertThat(r.pos()).isEqualTo(3);
    }

    @Test
    void fullMatchFailure() {
      DFA.SearchResult r = fullMatch("abc", "abcd");
      assertThat(r.matched()).isFalse();
    }

    @Test
    void longestMatchGreedy() {
      DFA.SearchResult r = longestMatch("a+", "aaa");
      assertThat(r.matched()).isTrue();
      assertThat(r.pos()).isEqualTo(3);
    }

    @Test
    void firstMatchShortest() {
      DFA.SearchResult r = search("a+", "aaa");
      assertThat(r.matched()).isTrue();
      // First match should still match (may not return the shortest match
      // since DFA with .*? prefix finds leftmost).
    }
  }

  @Nested
  @DisplayName("Unicode")
  class Unicode {
    @Test
    void supplementaryPlane() {
      DFA.SearchResult r = search(".", "😀");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void unicodeLetters() {
      DFA.SearchResult r = search("[à-ÿ]+", "café");
      assertThat(r.matched()).isTrue();
    }
  }

  @Nested
  @DisplayName("Complex patterns")
  class Complex {
    @Test
    void emailLike() {
      DFA.SearchResult r = search("[a-z]+@[a-z]+\\.[a-z]+", "user@example.com");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void ipAddress() {
      DFA.SearchResult r = search("\\d+\\.\\d+\\.\\d+\\.\\d+", "ip is 192.168.1.1 here");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void alternationWithQuantifiers() {
      DFA.SearchResult r = fullMatch("(ab|cd)+", "ababcdab");
      assertThat(r.matched()).isTrue();
    }
  }

  @Nested
  @DisplayName("DFA vs NFA consistency")
  class Consistency {
    /** Verifies DFA and NFA agree on match/no-match for a set of patterns. */
    private void assertConsistent(String pattern, String text) {
      Regexp re = Parser.parse(pattern, FLAGS);
      Prog prog = Compiler.compile(re);

      DFA.SearchResult dfaResult = DFA.search(prog, text, false, false);
      int[] nfaResult = NFA.search(
          prog, text, NFA.Anchor.UNANCHORED, NFA.MatchKind.FIRST_MATCH, 1);

      assertThat(dfaResult).isNotNull();
      boolean nfaMatched = (nfaResult != null);
      assertThat(dfaResult.matched())
          .as("Pattern '%s' on '%s'", pattern, text)
          .isEqualTo(nfaMatched);
    }

    @Test
    void literals() {
      assertConsistent("abc", "abc");
      assertConsistent("abc", "xabcy");
      assertConsistent("abc", "def");
    }

    @Test
    void quantifiers() {
      assertConsistent("a*", "");
      assertConsistent("a*", "aaa");
      assertConsistent("a+", "aaa");
      assertConsistent("a+", "");
      assertConsistent("a?", "a");
      assertConsistent("a?", "b");
    }

    @Test
    void charClasses() {
      assertConsistent("\\d+", "abc123");
      assertConsistent("\\w+", "hello world");
      assertConsistent("[a-z]+", "ABC");
    }

    @Test
    void anchors() {
      assertConsistent("^abc", "abcdef");
      assertConsistent("^abc", "xabc");
      assertConsistent("def$", "abcdef");
      assertConsistent("def$", "defx");
    }

    @Test
    void alternation() {
      assertConsistent("cat|dog", "cat");
      assertConsistent("cat|dog", "dog");
      assertConsistent("cat|dog", "bird");
    }

    @Test
    void complex() {
      assertConsistent("(a|b)*c", "aababc");
      assertConsistent("(a|b)*c", "aabab");
      assertConsistent("\\bfoo\\b", "foo bar");
      assertConsistent("\\bfoo\\b", "foobar");
    }

    @Test
    void wordBoundary() {
      assertConsistent("\\bbar\\b", "foo bar baz");
      assertConsistent("\\bbar\\b", "foobar");
    }
  }

  @Nested
  @DisplayName("State budget")
  class Budget {
    @Test
    void exceedBudgetReturnsNull() {
      // Alternation with many branches forces many DFA states.
      Regexp re = Parser.parse("(a|b)(c|d)(e|f)(g|h)(i|j)", FLAGS);
      Prog prog = Compiler.compile(re);
      // Budget of 2 is too small for this pattern.
      DFA.SearchResult r = DFA.search(prog, "acegi", false, false, 2);
      // Budget exceeded -- should return null to signal fallback to NFA.
      assertThat(r).isNull();
    }

    @Test
    void linearTimeGuarantee() {
      // Pathological for backtracking: a?^n a^n
      int n = 25;
      String pattern = "a?".repeat(n) + "a".repeat(n);
      String text = "a".repeat(n);
      Regexp re = Parser.parse(pattern, FLAGS);
      Prog prog = Compiler.compile(re);
      DFA.SearchResult r = DFA.search(prog, text, true, true);
      // May bail out (return null) due to state explosion, which is fine.
      // The important thing is it completes quickly, not exponentially.
      if (r != null) {
        assertThat(r.matched()).isTrue();
      }
    }
  }
}
