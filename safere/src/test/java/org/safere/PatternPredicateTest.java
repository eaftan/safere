// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

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
}
