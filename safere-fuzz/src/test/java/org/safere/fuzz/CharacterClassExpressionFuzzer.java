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
      List.of(
          "", "a", "b", "c", "&", "-", "0", "1", "9", "A", "Z", "_", "`", "x", " ", "\t", "Ā", "é",
          "\n", "]");
  private static final String[] REGRESSION_REGEXES = {
    "[\\d&&&-\\D]",
    "[\\d&&&\\Q\\E-\\D]",
    "(?x)[a&&& -\\D]",
    "(?x)[a&&& #x\n -\\D]",
    "[&\\Q\\E &&\\d]",
    "[b&&[a]&]",
    "[^b&&[a]&]",
    "(?x)[\\Qab\\E\\d &&\\Q\\E &-&]",
    "(?x)[\\Qa\\E\\d &&\\Q\\E &-&]",
    "(?x)[\\Qab\\E\\w &&\\Q\\E &-&]",
    "(?x)[\\Qab\\E\\d &&\\Q\\E &-a]",
    "(?x)[ab\\d &&  &]",
    "(?x)[a\\d && &]",
    "(?x)[a\\d && &\\d]",
    "(?x)[a\\d&&&-&]",
    "(?x)[a\\d&&[a] && &]",
    "(?x)[ab\\d&&[a] && &-a]",
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
    "[0&\\Q\\E\\Q\\E&&&&&&-&&]",
    "[0&\\Q\\E\\Q\\E&&&&&&-&]",
    "[0&\\Q\\E\\Q\\E&&&&&&-&a]",
    "[0&\\Q\\E\\Q\\E&&&&&&\\Q\\E-&&]",
    "(?x)[0&\\Q\\E\\Q\\E&&&&&&-&&]",
    "(?x)[a& &&&& -& &]",
    "(?x)[a& &&&& -&\\Q\\E&]",
    "(?x)[a& &&&& -& #x\n&]",
    "(?x)[a& &&&& -a& ]",
    "(?x)[a& &&&& -a\\Q\\E& ]",
    "(?x)[a& &&&& -a&]",
    "(?x)[a& &&&& & &]",
    "(?x)[a& &&&& & &\\Q\\E]",
    "(?x)[a& &&&& & & ]",
    "(?x)[a& & ]",
    "(?x)[a& ]]",
    "(?x)[a& & ]]",
    "(?x)[a& ]]]",
    "(?x)[a& &&&& & & ]]",
    "(?x)[& [^b]]",
    "(?x)[a& [^b]]",
    "(?x)[& & [^b]]",
    "(?x)[a& & [^b]]",
    "(?x)[a& &&&& --z]",
    "(?x)[a\\d&& [0]&]",
    "(?x)[a[b]&& [a]&]",
    "(?x)[a\\d&& &&&]",
    "(?x)[a\\d&&\\Q\\E &&&]",
    "(?x)[a && &-&]",
    "[a\\d&&&-&&]",
    "[ab\\d&&&-&&a]",
    "[a\\d&&&\\Q\\E-&]",
    "[a\\d&&&\\Q\\E\\Q\\E-&]",
    "[a&&&\\Q\\E&&-&]",
    "[a&&&\\Q\\E&&-a]",
    "[\\&&&&\\Q\\E&&-&]",
    "[\\&&&&&&\\Q\\E&&-a]",
    "(?x)[0&\\Q\\E\\Q\\E&& -&&a]",
    "[&&\\Q\\E&&&]",
    "[[a]a&&\\Q\\E&&&]",
    "(?x)[&&[a]&&& ]",
    "(?x)[&&[a]&&&#x\n]",
    "(?x)[&&[a]\\Q\\E&&& ]",
    "(?x)[&&[a][b]&&& ]",
    "(?x)[a\\d&&&&\\Q\\E&-&]",
    "(?x)[a\\d&&&&[a]&]",
    "(?x)[ab\\d&&&[a]&&&]",
    "[0&\\Q\\E\\Q\\E&&&[a]&&&]",
    "[a&&&[a][b]&&&]",
    "(?x)[ab\\d&&&[a] && &]",
    "(?x)[ab\\d&&&[a] && &-a]",
    "(?x)[ab\\d&&&&[ab] && &-a]",
    "(?x)[[a]a && [b]&-&]",
    "(?x)[a&&&&[b] && &-&]",
    "(?x)[0&\\Q\\E\\Q\\E&&[a] && &-&]",
    "(?x)[ && [a] &&& &]",
    "(?x)[ && [a] &&& &-a]",
    "(?x)[ && [a] &&& &&]",
    "(?x)[ && [a] &&& [b]]",
    "(?x)[a && [b] &&& &]",
    "(?x)[ab && [b] &&& &]",
    "(?x)[&&[a]&&&\\Q\\E&]",
    "(?x)[&&[a]&&&&& &]",
    "(?x)[&&[b]&&&#x\n&-a]",
    "(?x)[&&[^b]&&&\\Q\\E&-a]",
    "(?x)[a&&[b]&&&\\Q\\E&&]",
    "(?x)[[a]a&&[b]&&&#x\n&]",
    "(?x)[-[ab] &&& &&& &&]",
    "(?x)[&&[a] &&& &&& &&]",
    "(?x)[a&&[b] &&& &&]",
    "(?x)[a &&& & && b]",
    "(?x)[a &&& #x\n& && b]",
    "(?x)[a&&&\\Q\\E&& a-b]",
    "(?x)[a&&&\\Q\\E&& [a]]",
    "(?x)[a&&&\\Q\\E&&&[b]&-&]",
    "(?x)[a&&\\Q\\E&\\Q\\E&&\\w]",
    "(?x)[a&&\\Q\\E&\\Q\\E&&[a]]",
    "(?x)[a&&\\Q\\E&\\Q\\E&&[b]&-&]",
    "(?x)[a&&\\&&&& &&-&]",
    "(?x)[a&&\\&&&& [b]&-&]",
    "(?x)[a&&&\\Q\\E&& &-a]",
    "(?x)[a&&&\\Q\\E&& #x\n\\Q\\E-&]",
    "(?x)[a&&&\\Q\\E&&&\\Q\\E[b]&]",
    "(?x)[a&\\Q\\E&& \\Q\\E]]",
    "(?x)[\\Qab\\E\\d &&\\Q\\E &-&]",
    "[a-b&\\Q\\E&&[a]]",
    "[a-b&\\Q\\E\\Q\\E&&[b]-a]",
    "(?x)[a-b& &&[a]]",
    "(?x)[a& &&&& & & ]",
    "(?x)[a& &&&& && ]",
    "(?x)[-[ab]&&& && ]",
    "(?x)[a& &&&& & & ]]",
    "(?x)[a& &&&& && ]]",
    "(?x)[-[ab]&&& && ]]",
    "(?x)[&&[a]&&& && ]]",
    "[0&\\Q\\E\\Q\\E&&\\Q\\E[a]&&&]",
    "[0&\\Q\\E\\Q\\E&&\\Q\\E[a][b]&&&]",
    "[0&&& [a]&&&]",
    "[0&&& #x\n [a]&&&]",
    "[0&&&\\Q\\E [a]&&&]",
    "(?x)[[0]&\\Q\\E\\Q\\E&&[a] && &]",
    "(?x)[[0]&\\Q\\E\\Q\\E&&[a] && &-&]",
    "(?x)[\\w&\\Q\\E&&[a] && &]",
    "(?x)[\\Qab\\EĀ&&& #x\n\\Ā]",
    "(?x)[\\Qab\\EĀ&&& #x\n\\Ā&]",
    "(?x)[\\Qab\\EĀ&&& #x\n\\d]",
    "(?x)[\\Qab\\EĀ&&& #x\n\\d&]",
    "(?x)[\\Qab\\EĀ&&& #x\n\\d-a]",
    "(?x)[\\Qab\\EĀ&&&& #x\n&-a]",
    "(?x)[\\Qab\\EĀ&&\\Q\\E&-&&]",
    "(?x)[&&[a] && &]",
    "(?x)[&&[b] && &-&]",
    "(?x)[a& &&&& -z]",
    "(?x)[\\Qab\\E& &&&&&& \\Q\\E\\Q\\E-\\D]",
    "[0-1ab&&[a]&]",
    "[^0-1ab&&[a]&]",
    "(?x)[^0-1\\Qab\\E\\Q\\E\\Q\\E&& [a]&]",
    "(?x)[^ab\\p{javaLowerCase}&&\\Q\\E [a]&]",
    "(?x)[^a&&\\&\\Q\\E\\Q\\E&&& #x\n]]",
    "(?x)[b&&\\Q\\E& &&&\\Q\\E\\Q\\E[a]&]",
    "(?x)[b&&&& & &&&[a]&]",
    "(?x)[\\Qab\\E &&&& & &&& [a]&]",
    "(?x)[0&&&&& &&& &&-a]",
    "(?x)[\\d&&& && \\Q\\E&-a]",
    "(?x)[\\w&&&&& #x\n&&& #x\n&&-a]",
    "(?x)[^0&&&&&&& &&& &&-a]",
    "(?x)[^\\w&&&&& &&& &&-a]",
    "(?x)[[^b]&&& #x\n\\Q\\E&-&]",
    "(?x)[&a-b &&&&& &\\&]",
    "(?x)[0&&& &&&\\Q\\E-a]",
    "(?x)[[^b][a]&&&\\Q\\E b]",
    "(?x)[\\D[a]&&& -a]",
    "(?x)[[^b][a]\\Q\\E\\Q\\E&&& \\Q\\E]]",
    "(?x)[^0&&& & #x\n&& \\Q\\E]]",
    "(?x)[a&&&[a]&&& #x\nb]",
    "(?x)[a&&&[a]&&& #x\n\\Qa\\E]",
    "(?x)[a&&&[b]&&&\\Q\\E b]",
    "(?x)[a&&&&&[b]&&& #x\nb]",
    "(?x)[a&&\\&&&& [a]\\Q\\E-\\D]",
    "(?x)[a&&\\Q&\\E&&& [b]\\Q\\E-\\D]",
    "(?x)[^a&&\\&&&& [ab]\\Q\\E-\\D]",
    "[a&&&-&&-a]",
    "[&&[a]&-&&-&]",
    "[a-b&&&-&\\Q\\E\\Q\\E&-&]",
    "(?x)[&&[a]-&\\Q\\E -a]",
    "(?x)[\\d&&[a]-&\\Q\\E -a]",
    "(?x)[a&&[b]&-& \\Q\\E\\P{Lower}]",
    "(?x)[a&&\\&&&& [a]-&]",
    "(?x)[a&&\\Q&\\E&&& [b]-&]",
    "(?x)[a #x\n&&&& \\&&&& [a]-&&]",
    "(?x)[&&\\d-&\\Q\\E]",
    "(?x)[\\Q&\\E&&\\d-&\\Q\\E]",
    "(?x)[&&[ab]-&\\Q\\E]",
    "[a&&[b]&-&-&]",
    "(?x)[a&&[b]&-&-&]",
    "(?x)[^a&&[b]&-&-&]",
    "(?x)[a-b&&&[a]&&& #x\n\\Qa\\E]",
    "(?x)[[a]&&&[a]&&& #x\n\\Qa\\E]",
    "(?x)[a&&&\\Q\\E]]",
    "(?x)[a-b&&&\\Q\\E]]",
    "(?x)[[a]&&&\\Q\\E]]",
    "(?x)[^a&&&\\Q\\E]]",
    "(?x)[a&&&&&\\Q\\E\\Q\\E]]",
    "(?x)[a&&&\\Q\\E&&\\Q\\E]]",
    "(?x)[^a&&&&&\\Q\\E\\Q\\E]]"
  };

  @FuzzTest(maxDuration = "30s")
  void characterClassExpressions(FuzzedDataProvider data) {
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
