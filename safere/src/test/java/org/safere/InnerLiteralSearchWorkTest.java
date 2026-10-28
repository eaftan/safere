// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guarded gaps allow a sparse required literal to bypass irrelevant dense start candidates. */
@DisabledForCrosscheck("WorkCounter is an internal SafeRE API")
@Tag("work-counter")
class InnerLiteralSearchWorkTest {
  private record Scenario(String regex, String noise, String match) {}

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void forbiddenGapCharactersExcludeEarlierStartCandidates(boolean utf8) {
    for (Scenario scenario :
        List.of(
            new Scenario("record:[^;\\r\\n]*val=200", "record:x;\n", "record:target_val=200"),
            new Scenario("(record:)([^;]*?)(val=200)", "record:x;", "record:target_val=200"),
            new Scenario("key=[a-z_]*done", "key=x;", "key=target_done"),
            new Scenario("clé:[^;]*fin", "clé:x;", "clé:é😀fin"),
            new Scenario("record:[^😀]*val=200", "record:x😀", "record:target_val=200"))) {
      Pattern pattern = Pattern.compile(scenario.regex());
      String input = scenario.noise().repeat(4_096) + scenario.match();
      long work = findWork(pattern, input, scenario.match(), utf8);
      Pattern forward =
          Pattern.compile(
              scenario.regex(), 0, EnginePathOptions.builder().literalFastPaths(false).build());
      assertThat(findWork(forward, input, scenario.match(), utf8))
          .as("The control must exercise a forward scan")
          .isGreaterThan(input.length() / 4L);
      assertThat(work)
          .as("/ %s / must bypass dense candidates before the final guarded gap", scenario.regex())
          .isLessThan(input.length() / 16L);
    }
  }

  private static long findWork(Pattern pattern, String input, String match, boolean utf8) {
    return WorkCounter.countForwardDfaStepsForTesting(
        () -> {
          if (utf8) {
            Utf8Matcher matcher = pattern.matcher(Utf8Input.validated(input.getBytes(UTF_8)));
            assertThat(matcher.find()).isTrue();
            assertThat(matcher.start())
                .isEqualTo(input.getBytes(UTF_8).length - match.getBytes(UTF_8).length);
          } else {
            Matcher matcher = pattern.matcher(input);
            assertThat(matcher.find()).isTrue();
            assertThat(matcher.start()).isEqualTo(input.length() - match.length());
          }
        });
  }

  @Test
  void eligibleSearchesScanOnlyOneUnboundedLiteralRange() {
    Pattern pattern = Pattern.compile("record:[^;]*val=200");
    for (String noise : List.of("record:x;", "other:x;")) {
      String input = noise.repeat(2_048) + "record:target_val=200";
      for (Runnable search :
          List.<Runnable>of(
              () -> assertThat(pattern.matcher(input).find()).isTrue(),
              () -> assertThat(pattern.matcher(input).replaceAll("X")).endsWith("X"),
              () -> assertThat(pattern.split(input, -1)).hasSize(2))) {
        long work = WorkCounter.countForTesting(search);
        assertThat(work).as("noise=%s", noise).isLessThan(input.length() + 1_024L);
      }
    }
  }

  @Test
  void repeatedSuffixesAndBulkSearchesKeepWorkLinear() {
    Pattern pattern = Pattern.compile("record:[^;]*val=200");
    for (int count : new int[] {1_024, 2_048}) {
      for (String unit : List.of("record:x;val=200;", "record:xval=200;")) {
        String input = unit.repeat(count) + "record:target_val=200";
        for (Runnable search :
            List.<Runnable>of(
                () -> {
                  Matcher matcher = pattern.matcher(input);
                  while (matcher.find()) {
                    assertThat(matcher.end()).isGreaterThan(matcher.start());
                  }
                },
                () -> assertThat(pattern.matcher(input).replaceAll("X")).isNotNull(),
                () -> assertThat(pattern.split(input, -1)).isNotNull())) {
          long work = WorkCounter.countWithForwardDfaStepsForTesting(search);
          assertThat(work)
              .as("count=%s, unit=%s", count, unit)
              .isLessThan(16L * input.length() + 4_096);
        }
      }
    }
  }
}
