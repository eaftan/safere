// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link BitState}.
 *
 * <p>Tests verify that BitState produces the same results as the NFA for both anchored and
 * unanchored searches across a variety of patterns and inputs.
 */
class BitStateTest {

  private static final int FLAGS =
      ParseFlags.PERL_X | ParseFlags.PERL_CLASSES | ParseFlags.PERL_B
          | ParseFlags.UNICODE_GROUPS;

  /** Asserts BitState and NFA agree on an anchored search. */
  private static void assertConsistentAnchored(String pattern, String text,
      boolean longest, boolean endMatch) {
    Regexp re = Parser.parse(pattern, FLAGS);
    Prog prog = Compiler.compile(re);
    int nsubmatch = prog.numCaptures();

    int[] bsResult = BitState.search(prog, text, true, longest, endMatch, nsubmatch);
    Nfa.MatchKind kind = endMatch ? Nfa.MatchKind.FULL_MATCH
        : (longest ? Nfa.MatchKind.LONGEST_MATCH : Nfa.MatchKind.FIRST_MATCH);
    int[] nfaResult = Nfa.search(prog, text, Nfa.Anchor.ANCHORED, kind, nsubmatch);

    assertCapturesEqual(pattern, text, bsResult, nfaResult);
  }

  /** Asserts BitState and NFA agree on an unanchored search. */
  private static void assertConsistentUnanchored(String pattern, String text) {
    Regexp re = Parser.parse(pattern, FLAGS);
    Prog prog = Compiler.compile(re);
    int nsubmatch = prog.numCaptures();

    int[] bsResult = BitState.search(prog, text, false, false, false, nsubmatch);
    int[] nfaResult = Nfa.search(
        prog, text, Nfa.Anchor.UNANCHORED, Nfa.MatchKind.FIRST_MATCH, nsubmatch);

    assertCapturesEqual(pattern, text, bsResult, nfaResult);
  }

  private static void assertCapturesEqual(String pattern, String text,
      int[] bsResult, int[] nfaResult) {
    if (nfaResult == null) {
      assertThat(bsResult)
          .as("/%s/ on \"%s\": NFA=null, BitState should too", pattern, text)
          .isNull();
    } else {
      assertThat(bsResult)
          .as("/%s/ on \"%s\": NFA matched, BitState should too", pattern, text)
          .isNotNull();
      int len = Math.min(bsResult.length, nfaResult.length);
      for (int i = 0; i < len; i++) {
        assertThat(bsResult[i])
            .as("/%s/ on \"%s\": cap[%d]", pattern, text, i)
            .isEqualTo(nfaResult[i]);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Basic tests
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Basic matching")
  class Basic {
    @Test
    void literalMatch() {
      assertConsistentAnchored("abc", "abc", false, true);
    }

    @Test
    void literalNoMatch() {
      assertConsistentAnchored("abc", "xyz", false, true);
    }

    @Test
    void fullMatch() {
      assertConsistentAnchored("abc", "abc", false, true);
      assertConsistentAnchored("abc", "abcd", false, true);
    }

    @Test
    void prefixMatch() {
      assertConsistentAnchored("abc", "abcdef", false, false);
      assertConsistentAnchored("abc", "xyz", false, false);
    }

    @Test
    void emptyMatch() {
      assertConsistentAnchored("", "", false, false);
      assertConsistentAnchored("", "abc", false, false);
    }

    @Test
    void unanchoredSearch() {
      assertConsistentUnanchored("abc", "xyzabcdef");
      assertConsistentUnanchored("abc", "xyz");
    }
  }

  // ---------------------------------------------------------------------------
  // Capture groups
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Capture groups")
  class CaptureGroups {
    @Test
    void singleGroup() {
      assertConsistentAnchored("(abc)", "abc", false, true);
    }

    @Test
    void twoGroups() {
      assertConsistentAnchored("(\\d+)-(\\d+)", "123-456", false, true);
    }

    @Test
    void nestedGroups() {
      assertConsistentAnchored("(a(b(c)))", "abc", false, true);
    }

    @Test
    void manyGroups() {
      assertConsistentAnchored("(a)(b)(c)(d)(e)(f)(g)", "abcdefg", false, true);
    }

    @Test
    void optionalGroup() {
      assertConsistentAnchored("(a)?(b)", "b", false, false);
      assertConsistentAnchored("(a)?(b)", "ab", false, false);
    }

    @Test
    void unanchoredCaptures() {
      assertConsistentUnanchored("(\\w+)@(\\w+)", "user@host");
      assertConsistentUnanchored("(\\d+)", "abc123def");
    }
  }

  // ---------------------------------------------------------------------------
  // Quantifiers
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Quantifiers")
  class Quantifiers {
    @Test
    void star() {
      assertConsistentAnchored("a*", "aaa", false, false);
      assertConsistentAnchored("a*", "", false, false);
      assertConsistentAnchored("a*b", "aaab", false, true);
    }

    @Test
    void plus() {
      assertConsistentAnchored("a+", "aaa", false, false);
      assertConsistentAnchored("a+", "", false, false);
    }

    @Test
    void question() {
      assertConsistentAnchored("a?b", "ab", false, true);
      assertConsistentAnchored("a?b", "b", false, true);
    }

    @Test
    void repetition() {
      assertConsistentAnchored("a{3}", "aaa", false, true);
      assertConsistentAnchored("a{2,4}", "aaaa", false, true);
    }
  }

  // ---------------------------------------------------------------------------
  // Alternation
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Alternation")
  class Alternation {
    @Test
    void simpleAlt() {
      assertConsistentAnchored("cat|dog", "cat", false, true);
      assertConsistentAnchored("cat|dog", "dog", false, true);
      assertConsistentAnchored("cat|dog", "cow", false, true);
    }

    @Test
    void altWithGroups() {
      assertConsistentAnchored("(cat|dog)", "dog", false, true);
      assertConsistentAnchored("(a|b)(c|d)", "bc", false, true);
    }

    @Test
    void unanchoredAlt() {
      assertConsistentUnanchored("cat|dog", "I have a cat");
      assertConsistentUnanchored("cat|dog", "I have a dog");
    }
  }

  // ---------------------------------------------------------------------------
  // Anchors and assertions
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Anchors and assertions")
  class Anchors {
    @Test
    void wordBoundary() {
      assertConsistentUnanchored("\\bfoo\\b", "foo bar");
      assertConsistentUnanchored("\\bfoo\\b", "foobar");
    }

    @Test
    void beginEnd() {
      assertConsistentAnchored("^abc$", "abc", false, true);
      assertConsistentAnchored("^abc$", "abcd", false, true);
    }
  }

  // ---------------------------------------------------------------------------
  // Budget / text size limit
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Budget")
  class Budget {
    @Test
    void tooLargeReturnsNull() {
      Regexp re = Parser.parse("abc", FLAGS);
      Prog prog = Compiler.compile(re);
      // Create a text larger than the max BitState budget.
      int maxLen = BitState.maxTextSize(prog);
      if (maxLen > 0) {
        String largeText = "a".repeat(maxLen + 1);
        int[] result = BitState.search(prog, largeText, false, false, false, 1);
        assertThat(result).isNull();
      }
    }

    @Test
    void withinBudgetWorks() {
      Regexp re = Parser.parse("abc", FLAGS);
      Prog prog = Compiler.compile(re);
      int maxLen = BitState.maxTextSize(prog);
      if (maxLen > 3) {
        int[] result = BitState.search(prog, "abc", true, false, true, 1);
        assertThat(result).isNotNull();
        assertThat(result[0]).isEqualTo(0);
        assertThat(result[1]).isEqualTo(3);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Systematic consistency (parameterized)
  // ---------------------------------------------------------------------------

  record TestCase(String pattern, String input) {
    @Override
    public String toString() {
      return "/" + pattern + "/ on \"" + input + "\"";
    }
  }

  static Stream<TestCase> consistencyCases() {
    List<TestCase> cases = new ArrayList<>();
    cases.add(new TestCase("abc", "abc"));
    cases.add(new TestCase("abc", "xabcy"));
    cases.add(new TestCase("abc", "def"));
    cases.add(new TestCase("a+", "aaa"));
    cases.add(new TestCase("a*", ""));
    cases.add(new TestCase("a*b", "aaab"));
    cases.add(new TestCase("(a|b)+", "abba"));
    cases.add(new TestCase("(\\d+)-(\\w+)", "123-abc"));
    cases.add(new TestCase("(\\d+)-(\\w+)", "nope"));
    cases.add(new TestCase("[a-z]+@[a-z]+", "user@host"));
    cases.add(new TestCase("\\bfoo\\b", "foo bar"));
    cases.add(new TestCase("\\bfoo\\b", "foobar"));
    cases.add(new TestCase("(a(b)c)", "abc"));
    cases.add(new TestCase("x(y|z)w", "xyw"));
    cases.add(new TestCase("(.*)x", "abcx"));
    cases.add(new TestCase("a{2,4}", "aaaa"));
    cases.add(new TestCase("(?i)abc", "ABC"));
    cases.add(new TestCase("caf\u00e9", "caf\u00e9"));
    return cases.stream();
  }

  @ParameterizedTest(name = "unanchored: {0}")
  @MethodSource("consistencyCases")
  void unanchoredConsistency(TestCase tc) {
    assertConsistentUnanchored(tc.pattern(), tc.input());
  }

  @ParameterizedTest(name = "anchored fullMatch: {0}")
  @MethodSource("consistencyCases")
  void anchoredFullMatchConsistency(TestCase tc) {
    assertConsistentAnchored(tc.pattern(), tc.input(), false, true);
  }
}
