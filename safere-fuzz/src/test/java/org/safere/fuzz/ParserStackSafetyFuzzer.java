// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;

final class ParserStackSafetyFuzzer {

  private static final List<String> INPUTS = List.of("", "a", "b");

  @FuzzTest(maxDuration = "30s")
  void parserStackSafety(FuzzedDataProvider data) {
    for (int depth : List.of(1, 8, 64, 512)) {
      FuzzSupport.assertFullMatchesJdk(nestedCharacterClass(depth), 0, INPUTS);
      FuzzSupport.assertFullMatchesJdk(nestedGroups(depth), 0, INPUTS);
    }

    int depth = data.consumeInt(0, 512);
    if (data.consumeBoolean()) {
      FuzzSupport.assertFullMatchesJdk(nestedCharacterClass(depth), 0, INPUTS);
    } else {
      FuzzSupport.assertFullMatchesJdk(nestedGroups(depth), 0, INPUTS);
    }
  }

  private static String nestedCharacterClass(int depth) {
    return "[".repeat(depth + 1) + "a" + "]".repeat(depth + 1);
  }

  private static String nestedGroups(int depth) {
    return "(?:".repeat(depth) + "a" + ")".repeat(depth);
  }
}
