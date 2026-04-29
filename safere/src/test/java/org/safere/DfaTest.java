// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Dfa}.
 *
 * <p>Many tests verify that the DFA produces the same match/no-match result as the NFA. The DFA
 * only reports match boundaries (not capture groups), so tests focus on match detection and end
 * position.
 */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class DfaTest {

  private static final int FLAGS =
      ParseFlags.PERL_X | ParseFlags.PERL_CLASSES | ParseFlags.PERL_B
          | ParseFlags.UNICODE_GROUPS;

  /** Compiles a pattern and searches with the DFA (unanchored, first match). */
  private static Dfa.SearchResult search(String pattern, String text) {
    Regexp re = Parser.parse(pattern, FLAGS);
    Prog prog = Compiler.compile(re);
    return Dfa.search(prog, text, false, false);
  }

  /** Compiles a pattern and searches with the DFA (anchored, longest match = full match). */
  private static Dfa.SearchResult fullMatch(String pattern, String text) {
    Regexp re = Parser.parse(pattern, FLAGS);
    Prog prog = Compiler.compile(re);
    Dfa.SearchResult r = Dfa.search(prog, text, true, true);
    if (r != null && r.matched() && r.pos() != text.length()) {
      // Match didn't cover the entire text — not a full match.
      return new Dfa.SearchResult(false, r.pos());
    }
    return r;
  }

  /** Compiles a pattern and searches with the DFA (unanchored, longest match). */
  private static Dfa.SearchResult longestMatch(String pattern, String text) {
    Regexp re = Parser.parse(pattern, FLAGS);
    Prog prog = Compiler.compile(re);
    return Dfa.search(prog, text, false, true);
  }

  // ---------------------------------------------------------------------------
  // Basic matching
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Literals")
  class Literals {
    @Test
    void singleChar() {
      Dfa.SearchResult r = search("a", "a");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void multiChar() {
      Dfa.SearchResult r = search("abc", "xabcy");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void noMatch() {
      Dfa.SearchResult r = search("abc", "def");
      assertThat(r.matched()).isFalse();
    }

    @Test
    void emptyPattern() {
      Dfa.SearchResult r = search("", "hello");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void emptyText() {
      Dfa.SearchResult r = search("", "");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void atEnd() {
      Dfa.SearchResult r = search("xyz", "abcxyz");
      assertThat(r.matched()).isTrue();
      assertThat(r.pos()).isEqualTo(6);
    }
  }

  @Nested
  @DisplayName("Character classes")
  class CharClasses {
    @Test
    void digitClass() {
      Dfa.SearchResult r = search("\\d+", "abc123def");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void wordClass() {
      Dfa.SearchResult r = search("\\w+", "hello");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void range() {
      Dfa.SearchResult r = search("[a-z]+", "HELLO world");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void negatedClass() {
      Dfa.SearchResult r = search("[^0-9]+", "123abc456");
      assertThat(r.matched()).isTrue();
    }
  }

  @Nested
  @DisplayName("Quantifiers")
  class Quantifiers {
    @Test
    void star() {
      Dfa.SearchResult r = fullMatch("a*", "aaa");
      assertThat(r.matched()).isTrue();
      assertThat(r.pos()).isEqualTo(3);
    }

    @Test
    void plus() {
      Dfa.SearchResult r = fullMatch("a+", "aaa");
      assertThat(r.matched()).isTrue();
      assertThat(r.pos()).isEqualTo(3);
    }

    @Test
    void plusNoMatch() {
      Dfa.SearchResult r = fullMatch("a+", "");
      assertThat(r.matched()).isFalse();
    }

    @Test
    void quest() {
      Dfa.SearchResult r = fullMatch("a?", "a");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void questEmpty() {
      Dfa.SearchResult r = fullMatch("a?", "");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void repeat() {
      Dfa.SearchResult r = fullMatch("a{3}", "aaa");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void repeatRange() {
      Dfa.SearchResult r = fullMatch("a{2,4}", "aaa");
      assertThat(r.matched()).isTrue();
    }
  }

  @Nested
  @DisplayName("Alternation")
  class Alternation {
    @Test
    void firstAlt() {
      Dfa.SearchResult r = search("cat|dog", "I have a cat");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void secondAlt() {
      Dfa.SearchResult r = search("cat|dog", "I have a dog");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void noAlt() {
      Dfa.SearchResult r = search("cat|dog", "I have a bird");
      assertThat(r.matched()).isFalse();
    }
  }

  @Nested
  @DisplayName("Anchors")
  class Anchors {
    @Test
    void startAnchor() {
      Dfa.SearchResult r = search("^hello", "hello world");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void startAnchorFail() {
      Dfa.SearchResult r = search("^hello", "say hello");
      assertThat(r.matched()).isFalse();
    }

    @Test
    void endAnchor() {
      Dfa.SearchResult r = search("world$", "hello world");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void endAnchorFail() {
      Dfa.SearchResult r = search("world$", "world cup");
      assertThat(r.matched()).isFalse();
    }

    @Test
    void fullAnchor() {
      Dfa.SearchResult r = search("^abc$", "abc");
      assertThat(r.matched()).isTrue();
    }
  }

  @Nested
  @DisplayName("Dot")
  class Dot {
    @Test
    void dotMatchesChar() {
      Dfa.SearchResult r = fullMatch("a.c", "abc");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void dotDoesNotMatchNewline() {
      Dfa.SearchResult r = fullMatch("a.c", "a\nc");
      assertThat(r.matched()).isFalse();
    }

    @Test
    void dotPlus() {
      Dfa.SearchResult r = fullMatch(".+", "hello");
      assertThat(r.matched()).isTrue();
      assertThat(r.pos()).isEqualTo(5);
    }
  }

  @Nested
  @DisplayName("Word boundary")
  class WordBoundary {
    @Test
    void wordBoundaryMatch() {
      Dfa.SearchResult r = search("\\bfoo\\b", "foo bar");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void wordBoundaryNoMatch() {
      Dfa.SearchResult r = search("\\bfoo\\b", "foobar");
      assertThat(r.matched()).isFalse();
    }

    @Test
    void wordBoundaryInMiddle() {
      Dfa.SearchResult r = search("\\bbar\\b", "foo bar baz");
      assertThat(r.matched()).isTrue();
      assertThat(r.pos()).isEqualTo(7);
    }
  }

  @Nested
  @DisplayName("Match modes")
  class MatchModes {
    @Test
    void fullMatchSuccess() {
      Dfa.SearchResult r = fullMatch("abc", "abc");
      assertThat(r.matched()).isTrue();
      assertThat(r.pos()).isEqualTo(3);
    }

    @Test
    void fullMatchFailure() {
      Dfa.SearchResult r = fullMatch("abc", "abcd");
      assertThat(r.matched()).isFalse();
    }

    @Test
    void longestMatchGreedy() {
      Dfa.SearchResult r = longestMatch("a+", "aaa");
      assertThat(r.matched()).isTrue();
      assertThat(r.pos()).isEqualTo(3);
    }

    @Test
    void firstMatchShortest() {
      Dfa.SearchResult r = search("a+", "aaa");
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
      Dfa.SearchResult r = search(".", "😀");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void unicodeLetters() {
      Dfa.SearchResult r = search("[à-ÿ]+", "café");
      assertThat(r.matched()).isTrue();
    }
  }

  @Nested
  @DisplayName("Complex patterns")
  class Complex {
    @Test
    void emailLike() {
      Dfa.SearchResult r = search("[a-z]+@[a-z]+\\.[a-z]+", "user@example.com");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void ipAddress() {
      Dfa.SearchResult r = search("\\d+\\.\\d+\\.\\d+\\.\\d+", "ip is 192.168.1.1 here");
      assertThat(r.matched()).isTrue();
    }

    @Test
    void alternationWithQuantifiers() {
      Dfa.SearchResult r = fullMatch("(ab|cd)+", "ababcdab");
      assertThat(r.matched()).isTrue();
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
      Dfa.SearchResult r = Dfa.search(prog, "acegi", false, false, 2);
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
      Dfa.SearchResult r = Dfa.search(prog, text, true, true);
      // May bail out (return null) due to state explosion, which is fine.
      // The important thing is it completes quickly, not exponentially.
      if (r != null) {
        assertThat(r.matched()).isTrue();
      }
    }
  }
}
