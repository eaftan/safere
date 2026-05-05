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
      "\\Q\\E\\Q\\E",
      "\\Q*\\E",
      "^",
      "$",
      "a\\Q\\E",
      "[a]",
      "[^a]",
      "[a-z]",
      "[a-z&&[def]]",
      "()",
      "(?)",
      "(a)",
      "(?:a)",
      "(?<name>a)",
      "a\u001C\\^]",
      "a\u001D\\^]",
      "a\u001E\\^]",
      "a\u001F\\^]",
      "[a\u001Cb]",
      "[a\u001Db]",
      "[a\u001Eb]",
      "[a\u001Fb]"
  };
  private static final String[] PREFIXES = {"", "^", "(?i)", "(?x)", "(?m)", "(?s)"};
  private static final String[] CONNECTORS =
      {"", "", "|", "?", "??", "*", "*?", "+", "+?", "{0}", "{1,3}",
          "{1}", "??{1,3}", "?{1,3}", "*{1,3}", "+{1,3}", "{1,3}{1,3}"};
  private static final String[] SUFFIXES = {"", "$", "?", "*", "+", "{2}", "{1,3}"};
  private static final String[] COMMENT_TERMINATED_PREFIXES = {
      "a#\0",
      "a#\n",
      "a#\r",
      "a#\u0085",
      "a#\u2028",
      "a#\u2029"
  };
  private static final String[] MALFORMED_GROUP_SUFFIXES = {"(", "|(", "b|(", "(?:b)|("};
  private static final List<String> INPUTS =
      List.of(
          "",
          "a",
          "aa",
          "abc",
          "def",
          "0",
          " ",
          "\n",
          "*",
          "name",
          "a\u001C^]",
          "a\u001D^]",
          "a\u001E^]",
          "a\u001F^]",
          "a^]",
          "\u001C",
          "\u001D",
          "\u001E",
          "\u001F");

  @FuzzTest(maxDuration = "30s")
  void parserCompatibility(FuzzedDataProvider data) {
    int flags = FuzzSupport.consumeParserFlags(data);
    String regex;
    if (data.consumeBoolean()) {
      flags |= org.safere.Pattern.COMMENTS;
      regex = data.pickValue(COMMENT_TERMINATED_PREFIXES)
          + data.pickValue(MALFORMED_GROUP_SUFFIXES);
    } else {
      regex = data.pickValue(PREFIXES)
          + data.pickValue(ATOMS)
          + data.pickValue(CONNECTORS)
          + data.pickValue(ATOMS)
          + data.pickValue(SUFFIXES);
    }
    FuzzSupport.assertFullMatchesJdk(regex, flags, INPUTS);
  }
}
