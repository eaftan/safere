// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.AfterAll;

/** Strict ASCII membership and whole-match sequences; see TARGETS.md for the domain. */
public final class StrictAsciiFuzzer {
  @AfterAll
  static void reportOracleStats() {
    StrictOracle.report();
  }

  @FuzzTest(maxDuration = "30s")
  void strictAscii(FuzzedDataProvider data) {
    fuzzerTestOneInput(data);
  }

  /** Shared entry point for local Jazzer and OSS-Fuzz. */
  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
    StrictDomain.Sample sample = StrictDomain.generate(data);
    StrictDifferential.check(sample.regex(), StrictDomain.input(data));
    StrictDifferential.check(sample.regex(), sample.witness());
    StrictDifferential.check(sample.regex(), sample.witness() + "!");
  }
}
