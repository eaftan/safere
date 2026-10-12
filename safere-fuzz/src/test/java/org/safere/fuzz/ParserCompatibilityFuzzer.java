// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;
import java.util.Objects;
import org.safere.Matcher;
import org.safere.Pattern;

public final class ParserCompatibilityFuzzer {

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
    "\\b",
    "\\B",
    "\\b{g}",
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
  private static final String[] CONNECTORS = {
    "",
    "",
    "|",
    "?",
    "??",
    "*",
    "*?",
    "* #q\n ?",
    "* #q\n +",
    "* #q\n + +",
    "+",
    "+?",
    "+ #q\n ?",
    "+ #q\n +",
    "+ #q\n + +",
    "{0}",
    "{00}",
    "{1,3}",
    "{01,03}",
    "{1}",
    "{01}",
    "{2}+",
    "{0,2}+",
    "{1} #q\n ?",
    "{1} #q\n +",
    "{1} #q\n + +",
    "??{1,3}",
    "??{01,03}",
    "?{1,3}",
    "?{01,03}",
    "*{1,3}",
    "*{01,03}",
    "+{1,3}",
    "+{0}?+",
    "+{0}?+{1}",
    "+{1}{0}?+?",
    "+++",
    "++?{0,2}?",
    "+{01,03}",
    "{1,3}{1,3}",
    "{01,03}{02}"
  };
  private static final String[] SUFFIXES = {"", "$", "?", "*", "+", "{2}", "{02}", "{1,3}"};
  private static final String[] LEADING_COUNTED_REPEATS = {
    "{0}",
    "{00}",
    "{1}",
    "{01}",
    "{1,3}",
    "{01,03}",
    "{1,}",
    "{01,}",
    "{1,3}?",
    "{01,03}?",
    "{1,3}$",
    "{01,03}$",
    "^{1,3}",
    "^{01,03}",
    "a|{1,3}",
    "a|{01,03}",
    "({1,3})",
    "({01,03})"
  };
  private static final String[] COMMENT_TERMINATED_PREFIXES = {
    "a#\0", "a#\n", "a#\r", "a#\u0085", "a#\u2028", "a#\u2029"
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
    fuzzerTestOneInput(data);
  }

  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
    int flags = FuzzSupport.consumeParserFlags(data);
    String regex;
    if (data.consumeBoolean()) {
      flags |= Pattern.COMMENTS;
      regex =
          data.pickValue(COMMENT_TERMINATED_PREFIXES) + data.pickValue(MALFORMED_GROUP_SUFFIXES);
    } else if (data.consumeBoolean()) {
      regex = data.pickValue(PREFIXES) + data.pickValue(LEADING_COUNTED_REPEATS);
    } else {
      regex =
          data.pickValue(PREFIXES)
              + data.pickValue(ATOMS)
              + data.pickValue(CONNECTORS)
              + data.pickValue(ATOMS)
              + data.pickValue(SUFFIXES);
    }
    FuzzSupport.assertFullMatchesJdk(regex, flags, INPUTS);
    // Append consumers to preserve the interpretation of the existing corpus.
    assertOperandFreePossessiveCountedRepeatMatchesJdk(data);
  }

  private static void assertOperandFreePossessiveCountedRepeatMatchesJdk(FuzzedDataProvider data) {
    String prefix = data.pickValue(List.of("", "\\Q\\E", "(?i)", "a*", "a*?", "a{2}", "x|"));
    String count = data.pickValue(List.of("{0}", "{1}", "{1,2}", "{1,}"));
    boolean comments = data.consumeBoolean();
    String modifier = comments && data.consumeBoolean() ? " #q\n +" : "+";
    String suffix = data.pickValue(List.of("a", "", "[ab]"));
    String expression = prefix + count + modifier + suffix;
    String regex = data.consumeBoolean() ? "(" + expression + ")" : expression;
    int flags = comments ? Pattern.COMMENTS : 0;
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex, flags);
    // These generated forms all have an implicit empty operand. Assert directly so the broad
    // unsupported-feature waiver cannot hide their acceptance or matching behavior (#873).
    Pattern safeRe = Pattern.compile(regex, flags);
    for (String input : List.of("", "a", "aa", "A", "ab", "x", "xa", "b")) {
      java.util.regex.Matcher expected = jdk.matcher(input);
      Matcher actual = safeRe.matcher(input);
      boolean expectedMatch = expected.matches();
      if (actual.matches() != expectedMatch) {
        throw new AssertionError("Operand-free possessive count divergence for /" + regex + "/");
      }
      if (expectedMatch) {
        for (int group = 0; group <= expected.groupCount(); group++) {
          if (!Objects.equals(actual.group(group), expected.group(group))) {
            throw new AssertionError(
                "Operand-free possessive capture divergence for /" + regex + "/");
          }
        }
      }
    }
  }
}
