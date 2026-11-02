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
    String atom = pick(data, "a", "ab", "é", "😀");
    int count = data.consumeInt(3, 64);
    int maximum = data.consumeInt(2, 32);
    int minimum = data.consumeBoolean() ? 0 : 1;
    String captured = data.consumeBoolean() ? "(?<last>" + atom + ")" : "(?:(?<last>" + atom + "))";
    String outer = data.consumeBoolean() ? "*" : "+";
    String reluctant = data.consumeBoolean() ? "?" : "";
    String run = atom.repeat(count);

    // These oracles describe the accepted path directly. JDK nested-repeat captures can retain
    // an earlier iteration, so a differential oracle cannot establish this invariant (#1079).
    assertAcceptedPath(
        "(?:" + captured + "{" + minimum + "," + maximum + "})" + outer + reluctant + "!",
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

  @FuzzTest(maxDuration = "30s")
  void minimumRespectingEmptyStop(FuzzedDataProvider data) {
    String atom = pick(data, "a", "ab", "é", "😀");
    String prefix = pick(data, "", "x", "😀");
    int count = data.consumeInt(0, 8);
    int minimum = data.consumeInt(0, 4);
    int maximum = data.consumeBoolean() ? -1 : data.consumeInt(minimum, 6);
    boolean greedy = data.consumeBoolean();
    String quantifier = "{" + minimum + "," + (maximum < 0 ? "" : maximum) + "}";
    String operand = "(?:(?<empty>)|" + Pattern.quote(atom) + ")";
    int wrappers = data.consumeInt(0, 3);
    operand = "(?:".repeat(wrappers) + operand + ")".repeat(wrappers);
    Pattern pattern =
        Pattern.compile(
            "\\A" + Pattern.quote(prefix) + operand + quantifier + (greedy ? "" : "?") + "!");
    String input = prefix + atom.repeat(count) + "!";
    // A bounded independent execution tree, never a production result-repair strategy. Every
    // consuming iteration advances one atom; at most minimum + count + 1 iterations are needed.
    Integer expected =
        emptyStopCapture(
            minimum, maximum < 0 ? minimum + count + 1 : maximum, greedy, count, 0, 0, -1);
    for (int operation = 0; operation < 3; operation++) {
      var matcher = pattern.matcher(input);
      boolean matched =
          switch (operation) {
            case 0 -> matcher.matches();
            case 1 -> matcher.find();
            default -> matcher.lookingAt();
          };
      assertThat(matched).as("/%s/ on %s", pattern, input).isEqualTo(expected != null);
      if (matched) {
        int position = expected < 0 ? -1 : prefix.length() + expected * atom.length();
        assertThat(matcher.start()).isZero();
        assertThat(matcher.end()).isEqualTo(input.length());
        assertThat(matcher.start(1)).isEqualTo(position);
        assertThat(matcher.end(1)).isEqualTo(position);
        assertThat(matcher.group(1)).isEqualTo(expected < 0 ? null : "");
        var snapshot = matcher.toMatchResult();
        matcher.reset();
        assertThat(snapshot.start(1)).isEqualTo(position);
      }
    }
    // A fresh pattern has no capture-demand history, so matches() exercises DFA recognition
    // before deferred capture extraction. Search still exercises the guarded selection rule.
    Pattern bytePattern = Pattern.compile(pattern.pattern(), pattern.flags());
    var bytes = bytePattern.matcher(Utf8Input.trusted(input.getBytes(UTF_8)));
    assertThat(bytes.matches()).isEqualTo(expected != null);
    if (expected != null) {
      int position =
          expected < 0
              ? -1
              : prefix.getBytes(UTF_8).length + expected * atom.getBytes(UTF_8).length;
      assertThat(bytes.start(1)).isEqualTo(position);
      assertThat(bytes.end(1)).isEqualTo(position);
    }
    bytes.reset();
    assertThat(bytes.find()).isEqualTo(expected != null);
    if (expected != null) {
      int position =
          expected < 0
              ? -1
              : prefix.getBytes(UTF_8).length + expected * atom.getBytes(UTF_8).length;
      assertThat(bytes.start(1)).isEqualTo(position);
      assertThat(bytes.end(1)).isEqualTo(position);
    }
    assertThat(
            pattern
                .matcher(input)
                .replaceAll(result -> result.group(1) == null ? "omitted" : "empty"))
        .isEqualTo(expected == null ? input : expected < 0 ? "omitted" : "empty");
    fullMatchTerminatorCaptures(data);
  }

  private static void fullMatchTerminatorCaptures(FuzzedDataProvider data) {
    String atom = pick(data, "a", "ab", "é", "😀");
    String prefix = pick(data, "", "x", "😀");
    String terminator = pick(data, "\n", "\r\n", "\r", "\u0085", "\u2028", "\u2029");
    int minimum = data.consumeInt(0, 4);
    int maximum = data.consumeBoolean() ? -1 : data.consumeInt(minimum, 8);
    int count = data.consumeInt(0, 10);
    boolean greedy = data.consumeBoolean();
    int flags = data.consumeBoolean() ? Pattern.UNIX_LINES : 0;
    String regex =
        "^"
            + Pattern.quote(prefix)
            + "(?<last>(?:"
            + Pattern.quote(atom)
            + ")?){"
            + minimum
            + ","
            + (maximum < 0 ? "" : maximum)
            + "}"
            + (greedy ? "" : "?")
            + "((?:"
            + Pattern.quote(terminator)
            + ")??)$";
    String input = prefix + atom.repeat(count) + terminator;
    boolean expected = maximum < 0 || count <= maximum;
    boolean participates = minimum > 0 || count > 0 || (greedy && maximum != 0);
    boolean empty = minimum > count || (greedy && (maximum < 0 || maximum > count));
    Pattern pattern = Pattern.compile(regex, flags);
    var matcher = pattern.matcher(input);
    assertThat(matcher.matches()).as("/%s/ on %s", regex, input).isEqualTo(expected);
    if (expected) {
      int start =
          !participates ? -1 : prefix.length() + (empty ? count : count - 1) * atom.length();
      int end = !participates ? -1 : prefix.length() + count * atom.length();
      assertThat(matcher.start(1)).isEqualTo(start);
      assertThat(matcher.end(1)).isEqualTo(end);
      assertThat(matcher.group(2)).isEqualTo(terminator);
      assertThat(matcher.end()).isEqualTo(input.length());
      var snapshot = matcher.toMatchResult();
      assertThat(snapshot.group()).isEqualTo(input);
      assertThat(snapshot.group(2)).isEqualTo(terminator);
    }
    // Keep the UTF-8 pattern's capture-demand history independent of String group access.
    var bytes = Pattern.compile(regex, flags).matcher(Utf8Input.trusted(input.getBytes(UTF_8)));
    assertThat(bytes.matches()).isEqualTo(expected);
    if (expected) {
      int start =
          !participates
              ? -1
              : prefix.getBytes(UTF_8).length
                  + (empty ? count : count - 1) * atom.getBytes(UTF_8).length;
      int end =
          !participates ? -1 : prefix.getBytes(UTF_8).length + count * atom.getBytes(UTF_8).length;
      assertThat(bytes.start(1)).isEqualTo(start);
      assertThat(bytes.end(1)).isEqualTo(end);
      assertThat(bytes.start(2))
          .isEqualTo(input.getBytes(UTF_8).length - terminator.getBytes(UTF_8).length);
      assertThat(bytes.end(2)).isEqualTo(input.getBytes(UTF_8).length);
      assertThat(bytes.end()).isEqualTo(input.getBytes(UTF_8).length);
    }
  }

  private static Integer emptyStopCapture(
      int minimum,
      int maximum,
      boolean greedy,
      int length,
      int iterations,
      int position,
      int capture) {
    if (iterations == maximum) {
      return position == length ? capture : null;
    }
    if (!greedy && iterations >= minimum && position == length) {
      return capture;
    }
    Integer empty =
        iterations + 1 >= minimum
            ? (position == length ? position : null)
            : emptyStopCapture(
                minimum, maximum, greedy, length, iterations + 1, position, position);
    if (empty != null) {
      return empty;
    }
    if (position < length) {
      Integer consuming =
          emptyStopCapture(minimum, maximum, greedy, length, iterations + 1, position + 1, capture);
      if (consuming != null) {
        return consuming;
      }
    }
    return greedy && iterations >= minimum && position == length ? capture : null;
  }

  @FuzzTest(maxDuration = "30s")
  void emptyCaptures(FuzzedDataProvider data) {
    int minimum = data.consumeInt(0, 4);
    int maximum = data.consumeBoolean() ? -1 : data.consumeInt(Math.max(2, minimum), 8);
    if (minimum <= 1 && data.consumeBoolean()) {
      maximum = data.consumeInt(minimum, 1);
    }
    if (maximum > 1 && data.consumeBoolean()) {
      maximum = new int[] {16, 256, 1000}[data.consumeInt(0, 2)];
    }
    String quantifier = "{" + minimum + "," + (maximum < 0 ? "" : maximum) + "}";
    if (data.consumeBoolean() && maximum < 0 && minimum <= 1) {
      quantifier = minimum == 0 ? "*" : "+";
    }
    String mode = pick(data, "", "?", "+");
    String operand = pick(data, "(?<empty>)", "(?<empty>(?:))", "((?<empty>))");
    int wrappers = data.consumeInt(0, 3);
    operand = "(?:".repeat(wrappers) + operand + ")".repeat(wrappers);
    String prefix = pick(data, "", "a", "é", "😀");
    String suffix = pick(data, "", "b", "é", "😀");
    Pattern pattern =
        Pattern.compile(
            Pattern.quote(prefix) + operand + quantifier + mode + Pattern.quote(suffix));
    String input = prefix + suffix;
    boolean participates = maximum != 0 && (minimum > 0 || !mode.equals("?"));
    int position = participates ? prefix.length() : -1;

    // Empty iterations count and publish captures; redundant wrappers do not select another
    // participation policy. JDK's direct-empty suppression is intentionally not the oracle.
    for (int operation = 0; operation < 3; operation++) {
      var matcher = pattern.matcher(input);
      boolean matched =
          switch (operation) {
            case 0 -> matcher.find();
            case 1 -> matcher.matches();
            default -> matcher.lookingAt();
          };
      assertThat(matched).as("/%s/ on %s", pattern, input).isTrue();
      assertThat(matcher.start()).isZero();
      assertThat(matcher.end()).isEqualTo(input.length());
      for (int group = 1; group <= matcher.groupCount(); group++) {
        assertThat(matcher.start(group)).isEqualTo(position);
        assertThat(matcher.end(group)).isEqualTo(position);
        assertThat(matcher.group(group)).isEqualTo(participates ? "" : null);
      }
      assertThat(matcher.start("empty")).isEqualTo(position);
      var snapshot = matcher.toMatchResult();
      matcher.reset();
      assertThat(snapshot.start(1)).isEqualTo(position);
      assertThat(snapshot.end(1)).isEqualTo(position);
    }
    var utf8 = pattern.matcher(Utf8Input.trusted(input.getBytes(UTF_8)));
    assertThat(utf8.find()).isTrue();
    assertThat(utf8.start()).isZero();
    assertThat(utf8.end()).isEqualTo(input.getBytes(UTF_8).length);
    int bytePosition = participates ? prefix.getBytes(UTF_8).length : -1;
    for (int group = 1; group <= utf8.groupCount(); group++) {
      assertThat(utf8.start(group)).isEqualTo(bytePosition);
      assertThat(utf8.end(group)).isEqualTo(bytePosition);
    }
    assertThat(
            pattern
                .matcher(input)
                .replaceAll(result -> result.group(1) == null ? "omitted" : "empty"))
        .isEqualTo(participates ? "empty" : "omitted");
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
    assertZeroCountPossessive(data);
  }

  private static void assertZeroCountPossessive(FuzzedDataProvider data) {
    // Keep zero-count operands as a separate axis so existing corpus inputs retain their decoding.
    // Refs #1130: descendants below {0} cannot consume, even if they contain consuming branches.
    boolean named = data.consumeBoolean();
    String atom = named ? pick(data, "(?<word>a)", "(?<word>a|aa)") : pick(data, ATOMS);
    String zero = "(?:" + atom + ")" + pick(data, "{0}", "{0,0}", "{0}?", "{0,0}?");
    String operand =
        switch (data.consumeInt(0, 5)) {
          case 0 -> zero;
          case 1 -> "(" + zero + ")";
          case 2 -> zero + "(b){0}";
          case 3 -> zero + "|(b){0}";
          case 4 -> "(?:" + zero + "){2}";
          case 5 -> "(?:" + zero + ")*";
          default -> throw new AssertionError();
        };
    String regex =
        pick(data, PREFIXES)
            + "(?:"
            + operand
            + ")"
            + pick(data, "*+", "?+", "++", "{0}+", "{1}+", "{2}+", "{0,2}+", "{1,}+")
            + pick(data, SUFFIXES);
    String input = consumeCaptureInput(data);
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, 0);
    if (pattern != null) {
      assertMatchOperations(pattern, input, named);
      assertReplacementOperations(pattern, input, named);
    }
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
