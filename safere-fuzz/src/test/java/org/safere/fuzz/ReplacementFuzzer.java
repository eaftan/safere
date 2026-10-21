// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;
import org.junit.jupiter.api.Test;

public final class ReplacementFuzzer {

  @Test
  void terminalEmptyReplacementState() {
    // Exercise MatcherPair's state exclusion; MatcherTest pins the public API behavior.
    for (String regex : List.of("x*", "x?", "(x)*", "(?<part>x*)", "", "$", "\\b", "x+")) {
      for (String input : List.of("", "x", "yxxy", "yyyy")) {
        FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, 0);
        assertThat(pattern.matcher(input).replaceAll("-")).isTrue();
        assertThat(pattern.matcher(input).replaceAll(result -> "-")).isTrue();
        FuzzSupport.MatcherPair first = pattern.matcher(input);
        assertThat(first.replaceFirst("-")).isTrue();
        first.hasReplacementMatchState();
      }
    }
  }

  @FuzzTest(maxDuration = "30s")
  void replacement(FuzzedDataProvider data) {
    fuzzerTestOneInput(data);
  }

  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
    String regex;
    int flags;
    String input;
    String replacement;
    if (data.consumeBoolean()) {
      List<String> patternCharacters = List.of("\u03D1", "\u00DF");
      List<String> foldedCharacters = List.of("\u03F4", "\u1E9E");
      int variant = data.consumeInt(0, patternCharacters.size() - 1);
      String suffix = data.pickValue(List.of(".", "[xy]", "(?:x|y)"));
      regex = "(?iu)" + patternCharacters.get(variant) + suffix;
      flags = 0;
      input =
          data.consumeString(32)
              + foldedCharacters.get(variant)
              + data.pickValue(List.of("x", "y"))
              + data.consumeString(32);
      replacement = data.consumeString(32);
    } else if (data.consumeBoolean()) {
      String atom = data.pickValue(List.of("x", "[xy]", "(x)", "(?<part>x)", "(?:x|y)"));
      regex = atom + data.pickValue(List.of("*", "?", "{0,3}", "*?", "+"));
      flags = 0;
      input = data.consumeString(64);
      replacement = data.pickValue(List.of("-", "", "[$0]"));
    } else if (data.consumeBoolean()) {
      String leading = data.pickValue(List.of("\\s", "[é😀]", "[ab]"));
      String repetition = data.pickValue(List.of("*", "*?", "+", "{0,3}", "{1,3}"));
      regex = "(" + leading + repetition + ")# noqa" + data.pickValue(List.of("[0-9]", "\\b"));
      flags = 0;
      input =
          data.pickValue(List.of(" ", "é😀", "ab")).repeat(data.consumeInt(0, 64))
              + data.pickValue(List.of("# noqaX", "# noqa1", "# nopeX"))
              + data.pickValue(List.of("", "    # noqa2", "é😀# noqa3"));
      replacement = data.pickValue(List.of("X", "", "<$0>", "<$1>"));
    } else if (data.consumeBoolean()) {
      String low = data.pickValue(List.of("\uDC00", "\uDE00", "\uDFFF"));
      String high = data.pickValue(List.of("\uD800", "\uD83D", "\uDBFF"));
      regex = "(\\s" + data.pickValue(List.of("*", "*?", "+", "{0,3}")) + ")" + low + "[bc]";
      flags = 0;
      input =
          " " + low + "b;" + (high + low + "b;").repeat(data.consumeInt(1, 64)) + " " + low + "c";
      replacement = data.pickValue(List.of("X", "<$0>", "<$1>"));
    } else if (data.consumeBoolean()) {
      int window = data.consumeInt(1, 16);
      regex = "(\\s*)# noqa[ab]*a[ab]{" + window + "}Z";
      flags = 0;
      StringBuilder binary = new StringBuilder();
      int length = data.consumeInt(0, 512);
      for (int i = 0; i < length; i++) {
        binary.append(data.consumeBoolean() ? 'a' : 'b');
      }
      String candidate =
          "# noqa" + binary + "a" + "b".repeat(window) + data.pickValue(List.of("Z", "Y"));
      input =
          data.pickValue(List.of("", "# noqa" + "a" + "b".repeat(window) + "Z;"))
              + " ".repeat(data.consumeInt(0, 64))
              + candidate;
      replacement = data.pickValue(List.of("X", "<$0>", "<$1>"));
    } else {
      regex = data.consumeString(256);
      flags = FuzzSupport.consumeFlags(data);
      input = data.consumeString(2048);
      replacement = data.consumeString(1024);
    }
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, flags);
    if (pattern == null) {
      return;
    }

    FuzzSupport.MatcherPair replaceAllMatcher = pattern.matcher(input);
    if (!replaceAllMatcher.replaceAll(replacement)) {
      return;
    }
    replaceAllMatcher.reset();
    replaceAllMatcher.find();

    FuzzSupport.MatcherPair replaceFirstMatcher = pattern.matcher(input);
    if (!replaceFirstMatcher.replaceFirst(replacement)) {
      return;
    }
    replaceFirstMatcher.hasReplacementMatchState();

    appendReplacementLoop(pattern, input, replacement, data.consumeBoolean());
  }

  private static void appendReplacementLoop(
      FuzzSupport.CompiledPattern pattern,
      String input,
      String replacement,
      boolean useStringBuffer) {
    FuzzSupport.MatcherPair matcher = pattern.matcher(input);
    if (useStringBuffer) {
      StringBuffer sb = new StringBuffer();
      while (matcher.find()) {
        if (!matcher.appendReplacement(sb, replacement)) {
          return;
        }
      }
      matcher.appendTail(sb);
    } else {
      StringBuilder sb = new StringBuilder();
      while (matcher.find()) {
        if (!matcher.appendReplacement(sb, replacement)) {
          return;
        }
      }
      matcher.appendTail(sb);
    }
  }
}
