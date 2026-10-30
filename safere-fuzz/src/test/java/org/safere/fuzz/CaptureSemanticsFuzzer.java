// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.safere.Pattern;
import org.safere.Utf8Input;

public final class CaptureSemanticsFuzzer {
  private static final String[] ATOMS = {
    "()",
    "(a)",
    "(a?)",
    "(a)?",
    "(\\b)",
    "(a|aa)",
    "()|\\W+",
    "(a)|b",
    "b|(a)",
    "a|(b)|()",
    "a|(){2}",
    "a|(?:()){2}",
    ".|(){2}",
    "((a))",
    "(?:|(a))",
    "(?:a(b)?){1,2}",
    "(ab?)+(a){0,2}",
    "(?<word>a)",
    "(?<word>a|aa)"
  };
  private static final String[] QUANTIFIERS = {
    "*", "+", "?", "{0,2}", "{1,2}", "*?", "+?", "??", "{0,2}?", "{1,2}?", "*+", "?+", "{0,2}+"
  };
  private static final String[] CHAINED_QUANTIFIERS = {
    "{0}", "{1}", "{0,2}", "{1}?", "{0}?", "{0}+", "{1}+", "{0,2}+"
  };
  private static final String[] PREFIXES = {"", "x", "(?:x)?"};
  private static final String[] SUFFIXES = {"", "y", "c?"};

  @FuzzTest(maxDuration = "30s")
  void quantifiedCaptures(FuzzedDataProvider data) {
    fuzzerTestOneInput(data);
  }

  @FuzzTest(maxDuration = "30s")
  void acceptedPaths(FuzzedDataProvider data) {
    String atom = pick(data, "a", "é", "😀");
    int count = data.consumeInt(3, 64);
    int maximum = data.consumeInt(2, 4);
    String captured = data.consumeBoolean() ? "(?<last>" + atom + ")" : "(?:(?<last>" + atom + "))";
    String outer = data.consumeBoolean() ? "*" : "+";
    String reluctant = data.consumeBoolean() ? "?" : "";
    String run = atom.repeat(count);

    // These oracles describe the accepted path directly. JDK nested-repeat captures can retain
    // an earlier iteration, so a differential oracle cannot establish this invariant (#1079).
    assertAcceptedPath(
        "(?:" + captured + "{0," + maximum + "})" + outer + reluctant + "!",
        run + "!",
        run.length() - atom.length(),
        run.length());
    assertAcceptedPath(
        "(?:(?:" + atom + "(?<last>b)?){1," + maximum + "})+",
        run + "b",
        run.length(),
        run.length() + 1);
    assertAcceptedPath(
        "(?:(?<last>" + atom + "b?)+(" + atom + "){0," + maximum + "})+",
        run + "b",
        run.length() - atom.length(),
        run.length() + 1);
  }

  private static void assertAcceptedPath(String regex, String input, int start, int end) {
    Pattern pattern = Pattern.compile(regex);
    for (int operation = 0; operation < 3; operation++) {
      var matcher = pattern.matcher(input);
      boolean matched =
          switch (operation) {
            case 0 -> matcher.find();
            case 1 -> matcher.matches();
            default -> matcher.lookingAt();
          };
      assertThat(matched).as("/%s/ on %s", regex, input).isTrue();
      assertThat(matcher.start()).isZero();
      assertThat(matcher.end()).isEqualTo(input.length());
      assertThat(matcher.start("last")).isEqualTo(start);
      assertThat(matcher.end("last")).isEqualTo(end);
      if (matcher.groupCount() > 1) {
        assertThat(matcher.start(2)).isEqualTo(-1);
        assertThat(matcher.end(2)).isEqualTo(-1);
      }
      var snapshot = matcher.toMatchResult();
      matcher.reset();
      assertThat(snapshot.start(1)).isEqualTo(start);
      assertThat(snapshot.end(1)).isEqualTo(end);
    }
    var utf8 = pattern.matcher(Utf8Input.trusted(input.getBytes(UTF_8)));
    assertThat(utf8.find()).isTrue();
    assertThat(utf8.end()).isEqualTo(input.getBytes(UTF_8).length);
    assertThat(utf8.start(1)).isEqualTo(input.substring(0, start).getBytes(UTF_8).length);
    assertThat(utf8.end(1)).isEqualTo(input.substring(0, end).getBytes(UTF_8).length);
    assertThat(pattern.matcher(input).replaceAll("[${last}]"))
        .isEqualTo("[" + input.substring(start, end) + "]");
  }

  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
    boolean named = data.consumeBoolean();
    String regex = consumeRegex(data, named);
    String input = consumeCaptureInput(data);
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, 0);
    if (pattern == null) {
      return;
    }

    assertMatchOperations(pattern, input, named);
    assertReplacementOperations(pattern, input, named);
  }

  private static String consumeRegex(FuzzedDataProvider data, boolean named) {
    String atom = named ? pick(data, "(?<word>a)", "(?<word>a|aa)") : pick(data, ATOMS);
    String regex = "(?:" + atom + ")";
    if (!data.consumeBoolean()) {
      regex += pick(data, QUANTIFIERS);
    }
    if (data.consumeBoolean()) {
      regex += pick(data, CHAINED_QUANTIFIERS);
    }
    if (data.consumeBoolean()) {
      regex = "(?:" + regex + ")" + pick(data, QUANTIFIERS);
    }
    return pick(data, PREFIXES) + regex + pick(data, SUFFIXES);
  }

  private static String consumeCaptureInput(FuzzedDataProvider data) {
    int length = data.consumeInt(0, 64);
    StringBuilder input = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      input.append(
          switch (data.consumeInt(0, 5)) {
            case 0 -> 'a';
            case 1 -> 'b';
            case 2 -> 'c';
            case 3 -> 'x';
            case 4 -> 'y';
            case 5 -> ' ';
            default -> throw new AssertionError();
          });
    }
    return input.toString();
  }

  private static void assertMatchOperations(
      FuzzSupport.CompiledPattern pattern, String input, boolean named) {
    FuzzSupport.MatcherPair matcher = pattern.matcher(input);
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

  private static void observeGroups(
      FuzzSupport.MatcherPair matcher, boolean matched, boolean named) {
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

  private static void assertReplacementOperations(
      FuzzSupport.CompiledPattern pattern, String input, boolean named) {
    pattern.matcher(input).replaceAll("[$1]");
    pattern.matcher(input).replaceFirst("[$1]");
    pattern.matcher(input).replaceAll(match -> "[" + match.group(1) + "]");

    FuzzSupport.MatcherPair matcher = pattern.matcher(input);
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
