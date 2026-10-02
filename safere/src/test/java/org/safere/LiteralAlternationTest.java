// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("compares the optimized and forced fallback paths with the JDK")
class LiteralAlternationTest {
  private static final List<String> PATTERNS =
      List.of(
          "apple|banana|cherry",
          "blossom|sparkling|gardens",
          "foo|barfoo",
          "ab|bcd",
          "red|green|blue|yellow|orange|purple",
          "ab|abc",
          "abc|ab",
          "ab|cherry|abc",
          "abc|cherry|ab",
          "(apple)|banana",
          "(?i)apple|banana",
          "apple|banana$",
          "apple|",
          "éclair|😀");

  @Test
  void onlyCompleteCaptureFreeAsciiAlternationsSelectTheRunner() {
    for (String regex :
        List.of(
            "apple|banana|cherry",
            "foo|barfoo",
            "red|green|blue",
            "ab|cherry|abc",
            "abc|cherry|ab")) {
      assertThat(Pattern.compile(regex).preparedMatchRunner(false).getClass().getSimpleName())
          .as(regex)
          .isEqualTo("LiteralAlternationPreparedRunner");
    }
    for (String regex :
        List.of(
            "(apple)|banana",
            "(?i)apple|banana",
            "apple|banana$",
            "apple|",
            "éclair|😀",
            "(?:apple|banana)+",
            "gardening|gardener|gardens")) {
      assertThat(Pattern.compile(regex).preparedMatchRunner(false).getClass().getSimpleName())
          .as(regex)
          .isNotEqualTo("LiteralAlternationPreparedRunner");
    }
  }

  @Test
  void longAlternativesRetainTheExistingRunner() {
    for (int length : new int[] {13, 16, 17, 65, 4_097}) {
      String literal = "a".repeat(length - 1) + "z";
      for (String regex :
          List.of(
              literal + "|banana|cherry|durian|elderberry|fig",
              literal + "|banana|cherry",
              "banana|" + literal + "|cherry")) {
        Pattern pattern = Pattern.compile(regex);
        Pattern fallback =
            Pattern.compile(regex, 0, EnginePathOptions.builder().literalFastPaths(false).build());
        assertThat(pattern.preparedMatchRunner(false).getClass())
            .as(regex)
            .isEqualTo(fallback.preparedMatchRunner(false).getClass());
        for (String suffix : List.of("", "banana", "z")) {
          String text = "a".repeat(8_192) + suffix;
          compareFinds(pattern.matcher(text), java.util.regex.Pattern.compile(regex).matcher(text));
        }
      }
    }
  }

  @Test
  void searchSequencesAndReplacementsAgreeWithJdkAndFallback() {
    for (String regex : PATTERNS) {
      Pattern optimized = Pattern.compile(regex);
      Pattern fallback =
          Pattern.compile(regex, 0, EnginePathOptions.builder().literalFastPaths(false).build());
      for (int offset : new int[] {0, 1, 255, 256, 1_023, 1_024, 4_095, 4_096, 8_192}) {
        String text =
            "x".repeat(offset)
                + "barfoo banana gardens abc red 😀éclair apple"
                + "x".repeat(5_000)
                + "cherry blue banana";
        for (Pattern pattern : List.of(optimized, fallback)) {
          compareFinds(pattern.matcher(text), java.util.regex.Pattern.compile(regex).matcher(text));
          assertThat(pattern.matcher(text).replaceAll("<$0>"))
              .as("replacement %s offset %s", regex, offset)
              .isEqualTo(java.util.regex.Pattern.compile(regex).matcher(text).replaceAll("<$0>"));
          assertThat(pattern.matcher(text).replaceFirst("<$0>"))
              .isEqualTo(java.util.regex.Pattern.compile(regex).matcher(text).replaceFirst("<$0>"));
          Matcher actual = pattern.matcher(text);
          java.util.regex.Matcher expected = java.util.regex.Pattern.compile(regex).matcher(text);
          assertThat(actual.matches()).isEqualTo(expected.matches());
          assertThat(actual.lookingAt()).isEqualTo(expected.lookingAt());
          compareFinds(actual, expected);
          assertThat(actual.find(offset)).isEqualTo(expected.find(offset));
          compareFinds(actual, expected);
        }
      }
    }
  }

  @Test
  void regionsAndNonAsciiPaddingPreserveBounds() {
    for (String regex : List.of("apple|banana|cherry", "foo|barfoo", "ab|abc", "abc|ab")) {
      for (String padding : List.of("x", "é", "😀", "\uD800", "\uDC00")) {
        String text = padding.repeat(1_024) + "barfoo banana apple abc cherry" + padding;
        for (boolean transparent : new boolean[] {false, true}) {
          for (boolean anchoring : new boolean[] {false, true}) {
            for (int start : new int[] {0, 1, padding.length() * 1_024 + 3}) {
              Matcher actual =
                  Pattern.compile(regex)
                      .matcher(text)
                      .region(start, text.length() - 1)
                      .useTransparentBounds(transparent)
                      .useAnchoringBounds(anchoring);
              java.util.regex.Matcher expected =
                  java.util.regex.Pattern.compile(regex)
                      .matcher(text)
                      .region(start, text.length() - 1)
                      .useTransparentBounds(transparent)
                      .useAnchoringBounds(anchoring);
              compareFinds(actual, expected);
            }
          }
        }
      }
    }
  }

  @Test
  void growingWindowBoundariesPreserveLeftmostMatchAndAlternativePriority() {
    for (String regex :
        List.of(
            "ab|cherry|abc",
            "abc|cherry|ab",
            "ab|durian|cherry|elderberry|fig|abc",
            "abc|durian|cherry|elderberry|fig|ab")) {
      Pattern pattern = Pattern.compile(regex);
      assertThat(pattern.preparedMatchRunner(false))
          .isInstanceOf(Matcher.LiteralAlternationPreparedRunner.class);
      for (int boundary :
          new int[] {256, 768, 1_024, 1_792, 3_072, 3_840, 7_168, 7_936, 15_360, 31_744}) {
        for (int offset : new int[] {boundary - 1, boundary, boundary + 1}) {
          String text = "x".repeat(offset) + "abc" + "x".repeat(65_536) + "cherry";
          compareFinds(pattern.matcher(text), java.util.regex.Pattern.compile(regex).matcher(text));
        }
      }
    }
  }

  @Test
  void longLiteralsCrossingTheProbeBoundaryStillSelectTheLeftmostMatch() {
    String literal = "a".repeat(2_048) + "z";
    for (String regex :
        List.of(literal + "|banana|cherry", literal + "|banana|cherry|durian|elderberry|fig")) {
      for (int offset : new int[] {255, 256, 1_023, 1_024, 4_095, 4_096}) {
        for (String prefix : List.of("x".repeat(offset), "banana" + "x".repeat(offset))) {
          String text = prefix + literal + "x".repeat(8_192) + "cherry";
          compareFinds(
              Pattern.compile(regex).matcher(text),
              java.util.regex.Pattern.compile(regex).matcher(text));
        }
      }
    }
  }

  @Test
  void literalAlternationsAdvertiseLiteralCapability() {
    Pattern pattern = Pattern.compile("apple|banana|cherry");
    assertThat(pattern.analysis().capabilities()).contains(PatternCapability.LITERAL_MATCH);
  }

  @Test
  void fullAndPrefixMatchesPreserveTheExistingRunnerSemantics() {
    for (String regex : PATTERNS) {
      for (String text : List.of("", "apple", "banana", "cherry", "barfoo", "ab", "abc")) {
        Matcher actual = Pattern.compile(regex).matcher(text);
        java.util.regex.Matcher expected = java.util.regex.Pattern.compile(regex).matcher(text);
        assertThat(actual.matches()).isEqualTo(expected.matches());
        assertThat(actual.lookingAt()).isEqualTo(expected.lookingAt());
        compareFinds(actual, expected);
      }
    }
  }

  private static void compareFinds(Matcher actual, java.util.regex.Matcher expected) {
    while (true) {
      boolean found = expected.find();
      assertThat(actual.find()).isEqualTo(found);
      if (!found) break;
      assertThat(actual.start()).isEqualTo(expected.start());
      assertThat(actual.end()).isEqualTo(expected.end());
      assertThat(actual.toMatchResult().group()).isEqualTo(expected.toMatchResult().group());
      for (int group = 0; group <= expected.groupCount(); group++) {
        assertThat(actual.group(group)).isEqualTo(expected.group(group));
      }
    }
  }
}
