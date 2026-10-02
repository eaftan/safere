// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

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
        new String[] {"apple|banana|cherry|durian|elderberry|fig", "fooa|foob|fooc"}) {
      Pattern pattern = Pattern.compile(regex);
      String literal = regex.endsWith("fig") ? "fig" : "fooc";
      String prefix = "x".repeat(8_192) + literal;
      long shorter = work(pattern, prefix + "x".repeat(8_192));
      long longer = work(pattern, prefix + "x".repeat(65_536));
      assertThat(shorter).isPositive();
      assertThat(longer).as(regex).isLessThanOrEqualTo(shorter * 2);
    }
  }

  private static long work(Pattern pattern, String text) {
    return WorkCounter.countForTesting(() -> assertThat(pattern.matcher(text).find()).isTrue());
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
