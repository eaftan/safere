// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class CountedRepetitionQuotationTest {
  private static final String EMPTY_QUOTE = "\\Q\\E";

  @ParameterizedTest
  @MethodSource("countsWithEmptyQuotes")
  void emptyQuotesDisappearThroughoutCountedRepeatTokens(String count) {
    // JDK 26 documents quotes and repetition separately; its lexical interaction is unspecified.
    // Match observed JDK behavior, including gaps within numbers and before delimiters. Refs #1060.
    for (String operand : List.of("a", "(a)", "[a]", "\\Qa\\E", "😀", "()", "")) {
      for (String modifier : List.of("", "?", EMPTY_QUOTE + "?")) {
        String regex = operand + count + modifier;
        assertSameMatches(regex, 0);
        assertSameMatches(regex, Pattern.COMMENTS);
      }
    }
  }

  static Stream<String> countsWithEmptyQuotes() {
    return Stream.of("{02}", "{12}", "{1,2}", "{01,03}", "{01,}", "{00,02}")
        .flatMap(
            count ->
                Stream.of(EMPTY_QUOTE, EMPTY_QUOTE.repeat(3))
                    .flatMap(
                        gap -> {
                          Stream.Builder<String> variants = Stream.builder();
                          StringBuilder allGaps = new StringBuilder("{");
                          for (int i = 1; i < count.length(); i++) {
                            variants.add(count.substring(0, i) + gap + count.substring(i));
                            allGaps.append(gap).append(count.charAt(i));
                          }
                          variants.add(allGaps.toString());
                          return variants.build();
                        }));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "a{1 \\Q\\E, #count\n \\Q\\E3}",
        "a{1\\Q\\E #count\n ,3\\Q\\E }\\Q\\E ?",
        "a{1, #count\n \\Q\\E}"
      })
  void emptyQuotesInterleaveWithCommentsAfterTheFirstDigit(String regex) {
    assertSameMatches(regex, Pattern.COMMENTS);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "a{\\Q\\E}",
        "a{\\Q\\E,2}",
        "a{1\\Q\\E,0}",
        "a{1\\Q\\E",
        "a{1\\Q",
        "a{1,2\\Q\\E,3}",
        "a{1\\Q2\\E}",
        "a{1,\\Q2\\E}",
        "a{1\\Q,\\E2}",
        "a{1\\Q}\\E",
        "a{1\\Q\\E}?+",
        "a{\\Q\\E+1}",
        "a{1,\\Q\\E-2}",
        "a{\\Q\\E١}",
        "a{21474\\Q\\E83648}",
        "a{1,21474\\Q\\E83648}"
      })
  void emptyQuotesDoNotMakeMalformedCountsOrNonemptyQuotesValid(String regex) {
    assertThatThrownBy(() -> java.util.regex.Pattern.compile(regex))
        .isInstanceOf(PatternSyntaxException.class);
    assertThatThrownBy(() -> Pattern.compile(regex)).isInstanceOf(PatternSyntaxException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"a{ \\Q\\E1}", "a{\\Q\\E 1}", "a{\\Q\\E#c\n1}"})
  void commentsTriviaBeforeTheFirstCountDigitIsStillRejected(String regex) {
    assertThatThrownBy(() -> java.util.regex.Pattern.compile(regex, Pattern.COMMENTS))
        .isInstanceOf(PatternSyntaxException.class);
    assertThatThrownBy(() -> Pattern.compile(regex, Pattern.COMMENTS))
        .isInstanceOf(PatternSyntaxException.class);
  }

  @Test
  @DisabledForCrosscheck("SafeRE rejects consuming possessive quantifiers")
  void emptyQuotesDoNotBypassConsumingPossessiveRejection() {
    for (String regex : List.of("a{1\\Q\\E,3}+", "a{1,3\\Q\\E}\\Q\\E+")) {
      java.util.regex.Pattern.compile(regex);
      assertThatThrownBy(() -> Pattern.compile(regex))
          .isInstanceOf(PatternSyntaxException.class)
          .hasMessageContaining("possessive");
    }
  }

  @Test
  @DisabledForCrosscheck("SafeRE rejects bounds over its repeat budget")
  void emptyQuotesDoNotBypassRepeatBudgets() {
    for (String regex :
        List.of("a{1\\Q\\E001}", "a{0,1\\Q\\E001}", "(?:a{1\\Q\\E,100}){1\\Q\\E,100}")) {
      java.util.regex.Pattern.compile(regex);
      assertThatThrownBy(() -> Pattern.compile(regex))
          .isInstanceOf(PatternSyntaxException.class)
          .hasMessageContaining("invalid repeat count");
    }
  }

  @Test
  void zeroWidthPossessiveAndClassLiteralsKeepTheirMeaning() {
    for (String regex :
        List.of("(){1\\Q\\E,3}\\Q\\E+", "{1\\Q\\E,3}+", "[a{1\\Q\\E,3}]", "\\Qa{1\\Q\\E,3}")) {
      assertSameMatches(regex, 0);
    }
  }

  @Test
  void longRunsOfEmptyQuotesAndLeadingZerosAreStackSafe() {
    String regex =
        "a{"
            + EMPTY_QUOTE.repeat(20_000)
            + "0".repeat(20_000)
            + EMPTY_QUOTE.repeat(20_000)
            + "1,2"
            + EMPTY_QUOTE.repeat(20_000)
            + "}";
    assertSameMatches(regex, 0);
  }

  private static void assertSameMatches(String regex, int flags) {
    var expectedPattern = java.util.regex.Pattern.compile(regex, flags);
    var actualPattern = Pattern.compile(regex, flags);
    for (String input :
        List.of("", "a", "aa", "aaa", "aaaa", "baaaabaaa", "😀😀😀", "a".repeat(13), "{1,3}")) {
      var expected = expectedPattern.matcher(input);
      var actual = actualPattern.matcher(input);
      assertThat(actual.matches())
          .as("matches /%s/ on %s", regex, input)
          .isEqualTo(expected.matches());
      expected.reset();
      actual.reset();
      while (true) {
        boolean found = expected.find();
        assertThat(actual.find()).as("find /%s/ on %s", regex, input).isEqualTo(found);
        if (!found) break;
        for (int group = 0; group <= expected.groupCount(); group++) {
          assertThat(actual.start(group)).isEqualTo(expected.start(group));
          assertThat(actual.end(group)).isEqualTo(expected.end(group));
          assertThat(actual.group(group)).isEqualTo(expected.group(group));
        }
      }
    }
  }
}
