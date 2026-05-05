// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

final class ReplacementFuzzer {

  @FuzzTest(maxDuration = "30s")
  void replacement(FuzzedDataProvider data) {
    String regex = data.consumeString(256);
    int flags = FuzzSupport.consumeFlags(data);
    String input = data.consumeString(2048);
    String replacement = data.consumeRemainingAsString();
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, flags);
    if (pattern == null) {
      return;
    }

    if (!pattern.matcher(input).replaceAll(replacement)
        || !pattern.matcher(input).replaceFirst(replacement)) {
      return;
    }
    appendReplacementLoop(pattern, input, replacement, data.consumeBoolean());
  }

  private static void appendReplacementLoop(
      FuzzSupport.CompiledPattern pattern, String input, String replacement,
      boolean useStringBuffer) {
    FuzzSupport.MatcherPair matcher = pattern.matcher(input);
    if (useStringBuffer) {
      StringBuffer sb = new StringBuffer();
      while (matcher.find()) {
        if (!matcher.appendReplacement(sb, replacement)) {
          return;
        }
      }
      matcher.appendTail(sb);
    } else {
      StringBuilder sb = new StringBuilder();
      while (matcher.find()) {
        if (!matcher.appendReplacement(sb, replacement)) {
          return;
        }
      }
      matcher.appendTail(sb);
    }
  }
}
