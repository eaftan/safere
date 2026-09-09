// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;
import org.junit.jupiter.api.Test;

public final class FindSequenceFuzzer {
  private static final List<String> LINE_TERMINATORS =
      List.of("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029");

  @Test
  void reusedMultilineDollarKeepsCrLfAtomic() {
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip("\\W*(?m:$)", 0);
    pattern.matcher(" \na").find();
    FuzzSupport.MatcherPair matcher = pattern.matcher("xb\r\n" + "a".repeat(30));
    while (matcher.find()) {
      matcher.start();
      matcher.end();
    }
  }

  @Test
  void anchoredFailureAfterSuccessfulMatchRegression() {
    assertAnchoredContinuation("a", "a a", false, false);
    assertAnchoredContinuation("a*", "a!", true, false);
    assertAnchoredContinuation("a", "ba a", false, true);
    assertAnchoredContinuation("a*", "!a", false, false);
    assertAnchoredContinuation("^[ab]*", "!a😀b!", false, true);
    assertAnchoredContinuation("\\A(a?)", "!a", true, true);
  }

  private static void assertAnchoredContinuation(
      String regex, String input, boolean initialLookingAt, boolean attemptLookingAt) {
    FuzzSupport.MatcherPair matcher = FuzzSupport.compileOrSkip(regex, 0).matcher(input);
    if (initialLookingAt) {
      matcher.lookingAt();
    } else {
      matcher.find();
    }
    if (attemptLookingAt) {
      matcher.lookingAt();
    } else {
      matcher.matches();
    }
    matcher.find();
    matcher.find();
  }

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

  @Test
  void unicodeBoundaryTransitionClassesRegression() {
    for (String word : List.of("α", "中", "\u0301", "\u0660", "\u200c")) {
      assertFindSequence("(?U).\\B", " " + word + "`\u180e");
      assertFindSequence("(?U)\\b.", "x".repeat(300) + " " + word + "`\u180e");
    }
  }

  @Test
  void leadingExpansionDoesNotCrossSplitSurrogateFindStart() {
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip("[\\x{1F600}]+b", 0);

    pattern.matcher("😀bX😀b").find(1);
  }

  @Test
  void scopedEndAnchorLineModes() {
    for (String anchor : List.of("$", "\\Z")) {
      for (String terminator : List.of("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029")) {
        for (String mode : List.of("d", "-d")) {
          for (int flags : new int[] {0, java.util.regex.Pattern.UNIX_LINES}) {
            FuzzSupport.CompiledPattern pattern =
                FuzzSupport.compileOrSkip("(?:foo|bar)(?" + mode + ":" + anchor + ")", flags);
            FuzzSupport.MatcherPair matcher = pattern.matcher("foo" + terminator);
            while (matcher.find()) {
              matcher.start();
              matcher.end();
              matcher.group();
            }
          }
        }
      }
    }
  }

  @Test
  void mixedCrLfAndStandaloneLfCacheRegressions() {
    for (String regex : List.of("(?-d:(?m:$))(?dm:$)", "(?dm:$)(?-d:(?m:$))")) {
      FuzzSupport.CompiledPattern pattern =
          FuzzSupport.compileOrSkip(regex, java.util.regex.Pattern.UNIX_LINES);
      for (String input : List.of("\r\na\na", "\na\r\na", "a\na", "\r\na\na")) {
        FuzzSupport.MatcherPair matcher = pattern.matcher(input);
        while (matcher.find()) {
          matcher.start();
          matcher.end();
        }
      }
    }
  }

  @FuzzTest(maxDuration = "30s")
  void sequence(FuzzedDataProvider data) {
    fuzzerTestOneInput(data);
  }

  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
    String regex;
    int flags;
    String input;
    boolean splitSurrogateFindStart = false;
    boolean warmLineEndCache = false;
    String warmInput = null;
    switch (data.consumeInt(0, 13)) {
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
            data.pickValue(List.of("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029"));
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
      case 7 -> {
        String quantifier = data.pickValue(List.of("*", "+", "{1,2}"));
        regex = "[\\x{1F600}]" + quantifier + "b";
        flags = 0;
        input = "😀b" + data.consumeString(16) + "😀b";
        splitSurrogateFindStart = true;
      }
      case 8 -> {
        String anchor = data.pickValue(List.of("$", "\\Z", "(?m:$)"));
        String localMode = data.consumeBoolean() ? "d" : "-d";
        String scopedAnchor = "(?" + localMode + ":" + anchor + ")";
        String body = data.pickValue(List.of("foo", "(?:foo|bar)", "(foo|bar)", "[a-z]+"));
        regex = body + scopedAnchor + (data.consumeBoolean() ? anchor : "");
        flags = data.consumeBoolean() ? java.util.regex.Pattern.UNIX_LINES : 0;
        String terminator =
            data.pickValue(List.of("", "\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029"));
        input = "x".repeat(data.consumeInt(0, 2048)) + "foo" + terminator;
      }
      case 9 -> {
        regex =
            data.pickValue(
                List.of(
                    "(?-d:(?m:$))(?dm:$)",
                    "(?dm:$)(?-d:(?m:$))",
                    "((?-d:(?m:$)))(?dm:$)",
                    "(?-d:(?m:^))(?dm:^)[ab]"));
        flags = data.consumeBoolean() ? java.util.regex.Pattern.UNIX_LINES : 0;
        StringBuilder mixedLines = new StringBuilder("x".repeat(data.consumeInt(0, 600)));
        for (int i = data.consumeInt(1, 16); i > 0; i--) {
          mixedLines.append(data.pickValue(List.of("\r\na", "\na", "\rb", "\u0085a", "b")));
        }
        input = mixedLines.toString();
        warmLineEndCache = true;
      }
      case 10 -> {
        String atom = data.pickValue(List.of("a", "😀", "[ab]", "(a|b)"));
        String anchor = data.consumeBoolean() ? "^" : "";
        regex = anchor + atom + data.pickValue(List.of("", "*", "+", "{1,3}"));
        flags = 0;
        String prefix = data.consumeBoolean() ? "!" : "";
        input = prefix + "a😀b".repeat(data.consumeInt(1, 200)) + "!";
        assertAnchoredContinuation(regex, input, data.consumeBoolean(), data.consumeBoolean());
      }
      case 12 -> {
        String anchor = data.pickValue(List.of("^", "\\A", "(?m)^"));
        String body = data.pickValue(List.of("[ab]*", "(a?)", "(a|b)*", "(?:😀)*"));
        regex = anchor + body;
        flags = 0;
        input = "!" + data.consumeString(64);
        FuzzSupport.MatcherPair matcher = FuzzSupport.compileOrSkip(regex, flags).matcher(input);
        if (data.consumeBoolean()) {
          matcher.find(data.consumeInt(0, input.length()));
        } else if (data.consumeBoolean()) {
          matcher.find();
        } else {
          matcher.lookingAt();
        }
        for (int i = 0, steps = data.consumeInt(3, 8); i < steps; i++) {
          matcher.find();
        }
      }
      case 11 -> {
        String atom = data.pickValue(List.of("\\W", "\\s", "[\\r\\n ]"));
        String quantifier = data.pickValue(List.of("*", "+", "?", "*?", "{0,3}"));
        regex = atom + quantifier + "(?m:$)";
        flags = 0;
        warmInput = " ".repeat(data.consumeInt(1, 8)) + data.pickValue(LINE_TERMINATORS) + "a";
        input = "xb" + data.pickValue(LINE_TERMINATORS) + "a".repeat(data.consumeInt(1, 700));
      }
      case 13 -> {
        String boundary = data.pickValue(List.of("\\b", "\\B"));
        String atom = data.pickValue(List.of(".", "(.)", "[\\s\\S]"));
        regex = "(?U)" + (data.consumeBoolean() ? atom + boundary : boundary + atom);
        flags = 0;
        String word = data.pickValue(List.of("α", "中", "\u0301", "\u0660", "\u200c"));
        String nonWord = data.pickValue(List.of("`", "\u180e", "!"));
        input = "x".repeat(data.consumeInt(0, 512)) + " " + word + nonWord + nonWord;
      }
      default -> throw new AssertionError();
    }
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, flags);
    if (pattern == null) {
      return;
    }
    if (warmLineEndCache) {
      FuzzSupport.MatcherPair warmup = pattern.matcher(data.consumeBoolean() ? "\r\na" : "a\na");
      while (warmup.find()) {
        warmup.start();
        warmup.end();
      }
    }
    if (warmInput != null) {
      pattern.matcher(warmInput).find();
    }
    if (splitSurrogateFindStart) {
      pattern.matcher(input).find(1);
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
    boolean canReadGroups = false;
    int steps = data.consumeInt(1, 32);
    for (int i = 0; i < steps; i++) {
      switch (data.consumeInt(0, 10)) {
        case 0 -> canReadGroups = matcher.matches();
        case 1 -> canReadGroups = matcher.lookingAt();
        case 2 -> canReadGroups = matcher.find();
        case 3 -> canReadGroups = matcher.find(FuzzSupport.consumeIndex(data, input));
        case 4 -> {
          matcher.reset();
          canReadGroups = false;
        }
        case 5 -> {
          input = data.consumeString(2048);
          matcher.reset(input);
          canReadGroups = false;
        }
        case 6 -> matcher.groupCount();
        case 7 -> {
          if (canReadGroups) {
            matcher.group();
            matcher.start();
            matcher.end();
            int group = data.consumeInt(0, matcher.groupCount());
            matcher.group(group);
            matcher.start(group);
            matcher.end(group);
          }
        }
        case 8 -> {
          int[] region = FuzzSupport.consumeRegion(data, input);
          matcher.region(region[0], region[1]);
          canReadGroups = false;
        }
        case 9 -> {
          matcher.useAnchoringBounds(data.consumeBoolean());
          matcher.useTransparentBounds(data.consumeBoolean());
        }
        case 10 -> matcher.hasMatch();
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
