// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.Test;

final class FindSequenceFuzzer {

  @Test
  void delimitedPrefixBeforeRequiredSuffixRegression() {
    FuzzSupport.CompiledPattern pattern =
        FuzzSupport.compileOrSkip("[^{']*(?:'[^']*'[^{']*)*\\{([^}]*)\\}", 0);

    pattern.matcher("Foo '{0}' Bar: {0}").find();
  }

  @Test
  void dfaBoundSemanticsRegressions() {
    assertFindSequence("$", "x".repeat(512) + "a\n");
    assertFindSequence("$", "x".repeat(512) + "a\r\n");
    assertFindSequence("$", "x".repeat(512) + "a\u2028");
    assertFindSequence("(?:\\B{1}|a).a?", "ab".repeat(300) + "c");
    assertFindSequence("(?:\\B{1}|a).a?$", "x".repeat(512) + "ab");
    assertFindSequence("(?:a+?|(?:[^x])*)$", "x".repeat(512) + "a\n");
    assertFindSequence("(?:a{2,}|(?:.|\\B){1,2}){1,2}", "baax");
  }

  @FuzzTest(maxDuration = "30s")
  void sequence(FuzzedDataProvider data) {
    String regex;
    int flags;
    String input;
    switch (data.consumeInt(0, 6)) {
      case 0 -> {
        regex = nestedCapturingGroups(data.consumeInt(0, 512)) + "*";
        flags = 0;
        input = data.consumeBoolean() ? "a" : "a!";
      }
      case 1 -> {
        String suffix = data.consumeBoolean() ? "\\{([^}]*)\\}" : "END";
        regex = "[^']*(?:'[^']*'[^']*)*" + suffix;
        flags = 0;
        input = data.consumeBoolean() ? "Foo '{0}' Bar: {0}" : "prefix 'not END' suffix END";
      }
      case 2 -> {
        regex = data.consumeString(256);
        flags = FuzzSupport.consumeFlags(data);
        input = data.consumeString(2048);
      }
      case 3 -> {
        regex = "$";
        flags = 0;
        String terminator =
            switch (data.consumeInt(0, 2)) {
              case 0 -> "\n";
              case 1 -> "\r\n";
              case 2 -> "\u2028";
              default -> throw new AssertionError();
            };
        input = data.consumeString(64) + "a" + terminator;
      }
      case 4 -> {
        regex = "(?:\\B{1}|a).a?";
        flags = 0;
        input = "ab".repeat(data.consumeInt(0, 700)) + data.consumeString(2);
      }
      case 5 -> {
        regex = "(?:a+?|(?:[^x])*)$";
        flags = 0;
        input = "x".repeat(data.consumeInt(0, 700)) + data.consumeString(4);
      }
      case 6 -> {
        regex = "(?:a{2,}|(?:.|\\B){1,2}){1,2}";
        flags = 0;
        input = data.consumeBoolean() ? "baax" : data.consumeString(16);
      }
      default -> throw new AssertionError();
    }
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, flags);
    if (pattern == null) {
      return;
    }

    FuzzSupport.MatcherPair findWalker = pattern.matcher(input);
    int maxFinds = Math.min(input.length() + 2, 64);
    for (int i = 0; i < maxFinds; i++) {
      boolean found = findWalker.find();
      if (!found) {
        break;
      }
    }

    FuzzSupport.MatcherPair matcher = pattern.matcher(input);
    boolean hasMatch = false;
    int steps = data.consumeInt(1, 32);
    for (int i = 0; i < steps; i++) {
      switch (data.consumeInt(0, 9)) {
        case 0 -> hasMatch = matcher.matches();
        case 1 -> hasMatch = matcher.lookingAt();
        case 2 -> hasMatch = matcher.find();
        case 3 -> hasMatch = matcher.find(FuzzSupport.consumeIndex(data, input));
        case 4 -> {
          matcher.reset();
          hasMatch = false;
        }
        case 5 -> {
          input = data.consumeString(2048);
          matcher.reset(input);
          hasMatch = false;
        }
        case 6 -> matcher.groupCount();
        case 7 -> {
          if (hasMatch) {
            int group = data.consumeInt(0, matcher.groupCount());
            matcher.group(group);
            matcher.start(group);
            matcher.end(group);
          }
        }
        case 8 -> {
          int[] region = FuzzSupport.consumeRegion(data, input);
          matcher.region(region[0], region[1]);
          hasMatch = false;
        }
        case 9 -> {
          matcher.useAnchoringBounds(data.consumeBoolean());
          matcher.useTransparentBounds(data.consumeBoolean());
        }
        default -> throw new AssertionError();
      }
    }
  }

  private static String nestedCapturingGroups(int depth) {
    return "(".repeat(depth) + "a" + ")".repeat(depth);
  }

  private static void assertFindSequence(String regex, String input) {
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, 0);
    pattern.matcher(input).find();
    FuzzSupport.MatcherPair matcher = pattern.matcher(input);
    int maxFinds = Math.min(input.length() + 2, 128);
    for (int i = 0; i < maxFinds; i++) {
      if (!matcher.find()) {
        return;
      }
    }
  }
}
