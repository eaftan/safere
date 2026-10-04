// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Instrumented, operation-specific work bounds for forced NFA matching. */
@EnabledIfSystemProperty(named = "safere.fuzz.workCounters", matches = "true")
public final class WorkBoundFuzzer {
  @FuzzTest(maxDuration = "30s")
  void work(FuzzedDataProvider data) {
    fuzzerTestOneInput(data);
  }

  /** Requires a library built with the work-counters Maven profile. */
  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
    check(data.consumeInt(1, 16), data.consumeInt(16, 256), data.consumeBoolean());
  }

  static void check(int width, int length, boolean unicode) {
    Pattern pattern =
        Pattern.compile(
            "(?:a?){" + width + "}b",
            0,
            EnginePathOptions.builder()
                .literalFastPaths(false)
                .charClassMatchFastPaths(false)
                .keywordAlternationFastPath(false)
                .startAcceleration(false)
                .shiftDfa(false)
                .onePass(false)
                .dfa(false)
                .bitState(false)
                .build());
    String input = (unicode ? "é" : "a").repeat(length);
    long work = WorkCounter.countForTesting(() -> pattern.matcher(input).matches());
    assertThat(work).as("instrumentation must execute").isPositive();
    // A forced NFA visits each instruction a bounded number of times per input position.
    // Budget scales with compiled program size, not just subject size; compilation is excluded.
    long bound = 64L * pattern.prog().size() * (input.length() + 1L);
    assertThat(work).as("NFA work versus program/input bound").isLessThanOrEqualTo(bound);
  }
}
