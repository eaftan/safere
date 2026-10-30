// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("Deterministic work checks SafeRE region search kernels")
@Tag("work-counter")
class StringRegionWorkTest {
  @Test
  void terminalMismatchRejectsWithoutScanningTheRegion() {
    String input = "!" + "a".repeat(8192) + "!";
    for (String regex : new String[] {"^a+b$", "^a+[0-9]$"}) {
      Matcher matcher = Pattern.compile(regex).matcher(input).region(1, input.length() - 1);
      long work =
          WorkCounter.countWithForwardDfaStepsForTesting(
              () -> assertThat(matcher.matches()).isFalse());
      assertThat(work).as(regex).isLessThan(32);
    }
  }

  @Test
  void foldedNearMissVerificationIsBoundedByRegionSize() {
    String literal = "a".repeat(512) + "b";
    String input = "!" + "a".repeat(8192) + "!";
    Matcher matcher = Pattern.compile("(?i:" + literal + ")").matcher(input);
    matcher.region(1, input.length() - 1);
    long work = WorkCounter.countForTesting(() -> assertThat(matcher.find()).isFalse());
    assertThat(work).isLessThan(10L * (input.length() + literal.length()));
  }

  @Test
  void repeatedCharacterClassRegionsKeepTheirDirectScan() {
    for (String regex : new String[] {"[a-z]+", "[a-z]*"}) {
      String input = "!" + "a".repeat(8192) + "!";
      Matcher matcher = Pattern.compile(regex).matcher(input).region(1, input.length() - 1);
      long steps =
          WorkCounter.countForwardDfaStepsForTesting(() -> assertThat(matcher.matches()).isTrue());
      assertThat(steps).isZero();
    }
  }
}
