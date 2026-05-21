// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

final class FindSequenceFuzzer {

  @FuzzTest(maxDuration = "30s")
  void sequence(FuzzedDataProvider data) {
    String regex;
    int flags;
    String input;
    if (data.consumeBoolean()) {
      regex = nestedCapturingGroups(data.consumeInt(0, 512)) + "*";
      flags = 0;
      input = data.consumeBoolean() ? "a" : "a!";
    } else {
      regex = data.consumeString(256);
      flags = FuzzSupport.consumeFlags(data);
      input = data.consumeString(2048);
    }
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, flags);
    if (pattern == null) {
      return;
    }

    FuzzSupport.MatcherPair findWalker = pattern.matcher(input);
    int maxFinds = Math.min(input.length() + 2, 64);
    for (int i = 0; i < maxFinds; i++) {
      boolean found = findWalker.find();
      if (!found) {
        break;
      }
    }

    FuzzSupport.MatcherPair matcher = pattern.matcher(input);
    boolean hasMatch = false;
    int steps = data.consumeInt(1, 32);
    for (int i = 0; i < steps; i++) {
      switch (data.consumeInt(0, 9)) {
        case 0 -> hasMatch = matcher.matches();
        case 1 -> hasMatch = matcher.lookingAt();
        case 2 -> hasMatch = matcher.find();
        case 3 -> hasMatch = matcher.find(FuzzSupport.consumeIndex(data, input));
        case 4 -> {
          matcher.reset();
          hasMatch = false;
        }
        case 5 -> {
          input = data.consumeString(2048);
          matcher.reset(input);
          hasMatch = false;
        }
        case 6 -> matcher.groupCount();
        case 7 -> {
          if (hasMatch) {
            int group = data.consumeInt(0, matcher.groupCount());
            matcher.group(group);
            matcher.start(group);
            matcher.end(group);
          }
        }
        case 8 -> {
          int[] region = FuzzSupport.consumeRegion(data, input);
          matcher.region(region[0], region[1]);
          hasMatch = false;
        }
        case 9 -> {
          matcher.useAnchoringBounds(data.consumeBoolean());
          matcher.useTransparentBounds(data.consumeBoolean());
        }
        default -> throw new AssertionError();
      }
    }
  }

  private static String nestedCapturingGroups(int depth) {
    return "(".repeat(depth) + "a" + ")".repeat(depth);
  }
}
