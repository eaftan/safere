// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.safere.crosscheck.Matcher;
import org.safere.crosscheck.Pattern;

final class ReplacementFuzzer {

  @FuzzTest(maxDuration = "30s")
  void replacement(FuzzedDataProvider data) {
    String regex = data.consumeString(256);
    int flags = FuzzSupport.consumeFlags(data);
    String input = data.consumeString(2048);
    String replacement = data.consumeRemainingAsString();
    Pattern pattern = FuzzSupport.compileOrSkip(regex, flags);
    if (pattern == null) {
      return;
    }

    try {
      pattern.matcher(input).replaceAll(replacement);
      pattern.matcher(input).replaceFirst(replacement);
      appendReplacementLoop(pattern, input, replacement, data.consumeBoolean());
    } catch (IllegalArgumentException | IndexOutOfBoundsException expected) {
      // Invalid replacement syntax is valid fuzzer input.
    }
  }

  private static void appendReplacementLoop(
      Pattern pattern, String input, String replacement, boolean useStringBuffer) {
    Matcher matcher = pattern.matcher(input);
    if (useStringBuffer) {
      StringBuffer sb = new StringBuffer();
      while (matcher.find()) {
        matcher.appendReplacement(sb, replacement);
      }
      matcher.appendTail(sb);
    } else {
      StringBuilder sb = new StringBuilder();
      while (matcher.find()) {
        matcher.appendReplacement(sb, replacement);
      }
      matcher.appendTail(sb);
    }
  }
}
