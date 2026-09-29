// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for SafeRE's documented Unicode case-folding model where it intentionally diverges from
 * selected JDK range traces.
 */
@DisabledForCrosscheck(
    "SafeRE's documented Unicode case-folding model intentionally diverges from selected JDK range"
        + " traces")
class UnicodeCaseFoldingModelTest {

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("closedRangeCases")
  @DisplayName("Unicode case-insensitive ranges are closed under case folding")
  void unicodeCaseInsensitiveRangesAreClosedUnderCaseFolding(
      String description, String regex, String input) {
    Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    assertThat(pattern.matcher(input).matches()).as(description).isTrue();
  }

  @Test
  void caseFamiliesAreReciprocalAcrossSyntaxAndFlags() {
    String[] families = {
      "Iiİı",
      "KkK",
      "Ssſ",
      "Σσς",
      "ÅåÅ",
      "ΩωΩ",
      "ßẞ",
      "Ǆǅǆ",
      "Θθϑϴ",
      "\u0390\u1FD3",
      "\u03B0\u1FE3",
      "\uFB05\uFB06",
      "\uD801\uDC00\uD801\uDC28"
    };
    int[] flags = {
      0,
      Pattern.CASE_INSENSITIVE,
      Pattern.UNICODE_CASE,
      Pattern.UNICODE_CHARACTER_CLASS,
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE,
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    };
    for (String family : families) {
      for (int source : family.codePoints().toArray()) {
        String literal = "\\x{" + Integer.toHexString(source) + "}";
        for (int flag : flags) {
          for (String regex :
              new String[] {
                literal,
                "[" + literal + "]",
                "[" + literal + "-" + literal + "]",
                "[^" + literal + "]",
                "[^" + literal + "-" + literal + "]"
              }) {
            Pattern pattern = Pattern.compile(regex, flag);
            for (int target : (family + "!").codePoints().toArray()) {
              boolean insensitive = (flag & Pattern.CASE_INSENSITIVE) != 0;
              boolean unicode =
                  (flag & (Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS)) != 0;
              boolean member =
                  source == target
                      || (insensitive
                          && ((unicode && target != '!')
                              || (source < 128
                                  && target < 128
                                  && Character.toLowerCase(source)
                                      == Character.toLowerCase(target))));
              assertThat(pattern.matcher(new String(Character.toChars(target))).matches())
                  .as("%s flags=%s input=U+%04X", regex, flag, target)
                  .isEqualTo(regex.startsWith("[^") ? !member : member);
            }
          }
        }
      }
    }
  }

  @Test
  void largerRangesCloseBeforeNegationAndIntersection() {
    for (String range : new String[] {"H-J", "h-j", "İ-ı", "A-Z"}) {
      for (String input : new String[] {"I", "i", "İ", "ı"}) {
        assertThat(Pattern.matches("(?iu)[" + range + "]", input)).isTrue();
        assertThat(Pattern.matches("(?iu)[^" + range + "]", input)).isFalse();
        assertThat(Pattern.matches("(?iu)[" + range + "&&[İ]]", input)).isTrue();
        assertThat(Pattern.matches("(?iu)[" + range + "&&[^İ]]", input)).isFalse();
      }
    }
  }

  @Test
  void reciprocalLiteralsWorkInSearchCapturesAndScopedFlags() {
    for (String source : new String[] {"I", "i", "İ", "ı"}) {
      for (String target : new String[] {"I", "i", "İ", "ı"}) {
        for (int padding : new int[] {0, 300}) {
          String input = "!".repeat(padding) + target + target + ":";
          Matcher matcher = Pattern.compile("(?iu:(" + source + "+))(?-i::)").matcher(input);
          assertThat(matcher.find()).isTrue();
          assertThat(matcher.start()).isEqualTo(padding);
          assertThat(matcher.group(1)).isEqualTo(target + target);
          assertThat(matcher.find()).isFalse();
        }
        assertThat(
                Pattern.compile(
                        source, Pattern.LITERAL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(target)
                    .matches())
            .isTrue();
      }
    }
  }

  private static Stream<Arguments> closedRangeCases() {
    return Stream.of(
        Arguments.of(
            "singleton range [K-K] includes Kelvin sign's case-fold equivalent", "[K-K]", "\u212A"),
        Arguments.of(
            "uppercase range [A-Z] includes Kelvin sign's case-fold equivalent", "[A-Z]", "\u212A"),
        Arguments.of(
            "singleton range [I-I] includes capital I with dot's case variant", "[I-I]", "\u0130"),
        Arguments.of(
            "uppercase range [A-Z] includes capital I with dot's case variant", "[A-Z]", "\u0130"));
  }
}
