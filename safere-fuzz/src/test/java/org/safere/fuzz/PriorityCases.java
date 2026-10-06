// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import java.util.List;

/** Finite priority domain extracted from the existing matching regressions. */
final class PriorityCases {
  record Case(String regex, List<String> inputs) {}

  static final List<Case> CASES =
      List.of(
          new Case("a+?b?", List.of("aaab", "aab", "ab")),
          new Case("(?:a|ab)c?", List.of("abc", "ab", "ac")),
          new Case("a??b?", List.of("ab", "a", "b")));

  private PriorityCases() {}

  static Case choose(FuzzedDataProvider data) {
    return data.pickValue(CASES);
  }
}
