// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;

public final class EscapeSyntaxFuzzer {

  // "[\\t-" makes each escape the end of a class range.
  private static final String[] PREFIXES = {"", "^", "(?i)", "(?x)", "[", "[^", "[\\t-"};
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
    "\\h",
    "\\H",
    "\\v",
    "\\V",
    "\\e",
    "\\cA",
    "\\ca",
    "\\c\t",
    "\\c#\na",
    "\\c!",
    "\\cĀ",
    "\\c\uD800",
    "\\c",
    "\\c😀",
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
  private static final String[] SUFFIXES = {
    "",
    "$",
    "]",
    "*",
    "+",
    "?",
    "2*",
    "22*",
    "a",
    "-",
    "-z",
    "&&[a]",
    "\\Q\\E-z",
    " -z",
    "- z",
    "\\-z"
  };
  private static final List<String> INPUTS =
      List.of(
          "", "a", "A", "0", "7", "@", "\u001b", "&", "-", "©", "Ā", "é", "☃", "😀", "𠀀", "🙀",
          "\u0000", "\u0140", "\uD800", "\uD840", "\uf57f");
  // Reviewed intentional differences have separate expected-outcome tests, not fixed agreement
  // probes. FuzzSupportTest pins unsupported backreference outcomes. Prefix/escape/suffix
  // generation still explores those spellings and the ambiguous class-range spellings.
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
    "\\h",
    "\\H",
    "\\v",
    "\\V",
    "\\c!",
    "[\\c!]",
    "^\\c",
    "[\\c]",
    "\\cĀ",
    "[\\cĀ]",
    "\\c😀",
    "(?x)\\c\ta",
    "(?x)\\c#\na",
    "[^\\c\uD800]",
    "(?x)[\\v -z]",
    "[\\v\\-z]",
    "[\\t\\-\\v]"
  };

  @FuzzTest(maxDuration = "30s")
  void escapeSyntax(FuzzedDataProvider data) {
    fuzzerTestOneInput(data);
  }

  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
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
    String introducer = data.pickValue(List.of("\\p", "\\P", "\\c", "\\x", "\\u"));
    String gap = data.pickValue(List.of("", "\\Q\\E", "\\Q\\E\\Q\\E", " ", "#c\n"));
    String component = data.pickValue(List.of("L", "{L}", "{ L}", "{L }", "a", "41", "0041"));
    if (data.consumeBoolean()) component = "\\Q" + component + "\\E";
    String expression = introducer + gap + component;
    if (data.consumeBoolean()) expression = "[" + expression + "]";
    FuzzSupport.assertFullMatchesJdk(expression, FuzzSupport.consumeParserFlags(data), INPUTS);
    String high = data.pickValue(List.of("\\uD83D", "\\uD840", "\\uD800"));
    String low = data.pickValue(List.of("DE00", "DC00", "D800", "0041"));
    String between = data.pickValue(List.of("", " ", "\\Q\\E", "#c\n"));
    String digitGap = data.pickValue(List.of("", " ", "\\Q\\E", "#c\n"));
    String pair = high + between + "\\u" + low.substring(0, 2) + digitGap + low.substring(2);
    if (data.consumeBoolean()) pair += "\\uDE00";
    if (data.consumeBoolean()) pair = "[" + pair + "]";
    FuzzSupport.assertFullMatchesJdk(pair, FuzzSupport.consumeParserFlags(data), INPUTS);
  }
}
