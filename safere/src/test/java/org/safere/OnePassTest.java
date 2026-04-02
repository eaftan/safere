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
 * Tests for {@link OnePass}.
 *
 * <p>Tests verify: (1) correct one-pass detection, (2) match results agree with the NFA, and (3)
 * capture groups are correctly extracted.
 */
class OnePassTest {

  private static final int FLAGS =
      ParseFlags.PERL_X | ParseFlags.PERL_CLASSES | ParseFlags.PERL_B
          | ParseFlags.UNICODE_GROUPS;

  /** Helper: build a OnePass automaton, or null if not one-pass. */
  private static OnePass build(String pattern) {
    Regexp re = Parser.parse(pattern, FLAGS);
    Prog prog = Compiler.compile(re);
    return OnePass.build(prog);
  }

  /** Helper: run one-pass search (anchored, endMatch=false). */
  private static int[] search(String pattern, String text) {
    Regexp re = Parser.parse(pattern, FLAGS);
    Prog prog = Compiler.compile(re);
    OnePass op = OnePass.build(prog);
    assertThat(op).as("Pattern '%s' should be one-pass", pattern).isNotNull();
    return op.search(text, false, prog.numCaptures());
  }

  /** Helper: run one-pass search (anchored, endMatch=true, full match). */
  private static int[] fullMatch(String pattern, String text) {
    Regexp re = Parser.parse(pattern, FLAGS);
    Prog prog = Compiler.compile(re);
    OnePass op = OnePass.build(prog);
    assertThat(op).as("Pattern '%s' should be one-pass", pattern).isNotNull();
    return op.search(text, true, prog.numCaptures());
  }

  /** Asserts that OnePass and NFA agree on an anchored search. */
  private static void assertConsistentWithNfa(String pattern, String text, boolean endMatch) {
    Regexp re = Parser.parse(pattern, FLAGS);
    Prog prog = Compiler.compile(re);
    OnePass op = OnePass.build(prog);
    if (op == null) {
      return; // not one-pass; skip
    }
    int[] opResult = op.search(text, endMatch, prog.numCaptures());
    Nfa.MatchKind kind = endMatch ? Nfa.MatchKind.FULL_MATCH : Nfa.MatchKind.FIRST_MATCH;
    int[] nfaResult = Nfa.search(prog, text, Nfa.Anchor.ANCHORED, kind, prog.numCaptures());

    if (nfaResult == null) {
      assertThat(opResult).as("/%s/ on \"%s\": NFA=null, OnePass should too", pattern, text)
          .isNull();
    } else {
      assertThat(opResult).as("/%s/ on \"%s\": NFA matched, OnePass should too", pattern, text)
          .isNotNull();
      // Compare capture groups.
      int len = Math.min(opResult.length, nfaResult.length);
      for (int i = 0; i < len; i++) {
        assertThat(opResult[i])
            .as("/%s/ on \"%s\": cap[%d]", pattern, text, i)
            .isEqualTo(nfaResult[i]);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // One-pass detection
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("One-pass detection")
  class Detection {
    @Test
    void simpleOnePassPatterns() {
      // These should all be one-pass.
      assertThat(build("abc")).isNotNull();
      assertThat(build("a")).isNotNull();
      assertThat(build("[a-z]")).isNotNull();
      assertThat(build("a.c")).isNotNull();
      assertThat(build("x(y|z)")).isNotNull();
      assertThat(build("\\d+-\\d+")).isNotNull();
      assertThat(build("[^ ]+ .*")).isNotNull();
    }

    @Test
    void notOnePassPatterns() {
      // These should NOT be one-pass.
      assertThat(build("(xy|xz)")).isNull();   // both start with x
      assertThat(build("(.*)(.*)")).isNull();   // two greedy .* compete
    }

    @Test
    void tooManyCaptures() {
      // More than 16 capture groups (including group 0) -> not one-pass.
      // 16 user groups = 17 total -> exceeds MAX_CAPTURE_GROUPS.
      assertThat(build("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)(l)(m)(n)(o)(p)")).isNull();
    }

    @Test
    void maxCaptures() {
      // Exactly 16 total captures (15 user groups + group 0) -> still one-pass.
      assertThat(build("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)(l)(m)(n)(o)")).isNotNull();
    }

    @Test
    void emptyPattern() {
      assertThat(build("")).isNotNull();
    }
  }

  // ---------------------------------------------------------------------------
  // Basic matching
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Basic matching")
  class BasicMatching {
    @Test
    void literalMatch() {
      int[] r = search("abc", "abcdef");
      assertThat(r).isNotNull();
      assertThat(r[0]).isEqualTo(0);
      assertThat(r[1]).isEqualTo(3);
    }

    @Test
    void literalNoMatch() {
      int[] r = search("abc", "xyzdef");
      assertThat(r).isNull();
    }

    @Test
    void fullMatchSuccess() {
      int[] r = fullMatch("abc", "abc");
      assertThat(r).isNotNull();
      assertThat(r[0]).isEqualTo(0);
      assertThat(r[1]).isEqualTo(3);
    }

    @Test
    void fullMatchFailure() {
      int[] r = fullMatch("abc", "abcd");
      assertThat(r).isNull();
    }

    @Test
    void emptyMatch() {
      int[] r = search("", "anything");
      assertThat(r).isNotNull();
      assertThat(r[0]).isEqualTo(0);
      assertThat(r[1]).isEqualTo(0);
    }

    @Test
    void dotMatch() {
      int[] r = search("a.c", "abc");
      assertThat(r).isNotNull();
      assertThat(r[1]).isEqualTo(3);
    }

    @Test
    void charClassMatch() {
      int[] r = search("[a-z]+", "hello123");
      assertThat(r).isNotNull();
      assertThat(r[0]).isEqualTo(0);
      assertThat(r[1]).isEqualTo(5);
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
      int[] r = search("(abc)", "abcdef");
      assertThat(r).isNotNull();
      assertThat(r[0]).isEqualTo(0); // group 0 start
      assertThat(r[1]).isEqualTo(3); // group 0 end
      assertThat(r[2]).isEqualTo(0); // group 1 start
      assertThat(r[3]).isEqualTo(3); // group 1 end
    }

    @Test
    void twoGroups() {
      int[] r = search("(\\d+)-(\\d+)", "123-456rest");
      assertThat(r).isNotNull();
      assertThat(r[0]).isEqualTo(0);
      assertThat(r[1]).isEqualTo(7);
      assertThat(r[2]).isEqualTo(0); // group 1: "123"
      assertThat(r[3]).isEqualTo(3);
      assertThat(r[4]).isEqualTo(4); // group 2: "456"
      assertThat(r[5]).isEqualTo(7);
    }

    @Test
    void alternationGroup() {
      int[] r = search("x(y|z)", "xz");
      assertThat(r).isNotNull();
      assertThat(r[0]).isEqualTo(0);
      assertThat(r[1]).isEqualTo(2);
      assertThat(r[2]).isEqualTo(1); // group 1: "z"
      assertThat(r[3]).isEqualTo(2);
    }

    @Test
    void nestedGroup() {
      int[] r = search("(a(b)c)", "abc");
      assertThat(r).isNotNull();
      assertThat(r[0]).isEqualTo(0);
      assertThat(r[1]).isEqualTo(3);
      assertThat(r[2]).isEqualTo(0); // group 1: "abc"
      assertThat(r[3]).isEqualTo(3);
      assertThat(r[4]).isEqualTo(1); // group 2: "b"
      assertThat(r[5]).isEqualTo(2);
    }

    @Test
    void fiveGroups() {
      int[] r = search("(a)(b)(c)(d)(e)", "abcde");
      assertThat(r).isNotNull();
      assertThat(r[2]).isEqualTo(0);
      assertThat(r[3]).isEqualTo(1);
      assertThat(r[4]).isEqualTo(1);
      assertThat(r[5]).isEqualTo(2);
      assertThat(r[6]).isEqualTo(2);
      assertThat(r[7]).isEqualTo(3);
      assertThat(r[8]).isEqualTo(3);
      assertThat(r[9]).isEqualTo(4);
      assertThat(r[10]).isEqualTo(4);
      assertThat(r[11]).isEqualTo(5);
    }
  }

  // ---------------------------------------------------------------------------
  // Quantifiers
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Quantifiers")
  class Quantifiers {
    @Test
    void starGreedy() {
      int[] r = search("[a-z]*", "hello123");
      assertThat(r).isNotNull();
      assertThat(r[1]).isEqualTo(5); // greedy: consume all letters
    }

    @Test
    void starEmpty() {
      int[] r = search("[a-z]*", "123");
      assertThat(r).isNotNull();
      assertThat(r[1]).isEqualTo(0); // zero-length match
    }

    @Test
    void plusGreedy() {
      int[] r = search("[a-z]+", "hello123");
      assertThat(r).isNotNull();
      assertThat(r[1]).isEqualTo(5);
    }

    @Test
    void plusNoMatch() {
      int[] r = search("[a-z]+", "123");
      assertThat(r).isNull();
    }

    @Test
    void questionPresent() {
      int[] r = search("ab?c", "abc");
      assertThat(r).isNotNull();
      assertThat(r[1]).isEqualTo(3);
    }

    @Test
    void questionAbsent() {
      int[] r = search("ab?c", "ac");
      assertThat(r).isNotNull();
      assertThat(r[1]).isEqualTo(2);
    }
  }

  // ---------------------------------------------------------------------------
  // Consistency with NFA
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("OnePass vs NFA consistency")
  class Consistency {
    @Test
    void literals() {
      assertConsistentWithNfa("abc", "abc", false);
      assertConsistentWithNfa("abc", "abcdef", false);
      assertConsistentWithNfa("abc", "xyz", false);
      assertConsistentWithNfa("abc", "abc", true);
      assertConsistentWithNfa("abc", "abcd", true);
    }

    @Test
    void captureGroups() {
      assertConsistentWithNfa("(abc)", "abc", false);
      assertConsistentWithNfa("(a)(b)", "ab", false);
      assertConsistentWithNfa("(a)(b)(c)", "abc", true);
      assertConsistentWithNfa("(\\d+)-(\\d+)", "12-34", true);
    }

    @Test
    void quantifiers() {
      assertConsistentWithNfa("[a-z]+", "hello", false);
      assertConsistentWithNfa("[a-z]*", "hello", false);
      assertConsistentWithNfa("[a-z]*", "", false);
      assertConsistentWithNfa("a?b", "ab", false);
      assertConsistentWithNfa("a?b", "b", false);
    }

    @Test
    void alternation() {
      assertConsistentWithNfa("x(y|z)", "xy", false);
      assertConsistentWithNfa("x(y|z)", "xz", false);
      assertConsistentWithNfa("x(y|z)", "xa", false);
    }

    @Test
    void anchorsAndAssertions() {
      assertConsistentWithNfa("^abc", "abc", false);
      assertConsistentWithNfa("abc$", "abc", true);
      assertConsistentWithNfa("\\bfoo", "foo bar", false);
    }

    @Test
    void complex() {
      assertConsistentWithNfa("(\\w+)@(\\w+)", "user@host", false);
      assertConsistentWithNfa("[a-z]+-[0-9]+", "abc-123", false);
      assertConsistentWithNfa("(a(b(c)))", "abc", false);
    }

    @Test
    void unicode() {
      assertConsistentWithNfa("caf\u00e9", "caf\u00e9", false);
      assertConsistentWithNfa(".", "\uD83D\uDE00", false);
    }

    @Test
    void emptyPattern() {
      assertConsistentWithNfa("", "", false);
      assertConsistentWithNfa("", "abc", false);
    }
  }
}
