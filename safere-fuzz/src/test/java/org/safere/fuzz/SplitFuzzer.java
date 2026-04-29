// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.safere.crosscheck.Pattern;

final class SplitFuzzer {

  @FuzzTest(maxDuration = "30s")
  void split(FuzzedDataProvider data) {
    String regex = data.consumeString(256);
    int flags = FuzzSupport.consumeFlags(data);
    String input = data.consumeString(2048);
    int limit = data.consumeInt(-16, 16);
    Pattern pattern = FuzzSupport.compileOrSkip(regex, flags);
    if (pattern == null) {
      return;
    }

    pattern.split(input);
    pattern.split(input, limit);
    pattern.splitWithDelimiters(input);
    pattern.splitWithDelimiters(input, limit);
  }
}
