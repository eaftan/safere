// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Public searches must tolerate an unavailable standalone reject filter. */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@DisabledForCrosscheck("Exercises SafeRE-specific matcher-free UTF-8 and diagnostics APIs")
class MatcherFreeFindTest {
  @AfterEach
  void clearDiagnostics() {
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
  }

  static Stream<Arguments> innerLiteralSearchCases() {
    return Stream.of(false, true)
        .flatMap(
            diagnostics ->
                Stream.of("zz[^;]*qq", "zz[a-z]*qq")
                    .flatMap(
                        regex ->
                            Stream.of(61, 62, 63, 100, 509, 510, 511)
                                .flatMap(
                                    length ->
                                        Stream.of("", "qq", ";qq")
                                            .flatMap(
                                                suffix ->
                                                    Stream.of("", "\uD83D\uDE00")
                                                        .map(
                                                            prefix ->
                                                                Arguments.of(
                                                                    diagnostics,
                                                                    regex,
                                                                    prefix
                                                                        + "zz"
                                                                        + "x".repeat(length)
                                                                        + suffix))))));
  }

  @ParameterizedTest
  @MethodSource("innerLiteralSearchCases")
  void optionalRejectFilterPreservesPublicFindResults(
      boolean diagnostics, String regex, String input) {
    Pattern.setDiagnostics(
        diagnostics ? new SafeReMatchDiagnostics() : SafeReMatchDiagnostics.NONE);
    boolean expected = java.util.regex.Pattern.compile(regex).matcher(input).find();
    Pattern pattern = Pattern.compile(regex);
    Utf8Input utf8 = Utf8Input.validated(input.getBytes(UTF_8));
    assertThat(pattern.asPredicate().test(input)).isEqualTo(expected);
    assertThat(pattern.find(utf8)).isEqualTo(expected);
    assertThat(pattern.matcher(input).find()).isEqualTo(expected);
    assertThat(pattern.matcher(utf8).find()).isEqualTo(expected);
  }
}
