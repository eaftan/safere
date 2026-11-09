// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Finds that start after the beginning of the input, where the leftmost match starts at the search
 * start and depends on an assertion whose context is the character before the search start.
 */
class SearchStartAssertionContextFindTest {

  static Stream<Arguments> findAllCases() {
    return Stream.of(
        Arguments.of("(\\w?)(b|\\b-)?", "1b-", List.of("0:2", "2:3", "3:3")),
        Arguments.of("\\w?(?:b|\\b-)?", "1b-", List.of("0:2", "2:3", "3:3")),
        Arguments.of("(x?)(x|\\Bb)?", "xxb", List.of("0:2", "2:3", "3:3")),
        Arguments.of("(?m)\\n?(?:\\n|^b)?", "\n\nb", List.of("0:2", "2:3", "3:3")),
        Arguments.of("(\\w?)(b|\\b\uD83D\uDE00)?", "1b\uD83D\uDE00", List.of("0:2", "2:4", "4:4")));
  }

  @ParameterizedTest
  @MethodSource("findAllCases")
  void findAllKeepsMatchAtPreviousMatchEnd(String regex, String input, List<String> expected) {
    assertThat(findAll(Pattern.compile(regex).matcher(input))).isEqualTo(expected);
  }

  @ParameterizedTest
  @MethodSource("findAllCases")
  void findAllKeepsMatchAtPreviousMatchEndAfterCaptureUse(
      String regex, String input, List<String> expected) {
    Pattern pattern = Pattern.compile(regex);
    Matcher warmup = pattern.matcher(input);
    assertThat(warmup.find()).isTrue();
    if (warmup.groupCount() > 0) {
      assertThat(warmup.group(1)).isNotNull();
    }
    assertThat(findAll(pattern.matcher(input))).isEqualTo(expected);
  }

  @Test
  void capturesOfMatchAtPreviousMatchEnd() {
    Matcher matcher = Pattern.compile("(\\w?)(b|\\b-)?").matcher("1b-");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(2)).isEqualTo("b");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(2);
    assertThat(matcher.group(1)).isEmpty();
    assertThat(matcher.group(2)).isEqualTo("-");
  }

  @Test
  void findFromOffset() {
    Matcher matcher = Pattern.compile("\\w?(?:b|\\b-)?").matcher("1b-");
    assertThat(matcher.find(2)).isTrue();
    assertThat(matcher.start()).isEqualTo(2);
    assertThat(matcher.end()).isEqualTo(3);

    matcher = Pattern.compile("(\\w*)(o|\\b )?").matcher("foo bar");
    assertThat(matcher.find(3)).isTrue();
    assertThat(matcher.start()).isEqualTo(3);
    assertThat(matcher.end()).isEqualTo(4);
  }

  @Test
  void transparentRegionSeesContextBeforeRegion() {
    Matcher matcher = Pattern.compile("(\\w?)(b|\\b-)?").matcher("1b-");
    matcher.region(2, 3);
    matcher.useTransparentBounds(true);
    assertThat(findAll(matcher)).containsExactly("2:3", "3:3");
  }

  @Test
  void opaqueRegionHidesContextBeforeRegion() {
    Matcher matcher = Pattern.compile("(\\w?)(b|\\b-)?").matcher("1b-");
    matcher.region(2, 3);
    assertThat(findAll(matcher)).containsExactly("2:2", "3:3");
  }

  @Test
  void replaceAll() {
    assertThat(Pattern.compile("\\w?(?:b|\\b-)?").matcher("1b-").replaceAll("<$0>"))
        .isEqualTo("<1b><-><>");
    assertThat(Pattern.compile("(?m)\\n?(?:\\n|^b)?").matcher("\n\nb").replaceAll("<$0>"))
        .isEqualTo("<\n\n><b><>");
  }

  private static List<String> findAll(Matcher matcher) {
    List<String> spans = new ArrayList<>();
    while (matcher.find()) {
      spans.add(matcher.start() + ":" + matcher.end());
    }
    return spans;
  }
}
