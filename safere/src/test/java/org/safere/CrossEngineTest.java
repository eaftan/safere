// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Cross-engine test that runs the same regex patterns and input strings through both SafeRE
 * ({@code org.safere.Pattern}/{@code Matcher}) and {@code java.util.regex.Pattern}/{@code
 * Matcher}, comparing results.
 *
 * <p>Each test case specifies a {@link Divergence} describing whether the two engines are expected
 * to agree, whether SafeRE rejects the pattern, or whether results may legitimately differ.
 */
@DisplayName("Cross-engine: SafeRE vs java.util.regex")
class CrossEngineTest {

  /** Describes expected divergence between SafeRE and java.util.regex for a test case. */
  enum Divergence {
    /** Both engines should produce identical results. */
    NONE,
    /** SafeRE rejects this pattern (backreferences, lookahead, etc.). */
    SAFERE_REJECTS
  }

  /** A single cross-engine test case. */
  record TestCase(String pattern, String input, Divergence divergence) {
    TestCase(String pattern, String input) {
      this(pattern, input, Divergence.NONE);
    }

    @Override
    public String toString() {
      return "/"
          + pattern.replace("\n", "\\n")
          + "/ on \""
          + input.replace("\n", "\\n")
          + "\" ("
          + divergence
          + ")";
    }
  }

  /** A single captured match from a {@code find()} call. */
  private record MatchResult(
      String group, int start, int end, int groupCount, List<String> groups) {}

  // ---------------------------------------------------------------------------
  // Test corpus
  // ---------------------------------------------------------------------------

  static Stream<TestCase> testCases() {
    return Stream.of(
        // ===== Literals =====
        new TestCase("abc", "abc"),
        new TestCase("abc", "xabcy"),
        new TestCase("abc", "def"),
        new TestCase("", "abc"),
        new TestCase("", ""),
        new TestCase("hello world", "hello world"),
        new TestCase("hello world", "say hello world now"),
        new TestCase("aaa", "aaaa"),

        // ===== Character classes =====
        new TestCase("[abc]", "a"),
        new TestCase("[abc]", "b"),
        new TestCase("[abc]", "d"),
        new TestCase("[a-z]", "m"),
        new TestCase("[a-z]", "M"),
        new TestCase("[^abc]", "d"),
        new TestCase("[^abc]", "a"),
        new TestCase("[a-zA-Z0-9]", "Z"),
        new TestCase("[a-zA-Z0-9]", "!"),
        new TestCase("[^\\s]", "a"),
        new TestCase("[^\\s]", " "),

        // ===== Shorthand character classes =====
        new TestCase("\\d", "5"),
        new TestCase("\\d", "a"),
        new TestCase("\\w", "a"),
        new TestCase("\\w", "!"),
        new TestCase("\\s", " "),
        new TestCase("\\s", "a"),
        new TestCase("\\D", "a"),
        new TestCase("\\D", "5"),
        new TestCase("\\W", " "),
        new TestCase("\\W", "a"),
        new TestCase("\\S", "a"),
        new TestCase("\\S", " "),
        new TestCase("\\d+", "abc123def456"),
        new TestCase("\\w+", "hello world"),

        // ===== Quantifiers =====
        new TestCase("a*", ""),
        new TestCase("a*", "aaa"),
        new TestCase("a*", "bbb"),
        new TestCase("a+", "aaa"),
        new TestCase("a+", ""),
        new TestCase("a?", "a"),
        new TestCase("a?", ""),
        new TestCase("a?", "b"),
        new TestCase("a{3}", "aaa"),
        new TestCase("a{3}", "aa"),
        new TestCase("a{3}", "aaaa"),
        new TestCase("a{2,4}", "a"),
        new TestCase("a{2,4}", "aa"),
        new TestCase("a{2,4}", "aaa"),
        new TestCase("a{2,4}", "aaaa"),
        new TestCase("a{2,4}", "aaaaa"),
        new TestCase("a{0,}", "aaa"),
        new TestCase("a{1,}", "aaa"),

        // ===== Non-greedy quantifiers =====
        new TestCase("a*?", "aaa"),
        new TestCase("a+?", "aaa"),
        new TestCase("a??", "a"),
        new TestCase("a{2,4}?", "aaaa"),

        // ===== Dot =====
        new TestCase(".", "a"),
        new TestCase(".", "\n"),
        new TestCase(".+", "hello"),
        new TestCase("a.b", "axb"),
        new TestCase("a.b", "a\nb"),
        new TestCase("a.b", "ab"),
        new TestCase("...", "ab"),
        new TestCase("...", "abc"),
        new TestCase("...", "abcd"),

        // ===== Anchors =====
        new TestCase("^abc", "abc"),
        new TestCase("^abc", "xabc"),
        new TestCase("abc$", "abc"),
        new TestCase("abc$", "abcx"),
        new TestCase("^abc$", "abc"),
        new TestCase("^abc$", "abcx"),
        new TestCase("^abc$", "xabc"),
        new TestCase("^$", ""),
        new TestCase("^$", "a"),
        new TestCase("^", ""),
        new TestCase("^", "abc"),
        new TestCase("$", ""),
        new TestCase("$", "abc"),

        // ===== Alternation =====
        new TestCase("cat|dog", "cat"),
        new TestCase("cat|dog", "dog"),
        new TestCase("cat|dog", "bird"),
        new TestCase("cat|dog", "my cat is nice"),
        new TestCase("ab|a", "ab"),
        new TestCase("a|b|c", "b"),
        new TestCase("a|b|c", "d"),
        new TestCase("abc|def|ghi", "def"),
        new TestCase("a|ab", "ab"),

        // ===== Groups and captures =====
        new TestCase("(a)(b)(c)", "abc"),
        new TestCase("(a+)(b+)", "aaabbb"),
        new TestCase("(a|(b))", "a"),
        new TestCase("(a|(b))", "b"),
        new TestCase("(?:abc)", "abc"),
        new TestCase("(?:abc)", "def"),
        new TestCase("((a)(b))", "ab"),
        new TestCase("(a(b(c)))", "abc"),
        new TestCase("(\\w+)@(\\w+)", "user@host"),
        new TestCase("(a)?b", "b"),
        new TestCase("(a)?b", "ab"),

        // ===== Word boundaries =====
        new TestCase("\\bfoo\\b", "foo bar"),
        new TestCase("\\bfoo\\b", "foobar"),
        new TestCase("\\bfoo\\b", "barfoo"),
        new TestCase("\\bbar\\b", "foo bar baz"),
        new TestCase("\\bbar\\b", "foobar"),
        new TestCase("\\b\\w+\\b", "hello"),

        // ===== Repetition with groups =====
        new TestCase("(ab)+", "ababab"),
        new TestCase("(a)+", "aaa"),
        new TestCase("(a)+(b)+", "aaabbb"),
        new TestCase("([ab])+", "abab"),

        // ===== Complex patterns =====
        new TestCase(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", "user@example.com"),
        new TestCase(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", "no email here"),
        new TestCase(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
            "contact a@b.co and x@y.org"),
        new TestCase(
            "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "192.168.1.1"),
        new TestCase(
            "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "not an ip"),
        new TestCase("https?://[^\\s]+", "visit https://example.com today"),
        new TestCase("https?://[^\\s]+", "http://foo.bar/baz?q=1"),
        new TestCase("https?://[^\\s]+", "no url here"),
        new TestCase("(\\d{4})-(\\d{2})-(\\d{2})", "2025-03-17"),
        new TestCase("(\\d{4})-(\\d{2})-(\\d{2})", "date is 2025-03-17 ok"),
        new TestCase(
            "(\\d{4})-(\\d{2})-(\\d{2})", "2025-03-17 and 2024-12-25"),

        // ===== Unicode =====
        new TestCase(".", "\u00e9"),
        new TestCase(".", "\ud83d\ude00"),
        new TestCase("[\u00e0-\u00ff]", "\u00e9"),
        new TestCase("[\u00e0-\u00ff]", "a"),

        // ===== Character escapes =====
        // Octal
        new TestCase("\\012", "\n"),
        new TestCase("\\012", "a"),
        new TestCase("\\0", "\0"),
        new TestCase("\\077", "?"), // 077 octal = 63 = '?'
        // \e (escape char)
        new TestCase("\\e", "\u001B"),
        new TestCase("\\e", "a"),
        // \cx (control chars)
        new TestCase("\\cA", "\u0001"),
        new TestCase("\\cA", "a"),
        new TestCase("\\cZ", "\u001A"),
        new TestCase("\\c@", "\u0000"),
        // \N{name} (named Unicode character)
        new TestCase("\\N{LATIN SMALL LETTER A}", "a"),
        new TestCase("\\N{LATIN SMALL LETTER A}", "b"),
        new TestCase("\\N{WHITE SMILING FACE}", "\u263A"),

        // ===== Escaped metacharacters =====
        new TestCase("\\.", "a.b"),
        new TestCase("\\.", "abc"),
        new TestCase("\\*", "a*b"),
        new TestCase("\\+", "a+b"),
        new TestCase("\\?", "a?b"),
        new TestCase("\\(", "("),
        new TestCase("\\)", ")"),
        new TestCase("\\[", "["),
        new TestCase("\\{", "{"),
        new TestCase("\\\\", "a\\b"),

        // ===== Backreferences (SAFERE_REJECTS) =====
        new TestCase("(a)\\1", "aa", Divergence.SAFERE_REJECTS),
        new TestCase("(\\w+) \\1", "hello hello", Divergence.SAFERE_REJECTS),
        new TestCase("([abc])\\1", "aa", Divergence.SAFERE_REJECTS),
        new TestCase("(?<name>a)\\k<name>", "aa", Divergence.SAFERE_REJECTS),
        new TestCase("(?<word>\\w+) \\k<word>", "hello hello", Divergence.SAFERE_REJECTS),

        // ===== Lookahead (SAFERE_REJECTS) =====
        new TestCase("(?=a)a", "a", Divergence.SAFERE_REJECTS),
        new TestCase("(?!b)a", "a", Divergence.SAFERE_REJECTS),
        new TestCase("a(?=b)", "ab", Divergence.SAFERE_REJECTS),
        new TestCase("a(?!b)", "ac", Divergence.SAFERE_REJECTS),

        // ===== Lookbehind (SAFERE_REJECTS) =====
        new TestCase("(?<=a)b", "ab", Divergence.SAFERE_REJECTS),
        new TestCase("(?<!a)b", "cb", Divergence.SAFERE_REJECTS),

        // ===== Possessive quantifiers (SAFERE_REJECTS) =====
        new TestCase("a++", "aaa", Divergence.SAFERE_REJECTS),
        new TestCase("a*+", "aaa", Divergence.SAFERE_REJECTS),
        new TestCase("a?+", "a", Divergence.SAFERE_REJECTS),

        // ===== Atomic groups (SAFERE_REJECTS) =====
        new TestCase("(?>a+)", "aaa", Divergence.SAFERE_REJECTS),

        // ===== Edge cases =====
        new TestCase("(a*)", ""),
        new TestCase("a*b", "b"),
        new TestCase("a*b", "aaab"),
        new TestCase(".{0}", "abc"),
        new TestCase("a{100}", "a".repeat(100)),
        new TestCase("a{100}", "a".repeat(99)),
        new TestCase("a?a?a?aaa", "aaa"),

        // ===== Multiple find() matches =====
        new TestCase("\\d+", "a1b22c333"),
        new TestCase("[aeiou]", "hello world"),
        new TestCase("\\b\\w+\\b", "hello world foo"),
        new TestCase("[0-9]+", "no digits here"),
        new TestCase("a", "banana"),
        new TestCase("an", "banana"),
        new TestCase("[a-z]+", "Hello World 123"),

        // ===== Replacement-oriented patterns =====
        new TestCase("\\s+", "hello   world   foo"),
        new TestCase(",", "a,b,c,d"),
        new TestCase("\\d", "a1b2c3"),

        // ===== Split-oriented patterns =====
        new TestCase(":", "a:b:c"),
        new TestCase("[,;]", "a,b;c,d"),
        new TestCase("\\s+", "one two  three"),

        // ===== Nested quantifiers =====
        new TestCase("(a+)+", "aaa"),
        new TestCase("((a|b)+)", "abab"),

        // ===== Character class edge cases =====
        new TestCase("[]]", "]"),
        new TestCase("[-a]", "-"),
        new TestCase("[-a]", "a"),
        new TestCase("[a-]", "-"),
        new TestCase("[a-]", "a"),
        new TestCase("[\\-]", "-"),

        // ===== Miscellaneous =====
        new TestCase("(?i)abc", "ABC"),
        new TestCase("(?i)abc", "AbC"),
        new TestCase("(?i)abc", "def"),
        new TestCase("(?i)[a-z]+", "HeLLo"),
        new TestCase("a{0}", "a"),
        new TestCase("a{0}", ""),
        new TestCase("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)", "abcdefghij"),
        new TestCase("x*", "y"));
  }

  // ---------------------------------------------------------------------------
  // Main parameterized test
  // ---------------------------------------------------------------------------

  @ParameterizedTest(name = "{0}")
  @MethodSource("testCases")
  @DisplayName("Cross-engine comparison")
  void crossEngineComparison(TestCase tc) {
    // --- Phase 0: Compile with both engines ---
    Pattern safeRePattern;
    java.util.regex.Pattern jdkPattern;

    // Compile with java.util.regex first (it supports everything).
    try {
      jdkPattern = java.util.regex.Pattern.compile(tc.pattern());
    } catch (PatternSyntaxException e) {
      // If java.util.regex can't compile it, neither engine can; skip.
      return;
    }

    // Compile with SafeRE.
    if (tc.divergence() == Divergence.SAFERE_REJECTS) {
      try {
        Pattern.compile(tc.pattern());
        fail(
            "Expected SafeRE to reject pattern /"
                + tc.pattern()
                + "/ with PatternSyntaxException");
      } catch (PatternSyntaxException expected) {
        // Good — SafeRE correctly rejects this pattern.
      }
      return;
    }

    try {
      safeRePattern = Pattern.compile(tc.pattern());
    } catch (PatternSyntaxException e) {
      fail(
          "SafeRE unexpectedly rejected pattern /"
              + tc.pattern()
              + "/: "
              + e.getMessage());
      return; // unreachable, but keeps the compiler happy
    }

    boolean expectEqual = tc.divergence() == Divergence.NONE;

    // --- Phase 1: matches() ---
    compareMatches(safeRePattern, jdkPattern, tc, expectEqual);

    // --- Phase 2: find() loop ---
    compareFind(safeRePattern, jdkPattern, tc, expectEqual);

    // --- Phase 3: replaceAll() ---
    compareReplaceAll(safeRePattern, jdkPattern, tc, expectEqual);

    // --- Phase 4: split() ---
    compareSplit(safeRePattern, jdkPattern, tc, expectEqual);
  }

  // ---------------------------------------------------------------------------
  // Comparison helpers
  // ---------------------------------------------------------------------------

  private void compareMatches(
      Pattern safeRePattern,
      java.util.regex.Pattern jdkPattern,
      TestCase tc,
      boolean expectEqual) {
    boolean safeReResult = safeRePattern.matcher(tc.input()).matches();
    boolean jdkResult = jdkPattern.matcher(tc.input()).matches();

    if (expectEqual) {
      assertThat(safeReResult)
          .as(
              "matches() for /%s/ on \"%s\": SafeRE=%s, JDK=%s",
              tc.pattern(), tc.input(), safeReResult, jdkResult)
          .isEqualTo(jdkResult);
    }
    // For SAFERE_REJECTS, we just verify SafeRE didn't crash (it already ran).
  }

  private void compareFind(
      Pattern safeRePattern,
      java.util.regex.Pattern jdkPattern,
      TestCase tc,
      boolean expectEqual) {
    List<MatchResult> safeReMatches = collectFinds(safeRePattern.matcher(tc.input()));
    List<MatchResult> jdkMatches = collectFindsJdk(jdkPattern.matcher(tc.input()));

    if (!expectEqual) {
      return;
    }

    assertThat(safeReMatches.size())
        .as("find() match count for /%s/ on \"%s\"", tc.pattern(), tc.input())
        .isEqualTo(jdkMatches.size());

    for (int i = 0; i < safeReMatches.size(); i++) {
      MatchResult sr = safeReMatches.get(i);
      MatchResult jdk = jdkMatches.get(i);

      assertThat(sr.group())
          .as(
              "find() match #%d group() for /%s/ on \"%s\"",
              i, tc.pattern(), tc.input())
          .isEqualTo(jdk.group());
      assertThat(sr.start())
          .as(
              "find() match #%d start() for /%s/ on \"%s\"",
              i, tc.pattern(), tc.input())
          .isEqualTo(jdk.start());
      assertThat(sr.end())
          .as(
              "find() match #%d end() for /%s/ on \"%s\"",
              i, tc.pattern(), tc.input())
          .isEqualTo(jdk.end());

      // Compare capturing groups.
      assertThat(sr.groupCount())
          .as(
              "find() match #%d groupCount() for /%s/ on \"%s\"",
              i, tc.pattern(), tc.input())
          .isEqualTo(jdk.groupCount());
      assertThat(sr.groups())
          .as(
              "find() match #%d groups for /%s/ on \"%s\"",
              i, tc.pattern(), tc.input())
          .isEqualTo(jdk.groups());
    }
  }

  private void compareReplaceAll(
      Pattern safeRePattern,
      java.util.regex.Pattern jdkPattern,
      TestCase tc,
      boolean expectEqual) {
    String replacement = "X";
    String safeReResult = safeRePattern.matcher(tc.input()).replaceAll(replacement);
    String jdkResult = jdkPattern.matcher(tc.input()).replaceAll(replacement);

    if (expectEqual) {
      assertThat(safeReResult)
          .as("replaceAll(\"X\") for /%s/ on \"%s\"", tc.pattern(), tc.input())
          .isEqualTo(jdkResult);
    }
  }

  private void compareSplit(
      Pattern safeRePattern,
      java.util.regex.Pattern jdkPattern,
      TestCase tc,
      boolean expectEqual) {
    String[] safeReParts = safeRePattern.split(tc.input());
    String[] jdkParts = jdkPattern.split(tc.input());

    if (expectEqual) {
      assertThat(safeReParts)
          .as("split() for /%s/ on \"%s\"", tc.pattern(), tc.input())
          .isEqualTo(jdkParts);
    }
  }

  // ---------------------------------------------------------------------------
  // Match-collection helpers
  // ---------------------------------------------------------------------------

  /** Collects all {@code find()} results from a SafeRE {@link Matcher}. */
  private List<MatchResult> collectFinds(Matcher matcher) {
    List<MatchResult> results = new ArrayList<>();
    while (matcher.find()) {
      int gc = matcher.groupCount();
      List<String> groups = new ArrayList<>();
      for (int g = 0; g <= gc; g++) {
        groups.add(matcher.group(g));
      }
      results.add(
          new MatchResult(matcher.group(), matcher.start(), matcher.end(), gc, groups));
    }
    return results;
  }

  /** Collects all {@code find()} results from a JDK {@link java.util.regex.Matcher}. */
  private List<MatchResult> collectFindsJdk(java.util.regex.Matcher matcher) {
    List<MatchResult> results = new ArrayList<>();
    while (matcher.find()) {
      int gc = matcher.groupCount();
      List<String> groups = new ArrayList<>();
      for (int g = 0; g <= gc; g++) {
        groups.add(matcher.group(g));
      }
      results.add(
          new MatchResult(matcher.group(), matcher.start(), matcher.end(), gc, groups));
    }
    return results;
  }
}
