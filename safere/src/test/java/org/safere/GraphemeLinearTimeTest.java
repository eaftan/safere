// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Linear-time regression tests for grapheme cluster matching. */
@DisabledForCrosscheck("java.util.regex is not the SafeRE linear-time engine")
class GraphemeLinearTimeTest {
  @Test
  @DisplayName("repeated find() over grapheme clusters reuses per-input context")
  void repeatedFindOverGraphemeClustersReusesPerInputContext() {
    Pattern pattern = Pattern.compile("\\X");

    assertFourXInputStaysNearLinear(
        "repeated find() over grapheme clusters",
        "a".repeat(20_000),
        "a".repeat(80_000),
        text -> {
          Matcher matcher = pattern.matcher(text);
          int count = 0;
          while (matcher.find()) {
            count++;
          }
          assertThat(count).isEqualTo(text.length());
        });
  }

  @Test
  @DisplayName("JDK-compatible low-surrogate search positions stay near-linear")
  void lowSurrogateSearchPositionsStayNearLinear() {
    Pattern pattern = Pattern.compile(".*\\b{g}z");

    assertFourXInputStaysNearLinear(
        "low-surrogate search-position miss",
        "\uDC00".repeat(20_000),
        "\uDC00".repeat(80_000),
        text -> assertThat(pattern.matcher(text).find()).isFalse());
  }

  @Test
  @DisplayName("regional-indicator grapheme boundary misses stay near-linear")
  void regionalIndicatorBoundaryMissesStayNearLinear() {
    Pattern pattern = Pattern.compile("\\b{g}z");

    assertFourXInputStaysNearLinear(
        "regional-indicator boundary miss",
        "\uD83C\uDDE6".repeat(20_000),
        "\uD83C\uDDE6".repeat(80_000),
        text -> assertThat(pattern.matcher(text).find()).isFalse());
  }

  @Test
  @DisplayName("failed unanchored \\X suffix misses stay near-linear")
  void failedUnanchoredGraphemeSuffixMissesStayNearLinear() {
    Pattern pattern = Pattern.compile("\\Xz");

    assertFourXInputStaysNearLinear(
        "failed unanchored \\X suffix miss",
        "a" + "\u0301".repeat(20_000),
        "a" + "\u0301".repeat(80_000),
        text -> assertThat(pattern.matcher(text).find()).isFalse());
    assertFourXInputStaysNearLinear(
        "failed unanchored regional-indicator \\X suffix miss",
        "\uD83C\uDDE6".repeat(20_000),
        "\uD83C\uDDE6".repeat(80_000),
        text -> assertThat(pattern.matcher(text).find()).isFalse());
  }

  private static void assertFourXInputStaysNearLinear(
      String scenario, String smallerInput, String largerInput, Consumer<String> task) {
    task.accept(smallerInput);
    task.accept(largerInput);
    long smallerWork = WorkCounter.countForTesting(() -> task.accept(smallerInput));
    long largerWork = WorkCounter.countForTesting(() -> task.accept(largerInput));

    assertThat(smallerWork).as("%s: smaller input should use grapheme work", scenario).isPositive();
    assertThat(largerWork)
        .as(
            "%s: 4x input should stay near-linear, smallerWork=%d largerWork=%d",
            scenario, smallerWork, largerWork)
        .isLessThan(smallerWork * 5);
  }
}
