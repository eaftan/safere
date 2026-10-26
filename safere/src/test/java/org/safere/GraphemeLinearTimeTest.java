// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Linear-time regression tests for grapheme cluster matching. */
@DisabledForCrosscheck("java.util.regex is not the SafeRE linear-time engine")
@Tag("work-counter")
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

  @Test
  void mixedRuleInteractionsAndExplicitBoundariesStayLinear() {
    String unit =
        String.join(
            "",
            "\u0600",
            "👩\u0300\u200D👩",
            "\u0915\u094D\u200D\u0915",
            "🇦🇧",
            "\u1100\u1160\u11A8",
            "\r",
            "\n");
    for (String regex : new String[] {"\\X", "(?s).\\b{g}", "\\b{g}\\X\\b{g}"}) {
      Pattern pattern = Pattern.compile(regex);
      for (boolean utf8 : new boolean[] {false, true}) {
        assertFourXInputStaysNearLinear(
            "mixed Unicode rules " + regex + ", UTF-8=" + utf8,
            unit.repeat(500),
            unit.repeat(2_000),
            text -> {
              if (utf8) {
                Utf8Matcher matcher = pattern.matcher(Utf8Input.validated(text.getBytes(UTF_8)));
                while (matcher.find()) {
                  assertThat(matcher.end()).isGreaterThan(matcher.start());
                }
              } else {
                Matcher matcher = pattern.matcher(text);
                while (matcher.find()) {
                  assertThat(matcher.end()).isGreaterThan(matcher.start());
                }
              }
            });
      }
    }
  }

  @Test
  void cachedContextQueriesWithChangingVisibleStartsStayLinear() {
    for (String unit : new String[] {"🇦", "\u094D", "\u0300"}) {
      assertFourXInputStaysNearLinear(
          "cached context for " + unit,
          unit.repeat(2_000),
          unit.repeat(8_000),
          middle -> {
            String text = "👩\u0915" + middle + "\u200D\u0915";
            for (InputScanner scanner :
                new InputScanner[] {
                  new StringInputScanner(text), new Utf8InputScanner(text.getBytes(UTF_8))
                }) {
              GraphemeSupport.Context context = GraphemeSupport.Context.create(scanner, true);
              int pos = InputScanner.position(scanner.decodeBackward(scanner.length()));
              // Each query changes the clipping threshold, but the caches are built once.
              for (int start = 0;
                  start < pos;
                  start = InputScanner.position(scanner.decodeForward(start))) {
                context.hasEvenRegionalIndicatorsBefore(pos, start);
                context.hasExtendedPictographicBefore(pos, start);
                context.hasIndicConjunctLinkerBefore(pos, start);
                context.hasIndicConjunctSequenceBefore(pos, start);
              }
            }
          });
    }
  }

  @Test
  void immediateOpaqueGraphemeMatchDoesNotScanTheWholeRegion() {
    Pattern pattern = Pattern.compile("\\X");
    String small = "a".repeat(256);
    String large = "a".repeat(65_536);
    for (boolean find : new boolean[] {false, true}) {
      Consumer<String> match =
          input -> {
            Matcher matcher = pattern.matcher(input).region(1, input.length());
            assertThat(find ? matcher.find() : matcher.lookingAt()).isTrue();
            assertThat(matcher.start()).isEqualTo(1);
            assertThat(matcher.end()).isEqualTo(2);
          };
      long smallWork = WorkCounter.countForTesting(() -> match.accept(small));
      long largeWork = WorkCounter.countForTesting(() -> match.accept(large));
      assertThat(smallWork).isPositive();
      assertThat(largeWork)
          .as("first match must not copy or scan the full region")
          .isLessThanOrEqualTo(smallWork * 2);
    }
  }

  @Test
  void opaqueFindSequencesReuseTheirRegionAndGraphemeContext() {
    for (String regex : new String[] {"\\X", "\\b{g}\\X\\b{g}", "\\X(?m:$)?"}) {
      Pattern pattern = Pattern.compile(regex);
      for (boolean anchoring : new boolean[] {true, false}) {
        assertFourXInputStaysNearLinear(
            "opaque repeated find: " + regex + " anchoring=" + anchoring,
            "\uD83C\uDDE6\uD83C\uDDE7".repeat(500),
            "\uD83C\uDDE6\uD83C\uDDE7".repeat(2_000),
            middle -> {
              Matcher matcher =
                  pattern
                      .matcher("x" + middle + "y")
                      .region(1, middle.length() + 1)
                      .useAnchoringBounds(anchoring);
              int matches = 0;
              while (matcher.find()) {
                assertThat(matcher.start()).isGreaterThanOrEqualTo(1);
                assertThat(matcher.end()).isLessThanOrEqualTo(middle.length() + 1);
                matches++;
              }
              assertThat(matches).isPositive();
            });
      }
    }
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
