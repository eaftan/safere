// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for leftmost-match semantics with alternation when different alternatives can match at
 * different starting positions in the input.
 *
 * <p>The fundamental rule: among all possible matches, return the one starting at the earliest
 * (leftmost) position, regardless of which alternative produces it. Existing tests only covered
 * alternation where alternatives compete at the <em>same</em> starting position. This test class
 * covers the missing dimension: alternatives that match at <em>different</em> positions.
 *
 * <p>See <a href="https://github.com/eaftan/safere/issues/150">issue #150</a>.
 */
@DisplayName("Alternation: leftmost position across alternatives")
class AlternationLeftmostPositionTest {

  /** A test case comparing SafeRE find() results against JDK java.util.regex. */
  record FindCase(String description, String pattern, String input) {
    @Override
    public String toString() {
      return description;
    }
  }

  // ---------------------------------------------------------------------------
  // Helper: compare SafeRE find() against JDK for a single pattern + input
  // ---------------------------------------------------------------------------

  private static void assertMatchesJdk(String pattern, String input) {
    Pattern saferePattern = Pattern.compile(pattern);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(pattern);

    Matcher safereM = saferePattern.matcher(input);
    java.util.regex.Matcher jdkM = jdkPattern.matcher(input);

    boolean jdkFound = jdkM.find();
    boolean safereFound = safereM.find();

    assertThat(safereFound)
        .as("find() for /%s/ on \"%s\"", pattern, input)
        .isEqualTo(jdkFound);

    if (jdkFound && safereFound) {
      assertThat(safereM.start())
          .as("start() for /%s/ on \"%s\": SafeRE matched '%s', JDK matched '%s'",
              pattern, input,
              input.substring(safereM.start(), safereM.end()),
              input.substring(jdkM.start(), jdkM.end()))
          .isEqualTo(jdkM.start());
      assertThat(safereM.end())
          .as("end() for /%s/ on \"%s\"", pattern, input)
          .isEqualTo(jdkM.end());
      assertThat(safereM.group())
          .as("group() for /%s/ on \"%s\"", pattern, input)
          .isEqualTo(jdkM.group());
    }
  }

  /** Compare all find() results (not just first) against JDK. */
  private static void assertAllMatchesMatchJdk(String pattern, String input) {
    Pattern saferePattern = Pattern.compile(pattern);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(pattern);

    Matcher safereM = saferePattern.matcher(input);
    java.util.regex.Matcher jdkM = jdkPattern.matcher(input);

    List<String> safereResults = new ArrayList<>();
    while (safereM.find()) {
      safereResults.add(
          String.format("[%d,%d)='%s'", safereM.start(), safereM.end(), safereM.group()));
    }
    List<String> jdkResults = new ArrayList<>();
    while (jdkM.find()) {
      jdkResults.add(
          String.format("[%d,%d)='%s'", jdkM.start(), jdkM.end(), jdkM.group()));
    }

    assertThat(safereResults)
        .as("all find() results for /%s/ on \"%s\"", pattern, input)
        .isEqualTo(jdkResults);
  }

  /** Same as {@link #assertMatchesJdk} but also compares capture groups. */
  private static void assertMatchesJdkWithGroups(String pattern, String input) {
    Pattern saferePattern = Pattern.compile(pattern);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(pattern);

    Matcher safereM = saferePattern.matcher(input);
    java.util.regex.Matcher jdkM = jdkPattern.matcher(input);

    boolean jdkFound = jdkM.find();
    boolean safereFound = safereM.find();

    assertThat(safereFound)
        .as("find() for /%s/ on \"%s\"", pattern, input)
        .isEqualTo(jdkFound);

    if (jdkFound && safereFound) {
      assertThat(safereM.start())
          .as("start() for /%s/ on \"%s\"", pattern, input)
          .isEqualTo(jdkM.start());
      assertThat(safereM.end())
          .as("end() for /%s/ on \"%s\"", pattern, input)
          .isEqualTo(jdkM.end());
      assertThat(safereM.group())
          .as("group() for /%s/ on \"%s\"", pattern, input)
          .isEqualTo(jdkM.group());

      int groupCount = Math.min(safereM.groupCount(), jdkM.groupCount());
      for (int g = 1; g <= groupCount; g++) {
        assertThat(safereM.group(g))
            .as("group(%d) for /%s/ on \"%s\"", g, pattern, input)
            .isEqualTo(jdkM.group(g));
        assertThat(safereM.start(g))
            .as("start(%d) for /%s/ on \"%s\"", g, pattern, input)
            .isEqualTo(jdkM.start(g));
        assertThat(safereM.end(g))
            .as("end(%d) for /%s/ on \"%s\"", g, pattern, input)
            .isEqualTo(jdkM.end(g));
      }
    }
  }

  // ==========================================================================
  // 1. Basic: second alternative matches at an earlier position than first
  // ==========================================================================

  @Nested
  @DisplayName("Basic: later alternative matches at earlier position")
  class BasicDifferentPosition {

    @Test
    @DisplayName("(bcd|abcde) on 'xabcdex' — second alt starts at 1, first at 2")
    void secondAltMatchesEarlier() {
      assertMatchesJdk("(bcd|abcde)", "xabcdex");
    }

    @Test
    @DisplayName("(cd|abcd) on 'xabcdy' — second alt starts at 1, first at 3")
    void longerAltMatchesEarlier() {
      assertMatchesJdk("(cd|abcd)", "xabcdy");
    }

    @Test
    @DisplayName("(def|abc) on 'abcdef' — second alt starts at 0, first at 3")
    void nonOverlappingAltsAtDifferentPositions() {
      assertMatchesJdk("(def|abc)", "abcdef");
    }

    @Test
    @DisplayName("(b|ab) on 'ab' — second alt starts at 0, first at 1")
    void singleCharVsLonger() {
      assertMatchesJdk("(b|ab)", "ab");
    }

    @Test
    @DisplayName("(bc|abc) on 'abc' — second alt starts at 0, first at 1")
    void suffixVsFull() {
      assertMatchesJdk("(bc|abc)", "abc");
    }

    @Test
    @DisplayName("(xyz|abcxyz) on '...abcxyz...' — second alt earlier")
    void longerPrefixedAlt() {
      assertMatchesJdk("(xyz|abcxyz)", "QQabcxyzQQ");
    }
  }

  // ==========================================================================
  // 2. First alternative matches at an earlier position than second
  // ==========================================================================

  @Nested
  @DisplayName("First alternative matches at earlier position")
  class FirstAltEarlier {

    @Test
    @DisplayName("(abc|def) on 'abcdef' — first alt at 0, second at 3")
    void firstAltEarlierSimple() {
      assertMatchesJdk("(abc|def)", "abcdef");
    }

    @Test
    @DisplayName("(ab|cd) on 'xabcdy' — first at 1, second at 3")
    void firstAltEarlierInMiddle() {
      assertMatchesJdk("(ab|cd)", "xabcdy");
    }
  }

  // ==========================================================================
  // 3. Real-world regressions for alternatives that start at different positions
  // ==========================================================================

  @Nested
  @DisplayName("Real-world overlapping alternatives")
  class RealWorldOverlappingAlternatives {

    @Test
    @DisplayName("Java generics pattern on 'List<List<Integer>> '")
    void javaGenericsPattern() {
      // Regression for issue #150.
      String pattern =
          "([A-Z][a-zA-Z0-9_]*<[A-Z][A-Za-z0-9,.& ]+>"
              + "|[A-Z][a-zA-Z0-9_]*<[A-Z][a-zA-Z0-9_]*<[A-Z][A-Za-z0-9,.& ]+>>)";
      String input = "List<List<Integer>> ";
      assertMatchesJdkWithGroups(pattern, input);
    }

    @Test
    @DisplayName("API version pattern on '/literature/{$api_version}/'")
    void apiVersionPattern() {
      // Regression for issue #150.
      String pattern = "(?:(/|^)\\{\\$api_version\\}(/|$)|\\{\\$api_version\\})";
      String input = "/literature/{$api_version}/";
      assertMatchesJdkWithGroups(pattern, input);
    }

    @Test
    @DisplayName("Java generics pattern — all matches")
    void javaGenericsPatternAllMatches() {
      // Regression for issue #150.
      String pattern =
          "([A-Z][a-zA-Z0-9_]*<[A-Z][A-Za-z0-9,.& ]+>"
              + "|[A-Z][a-zA-Z0-9_]*<[A-Z][a-zA-Z0-9_]*<[A-Z][A-Za-z0-9,.& ]+>>)";
      String input = "List<List<Integer>> ";
      assertAllMatchesMatchJdk(pattern, input);
    }

    @Test
    @DisplayName("API version pattern — all matches")
    void apiVersionPatternAllMatches() {
      // Regression for issue #150.
      String pattern = "(?:(/|^)\\{\\$api_version\\}(/|$)|\\{\\$api_version\\})";
      String input = "/literature/{$api_version}/";
      assertAllMatchesMatchJdk(pattern, input);
    }
  }

  // ==========================================================================
  // 4. More than two alternatives
  // ==========================================================================

  @Nested
  @DisplayName("Multiple alternatives at different positions")
  class MultipleAlternatives {

    @Test
    @DisplayName("(c|bc|abc) on 'abc' — third alt starts earliest")
    void threeAltsLastStartsEarliest() {
      assertMatchesJdk("(c|bc|abc)", "abc");
    }

    @Test
    @DisplayName("(de|cde|bcde|abcde) on 'xabcdey' — last alt earliest")
    void fourAltsLastStartsEarliest() {
      assertMatchesJdk("(de|cde|bcde|abcde)", "xabcdey");
    }

    @Test
    @DisplayName("(ghi|def|abc) on 'abcdefghi' — third alt at 0")
    void threeNonOverlapping() {
      assertMatchesJdk("(ghi|def|abc)", "abcdefghi");
    }

    @Test
    @DisplayName("(z|yz|xyz|wxyz) on 'wxyz' — last alt at 0")
    void chainedSuffixesToFull() {
      assertMatchesJdk("(z|yz|xyz|wxyz)", "wxyz");
    }
  }

  // ==========================================================================
  // 5. Alternation with capturing groups
  // ==========================================================================

  @Nested
  @DisplayName("With capturing groups (affects engine path)")
  class WithCaptures {

    @Test
    @DisplayName("((b+)|(a+b+)) on 'aabb' — group positions")
    void captureGroupPositions() {
      assertMatchesJdkWithGroups("((b+)|(a+b+))", "aabb");
    }

    @Test
    @DisplayName("((xy)|(wxy)) on 'zwxyz' — captured group text")
    void capturedText() {
      assertMatchesJdkWithGroups("((xy)|(wxy))", "zwxyz");
    }

    @Test
    @DisplayName("(([0-9]+)|([a-z]+[0-9]+)) on 'abc123' — charclass alt groups")
    void charClassCaptureGroups() {
      assertMatchesJdkWithGroups("(([0-9]+)|([a-z]+[0-9]+))", "abc123");
    }

    @Test
    @DisplayName("(?:(b)c|(a)bc) on 'abc' — non-participating groups")
    void nonParticipatingGroups() {
      assertMatchesJdkWithGroups("(?:(b)c|(a)bc)", "abc");
    }
  }

  // ==========================================================================
  // 6. Alternation with quantifiers
  // ==========================================================================

  @Nested
  @DisplayName("Alternation combined with quantifiers")
  class WithQuantifiers {

    @Test
    @DisplayName("(b+|a+b+) on 'aabbb' — greedy quantifiers")
    void greedyQuantifiers() {
      assertMatchesJdk("(b+|a+b+)", "aabbb");
    }

    @Test
    @DisplayName("(b+?|a+?b+?) on 'aabbb' — non-greedy quantifiers")
    void nonGreedyQuantifiers() {
      assertMatchesJdk("(b+?|a+?b+?)", "aabbb");
    }

    @Test
    @DisplayName("(x{2}|.x{2}) on 'axx' — counted repetition")
    void countedRepetition() {
      assertMatchesJdk("(x{2}|.x{2})", "axx");
    }

    @Test
    @DisplayName("(a*b|xa*b) on 'xxab' — star quantifier in alt")
    void starInAlternation() {
      assertMatchesJdk("(a*b|xa*b)", "xxab");
    }

    @Test
    @DisplayName("([0-9]+|[a-z][0-9]+) on 'a123' — charclass+quantifier")
    void charClassQuantifier() {
      assertMatchesJdk("([0-9]+|[a-z][0-9]+)", "a123");
    }

    @Test
    @DisplayName("(a{2,4}|.a{2,4}) on 'xaaaa' — range repetition")
    void rangeRepetition() {
      assertMatchesJdk("(a{2,4}|.a{2,4})", "xaaaa");
    }
  }

  // ==========================================================================
  // 7. Alternation with character classes
  // ==========================================================================

  @Nested
  @DisplayName("Alternation with character classes")
  class WithCharClasses {

    @Test
    @DisplayName("([0-9]+|[a-z]+[0-9]+) on 'abc123' — charclass alts")
    void charClassAlternation() {
      assertMatchesJdk("([0-9]+|[a-z]+[0-9]+)", "abc123");
    }

    @Test
    @DisplayName("(\\d+|\\w+\\d+) on 'abc123' — shorthand charclass")
    void shorthandCharClass() {
      assertMatchesJdk("(\\d+|\\w+\\d+)", "abc123");
    }

    @Test
    @DisplayName("([A-Z]+|[a-z]+[A-Z]+) on 'abcXYZ' — case split")
    void caseSplitCharClass() {
      assertMatchesJdk("([A-Z]+|[a-z]+[A-Z]+)", "abcXYZ");
    }
  }

  // ==========================================================================
  // 8. Nested alternation
  // ==========================================================================

  @Nested
  @DisplayName("Nested alternation groups")
  class NestedAlternation {

    @Test
    @DisplayName("((c|bc)|(abc)) on 'abc' — nested groups, outer alt")
    void nestedGroupsOuterAlt() {
      assertMatchesJdkWithGroups("((c|bc)|(abc))", "abc");
    }

    @Test
    @DisplayName("(a(b|c)|ab(c|d)) on 'abcd' — inner alt different positions")
    void innerAltDifferentPositions() {
      assertMatchesJdkWithGroups("(a(b|c)|ab(c|d))", "abcd");
    }

    @Test
    @DisplayName("((x|xy)(z|yz)) on 'xyz' — both halves have alternation")
    void bothHalvesHaveAlternation() {
      assertMatchesJdkWithGroups("((x|xy)(z|yz))", "xyz");
    }
  }

  // ==========================================================================
  // 9. Alternation with anchors and boundaries
  // ==========================================================================

  @Nested
  @DisplayName("Alternation with anchors and boundaries")
  class WithAnchors {

    @Test
    @DisplayName("(foo$|foobar) on 'foobar' — anchor prevents first alt")
    void dollarAnchorInFirstAlt() {
      assertMatchesJdk("(foo$|foobar)", "foobar");
    }

    @Test
    @DisplayName("(\\bbar|foobar) on 'foobar' — word boundary affects position")
    void wordBoundaryInFirstAlt() {
      assertMatchesJdk("(\\bbar|foobar)", "foobar");
    }

    @Test
    @DisplayName("(^abc|xabc) on 'xabc' — caret prevents first alt")
    void caretPreventsFirstAlt() {
      assertMatchesJdk("(^abc|xabc)", "xabc");
    }
  }

  // ==========================================================================
  // 10. Long text (forces DFA path, >256 chars)
  // ==========================================================================

  @Nested
  @DisplayName("Long text (>256 chars, forces DFA engine path)")
  class LongText {

    private static final String PADDING = "#".repeat(300);

    // --- Capturing groups (DFA sandwich + NFA capture extraction) ---

    @Test
    @DisplayName("(bcd|abcde) in long text — capturing, second alt starts earlier")
    void capturingBasicLongText() {
      assertMatchesJdk("(bcd|abcde)", PADDING + "abcde" + PADDING);
    }

    @Test
    @DisplayName("(cd|abcd) in long text — capturing")
    void capturingSuffixVsFullLongText() {
      assertMatchesJdk("(cd|abcd)", PADDING + "abcd" + PADDING);
    }

    @Test
    @DisplayName("(c|bc|abc) in long text — capturing, three alternatives")
    void capturingThreeAltsLongText() {
      assertMatchesJdk("(c|bc|abc)", PADDING + "abc" + PADDING);
    }

    @Test
    @DisplayName("(b+|a+b+) in long text — capturing, quantifiers")
    void capturingQuantifiersLongText() {
      assertMatchesJdk("(b+|a+b+)", PADDING + "aabbb" + PADDING);
    }

    @Test
    @DisplayName("([0-9]+|[a-z]+[0-9]+) in long text — capturing, charclass")
    void capturingCharClassLongText() {
      assertMatchesJdk("([0-9]+|[a-z]+[0-9]+)", PADDING + "abc123" + PADDING);
    }

    // --- Non-capturing groups (DFA path only, no NFA capture extraction) ---

    @Test
    @DisplayName("(?:bcd|abcde) in long text — non-capturing, second alt starts earlier")
    void nonCapturingBasicLongText() {
      assertMatchesJdk("(?:bcd|abcde)", PADDING + "abcde" + PADDING);
    }

    @Test
    @DisplayName("(?:cd|abcd) in long text — non-capturing")
    void nonCapturingSuffixVsFullLongText() {
      assertMatchesJdk("(?:cd|abcd)", PADDING + "abcd" + PADDING);
    }

    @Test
    @DisplayName("(?:c|bc|abc) in long text — non-capturing, three alternatives")
    void nonCapturingThreeAltsLongText() {
      assertMatchesJdk("(?:c|bc|abc)", PADDING + "abc" + PADDING);
    }

    @Test
    @DisplayName("(?:b+|a+b+) in long text — non-capturing, quantifiers")
    void nonCapturingQuantifiersLongText() {
      assertMatchesJdk("(?:b+|a+b+)", PADDING + "aabbb" + PADDING);
    }

    @Test
    @DisplayName("(?:[0-9]+|[a-z]+[0-9]+) in long text — non-capturing, charclass")
    void nonCapturingCharClassLongText() {
      assertMatchesJdk("(?:[0-9]+|[a-z]+[0-9]+)", PADDING + "abc123" + PADDING);
    }

    // --- Real-world overlapping alternatives on long text ---

    @Test
    @DisplayName("Java generics pattern in long text — capturing")
    void javaGenericsLongTextCapturing() {
      // Regression for issue #150.
      String pattern =
          "([A-Z][a-zA-Z0-9_]*<[A-Z][A-Za-z0-9,.& ]+>"
              + "|[A-Z][a-zA-Z0-9_]*<[A-Z][a-zA-Z0-9_]*<[A-Z][A-Za-z0-9,.& ]+>>)";
      String input = PADDING + "List<List<Integer>> " + PADDING;
      assertMatchesJdkWithGroups(pattern, input);
    }

    @Test
    @DisplayName("Java generics pattern in long text — non-capturing")
    void javaGenericsLongTextNonCapturing() {
      // Regression for issue #150.
      String pattern =
          "(?:[A-Z][a-zA-Z0-9_]*<[A-Z][A-Za-z0-9,.& ]+>"
              + "|[A-Z][a-zA-Z0-9_]*<[A-Z][a-zA-Z0-9_]*<[A-Z][A-Za-z0-9,.& ]+>>)";
      String input = PADDING + "List<List<Integer>> " + PADDING;
      assertMatchesJdk(pattern, input);
    }

    // --- Bare alternation (no explicit group) on long text ---

    @Test
    @DisplayName("bcd|abcde in long text — bare alternation, no group")
    void bareAlternationLongText() {
      assertMatchesJdk("bcd|abcde", PADDING + "abcde" + PADDING);
    }

    // --- Tests isolating the "different endpoints" dimension ---
    // The DFA appears to work when alternatives share the same endpoint
    // but fails when they have different endpoints.

    @Test
    @DisplayName("(cd|abcd) long text — same endpoint: both end at same position")
    void sameEndpointLongText() {
      // cd matches [302,304), abcd matches [300,304) — same endpoint 304
      assertMatchesJdk("(cd|abcd)", PADDING + "abcd" + PADDING);
    }

    @Test
    @DisplayName("(cde|abcde) long text — same endpoint, longer overlap")
    void sameEndpointLongerOverlapLongText() {
      // cde matches [302,305), abcde matches [300,305) — same endpoint 305
      assertMatchesJdk("(cde|abcde)", PADDING + "abcde" + PADDING);
    }

    @Test
    @DisplayName("(bc|abcde) long text — different endpoints: 3-char vs 5-char")
    void differentEndpointsLongText() {
      // bc matches [301,303), abcde matches [300,305) — different endpoints!
      assertMatchesJdk("(bc|abcde)", PADDING + "abcde" + PADDING);
    }

    @Test
    @DisplayName("(bcd|abcdef) long text — different endpoints: 3-char vs 6-char")
    void differentEndpointsSixCharLongText() {
      // bcd matches [301,304), abcdef matches [300,306) — different endpoints!
      assertMatchesJdk("(bcd|abcdef)", PADDING + "abcdef" + PADDING);
    }

    @Test
    @DisplayName("(b|abcd) long text — different endpoints: 1-char vs 4-char")
    void differentEndpointsOneVsFourLongText() {
      // b matches [301,302), abcd matches [300,304) — different endpoints!
      assertMatchesJdk("(b|abcd)", PADDING + "abcd" + PADDING);
    }

    @Test
    @DisplayName("(d|abcd) long text — same endpoint: both end at same pos")
    void sameEndpointOneCharLongText() {
      // d matches [303,304), abcd matches [300,304) — same endpoint 304
      assertMatchesJdk("(d|abcd)", PADDING + "abcd" + PADDING);
    }
  }

  // ==========================================================================
  // 11. Non-capturing groups wrapping alternation
  // ==========================================================================

  @Nested
  @DisplayName("Non-capturing groups around alternation")
  class NonCapturing {

    @Test
    @DisplayName("(?:bcd|abcde) on 'xabcdex' — no captures")
    void nonCapturingBasic() {
      assertMatchesJdk("(?:bcd|abcde)", "xabcdex");
    }

    @Test
    @DisplayName("(?:cd|abcd) on 'xabcdy'")
    void nonCapturingSuffixVsFull() {
      assertMatchesJdk("(?:cd|abcd)", "xabcdy");
    }

    @Test
    @DisplayName("(?:c|bc|abc) on 'abc'")
    void nonCapturingThreeAlts() {
      assertMatchesJdk("(?:c|bc|abc)", "abc");
    }
  }

  // ==========================================================================
  // 12. Multiple successive find() calls (all matches)
  // ==========================================================================

  @Nested
  @DisplayName("All find() matches with position-varying alternation")
  class AllMatches {

    @Test
    @DisplayName("(bcd|abcde) findAll on 'abcde..bcd..'")
    void findAllMixed() {
      assertAllMatchesMatchJdk("(bcd|abcde)", "abcde..bcd..");
    }

    @Test
    @DisplayName("(xy|wxy) findAll on 'wxy..xy..wxy'")
    void findAllRepeated() {
      assertAllMatchesMatchJdk("(xy|wxy)", "wxy..xy..wxy");
    }

    @Test
    @DisplayName("(cd|abcd) findAll on 'abcd..cd..abcd'")
    void findAllOverlap() {
      assertAllMatchesMatchJdk("(cd|abcd)", "abcd..cd..abcd");
    }

    @Test
    @DisplayName("Java generics pattern findAll on multi-generic input")
    void findAllGenerics() {
      // Regression for issue #150.
      String pattern =
          "([A-Z][a-zA-Z0-9_]*<[A-Z][A-Za-z0-9,.& ]+>"
              + "|[A-Z][a-zA-Z0-9_]*<[A-Z][a-zA-Z0-9_]*<[A-Z][A-Za-z0-9,.& ]+>>)";
      String input = "Map<String, List<Integer>> and List<String> and Set<List<Long>>";
      assertAllMatchesMatchJdk(pattern, input);
    }
  }

  // ==========================================================================
  // 13. Parameterized: systematic position-offset exploration
  // ==========================================================================

  @Nested
  @DisplayName("Systematic: shorter alt at offset N, longer alt at offset 0")
  class SystematicOffsets {

    static Stream<Arguments> offsetCases() {
      List<Arguments> cases = new ArrayList<>();
      // For each offset 1..5, test that the longer alternative matching at position 0
      // beats the shorter alternative matching at a later position.
      for (int offset = 1; offset <= 5; offset++) {
        String prefix = "x".repeat(offset);
        // Pattern: (short_suffix|full_string) where short_suffix appears later
        String full = prefix + "ABC";
        String shortAlt = "ABC";
        cases.add(Arguments.of(
            String.format("offset=%d: (%s|%s) on '%s'", offset, shortAlt, full, full),
            "(" + shortAlt + "|" + full + ")",
            full));
      }
      return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("offsetCases")
    void shorterAltAtOffset(String desc, String pattern, String input) {
      assertMatchesJdk(pattern, input);
    }
  }

  // ==========================================================================
  // 14. Exhaustive cross-engine: small alphabet with alternation focus
  // ==========================================================================

  @Nested
  @DisplayName("Exhaustive: alternation with multi-char atoms")
  class ExhaustiveAlternation {

    @Test
    @DisplayName("Exhaustive alternation with overlapping multi-char atoms")
    void exhaustiveMultiCharAlternation() {
      // Multi-char atoms that can match at different positions in the same string.
      // This is the KEY missing configuration: existing exhaustive tests only use
      // single-char atoms for alternation, which cannot produce different-position matches.
      int tests =
          ExhaustiveUtils.run(
              new ExhaustiveUtils.Config(
                  /* maxAtoms= */ 2,
                  /* maxOps= */ 1,
                  /* atoms= */ List.of("a", "ab", "ba", "b"),
                  /* ops= */ List.of("%s|%s", "%s%s"),
                  /* maxStrLen= */ 4,
                  /* strAlphabet= */ List.of("a", "b"),
                  /* wrapper= */ "",
                  /* flags= */ 0,
                  /* testLongText= */ true));
      System.err.printf("exhaustiveMultiCharAlternation: %,d tests%n", tests);
    }

    @Test
    @DisplayName("Exhaustive alternation with three-char atoms")
    void exhaustiveThreeCharAtoms() {
      int tests =
          ExhaustiveUtils.run(
              new ExhaustiveUtils.Config(
                  /* maxAtoms= */ 2,
                  /* maxOps= */ 1,
                  /* atoms= */ List.of("a", "abc", "bc", "c"),
                  /* ops= */ List.of("%s|%s"),
                  /* maxStrLen= */ 5,
                  /* strAlphabet= */ List.of("a", "b", "c"),
                  /* wrapper= */ "",
                  /* flags= */ 0,
                  /* testLongText= */ true));
      System.err.printf("exhaustiveThreeCharAtoms: %,d tests%n", tests);
    }

    @Test
    @DisplayName("Exhaustive alternation with quantifiers and multi-char atoms")
    void exhaustiveAlternationWithQuantifiers() {
      int tests =
          ExhaustiveUtils.run(
              new ExhaustiveUtils.Config(
                  /* maxAtoms= */ 2,
                  /* maxOps= */ 1,
                  /* atoms= */ List.of("a", "ab", "b", "ba"),
                  /* ops= */ List.of("%s|%s", "%s*", "%s+"),
                  /* maxStrLen= */ 4,
                  /* strAlphabet= */ List.of("a", "b"),
                  /* wrapper= */ "",
                  /* flags= */ 0,
                  /* testLongText= */ true));
      System.err.printf("exhaustiveAlternationWithQuantifiers: %,d tests%n", tests);
    }

    @Test
    @DisplayName("Exhaustive: three-way alternation with different-length atoms")
    void exhaustiveThreeWayAlternation() {
      int tests =
          ExhaustiveUtils.run(
              new ExhaustiveUtils.Config(
                  /* maxAtoms= */ 3,
                  /* maxOps= */ 2,
                  /* atoms= */ List.of("a", "ab", "b"),
                  /* ops= */ List.of("%s|%s"),
                  /* maxStrLen= */ 4,
                  /* strAlphabet= */ List.of("a", "b"),
                  /* wrapper= */ "",
                  /* flags= */ 0,
                  /* testLongText= */ true));
      System.err.printf("exhaustiveThreeWayAlternation: %,d tests%n", tests);
    }
  }

  // ==========================================================================
  // 15. Alternation with dot (.) wildcard
  // ==========================================================================

  @Nested
  @DisplayName("Alternation with dot wildcard")
  class WithDot {

    @Test
    @DisplayName("(.bc|abc) on 'xabc' — dot in first alt")
    void dotInFirstAlt() {
      assertMatchesJdk("(.bc|abc)", "xabc");
    }

    @Test
    @DisplayName("(..c|abc) on 'xabc' — two dots vs literal")
    void dotsVsLiteral() {
      assertMatchesJdk("(..c|abc)", "xabc");
    }

    @Test
    @DisplayName("(.+b|a.+b) on 'axxb' — dot-plus in alternation")
    void dotPlusInAlternation() {
      assertMatchesJdk("(.+b|a.+b)", "axxb");
    }
  }

  // ==========================================================================
  // 16. Alternation where alternatives share a common prefix
  // ==========================================================================

  @Nested
  @DisplayName("Shared prefix between alternatives")
  class SharedPrefix {

    @Test
    @DisplayName("(ab|abc) on 'xabc' — short prefix vs long prefix")
    void shortVsLongPrefix() {
      assertMatchesJdk("(ab|abc)", "xabc");
    }

    @Test
    @DisplayName("(abc|abcd) on 'xabcdy' — extending prefix")
    void extendingPrefix() {
      assertMatchesJdk("(abc|abcd)", "xabcdy");
    }

    @Test
    @DisplayName("(ab.|ab..) on 'xabcdy' — shared prefix with wildcards")
    void sharedPrefixWildcards() {
      assertMatchesJdk("(ab.|ab..)", "xabcdy");
    }
  }

  // ==========================================================================
  // 17. Real-world patterns that hit this bug class
  // ==========================================================================

  @Nested
  @DisplayName("Real-world patterns")
  class RealWorld {

    @Test
    @DisplayName("HTTP method pattern: (GET|POST|DELETE) in URL line")
    void httpMethodInUrl() {
      // Both alternatives can appear, test leftmost
      assertMatchesJdk("(POST|GET|PUT|DELETE)", "GET /api/v1 HTTP/1.1");
    }

    @Test
    @DisplayName("Log level: (WARN|WARNING) in log line")
    void logLevel() {
      assertMatchesJdk("(WARN|WARNING)", "2026-01-01 WARNING: disk full");
    }

    @Test
    @DisplayName("Path matching: two URL alternatives at different positions")
    void pathMatching() {
      assertMatchesJdk("(/api/v2/users|/v2/users)", "GET /api/v2/users HTTP/1.1");
    }

    @Test
    @DisplayName("Version string: (\\d+\\.\\d+|\\d+\\.\\d+\\.\\d+) on '1.2.3'")
    void versionString() {
      assertMatchesJdk("(\\d+\\.\\d+|\\d+\\.\\d+\\.\\d+)", "version 1.2.3");
    }

    @Test
    @DisplayName("HTML tag pattern: simple vs nested")
    void htmlTags() {
      assertMatchesJdk("(<b>|<b class=\"[^\"]*\">)", "text <b class=\"bold\">hello</b>");
    }
  }

  // ==========================================================================
  // 18. Minimal reproduction: progressively simplify short-text failures
  // ==========================================================================

  @Nested
  @DisplayName("Minimal reproduction of short-text failures")
  class MinimalRepro {

    // The generics pattern fails on short text. The basic (bcd|abcde) passes on short text.
    // These tests progressively simplify the generics pattern to find the boundary.

    @Test
    @DisplayName("Shared prefix with charclass: ([A-Z]x|[A-Z]xy) on 'Axy'")
    void sharedCharClassPrefix() {
      assertMatchesJdk("([A-Z]x|[A-Z]xy)", "Axy");
    }

    @Test
    @DisplayName("Shared prefix + quantifier: ([A-Z]+x|[A-Z]+xy) on 'AAAxy'")
    void sharedCharClassPrefixQuantifier() {
      assertMatchesJdk("([A-Z]+x|[A-Z]+xy)", "AAAxy");
    }

    @Test
    @DisplayName("Alt1 is suffix of alt2's match: (x>|x.x>) on 'axbx>'")
    void alt1SuffixOfAlt2() {
      assertMatchesJdk("(x>|x.x>)", "axbx>");
    }

    @Test
    @DisplayName("Alt with literal delimiters: (a<b>|a<a<b>>) on 'a<a<b>>'")
    void literalDelimiters() {
      assertMatchesJdk("(a<b>|a<a<b>>)", "a<a<b>>");
    }

    @Test
    @DisplayName("Charclass + quantifier, different match lengths")
    void charClassQuantifierDiffLengths() {
      // Alt1 matches shorter thing at later position, alt2 matches longer thing from earlier
      assertMatchesJdk("([a-z]+>|[a-z]+<[a-z]+>)", "ab<cd>");
    }

    @Test
    @DisplayName("Nested angle brackets: (X<Y>|X<X<Y>>)")
    void nestedAngleBrackets() {
      assertMatchesJdk("(X<Y>|X<X<Y>>)", "X<X<Y>>");
    }

    @Test
    @DisplayName("Simplified generics: (L<I>|L<L<I>>) on 'L<L<I>>'")
    void simplifiedGenerics() {
      assertMatchesJdk("(L<I>|L<L<I>>)", "L<L<I>>");
    }

    @Test
    @DisplayName("Simplified generics with charclass: ([A-Z]<[A-Z]>|[A-Z]<[A-Z]<[A-Z]>>)")
    void simplifiedGenericsCharClass() {
      assertMatchesJdk(
          "([A-Z]<[A-Z]>|[A-Z]<[A-Z]<[A-Z]>>)", "A<B<C>>");
    }

    @Test
    @DisplayName("With + quantifier: ([a-z]+<[a-z]+>|[a-z]+<[a-z]+<[a-z]+>>)")
    void withPlusQuantifier() {
      assertMatchesJdk(
          "([a-z]+<[a-z]+>|[a-z]+<[a-z]+<[a-z]+>>)", "ab<cd<ef>>");
    }

    @Test
    @DisplayName("API version simplified: (/.+/|.+) on '/lit/{v}/'")
    void apiVersionSimplified() {
      assertMatchesJdk("(/.+/|.+)", "/lit/thing/");
    }

    @Test
    @DisplayName("Alternation where first alt fails, second succeeds, at same start position")
    void firstAltFailsSameStart() {
      // At position 0: alt1 a<b> needs 'a<b>' but input is 'a<a<b>>',
      // so alt1 sees a<a which fails at second char. Alt2 a<a<b>> matches.
      assertMatchesJdk("(a<b>|a<a<b>>)", " a<a<b>>");
    }

    @Test
    @DisplayName("Both alts share prefix, only second can match at earlier position")
    void sharedPrefixSecondAltOnly() {
      // In 'xabcdy': at pos 1, alt1 'abc' matches [1,4), at pos 1 alt2 'abcde' matches [1,6)
      // Both start at same position but different end — tests preference for longer match
      assertMatchesJdk("(abc|abcde)", "xabcdy");
    }

    @Test
    @DisplayName("Minimal nested: (y>|xyy>) on 'xyy>'")
    void minimalNested() {
      // Alt1 y> at pos 1: y✓ y≠> → fail. At pos 2: y✓ >✓ → [2,4).
      // Alt2 xyy> at pos 0: x✓ y✓ y✓ >✓ → [0,4). Leftmost is 0.
      assertMatchesJdk("(y>|xyy>)", "xyy>");
    }

    @Test
    @DisplayName("Three-char minimal: (bc>|abbc>) on 'abbc>'")
    void threeCharMinimal() {
      assertMatchesJdk("(bc>|abbc>)", "abbc>");
    }

    @Test
    @DisplayName("Same structure, different delimiters: (b]|a[b]) on 'a[b]'")
    void bracketDelimiters() {
      assertMatchesJdk("(b]|a\\[b])", "a[b]");
    }

    @Test
    @DisplayName("Same structure with parens: (b\\)|a\\(b\\)) on 'a(b)'")
    void parenDelimiters() {
      assertMatchesJdk("(b\\)|a\\(b\\))", "a(b)");
    }
  }
}
