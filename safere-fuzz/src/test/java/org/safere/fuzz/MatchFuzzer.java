// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;
import java.util.Locale;
import org.safere.Pattern;

public final class MatchFuzzer {
  private static final int CI = Pattern.CASE_INSENSITIVE;
  private static final int CI_U = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
  private static final int CI_UCC = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS;

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
    fuzzerTestOneInput(data);
  }

  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
    for (RegressionCase regression : CASE_FOLDING_REGRESSIONS) {
      FuzzSupport.assertFullMatchesJdk(regression.regex(), regression.flags(), regression.inputs());
    }
    for (RegressionCase regression : CASE_FOLDING_MODEL_REGRESSIONS) {
      assertFullMatchesSafeRe(regression.regex(), regression.flags(), regression.inputs());
    }
    assertUnicodeBoundaryStartCacheMatchesJdk();
    assertTrailingLineTerminatorEndAnchorFindsMatchJdk();
    assertUnicodeLineStartAnchorsMatchJdk();
    assertMixedCarriageReturnLineStartsMatchJdk(data);
    assertZeroWidthAlternationFindsLeftmostStartJdk();
    assertZeroWidthPossessiveCaptureRetentionJdk();
    assertDfaSandwichLeftmostStartCasesMatchJdk();
    assertMixedAsciiAndExactUnicodeCaseFoldingMatchesJdk(data);
    assertScopedCaseFoldingMatchesJdk(data);
    assertMultiAnchorGapBoundsMatchJdk(data);
    assertLeadingClassAssertionsMatchJdk(data);
    assertFactoredPrefixCaseFlagsMatchJdk(data);
    assertAlternationAndQuantifierPriorityLookingAtMatchesJdk(data);

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

  private static void assertUnicodeBoundaryStartCacheMatchesJdk() {
    String regex = "\\b.";
    int flags = Pattern.UNICODE_CHARACTER_CLASS;
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileCompatibleOrSkip(regex, flags);
    if (pattern == null) {
      return;
    }

    String boundary = "!\u00E9" + "x".repeat(300);
    String nonBoundary = "!!" + "x".repeat(300);
    FuzzSupport.MatcherPair matcher = pattern.matcher(boundary);
    matcher.region(1, boundary.length()).lookingAt();
    matcher.reset(nonBoundary).region(1, nonBoundary.length()).lookingAt();
  }

  private static void assertLeadingClassAssertionsMatchJdk(FuzzedDataProvider data) {
    String leadingClass = data.pickValue(List.of("[a-z]", "[ ]", "[0-9]", "[^A-Z]"));
    String assertion = data.consumeBoolean() ? "\\b" : "\\B";
    String suffix = data.consumeBoolean() ? "AAA" : "AAA[0-9]RAREST_TOKEN";
    String regex = leadingClass + assertion + suffix;
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileCompatibleOrSkip(regex, 0);
    if (pattern != null) {
      pattern.matcher(data.pickValue(List.of("xAAA", " AAA", "11AAA", "xAAA1RAREST_TOKEN"))).find();
    }
  }

  private static void assertFactoredPrefixCaseFlagsMatchJdk(FuzzedDataProvider data) {
    String exactSuffix = data.pickValue(List.of("X", "Y", "Z"));
    String foldedSuffix = data.pickValue(List.of("M", "N", "P"));
    String foldedBranch = "(?i:abc)" + foldedSuffix;
    String exactBranch = "abc" + exactSuffix;
    String regex =
        data.consumeBoolean()
            ? "(?:" + foldedBranch + "|" + exactBranch + ")"
            : "(?:" + exactBranch + "|" + foldedBranch + ")";
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileCompatibleOrSkip(regex, 0);
    if (pattern != null) {
      pattern.matcher(data.consumeBoolean() ? "ABC" + foldedSuffix : "abc" + exactSuffix).find();
    }
  }

  private static void assertTrailingLineTerminatorEndAnchorFindsMatchJdk() {
    List<String> regexes = List.of("^(?:\\n|\\n*)$", "^(?:a?|\\n)$", "(?:(?:(?:a?)|\\n))$");
    List<String> inputs = List.of("\n", "\n\n", "a\n", "aa\n", "\u2028\u2028");
    for (String regex : regexes) {
      FuzzSupport.CompiledPattern pattern = FuzzSupport.compileCompatibleOrSkip(regex, 0);
      if (pattern == null) {
        continue;
      }
      for (String input : inputs) {
        pattern.matcher(input).find();
      }
    }
  }

  private static void assertUnicodeLineStartAnchorsMatchJdk() {
    List<String> terminators = List.of("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029");
    List<String> regexes = List.of("(?m)^\\s+at\\s+(\\w+)$", "(?m).+^");
    for (String regex : regexes) {
      FuzzSupport.CompiledPattern pattern = FuzzSupport.compileCompatibleOrSkip(regex, 0);
      if (pattern == null) {
        continue;
      }
      for (String terminator : terminators) {
        FuzzSupport.MatcherPair matcher =
            pattern.matcher("header" + terminator + "\tat alpha" + terminator + "\tat beta");
        while (matcher.find()) {}
      }
    }
  }

  private static void assertMixedCarriageReturnLineStartsMatchJdk(FuzzedDataProvider data) {
    List<RegressionCase> regressions =
        List.of(
            new RegressionCase("(?m)^.X", 0, List.of("a".repeat(500) + "\r\nqq\rqX")),
            new RegressionCase("(?m)^\\nX", 0, List.of("a".repeat(500) + "\rqY\r\nX")));
    for (RegressionCase regression : regressions) {
      FuzzSupport.CompiledPattern pattern =
          FuzzSupport.compileCompatibleOrSkip(regression.regex(), regression.flags());
      if (pattern != null) {
        for (String input : regression.inputs()) {
          pattern.matcher(input).find();
        }
      }
    }

    String firstTerminator = data.consumeBoolean() ? "\r\n" : "\r";
    String secondTerminator = firstTerminator.length() == 2 ? "\r" : "\r\n";
    String regex = data.consumeBoolean() ? "(?m)^.X" : "(?m)^\\nX";
    String input =
        "a".repeat(data.consumeInt(257, 1024))
            + firstTerminator
            + data.consumeString(8)
            + secondTerminator
            + data.consumeString(8);
    FuzzSupport.CompiledPattern generated = FuzzSupport.compileCompatibleOrSkip(regex, 0);
    if (generated != null) {
      generated.matcher(input).find();
    }
  }

  private static void assertZeroWidthAlternationFindsLeftmostStartJdk() {
    List<String> regexes =
        List.of("(?:\\B{1}|a).", "(?:a|\\B?).", "(?:a|(\\B)?).", "(?:(?:)\\B|a).");
    for (String regex : regexes) {
      FuzzSupport.CompiledPattern pattern = FuzzSupport.compileCompatibleOrSkip(regex, 0);
      if (pattern != null) {
        pattern.matcher("ab").find();
      }
    }
  }

  private static void assertZeroWidthPossessiveCaptureRetentionJdk() {
    FuzzSupport.CompiledPattern pattern =
        FuzzSupport.compileCompatibleOrSkip("(?m:^(\\B)*+$)", Pattern.MULTILINE);
    if (pattern == null) {
      return;
    }
    pattern.matcher("\n").find();
    pattern.matcher("\na").find();
  }

  private static void assertDfaSandwichLeftmostStartCasesMatchJdk() {
    List<String> regexes =
        List.of(
            "\\B([^a])*[^a][^a]",
            "\\B(?:[^a])*[^a][^a]",
            "\\B[^a]*[^a][^a]",
            "\\B(?:b|bb)*bb",
            "(?:(?:\\ba?)|\\B|[^a])a?",
            "(?:(?:\\ba?)|\\B|b)a?");
    for (String regex : regexes) {
      FuzzSupport.CompiledPattern pattern = FuzzSupport.compileCompatibleOrSkip(regex, 0);
      if (pattern != null) {
        pattern.matcher("bbbb").find();
        pattern.matcher("ba").find();
      }
    }
  }

  private static void assertMixedAsciiAndExactUnicodeCaseFoldingMatchesJdk(
      FuzzedDataProvider data) {
    List<String> exactCharacters = List.of("\u00DF", "\uFB05");
    List<String> unicodeFoldedCharacters = List.of("\u1E9E", "\uFB06");
    int variant = data.consumeInt(0, exactCharacters.size() - 1);
    String ascii = String.valueOf((char) ('a' + data.consumeInt(0, 25)));
    String separator = data.pickValue(List.of("", "-", "_", "0"));
    String regex = "(?i:" + ascii + separator + exactCharacters.get(variant) + ")";
    String input =
        Character.toUpperCase(ascii.charAt(0)) + separator + unicodeFoldedCharacters.get(variant);

    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileCompatibleOrSkip(regex, 0);
    if (pattern != null) {
      pattern.matcher(input).find();
    }
  }

  private static void assertScopedCaseFoldingMatchesJdk(FuzzedDataProvider data) {
    String exact = distinctAsciiLiteral(data.consumeInt(3, 12));
    String folded = distinctAsciiLiteral(data.consumeInt(3, 12));
    String regex =
        data.consumeBoolean()
            ? "(?-i:" + exact + ")[0-9]" + folded
            : folded + "[0-9](?-i:" + exact + ")";
    String input =
        regex.startsWith("(?-i")
            ? exact.toLowerCase(Locale.ROOT) + "1" + folded.toLowerCase(Locale.ROOT)
            : folded.toLowerCase(Locale.ROOT) + "1" + exact.toLowerCase(Locale.ROOT);

    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileCompatibleOrSkip(regex, CI);
    if (pattern != null) {
      pattern.matcher(input).find();
    }
  }

  private static String distinctAsciiLiteral(int count) {
    StringBuilder literal = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      literal.append((char) ('A' + i % 26));
    }
    return literal.toString();
  }

  private static void assertMultiAnchorGapBoundsMatchJdk(FuzzedDataProvider data) {
    int repeatedDigits = data.consumeInt(3, 12);
    String driver = distinctAsciiLiteral(data.consumeInt(8, 16));
    String regex;
    String input;
    switch (data.consumeInt(0, 7)) {
      case 0 -> {
        regex = "111[0-9]+" + driver;
        input = "1".repeat(repeatedDigits) + "2" + driver;
      }
      case 1 -> {
        int maximum = data.consumeInt(1, 4);
        regex = "(?s)TARGET.{1," + maximum + "}";
        input = "TARGET" + "😀".repeat(maximum + 1);
      }
      case 2 -> {
        int maximum = data.consumeInt(1, 4);
        boolean interior = data.consumeBoolean();
        regex = interior ? "TARGET[^;]?" + driver : "TARGET[^;]{0," + maximum + "}";
        input = "xxTARGET" + "😀".repeat(interior ? 1 : maximum) + (interior ? driver : "zz");
      }
      case 3 -> {
        regex = "TARGET[^;]*" + driver;
        input = "TARGET" + driver + ";" + driver;
      }
      case 4 -> {
        String upstream = data.consumeBoolean() ? "TARGET;" : "é;";
        regex = upstream + "[^;]" + driver;
        input = "xx" + upstream + "x" + driver + "yy";
      }
      case 5 -> {
        regex = "TARGET[^;]*" + driver + "[^;]X";
        input = "X" + "TARGET".repeat(data.consumeInt(16, 128)) + driver + "y";
      }
      case 6 -> {
        regex = "TARGET[^;]*MID[^;]*" + driver;
        input = "TARGETMID" + driver + "MID;" + driver;
      }
      default -> {
        regex = "TARGET[^;]*?MID[^:]*" + driver;
        input = "TARGETMID:MID" + driver;
      }
    }

    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileCompatibleOrSkip(regex, 0);
    if (pattern != null) {
      pattern.matcher(input).find();
    }
  }

  private static void assertAlternationAndQuantifierPriorityLookingAtMatchesJdk(
      FuzzedDataProvider data) {
    RegressionCase regression =
        data.pickValue(
            List.of(
                new RegressionCase("a+?b?", 0, List.of("aaab", "aab", "ab")),
                new RegressionCase("(?:a|ab)c?", 0, List.of("abc", "ab", "ac")),
                new RegressionCase("a??b?", 0, List.of("ab", "a", "b"))));
    FuzzSupport.CompiledPattern pattern =
        FuzzSupport.compileCompatibleOrSkip(regression.regex(), regression.flags());
    if (pattern != null) {
      pattern.matcher(data.pickValue(regression.inputs())).lookingAt();
    }
  }

  private static void assertFullMatchesSafeRe(String regex, int flags, List<String> inputs) {
    Pattern pattern = Pattern.compile(regex, flags);
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
