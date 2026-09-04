// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;

final class CharacterClassExpressionFuzzer {

  private static final String[] BASE_PIECES = {
    "",
    "a",
    "ab",
    "a-b",
    "0",
    "0-1",
    "&",
    "\\&",
    "\\Q&\\E",
    "\\Qa\\E",
    "\\Qab\\E",
    "\\Q\\E",
    "Ā",
    "\\Ā",
    "[a]",
    "[b]",
    "[ab]",
    "[^b]",
    "\\d",
    "\\D",
    "\\w",
    "\\W",
    "\\p{Lower}",
    "\\P{Lower}",
    "\\p{javaLowerCase}"
  };
  private static final String[] AMPERSAND_PIECES = {"&", "\\&", "\\Q&\\E"};
  private static final String[] TRAILING_PIECES = {
    "",
    "&",
    "\\&",
    "\\Q&\\E",
    "-\\D",
    "-a",
    "-&",
    "&-&",
    "&-a",
    "-& -a",
    "-&\\Q\\E -a",
    "-&a",
    "-&&",
    "&-&&-&",
    "&-& \\P{Lower}",
    "\\Q\\E-\\D",
    "\\Q\\E]"
  };
  private static final String[] SEPARATORS = {"", "\\Q\\E", "\\Q\\E\\Q\\E"};
  private static final String[] OPERATORS = {"&&", "&&&", "&&&&", "&&&&&", "&&&&&&"};
  private static final String[] RIGHT_PIECES = {
    "",
    "a",
    "b",
    "a-b",
    "0",
    "0-1",
    "&",
    "\\&",
    "\\Q&\\E",
    "\\Qa\\E",
    "\\Q\\E",
    "Ā",
    "\\Ā",
    "[a]",
    "[b]",
    "[ab]",
    "\\d",
    "\\D",
    "\\w",
    "\\p{Lower}",
    "\\P{Lower}",
    "\\p{javaLowerCase}"
  };
  private static final List<String> INPUTS =
      List.of(
          "", "a", "b", "c", "&", "-", "0", "1", "9", "A", "Z", "_", "`", "x", " ", "\t", "Ā", "é",
          "\n", "]");
  private static final String[] REGRESSION_REGEXES = {
    "[\\d&&&-\\D]",
    "[\\d&&&\\Q\\E-\\D]",
    "[&\\Q\\E &&\\d]",
    "[b&&[a]&]",
    "[^b&&[a]&]",
    "[&&abc]",
    "[a&&&&b]",
    "[ [a]&&]",
    "[ &&&]",
    "[&&[x]-&&a]",
    "[ab\\Q\\E\\Q\\E&&&&&\\Q\\E&\\&]",
    "[a\\Q\\E&&\\Q\\E\\Q\\E&-\\D]",
    "[\\&\\Q\\E&&&&&\\Q\\E\\Q\\E&-\\D]",
    "[\\Q&\\E&&\\Q\\E&-\\D]",
    "[[^b]&\\Q\\E\\Q\\E&&&&\\Q\\E&\\&]",
    "[^[^b]&\\Q\\E\\Q\\E&&&&\\Q\\E&\\&]",
    "[[^b]&\\Q\\E\\Q\\E&&&&\\Q\\E&-\\D]",
    "[^[^b]&\\Q\\E\\Q\\E&&&&\\Q\\E&-\\D]",
    "[&&[a]&-a]",
    "[&&[a]&-&&]",
    "[a\\d&&&\\Q\\E&]",
    "[^[^b]&\\Q\\E&&\\Q\\E-&&]",
    "[0&\\Q\\E\\Q\\E&&&&&&-&&]",
    "[0&\\Q\\E\\Q\\E&&&&&&-&]",
    "[0&\\Q\\E\\Q\\E&&&&&&-&a]",
    "[0&\\Q\\E\\Q\\E&&&&&&\\Q\\E-&&]",
    "[a\\d&&&-&&]",
    "[ab\\d&&&-&&a]",
    "[a\\d&&&\\Q\\E-&]",
    "[a\\d&&&\\Q\\E\\Q\\E-&]",
    "[a&&&\\Q\\E&&-&]",
    "[a&&&\\Q\\E&&-a]",
    "[\\&&&&\\Q\\E&&-&]",
    "[\\&&&&&&\\Q\\E&&-a]",
    "[&&\\Q\\E&&&]",
    "[[a]a&&\\Q\\E&&&]",
    "[0&\\Q\\E\\Q\\E&&&[a]&&&]",
    "[a&&&[a][b]&&&]",
    "[a-b&\\Q\\E&&[a]]",
    "[a-b&\\Q\\E\\Q\\E&&[b]-a]",
    "[0&\\Q\\E\\Q\\E&&\\Q\\E[a]&&&]",
    "[0&\\Q\\E\\Q\\E&&\\Q\\E[a][b]&&&]",
    "[0&&& [a]&&&]",
    "[0&&& #x\n [a]&&&]",
    "[0&&&\\Q\\E [a]&&&]",
    "[0-1ab&&[a]&]",
    "[^0-1ab&&[a]&]",
    "[a&&&-&&-a]",
    "[&&[a]&-&&-&]",
    "[a-b&&&-&\\Q\\E\\Q\\E&-&]",
    "[a&&[b]&-&-&]"
  };

  @FuzzTest(maxDuration = "30s")
  void characterClassExpressions(FuzzedDataProvider data) {
    for (String regex : REGRESSION_REGEXES) {
      FuzzSupport.assertFullMatchesJdk(regex, 0, INPUTS);
    }

    boolean negated = data.consumeBoolean();
    String prefix = "[" + (negated ? "^" : "");
    String regex =
        switch (data.consumeInt(0, 2)) {
          case 0 ->
              prefix
                  + data.pickValue(BASE_PIECES)
                  + data.pickValue(BASE_PIECES)
                  + data.pickValue(SEPARATORS)
                  + data.pickValue(OPERATORS)
                  + data.pickValue(SEPARATORS)
                  + data.pickValue(RIGHT_PIECES)
                  + data.pickValue(TRAILING_PIECES)
                  + "]";
          case 1 ->
              prefix
                  + data.pickValue(BASE_PIECES)
                  + data.pickValue(AMPERSAND_PIECES)
                  + data.pickValue(SEPARATORS)
                  + data.pickValue(OPERATORS)
                  + data.pickValue(SEPARATORS)
                  + data.pickValue(RIGHT_PIECES)
                  + data.pickValue(TRAILING_PIECES)
                  + "]";
          case 2 ->
              prefix
                  + data.pickValue(SEPARATORS)
                  + data.pickValue(OPERATORS)
                  + data.pickValue(SEPARATORS)
                  + data.pickValue(RIGHT_PIECES)
                  + data.pickValue(TRAILING_PIECES)
                  + "]";
          default -> throw new AssertionError();
        };

    FuzzSupport.assertFullMatchesJdk(regex, 0, INPUTS);
  }
}
