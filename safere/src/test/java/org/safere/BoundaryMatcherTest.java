// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for boundary matcher constructs: {@code \b}, {@code \B}, {@code \A}, {@code \z}, {@code
 * \Z}, {@code \G}, and {@code \b{g}}.
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

    private void assertFindSameAsJdk(String regex, String input) {
      java.util.regex.Matcher jdkMatcher = java.util.regex.Pattern.compile(regex).matcher(input);
      Matcher safeMatcher = Pattern.compile(regex).matcher(input);

      List<int[]> jdkMatches = new ArrayList<>();
      while (jdkMatcher.find()) {
        jdkMatches.add(new int[] {jdkMatcher.start(), jdkMatcher.end()});
      }

      List<int[]> safeMatches = new ArrayList<>();
      while (safeMatcher.find()) {
        safeMatches.add(new int[] {safeMatcher.start(), safeMatcher.end()});
      }

      assertThat(safeMatches)
          .as("find() positions for /%s/ on %s", regex, input)
          .containsExactly(jdkMatches.toArray(int[][]::new));
    }

    private void assertTraceSameAsJdk(String regex, String input, int start, int end) {
      java.util.regex.Matcher jdkMatcher =
          java.util.regex.Pattern.compile(regex).matcher(input).region(start, end);
      Matcher safeMatcher = Pattern.compile(regex).matcher(input).region(start, end);

      assertThat(safeMatcher.matches())
          .as("matches() for /%s/ on %s region [%s,%s]", regex, input, start, end)
          .isEqualTo(jdkMatcher.matches());

      jdkMatcher.reset(input).region(start, end);
      safeMatcher.reset(input).region(start, end);
      boolean jdkLookingAt = jdkMatcher.lookingAt();
      boolean safeLookingAt = safeMatcher.lookingAt();
      assertThat(safeLookingAt)
          .as("lookingAt() for /%s/ on %s region [%s,%s]", regex, input, start, end)
          .isEqualTo(jdkLookingAt);
      if (jdkLookingAt) {
        assertThat(new int[] {safeMatcher.start(), safeMatcher.end()})
            .as("lookingAt() positions for /%s/ on %s region [%s,%s]", regex, input, start, end)
            .containsExactly(jdkMatcher.start(), jdkMatcher.end());
      }

      jdkMatcher.reset(input).region(start, end);
      safeMatcher.reset(input).region(start, end);
      List<int[]> jdkMatches = new ArrayList<>();
      while (jdkMatcher.find()) {
        jdkMatches.add(new int[] {jdkMatcher.start(), jdkMatcher.end()});
      }

      List<int[]> safeMatches = new ArrayList<>();
      while (safeMatcher.find()) {
        safeMatches.add(new int[] {safeMatcher.start(), safeMatcher.end()});
      }

      assertThat(safeMatches)
          .as("find() positions for /%s/ on %s region [%s,%s]", regex, input, start, end)
          .containsExactly(jdkMatches.toArray(int[][]::new));
    }

    private void assertSafeReFindBounds(
        String regex, String input, int start, int end, List<String> expected) {
      Matcher safeMatcher = Pattern.compile(regex).matcher(input).region(start, end);
      List<String> safeMatches = new ArrayList<>();
      while (safeMatcher.find()) {
        safeMatches.add(safeMatcher.start() + "-" + safeMatcher.end());
      }
      assertThat(safeMatches)
          .as("SafeRE find() positions for /%s/ on %s region [%s,%s]", regex, input, start, end)
          .containsExactlyElementsOf(expected);
    }

    private void assertTransparentTraceSameAsJdk(String regex, String input, int start, int end) {
      java.util.regex.Matcher jdkMatcher =
          java.util.regex.Pattern.compile(regex)
              .matcher(input)
              .region(start, end)
              .useTransparentBounds(true);
      Matcher safeMatcher =
          Pattern.compile(regex).matcher(input).region(start, end).useTransparentBounds(true);

      assertThat(safeMatcher.matches())
          .as("transparent matches() for /%s/ on %s region [%s,%s]", regex, input, start, end)
          .isEqualTo(jdkMatcher.matches());

      jdkMatcher.reset(input).region(start, end).useTransparentBounds(true);
      safeMatcher.reset(input).region(start, end).useTransparentBounds(true);
      boolean jdkLookingAt = jdkMatcher.lookingAt();
      boolean safeLookingAt = safeMatcher.lookingAt();
      assertThat(safeLookingAt)
          .as("transparent lookingAt() for /%s/ on %s region [%s,%s]", regex, input, start, end)
          .isEqualTo(jdkLookingAt);
      if (jdkLookingAt) {
        assertThat(new int[] {safeMatcher.start(), safeMatcher.end()})
            .as(
                "transparent lookingAt() positions for /%s/ on %s region [%s,%s]",
                regex, input, start, end)
            .containsExactly(jdkMatcher.start(), jdkMatcher.end());
      }

      jdkMatcher.reset(input).region(start, end).useTransparentBounds(true);
      safeMatcher.reset(input).region(start, end).useTransparentBounds(true);
      List<int[]> jdkMatches = new ArrayList<>();
      while (jdkMatcher.find()) {
        jdkMatches.add(new int[] {jdkMatcher.start(), jdkMatcher.end()});
      }

      List<int[]> safeMatches = new ArrayList<>();
      while (safeMatcher.find()) {
        safeMatches.add(new int[] {safeMatcher.start(), safeMatcher.end()});
      }

      assertThat(safeMatches)
          .as(
              "transparent find() positions for /%s/ on %s region [%s,%s]",
              regex, input, start, end)
          .containsExactly(jdkMatches.toArray(int[][]::new));
    }

    private void assertCapturedFindTraceSameAsJdk(String regex, String input, int start, int end) {
      java.util.regex.Matcher jdkMatcher =
          java.util.regex.Pattern.compile(regex).matcher(input).region(start, end);
      Matcher safeMatcher = Pattern.compile(regex).matcher(input).region(start, end);

      List<String> jdkMatches = capturedFindTrace(jdkMatcher);
      List<String> safeMatches = capturedFindTrace(safeMatcher);

      assertThat(safeMatches)
          .as("captured find() trace for /%s/ on %s region [%s,%s]", regex, input, start, end)
          .containsExactlyElementsOf(jdkMatches);
    }

    private List<String> capturedFindTrace(java.util.regex.Matcher matcher) {
      List<String> trace = new ArrayList<>();
      while (matcher.find()) {
        trace.add(capturedMatchTrace(matcher));
      }
      return trace;
    }

    private List<String> capturedFindTrace(Matcher matcher) {
      List<String> trace = new ArrayList<>();
      while (matcher.find()) {
        trace.add(capturedMatchTrace(matcher));
      }
      return trace;
    }

    private String capturedMatchTrace(java.util.regex.Matcher matcher) {
      StringBuilder builder = new StringBuilder();
      builder.append(matcher.start()).append('-').append(matcher.end()).append(':');
      builder.append(matcher.group());
      for (int i = 1; i <= matcher.groupCount(); i++) {
        builder.append(':').append(matcher.group(i));
      }
      return builder.toString();
    }

    private String capturedMatchTrace(Matcher matcher) {
      StringBuilder builder = new StringBuilder();
      builder.append(matcher.start()).append('-').append(matcher.end()).append(':');
      builder.append(matcher.group());
      for (int i = 1; i <= matcher.groupCount(); i++) {
        builder.append(':').append(matcher.group(i));
      }
      return builder.toString();
    }

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
    @DisplayName("\\b{g} does not match inside a base-plus-combining-mark cluster")
    void doesNotMatchInsideBaseCombiningCluster() {
      assertFindSameAsJdk("a\\b{g}\\u0300", "a\u0300");
    }

    @Test
    @DisplayName("\\b{g} matches at the edges of base-plus-combining-mark clusters")
    void matchesBaseCombiningClusterEdges() {
      assertFindSameAsJdk("\\b{g}a\\u0300\\b{g}", "a\u0300");
    }

    @Test
    @DisplayName("\\b{g} find() reports cluster boundaries, not every UTF-16 position")
    void findReportsOnlyClusterBoundaries() {
      assertFindSameAsJdk("\\b{g}", "a\u0300b");
    }

    @Test
    @DisplayName("\\b{g} does not split CRLF")
    void doesNotSplitCrLf() {
      assertFindSameAsJdk("\\r\\b{g}\\n", "\r\n");
    }

    @Test
    @DisplayName("\\b{g} respects opaque region bounds for base-plus-mark clusters")
    void respectsOpaqueRegionBoundsForBaseMarkClusters() {
      assertTraceSameAsJdk("\\b{g}", "e\u0301", 1, 2);
      assertTraceSameAsJdk("(\\b{g})", "e\u0301", 1, 2);
      assertTraceSameAsJdk("\\b{g}\\X", "e\u0301", 1, 2);
      assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "e\u0301", 1, 2);
      assertTraceSameAsJdk("a\\b{g}\\u0300", "#a\u0300$", 1, 3);
      assertTraceSameAsJdk("(?:a)\\b{g}\\u0300", "#a\u0300$", 1, 3);
      assertTraceSameAsJdk("(a)\\b{g}\\u0300", "#a\u0300$", 1, 3);
      assertTraceSameAsJdk("a(?:\\b{g})\\u0300", "#a\u0300$", 1, 3);
      assertTraceSameAsJdk("\\u0061\\b{g}\\u0300", "#a\u0300$", 1, 3);
      assertTraceSameAsJdk("\\r\\b{g}\\n", "#\r\n$", 1, 3);
      assertTraceSameAsJdk("(?:\\r)\\b{g}\\n", "#\r\n$", 1, 3);
      assertTraceSameAsJdk("(\\r)\\b{g}\\n", "#\r\n$", 1, 3);
      assertTraceSameAsJdk("\\r(?:\\b{g})\\n", "#\r\n$", 1, 3);
      assertTraceSameAsJdk("\\u000D\\b{g}\\u000A", "#\r\n$", 1, 3);
    }

    @Test
    @DisplayName("\\b{g} treats opaque region edges inside non-surrogate clusters as boundaries")
    void treatsOpaqueRegionEdgesInsideNonSurrogateClustersAsBoundaries() {
      assertTraceSameAsJdk("\\b{g}", "a\u0301b", 1, 2);
      assertTraceSameAsJdk("\\b{g}\\X", "a\u0301b", 1, 2);
      assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "a\u0301b", 1, 2);

      String hangulCluster = "\u1100\u1161";
      assertTraceSameAsJdk("\\b{g}", hangulCluster, 1, 2);
      assertTraceSameAsJdk("\\b{g}\\X", hangulCluster, 1, 2);
      assertTraceSameAsJdk("\\b{g}\\X\\b{g}", hangulCluster, 1, 2);
    }

    @Test
    @DisabledForCrosscheck(
        "SafeRE's region-local grapheme model intentionally composes this JDK edge trace")
    @DisplayName("\\b{g} composes across opaque region edges inside ZWJ sequences")
    void composesAcrossOpaqueRegionEdgesInsideZwjSequences() {
      String zwjSequence = "\uD83D\uDC69\u200D\uD83D\uDC69";
      assertSafeReFindBounds("\\b{g}\\X", zwjSequence, 2, 5, List.of("2-3", "3-5"));
    }

    @Test
    @DisplayName("bare \\b{g} alternatives preserve priority after empty find() matches")
    void boundaryOnlyAlternativesPreserveEmptyMatchPriorityOnRepeatedFind() {
      assertTraceSameAsJdk("\\b{g}|a", "aa", 0, 2);
      assertTraceSameAsJdk("(?:\\b{g}|a)", "aa", 0, 2);
      assertTraceSameAsJdk("(?:(?:\\b{g})|a)", "aa", 0, 2);
      assertCapturedFindTraceSameAsJdk("(\\b{g})|(a)", "aa", 0, 2);
      assertTraceSameAsJdk("\\b{g}|\\X", "aa", 0, 2);
      assertTraceSameAsJdk("(?:\\b{g}|\\X)", "aa", 0, 2);
      assertCapturedFindTraceSameAsJdk("(\\b{g})|(\\X)", "aa", 0, 2);
    }

    @Test
    @DisplayName("transparent trailing \\b{g} sees contextual characters beyond region end")
    void transparentTrailingGraphemeBoundaryUsesFullTextContext() {
      assertTransparentTraceSameAsJdk("\\X\\b{g}", "a\u0301", 0, 1);
      assertTransparentTraceSameAsJdk("^\\X\\b{g}", "a\u0301", 0, 1);
      assertTransparentTraceSameAsJdk("\\b{g}\\X\\b{g}", "a\u0301", 0, 1);
      assertTransparentTraceSameAsJdk("\\X\\b{g}", "\r\n", 0, 1);
      assertTransparentTraceSameAsJdk("\\X\\b{g}", "a\u200D", 0, 1);
      assertTransparentTraceSameAsJdk("\\X\\b{g}", "a\u0903", 0, 1);
      assertTransparentTraceSameAsJdk("\\X\\b{g}", "\uD83D\uDC4D\uD83C\uDFFD", 0, 2);
      assertTransparentTraceSameAsJdk("\\X\\b{g}", "\uD83C\uDDFA\uD83C\uDDF8", 0, 2);
      assertTransparentTraceSameAsJdk("\\X\\b{g}", "\u1100\u1161", 0, 1);
      assertTransparentTraceSameAsJdk("\\X\\b{g}", "\uAC00\u11A8", 0, 1);
    }

    @Test
    @DisplayName("\\b{g} and \\X respect regions split inside surrogate pairs")
    void graphemeBoundariesRespectRegionsSplitInsideSurrogatePairs() {
      String splitBeforeZwj = "\uD83D\uDC69\u200D\uD83D\uDCBB";
      assertTraceSameAsJdk("\\b{g}", splitBeforeZwj, 1, 5);
      assertTransparentTraceSameAsJdk("\\b{g}", "\uD83D\uDE00", 1, 1);
      assertTransparentTraceSameAsJdk("(\\b{g})", "\uD83D\uDE00", 1, 1);
      assertTransparentTraceSameAsJdk("\\b{g}", "\uD83D\uDE00\u0301\u0301", 2, 4);
      assertTransparentTraceSameAsJdk("(\\b{g})", "\uD83D\uDE00\u0301\u0301", 2, 4);
      assertTransparentTraceSameAsJdk("\\b{g}", "\uD83D\uD83C\uDDFA\uD83C\uDDF8\u200D", 1, 5);
      assertTransparentTraceSameAsJdk(
          "\\b{g}", "a\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDE8\u0300", 1, 7);

      String lowSurrogateThenZwj = "\uD83D\uDE00\u200D";
      assertTraceSameAsJdk("\\b{g}", lowSurrogateThenZwj, 1, 3);

      String splitLowSurrogateBeforeModifier = "\uD83D\uDC4D\uD83C\uDFFB";
      assertTransparentTraceSameAsJdk("\\X\\b{g}", splitLowSurrogateBeforeModifier, 1, 3);
      assertTransparentTraceSameAsJdk("\\b{g}\\X\\b{g}", splitLowSurrogateBeforeModifier, 1, 3);

      String splitBeforeExtender = "\uD83D\uDE00\uD83D\u0301";
      assertTraceSameAsJdk("\\b{g}", splitBeforeExtender, 1, 3);
      assertTraceSameAsJdk("(\\b{g})", splitBeforeExtender, 1, 3);
      assertTraceSameAsJdk("\\X\\b{g}", splitBeforeExtender, 1, 3);
    }

    @Test
    @DisplayName(
        "\\X preserves full code points when an opaque region ends inside a surrogate pair")
    void graphemeClustersPreserveCodePointsAtOpaqueRegionEnd() {
      String emoji = "\uD83D\uDE00";
      assertTraceSameAsJdk("\\X", emoji, 0, 1);
      assertTraceSameAsJdk("^\\X$", emoji, 0, 1);
      assertTraceSameAsJdk("\\b{g}\\X", emoji, 0, 1);
      assertTraceSameAsJdk("\\X\\b{g}", emoji, 0, 1);
      assertCapturedFindTraceSameAsJdk("(.)|(\\X)", emoji, 0, 1);
      assertCapturedFindTraceSameAsJdk("(\\X)|(.)", emoji, 0, 1);
    }

    @Test
    @DisplayName("chained \\X find() preserves leftmost starts inside Indic conjunct clusters")
    void chainedGraphemeClustersPreserveLeftmostStartsInsideIndicConjuncts() {
      String devanagariConjunct = "\u0915\u094D\u200D\u0915";
      assertTraceSameAsJdk("\\X\\X", devanagariConjunct, 0, devanagariConjunct.length());
      assertTraceSameAsJdk("(?:\\X)(?:\\X)", devanagariConjunct, 0, devanagariConjunct.length());
      assertTraceSameAsJdk("(?:\\X){2}", devanagariConjunct, 0, devanagariConjunct.length());
      assertTraceSameAsJdk("\\X{1}\\X", devanagariConjunct, 0, devanagariConjunct.length());
      assertCapturedFindTraceSameAsJdk(
          "(\\X)(\\X)", devanagariConjunct, 0, devanagariConjunct.length());

      String bengaliConjunct = "\u0995\u09CD\u200D\u0995";
      assertTraceSameAsJdk("\\X\\X", bengaliConjunct, 0, bengaliConjunct.length());
      assertTraceSameAsJdk("(?:\\X){2}", bengaliConjunct, 0, bengaliConjunct.length());
      assertCapturedFindTraceSameAsJdk("(\\X)(\\X)", bengaliConjunct, 0, bengaliConjunct.length());
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
  // \b in zero-width alternation
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("\\b in zero-width alternation")
  class WordBoundaryAlternation {

    @Test
    @DisplayName("(?:$|\\b) finds the word boundary before the first word character")
    void dollarOrWordBoundary() {
      // Regression for issue #42: find() must choose the leftmost boundary, not the first
      // alternative that can match later.
      Matcher m = Pattern.compile("(?:$|\\b)").matcher(" a");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(1);
      assertThat(m.end()).isEqualTo(1);
    }

    @Test
    @DisplayName("(?:\\b|$) finds the word boundary before the first word character")
    void wordBoundaryOrDollar() {
      Matcher m = Pattern.compile("(?:\\b|$)").matcher(" a");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(1);
      assertThat(m.end()).isEqualTo(1);
    }

    @Test
    @DisplayName("$|\\b on longer text finds the boundary at the start of the word")
    void dollarOrWordBoundaryLongText() {
      Matcher m = Pattern.compile("(?:$|\\b)").matcher("hello world");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(0);
    }

    @Test
    @DisplayName("find() with $|\\b returns each boundary position once")
    void dollarOrWordBoundaryFindAll() {
      Matcher m = Pattern.compile("(?:$|\\b)").matcher(" a");
      List<int[]> matches = new ArrayList<>();
      while (m.find()) {
        matches.add(new int[] {m.start(), m.end()});
      }

      assertThat(matches).hasSize(2);
      assertThat(matches.get(0)).containsExactly(1, 1);
      assertThat(matches.get(1)).containsExactly(2, 2);
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
