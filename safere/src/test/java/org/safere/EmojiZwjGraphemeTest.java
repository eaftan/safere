// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Rule-based GB11 coverage beyond Unicode's GraphemeBreakTest.txt; see issue #936. */
@DisabledForCrosscheck(
    "SafeRE follows UAX #29 GB11; the JDK mishandles interrupted and Prepend-prefixed chains")
class EmojiZwjGraphemeTest {
  @ParameterizedTest
  @ValueSource(strings = {"", "\u0301", "\u0301\u0301", "\uD83C\uDFFD"})
  void onlyExtendPreservesPictographBeforeZwj(String extend) {
    String pictograph = "\uD83D\uDC4D";
    for (String interrupt : List.of("", "\u0903", "\u200D", "\u200D\u0301", "\u0903\u200D")) {
      String first = pictograph + extend + interrupt + extend + "\u200D";
      String last = pictograph + "\u0301";
      List<String> expected = interrupt.isEmpty() ? List.of(first + last) : List.of(first, last);
      assertClusters(first + last, expected);
      // A subsequent pictograph starts a fresh GB11 chain, even after an interruption.
      assertClusters(
          first + last + "\u200D" + pictograph,
          interrupt.isEmpty()
              ? List.of(first + last + "\u200D" + pictograph)
              : List.of(first, last + "\u200D" + pictograph));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "\u0301", "\uFE0F", "\uD83C\uDFFD"})
  void prependBeforePictographDoesNotDisableZwjChain(String extend) {
    String pictograph = "\uD83D\uDC69";
    for (String prepend : List.of("\u0600", "\u0600\u0600", "\uD804\uDCBD")) {
      String chain = pictograph + extend + "\u200D" + pictograph;
      assertClusters(prepend + chain, List.of(prepend + chain));
      assertClusters(
          prepend + chain + "\u200D" + pictograph,
          List.of(prepend + chain + "\u200D" + pictograph));
      // Prepend inside the GB11 prefix interrupts it; GB9b attaches the following ZWJ.
      assertClusters(
          pictograph + prepend + "\u200D" + pictograph,
          List.of(pictograph, prepend + "\u200D", pictograph));
    }
  }

  @Test
  void regionClipsPictographContextButNotPrependBeforeVisiblePictograph() {
    String input = "\u0600\uD83D\uDC69\u0301\u200D\uD83D\uDC69";
    for (int start : List.of(0, 1, 3, 4)) {
      Matcher matcher = Pattern.compile("\\X").matcher(input).region(start, input.length());
      List<String> actual = new ArrayList<>();
      while (matcher.find()) {
        actual.add(matcher.group());
      }
      assertThat(actual)
          .as("region start=%s", start)
          .isEqualTo(
              start <= 1
                  ? List.of(input.substring(start))
                  : List.of(input.substring(start, 5), input.substring(5)));
    }
  }

  private static void assertClusters(String input, List<String> expected) {
    for (String regex : List.of("\\X", "\\X\\b{g}", "(\\X)")) {
      Pattern pattern = Pattern.compile(regex);
      for (boolean transparent : List.of(false, true)) {
        Matcher matcher =
            pattern
                .matcher("a" + input + "z")
                .region(1, input.length() + 1)
                .useTransparentBounds(transparent);
        List<String> actual = new ArrayList<>();
        while (matcher.find()) {
          actual.add(matcher.group());
        }
        assertThat(actual)
            .as("%s on %s, transparent=%s", regex, input, transparent)
            .isEqualTo(expected);
      }
      byte[] bytes = input.getBytes(UTF_8);
      Utf8Matcher matcher = pattern.matcher(Utf8Input.validated(bytes));
      List<String> actual = new ArrayList<>();
      while (matcher.find()) {
        actual.add(new String(bytes, matcher.start(), matcher.end() - matcher.start(), UTF_8));
      }
      assertThat(actual).as("UTF-8 %s on %s", regex, input).isEqualTo(expected);
    }
    assertThat(Pattern.compile("\\b{g}").split(input)).containsExactlyElementsOf(expected);
    assertThat(Pattern.compile("\\X{" + expected.size() + "}").matcher(input).matches()).isTrue();
  }
}
