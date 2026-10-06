// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Intentional reproductions are model tests, not passing differential seeds. */
final class ExploratoryCaptureTest {
  @Test
  void failedStartCapturesFollowSafeReModelAndRemainExploratoryFindings() {
    for (String regex : new String[] {"(?:(a){1})*$", "(?:(?<item>a){2})*$"}) {
      var matcher = org.safere.Pattern.compile(regex).matcher("aab");
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.start()).isEqualTo(3);
      assertThat(matcher.group(1)).isNull();
      assertThat(matcher.start(1)).isEqualTo(-1);
      assertThat(matcher.end(1)).isEqualTo(-1);
      assertThatThrownBy(() -> FuzzSupport.compileOrSkip(regex, 0).matcher("aab").find())
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("divergence");
    }
  }

  @Test
  void sameStartCaptureResidueRemainsAVisibleFinding() {
    String regex = "(?:(?:())*{0}{1}?{1}|a).";
    var matcher = org.safere.Pattern.compile(regex).matcher("ab");
    assertThat(matcher.matches()).isTrue();
    assertThat(matcher.group(1)).isNull();
    assertThatThrownBy(() -> FuzzSupport.compileOrSkip(regex, 0).matcher("ab").matches())
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void missingQuantifiedCaptureIsNoLongerABlindSpot() {
    var pair =
        new FuzzSupport.MatcherPair(
            "(a)?b$",
            0,
            "ab",
            org.safere.Pattern.compile("()??ab$").matcher("ab"),
            java.util.regex.Pattern.compile("(a)?b$").matcher("ab"));
    assertThatThrownBy(pair::matches).isInstanceOf(AssertionError.class);
  }

  @Test
  void replacementDifferencesAreNotWaived() {
    String regex = "(?:(?<item>a){1})*$";
    assertThat(org.safere.Pattern.compile(regex).matcher("ab").replaceAll("${item}"))
        .isEqualTo("ab");
    assertThatThrownBy(
            () -> FuzzSupport.compileOrSkip(regex, 0).matcher("ab").replaceAll("${item}"))
        .isInstanceOf(AssertionError.class);
    assertThatThrownBy(
            () -> FuzzSupport.compileOrSkip(regex, 0).matcher("ab").replaceFirst("${item}"))
        .isInstanceOf(AssertionError.class);
    assertThatThrownBy(
            () ->
                FuzzSupport.compileOrSkip(regex, 0)
                    .matcher("ab")
                    .replaceAll(result -> result.group(1) == null ? "empty" : "captured"))
        .isInstanceOf(AssertionError.class);
  }
}
