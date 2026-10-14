// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.safere.Pattern;
import org.safere.Utf8Input;
import org.safere.Utf8Matcher;

/** Shared representation properties on explicitly constructed valid Unicode inputs. */
final class RepresentationChecks {
  private RepresentationChecks() {}

  static void assertLiteralSearchMatchesString(String regex, String input) {
    Pattern pattern = Pattern.compile(regex);
    org.safere.Matcher stringMatcher = pattern.matcher(input);
    byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
    Utf8Input utf8Input = Utf8Input.validated(bytes);
    Utf8Matcher utf8Matcher = pattern.matcher(utf8Input);

    boolean stringFound = stringMatcher.find();
    boolean utf8Found = utf8Matcher.find();
    if (stringFound != utf8Found || pattern.find(utf8Input) != stringFound) {
      throw new AssertionError("UTF-8 literal search result differs from String search");
    }
    if (stringFound
        && (!Objects.equals(stringMatcher.group(), decodeGroup(bytes, utf8Matcher))
            || utf8Matcher.start() < 0
            || utf8Matcher.end() > bytes.length)) {
      throw new AssertionError("UTF-8 literal search bounds differ from String search");
    }
  }

  static void assertKeywordAlternationMatchesString(FuzzedDataProvider data) {
    String keyword = data.pickValue(List.of("you", "your", "error", "timeout"));
    String prefix = data.pickValue(List.of("", "plain ", "é ", "β-", "word_"));
    String suffix = data.pickValue(List.of("", "!", " 中", "2", "_word"));
    boolean greedy = data.consumeBoolean();
    String beforeBoundaryMode = data.pickValue(List.of("", "(?U)", "(?-U)"));
    String afterBoundaryMode = data.pickValue(List.of("", "(?U)", "(?-U)"));
    String core =
        beforeBoundaryMode + "\\b(?i)(you|your|error|timeout)" + afterBoundaryMode + "\\b";
    String regex = greedy ? "(?s).*" + core + ".*" : core;
    String input =
        prefix + data.pickValue(List.of(keyword, keyword.toUpperCase(Locale.ROOT))) + suffix;
    Pattern pattern = Pattern.compile(regex);
    org.safere.Matcher stringMatcher = pattern.matcher(input);
    byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
    Utf8Input utf8Input = Utf8Input.validated(bytes);
    Utf8Matcher utf8Matcher = pattern.matcher(utf8Input);

    boolean stringFound = stringMatcher.find();
    if (utf8Matcher.find() != stringFound || pattern.find(utf8Input) != stringFound) {
      throw new AssertionError("UTF-8 keyword alternation result differs from String search");
    }
    if (stringFound
        && (utf8Matcher.start() != utf8Offset(input, stringMatcher.start())
            || utf8Matcher.end() != utf8Offset(input, stringMatcher.end())
            || utf8Matcher.start(1) != utf8Offset(input, stringMatcher.start(1))
            || utf8Matcher.end(1) != utf8Offset(input, stringMatcher.end(1)))) {
      throw new AssertionError("UTF-8 keyword alternation bounds differ from String search");
    }
  }

  static void assertFixedOffsetAccelerationMatchesString(FuzzedDataProvider data) {
    String leadingClass = data.pickValue(List.of("[aé]", "[a-ÿ]", "[a😀]", "[^x]"));
    String leadingMember =
        switch (leadingClass) {
          case "[a😀]" -> "😀";
          default -> "é";
        };
    String literal = data.pickValue(List.of("bc", "tag", "literal"));
    String input =
        data.pickValue(List.of("", "x", "😀")) + leadingMember + literal + " a" + literal;
    Pattern pattern = Pattern.compile(leadingClass + literal);
    org.safere.Matcher stringMatcher = pattern.matcher(input);
    byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
    Utf8Input utf8Input = Utf8Input.validated(bytes);
    Utf8Matcher utf8Matcher = pattern.matcher(utf8Input);

    boolean stringFound = stringMatcher.find();
    if (utf8Matcher.find() != stringFound || pattern.find(utf8Input) != stringFound) {
      throw new AssertionError("UTF-8 fixed-offset search result differs from String search");
    }
    if (stringFound
        && (utf8Matcher.start() != utf8Offset(input, stringMatcher.start())
            || utf8Matcher.end() != utf8Offset(input, stringMatcher.end())
            || !Objects.equals(stringMatcher.group(), decodeGroup(bytes, utf8Matcher)))) {
      throw new AssertionError("UTF-8 fixed-offset search bounds differ from String search");
    }
  }

  static void assertGraphemeSearchMatchesString(FuzzedDataProvider data) {
    String regex = data.pickValue(List.of(".\\X|z", "(?:.\\X)|z", ".\\X| /0? ?5-", "(?:a|.)\\X|z"));
    String input =
        data.pickValue(List.of("é", "軖", "😀", "a\u0301", "👩‍💻", "🇺🇸"))
            + data.pickValue(List.of("", "| 5  軖", "z", " β"));
    Pattern pattern = Pattern.compile(regex);
    org.safere.Matcher stringMatcher = pattern.matcher(input);
    byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
    Utf8Matcher utf8Matcher = pattern.matcher(Utf8Input.validated(bytes));

    while (true) {
      boolean stringFound = stringMatcher.find();
      boolean utf8Found = utf8Matcher.find();
      if (utf8Found != stringFound) {
        throw new AssertionError("UTF-8 grapheme search result differs from String search");
      }
      if (!stringFound) {
        return;
      }
      if (utf8Matcher.start() != utf8Offset(input, stringMatcher.start())
          || utf8Matcher.end() != utf8Offset(input, stringMatcher.end())
          || !Objects.equals(stringMatcher.group(), decodeGroup(bytes, utf8Matcher))) {
        throw new AssertionError("UTF-8 grapheme search bounds differ from String search");
      }
    }
  }

  static void assertMultibyteMultiAnchorReverseWindowMatchesString(FuzzedDataProvider data) {
    String upstream = data.pickValue(List.of("é", "軖", "😀")).repeat(data.consumeInt(2, 12));
    String downstream = "z".repeat(data.consumeInt(16, 40));
    String regex = upstream + "[0-9]" + downstream;
    String input = upstream + data.consumeInt(0, 9) + downstream;
    Pattern pattern = Pattern.compile(regex);
    org.safere.Matcher stringMatcher = pattern.matcher(input);
    byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
    Utf8Matcher utf8Matcher = pattern.matcher(Utf8Input.validated(bytes));

    boolean stringFound = stringMatcher.find();
    boolean utf8Found = utf8Matcher.find();
    if (utf8Found != stringFound
        || (stringFound
            && (utf8Matcher.start() != utf8Offset(input, stringMatcher.start())
                || utf8Matcher.end() != utf8Offset(input, stringMatcher.end())))) {
      throw new AssertionError("UTF-8 multi-anchor reverse window differs from String matching");
    }
  }

  static int utf8Offset(String input, int utf16Offset) {
    return input.substring(0, utf16Offset).getBytes(StandardCharsets.UTF_8).length;
  }

  static String decodeGroup(byte[] bytes, Utf8Matcher matcher) {
    return new String(
        bytes, matcher.start(), matcher.end() - matcher.start(), StandardCharsets.UTF_8);
  }
}
