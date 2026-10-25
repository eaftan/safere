// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;
import java.util.regex.PatternSyntaxException;

public final class CharacterClassExpressionFuzzer {

  private static final String[] BASE_PIECES = {
    "",
    "a",
    "ab",
    "a-b",
    " ^a",
    "#c\n^a",
    "!-&",
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
    "\\p\\Q\\EL",
    "\\p\\QL\\E",
    "\\p{ L}",
    "\\p{L }",
    "\\c\\Qa\\E",
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
  private static final Separator[] SEPARATORS = {
    new Separator("", false),
    new Separator("\\Q\\E", false),
    new Separator("\\Q\\E\\Q\\E", false),
    new Separator(" ", true),
    new Separator(" #x\n", true),
    new Separator("\\Q\\E ", true),
    new Separator(" \\Q\\E", true)
  };
  private static final String[] OPERATORS = {"&&"};
  private static final String[] RIGHT_PIECES = {
    "",
    "a",
    "b",
    "a-b",
    " ^a",
    "#c\n^a",
    "!-&",
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
    "\\p\\Q\\EL",
    "\\p\\QL\\E",
    "\\p{ L}",
    "\\p{L }",
    "\\c\\Qa\\E",
    "\\P{Lower}",
    "\\p{javaLowerCase}"
  };
  private static final List<String> INPUTS =
      List.of(
          "", "a", "b", "c", "^", "&", "-", "0", "1", "9", "A", "Z", "_", "`", "x", " ", "\t", "Ā",
          "é", "\n", "]", "!", ".", "\\", "!]", "-]", "A]");
  private static final String[] REGRESSION_REGEXES = {
    "[\\Qab\\E&&[b]]",
    "[[ab]&&[^b]]",
    "[&\\Q\\E &&\\d]",
    "[a-z&&[def]]",
    "[a-z&&[^bc]]",
    "[a-z&&d-f&&e]",
    "[!-&&]",
    "(?x)[ ^a]",
    "(?x)[#c\n^a]",
    "[&-&&]",
    "[\\Q&\\E-&&]"
  };

  @FuzzTest(maxDuration = "30s")
  void characterClassExpressions(FuzzedDataProvider data) {
    fuzzerTestOneInput(data);
  }

  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
    // #1070: model checks use unspaced references, not the JDK's trivia-sensitive parsing.
    assertRangeTriviaIgnored("(?x)[!-", " ", "]]");
    assertRangeTriviaIgnored("(?x)[!-", " ", "]a]");
    assertRangeTriviaIgnored("(?x)[!-", " #c\n", "]]");
    assertRangeTriviaIgnored("(?x)[!-", " ", "[A]]");
    assertRangeTriviaIgnored("(?x)[a-", " #c\n", "]");
    for (String regex : REGRESSION_REGEXES) {
      FuzzSupport.assertFullMatchesJdk(regex, 0, INPUTS);
    }

    boolean comments = data.consumeBoolean();
    boolean negated = data.consumeBoolean();
    String prefix = (comments ? "(?x)" : "") + "[" + (negated ? "^" : "");
    String regex =
        switch (data.consumeInt(0, 2)) {
          case 0 ->
              prefix
                  + data.pickValue(BASE_PIECES)
                  + data.pickValue(BASE_PIECES)
                  + pickSeparator(data, comments)
                  + data.pickValue(OPERATORS)
                  + pickSeparator(data, comments)
                  + data.pickValue(RIGHT_PIECES)
                  + data.pickValue(TRAILING_PIECES)
                  + "]";
          case 1 ->
              prefix
                  + data.pickValue(BASE_PIECES)
                  + data.pickValue(AMPERSAND_PIECES)
                  + pickSeparator(data, comments)
                  + data.pickValue(OPERATORS)
                  + pickSeparator(data, comments)
                  + data.pickValue(RIGHT_PIECES)
                  + data.pickValue(TRAILING_PIECES)
                  + "]";
          case 2 ->
              prefix
                  + pickSeparator(data, comments)
                  + data.pickValue(OPERATORS)
                  + pickSeparator(data, comments)
                  + data.pickValue(RIGHT_PIECES)
                  + data.pickValue(TRAILING_PIECES)
                  + "]";
          default -> throw new AssertionError();
        };

    FuzzSupport.assertFullMatchesJdk(regex, 0, INPUTS);
    // Append the range-shape axis so saved inputs retain their original expression.
    String before = prefix + data.pickValue(List.of("!", "A", "\\x41", "\\Q!\\E")) + "-";
    String gap = pickSeparator(data, comments);
    String after =
        data.pickValue(List.of("[", "]")) + data.pickValue(List.of("", "a", "\\d", "[A-Z]")) + "]";
    assertRangeTriviaIgnored(before, gap, after);
    // Broad differential discovery still reports intentional differences; never waive them.
    FuzzSupport.assertFullMatchesJdk(before + gap + after, 0, INPUTS);
  }

  private static void assertRangeTriviaIgnored(String before, String gap, String after) {
    String regex = before + gap + after;
    java.util.regex.Pattern reference;
    try {
      reference = java.util.regex.Pattern.compile(before + after);
    } catch (PatternSyntaxException e) {
      assertThatThrownBy(() -> org.safere.Pattern.compile(regex))
          .as("range trivia preserves rejection of /%s/ in /%s/", before + after, regex)
          .isInstanceOf(PatternSyntaxException.class);
      return;
    }
    var pattern = org.safere.Pattern.compile(regex);
    for (String input : INPUTS) {
      assertThat(pattern.matcher(input).matches())
          .as("/%s/ equivalent to unspaced /%s/ on <%s>", regex, before + after, input)
          .isEqualTo(reference.matcher(input).matches());
    }
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
