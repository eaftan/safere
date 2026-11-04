// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.regex.MatchResult;
import org.junit.jupiter.api.Test;

class CapturePolicyLifecycleTest {
  @Test
  void resetInputAndRegionChangesPreservePublicResultsAcrossUsageChanges() {
    String regex = "(?<word>[a-z]+)([0-9]*)";
    Pattern compiled = Pattern.compile(regex);
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);
    Matcher actual = compiled.matcher("");
    java.util.regex.Matcher expected = jdk.matcher("");
    for (int cycle = 0; cycle < 64; cycle++) {
      String input = cycle % 3 == 0 ? " ".repeat(96) + "alpha12 beta333" : "7".repeat(128);
      actual.reset(input);
      expected.reset(input);
      actual.useTransparentBounds((cycle & 1) == 0).useAnchoringBounds((cycle & 2) == 0);
      expected.useTransparentBounds((cycle & 1) == 0).useAnchoringBounds((cycle & 2) == 0);
      actual.region(2, input.length() - 1);
      expected.region(2, input.length() - 1);
      while (expected.find()) {
        assertThat(actual.find()).isTrue();
        assertThat(actual.start()).isEqualTo(expected.start());
        assertThat(actual.end()).isEqualTo(expected.end());
        if (cycle % 4 != 0) {
          assertThat(actual.group("word")).isEqualTo(expected.group("word"));
          assertThat(actual.group(2)).isEqualTo(expected.group(2));
        }
      }
      assertThat(actual.find()).isFalse();
    }
  }

  @Test
  void snapshotsRemainIndependentAndPatternChangesRestartLocalLearning() {
    Matcher matcher = Pattern.compile("(a+)(b?)").matcher(" ".repeat(96) + "aaab aa");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group()).isEqualTo("aaab");
    assertThatThrownBy(() -> matcher.group(9)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThat(matcher.capturePolicySnapshot().reads()).isZero();
    MatchResult snapshot = matcher.toMatchResult();
    assertThat(matcher.find()).isTrue();
    assertThat(snapshot.group(1)).isEqualTo("aaa");
    assertThat(snapshot.group(2)).isEqualTo("b");
    assertThat(matcher.capturePolicySnapshot().reads()).isEqualTo(1);
    matcher.usePattern(Pattern.compile("(c+)"));
    assertThat(matcher.capturePolicySnapshot().successes()).isZero();
    assertThat(matcher.capturePolicySnapshot().attempts()).isZero();
    matcher.reset(" ".repeat(96) + "ccc");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(1)).isEqualTo("ccc");
    assertThat(snapshot.group()).isEqualTo("aaab");
  }

  @Test
  void replacementRequirementsDoNotDependOnHistoricalPreference() {
    String regex = "([a-z]+)([0-9]+)";
    String input = " ".repeat(96) + "alpha12 beta333";
    Pattern compiled = Pattern.compile(regex);
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);
    Matcher actual = compiled.matcher(input);
    java.util.regex.Matcher expected = jdk.matcher(input);
    for (int i = 0; i < 64; i++) {
      actual.reset();
      expected.reset();
      assertThat(actual.replaceAll("$2:$1")).isEqualTo(expected.replaceAll("$2:$1"));
      actual.reset();
      expected.reset();
      assertThat(actual.replaceFirst("$1/$2")).isEqualTo(expected.replaceFirst("$1/$2"));
    }
    actual.reset();
    expected.reset();
    StringBuilder actualText = new StringBuilder();
    StringBuilder expectedText = new StringBuilder();
    while (expected.find()) {
      assertThat(actual.find()).isTrue();
      actual.appendReplacement(actualText, "$2-$1");
      expected.appendReplacement(expectedText, "$2-$1");
    }
    actual.appendTail(actualText);
    expected.appendTail(expectedText);
    assertThat(actualText.toString()).isEqualTo(expectedText.toString());
  }

  @Test
  void utf8RetainedInputAndRegionsKeepCodePointAlignedNativeBounds() {
    String regex = "((?:é|😀)+)(x?)";
    String input = " ".repeat(96) + "é😀x | éé";
    byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
    Utf8Matcher actual = Pattern.compile(regex).matcher(Utf8Input.trusted(bytes));
    java.util.regex.Matcher expected = java.util.regex.Pattern.compile(regex).matcher(input);
    for (int cycle = 0; cycle < 64; cycle++) {
      int start = cycle % 2 == 0 ? 0 : 96;
      actual.reset().region(start, bytes.length);
      expected.reset().region(start, input.length());
      while (expected.find()) {
        assertThat(actual.find()).isTrue();
        for (int group = 0; group <= 2; group++) {
          if (cycle % 4 == 0 && group > 0) {
            continue;
          }
          int expectedStart =
              input.substring(0, expected.start(group)).getBytes(StandardCharsets.UTF_8).length;
          int expectedEnd =
              input.substring(0, expected.end(group)).getBytes(StandardCharsets.UTF_8).length;
          assertThat(actual.start(group)).isEqualTo(expectedStart);
          assertThat(actual.end(group)).isEqualTo(expectedEnd);
        }
      }
      assertThat(actual.find()).isFalse();
    }
  }
}
