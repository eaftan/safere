// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Unicode match bounds")
class UnicodeMatchBoundsTest {

  record Case(String label, String regex, int flags, String input) {
    @Override
    public String toString() {
      return label;
    }
  }

  record Outcome(boolean matched, int start, int end) {}

  static Stream<Case> dotAndAnchorBoundsMatchJdk() {
    return Stream.of(
        new Case("dot-empty-alternative", ".|", 352, "\ud85c"),
        new Case("dollar-anchor", "$", 332, "\udb7f\udb7f\ud85c\r\r"),
        new Case("dot-plus-surrogate", ".+", 358, "\ud828"),
        new Case(
            "dot-plus-anchor",
            ".+^",
            300,
            "\u0301\r\u2028\u0301\u0301\u2028\u0301\u0301\ud85c\u2028a"),
        new Case("dot-unpaired-high-surrogate", ".", Pattern.DOTALL, "\ud83d"),
        new Case("dot-unpaired-low-surrogate", ".", Pattern.DOTALL, "\ude00"),
        new Case("dot-valid-surrogate-pair", ".", Pattern.DOTALL, "\ud83d\ude00"));
  }

  @ParameterizedTest
  @MethodSource
  @DisplayName("dot and anchor bounds match java.util.regex")
  void dotAndAnchorBoundsMatchJdk(Case c) {
    Pattern safePattern = Pattern.compile(c.regex(), c.flags());
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(c.regex(), c.flags());

    assertThat(findOutcome(safePattern.matcher(c.input())))
        .as("%s find", c.label())
        .isEqualTo(findOutcome(jdkPattern.matcher(c.input())));
    assertThat(lookingAtOutcome(safePattern.matcher(c.input())))
        .as("%s lookingAt", c.label())
        .isEqualTo(lookingAtOutcome(jdkPattern.matcher(c.input())));
    assertThat(matchesOutcome(safePattern.matcher(c.input())))
        .as("%s matches", c.label())
        .isEqualTo(matchesOutcome(jdkPattern.matcher(c.input())));
  }

  private static Outcome findOutcome(Matcher matcher) {
    boolean matched = matcher.find();
    return new Outcome(matched, matched ? matcher.start() : -1, matched ? matcher.end() : -1);
  }

  private static Outcome findOutcome(java.util.regex.Matcher matcher) {
    boolean matched = matcher.find();
    return new Outcome(matched, matched ? matcher.start() : -1, matched ? matcher.end() : -1);
  }

  private static Outcome lookingAtOutcome(Matcher matcher) {
    boolean matched = matcher.lookingAt();
    return new Outcome(matched, matched ? matcher.start() : -1, matched ? matcher.end() : -1);
  }

  private static Outcome lookingAtOutcome(java.util.regex.Matcher matcher) {
    boolean matched = matcher.lookingAt();
    return new Outcome(matched, matched ? matcher.start() : -1, matched ? matcher.end() : -1);
  }

  private static Outcome matchesOutcome(Matcher matcher) {
    boolean matched = matcher.matches();
    return new Outcome(matched, matched ? matcher.start() : -1, matched ? matcher.end() : -1);
  }

  private static Outcome matchesOutcome(java.util.regex.Matcher matcher) {
    boolean matched = matcher.matches();
    return new Outcome(matched, matched ? matcher.start() : -1, matched ? matcher.end() : -1);
  }
}
