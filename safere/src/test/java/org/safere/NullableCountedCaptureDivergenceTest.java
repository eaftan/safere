// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Pins the approved successful-path capture divergence for nullable counted repeats. */
@DisabledForCrosscheck("intentional divergence in nullable counted-repeat capture boundaries")
class NullableCountedCaptureDivergenceTest {
  private static Stream<Arguments> captureCases() {
    return Stream.of(
        Arguments.of("(?:()|\\W+){0,2}y", true),
        Arguments.of("(?:()|\\W+){1,2}y", true),
        Arguments.of("(?:(?<empty>)|\\W+){0,2}y", true),
        Arguments.of("(?:()|\\W+)*y", false),
        Arguments.of("(?:\\W+|()){0,2}y", false));
  }

  @ParameterizedTest
  @MethodSource("captureCases")
  void nullableRepeatCapturesFollowTheChosenSuccessfulPath(String regex, boolean diverges) {
    for (String prefix : new String[] {" ", "😀"}) {
      String input = prefix + "y";
      for (int operation = 0; operation < 3; operation++) {
        Matcher actual = Pattern.compile(regex).matcher(input);
        java.util.regex.Matcher oracle = java.util.regex.Pattern.compile(regex).matcher(input);
        boolean actualMatched =
            switch (operation) {
              case 0 -> actual.find();
              case 1 -> actual.matches();
              default -> actual.lookingAt();
            };
        boolean oracleMatched =
            switch (operation) {
              case 0 -> oracle.find();
              case 1 -> oracle.matches();
              default -> oracle.lookingAt();
            };
        assertThat(actualMatched).as("/%s/ on %s", regex, input).isTrue();
        assertThat(oracleMatched).isTrue();
        assertThat(actual.start()).isZero();
        assertThat(actual.end()).isEqualTo(input.length());
        assertThat(oracle.start()).isEqualTo(actual.start());
        assertThat(oracle.end()).isEqualTo(actual.end());
        assertThat(actual.group(1)).isEmpty();
        assertThat(oracle.group(1)).isEmpty();
        assertThat(actual.start(1)).isEqualTo(diverges ? 0 : prefix.length());
        assertThat(actual.end(1)).isEqualTo(actual.start(1));
        assertThat(oracle.start(1)).isEqualTo(prefix.length());
        assertThat(oracle.end(1)).isEqualTo(oracle.start(1));
      }
    }
  }

  @Test
  void repeatedFindsAndSnapshotsRetainTheirOwnEmptyCapturePositions() {
    Matcher matcher = Pattern.compile("(?:x)?(?:()|\\W+){0,2}y").matcher("aca cybb yyaaaaa");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(5);
    assertThat(matcher.start(1)).isEqualTo(5);
    var first = matcher.toMatchResult();
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(8);
    assertThat(matcher.end()).isEqualTo(10);
    assertThat(matcher.start(1)).isEqualTo(8);
    assertThat(matcher.end(1)).isEqualTo(8);
    var second = matcher.toMatchResult();
    matcher.reset();
    assertThat(matcher.find()).isTrue();
    assertThat(first.start(1)).isEqualTo(5);
    assertThat(first.end(1)).isEqualTo(5);
    assertThat(second.start(1)).isEqualTo(8);
    assertThat(second.end(1)).isEqualTo(8);
  }
}
