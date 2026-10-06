// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class FuzzSupportOracleTimeoutTest {
  private static final String TIMEOUT_PROPERTY = "safere.fuzz.jdkOracleTimeoutMillis";

  @Test
  @DisplayName("JDK oracle timeout marks pathological backtracking input unavailable")
  void jdkOracleTimeoutMarksPathologicalBacktrackingInputUnavailable() {
    String previous = System.getProperty(TIMEOUT_PROPERTY);
    System.setProperty(TIMEOUT_PROPERTY, "1");
    try {
      assertTimeoutPreemptively(
          Duration.ofSeconds(2),
          () ->
              assertFalse(
                  FuzzSupport.jdkOracleCompletesForTesting(
                      "(?:(?:(a|aa))+)+y", "a".repeat(64) + "x")));
    } finally {
      if (previous == null) {
        System.clearProperty(TIMEOUT_PROPERTY);
      } else {
        System.setProperty(TIMEOUT_PROPERTY, previous);
      }
    }
  }

  @Test
  @DisplayName("JDK oracle stack overflow marks recursive matching unavailable")
  void jdkOracleStackOverflowMarksRecursiveMatchingUnavailable() {
    assertFalse(FuzzSupport.jdkOracleStackOverflowIsAvailableForTesting());
  }

  @Test
  @DisplayName("intentional syntax rejection remains an exploratory finding")
  void intentionalSyntaxRejectionRemainsAnExploratoryFinding() {
    for (String regex : new String[] {"[a&&&b]", "(?x:[a& & &b])", "(?ix)[a& & &b]"}) {
      assertThatThrownBy(() -> org.safere.Pattern.compile(regex))
          .isInstanceOf(java.util.regex.PatternSyntaxException.class);
      assertThatThrownBy(() -> FuzzSupport.compileCompatibleOrSkip(regex, 0))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("compile divergence");
    }
  }

  @Test
  @DisplayName("unsupported previous-match anchor and atomic group are excluded")
  void unsupportedFeaturesAreExcluded() {
    assertThat(FuzzSupport.compileCompatibleOrSkip("\\G", 0)).isNull();
    assertThat(FuzzSupport.compileCompatibleOrSkip("\\G\\w+", 0)).isNull();
    assertThat(FuzzSupport.compileCompatibleOrSkip("a+\\GGGG", 7)).isNull();
    assertThat(FuzzSupport.compileCompatibleOrSkip("(?>a+)", 0)).isNull();
  }
}
