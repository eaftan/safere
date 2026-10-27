// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openjdk.jmh.infra.Blackhole;

class BenchmarkOperationTest {
  @ParameterizedTest
  @CsvSource({
    "MATCHER_REGION_FIND, RETAINED",
    "MATCHER_REGION_FIND, NEW_PER_INVOCATION",
    "MATCHER_RESET_FIND, RETAINED",
    "MATCHER_RESET_FIND, NEW_PER_INVOCATION"
  })
  void matcherConstructionFollowsDeclaredLifecycle(
      BenchmarkOperation operation, DeclarativeBenchmarkPlan.MatcherReuse reuse) {
    RegexEngineVariant.RegexInput input = new RegexEngineVariant.StringRegexInput("prefix needle");
    List<RegexEngineVariant.RegexInput> matcherInputs = new ArrayList<>();
    List<String> calls = new ArrayList<>();
    RegexEngineVariant.CompiledRegex pattern =
        new RegexEngineVariant.CompiledRegex() {
          @Override
          public RegexEngineVariant.MatchCursor matcher(
              RegexEngineVariant.RegexInput preparedInput) {
            matcherInputs.add(preparedInput);
            int matcherId = matcherInputs.size();
            return new RegexEngineVariant.MatchCursor() {
              private boolean found;

              @Override
              public void region(int start, int end) {
                calls.add(matcherId + ":region:" + start + ":" + end);
              }

              @Override
              public void reset() {
                calls.add(matcherId + ":reset");
                found = false;
              }

              @Override
              public boolean find() {
                calls.add(matcherId + ":find");
                boolean result = !found;
                found = true;
                return result;
              }
            };
          }
        };
    boolean region = operation == BenchmarkOperation.MATCHER_REGION_FIND;
    DeclarativeBenchmarkPlan.LifecycleStep step =
        region
            ? new DeclarativeBenchmarkPlan.LifecycleStep(
                DeclarativeBenchmarkPlan.LifecycleStepKind.REGION, 7, 13, null)
            : new DeclarativeBenchmarkPlan.LifecycleStep(
                DeclarativeBenchmarkPlan.LifecycleStepKind.RESET, null, null, null);
    BenchmarkOperation.BenchmarkTask task =
        operation.bind(
            RegexEngineVariant.SAFERE_STRING,
            List.of("needle"),
            List.of(pattern),
            List.of(input),
            new int[0],
            null,
            0,
            new DeclarativeBenchmarkPlan.MatcherLifecycle(reuse, List.of(step)),
            null,
            0,
            0);
    boolean retained = reuse == DeclarativeBenchmarkPlan.MatcherReuse.RETAINED;
    assertThat(matcherInputs).hasSize(retained ? 1 : 0);
    assertThat(calls).isEmpty();
    Blackhole blackhole =
        new Blackhole(
            "Today's password is swordfish. I understand instantiating Blackholes directly is"
                + " dangerous.");

    for (int invocation = 1; invocation <= 3; invocation++) {
      task.run(blackhole);

      assertThat(matcherInputs)
          .hasSize(retained ? 1 : invocation)
          .allSatisfy(preparedInput -> assertThat(preparedInput).isSameAs(input));
      int matcherId = retained ? 1 : invocation;
      assertThat(calls)
          .containsExactlyElementsOf(
              region
                  ? List.of(matcherId + ":region:7:13", matcherId + ":find")
                  : List.of(matcherId + ":reset", matcherId + ":find", matcherId + ":find"));
      calls.clear();
    }
  }
}
