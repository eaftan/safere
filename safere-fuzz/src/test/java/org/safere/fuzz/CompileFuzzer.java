// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.regex.PatternSyntaxException;
import org.safere.crosscheck.Pattern;

final class CompileFuzzer {

  @FuzzTest(maxDuration = "30s")
  void compile(FuzzedDataProvider data) {
    int flags = FuzzSupport.consumeFlags(data);
    String regex = data.consumeRemainingAsString();
    try {
      Pattern.compile(regex, flags);
    } catch (PatternSyntaxException expected) {
      // Invalid or intentionally unsupported patterns are valid fuzzer inputs.
    }
  }
}
