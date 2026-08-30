// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Work-bound regression tests for matcher operations. */
@DisabledForCrosscheck("work accounting is a SafeRE linear-time implementation check")
@Tag("work-counter")
class MatcherLinearTimeTest {
  private static final Duration SCENARIO_TIMEOUT = Duration.ofSeconds(30);

  @Test
  @DisplayName("group access stays linear for ambiguous repeated captures")
  void groupAccessWithAmbiguousRepeatedCapturesStaysLinear() {
    Pattern pattern = Pattern.compile("((a|aa))*");

    assertNoWorkCliff(
        "matches()+group(1)",
        "a".repeat(100),
        "a".repeat(5_000),
        input -> {
          Matcher matcher = pattern.matcher(input);
          assertThat(matcher.matches()).isTrue();
          assertThat(matcher.group(1)).isEqualTo("a");
          assertThat(matcher.group(2)).isEqualTo("a");
        });
  }

  @Test
  @DisplayName("matches() stays linear for repeated dot-star with bounded captures")
  void matchesWithRepeatedDotStarAndBoundedCaptures() {
    Pattern pattern = repeatedDotStarSqlUnionPattern();

    assertNoWorkCliff(
        "matches()",
        blocks -> {
          String input = repeatedDotStarSqlUnionInput(blocks);
          Matcher matcher = pattern.matcher(input);
          assertThat(matcher.matches()).isTrue();
          assertThat(matcher.group()).isEqualTo(input);
          assertThat(matcher.start()).isEqualTo(0);
          assertThat(matcher.end()).isEqualTo(input.length());
        });
  }

  @Test
  @DisplayName("lookingAt() stays linear for repeated dot-star with bounded captures")
  void lookingAtWithRepeatedDotStarAndBoundedCaptures() {
    Pattern pattern = repeatedDotStarSqlUnionPattern();

    assertNoWorkCliff(
        "lookingAt()",
        blocks -> {
          Matcher matcher = pattern.matcher(repeatedDotStarSqlUnionInput(blocks));
          assertThat(matcher.lookingAt()).isTrue();
          assertThat(matcher.group(1)).contains("INFORMATION_SCHEMA");
        });
  }

  @Test
  @DisplayName("group access after find() stays linear for repeated dot-star captures")
  void findGroupWithRepeatedDotStarAndBoundedCaptures() {
    Pattern pattern = repeatedDotStarSqlUnionPattern();

    assertNoWorkCliff(
        "find()+group(1)",
        blocks -> {
          Matcher matcher = pattern.matcher(repeatedDotStarSqlUnionInput(blocks));
          assertThat(matcher.find()).isTrue();
          assertThat(matcher.group(1)).contains("INFORMATION_SCHEMA");
        });
  }

  @Test
  @DisplayName("region find stays linear for repeated dot-star captures")
  void regionFindWithRepeatedDotStarAndBoundedCaptures() {
    Pattern pattern = repeatedDotStarSqlUnionPattern();

    assertNoWorkCliff(
        "region().find()",
        blocks -> {
          String input = "prefix\n" + repeatedDotStarSqlUnionInput(blocks) + "suffix\n";
          Matcher matcher = pattern.matcher(input);
          matcher.region("prefix\n".length(), input.length() - "suffix\n".length());
          assertThat(matcher.find()).isTrue();
          assertThat(matcher.group(1)).contains("INFORMATION_SCHEMA");
        });
  }

  private static String repeatedDotStarSqlUnionInput(int selectCount) {
    StringBuilder input = new StringBuilder();
    for (int i = 1; i <= selectCount; i++) {
      input
          .append("(SELECT *, PARSE_DATE('%Y-%m-%d', '2025-06-25') AS snapshot_date FROM ")
          .append("`project-")
          .append("%02d".formatted(i))
          .append("`.`region2`.INFORMATION_SCHEMA.TABLE_OPTIONS)\n")
          .append("UNION ALL\n");
    }
    return input.toString();
  }

  private static Pattern repeatedDotStarSqlUnionPattern() {
    return Pattern.compile(
        ".*SELECT.*FROM.*(.*INFORMATION_SCHEMA.*){5,}.*",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  }

  private static void assertNoWorkCliff(String api, IntConsumer scenario) {
    long largerPositiveWork = countedWork(() -> scenario.accept(16));
    long nearMinimumWork = countedWork(() -> scenario.accept(5));

    assertThat(largerPositiveWork).as("%s should use matcher work", api).isPositive();
    assertThat(nearMinimumWork)
        .as(
            "%s near-minimum input should not be dramatically slower than a larger "
                + "positive input; nearWork=%d largerWork=%d",
            api, nearMinimumWork, largerPositiveWork)
        .isLessThan(largerPositiveWork * 50);
  }

  private static void assertNoWorkCliff(
      String api, String nearMinimumInput, String largerPositiveInput, Consumer<String> scenario) {
    scenario.accept(nearMinimumInput);
    scenario.accept(largerPositiveInput);
    long largerPositiveWork = countedWork(() -> scenario.accept(largerPositiveInput));
    long nearMinimumWork = countedWork(() -> scenario.accept(nearMinimumInput));

    assertThat(largerPositiveWork).as("%s should use matcher work", api).isPositive();
    assertThat(nearMinimumWork)
        .as(
            "%s near-minimum input should not be dramatically slower than a larger "
                + "positive input; nearWork=%d largerWork=%d",
            api, nearMinimumWork, largerPositiveWork)
        .isLessThan(largerPositiveWork * 50);
  }

  private static long countedWork(Runnable task) {
    return assertTimeoutPreemptively(SCENARIO_TIMEOUT, () -> WorkCounter.countForTesting(task));
  }
}
