// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for SafeRE's documented Unicode case-folding model where it intentionally diverges from
 * selected JDK range traces.
 */
@DisabledForCrosscheck(
    "SafeRE's documented Unicode case-folding model intentionally diverges from selected JDK range"
        + " traces")
class UnicodeCaseFoldingModelTest {

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("closedRangeCases")
  @DisplayName("Unicode case-insensitive ranges are closed under case folding")
  void unicodeCaseInsensitiveRangesAreClosedUnderCaseFolding(
      String description, String regex, String input) {
    Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    assertThat(pattern.matcher(input).matches()).as(description).isTrue();
  }

  private static Stream<Arguments> closedRangeCases() {
    return Stream.of(
        Arguments.of(
            "singleton range [K-K] includes Kelvin sign's case-fold equivalent", "[K-K]", "\u212A"),
        Arguments.of(
            "uppercase range [A-Z] includes Kelvin sign's case-fold equivalent", "[A-Z]", "\u212A"),
        Arguments.of(
            "singleton range [I-I] includes capital I with dot's case variant", "[I-I]", "\u0130"),
        Arguments.of(
            "uppercase range [A-Z] includes capital I with dot's case variant", "[A-Z]", "\u0130"));
  }
}
