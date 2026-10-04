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
    for (String regex : new String[] {"a+b", "^a+b$", "(a)?b", ".|"}) {
      seen.addAll(EngineEquivalenceFuzzer.check(regex, "aaab", 0));
      seen.addAll(EngineEquivalenceFuzzer.check(regex, "é😀aaab", 0));
    }
    assertThat(seen)
        .contains(
            MatchStrategy.DFA, MatchStrategy.ONE_PASS, MatchStrategy.BIT_STATE, MatchStrategy.NFA);
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
