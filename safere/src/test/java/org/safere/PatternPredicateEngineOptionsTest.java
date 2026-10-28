// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Checks that the matcher-free {@link Pattern#asPredicate()} path honors engine path options. */
@DisabledForCrosscheck("uses package-private engine path options")
class PatternPredicateEngineOptionsTest {

  private static Stream<Arguments> engineOptions() {
    return Stream.of(
        Arguments.of("literal fast paths off", EnginePathOptions.builder().literalFastPaths(false)),
        Arguments.of(
            "start acceleration off", EnginePathOptions.builder().startAcceleration(false)),
        Arguments.of(
            "keyword fast path off", EnginePathOptions.builder().keywordAlternationFastPath(false)),
        Arguments.of("DFA off", EnginePathOptions.builder().dfa(false)));
  }

  @ParameterizedTest
  @MethodSource("engineOptions")
  void asPredicateHonorsEnginePathOptions(String name, EnginePathOptions.Builder options) {
    List<String> failures = new ArrayList<>();
    PatternPredicateTest.patterns()
        .forEach(
            arguments -> {
              String regex = (String) arguments.get()[0];
              int flags = (int) arguments.get()[1];
              Pattern pattern = Pattern.compile(regex, flags, options.build());
              for (String input : PatternPredicateTest.INPUTS) {
                if (pattern.asPredicate().test(input) != pattern.matcher(input).find()) {
                  failures.add(
                      "/"
                          + regex
                          + "/ flags="
                          + flags
                          + " on "
                          + PatternPredicateTest.escape(input));
                }
              }
            });
    assertThat(failures).as(name).isEmpty();
  }
}
