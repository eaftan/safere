// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.safere.crosscheck.Matcher;
import org.safere.crosscheck.Pattern;

final class RegionBoundsFuzzer {

  @FuzzTest(maxDuration = "30s")
  void regionBounds(FuzzedDataProvider data) {
    String regex = data.consumeString(256);
    int flags = FuzzSupport.consumeFlags(data);
    String input = data.consumeString(2048);
    Pattern pattern = FuzzSupport.compileOrSkip(regex, flags);
    if (pattern == null) {
      return;
    }

    Matcher matcher = pattern.matcher(input);
    int[] region = FuzzSupport.consumeRegion(data, input);
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
