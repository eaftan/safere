// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("SafeRE work counters and execution paths")
@Tag("work-counter")
class LiteralAlternationWorkTest {
  @Test
  void earlyMatchesDoNotScanTheUnrelatedTail() {
    Pattern pattern = Pattern.compile("apple|banana|cherry");
    for (String literal : new String[] {"apple", "banana", "cherry"}) {
      for (int offset : new int[] {0, 1_023, 1_024, 2_048, 3_072, 8_192, 16_384, 65_536}) {
        String prefix = "x".repeat(offset) + literal;
        long shorter = work(pattern, prefix + "x".repeat(Math.max(32_768, offset * 2)));
        long longer = work(pattern, prefix + "x".repeat(1_048_576));
        assertThat(shorter).isPositive();
        assertThat(longer).as("%s at %s", literal, offset).isLessThanOrEqualTo(shorter * 2);
      }
    }
  }

  @Test
  void failedSearchesKeepLinearWorkAcrossGrowingWindows() {
    for (String regex :
        new String[] {"apple|banana|cherry", "apple|banana|cherry|durian|elderberry|fig"}) {
      Pattern pattern = Pattern.compile(regex);
      long shorter =
          WorkCounter.countForTesting(
              () -> assertThat(pattern.matcher("x".repeat(32_768)).find()).isFalse());
      long longer =
          WorkCounter.countForTesting(
              () -> assertThat(pattern.matcher("x".repeat(131_072)).find()).isFalse());
      assertThat(shorter).isPositive();
      assertThat(longer).as(regex).isLessThanOrEqualTo(shorter * 6);
    }
  }

  @Test
  void repeatedSparseMatchesDoNotRescanTheRemainingTail() {
    Pattern pattern = Pattern.compile("apple|banana|cherry");
    for (int gap : new int[] {1, 1_024, 4_096}) {
      String segment = "x".repeat(gap) + "cherry";
      long shorter = allWork(pattern, segment.repeat(16));
      long longer = allWork(pattern, segment.repeat(64));
      assertThat(longer).as("gap %s", gap).isLessThanOrEqualTo(shorter * 6);
    }
  }

  @Test
  void alternativesWithoutAWholeInputFilterDoNotScanBeyondALateMatch() {
    for (String regex :
        new String[] {"apple|banana|cherry|durian|elderberry|fig", "foo|barfoo", "a|ab"}) {
      Pattern pattern = Pattern.compile(regex);
      assertThat(pattern.preparedMatchRunner(false))
          .as(regex)
          .isInstanceOf(Matcher.LiteralAlternationPreparedRunner.class);
      assertThat(pattern.rejectPrefilter()).as(regex).isNull();
      String literal = regex.substring(regex.lastIndexOf('|') + 1);
      String prefix = "x".repeat(8_192) + literal;
      long shorter = work(pattern, prefix + "x".repeat(8_192));
      long longer = work(pattern, prefix + "x".repeat(65_536));
      assertThat(shorter).isPositive();
      assertThat(longer).as(regex).isLessThanOrEqualTo(shorter * 2);
    }
  }

  @Test
  void utf8SearchesOnDenseFirstBytesStayLinearAndLocal() {
    assumeFalse(
        VectorScanProviders.vectorProviderAvailable(),
        "The UTF-8 literal search is off when the vector Teddy scan is available");
    // "ab " starts two of the literals often enough for the UTF-8 literal search to run.
    for (String regex :
        new String[] {
          "apple|banana|cherry",
          "blossom|sparkling|gardens",
          "ab|cherry|abc",
          "north|south|east|west|upper|lower"
        }) {
      Pattern pattern = Pattern.compile(regex);
      String filler = regex.startsWith("ab|") ? "aa bb " : "ab bc ";
      long shorter = utf8Work(pattern, filler.repeat(5_000), false);
      long longer = utf8Work(pattern, filler.repeat(20_000), false);
      assertThat(shorter).isPositive();
      assertThat(longer).as(regex).isLessThanOrEqualTo(shorter * 6);
      String literal = regex.substring(regex.lastIndexOf('|') + 1);
      for (int offset : new int[] {0, 1_000, 4_000}) {
        String prefix = filler.repeat(offset) + literal;
        long early = utf8Work(pattern, prefix + filler.repeat(Math.max(8_000, offset * 2)), true);
        long late = utf8Work(pattern, prefix + filler.repeat(200_000), true);
        assertThat(late).as("%s at %s", regex, offset).isLessThanOrEqualTo(early * 2);
      }
    }
  }

  @Test
  void manyInitialsKeepSparseAndDenseSearchesLinearAndLocal() {
    Pattern pattern = Pattern.compile("north|south|east|west|upper|lower|front|back");
    for (String filler : new String[] {"x", "n s e w u l f b ", "日本語😀"}) {
      String shorter = filler.repeat(2_000);
      String longer = filler.repeat(8_000);
      long shortMiss =
          WorkCounter.countForTesting(() -> assertThat(pattern.matcher(shorter).find()).isFalse());
      long longMiss =
          WorkCounter.countForTesting(() -> assertThat(pattern.matcher(longer).find()).isFalse());
      assertThat(shortMiss).isPositive();
      assertThat(longMiss).as(filler).isLessThanOrEqualTo(shortMiss * 6);
      assertThat(utf8Work(pattern, longer, false))
          .as(filler)
          .isLessThanOrEqualTo(utf8Work(pattern, shorter, false) * 6);
      for (int offset : new int[] {255, 256, 1_024, 4_096}) {
        String prefix = filler.repeat(offset) + "back";
        assertThat(work(pattern, prefix + filler.repeat(100_000)))
            .as("%s at %s", filler, offset)
            .isLessThanOrEqualTo(work(pattern, prefix + filler.repeat(8_000)) * 2);
        assertThat(utf8Work(pattern, prefix + filler.repeat(100_000), true))
            .as("%s at %s", filler, offset)
            .isLessThanOrEqualTo(utf8Work(pattern, prefix + filler.repeat(8_000), true) * 2);
      }
    }
  }

  private static long work(Pattern pattern, String text) {
    return WorkCounter.countForTesting(() -> assertThat(pattern.matcher(text).find()).isTrue());
  }

  private static long utf8Work(Pattern pattern, String text, boolean expected) {
    Utf8Input input = Utf8Input.validated(text.getBytes(UTF_8));
    return WorkCounter.countForTesting(
        () -> assertThat(pattern.matcher(input).find()).isEqualTo(expected));
  }

  private static long allWork(Pattern pattern, String text) {
    return WorkCounter.countForTesting(
        () -> {
          Matcher matcher = pattern.matcher(text);
          while (matcher.find()) {
            assertThat(matcher.group()).isEqualTo("cherry");
          }
        });
  }
}
