// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;

final class MatchFuzzer {
  private static final int CI = org.safere.Pattern.CASE_INSENSITIVE;
  private static final int CI_U =
      org.safere.Pattern.CASE_INSENSITIVE | org.safere.Pattern.UNICODE_CASE;
  private static final int CI_UCC =
      org.safere.Pattern.CASE_INSENSITIVE | org.safere.Pattern.UNICODE_CHARACTER_CLASS;

  private static final List<RegressionCase> CASE_FOLDING_REGRESSIONS =
      List.of(
          new RegressionCase("\\p{Lt}{4}", CI, List.of("abcd", "AAAA", "\u01C4".repeat(4))),
          new RegressionCase("\\p{javaUpperCase}", CI, List.of("\u00AA", "\u02B0", "A", "a", "1")),
          new RegressionCase("[h-j]+", CI_U, List.of("\u0130\u0131", "HIJ", "abc")),
          new RegressionCase("[^h-j]", CI_U, List.of("\u0130", "\u0131", "x")),
          new RegressionCase("[\\x{17F}]", CI_U, List.of("\u017F", "S", "s")),
          new RegressionCase("[K]", CI, List.of("\u212A", "K", "k")),
          new RegressionCase("\\p{L}", CI_U, List.of("\u0345", "\u0399", "A", "a")),
          new RegressionCase("\\P{L}", CI_U, List.of("\u0345", "\u0399", "A", "a")),
          new RegressionCase("[A-Z]", CI_U, List.of("\u017F", "K", "k")),
          new RegressionCase("[K-K]", CI_U, List.of("K", "k")),
          new RegressionCase("\\p{Lower}", CI_U, List.of("\u212A", "A", "a")),
          new RegressionCase("\\p{Lower}", CI_UCC, List.of("\u0345", "\u212A", "A", "a")),
          new RegressionCase("\\x{49}", CI_U, List.of("\u0130", "\u0131", "I", "i")),
          new RegressionCase("\\x{69}", CI_U, List.of("\u0130", "\u0131", "I", "i")));

  private static final List<RegressionCase> CASE_FOLDING_MODEL_REGRESSIONS =
      List.of(
          new RegressionCase("[A-Z]", CI_U, List.of("\u0130", "\u212A")),
          new RegressionCase("[I-I]", CI_U, List.of("\u0130")),
          new RegressionCase("[K-K]", CI_U, List.of("\u212A")));

  @FuzzTest(maxDuration = "30s")
  void match(FuzzedDataProvider data) {
    for (RegressionCase regression : CASE_FOLDING_REGRESSIONS) {
      FuzzSupport.assertFullMatchesJdk(regression.regex(), regression.flags(), regression.inputs());
    }
    for (RegressionCase regression : CASE_FOLDING_MODEL_REGRESSIONS) {
      assertFullMatchesSafeRe(regression.regex(), regression.flags(), regression.inputs());
    }

    String regex;
    int flags;
    String input;
    if (data.consumeBoolean()) {
      String atom = distinctLiteralRun(data.consumeInt(16, 192));
      int repetitions = data.consumeInt(1, 16);
      regex = "(?:" + atom + "){" + repetitions + "}";
      flags = 0;
      input = data.consumeBoolean() ? atom.repeat(repetitions) : data.consumeString(512);
    } else {
      regex = data.consumeString(256);
      flags = FuzzSupport.consumeFlags(data);
      input = data.consumeRemainingAsString();
    }
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, flags);
    if (pattern == null) {
      return;
    }

    FuzzSupport.MatcherPair matcher = pattern.matcher(input);
    matcher.matches();
    matcher.reset();
    matcher.lookingAt();
    matcher.reset();
    matcher.find();
    matcher.reset();
    matcher.find(FuzzSupport.consumeIndex(data, input));
  }

  private static String distinctLiteralRun(int count) {
    StringBuilder pattern = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      pattern.appendCodePoint(0x1000 + i * 2);
    }
    return pattern.toString();
  }

  private static void assertFullMatchesSafeRe(String regex, int flags, List<String> inputs) {
    org.safere.Pattern pattern = org.safere.Pattern.compile(regex, flags);
    for (String input : inputs) {
      if (!pattern.matcher(input).matches()) {
        throw new AssertionError(
            "SafeRE model regression"
                + "\nRegex: "
                + regex
                + "\nFlags: "
                + flags
                + "\nInput: "
                + input);
      }
    }
  }

  private record RegressionCase(String regex, int flags, List<String> inputs) {}
}
