// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;

final class DialectSyntaxFuzzer {

  private static final String[] CONTEXT_PREFIXES = {"", "^", "(?:", "a|", "[", "[^"};
  private static final String[] DIALECT_FRAGMENTS = {
      "(?P<name>a)",
      "(?P=name)",
      "(?'name'a)",
      "\\g{name}",
      "\\g1",
      "\\p{Braille}",
      "\\P{Braille}",
      "\\p{Latin}",
      "\\p{^Braille}",
      "\\p{InBraille}",
      "\\p{IsLatin}",
      "[:lower:]",
      "[:alpha:]",
      "[:digit:]",
      "[:^space:]",
      "[.ch.]",
      "[=a=]",
      "\\C",
      "\\R",
      "\\K"
  };
  private static final String[] CONTEXT_SUFFIXES = {"", "$", ")", "b", "]", "&&[a]]"};
  private static final List<String> INPUTS =
      List.of("", "a", "b", "Braille", "Latin", ":", "[", "]", "l", "o", "w", "e", "r");
  private static final String[] REGRESSION_REGEXES = {
      "(?P<name>a)",
      "\\p{Braille}",
      "\\p{Latin}",
      "[[:lower:]]",
      "[[:^space:]]",
      "\\Q{?\\E",
      "{?",
      "a{,2}",
      "a{2,1}"
  };

  @FuzzTest(maxDuration = "30s")
  void dialectSyntax(FuzzedDataProvider data) {
    for (String regex : REGRESSION_REGEXES) {
      FuzzSupport.assertFullMatchesJdk(regex, 0, INPUTS);
    }

    String prefix = data.pickValue(CONTEXT_PREFIXES);
    String suffix = data.pickValue(CONTEXT_SUFFIXES);
    if (prefix.startsWith("[") && !suffix.contains("]")) {
      suffix = suffix + "]";
    }
    if (!prefix.startsWith("[") && suffix.startsWith("]")) {
      suffix = "";
    }
    if (prefix.equals("(?:") && !suffix.startsWith(")")) {
      suffix = ")" + suffix;
    }

    String regex = prefix + data.pickValue(DIALECT_FRAGMENTS) + suffix;
    FuzzSupport.assertFullMatchesJdk(regex, FuzzSupport.consumeParserFlags(data), INPUTS);
  }
}
