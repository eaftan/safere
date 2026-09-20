// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;

public final class SplitFuzzer {
  private static final List<String> GRAPHEME_CRLF_DELIMITERS =
      List.of("\\r\\b{g}\\n", "(?:\\r)\\b{g}\\n", "\\r(?:\\b{g})\\n");
  private static final List<String> ADJACENT_CRLF_INPUTS =
      List.of("#\r\n\r\n$", "\r\n\r\n", "a\r\n\r\nb");
  private static final List<Integer> SPLIT_LIMITS = List.of(-1, 0, 2);
  private static final List<Integer> LARGE_POSITIVE_LIMITS =
      List.of(Integer.MAX_VALUE, Integer.MAX_VALUE / 2 + 1);

  // Exercise cache reuse where a delimiter is also a member of the preceding repeated class.
  @FuzzTest(maxDuration = "30s")
  void repeatedClassSplits(FuzzedDataProvider data) {
    String alphabet = data.pickValue(List.of("01", "ab", "\0x", "😀😁"));
    int firstWidth = Character.charCount(alphabet.codePointAt(0));
    String delimiter = alphabet.substring(0, firstWidth);
    String continuation = alphabet.substring(firstWidth);
    String quantifier = data.pickValue(List.of("*", "+", "*?", "{0,3}"));
    String regex = "[" + alphabet + "]" + quantifier + delimiter;
    if (data.consumeBoolean()) {
      regex = "(" + regex + ")";
    }
    String input =
        delimiter
            + "~".repeat(data.consumeInt(1, 8))
            + delimiter
            + continuation.repeat(data.consumeInt(1, 32))
            + "~".repeat(data.consumeInt(16, 600))
            + delimiter;
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, 0);
    if (pattern == null) {
      return;
    }
    int limit = data.consumeInt(-1, 8);
    for (int reuse = 0; reuse < 2; reuse++) {
      pattern.split(input);
      pattern.split(input, limit);
      pattern.splitWithDelimiters(input, limit);
    }
  }

  @FuzzTest(maxDuration = "30s")
  void split(FuzzedDataProvider data) {
    fuzzerTestOneInput(data);
  }

  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
    String regex = data.consumeString(256);
    int flags = FuzzSupport.consumeFlags(data);
    String input = data.consumeString(2048);
    int limit = data.consumeInt(-16, 16);
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, flags);
    if (pattern == null) {
      return;
    }

    pattern.split(input);
    pattern.split(input, limit);
    pattern.splitWithDelimiters(input);
    pattern.splitWithDelimiters(input, limit);
    for (int largeLimit : LARGE_POSITIVE_LIMITS) {
      pattern.split(input, largeLimit);
      pattern.splitWithDelimiters(input, largeLimit);
    }

    for (String delimiter : GRAPHEME_CRLF_DELIMITERS) {
      FuzzSupport.CompiledPattern graphemePattern = FuzzSupport.compileOrSkip(delimiter, 0);
      if (graphemePattern == null) {
        continue;
      }
      for (String adjacentCrLfInput : ADJACENT_CRLF_INPUTS) {
        graphemePattern.split(adjacentCrLfInput);
        graphemePattern.splitWithDelimiters(adjacentCrLfInput);
        for (int splitLimit : SPLIT_LIMITS) {
          graphemePattern.split(adjacentCrLfInput, splitLimit);
          graphemePattern.splitWithDelimiters(adjacentCrLfInput, splitLimit);
        }
      }
    }
  }
}
