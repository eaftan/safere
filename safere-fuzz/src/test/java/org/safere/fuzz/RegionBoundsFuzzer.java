// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;

final class RegionBoundsFuzzer {
  private static final List<String> SURROGATE_BOUNDARY_REGEXES =
      List.of("[^a]", ".", "\\P{Cs}", "[^\\p{Cs}]");

  @FuzzTest(maxDuration = "30s")
  void regionBounds(FuzzedDataProvider data) {
    String regex;
    int flags;
    String input;
    int[] forcedRegion = null;
    if (data.consumeBoolean()) {
      regex = data.pickValue(SURROGATE_BOUNDARY_REGEXES);
      flags = data.consumeBoolean() ? org.safere.Pattern.DOTALL : 0;
      input = "\uD83D\uDE00" + data.consumeString(32);
      forcedRegion = data.consumeBoolean() ? new int[] {0, 1} : new int[] {1, 2};
    } else {
      regex = data.consumeString(256);
      flags = FuzzSupport.consumeFlags(data);
      input = data.consumeString(2048);
    }
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, flags);
    if (pattern == null) {
      return;
    }

    FuzzSupport.MatcherPair matcher = pattern.matcher(input);
    int[] region = forcedRegion != null ? forcedRegion : FuzzSupport.consumeRegion(data, input);
    matcher.region(region[0], region[1]);
    matcher.useAnchoringBounds(data.consumeBoolean());
    matcher.useTransparentBounds(data.consumeBoolean());
    matcher.regionStart();
    matcher.regionEnd();
    matcher.hasAnchoringBounds();
    matcher.hasTransparentBounds();
    matcher.find();
    matcher.reset();
    matcher.region(region[0], region[1]);
    matcher.matches();
    matcher.reset();
    matcher.region(region[0], region[1]);
    matcher.lookingAt();
  }
}
