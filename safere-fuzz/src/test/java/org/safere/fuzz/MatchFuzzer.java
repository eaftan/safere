// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

final class MatchFuzzer {

  @FuzzTest(maxDuration = "30s")
  void match(FuzzedDataProvider data) {
    String regex;
    int flags;
    String input;
    if (data.consumeBoolean()) {
      String atom = distinctLiteralRun(data.consumeInt(16, 192));
      int repetitions = data.consumeInt(1, 16);
      regex = "(?:" + atom + "){" + repetitions + "}";
      flags = 0;
      input = data.consumeBoolean() ? atom.repeat(repetitions) : data.consumeString(512);
    } else {
      regex = data.consumeString(256);
      flags = FuzzSupport.consumeFlags(data);
      input = data.consumeRemainingAsString();
    }
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, flags);
    if (pattern == null) {
      return;
    }

    FuzzSupport.MatcherPair matcher = pattern.matcher(input);
    matcher.matches();
    matcher.reset();
    matcher.lookingAt();
    matcher.reset();
    matcher.find();
    matcher.reset();
    matcher.find(FuzzSupport.consumeIndex(data, input));
  }

  private static String distinctLiteralRun(int count) {
    StringBuilder pattern = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      pattern.appendCodePoint(0x1000 + i * 2);
    }
    return pattern.toString();
  }
}
