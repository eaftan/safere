// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.re2ffm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RE2FfmMatcherTest {
  @ParameterizedTest
  @CsvSource({"a, ab, true", "b, ab, false", "a+, aaab, true", "a+, b, false", "'', abc, true"})
  void lookingAtUsesNativeStartAnchoring(String regex, String input, boolean expected) {
    RE2FfmMatcher matcher = RE2FfmPattern.compile(regex).matcher(input);
    assertThat(matcher.lookingAt()).isEqualTo(expected);
    assertThat(java.util.regex.Pattern.compile(regex).matcher(input).lookingAt())
        .isEqualTo(expected);
    if (!expected) {
      assertThatThrownBy(matcher::group).isInstanceOf(IllegalStateException.class);
    }
  }

  @ParameterizedTest
  @CsvSource({"(a|ab), ab", "(a+?), aa", "((a)?b), b", "(a*)(b?), ab"})
  void fullMatchCapturesComeFromTheAnchoredOperation(String regex, String input) {
    var expected = java.util.regex.Pattern.compile(regex).matcher(input);
    var actual = RE2FfmPattern.compile(regex).matcher(input);
    assertThat(actual.matches()).isEqualTo(expected.matches());
    if (expected.hasMatch()) {
      for (int group = 0; group <= expected.groupCount(); group++) {
        assertThat(actual.group(group)).isEqualTo(expected.group(group));
        assertThat(actual.start(group)).isEqualTo(expected.start(group));
        assertThat(actual.end(group)).isEqualTo(expected.end(group));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"(ab)", "^(ab)$", "\\A(ab)\\z", "\\b(ab)\\b"})
  void regionsUseOpaqueAnchoringBoundsAndAbsoluteOffsets(String regex) {
    String input = "xabz";
    var expected = java.util.regex.Pattern.compile(regex).matcher(input).region(1, 3);
    var actual = RE2FfmPattern.compile(regex).matcher(input).region(1, 3);
    assertThat(actual.regionStart()).isEqualTo(1);
    assertThat(actual.regionEnd()).isEqualTo(3);
    assertThat(actual.matches()).isEqualTo(expected.matches()).isTrue();
    assertThat(actual.group(1)).isEqualTo("ab");
    assertThat(actual.start(1)).isEqualTo(1);
    assertThat(actual.end(1)).isEqualTo(3);
    actual.region(1, 3);
    assertThat(actual.lookingAt()).isTrue();
    actual.region(1, 3);
    assertThat(actual.find()).isTrue();
    assertThat(actual.find()).isFalse();
  }

  @Test
  void findStaysWithinRegionAndRegionDiscardsMatchState() {
    var actual = RE2FfmPattern.compile("a").matcher("aaxa");
    assertThat(actual.find()).isTrue();
    actual.region(1, 3);
    assertThatThrownBy(actual::group).isInstanceOf(IllegalStateException.class);
    assertThat(actual.find()).isTrue();
    assertThat(actual.start()).isEqualTo(1);
    assertThat(actual.find()).isFalse();
    assertThat(actual.find()).isFalse();
    actual.region(3, 4);
    assertThat(actual.lookingAt()).isTrue();
    assertThat(actual.start()).isEqualTo(3);
  }

  @Test
  void resetAndFindStartRestoreWholeInput() {
    var actual = RE2FfmPattern.compile("a").matcher("aba").region(2, 3);
    assertThat(actual.matches()).isTrue();
    actual.reset();
    assertThat(actual.regionStart()).isZero();
    assertThat(actual.regionEnd()).isEqualTo(3);
    assertThatThrownBy(actual::group).isInstanceOf(IllegalStateException.class);
    assertThat(actual.find()).isTrue();
    assertThat(actual.start()).isZero();
    actual.region(2, 3);
    assertThat(actual.find(0)).isTrue();
    assertThat(actual.start()).isZero();
    assertThat(actual.regionStart()).isZero();
    actual.reset("za");
    assertThat(actual.regionEnd()).isEqualTo(2);
    assertThat(actual.find()).isTrue();
    assertThat(actual.start()).isEqualTo(1);
  }

  @Test
  void anchoredSuccessAdvancesFindWithoutRepeatingTheMatch() {
    var actual = RE2FfmPattern.compile("a").matcher("aa");
    assertThat(actual.lookingAt()).isTrue();
    assertThat(actual.find()).isTrue();
    assertThat(actual.start()).isEqualTo(1);
    actual.reset("a");
    assertThat(actual.matches()).isTrue();
    assertThat(actual.find()).isFalse();
  }

  @Test
  void emptyRegionsAndEmptyCapturesRemainParticipating() {
    for (String input : List.of("", "ab")) {
      int end = input.length();
      var actual = RE2FfmPattern.compile("()").matcher(input).region(end, end);
      assertThat(actual.matches()).isTrue();
      assertThat(actual.group(1)).isEmpty();
      assertThat(actual.start(1)).isEqualTo(end);
      assertThat(actual.find()).isFalse();
      actual.region(end, end);
      assertThat(actual.find()).isTrue();
      assertThat(actual.find()).isFalse();
    }
  }

  @Test
  void regionsUseUtf16CoordinatesAtCodePointBoundaries() {
    String input = "é😀世😀z";
    var actual = RE2FfmPattern.compile("(😀)").matcher(input).region(1, 6);
    var expected = java.util.regex.Pattern.compile("(😀)").matcher(input).region(1, 6);
    while (expected.find()) {
      assertThat(actual.find()).isTrue();
      assertThat(actual.start(1)).isEqualTo(expected.start(1));
      assertThat(actual.end(1)).isEqualTo(expected.end(1));
      assertThat(actual.group(1)).isEqualTo(expected.group(1));
    }
    assertThat(actual.find()).isFalse();
  }

  @ParameterizedTest
  @CsvSource({"-1, 2", "0, -1", "3, 2", "0, 4", "4, 4"})
  void invalidRegionsDoNotMutateExistingState(int start, int end) {
    var actual = RE2FfmPattern.compile("b").matcher("abc").region(1, 2);
    assertThat(actual.matches()).isTrue();
    assertThatThrownBy(() -> actual.region(start, end))
        .isInstanceOf(IndexOutOfBoundsException.class);
    assertThat(actual.regionStart()).isEqualTo(1);
    assertThat(actual.group()).isEqualTo("b");
  }

  @Test
  void surrogateSplittingBoundsAreExplicitlyUnsupported() {
    var actual = RE2FfmPattern.compile(".").matcher("😀");
    assertThatThrownBy(() -> actual.region(1, 2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("surrogate");
    assertThatThrownBy(() -> actual.region(0, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("surrogate");
    assertThatThrownBy(() -> actual.find(1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> actual.find(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> actual.find(3)).isInstanceOf(IndexOutOfBoundsException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"(a|ab)", "(a+?)"})
  void prefixMatchingRetainsNativeAlternationAndLazySelection(String regex) {
    var actual = RE2FfmPattern.compile(regex).matcher("ab");
    assertThat(actual.lookingAt()).isTrue();
    assertThat(actual.group()).isEqualTo("a");
    assertThat(actual.group(1)).isEqualTo("a");
  }

  @ParameterizedTest
  @ValueSource(strings = {"a", "(a)()()()()()()()()()"})
  void replacementConvenienceMethodsRestoreWholeInputRegion(String regex) {
    var actual = RE2FfmPattern.compile(regex).matcher("aba").region(2, 3);
    assertThat(actual.replaceAll("x")).isEqualTo("xbx");
    assertThat(actual.regionStart()).isZero();
    assertThat(actual.regionEnd()).isEqualTo(3);
    actual.region(2, 3);
    assertThat(actual.replaceFirst("x")).isEqualTo("xba");
    assertThat(actual.regionStart()).isZero();
    assertThat(actual.regionEnd()).isEqualTo(3);
  }

  @Test
  void emptyMatchesAdvanceByCodePoint() {
    var actual = RE2FfmPattern.compile("").matcher("😀");
    assertThat(actual.find()).isTrue();
    assertThat(actual.start()).isZero();
    assertThat(actual.find()).isTrue();
    assertThat(actual.start()).isEqualTo(2);
    assertThat(actual.find()).isFalse();
  }
}
