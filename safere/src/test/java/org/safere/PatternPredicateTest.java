// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Tests for {@link Pattern#asPredicate()} and {@link Pattern#asMatchPredicate()}. */
class PatternPredicateTest {

  @Test
  void asPredicatePartialMatch() {
    Predicate<String> pred = Pattern.compile("\\d+").asPredicate();
    assertThat(pred.test("abc123def")).isTrue();
    assertThat(pred.test("abcdef")).isFalse();
  }

  @Test
  void asMatchPredicateFullMatch() {
    Predicate<String> pred = Pattern.compile("\\d+").asMatchPredicate();
    assertThat(pred.test("123")).isTrue();
    assertThat(pred.test("abc123")).isFalse();
  }

  static final List<String> INPUTS =
      List.of(
          "",
          "a",
          "hello",
          "say hello world",
          "HELLO there",
          "foo bar baz",
          "x foo",
          "x foo\n",
          "x foo\r\n",
          "foo\nbar",
          "line1\r\nline2\r\n",
          "  # noqa",
          " ".repeat(40) + "# noqaX",
          "GET /index.html",
          "2026-10-26",
          "on 2026-10-26 ok",
          "MyClassTest",
          "getValue",
          "a😀b",
          "😀",
          "\uD83D",
          "\uDE00",
          "x\uDE00y",
          "\uD83D\uDE00\uDE00",
          "éabc",
          "Straße",
          "ǅ titlecase",
          "abc123def");

  /** Patterns chosen to reach each step of the matcher-free path and its matcher fallbacks. */
  static Stream<Arguments> patterns() {
    return Stream.of(
        // Literal fast path, unanchored and anchored.
        Arguments.of("hello", 0),
        Arguments.of("^hello", 0),
        Arguments.of("😀", 0),
        Arguments.of("hello", Pattern.CASE_INSENSITIVE),
        // Keyword alternation.
        Arguments.of("\\b(?:foo|bar|baz)\\b", 0),
        Arguments.of("(?i)\\b(?:foo|bar|baz)\\b", 0),
        // Anchored prefix and anchored character class.
        Arguments.of("^GET [^ ]+", 0),
        Arguments.of("^[0-9]{4}-", 0),
        Arguments.of("^[\\x{1F600}-\\x{1F64F}]", 0),
        // End-anchored search, including $ before a final line terminator.
        Arguments.of("(?:foo|fax)$", 0),
        Arguments.of("\\s*(?:foo|fax)\\z", 0),
        Arguments.of("(?i)\\s*(?:foo|fax)$", 0),
        Arguments.of("foo$", Pattern.MULTILINE),
        Arguments.of("^bar", Pattern.MULTILINE),
        Arguments.of("line\\d$", Pattern.MULTILINE),
        Arguments.of("line\\d$", Pattern.MULTILINE | Pattern.UNIX_LINES),
        // Rejection on a required literal, and start acceleration.
        Arguments.of("[A-Z][a-z]+Test", 0),
        Arguments.of("\\d{4}-\\d{2}-\\d{2}", 0),
        Arguments.of("[a-z]+ing\\b", 0),
        Arguments.of("foo[a-z]{0,8}bar", 0),
        Arguments.of("(?i)straße", 0),
        Arguments.of("\\s*# noqa", 0),
        Arguments.of("\\s*# noqa[0-9]", 0),
        Arguments.of("a*[bc]d", 0),
        Arguments.of("é[ab]+c", 0),
        // Surrogates: candidates inside a pair must be skipped, lone halves still match.
        Arguments.of("\\uDE00", 0),
        Arguments.of("[\\uDC00-\\uDFFF]", 0),
        Arguments.of("x\\uDE00", 0),
        Arguments.of("\\uD83D", 0),
        Arguments.of(".\\uDE00", 0),
        // Empty matches and minimum length.
        Arguments.of("", 0),
        Arguments.of("x*", 0),
        Arguments.of("[a-z]{6,}", 0),
        // Fallbacks to the matcher: grapheme semantics and loop registers.
        Arguments.of("\\X\\b{g}a", 0),
        Arguments.of("(x?)*Q", 0),
        Arguments.of("(?:a|)+b", 0),
        // Case folding and titlecase.
        Arguments.of("(?i)ǆ", 0),
        Arguments.of("(?u)(?i)STRASSE", 0));
  }

  @ParameterizedTest
  @MethodSource("patterns")
  void asPredicateAgreesWithMatcherFind(String regex, int flags) {
    Pattern pattern = Pattern.compile(regex, flags);
    Predicate<String> predicate = pattern.asPredicate();
    for (String input : INPUTS) {
      assertThat(predicate.test(input))
          .as("/%s/ flags=%d on %s", regex, flags, escape(input))
          .isEqualTo(pattern.matcher(input).find());
    }
  }

  private static final String[] ATOMS = {
    "a", "b", "x", ".", "[ab]", "[^a]", "\\d", "\\w", "\\s", "\\b", "\\B", "^", "$", "\\z", "é",
    "😀", "\\uDE00", "\\uD83D", "(?i)", "(?m)", "foo", "bar", "\\n", "\\r\\n", "#",
  };
  private static final String[] QUANTIFIERS = {"", "", "", "?", "*", "+", "{2}", "{0,3}", "+?"};
  private static final String[] RANDOM_INPUT_PIECES = {
    "a", "b", "x", "foo", "bar", " ", "\n", "\r\n", "1", "é", "😀", "\uDE00", "\uD83D", "#", "A",
  };

  @Test
  @DisabledForCrosscheck(
      "Randomized internal parity includes intentional scalar-boundary differences")
  void asPredicateAgreesWithMatcherFindOnRandomPatterns() {
    Random random = new Random(1062);
    List<String> failures = new ArrayList<>();
    for (int i = 0; i < 3_000 && failures.size() < 10; i++) {
      StringBuilder regex = new StringBuilder();
      int atoms = 1 + random.nextInt(5);
      for (int j = 0; j < atoms; j++) {
        if (random.nextInt(6) == 0) {
          regex.append('|');
        }
        regex.append(ATOMS[random.nextInt(ATOMS.length)]);
        regex.append(QUANTIFIERS[random.nextInt(QUANTIFIERS.length)]);
      }
      Pattern pattern;
      try {
        pattern = Pattern.compile(regex.toString());
      } catch (PatternSyntaxException e) {
        continue;
      }
      Predicate<String> predicate = pattern.asPredicate();
      for (int k = 0; k < 8; k++) {
        StringBuilder input = new StringBuilder();
        int pieces = random.nextInt(8);
        for (int j = 0; j < pieces; j++) {
          input.append(RANDOM_INPUT_PIECES[random.nextInt(RANDOM_INPUT_PIECES.length)]);
        }
        String text = input.toString();
        if (predicate.test(text) != pattern.matcher(text).find()) {
          failures.add("/" + regex + "/ on " + escape(text));
        }
      }
    }
    assertThat(failures).isEmpty();
  }

  static String escape(String s) {
    StringBuilder sb = new StringBuilder("\"");
    s.chars()
        .forEach(
            c -> {
              if (c < 0x20 || c >= 0x7f) {
                sb.append(String.format("\\u%04X", c));
              } else {
                sb.append((char) c);
              }
            });
    return sb.append('"').toString();
  }
}
