// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class OracleDefectsTest {
  @Test
  void usePatternCanaryRequiresRemovalWhenJdkIsFixed() {
    var jdk = java.util.regex.Pattern.compile("a").matcher("a");
    assertThat(jdk.find()).isTrue();
    assertThat(OracleDefects.clearedGroupsWithLiveMatch(jdk)).isFalse();
    jdk.usePattern(java.util.regex.Pattern.compile("b"));
    assertThat(OracleDefects.clearedGroupsWithLiveMatch(jdk))
        .as("JDK-8390449 changed: review/remove observed-state exception")
        .isTrue();
  }

  @Test
  void exhaustedFindCanaryRequiresReviewWhenJdkIsFixed() {
    var jdk = java.util.regex.Pattern.compile("a*").matcher("ba");
    jdk.replaceAll("-");
    assertThat(OracleDefects.exhaustedEmptyMatch(jdk))
        .as("SafeRE #931/#933 changed: review exhausted-state exception")
        .isTrue();
    jdk.reset();
    assertThat(OracleDefects.exhaustedEmptyMatch(jdk)).isFalse();
  }

  @Test
  void failedInitialOperationsRemainInTheStrictResetDomain() {
    for (String regex :
        new String[] {
          "a", "(?:)a", "(?:)?a", "x*a", "(?:)*a", "(?:){0,2}a", "(?:(?:){0,2})(?:a)"
        }) {
      assertThat(StrictStateChecks.check(regex, "ab", 1, 2, true, false)).isTrue();
    }
    assertThat(
            StrictStateChecks.check(
                "(?:[a-c]|(?:(?:.)+)(?:(?:b|\\.)))", "\udc00a", 0, 2, true, false))
        .isTrue();
  }

  @Test
  void initialFailureCanaryShowsWhyStrictContinuationResets() {
    var jdk = java.util.regex.Pattern.compile("(?:){0,2}a").matcher("ab");
    assertThat(jdk.find(1)).isFalse();
    assertThat(jdk.find())
        .as("SafeRE #818 changed: review strict reset-domain restriction")
        .isFalse();
    assertThat(jdk.reset().find()).isTrue();
    assertThat(jdk.start()).isZero();
  }

  @Test
  void stateSequencesCoverSurrogatesRegionsAndReplacement() {
    for (String regex : new String[] {".|", "a*", "(?:a|b)+", ".", "[^a]"}) {
      for (String input : new String[] {"", "a", "é中", "\udb7d\udf02A", "\ud800x\udc00"}) {
        for (int start = 0; start <= input.length(); start++) {
          assertThat(StrictStateChecks.check(regex, input, start, input.length(), true, false))
              .isTrue();
        }
      }
    }
  }
}
