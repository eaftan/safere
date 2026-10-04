// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;

final class StrictDifferentialTest {
  @Test
  void generatedWitnessesStayInDomainAndMatch() {
    Random random = new Random(872);
    for (int index = 0; index < 500; index++) {
      StrictDomain.Sample sample = StrictDomain.generate(random::nextInt);
      assertThat(sample.regex().length()).isLessThan(256);
      assertThat(sample.witness().length()).isLessThanOrEqualTo(StrictDomain.MAX_INPUT);
      var expected = java.util.regex.Pattern.compile(sample.regex()).matcher(sample.witness());
      assertThat(expected.groupCount()).isZero();
      assertThat(expected.matches()).as(sample.regex()).isTrue();
      assertThat(StrictDifferential.check(sample.regex(), sample.witness())).isTrue();
    }
  }

  @Test
  void enumerateSmallDomainWithoutLegacyExclusions() {
    List<String> inputs = new ArrayList<>(List.of("", "a", "b", "c", ".", "!"));
    for (String left : List.of("a", "b", "!")) {
      for (String right : List.of("a", "b", "!")) {
        inputs.add(left + right);
      }
    }
    for (String regex : StrictDomain.smallPatterns()) {
      for (String input : inputs) {
        assertThat(StrictDifferential.check(regex, input)).as("%s on %s", regex, input).isTrue();
      }
    }
    for (String left : StrictDomain.QUANTIFIERS) {
      for (String right : StrictDomain.QUANTIFIERS) {
        String regex = StrictDomain.quantify(StrictDomain.quantify("a", left), right);
        for (String input : inputs) {
          assertThat(StrictDifferential.check(regex, input)).isTrue();
        }
      }
    }
  }

  @Test
  void rejectsInvalidPatternInsteadOfSkippingBothRejections() {
    assertThatThrownBy(() -> StrictDifferential.check("[", "a"))
        .isInstanceOf(PatternSyntaxException.class);
  }

  @Test
  void reportsWholeMatchCaptureAndSequenceDifferences() {
    var group = new StrictDifferential.Group(0, 1, "a");
    var good = List.of(new StrictDifferential.Step("find", true, List.of(group)));
    for (var wrong :
        List.of(
            List.<StrictDifferential.Step>of(),
            List.of(new StrictDifferential.Step("find", false, List.of())),
            List.of(
                new StrictDifferential.Step(
                    "find", true, List.of(new StrictDifferential.Group(1, 2, "a")))),
            List.of(new StrictDifferential.Step("find", true, List.of(group, group))))) {
      assertThatThrownBy(() -> StrictDifferential.assertSame("a", "a", good, wrong))
          .isInstanceOf(AssertionError.class);
    }
  }

  @Test
  void knownCaptureDivergenceIsNotWaived() {
    assertThatThrownBy(() -> StrictDifferential.check("(?:(a){1})*$", "ab"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("strict differential divergence");
  }

  @Test
  void oracleBudgetIsReportedAsUnavailable() {
    long before = StrictOracle.UNAVAILABLE.sum();
    assertThat(StrictDifferential.check("(?:(?:a|aa)+)+y", "a".repeat(24) + "x")).isFalse();
    assertThat(StrictOracle.UNAVAILABLE.sum()).isEqualTo(before + 1);
    assertThat(StrictDifferential.check("a", "a")).isTrue();
  }

  @Test
  void enumerateEntireGraduatedPriorityDomain() {
    for (PriorityCases.Case sample : PriorityCases.CASES) {
      for (String input : sample.inputs()) {
        assertThat(StrictDifferential.check(sample.regex(), input)).isTrue();
      }
    }
  }

  @Test
  void historicalPriorityRegressionsRemainObservable() {
    for (String regex : List.of("a+?b?", "(?:a|ab)c?", "a??b?")) {
      for (String input : List.of("aaab", "abc", "ab", "b", "")) {
        assertThat(StrictDifferential.check(regex, input)).isTrue();
      }
    }
  }
}
