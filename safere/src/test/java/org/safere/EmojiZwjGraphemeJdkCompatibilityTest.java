// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks that SafeRE and the JDK agree on UAX #29 GB11 once the JDK implements it.
 *
 * <p>JDK 27 and earlier mishandle interrupted and Prepend-prefixed emoji ZWJ chains (see issue #936
 * and {@code INTENTIONAL_DIVERGENCES.md}). JDK-8393247 fixes that in JDK 28. {@link
 * EmojiZwjGraphemeTest} checks SafeRE against the rule itself on every JDK.
 */
@EnabledForJreRange(minVersion = 28)
class EmojiZwjGraphemeJdkCompatibilityTest {
  private static final String THUMBS_UP = "\uD83D\uDC4D";
  private static final String WOMAN = "\uD83D\uDC69";
  private static final String MAN = "\uD83D\uDC68";
  private static final String MEDIUM_DARK_SKIN_TONE = "\uD83C\uDFFE";
  private static final String ZWJ = "\u200D";
  private static final String ACUTE = "\u0301"; // Extend
  private static final String DEVANAGARI_VISARGA = "\u0903"; // SpacingMark
  private static final String ARABIC_NUMBER_SIGN = "\u0600"; // Prepend

  static Stream<Arguments> gb11Cases() {
    return Stream.of(
        Arguments.of(
            List.of(THUMBS_UP + DEVANAGARI_VISARGA + ZWJ, THUMBS_UP), "SpacingMark interrupts"),
        Arguments.of(List.of(THUMBS_UP + ZWJ + ZWJ, THUMBS_UP), "second ZWJ interrupts"),
        Arguments.of(List.of(THUMBS_UP + ZWJ + ACUTE, THUMBS_UP), "Extend after ZWJ interrupts"),
        Arguments.of(List.of(THUMBS_UP + ACUTE + ZWJ + THUMBS_UP), "Extend before ZWJ joins"),
        Arguments.of(
            List.of(MAN + ZWJ + MEDIUM_DARK_SKIN_TONE + ZWJ, WOMAN),
            "modifier after ZWJ is not a pictograph"),
        Arguments.of(
            List.of(ARABIC_NUMBER_SIGN + WOMAN + ZWJ + WOMAN), "Prepend before pictograph joins"),
        Arguments.of(
            List.of(ARABIC_NUMBER_SIGN + WOMAN + ACUTE + ZWJ + WOMAN + ZWJ + WOMAN),
            "Prepend before a longer chain joins"),
        Arguments.of(
            List.of(WOMAN, ARABIC_NUMBER_SIGN + ZWJ, WOMAN),
            "Prepend inside the prefix interrupts"));
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("gb11Cases")
  void graphemeClustersMatchJdk(List<String> expected, String description) {
    String input = String.join("", expected);
    assertThat(clusters(Pattern.compile("\\X").matcher(input))).isEqualTo(expected);
    assertThat(jdkClusters(java.util.regex.Pattern.compile("\\X").matcher(input)))
        .as("JDK \\X clusters")
        .isEqualTo(expected);
    assertThat(Pattern.compile("\\b{g}").split(input))
        .containsExactlyElementsOf(expected)
        .containsExactly(java.util.regex.Pattern.compile("\\b{g}").split(input));
  }

  private static List<String> clusters(Matcher matcher) {
    List<String> clusters = new ArrayList<>();
    while (matcher.find()) {
      clusters.add(matcher.group());
    }
    return clusters;
  }

  private static List<String> jdkClusters(java.util.regex.Matcher matcher) {
    List<String> clusters = new ArrayList<>();
    while (matcher.find()) {
      clusters.add(matcher.group());
    }
    return clusters;
  }
}
