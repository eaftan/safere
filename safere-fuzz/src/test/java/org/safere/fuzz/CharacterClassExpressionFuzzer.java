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
      "", "&", "\\&", "\\Q&\\E", "-\\D", "-a", "-&&", "\\Q\\E-\\D"
  };
  private static final Separator[] SEPARATORS = {
      new Separator("", false),
      new Separator("\\Q\\E", false),
      new Separator("\\Q\\E\\Q\\E", false),
      new Separator(" ", true),
      new Separator(" #x\n", true),
      new Separator("\\Q\\E ", true),
      new Separator(" \\Q\\E", true)
  };
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
      List.of("", "a", "b", "c", "&", "-", "0", "1", "9", "A", "Z", "_", "`", "x", " ",
          "\t", "Ā", "é", "\n");
  private static final String[] REGRESSION_REGEXES = {
      "[\\d&&&-\\D]",
      "[\\d&&&\\Q\\E-\\D]",
      "(?x)[a&&& -\\D]",
      "(?x)[a&&& #x\n -\\D]",
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
      "(?x)[0&\\Q\\E\\Q\\E&&& #x\n-&&]",
      "(?x)[0&\\Q\\E\\Q\\E&&&& #x\n-&&]",
      "(?x)[0&\\Q\\E\\Q\\E&&&&&& #x\n-&&]",
      "(?x)[a\\d&& [0]&]",
      "(?x)[a[b]&& [a]&]",
      "(?x)[^ab\\p{javaLowerCase}&&\\Q\\E [a]&]"
  };

  @FuzzTest(maxDuration = "30s")
  void characterClassExpressions(FuzzedDataProvider data) {
    for (String regex : REGRESSION_REGEXES) {
      FuzzSupport.assertFullMatchesJdk(regex, 0, INPUTS);
    }

    boolean comments = data.consumeBoolean();
    boolean negated = data.consumeBoolean();
    String prefix = (comments ? "(?x)" : "") + "[" + (negated ? "^" : "");
    String regex = switch (data.consumeInt(0, 2)) {
      case 0 -> prefix
          + data.pickValue(BASE_PIECES)
          + data.pickValue(BASE_PIECES)
          + pickSeparator(data, comments)
          + data.pickValue(OPERATORS)
          + pickSeparator(data, comments)
          + data.pickValue(RIGHT_PIECES)
          + data.pickValue(TRAILING_PIECES)
          + "]";
      case 1 -> prefix
          + data.pickValue(BASE_PIECES)
          + data.pickValue(AMPERSAND_PIECES)
          + pickSeparator(data, comments)
          + data.pickValue(OPERATORS)
          + pickSeparator(data, comments)
          + data.pickValue(RIGHT_PIECES)
          + data.pickValue(TRAILING_PIECES)
          + "]";
      case 2 -> prefix
          + pickSeparator(data, comments)
          + data.pickValue(OPERATORS)
          + pickSeparator(data, comments)
          + data.pickValue(RIGHT_PIECES)
          + data.pickValue(TRAILING_PIECES)
          + "]";
      default -> throw new AssertionError();
    };

    FuzzSupport.assertFullMatchesJdk(regex, 0, INPUTS);
  }

  private static String pickSeparator(FuzzedDataProvider data, boolean comments) {
    Separator separator;
    do {
      separator = data.pickValue(SEPARATORS);
    } while (separator.commentsModeOnly() && !comments);
    return separator.text();
  }

  private record Separator(String text, boolean commentsModeOnly) {}
}
