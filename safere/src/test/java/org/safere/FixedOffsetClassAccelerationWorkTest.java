// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisabledForCrosscheck("uses internal work counters to verify acceleration bounds")
@Tag("work-counter")
class FixedOffsetClassAccelerationWorkTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void absentInteriorClassRejectsBeforeDfaSearch(boolean utf8) {
    Pattern pattern = Pattern.compile(" [0-9]{4,}");
    String input = "words with frequent spaces é😀 ".repeat(2_000);
    WorkCounter.StartAccelerationWork work =
        WorkCounter.countStartAccelerationForTesting(
            () -> assertThat(find(pattern, input, utf8)).isFalse());
    assertThat(work.calls()).isZero();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void earlySuccessDoesNotScanTheRemainingInput(boolean utf8) {
    Pattern pattern = Pattern.compile(" [0-9]{4,}");
    // Warm engine setup before comparing searches with the same early successful match.
    assertThat(find(pattern, " 1234x", utf8)).isTrue();
    long small = successWork(pattern, " 1234x" + " words é😀".repeat(4_000), utf8);
    long large = successWork(pattern, " 1234x" + " words é😀".repeat(20_000), utf8);
    assertThat(small).isPositive();
    assertThat(large).isLessThanOrEqualTo(small + 32);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void falseCandidatesAndLongContinuationsRemainLinear(boolean utf8) {
    for (String regex : List.of(" [0-9]{4,}", " ([0-9]+)Z", "[a-z]{2}([0-9]+)Z")) {
      Pattern pattern = Pattern.compile(regex);
      for (String unit : List.of("1111x", " 1x", "aa1234Y", "é😀1111x")) {
        long small = failureWork(pattern, unit.repeat(2_000) + "Z", utf8);
        long large = failureWork(pattern, unit.repeat(10_000) + "Z", utf8);
        assertThat(small).as("%s on %s", regex, unit).isPositive();
        assertThat(large).as("%s on %s", regex, unit).isLessThan(small * 6);
      }
    }
  }

  private static long successWork(Pattern pattern, String input, boolean utf8) {
    return WorkCounter.countForTesting(() -> assertThat(find(pattern, input, utf8)).isTrue());
  }

  private static long failureWork(Pattern pattern, String input, boolean utf8) {
    return WorkCounter.countForTesting(() -> assertThat(find(pattern, input, utf8)).isFalse());
  }

  private static boolean find(Pattern pattern, String input, boolean utf8) {
    return utf8
        ? pattern.matcher(Utf8Input.trusted(input.getBytes(UTF_8))).find()
        : pattern.matcher(input).find();
  }
}
