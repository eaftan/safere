// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@code \R} (Unicode linebreak) and {@code \X} (extended grapheme cluster) support.
 * Validates behavior against JDK 21's {@link java.util.regex.Pattern} specification.
 */
class LinebreakGraphemeTest {
  private static final Duration PERFORMANCE_SCENARIO_TIMEOUT = Duration.ofSeconds(30);

  @Nested
  @DisplayName("\\R (Unicode linebreak)")
  class LinebreakTests {

    @Test
    @DisplayName("\\R matches LF (U+000A)")
    void matchesLineFeed() {
      Pattern p = Pattern.compile("\\R");
      assertThat(p.matcher("\n").matches()).isTrue();
    }

    @Test
    @DisplayName("\\R matches CR (U+000D)")
    void matchesCarriageReturn() {
      Pattern p = Pattern.compile("\\R");
      assertThat(p.matcher("\r").matches()).isTrue();
    }

    @Test
    @DisplayName("\\R matches CRLF as a single unit")
    void matchesCrLf() {
      Pattern p = Pattern.compile("\\R");
      assertThat(p.matcher("\r\n").matches()).isTrue();
    }

    @Test
    @DisplayName("\\R matches vertical tab (U+000B)")
    void matchesVerticalTab() {
      Pattern p = Pattern.compile("\\R");
      assertThat(p.matcher("\u000B").matches()).isTrue();
    }

    @Test
    @DisplayName("\\R matches form feed (U+000C)")
    void matchesFormFeed() {
      Pattern p = Pattern.compile("\\R");
      assertThat(p.matcher("\f").matches()).isTrue();
    }

    @Test
    @DisplayName("\\R matches next line (U+0085)")
    void matchesNextLine() {
      Pattern p = Pattern.compile("\\R");
      assertThat(p.matcher("\u0085").matches()).isTrue();
    }

    @Test
    @DisplayName("\\R matches line separator (U+2028)")
    void matchesLineSeparator() {
      Pattern p = Pattern.compile("\\R");
      assertThat(p.matcher("\u2028").matches()).isTrue();
    }

    @Test
    @DisplayName("\\R matches paragraph separator (U+2029)")
    void matchesParagraphSeparator() {
      Pattern p = Pattern.compile("\\R");
      assertThat(p.matcher("\u2029").matches()).isTrue();
    }

    @Test
    @DisplayName("\\R does not match ordinary characters")
    void doesNotMatchOrdinaryChars() {
      Pattern p = Pattern.compile("\\R");
      assertThat(p.matcher("a").matches()).isFalse();
      assertThat(p.matcher(" ").matches()).isFalse();
      assertThat(p.matcher("\t").matches()).isFalse();
      assertThat(p.matcher("1").matches()).isFalse();
    }

    @Test
    @DisplayName("\\R treats CRLF as one match, not two")
    void crlfIsSingleMatch() {
      Pattern p = Pattern.compile("\\R");
      Matcher m = p.matcher("\r\n");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(0);
      assertThat(m.end()).isEqualTo(2);
      assertThat(m.find()).isFalse(); // no second match
    }

    @Test
    @DisplayName("\\R+ matches multiple linebreaks")
    void plusMatchesMultiple() {
      Pattern p = Pattern.compile("\\R+");
      assertThat(p.matcher("\n\n").matches()).isTrue();
      assertThat(p.matcher("\r\n\n").matches()).isTrue();
      assertThat(p.matcher("\r\n").matches()).isTrue();
    }

    @Test
    @DisplayName("\\R works in context: a\\Rb matches a<linebreak>b")
    void worksInContext() {
      Pattern p = Pattern.compile("a\\Rb");
      assertThat(p.matcher("a\nb").matches()).isTrue();
      assertThat(p.matcher("a\rb").matches()).isTrue();
      assertThat(p.matcher("a\r\nb").matches()).isTrue();
      assertThat(p.matcher("a\u2028b").matches()).isTrue();
      assertThat(p.matcher("ab").matches()).isFalse();
    }

    @Test
    @DisplayName("\\R\\n matches CRLF (\\R takes \\r, \\n takes \\n)")
    void linebreakFollowedByNewline() {
      Pattern p = Pattern.compile("\\R\\n");
      assertThat(p.matcher("\r\n").matches()).isTrue();
    }

    @Test
    @DisplayName("\\R{2} matches two linebreak sequences")
    void repeatedLinebreak() {
      Pattern p = Pattern.compile("\\R{2}");
      assertThat(p.matcher("\r\n\n").matches()).isTrue();
      assertThat(p.matcher("\n\n").matches()).isTrue();
    }

    @Test
    @DisplayName("\\R? matches optional linebreak")
    void optionalLinebreak() {
      Pattern p = Pattern.compile("a\\R?b");
      assertThat(p.matcher("ab").matches()).isTrue();
      assertThat(p.matcher("a\nb").matches()).isTrue();
      assertThat(p.matcher("a\r\nb").matches()).isTrue();
    }

    @Test
    @DisplayName("\\R inside [...] is rejected")
    void rejectedInsideCharClass() {
      assertThatThrownBy(() -> Pattern.compile("[\\R]")).isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    @DisplayName("find() locates \\R in mixed text")
    void findInMixedText() {
      Pattern p = Pattern.compile("\\R");
      Matcher m = p.matcher("hello\nworld\r\n!");
      List<int[]> matches = new ArrayList<>();
      while (m.find()) {
        matches.add(new int[] {m.start(), m.end()});
      }
      assertThat(matches).hasSize(2);
      assertThat(matches.get(0)).containsExactly(5, 6); // \n
      assertThat(matches.get(1)).containsExactly(11, 13); // \r\n
    }

    @Test
    @DisplayName("\\R works with capture groups")
    void captureGroup() {
      Pattern p = Pattern.compile("(\\R)");
      Matcher m = p.matcher("\r\n");
      assertThat(m.find()).isTrue();
      assertThat(m.group(1)).isEqualTo("\r\n");
    }

    @Test
    @DisplayName("split on \\R splits lines correctly")
    void splitOnLinebreak() {
      Pattern p = Pattern.compile("\\R");
      String[] parts = p.split("line1\nline2\r\nline3\rline4");
      assertThat(parts).containsExactly("line1", "line2", "line3", "line4");
    }
  }

  @Nested
  @DisplayName("\\X (extended grapheme cluster)")
  class GraphemeClusterTests {

    @Test
    @DisplayName("\\X matches a single ASCII character")
    void matchesSingleAscii() {
      Pattern p = Pattern.compile("\\X");
      Matcher m = p.matcher("a");
      assertThat(m.matches()).isTrue();
    }

    @Test
    @DisplayName("\\X matches each character in ASCII text")
    void matchesEachAsciiChar() {
      Pattern p = Pattern.compile("\\X");
      Matcher m = p.matcher("abc");
      int count = 0;
      while (m.find()) {
        count++;
      }
      assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("\\X matches base character + combining marks as one cluster")
    void matchesBaseWithCombiningMark() {
      // é = e (U+0065) + combining acute accent (U+0301)
      String grapheme = "e\u0301";
      Pattern p = Pattern.compile("\\X");
      Matcher m = p.matcher(grapheme);
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo(grapheme);
      assertThat(m.group().length()).isEqualTo(2);
      assertThat(m.find()).isFalse(); // single grapheme, no more matches
    }

    @Test
    @DisplayName("\\X matches CRLF as a single grapheme cluster")
    void matchesCrLf() {
      Pattern p = Pattern.compile("\\X");
      Matcher m = p.matcher("\r\n");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("\r\n");
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("\\X matches a single newline")
    void matchesSingleNewline() {
      Pattern p = Pattern.compile("\\X");
      assertThat(p.matcher("\n").matches()).isTrue();
    }

    @Test
    @DisplayName("\\X matches a standalone combining mark as one cluster")
    void matchesStandaloneCombiningMark() {
      Pattern p = Pattern.compile("\\X");
      Matcher m = p.matcher("\u0301"); // combining acute accent alone
      assertThat(m.find()).isTrue();
      assertThat(m.group().length()).isEqualTo(1);
    }

    @Test
    @DisplayName("\\X groups leading combining marks before the next base character")
    void groupsLeadingCombiningMarksBeforeNextBaseCharacter() {
      String text = "\u0301\u0301a";
      Matcher m = Pattern.compile("\\X").matcher(text);

      assertThat(m.find()).isTrue();
      assertThat(m.start()).isZero();
      assertThat(m.end()).isEqualTo(2);
      assertThat(m.group()).isEqualTo("\u0301\u0301");

      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(2);
      assertThat(m.end()).isEqualTo(3);
      assertThat(m.group()).isEqualTo("a");

      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("\\X keeps ZWJ with leading grapheme extenders")
    void keepsZwjWithLeadingGraphemeExtenders() {
      assertTraceSameAsJdk("\\X", "\u0301\u200D", 0, 2);
      assertTraceSameAsJdk("\\X", "\u0301\u0301\u200D", 0, 3);
      assertTraceSameAsJdk("\\X", "\u0301\u200Da", 0, 3);
      assertTraceSameAsJdk("\\X", "a\u0301\u200D\uD83D\uDE00\u0300", 1, 5);
      assertTraceSameAsJdk("\\X\\X", "\u0301\u200Da", 0, 3);
      assertTraceSameAsJdk("\\X\\b{g}", "\u200D\uD83D\uDC69", 0, 3);
      assertTraceSameAsJdk("\\X\\b{g}", "\uDE00\u200D\uD83D\uDC69", 0, 4);
    }

    @Test
    @DisplayName("\\X repeated find respects region-local transparent bounds")
    void repeatedFindRespectsRegionLocalTransparentBounds() {
      assertTransparentTraceSameAsJdk("\\X", "aa\u0300", 1, 2);
      assertTransparentTraceSameAsJdk("\\X", "\uD83Da\u0301", 1, 2);
      assertTransparentTraceSameAsJdk("\\X", "\uD83Dab\u0301", 1, 3);
      assertTransparentTraceSameAsJdk("\\X", "\uD83D\uDE00", 1, 1);
      assertTransparentTraceSameAsJdk("\\X", "\uD83D\uDC69\u200D\uD83D\uDCBB", 1, 5);
      assertTransparentTraceSameAsJdk("\\X", "\uD83D\uDE00\u0301\uD83D\uDC69", 1, 5);
      assertTransparentTraceSameAsJdk("^\\X\\X", "\uD83D\uDE00\u200D\uD83D\uDC69", 1, 5);
      assertTransparentTraceSameAsJdk("^\\X\\X", "\uD83D\uDC69\u200D\uD83D\uDCBB", 1, 5);
    }

    @Test
    @DisplayName("\\X treats controls as standalone grapheme clusters")
    void treatsControlsAsStandaloneGraphemeClusters() {
      assertTraceSameAsJdk("\\X", "\r\u0301", 0, 2);
      assertTraceSameAsJdk("\\X", "\n\u0301", 0, 2);
      assertTraceSameAsJdk("\\X", "\u0000\u0301", 0, 2);
      assertTraceSameAsJdk("\\X", "\u0600\r", 0, 2);
      assertTraceSameAsJdk("\\X", "\u0600\n", 0, 2);
      assertTraceSameAsJdk("\\X", "\u0600\uDE00", 0, 2);
      assertTraceSameAsJdk("\\X", "\uD83D\u0301", 0, 2);
      assertTraceSameAsJdk("\\X", "\uD83D\u200D", 0, 2);
    }

    @Test
    @DisplayName("\\X keeps trailing extenders with non-base cluster forms")
    void keepsTrailingExtendersWithNonBaseClusterForms() {
      assertTraceSameAsJdk("\\X", "\uD83C\uDDFA\uD83C\uDDFA\u0301", 0, 5);
      assertTraceSameAsJdk("\\X", "\uD83C\uDDFA\uD83C\uDDFA\u200D", 0, 5);
      assertTraceSameAsJdk("\\X", "\uD83C\uDDFA\uD83C\uDDFA\uD83C\uDFFD", 0, 6);
      assertTraceSameAsJdk("\\X", "\u1100\u1161\u0301", 0, 3);
      assertTraceSameAsJdk("\\X", "\uAC00\u11A8\u0301", 0, 3);
    }

    @Test
    @DisplayName("\\X keeps prepend characters with following non-base cluster forms")
    void keepsPrependCharactersWithFollowingNonBaseClusterForms() {
      assertTraceSameAsJdk("\\X", "\u0600\uD83C\uDDFA\uD83C\uDDFA", 0, 5);
      assertTraceSameAsJdk("\\X", "\u0600\u1100\u1161", 0, 3);
      assertTraceSameAsJdk("\\X", "\u0600\uAC00\u11A8", 0, 3);
      assertTraceSameAsJdk("\\X", "\u0600\u0301", 0, 2);
      assertTraceSameAsJdk("\\X", "\u0600\uD83D\uDC69\u200D\uD83D\uDC69", 0, 6);
      assertTraceSameAsJdk("\\X\\b{g}", "\u0600\uD83D\uDC69\u200D\uD83D\uDC69", 0, 6);
    }

    @Test
    @DisplayName("\\X keeps ZWJ extenders inside pictographic sequences")
    void keepsZwjExtendersInsidePictographicSequences() {
      assertTraceSameAsJdk("\\X", "\uD83D\uDC69\u200D\u200D\uD83D\uDC69", 0, 6);
      assertTraceSameAsJdk("\\X", "\uD83D\uDC69\u200D\u0301\u200D\uD83D\uDC69", 0, 7);
      assertTraceSameAsJdk("\\X", "\uD83D\uDC69\u200D\uD83D\uDC69\u200D", 0, 6);
      assertTraceSameAsJdk("\\X", "\uD83D\uDC69\u200D\uD83D\uDC69\u200D\u0301", 0, 7);
      assertTraceSameAsJdk(
          "\\X\\X", "#\uDE00\uD83D\uDC69\u200D\uD83D\uDC69\u200D\uD83D\uDC69$", 1, 10);
    }

    @Test
    @DisplayName("repeated find with an internal grapheme boundary follows JDK CRLF state")
    void repeatedFindWithInternalGraphemeBoundaryFollowsJdkCrLfState() {
      assertTraceSameAsJdk("\\r\\b{g}\\n", "#\r\n\r\n$", 1, 5);
    }

    @Test
    @DisplayName("consecutive \\X atoms do not split leading extenders or Hangul clusters")
    void consecutiveAtomsDoNotSplitLeadingExtendersOrHangulClusters() {
      assertTraceSameAsJdk("\\X\\X", "\u200D\u0301", 0, 2);
      assertTraceSameAsJdk("\\X\\X", "\u200D\u200D", 0, 2);
      assertTraceSameAsJdk("\\X\\X", "\u1100\uAC01", 0, 2);
    }

    @Test
    @DisplayName("\\X treats standalone low surrogates as separate from following extenders")
    void treatsStandaloneLowSurrogatesAsSeparateFromFollowingExtenders() {
      assertTraceSameAsJdk("\\X", "\uDE00\u0301", 0, 2);
      assertTraceSameAsJdk("\\b{g}", "\uD83D\u0600\uD83D\uDE00", 1, 3);
      assertTraceSameAsJdk("\\b{g}", "#\uDE00\u200D\u0301$", 1, 4);
      assertTraceSameAsJdk("\\b{g}", "\uD83D\uDE00\u200D\uD83D\uDE00", 1, 4);
      assertFindGroupsSameAsJdk("(\\b{g})", "\uD83D\uDE00\u200D\uD83D\uDE00", 1, 4);
      assertTraceSameAsJdk("\\X\\b{g}", "\uD83D\uDE00\u200D\u0301\uDE00", 1, 4);
      assertTraceSameAsJdk("\\X\\b{g}", "\uD83D\uDE00\u200D\uD83C\uDDFA", 1, 5);
      assertTraceSameAsJdk("\\X\\b{g}", "\uD83D\uDE00\u0301\u200D\uD83C\uDDFA", 1, 6);
      assertTraceSameAsJdk("\\X\\b{g}", "\uD83D\uDE00\u200D\u0301\uD83C\uDDFA", 1, 6);
      assertTraceSameAsJdk("\\X\\b{g}", "\uD83D\uDE00\uD83C\uDFFD\uD83D\uDE00", 1, 6);
      assertTransparentTraceSameAsJdk("\\X\\b{g}", "#\u0301$", 1, 2);
      assertTransparentTraceSameAsJdk(
          "\\X\\b{g}", "\uD83D\u0000\u200D\u1100\u0600\uD83D\uDC69\u0903", 1, 7);
      assertTraceSameAsJdk("\\X\\X", "\r\uDE00\u0301", 0, 3);
      assertTraceSameAsJdk("\\X\\X", "\uDE00\uDE00\u0301", 0, 3);
      assertTraceSameAsJdk("\\X\\X", "#\uDE00\uD83D\uDC69\u200D\uD83D\uDC69\u0301$", 1, 8);
      assertTraceSameAsJdk("^\\X\\X$", "\uD83D\uDE00\r\r\uDE00", 1, 4);
      assertTraceSameAsJdk("^\\X\\X$", "\uD83D\uDE00a\u0903\r\n\uDE00", 1, 6);
      assertTransparentTraceSameAsJdk("\\b{g}a\\u0300\\b{g}", "aa\u0300\u0300", 1, 3);
    }

    @Test
    @DisplayName("\\X repeated find keeps valid split-surrogate region-local candidates")
    void repeatedFindKeepsValidSplitSurrogateRegionLocalCandidates() {
      assertTraceSameAsJdk("\\X", "\uD83D\uDE00\u200D\u0301", 1, 3);
      assertTraceSameAsJdk("\\X", "\uD83D\uDE00a\u0301", 1, 3);
    }

    @Test
    @DisplayName("consecutive \\X atoms preserve match bounds after leading combining marks")
    void consecutiveAtomsPreserveBoundsAfterLeadingCombiningMarks() {
      String text = "\u0301".repeat(44) + "a".repeat(8);
      Matcher m = Pattern.compile("^\\^?\\X\\X").matcher(text);

      assertThat(m.find()).isTrue();
      assertThat(m.start()).isZero();
      assertThat(m.end()).isEqualTo(45);
      assertThat(m.group()).isEqualTo("\u0301".repeat(44) + "a");
    }

    @Test
    @DisplayName("\\X matches multiple combining marks with a base character")
    void matchesMultipleCombiningMarks() {
      // a + combining tilde (U+0303) + combining acute accent (U+0301)
      String cluster = "a\u0303\u0301";
      Pattern p = Pattern.compile("\\X");
      Matcher m = p.matcher(cluster);
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo(cluster);
      assertThat(m.group().length()).isEqualTo(3);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("\\X correctly segments mixed grapheme clusters")
    void segmentsMixedClusters() {
      // "é" (e + combining accent) followed by "â" (a + combining circumflex)
      String text = "e\u0301a\u0302";
      Pattern p = Pattern.compile("\\X");
      Matcher m = p.matcher(text);

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("e\u0301");

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("a\u0302");

      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("\\X+ matches entire string of grapheme clusters")
    void plusMatchesMultipleClusters() {
      Pattern p = Pattern.compile("\\X+");
      assertThat(p.matcher("hello").matches()).isTrue();
      assertThat(p.matcher("e\u0301").matches()).isTrue();
      assertThat(p.matcher("e\u0301a\u0302").matches()).isTrue();
    }

    @Test
    @DisplayName("greedy \\X+ find consumes all adjacent clusters")
    void greedyPlusFindConsumesAllAdjacentClusters() {
      Matcher ascii = Pattern.compile("\\X+").matcher("ab");
      assertThat(ascii.find()).isTrue();
      assertThat(ascii.start()).isZero();
      assertThat(ascii.end()).isEqualTo(2);
      assertThat(ascii.find()).isFalse();

      Matcher combining = Pattern.compile("\\X+").matcher("a\u0301b");
      assertThat(combining.find()).isTrue();
      assertThat(combining.start()).isZero();
      assertThat(combining.end()).isEqualTo(3);
      assertThat(combining.group()).isEqualTo("a\u0301b");
      assertThat(combining.find()).isFalse();
    }

    @Test
    @DisplayName("ordered alternation preserves earlier branches before \\X")
    void orderedAlternationPreservesEarlierBranchesBeforeGraphemeCluster() {
      assertTraceSameAsJdk("a|\\X", "a\u0301b", 0, 3);
      assertFindGroupsSameAsJdk("(a)|(\\X)", "a\u0301b", 0, 3);
    }

    @Test
    @DisabledForCrosscheck(
        "SafeRE's region-local grapheme model intentionally diverges from selected JDK traces")
    @DisplayName("repeated find resumes after leading grapheme-boundary clusters")
    void repeatedFindResumesAfterLeadingGraphemeBoundaryClusters() {
      assertSafeReFindBounds("\\b{g}\\X", "ab", 0, 2, List.of("0-1", "1-2"));
      assertSafeReFindBounds("\\b{g}\\X", "a\u0301b", 0, 3, List.of("0-2", "2-3"));
      assertSafeReFindBounds("(\\b{g})(\\X)", "ab", 0, 2, List.of("0-1", "1-2"));
    }

    @Test
    @DisplayName("\\X inside [...] is rejected")
    void rejectedInsideCharClass() {
      assertThatThrownBy(() -> Pattern.compile("[\\X]")).isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    @DisplayName("\\X works with quantifiers")
    void worksWithQuantifiers() {
      // Two grapheme clusters
      Pattern p = Pattern.compile("\\X{2}");
      assertThat(p.matcher("ab").matches()).isTrue();
      assertThat(p.matcher("e\u0301a\u0302").matches()).isTrue();
      assertThat(p.matcher("e\u0301").matches()).isFalse();
      assertThat(p.matcher("a").matches()).isFalse();
    }

    @Test
    @DisplayName("unanchored \\X chains can start at UTF-16 low-surrogate offsets")
    void unanchoredChainsCanStartAtLowSurrogateOffsets() {
      assertFirstFindBounds("\\X\\X", "\uD83C\uDDFA\uD83C\uDDF8", 1, 4);
      assertFirstFindBounds("\\X{2}", "\uD83C\uDDFA\uD83C\uDDF8", 1, 4);
      assertFirstFindBounds("(?:\\X)(?:\\X)", "\uD83D\uDC4D\uD83C\uDFFD", 1, 4);
      assertFirstFindBounds("(\\X)(\\X)", "\uD83D\uDC69\u200D\uD83D\uDCBB", 1, 3);
      assertTraceSameAsJdk("\\X\\X\\X", "a\uD83D\uDC69\u200D\uD83D\uDC69", 0, 6);
      assertTraceSameAsJdk("\\X{3}", "a\uD83D\uDC69\u200D\uD83D\uDC69", 0, 6);
      assertTraceSameAsJdk("(?:\\X)(?:\\X)(?:\\X)", "a\uD83D\uDC69\u200D\uD83D\uDC69", 0, 6);
    }

    @Test
    @DisplayName("end-anchored \\X searches keep correct long-input bounds")
    void endAnchoredGraphemeSearchesKeepCorrectLongInputBounds() {
      assertFirstFindBounds("\\Xz$", "a".repeat(2_000) + "z", 1_999, 2_001);
    }

    @Test
    @DisplayName("unanchored multi-boundary \\X search allocation does not scale with input tail")
    void unanchoredMultiBoundarySearchDoesNotAllocatePerInputPosition() {
      AllocationTracker allocationTracker = allocationTracker();
      long threadId = Thread.currentThread().threadId();

      for (String regex : List.of("\\X\\X", "\\X{2}", "(?:\\X)(?:\\X)")) {
        Pattern pattern = Pattern.compile(regex);

        assertImmediateTwoClusterMatch(pattern, "ab" + "c".repeat(1_000));
        assertImmediateTwoClusterMatch(pattern, "ab" + "c".repeat(10_000));

        long shortAllocated =
            allocatedForImmediateTwoClusterFind(
                allocationTracker, threadId, pattern, "ab" + "c".repeat(10_000));
        long longAllocated =
            allocatedForImmediateTwoClusterFind(
                allocationTracker, threadId, pattern, "ab" + "c".repeat(100_000));

        assertThat(longAllocated - shortAllocated)
            .as("extra allocated bytes when input tail grows for /%s/", regex)
            .isLessThan(64_000L);
      }
    }

    @Test
    @DisabledForCrosscheck("java.util.regex is not the SafeRE linear-time engine")
    @DisplayName("repeated find() over grapheme clusters reuses per-input context")
    void repeatedFindOverGraphemeClustersReusesPerInputContext() {
      Pattern pattern = Pattern.compile("\\X");

      assertFourXInputStaysNearLinear(
          "repeated find() over grapheme clusters",
          length -> {
            Matcher matcher = pattern.matcher("a".repeat(length));
            int count = 0;
            while (matcher.find()) {
              count++;
            }
            assertThat(count).isEqualTo(length);
          });
    }

    @Test
    @DisabledForCrosscheck("java.util.regex is not the SafeRE linear-time engine")
    @DisplayName("JDK-compatible low-surrogate search positions stay near-linear")
    void lowSurrogateSearchPositionsStayNearLinear() {
      Pattern pattern = Pattern.compile(".*\\b{g}z");

      assertFourXInputStaysNearLinear(
          "low-surrogate search-position miss",
          length -> assertThat(pattern.matcher("\uDC00".repeat(length)).find()).isFalse());
    }

    @Test
    @DisabledForCrosscheck("java.util.regex is not the SafeRE linear-time engine")
    @DisplayName("regional-indicator grapheme boundary misses stay near-linear")
    void regionalIndicatorBoundaryMissesStayNearLinear() {
      Pattern pattern = Pattern.compile("\\b{g}z");

      assertFourXInputStaysNearLinear(
          "regional-indicator boundary miss",
          length -> assertThat(pattern.matcher("\uD83C\uDDE6".repeat(length)).find()).isFalse());
    }

    @Test
    @DisabledForCrosscheck("java.util.regex is not the SafeRE linear-time engine")
    @DisplayName("failed unanchored \\X suffix misses stay near-linear")
    void failedUnanchoredGraphemeSuffixMissesStayNearLinear() {
      Pattern pattern = Pattern.compile("\\Xz");

      assertFourXInputStaysNearLinear(
          "failed unanchored \\X suffix miss",
          length -> assertThat(pattern.matcher("a" + "\u0301".repeat(length)).find()).isFalse());
      assertFourXInputStaysNearLinear(
          "failed unanchored regional-indicator \\X suffix miss",
          length -> assertThat(pattern.matcher("\uD83C\uDDE6".repeat(length)).find()).isFalse());
    }

    @Test
    @DisplayName("consecutive \\X atoms do not split a single grapheme cluster")
    void consecutiveAtomsDoNotSplitSingleCluster() {
      Pattern p = Pattern.compile("^\\X\\X$");

      assertThat(p.matcher("e\u0301").matches()).isFalse();
      assertThat(p.matcher("\uD83D\uDC4D\uD83C\uDFFD").matches()).isFalse();
      assertThat(p.matcher("\uD83C\uDDFA\uD83C\uDDF8").matches()).isFalse();
      assertThat(p.matcher("\u0600a").matches()).isFalse();
      assertThat(p.matcher("\u1100\u1161").matches()).isFalse();
      assertThat(p.matcher("e\u0301a").matches()).isTrue();
    }

    @Test
    @DisplayName("\\X matches control characters")
    void matchesControlChars() {
      Pattern p = Pattern.compile("\\X");
      assertThat(p.matcher("\t").matches()).isTrue();
      assertThat(p.matcher("\u0000").matches()).isTrue();
    }

    @Test
    @DisplayName("\\X works with capture groups")
    void captureGroup() {
      Pattern p = Pattern.compile("(\\X)");
      Matcher m = p.matcher("e\u0301");
      assertThat(m.find()).isTrue();
      assertThat(m.group(1)).isEqualTo("e\u0301");
    }

    @Test
    @DisplayName("\\X matches supplementary code points")
    void matchesSupplementaryCodePoint() {
      // U+1F600 GRINNING FACE (surrogate pair in Java)
      String emoji = "\uD83D\uDE00";
      Pattern p = Pattern.compile("\\X");
      Matcher m = p.matcher(emoji);
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo(emoji);
    }

    @Test
    @DisplayName("\\X matches a regional indicator flag as one cluster")
    void matchesRegionalIndicatorFlagAsOneCluster() {
      String flag = "\uD83C\uDDFA\uD83C\uDDF8"; // US flag
      Matcher m = Pattern.compile("\\X").matcher(flag);

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo(flag);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("\\X segments regional indicators in pairs")
    void segmentsRegionalIndicatorsInPairs() {
      String regionalIndicators = "\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDE8"; // U, S, C
      Matcher m = Pattern.compile("\\X").matcher(regionalIndicators);

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("\uD83C\uDDFA\uD83C\uDDF8");

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("\uD83C\uDDE8");

      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("opaque regions reset regional-indicator grapheme parity")
    void opaqueRegionsResetRegionalIndicatorGraphemeParity() {
      String regionalIndicators = "\uD83C\uDDE6".repeat(3);

      assertTraceSameAsJdk("\\X", regionalIndicators, 2, 6);
      assertTraceSameAsJdk("\\b{g}", regionalIndicators, 2, 6);
      assertTraceSameAsJdk("\\b{g}\\X", regionalIndicators, 2, 6);
      assertTraceSameAsJdk("\\b{g}\\X\\b{g}", regionalIndicators, 2, 6);
    }

    @Test
    @DisplayName("transparent boundaries keep full-input regional-indicator parity")
    void transparentBoundariesKeepFullInputRegionalIndicatorParity() {
      String regionalIndicators = "\uD83C\uDDE6".repeat(3);

      assertTransparentTraceSameAsJdk("\\b{g}", regionalIndicators, 2, 6);
      assertTransparentTraceSameAsJdk("\\b{g}\\X", regionalIndicators, 2, 6);
    }

    @Test
    @DisplayName("\\X matches emoji modifier sequences as one cluster")
    void matchesEmojiModifierSequenceAsOneCluster() {
      String thumbsUpMediumSkinTone = "\uD83D\uDC4D\uD83C\uDFFD";
      Matcher m = Pattern.compile("\\X").matcher(thumbsUpMediumSkinTone);

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo(thumbsUpMediumSkinTone);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("\\X matches ZWJ emoji sequences as one cluster")
    void matchesZwjEmojiSequenceAsOneCluster() {
      String technologist = "\uD83D\uDC69\u200D\uD83D\uDCBB";
      Matcher m = Pattern.compile("\\X").matcher(technologist);

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo(technologist);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("\\X matches ZWJ emoji sequences with modifiers as one cluster")
    void matchesZwjEmojiSequenceWithModifierAsOneCluster() {
      String technologist = "\uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDCBB";
      Matcher m = Pattern.compile("\\X").matcher(technologist);

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo(technologist);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("\\X keeps ZWJ attached to the preceding cluster")
    void keepsZwjAttachedToPrecedingCluster() {
      String joined = "a\u200D";
      Matcher m = Pattern.compile("\\X").matcher(joined);

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo(joined);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("\\X matches Hangul jamo sequences as one cluster")
    void matchesHangulJamoSequenceAsOneCluster() {
      String hangul = "\u1100\u1161";
      Matcher m = Pattern.compile("\\X").matcher(hangul);

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo(hangul);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("\\X keeps trailing Hangul jongseong with a syllable")
    void keepsTrailingHangulJongseongWithSyllable() {
      String hangul = "\uAC00\u11A8";
      Matcher m = Pattern.compile("\\X").matcher(hangul);

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo(hangul);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("\\X keeps prepend characters with the following cluster")
    void keepsPrependCharactersWithFollowingCluster() {
      String cluster = "\u0600a";
      Matcher m = Pattern.compile("\\X").matcher(cluster);

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo(cluster);
      assertThat(m.find()).isFalse();
    }

    @Test
    @DisplayName("find() locates grapheme clusters in mixed text")
    void findInMixedText() {
      // "He\u0301llo" = H + é (2 code points) + l + l + o → 4 grapheme clusters
      String text = "He\u0301llo";
      Pattern p = Pattern.compile("\\X");
      List<String> clusters = new ArrayList<>();
      Matcher m = p.matcher(text);
      while (m.find()) {
        clusters.add(m.group());
      }
      assertThat(clusters).containsExactly("H", "e\u0301", "l", "l", "o");
    }

    private static void assertFirstFindBounds(String regex, String input, int start, int end) {
      Matcher matcher = Pattern.compile(regex).matcher(input);
      assertThat(matcher.find()).as("first find() for /%s/ on %s", regex, input).isTrue();
      assertThat(matcher.start())
          .as("first find() start for /%s/ on %s", regex, input)
          .isEqualTo(start);
      assertThat(matcher.end()).as("first find() end for /%s/ on %s", regex, input).isEqualTo(end);
      assertThat(matcher.find()).as("second find() for /%s/ on %s", regex, input).isFalse();
    }

    private static void assertFindGroupsSameAsJdk(String regex, String input, int start, int end) {
      java.util.regex.Matcher jdkMatcher =
          java.util.regex.Pattern.compile(regex).matcher(input).region(start, end);
      Matcher safeMatcher = Pattern.compile(regex).matcher(input).region(start, end);

      assertThat(safeMatcher.find()).as("SafeRE find() should match").isEqualTo(jdkMatcher.find());
      assertThat(safeMatcher.groupCount()).isEqualTo(jdkMatcher.groupCount());
      for (int group = 0; group <= jdkMatcher.groupCount(); group++) {
        assertThat(safeMatcher.group(group))
            .as("group %s for /%s/ on %s region [%s,%s]", group, regex, input, start, end)
            .isEqualTo(jdkMatcher.group(group));
      }
    }

    private static void assertTraceSameAsJdk(String regex, String input, int start, int end) {
      java.util.regex.Matcher jdkMatcher =
          java.util.regex.Pattern.compile(regex).matcher(input).region(start, end);
      Matcher safeMatcher = Pattern.compile(regex).matcher(input).region(start, end);

      assertThat(safeMatcher.matches())
          .as("matches() for /%s/ on %s region [%s,%s]", regex, input, start, end)
          .isEqualTo(jdkMatcher.matches());

      jdkMatcher.reset(input).region(start, end);
      safeMatcher.reset(input).region(start, end);
      assertThat(safeMatcher.lookingAt())
          .as("lookingAt() for /%s/ on %s region [%s,%s]", regex, input, start, end)
          .isEqualTo(jdkMatcher.lookingAt());

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

    private static void assertSafeReFindBounds(
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

    private static void assertTransparentTraceSameAsJdk(
        String regex, String input, int start, int end) {
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
      assertThat(safeMatcher.lookingAt())
          .as("transparent lookingAt() for /%s/ on %s region [%s,%s]", regex, input, start, end)
          .isEqualTo(jdkMatcher.lookingAt());

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

    private static long allocatedForImmediateTwoClusterFind(
        AllocationTracker allocationTracker, long threadId, Pattern pattern, String text) {
      long before = allocationTracker.allocatedBytes(threadId);
      assertImmediateTwoClusterMatch(pattern, text);
      return allocationTracker.allocatedBytes(threadId) - before;
    }

    private static void assertImmediateTwoClusterMatch(Pattern pattern, String text) {
      Matcher matcher = pattern.matcher(text);
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.start()).isZero();
      assertThat(matcher.end()).isEqualTo(2);
    }

    private static void assertFourXInputStaysNearLinear(String scenario, IntConsumer task) {
      task.accept(1_000);
      long smallerNanos = bestRuntimeNanos(() -> task.accept(5_000));
      long largerNanos = bestRuntimeNanos(() -> task.accept(20_000));

      assertThat(largerNanos)
          .as(
              "%s: 4x input should stay near-linear, smaller=%dns larger=%dns",
              scenario, smallerNanos, largerNanos)
          .isLessThan(smallerNanos * 10);
    }

    private static long bestRuntimeNanos(Runnable task) {
      long best = Long.MAX_VALUE;
      for (int i = 0; i < 3; i++) {
        best = Math.min(best, runtimeNanos(task));
      }
      return best;
    }

    private static long runtimeNanos(Runnable task) {
      return assertTimeoutPreemptively(
          PERFORMANCE_SCENARIO_TIMEOUT,
          () -> {
            long start = System.nanoTime();
            task.run();
            return System.nanoTime() - start;
          });
    }

    private static AllocationTracker allocationTracker() {
      try {
        Class<?> managementFactoryClass = Class.forName("java.lang.management.ManagementFactory");
        Object threadBean = managementFactoryClass.getMethod("getThreadMXBean").invoke(null);
        Class<?> allocationBeanClass = Class.forName("com.sun.management.ThreadMXBean");
        Assumptions.assumeTrue(allocationBeanClass.isInstance(threadBean));

        Method supportedMethod = allocationBeanClass.getMethod("isThreadAllocatedMemorySupported");
        Method enabledMethod = allocationBeanClass.getMethod("isThreadAllocatedMemoryEnabled");
        Method setEnabledMethod =
            allocationBeanClass.getMethod("setThreadAllocatedMemoryEnabled", boolean.class);
        Method allocatedBytesMethod =
            allocationBeanClass.getMethod("getThreadAllocatedBytes", long.class);

        Assumptions.assumeTrue((Boolean) supportedMethod.invoke(threadBean));
        if (!(Boolean) enabledMethod.invoke(threadBean)) {
          setEnabledMethod.invoke(threadBean, true);
        }
        return new AllocationTracker(threadBean, allocatedBytesMethod);
      } catch (ReflectiveOperationException e) {
        Assumptions.abort("thread allocation tracking is unavailable: " + e);
        throw new AssertionError("unreachable");
      }
    }

    private record AllocationTracker(Object threadBean, Method allocatedBytesMethod) {
      long allocatedBytes(long threadId) {
        try {
          return (Long) allocatedBytesMethod.invoke(threadBean, threadId);
        } catch (IllegalAccessException e) {
          throw new AssertionError(e);
        } catch (InvocationTargetException e) {
          throw new AssertionError(e.getCause());
        }
      }
    }
  }
}
