// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link Nfa}. End-to-end tests: parse → compile → match. */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class NfaTest {

  private static final int FLAGS =
      ParseFlags.PERL_X | ParseFlags.PERL_CLASSES | ParseFlags.PERL_B
          | ParseFlags.UNICODE_GROUPS;

  /** Search with default first-match, unanchored semantics. */
  private static int[] search(String pattern, String text) {
    Regexp re = Parser.parse(pattern, FLAGS);
    Prog prog = Compiler.compile(re);
    return Nfa.search(
        prog, text, Nfa.Anchor.UNANCHORED, Nfa.MatchKind.FIRST_MATCH, prog.numCaptures());
  }

  /** Search with full-match semantics. */
  private static int[] fullMatch(String pattern, String text) {
    Regexp re = Parser.parse(pattern, FLAGS);
    Prog prog = Compiler.compile(re);
    return Nfa.search(
        prog, text, Nfa.Anchor.UNANCHORED, Nfa.MatchKind.FULL_MATCH, prog.numCaptures());
  }

  /** Search with longest-match semantics. */
  private static int[] longestMatch(String pattern, String text) {
    Regexp re = Parser.parse(pattern, FLAGS);
    Prog prog = Compiler.compile(re);
    return Nfa.search(
        prog, text, Nfa.Anchor.UNANCHORED, Nfa.MatchKind.LONGEST_MATCH, prog.numCaptures());
  }

  /** Search with anchored semantics. */
  private static int[] anchoredSearch(String pattern, String text) {
    Regexp re = Parser.parse(pattern, FLAGS);
    Prog prog = Compiler.compile(re);
    return Nfa.search(
        prog, text, Nfa.Anchor.ANCHORED, Nfa.MatchKind.FIRST_MATCH, prog.numCaptures());
  }

  @Nested
  @DisplayName("Simple literals")
  class Literals {
    @Test
    void singleChar() {
      int[] m = search("a", "a");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(1);
    }

    @Test
    void multiChar() {
      int[] m = search("abc", "xabcy");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(1);
      assertThat(m[1]).isEqualTo(4);
    }

    @Test
    void atEnd() {
      int[] m = search("xyz", "abcxyz");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(3);
      assertThat(m[1]).isEqualTo(6);
    }
  }

  @Nested
  @DisplayName("No match")
  class NoMatch {
    @Test
    void noMatch() {
      assertThat(search("xyz", "abc")).isNull();
    }

    @Test
    void emptyText() {
      assertThat(search("a", "")).isNull();
    }
  }

  @Nested
  @DisplayName("Alternation")
  class Alternation {
    @Test
    void simpleAlt() {
      int[] m = search("cat|dog", "I have a dog");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(9);
      assertThat(m[1]).isEqualTo(12);
    }

    @Test
    void leftmostWins() {
      int[] m = search("a|b", "ba");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("Quantifiers")
  class Quantifiers {
    @Test
    void starGreedy() {
      int[] m = search("a*", "aaa");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(3);
    }

    @Test
    void starEmptyMatch() {
      int[] m = search("a*", "bbb");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(0);
    }

    @Test
    void plusMatch() {
      int[] m = search("a+", "aaa");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(3);
    }

    @Test
    void plusNoMatch() {
      assertThat(search("a+", "bbb")).isNull();
    }

    @Test
    void questMatch() {
      int[] m = search("a?", "a");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(1);
    }

    @Test
    void questEmpty() {
      int[] m = search("a?", "b");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("Non-greedy quantifiers")
  class NonGreedy {
    @Test
    void starNonGreedy() {
      int[] m = search("a*?", "aaa");
      assertThat(m).isNotNull();
      // Non-greedy star: matches empty at position 0
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(0);
    }

    @Test
    void plusNonGreedy() {
      int[] m = search("a+?", "aaa");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("Dot")
  class Dot {
    @Test
    void dotSingle() {
      int[] m = search(".", "x");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(1);
    }

    @Test
    void dotPlus() {
      int[] m = search(".+", "hello");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(5);
    }

    @Test
    void dotDoesNotMatchNewline() {
      assertThat(search("^.$", "\n")).isNull();
    }
  }

  @Nested
  @DisplayName("Character classes")
  class CharClasses {
    @Test
    void simpleClass() {
      int[] m = search("[abc]", "b");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(1);
    }

    @Test
    void negatedClass() {
      int[] m = search("[^a-z]", "a1b");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(1);
      assertThat(m[1]).isEqualTo(2);
    }

    @Test
    void rangeClass() {
      int[] m = search("[a-z]+", "hello");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(5);
    }

    @Test
    void digitClass() {
      int[] m = search("\\d+", "abc123def");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(3);
      assertThat(m[1]).isEqualTo(6);
    }
  }

  @Nested
  @DisplayName("Anchors")
  class Anchors {
    @Test
    void beginLine() {
      int[] m = search("^abc", "abcdef");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(3);
    }

    @Test
    void beginLineNoMatch() {
      assertThat(search("^abc", "xabc")).isNull();
    }

    @Test
    void endLine() {
      int[] m = search("abc$", "xyzabc");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(3);
      assertThat(m[1]).isEqualTo(6);
    }

    @Test
    void fullAnchor() {
      int[] m = search("^abc$", "abc");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(3);
    }

    @Test
    void fullAnchorNoMatch() {
      assertThat(search("^abc$", "abcd")).isNull();
    }
  }

  @Nested
  @DisplayName("Captures")
  class Captures {
    @Test
    void twoGroups() {
      int[] m = search("(a+)(b+)", "aaabb");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0); // full match start
      assertThat(m[1]).isEqualTo(5); // full match end
      assertThat(m[2]).isEqualTo(0); // group 1 start
      assertThat(m[3]).isEqualTo(3); // group 1 end
      assertThat(m[4]).isEqualTo(3); // group 2 start
      assertThat(m[5]).isEqualTo(5); // group 2 end
    }

    @Test
    void nestedCaptures() {
      int[] m = search("((a)(b))", "ab");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0); // full match start
      assertThat(m[1]).isEqualTo(2); // full match end
      assertThat(m[2]).isEqualTo(0); // group 1 start
      assertThat(m[3]).isEqualTo(2); // group 1 end
      assertThat(m[4]).isEqualTo(0); // group 2 start
      assertThat(m[5]).isEqualTo(1); // group 2 end
      assertThat(m[6]).isEqualTo(1); // group 3 start
      assertThat(m[7]).isEqualTo(2); // group 3 end
    }
  }

  @Nested
  @DisplayName("Word boundary")
  class WordBoundary {
    @Test
    void wordBoundaryMatch() {
      int[] m = search("\\bfoo\\b", "foo bar");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(3);
    }

    @Test
    void wordBoundaryNoMatch() {
      assertThat(search("\\bfoo\\b", "foobar")).isNull();
    }

    @Test
    void wordBoundaryInMiddle() {
      int[] m = search("\\bbar\\b", "foo bar baz");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(4);
      assertThat(m[1]).isEqualTo(7);
    }
  }

  @Nested
  @DisplayName("Match modes")
  class MatchModes {
    @Test
    void fullMatchSuccess() {
      int[] m = fullMatch("abc", "abc");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(3);
    }

    @Test
    void fullMatchFailure() {
      assertThat(fullMatch("abc", "abcd")).isNull();
    }

    @Test
    void fullMatchPartialNoMatch() {
      assertThat(fullMatch("abc", "xabc")).isNull();
    }

    @Test
    void longestMatchMode() {
      int[] m = longestMatch("a+", "aaa");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(3);
    }

    @Test
    void anchoredMatch() {
      int[] m = anchoredSearch("abc", "abcdef");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(3);
    }

    @Test
    void anchoredNoMatch() {
      assertThat(anchoredSearch("abc", "xabc")).isNull();
    }
  }

  @Nested
  @DisplayName("Unanchored search")
  class Unanchored {
    @Test
    void findsLeftmost() {
      int[] m = search("abc", "xxabcyy");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(2);
      assertThat(m[1]).isEqualTo(5);
    }
  }

  @Nested
  @DisplayName("Empty patterns")
  class EmptyPatterns {
    @Test
    void emptyPattern() {
      int[] m = search("", "abc");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(0);
    }

    @Test
    void emptyPatternEmptyText() {
      int[] m = search("", "");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("Unicode")
  class Unicode {
    @Test
    void dotMatchesEmoji() {
      // 😀 is U+1F600, encoded as a surrogate pair (2 chars)
      String emoji = "\uD83D\uDE00";
      int[] m = search(".", emoji);
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(2); // 2 chars for the surrogate pair
    }

    @Test
    void unicodeLetters() {
      int[] m = search("[a-z]+", "café");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(3); // "caf" — é is not in [a-z]
    }
  }

  @Nested
  @DisplayName("Complex patterns")
  class Complex {
    @Test
    void digitsDotDigits() {
      int[] m = search("(\\d+)\\.(\\d+)", "version 3.14 here");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(8);  // "3.14" start
      assertThat(m[1]).isEqualTo(12); // "3.14" end
      assertThat(m[2]).isEqualTo(8);  // group 1: "3"
      assertThat(m[3]).isEqualTo(9);
      assertThat(m[4]).isEqualTo(10); // group 2: "14"
      assertThat(m[5]).isEqualTo(12);
    }

    @Test
    void alternationWithQuantifiers() {
      int[] m = search("(foo|bar)+", "foobarfoo");
      assertThat(m).isNotNull();
      assertThat(m[0]).isEqualTo(0);
      assertThat(m[1]).isEqualTo(9);
    }
  }

  @Nested
  @DisplayName("Linear time guarantee")
  class LinearTime {
    @Test
    void pathologicalPattern() {
      // a?^n a^n matched against a^n — classic pathological case for backtracking.
      // With Pike VM, this should complete in linear time.
      int n = 25;
      String questPart = "a?".repeat(n);
      String litPart = "a".repeat(n);
      String pattern = questPart + litPart;
      String text = litPart;

      int[] m = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> fullMatch(pattern, text));

      assertThat(m).isNotNull();
    }
  }

  @Nested
  @DisplayName("EmptyFlags helper")
  class EmptyFlags {
    @Test
    void beginOfText() {
      int flags = Nfa.emptyFlags("abc", 0, false);
      assertThat(flags & EmptyOp.BEGIN_TEXT).isNotZero();
      assertThat(flags & EmptyOp.BEGIN_LINE).isNotZero();
    }

    @Test
    void endOfText() {
      int flags = Nfa.emptyFlags("abc", 3, false);
      assertThat(flags & EmptyOp.END_TEXT).isNotZero();
      assertThat(flags & EmptyOp.END_LINE).isNotZero();
    }

    @Test
    void midText() {
      int flags = Nfa.emptyFlags("abc", 1, false);
      assertThat(flags & EmptyOp.BEGIN_TEXT).isZero();
      assertThat(flags & EmptyOp.END_TEXT).isZero();
    }

    @Test
    void afterNewline() {
      int flags = Nfa.emptyFlags("a\nb", 2, false);
      assertThat(flags & EmptyOp.BEGIN_LINE).isNotZero();
    }

    @Test
    void wordBoundary() {
      int flags = Nfa.emptyFlags("foo bar", 3, false);
      assertThat(flags & EmptyOp.WORD_BOUNDARY).isNotZero();
    }

    @Test
    void nonWordBoundary() {
      int flags = Nfa.emptyFlags("foo", 1, false);
      assertThat(flags & EmptyOp.NON_WORD_BOUNDARY).isNotZero();
    }

    @Test
    @DisplayName("BEGIN_LINE after standalone \\r")
    void beginLineAfterCr() {
      int flags = Nfa.emptyFlags("a\rb", 2, false);
      assertThat(flags & EmptyOp.BEGIN_LINE).isNotZero();
    }

    @Test
    @DisplayName("BEGIN_LINE NOT between \\r and \\n in \\r\\n")
    void noBeginLineBetweenCrLf() {
      int flags = Nfa.emptyFlags("a\r\nb", 2, false);
      assertThat(flags & EmptyOp.BEGIN_LINE).isZero();
    }

    @Test
    @DisplayName("END_LINE before standalone \\r")
    void endLineBeforeCr() {
      int flags = Nfa.emptyFlags("a\rb", 1, false);
      assertThat(flags & EmptyOp.END_LINE).isNotZero();
    }

    @Test
    @DisplayName("END_LINE NOT between \\r and \\n in \\r\\n (#78)")
    void noEndLineBetweenCrLf() {
      // Position 1 is at the \r in "a\r\nb"; END_LINE SHOULD fire here (before the \r\n pair).
      int flags = Nfa.emptyFlags("a\r\nb", 1, false);
      assertThat(flags & EmptyOp.END_LINE).isNotZero();

      // Position 2 is at the \n in "a\r\nb" (between \r and \n in the atomic pair);
      // END_LINE must NOT fire here.
      int flags2 = Nfa.emptyFlags("a\r\nb", 2, false);
      assertThat(flags2 & EmptyOp.END_LINE).isZero();
    }

    @Test
    @DisplayName("END_LINE at standalone \\r not followed by \\n still fires")
    void endLineAtStandaloneCr() {
      int flags = Nfa.emptyFlags("\r", 0, false);
      assertThat(flags & EmptyOp.END_LINE).isNotZero();
    }

    @Test
    @DisplayName("UNIX_LINES: \\r is not a line terminator")
    void unixLinesCrNotLineTerm() {
      // After \r: no BEGIN_LINE in UNIX_LINES mode
      int flags = Nfa.emptyFlags("a\rb", 2, true);
      assertThat(flags & EmptyOp.BEGIN_LINE).isZero();
      // Before \r: no END_LINE in UNIX_LINES mode
      int flags2 = Nfa.emptyFlags("a\rb", 1, true);
      assertThat(flags2 & EmptyOp.END_LINE).isZero();
    }
  }
}
