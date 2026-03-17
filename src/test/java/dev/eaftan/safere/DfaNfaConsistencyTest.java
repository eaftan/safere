// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Systematic consistency tests verifying that the DFA and NFA engines agree on match results for
 * the same pattern and input.
 *
 * <p>The DFA only reports match/no-match and match end position (no capture groups). The DFA and
 * NFA have different semantics for "first" and "longest" match:
 *
 * <ul>
 *   <li>DFA first-match: earliest end position (considering all start positions via .*? prefix)
 *   <li>NFA FIRST_MATCH: leftmost start, greedy end (Perl-like)
 *   <li>DFA unanchored longest: latest end position (from any start)
 *   <li>NFA LONGEST_MATCH: leftmost start, longest end (POSIX-like)
 * </ul>
 *
 * <p>Because of these semantic differences, end positions only reliably agree in <b>anchored +
 * longest</b> mode (both start at position 0, both maximize match length). All modes should agree
 * on match/no-match.
 */
@DisplayName("DFA vs NFA consistency")
class DfaNfaConsistencyTest {

  private static final int FLAGS =
      ParseFlags.PERL_X | ParseFlags.PERL_CLASSES | ParseFlags.PERL_B
          | ParseFlags.UNICODE_GROUPS;

  /** A single test case: pattern + input text. */
  record TestCase(String pattern, String input) {
    @Override
    public String toString() {
      return "/" + pattern.replace("\n", "\\n") + "/ on \""
          + input.replace("\n", "\\n") + "\"";
    }
  }

  // ---------------------------------------------------------------------------
  // Test corpus
  // ---------------------------------------------------------------------------

  static Stream<TestCase> testCases() {
    List<TestCase> cases = new ArrayList<>();

    // -- Literals --
    cases.add(new TestCase("abc", "abc"));
    cases.add(new TestCase("abc", "xabcy"));
    cases.add(new TestCase("abc", "def"));
    cases.add(new TestCase("abc", ""));
    cases.add(new TestCase("abc", "ab"));
    cases.add(new TestCase("abc", "abcabc"));
    cases.add(new TestCase("hello world", "hello world"));
    cases.add(new TestCase("hello world", "say hello world!"));
    cases.add(new TestCase("hello world", "hello"));

    // -- Single characters --
    cases.add(new TestCase("a", "a"));
    cases.add(new TestCase("a", "b"));
    cases.add(new TestCase("a", "ba"));
    cases.add(new TestCase("a", ""));

    // -- Dot --
    cases.add(new TestCase(".", "a"));
    cases.add(new TestCase(".", ""));
    cases.add(new TestCase("..", "ab"));
    cases.add(new TestCase("..", "a"));
    cases.add(new TestCase("a.c", "abc"));
    cases.add(new TestCase("a.c", "aXc"));
    cases.add(new TestCase("a.c", "ac"));

    // -- Character classes --
    cases.add(new TestCase("[abc]", "a"));
    cases.add(new TestCase("[abc]", "b"));
    cases.add(new TestCase("[abc]", "d"));
    cases.add(new TestCase("[abc]", ""));
    cases.add(new TestCase("[a-z]", "m"));
    cases.add(new TestCase("[a-z]", "M"));
    cases.add(new TestCase("[a-z]+", "hello"));
    cases.add(new TestCase("[a-z]+", "HELLO"));
    cases.add(new TestCase("[^a-z]", "A"));
    cases.add(new TestCase("[^a-z]", "a"));
    cases.add(new TestCase("[a-zA-Z0-9_]", "x"));
    cases.add(new TestCase("[a-zA-Z0-9_]", " "));

    // -- Perl character classes --
    cases.add(new TestCase("\\d", "5"));
    cases.add(new TestCase("\\d", "a"));
    cases.add(new TestCase("\\d+", "abc123def"));
    cases.add(new TestCase("\\D+", "abc123def"));
    cases.add(new TestCase("\\w+", "hello world"));
    cases.add(new TestCase("\\W+", "hello world"));
    cases.add(new TestCase("\\s", " "));
    cases.add(new TestCase("\\s", "a"));
    cases.add(new TestCase("\\s+", "a b\tc\nd"));

    // -- Quantifiers: star --
    cases.add(new TestCase("a*", ""));
    cases.add(new TestCase("a*", "a"));
    cases.add(new TestCase("a*", "aaa"));
    cases.add(new TestCase("a*", "b"));
    cases.add(new TestCase("a*", "baaab"));
    cases.add(new TestCase("xa*y", "xy"));
    cases.add(new TestCase("xa*y", "xaaay"));
    cases.add(new TestCase("xa*y", "xaaa"));

    // -- Quantifiers: plus --
    cases.add(new TestCase("a+", ""));
    cases.add(new TestCase("a+", "a"));
    cases.add(new TestCase("a+", "aaa"));
    cases.add(new TestCase("a+", "b"));
    cases.add(new TestCase("a+", "baaab"));
    cases.add(new TestCase("xa+y", "xy"));
    cases.add(new TestCase("xa+y", "xay"));
    cases.add(new TestCase("xa+y", "xaaay"));

    // -- Quantifiers: question --
    cases.add(new TestCase("a?", ""));
    cases.add(new TestCase("a?", "a"));
    cases.add(new TestCase("a?", "b"));
    cases.add(new TestCase("xa?y", "xy"));
    cases.add(new TestCase("xa?y", "xay"));
    cases.add(new TestCase("xa?y", "xaay"));

    // -- Quantifiers: repetition --
    cases.add(new TestCase("a{3}", "aaa"));
    cases.add(new TestCase("a{3}", "aa"));
    cases.add(new TestCase("a{3}", "aaaa"));
    cases.add(new TestCase("a{2,4}", "a"));
    cases.add(new TestCase("a{2,4}", "aa"));
    cases.add(new TestCase("a{2,4}", "aaa"));
    cases.add(new TestCase("a{2,4}", "aaaa"));
    cases.add(new TestCase("a{2,4}", "aaaaa"));
    cases.add(new TestCase("a{2,}", "a"));
    cases.add(new TestCase("a{2,}", "aa"));
    cases.add(new TestCase("a{2,}", "aaaaaaa"));

    // -- Non-greedy quantifiers --
    cases.add(new TestCase("a*?", "aaa"));
    cases.add(new TestCase("a+?", "aaa"));
    cases.add(new TestCase("a??", "a"));
    cases.add(new TestCase("a{2,4}?", "aaaa"));

    // -- Alternation --
    cases.add(new TestCase("cat|dog", "cat"));
    cases.add(new TestCase("cat|dog", "dog"));
    cases.add(new TestCase("cat|dog", "bird"));
    cases.add(new TestCase("cat|dog", "catdog"));
    cases.add(new TestCase("a|b|c", "a"));
    cases.add(new TestCase("a|b|c", "b"));
    cases.add(new TestCase("a|b|c", "c"));
    cases.add(new TestCase("a|b|c", "d"));
    cases.add(new TestCase("ab|cd|ef", "cd"));
    cases.add(new TestCase("ab|cd|ef", "abcdef"));

    // -- Groups --
    cases.add(new TestCase("(abc)", "abc"));
    cases.add(new TestCase("(abc)", "def"));
    cases.add(new TestCase("(a)(b)(c)", "abc"));
    cases.add(new TestCase("(a|b)(c|d)", "ac"));
    cases.add(new TestCase("(a|b)(c|d)", "bd"));
    cases.add(new TestCase("(a|b)(c|d)", "ae"));
    cases.add(new TestCase("(ab)+", "ababab"));
    cases.add(new TestCase("(ab)+", "abcab"));

    // -- Nested groups --
    cases.add(new TestCase("((a)(b))", "ab"));
    cases.add(new TestCase("(a(b(c)))", "abc"));
    cases.add(new TestCase("((a|b)+)", "abba"));

    // -- Anchors --
    cases.add(new TestCase("^abc", "abc"));
    cases.add(new TestCase("^abc", "abcdef"));
    cases.add(new TestCase("^abc", "xabc"));
    cases.add(new TestCase("abc$", "abc"));
    cases.add(new TestCase("abc$", "xyzabc"));
    cases.add(new TestCase("abc$", "abcx"));
    cases.add(new TestCase("^abc$", "abc"));
    cases.add(new TestCase("^abc$", "abcx"));
    cases.add(new TestCase("^abc$", "xabc"));
    cases.add(new TestCase("^$", ""));
    cases.add(new TestCase("^$", "a"));
    cases.add(new TestCase("^", "anything"));
    cases.add(new TestCase("$", "anything"));

    // -- Word boundaries --
    cases.add(new TestCase("\\bfoo\\b", "foo"));
    cases.add(new TestCase("\\bfoo\\b", "foo bar"));
    cases.add(new TestCase("\\bfoo\\b", "bar foo baz"));
    cases.add(new TestCase("\\bfoo\\b", "foobar"));
    cases.add(new TestCase("\\bfoo\\b", "barfoo"));
    cases.add(new TestCase("\\bbar\\b", "foo bar baz"));
    cases.add(new TestCase("\\bbar\\b", "foobar"));
    cases.add(new TestCase("\\Bfoo\\B", "xfoox"));
    cases.add(new TestCase("\\Bfoo\\B", "foo"));

    // -- Escaped metacharacters --
    cases.add(new TestCase("a\\.b", "a.b"));
    cases.add(new TestCase("a\\.b", "axb"));
    cases.add(new TestCase("a\\*b", "a*b"));
    cases.add(new TestCase("a\\+b", "a+b"));
    cases.add(new TestCase("a\\?b", "a?b"));
    cases.add(new TestCase("\\(a\\)", "(a)"));
    cases.add(new TestCase("\\[a\\]", "[a]"));

    // -- Unicode --
    cases.add(new TestCase("café", "café"));
    cases.add(new TestCase("café", "cafe"));
    cases.add(new TestCase(".", "\uD83D\uDE00")); // U+1F600 emoji (surrogate pair)
    cases.add(new TestCase("..", "\uD83D\uDE00\uD83D\uDE01")); // two emojis
    cases.add(new TestCase("\\w+", "naïve"));
    cases.add(new TestCase("[à-ÿ]+", "naïve"));
    cases.add(new TestCase("\\p{L}+", "Ωmega"));

    // -- Empty pattern / empty text --
    cases.add(new TestCase("", ""));
    cases.add(new TestCase("", "abc"));
    cases.add(new TestCase("a*", ""));
    cases.add(new TestCase("()", ""));
    cases.add(new TestCase("()*", ""));

    // -- Complex patterns --
    cases.add(new TestCase("(a|b)*c", "aababc"));
    cases.add(new TestCase("(a|b)*c", "aabab"));
    cases.add(new TestCase("[a-z]+@[a-z]+\\.[a-z]+", "user@host.com"));
    cases.add(new TestCase("[a-z]+@[a-z]+\\.[a-z]+", "user@host"));
    cases.add(new TestCase("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "192.168.1.1"));
    cases.add(new TestCase("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "999.999.999.999"));
    cases.add(new TestCase("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "1.2.3"));
    cases.add(new TestCase("(https?://)?[\\w.-]+\\.[a-z]{2,}", "https://example.com"));
    cases.add(new TestCase("(https?://)?[\\w.-]+\\.[a-z]{2,}", "example.com"));
    cases.add(new TestCase("(https?://)?[\\w.-]+\\.[a-z]{2,}", "not a url"));

    // -- Pathological for backtracking (should be fine for linear-time engines) --
    cases.add(new TestCase("a?a?a?aaa", "aaa"));
    cases.add(new TestCase("a?a?a?a?a?aaaaa", "aaaaa"));
    cases.add(new TestCase("(a+)+b", "aaab"));
    cases.add(new TestCase("(a+)+b", "aaa"));
    cases.add(new TestCase("([a-z]+)*x", "abcx"));
    cases.add(new TestCase("([a-z]+)*x", "abc"));

    // -- Multiline-like patterns --
    cases.add(new TestCase("a.*b", "aXXXb"));
    cases.add(new TestCase("a.*b", "a\nb"));
    cases.add(new TestCase("a.+b", "axb"));
    cases.add(new TestCase("a.+b", "ab"));

    // -- Nested quantifiers --
    cases.add(new TestCase("(a{2}){3}", "aaaaaa"));
    cases.add(new TestCase("(a{2}){3}", "aaaaa"));
    cases.add(new TestCase("((ab){2}(cd){2})", "ababcdcd"));
    cases.add(new TestCase("((ab){2}(cd){2})", "ababcd"));

    // -- Long text --
    cases.add(new TestCase("needle", "hay".repeat(100) + "needle" + "hay".repeat(100)));
    cases.add(new TestCase("needle", "hay".repeat(1000)));
    cases.add(new TestCase("[a-z]+", "a".repeat(1000)));
    cases.add(new TestCase("a{100}", "a".repeat(100)));
    cases.add(new TestCase("a{100}", "a".repeat(99)));

    // -- Case sensitivity --
    cases.add(new TestCase("(?i)abc", "ABC"));
    cases.add(new TestCase("(?i)abc", "abc"));
    cases.add(new TestCase("(?i)abc", "AbC"));
    cases.add(new TestCase("(?i)[a-z]+", "HeLLo"));

    return cases.stream();
  }

  // ---------------------------------------------------------------------------
  // Parameterized tests — one per mode combination
  // ---------------------------------------------------------------------------

  @ParameterizedTest(name = "unanchored first: {0}")
  @MethodSource("testCases")
  void unanchoredFirstMatch(TestCase tc) {
    assertMatchAgreement(tc, false, false);
  }

  @ParameterizedTest(name = "unanchored longest: {0}")
  @MethodSource("testCases")
  void unanchoredLongestMatch(TestCase tc) {
    assertMatchAgreement(tc, false, true);
  }

  @ParameterizedTest(name = "anchored first: {0}")
  @MethodSource("testCases")
  void anchoredFirstMatch(TestCase tc) {
    assertMatchAgreement(tc, true, false);
  }

  @ParameterizedTest(name = "anchored longest: {0}")
  @MethodSource("testCases")
  void anchoredLongestMatch(TestCase tc) {
    assertMatchAndEndPosition(tc, true, true);
  }

  // ---------------------------------------------------------------------------
  // Assertion helper
  // ---------------------------------------------------------------------------

  /**
   * Asserts that DFA and NFA agree on match/no-match only (not end position). Used for modes where
   * end-position semantics legitimately differ between the two engines.
   */
  private static void assertMatchAgreement(TestCase tc, boolean anchored, boolean longest) {
    Regexp re = Parser.parse(tc.pattern(), FLAGS);
    Prog prog = Compiler.compile(re);

    // Run DFA.
    DFA.SearchResult dfaResult = DFA.search(prog, tc.input(), anchored, longest);

    // Run NFA with matching settings.
    NFA.Anchor nfaAnchor = anchored ? NFA.Anchor.ANCHORED : NFA.Anchor.UNANCHORED;
    NFA.MatchKind nfaKind = longest ? NFA.MatchKind.LONGEST_MATCH : NFA.MatchKind.FIRST_MATCH;
    int[] nfaResult = NFA.search(prog, tc.input(), nfaAnchor, nfaKind, 1);

    if (dfaResult == null) {
      // DFA bailed out due to budget -- skip consistency check.
      return;
    }

    boolean nfaMatched = (nfaResult != null);
    String mode = (anchored ? "anchored" : "unanchored")
        + " " + (longest ? "longest" : "first");

    assertThat(dfaResult.matched())
        .as("match/no-match for /%s/ on \"%s\" (%s)", tc.pattern(), tc.input(), mode)
        .isEqualTo(nfaMatched);
  }

  /**
   * Asserts that DFA and NFA agree on match/no-match AND end position. Only valid for anchored +
   * longest mode where both engines have the same semantics (start at position 0, maximize).
   */
  private static void assertMatchAndEndPosition(TestCase tc, boolean anchored, boolean longest) {
    Regexp re = Parser.parse(tc.pattern(), FLAGS);
    Prog prog = Compiler.compile(re);

    DFA.SearchResult dfaResult = DFA.search(prog, tc.input(), anchored, longest);

    NFA.Anchor nfaAnchor = anchored ? NFA.Anchor.ANCHORED : NFA.Anchor.UNANCHORED;
    NFA.MatchKind nfaKind = longest ? NFA.MatchKind.LONGEST_MATCH : NFA.MatchKind.FIRST_MATCH;
    int[] nfaResult = NFA.search(prog, tc.input(), nfaAnchor, nfaKind, 1);

    if (dfaResult == null) {
      return;
    }

    boolean nfaMatched = (nfaResult != null);
    String mode = (anchored ? "anchored" : "unanchored")
        + " " + (longest ? "longest" : "first");

    assertThat(dfaResult.matched())
        .as("match/no-match for /%s/ on \"%s\" (%s)", tc.pattern(), tc.input(), mode)
        .isEqualTo(nfaMatched);

    if (dfaResult.matched() && nfaMatched) {
      int nfaEnd = nfaResult[1]; // group(0) end
      assertThat(dfaResult.pos())
          .as("match end for /%s/ on \"%s\" (%s): DFA=%d, NFA=%d",
              tc.pattern(), tc.input(), mode, dfaResult.pos(), nfaEnd)
          .isEqualTo(nfaEnd);
    }
  }
}
