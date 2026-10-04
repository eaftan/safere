// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

/** Small syntax, arbitrary UTF-16 subjects, and reviewed stateful operation sequences. */
public final class StrictStateFuzzer {
  @FuzzTest(maxDuration = "30s")
  void state(FuzzedDataProvider data) {
    fuzzerTestOneInput(data);
  }

  /** Shared local and standalone entry point. */
  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
    String regex = StrictDomain.generate(data).regex();
    String input = StrictDomain.input(data);
    int start = data.consumeInt(0, input.length());
    int end = data.consumeInt(start, input.length());
    // Split-surrogate region ends are a documented intentional scalar-quantifier boundary.
    // Interior starts remain included, as do arbitrary lone surrogates.
    if (end > 0
        && end < input.length()
        && Character.isHighSurrogate(input.charAt(end - 1))
        && Character.isLowSurrogate(input.charAt(end))) {
      end++;
    }
    StrictStateChecks.check(regex, input, start, end, data.consumeBoolean(), data.consumeBoolean());
  }
}
