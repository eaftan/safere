// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.openjdk.jmh.infra.Blackhole;

class BenchmarkOperationTest {
  @Test
  void utf8CaptureTextDecodesNativeBoundsIncludingBorrowedViewOffsets() {
    byte[] storage = "!!é😀 tail??".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    RegexEngineVariant.RegexInput input =
        RegexEngineVariant.utf8Input(storage, 2, storage.length - 4);
    try (RegexEngineVariant.CompiledRegex pattern =
        RegexEngineVariant.SAFERE_UTF8.compile("(é)(😀)(x)?()")) {
      RegexEngineVariant.MatchCursor cursor = pattern.matcher(input);
      assertThatThrownBy(() -> cursor.group(1)).isInstanceOf(IllegalStateException.class);
      assertThat(cursor.find()).isTrue();
      assertThat(cursor.group(0)).isEqualTo("é😀");
      assertThat(cursor.group(1)).isEqualTo("é");
      assertThat(cursor.group(2)).isEqualTo("😀");
      assertThat(cursor.start(2)).isEqualTo(2);
      assertThat(cursor.end(2)).isEqualTo(6);
      assertThat(cursor.group(3)).isNull();
      assertThat(cursor.group(4)).isEmpty();
      assertThatThrownBy(() -> cursor.group(5)).isInstanceOf(IndexOutOfBoundsException.class);
      cursor.reset();
      assertThat(cursor.find()).isTrue();
      assertThat(cursor.group(0)).isEqualTo("é😀");
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = RegexEngineVariant.class,
      names = {"SAFERE_STRING", "SAFERE_UTF8", "JDK_STRING"})
  void tokenizerAdvancesInNativeCoordinatesAndCanReuseItsMatcher(RegexEngineVariant variant) {
    RegexEngineVariant.RegexInput input =
        variant == RegexEngineVariant.SAFERE_UTF8
            ? RegexEngineVariant.utf8Input(
                "é 12 é ".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            : new RegexEngineVariant.StringRegexInput("é 12 é ");
    try (RegexEngineVariant.CompiledRegex pattern = variant.compile("[^ ]+| +")) {
      BenchmarkOperation operation = BenchmarkOperation.MATCHER_REGION_TOKENIZE;
      DeclarativeBenchmarkPlan.MatcherLifecycle lifecycle =
          new DeclarativeBenchmarkPlan.MatcherLifecycle(
              DeclarativeBenchmarkPlan.MatcherReuse.RETAINED, List.of());
      assertThat(
              operation.execute(
                  variant,
                  List.of("[^ ]+| +"),
                  List.of(pattern),
                  List.of(input),
                  new int[0],
                  null,
                  0,
                  lifecycle,
                  null,
                  0,
                  0,
                  "integer"))
          .isEqualTo(6);
      BenchmarkOperation.BenchmarkTask task =
          operation.bind(
              variant,
              List.of("[^ ]+| +"),
              List.of(pattern),
              List.of(input),
              new int[0],
              null,
              0,
              lifecycle,
              null,
              0,
              0,
              "integer");
      Blackhole blackhole =
          new Blackhole(
              "Today's password is swordfish. I understand instantiating Blackholes directly is"
                  + " dangerous.");
      task.run(blackhole);
      task.run(blackhole);
    }
  }

  @ParameterizedTest
  @CsvSource({
    "MATCHER_REGION_MATCHES, RETAINED",
    "MATCHER_REGION_MATCHES, NEW_PER_INVOCATION",
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
              public boolean matches() {
                calls.add(matcherId + ":matches");
                return true;
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
    boolean region = operation != BenchmarkOperation.MATCHER_RESET_FIND;
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
            0,
            "boolean");
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
                  ? List.of(
                      matcherId + ":region:7:13",
                      matcherId
                          + (operation == BenchmarkOperation.MATCHER_REGION_MATCHES
                              ? ":matches"
                              : ":find"))
                  : List.of(matcherId + ":reset", matcherId + ":find", matcherId + ":find"));
      calls.clear();
    }
  }
}
