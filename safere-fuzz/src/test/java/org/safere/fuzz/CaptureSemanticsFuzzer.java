// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.safere.crosscheck.Matcher;
import org.safere.crosscheck.Pattern;

final class CaptureSemanticsFuzzer {
  private static final String[] ATOMS = {
      "(a)",
      "(a?)",
      "(a)?",
      "(a|aa)",
      "((a))",
      "(?:|(a))",
      "(?<word>a)",
      "(?<word>a|aa)"
  };
  private static final String[] QUANTIFIERS = {
      "*", "+", "?", "{0,2}", "{1,2}", "*?", "+?", "??", "{0,2}?", "{1,2}?"
  };
  private static final String[] PREFIXES = {"", "x", "(?:x)?"};
  private static final String[] SUFFIXES = {"", "y", "c?"};

  @FuzzTest(maxDuration = "30s")
  void quantifiedCaptures(FuzzedDataProvider data) {
    boolean named = data.consumeBoolean();
    String regex = consumeRegex(data, named);
    String input = consumeCaptureInput(data);
    Pattern pattern = FuzzSupport.compileOrSkip(regex, 0);
    if (pattern == null) {
      return;
    }

    assertMatchOperations(pattern, input, named);
    assertReplacementOperations(pattern, input, named);
  }

  private static String consumeRegex(FuzzedDataProvider data, boolean named) {
    String atom = named
        ? pick(data, "(?<word>a)", "(?<word>a|aa)")
        : pick(data, ATOMS);
    String regex = "(?:" + atom + ")" + pick(data, QUANTIFIERS);
    if (data.consumeBoolean()) {
      regex = "(?:" + regex + ")" + pick(data, QUANTIFIERS);
    }
    return pick(data, PREFIXES) + regex + pick(data, SUFFIXES);
  }

  private static String consumeCaptureInput(FuzzedDataProvider data) {
    int length = data.consumeInt(0, 64);
    StringBuilder input = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      input.append(switch (data.consumeInt(0, 4)) {
        case 0 -> 'a';
        case 1 -> 'b';
        case 2 -> 'c';
        case 3 -> 'x';
        case 4 -> 'y';
        default -> throw new AssertionError();
      });
    }
    return input.toString();
  }

  private static void assertMatchOperations(Pattern pattern, String input, boolean named) {
    Matcher matcher = pattern.matcher(input);
    observeGroups(matcher, matcher.matches(), named);

    matcher = pattern.matcher(input);
    observeGroups(matcher, matcher.lookingAt(), named);

    matcher = pattern.matcher(input);
    int matches = 0;
    while (matcher.find() && matches < 128) {
      observeGroups(matcher, true, named);
      matches++;
    }
  }

  private static void observeGroups(Matcher matcher, boolean matched, boolean named) {
    matcher.groupCount();
    if (!matched) {
      return;
    }
    for (int group = 0; group <= matcher.groupCount(); group++) {
      matcher.group(group);
      matcher.start(group);
      matcher.end(group);
    }
    matcher.toMatchResult();
    if (named) {
      matcher.group("word");
      matcher.start("word");
      matcher.end("word");
    }
  }

  private static void assertReplacementOperations(Pattern pattern, String input, boolean named) {
    pattern.matcher(input).replaceAll("[$1]");
    pattern.matcher(input).replaceFirst("[$1]");
    pattern.matcher(input).replaceAll(match -> "[" + match.group(1) + "]");

    Matcher matcher = pattern.matcher(input);
    StringBuilder builder = new StringBuilder();
    while (matcher.find()) {
      matcher.appendReplacement(builder, "[$1]");
    }
    matcher.appendTail(builder);

    if (named) {
      pattern.matcher(input).replaceAll("[${word}]");
    }
  }

  private static String pick(FuzzedDataProvider data, String... values) {
    return values[data.consumeInt(0, values.length - 1)];
  }
}
