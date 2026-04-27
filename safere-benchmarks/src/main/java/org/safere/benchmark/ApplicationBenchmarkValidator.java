// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.Map;

/** Smoke utility for validating data-driven application benchmark cases. */
public final class ApplicationBenchmarkValidator {

  private ApplicationBenchmarkValidator() {}

  /** Loads and validates all application benchmark cases. */
  public static void main(String[] args) {
    Map<String, ApplicationCase> cases = BenchmarkData.get().getApplicationCases();
    if (cases.isEmpty()) {
      throw new IllegalArgumentException("No application benchmark cases configured");
    }
    for (ApplicationCase appCase : cases.values()) {
      org.safere.Pattern pattern = org.safere.Pattern.compile(appCase.pattern);
      if (appCase.op.startsWith("findAll") && pattern.matcher("").find()) {
        throw new IllegalArgumentException(
            appCase.name + " uses an empty-width pattern with find-all op " + appCase.op);
      }
    }
  }
}
