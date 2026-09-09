// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;

public final class ReplacementFuzzer {

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
    } else {
      regex = data.consumeString(256);
      flags = FuzzSupport.consumeFlags(data);
      input = data.consumeString(2048);
      replacement = data.consumeRemainingAsString();
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
