// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class EngineEquivalenceFuzzerTest {
  @Test
  void diagnosticsProveExecutionOfDistinctEngines() {
    Set<MatchStrategy> seen = EnumSet.noneOf(MatchStrategy.class);
    var previous = Pattern.diagnostics();
    Pattern.setDiagnostics(
        new SafeReMatchDiagnostics() {
          @Override
          public void onOperationCompleted(OperationDiagnostics event) {
            seen.add(event.boundaryStrategy());
            seen.add(event.captureStrategy());
          }
        });
    try {
      for (String regex : new String[] {"a+b", "^a+b$", "(a)?b", ".|"}) {
        EngineEquivalenceFuzzer.check(regex, "aaab", 0);
        EngineEquivalenceFuzzer.check(regex, "é😀aaab", 0);
      }
    } finally {
      Pattern.setDiagnostics(previous);
    }
    assertThat(seen)
        .contains(
            MatchStrategy.DFA, MatchStrategy.ONE_PASS, MatchStrategy.BIT_STATE, MatchStrategy.NFA);
  }

  @Test
  void modelChecksPreserveAndExerciseTheCallersDiagnostics() {
    var previous = Pattern.diagnostics();
    int[] operations = {0};
    var listener =
        new SafeReMatchDiagnostics() {
          @Override
          public void onOperationCompleted(OperationDiagnostics event) {
            operations[0]++;
          }
        };
    Pattern.setDiagnostics(listener);
    try {
      EngineEquivalenceFuzzer.check("a+b", "aaab", 0);
      assertThat(Pattern.diagnostics()).isSameAs(listener);
      assertThat(operations[0]).isPositive();
    } finally {
      Pattern.setDiagnostics(previous);
    }
  }

  @Test
  void emptyFindContinuationUsesEachRepresentationsOwnProgression() {
    EngineEquivalenceFuzzer.check("a|", "😀aaa", 0);
    EngineEquivalenceFuzzer.check("", "😀", 0);
  }

  @Test
  void surrogateInteriorFindAndUnicodePathsAgree() {
    for (String input : new String[] {"\udb7d\udf02A", "\ud800A", "é😀中a", "\udc00a"}) {
      for (int start = 0; start <= input.length(); start++)
        EngineEquivalenceFuzzer.check(".|", input, start);
    }
  }
}
