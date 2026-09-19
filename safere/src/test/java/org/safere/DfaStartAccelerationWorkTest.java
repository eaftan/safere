// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Deterministic accounting of useful skips and recovery in DFA start acceleration. */
@DisabledForCrosscheck("WorkCounter and acceleration policies are SafeRE implementation details")
@Tag("work-counter")
class DfaStartAccelerationWorkTest {
  private static final String REGEX = "record:[^\\r\\n;]*val=200";
  private static final String MATCH = "record:target_val=200";
  private static final int DENSE_RECORD_COUNT = 30_000;

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void denseStartsRetainProfitableSkips(boolean utf8, boolean nonAscii) {
    for (int padding : new int[] {16, 32, 48, 80}) {
      String unit = "record:" + (nonAscii ? "é" : "x") + ";" + "x".repeat(padding) + "\n";
      String input = unit.repeat(2_048) + MATCH;

      WorkCounter.StartAccelerationWork work = findWork(input, utf8);

      assertThat(work.calls()).as("padding=%s", padding).isPositive();
      assertThat(work.skippedUnits())
          .as("Profitable calls must keep bypassing DFA work, padding=%s", padding)
          .isGreaterThan(inputLength(input, utf8) * 2L / 5);
      assertThat(work.quarantines()).as("padding=%s", padding).isZero();
    }
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void startsWithNegligibleSkipsBackOff(boolean utf8, boolean nonAscii) {
    String unit = "record:" + (nonAscii ? "é" : "x") + ";\n";
    String input = unit.repeat(DENSE_RECORD_COUNT) + MATCH;

    WorkCounter.StartAccelerationWork work = findWork(input, utf8);

    // The general loop can skip the trailing newline; the ASCII loop already consumed it.
    assertThat(work.skippedUnits()).isLessThanOrEqualTo(work.calls());
    assertThat(work.calls()).isBetween(1L, DENSE_RECORD_COUNT / 100L);
    assertThat(work.largestQuarantine()).isEqualTo(AcceleratorPolicy.LITERAL.maxQuarantineWindow());
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void scalarProgressCannotRestoreUnprofitableAcceleration(boolean utf8, boolean nonAscii) {
    // Offsetting the candidate stride from power-of-two quarantine windows must not make the
    // distance covered by the scalar DFA look like work saved by the next accelerator call.
    for (int padding : new int[] {1, 2, 3}) {
      String unit = "record:" + (nonAscii ? "é" : "x") + ";" + "x".repeat(padding) + "\n";
      String input = unit.repeat(DENSE_RECORD_COUNT) + MATCH;

      WorkCounter.StartAccelerationWork work = findWork(input, utf8);

      assertThat(work.calls()).as("padding=%s", padding).isBetween(1L, DENSE_RECORD_COUNT / 100L);
      assertThat(work.largestQuarantine())
          .as("padding=%s", padding)
          .isEqualTo(AcceleratorPolicy.LITERAL.maxQuarantineWindow());
    }
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void breakEvenSkipsCannotEraseLossesFromOtherCalls(boolean utf8, boolean nonAscii) {
    String candidate = "record:" + (nonAscii ? "é" : "x") + ";";
    String unit =
        candidate
            + "x".repeat(AcceleratorPolicy.LITERAL.minProfitableSkip())
            + "\n"
            + candidate
            + "\n";
    String input = unit.repeat(DENSE_RECORD_COUNT / 2) + MATCH;

    WorkCounter.StartAccelerationWork work = findWork(input, utf8);

    assertThat(work.calls()).isBetween(1L, DENSE_RECORD_COUNT / 100L);
    assertThat(work.largestQuarantine()).isEqualTo(AcceleratorPolicy.LITERAL.maxQuarantineWindow());
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void profitableBatchesCanIncludePartiallyUsefulCalls(boolean utf8, boolean nonAscii) {
    String candidate = "record:" + (nonAscii ? "é" : "x") + ";";
    int cost = AcceleratorPolicy.LITERAL.minProfitableSkip();
    // Twenty half-cost skips lose less than the sixteen-call allowance. The larger skip pays
    // back that deficit: counting every partially useful call as a full strike defeats too early.
    String batch =
        (candidate + "x".repeat(cost / 2) + "\n").repeat(20)
            + candidate
            + "x".repeat(cost * 12)
            + "\n";
    String input = batch.repeat(512) + MATCH;

    WorkCounter.StartAccelerationWork work = findWork(input, utf8);

    assertThat(work.skippedUnits()).isGreaterThan(inputLength(input, utf8) / 2L);
    assertThat(work.quarantines()).isZero();
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void usefulSkipsRestoreAccelerationAfterDenseStarts(boolean utf8, boolean nonAscii) {
    String candidate = "record:" + (nonAscii ? "é" : "x") + ";";
    String dense = (candidate + "\n").repeat(DENSE_RECORD_COUNT);
    String sparse = (candidate + "x".repeat(512) + "\n").repeat(1_024);

    WorkCounter.StartAccelerationWork work = findWork(dense + sparse + MATCH, utf8);

    assertThat(work.largestQuarantine()).isEqualTo(AcceleratorPolicy.LITERAL.maxQuarantineWindow());
    assertThat(work.skippedUnits()).isGreaterThan(inputLength(sparse, utf8) * 3L / 4);
  }

  private static WorkCounter.StartAccelerationWork findWork(String input, boolean utf8) {
    Pattern pattern = Pattern.compile(REGEX);
    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(REGEX).matcher(input);
    assertThat(jdk.find()).isTrue();
    if (utf8) {
      Utf8Matcher matcher = pattern.matcher(Utf8Input.validated(input.getBytes(UTF_8)));
      assertThat(matcher.find()).isTrue();
      matcher.reset();
      WorkCounter.StartAccelerationWork work =
          WorkCounter.countStartAccelerationForTesting(() -> assertThat(matcher.find()).isTrue());
      assertThat(matcher.start()).isEqualTo(input.substring(0, jdk.start()).getBytes(UTF_8).length);
      assertThat(matcher.end()).isEqualTo(input.substring(0, jdk.end()).getBytes(UTF_8).length);
      return work;
    }
    Matcher matcher = pattern.matcher(input);
    assertThat(matcher.find()).isTrue();
    matcher.reset();
    WorkCounter.StartAccelerationWork work =
        WorkCounter.countStartAccelerationForTesting(() -> assertThat(matcher.find()).isTrue());
    assertThat(matcher.start()).isEqualTo(jdk.start());
    assertThat(matcher.end()).isEqualTo(jdk.end());
    return work;
  }

  private static int inputLength(String input, boolean utf8) {
    return utf8 ? input.getBytes(UTF_8).length : input.length();
  }
}
