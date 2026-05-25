// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Inventory of observed JDK Unicode case-insensitive range behavior that appears to depend on
 * {@code java.util.regex} implementation details rather than on specified regex semantics.
 *
 * <p>These cases are intentionally not active SafeRE compatibility requirements. SafeRE treats
 * Unicode case-insensitive character classes as sets closed under Unicode case folding, so these
 * JDK observations are documented here instead of encoded as active SafeRE expectations.
 */
@Disabled("Observed JDK Unicode case-folding range details; not a SafeRE compatibility target")
@DisplayName("JDK Unicode case-folding implementation-detail inventory")
class UnicodeCaseFoldingJdkImplementationDetailTest {

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("jdkRangeCases")
  @DisplayName("selected Unicode case-insensitive ranges have JDK implementation-specific misses")
  void selectedUnicodeCaseInsensitiveRangesHaveJdkImplementationSpecificMisses(
      String description, String regex, String input) {
    java.util.regex.Pattern pattern =
        java.util.regex.Pattern.compile(
            regex, java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    assertThat(pattern.matcher(input).matches()).as(description).isFalse();
  }

  private static Stream<Arguments> jdkRangeCases() {
    return Stream.of(
        Arguments.of(
            "JDK does not match Kelvin sign with singleton range [K-K]", "[K-K]", "\u212A"),
        Arguments.of(
            "JDK does not match Kelvin sign with uppercase range [A-Z]", "[A-Z]", "\u212A"),
        Arguments.of(
            "JDK does not match capital I with dot with singleton range [I-I]", "[I-I]", "\u0130"),
        Arguments.of(
            "JDK does not match capital I with dot with uppercase range [A-Z]", "[A-Z]", "\u0130"));
  }
}
