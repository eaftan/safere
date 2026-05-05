// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;

final class ParserCompatibilityFuzzer {

  private static final String[] ATOMS = {
      "",
      "a",
      ".",
      "\\d",
      "\\D",
      "\\w",
      "\\W",
      "\\s",
      "\\S",
      "\\p{Lower}",
      "\\P{Lower}",
      "\\Q\\E",
      "\\Q*\\E",
      "[a]",
      "[^a]",
      "[a-z]",
      "[a-z&&[def]]",
      "()",
      "(?)",
      "(a)",
      "(?:a)",
      "(?<name>a)"
  };
  private static final String[] PREFIXES = {"", "^", "(?i)", "(?x)", "(?m)", "(?s)"};
  private static final String[] CONNECTORS =
      {"", "", "|", "?", "??", "*", "*?", "+", "+?", "{0}", "{1,3}"};
  private static final String[] SUFFIXES = {"", "$", "?", "*", "+", "{2}", "{1,3}"};
  private static final List<String> INPUTS =
      List.of("", "a", "aa", "abc", "def", "0", " ", "\n", "*", "name");

  @FuzzTest(maxDuration = "30s")
  void parserCompatibility(FuzzedDataProvider data) {
    String regex = data.pickValue(PREFIXES)
        + data.pickValue(ATOMS)
        + data.pickValue(CONNECTORS)
        + data.pickValue(ATOMS)
        + data.pickValue(SUFFIXES);
    FuzzSupport.assertFullMatchesJdk(regex, FuzzSupport.consumeParserFlags(data), INPUTS);
  }
}
