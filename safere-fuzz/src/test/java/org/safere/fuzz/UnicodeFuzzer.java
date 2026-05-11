// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;

final class UnicodeFuzzer {

  private static final List<String> GRAPHEME_CLUSTER_REGEXES = List.of("\\X", "^\\^?\\X\\X");

  private static final List<String> GRAPHEME_CLUSTER_INPUTS =
      List.of(
          "a",
          "e\u0301",
          "\u0301\u0301a",
          "\u0301".repeat(44) + "a".repeat(8),
          "\r\n",
          "\uD83C\uDDFA\uD83C\uDDF8",
          "\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDE8",
          "\uD83D\uDC4D\uD83C\uDFFD",
          "\uD83D\uDC69\u200D\uD83D\uDCBB",
          "\uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDCBB",
          "a\u200D",
          "\u1100\u1161",
          "\uAC00\u11A8",
          "\u0600a");

  @FuzzTest(maxDuration = "30s")
  void unicode(FuzzedDataProvider data) {
    for (String regex : GRAPHEME_CLUSTER_REGEXES) {
      FuzzSupport.CompiledPattern graphemePattern = FuzzSupport.compileOrSkip(regex, 0);
      for (String input : GRAPHEME_CLUSTER_INPUTS) {
        FuzzSupport.MatcherPair matcher = graphemePattern.matcher(input);
        while (matcher.find()) {
          // Continue through the sequence so group boundaries are compared at every cluster.
        }
      }
    }

    String regex = data.consumeString(256);
    int flags = FuzzSupport.consumeFlags(data);
    String input = FuzzSupport.consumeUnicodeHeavyString(data, 512);
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, flags);
    if (pattern == null) {
      return;
    }

    FuzzSupport.MatcherPair matcher = pattern.matcher(input);
    matcher.find();
    matcher.reset();
    matcher.matches();
    matcher.reset();
    matcher.lookingAt();
  }
}
