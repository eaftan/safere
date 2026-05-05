// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;

final class EscapeSyntaxFuzzer {

  private static final String[] PREFIXES = {"", "^", "(?i)", "(?x)", "[", "[^"};
  private static final String[] ESCAPES = {
      "\\0",
      "\\00",
      "\\07",
      "\\08",
      "\\077",
      "\\078",
      "\\123",
      "\\400",
      "\\777",
      "\\u0041",
      "\\u{41}",
      "\\x41",
      "\\x{41}",
      "\\e",
      "\\cA",
      "\\ca",
      "\\©",
      "\\Ā",
      "\\é",
      "\\☃",
      "\\😀",
      "\\Q\\E",
      "\\Q&\\E",
      "\\Q-\\E",
      "\\Qab\\E"
  };
  private static final String[] SUFFIXES = {"", "$", "]", "a", "-", "-z", "&&[a]"};
  private static final List<String> INPUTS =
      List.of(
          "",
          "a",
          "A",
          "0",
          "7",
          "@",
          "\u001b",
          "&",
          "-",
          "©",
          "Ā",
          "é",
          "☃",
          "😀",
          "\u0000");
  private static final String[] REGRESSION_REGEXES = {
      "^\\©",
      "[\\©]",
      "\\Ā",
      "[\\Ā]",
      "\\☃",
      "[\\☃]",
      "\\😀",
      "[\\😀]",
      "\\0",
      "\\08",
      "\\400",
      "\\777",
      "\\123",
      "(a)\\12"
  };

  @FuzzTest(maxDuration = "30s")
  void escapeSyntax(FuzzedDataProvider data) {
    for (String regex : REGRESSION_REGEXES) {
      FuzzSupport.assertFullMatchesJdk(regex, 0, INPUTS);
    }

    String prefix = data.pickValue(PREFIXES);
    String suffix = data.pickValue(SUFFIXES);
    if (prefix.startsWith("[") && !suffix.contains("]")) {
      suffix = suffix + "]";
    }
    if (!prefix.startsWith("[") && suffix.equals("]")) {
      suffix = "";
    }

    String regex = prefix + data.pickValue(ESCAPES) + suffix;
    FuzzSupport.assertFullMatchesJdk(regex, FuzzSupport.consumeParserFlags(data), INPUTS);
  }
}
