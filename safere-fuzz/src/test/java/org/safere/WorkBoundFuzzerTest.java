// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "safere.fuzz.workCounters", matches = "true")
final class WorkBoundFuzzerTest {
  @Test
  void zeroWorkPrefiltersAndNonemptySubjectsRemainWithinTheBound() {
    for (int width : new int[] {1, 8, 16}) {
      for (int length : new int[] {0, 16, 32, 256}) {
        WorkBoundFuzzer.check(width, length, false);
        WorkBoundFuzzer.check(width, length, true);
      }
    }
  }
}
