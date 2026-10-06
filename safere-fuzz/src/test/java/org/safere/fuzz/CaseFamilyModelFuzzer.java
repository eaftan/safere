// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

/** Checks the documented SafeRE case-family model, including intentional JDK differences. */
public final class CaseFamilyModelFuzzer {
  @FuzzTest(maxDuration = "30s")
  void caseFamily(FuzzedDataProvider data) {
    fuzzerTestOneInput(data);
  }

  /** Shared entry point for local Jazzer and OSS-Fuzz. */
  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
    CaseFamilyChecks.assertCaseFamilyClosureSafeRe(data);
  }
}
