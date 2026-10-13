// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Predicate;
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

  @Test
  void asPredicateRejectsNullInput() {
    Predicate<String> pred = Pattern.compile("hello").asPredicate();
    assertThatThrownBy(() -> pred.test(null)).isInstanceOf(NullPointerException.class);
  }

  static Stream<Arguments> predicateCases() {
    return Stream.of(
        Arguments.of("hello", "xxhelloyy", true),
        Arguments.of("hello", "xxworldyy", false),
        Arguments.of("^hello", "helloyy", true),
        Arguments.of("^hello", "xxhelloyy", false),
        Arguments.of("(?i)hello", "xxHeLLoyy", true),
        Arguments.of("(?i)hello", "xxworldyy", false),
        Arguments.of("(?i)^hello", "HeLLoyy", true),
        Arguments.of("(?i)^hello", "xxHeLLoyy", false),
        Arguments.of("foo|bar|baz", "xxbaryy", true),
        Arguments.of("foo|bar|baz", "xxquxyy", false),
        Arguments.of("[0-9]", "abc5def", true),
        Arguments.of("[0-9]", "abcdef", false),
        Arguments.of("^[0-9]", "5abcdef", true),
        Arguments.of("^[0-9]", "abcdef5", false),
        Arguments.of("\\p{L}", "123é456", true),
        Arguments.of("\\p{L}", "123456", false),
        Arguments.of("(?i)\\b(?:foo|bar|baz)\\b", "xx Bar yy", true),
        Arguments.of("(?i)\\b(?:foo|bar|baz)\\b", "xx Barred yy", false),
        Arguments.of("(?is).*\\b(you|your)\\b.*", "hello\nyour world", true),
        Arguments.of("(?is).*\\b(you|your)\\b.*", "hello\nyouth world", false),
        Arguments.of("^GET ([^ ]+)", "GET /index.html HTTP/1.1", true),
        Arguments.of("^GET ([^ ]+)", "POST /index.html HTTP/1.1", false),
        Arguments.of("^foo$", "foo", true),
        Arguments.of("^foo$", "foobar", false),
        Arguments.of("(?:foo|fax)$", "x".repeat(80) + "fax", true),
        Arguments.of("(?:foo|fax)$", "x".repeat(80) + "box", false),
        Arguments.of("\\Xz", "a\u0301z", true),
        Arguments.of("\\Xz", "a\u0301y", false),
        Arguments.of("(a?)*b", "aaab", true),
        Arguments.of("(a?)*b", "aaac", false));
  }

  @ParameterizedTest
  @MethodSource("predicateCases")
  void asPredicateMatchesMatcherFind(String regex, String input, boolean expected) {
    Pattern pattern = Pattern.compile(regex);
    assertThat(pattern.asPredicate().test(input))
        .isEqualTo(expected)
        .isEqualTo(pattern.matcher(input).find());
  }
}
