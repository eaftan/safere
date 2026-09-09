// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;

public final class ParserStackSafetyFuzzer {

  private static final List<String> INPUTS = List.of("", "a", "b");

  @FuzzTest(maxDuration = "30s")
  void parserStackSafety(FuzzedDataProvider data) {
    fuzzerTestOneInput(data);
  }

  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
    for (int depth : List.of(1, 8, 64, 512)) {
      FuzzSupport.assertFullMatchesJdk(nestedCharacterClass(depth), 0, INPUTS);
      FuzzSupport.assertFullMatchesJdk(nestedGroups(depth), 0, INPUTS);
      FuzzSupport.assertFullMatchesJdk(quantifiedNestedCaptures(depth), 0, INPUTS);
      FuzzSupport.assertFullMatchesJdk(nestedCountedRepeat(depth, "{0,2}"), 0, INPUTS);
      FuzzSupport.assertFullMatchesJdk(nestedQuantifiers(Math.min(depth, 64)), 0, INPUTS);
      FuzzSupport.assertFullMatchesJdk(
          homogeneousGapNesting(Math.min(depth, 64), "[ab]"), 0, INPUTS);
      FuzzSupport.assertFullMatchesJdk(homogeneousGapNesting(Math.min(depth, 64), "."), 0, INPUTS);
    }

    int depth = data.consumeInt(0, 512);
    switch (data.consumeInt(0, 4)) {
      case 0 -> FuzzSupport.assertFullMatchesJdk(nestedCharacterClass(depth), 0, INPUTS);
      case 1 -> FuzzSupport.assertFullMatchesJdk(nestedGroups(depth), 0, INPUTS);
      case 2 -> FuzzSupport.assertFullMatchesJdk(quantifiedNestedCaptures(depth), 0, INPUTS);
      case 3 -> {
        String quantifier = data.pickValue(List.of("{0}", "{1}", "{0,2}", "{1,2}"));
        FuzzSupport.assertFullMatchesJdk(nestedCountedRepeat(depth, quantifier), 0, INPUTS);
      }
      default ->
          FuzzSupport.assertFullMatchesJdk(nestedQuantifiers(data.consumeInt(0, 64)), 0, INPUTS);
    }
  }

  private static String nestedCharacterClass(int depth) {
    return "[".repeat(depth + 1) + "a" + "]".repeat(depth + 1);
  }

  private static String nestedGroups(int depth) {
    return "(?:".repeat(depth) + "a" + ")".repeat(depth);
  }

  private static String quantifiedNestedCaptures(int depth) {
    return "(".repeat(depth) + "a" + ")".repeat(depth) + "*";
  }

  private static String nestedCountedRepeat(int depth, String quantifier) {
    return nestedGroups(depth) + quantifier;
  }

  private static String nestedQuantifiers(int depth) {
    StringBuilder regex = new StringBuilder(depth * 6 + 1);
    regex.append("(?:".repeat(depth));
    regex.append('a');
    for (int index = depth - 1; index >= 0; index--) {
      regex.append(index % 2 == 0 ? ")?" : ")+");
    }
    return regex.toString();
  }

  private static String homogeneousGapNesting(int depth, String atom) {
    return "foo" + "(?:".repeat(depth) + atom + (")?" + atom).repeat(depth) + "bar";
  }
}
