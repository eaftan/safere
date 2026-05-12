// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@code \R} (Unicode linebreak) and {@code \X} (extended grapheme cluster) support.
 * Validates behavior against JDK 21's {@link java.util.regex.Pattern} specification.
 */
class LinebreakGraphemeTest {

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
    @DisplayName("unanchored consecutive \\X atoms follow JDK search positions")
    void unanchoredConsecutiveAtomsFollowJdkSearchPositions() {
      assertFindBoundsSameAsJdk("\\X\\X", "\uD83C\uDDFA\uD83C\uDDF8");
      assertFindBoundsSameAsJdk("\\X{2}", "\uD83D\uDC4D\uD83C\uDFFD");
      assertFindBoundsSameAsJdk("(\\X)(\\X)", "\uD83D\uDC69\u200D\uD83D\uDCBB");
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
    @DisplayName("consecutive \\X atoms respect regions split inside surrogate pairs")
    void consecutiveAtomsRespectRegionsSplitInsideSurrogatePairs() {
      String regionEndsInsidePair = "\uDE00\uD83D\uDE00";
      assertTraceSameAsJdk("^\\X\\X$", regionEndsInsidePair, 0, 2);
      assertTraceSameAsJdk("\\X{2}", regionEndsInsidePair, 0, 2);

      String regionStartsInsidePair = "\uD83D\uDE00\uD83D\uDE01";
      assertTraceSameAsJdk("\\X{2}", regionStartsInsidePair, 1, 4);
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

    private void assertFindBoundsSameAsJdk(String regex, String input) {
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
  }
}
