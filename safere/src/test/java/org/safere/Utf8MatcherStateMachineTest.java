// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class Utf8MatcherStateMachineTest {
  @Test
  void repeatedFindReportsByteRelativeGroupBounds() {
    Utf8Matcher matcher = matcher("(é)|(😀)", "xé😀y");

    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(1);
    assertThat(matcher.end()).isEqualTo(3);
    assertThat(matcher.start(1)).isEqualTo(1);
    assertThat(matcher.end(1)).isEqualTo(3);
    assertThat(matcher.start(2)).isEqualTo(-1);
    assertThat(matcher.end(2)).isEqualTo(-1);

    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(3);
    assertThat(matcher.end()).isEqualTo(7);
    assertThat(matcher.start(1)).isEqualTo(-1);
    assertThat(matcher.start(2)).isEqualTo(3);
    assertThat(matcher.end(2)).isEqualTo(7);

    assertThat(matcher.find()).isFalse();
    assertThat(matcher.find()).isFalse();
  }

  @Test
  void emptyFindAdvancesByOneCodePoint() {
    Utf8Matcher matcher = matcher("", "😀");
    List<List<Integer>> bounds = new ArrayList<>();

    while (matcher.find()) {
      bounds.add(List.of(matcher.start(), matcher.end()));
    }

    assertThat(bounds).containsExactly(List.of(0, 0), List.of(4, 4));
  }

  @Test
  void failedFindInvalidatesPreviousResult() {
    Utf8Matcher matcher = matcher("a", "a");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.find()).isFalse();

    assertThatThrownBy(matcher::start).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(matcher::end).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void observationBeforeMatchAndInvalidGroupsThrow() {
    Utf8Matcher matcher = matcher("(a)", "a");

    assertThatThrownBy(matcher::start).isInstanceOf(IllegalStateException.class);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.groupCount()).isEqualTo(1);
    assertThatThrownBy(() -> matcher.start(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> matcher.end(2)).isInstanceOf(IndexOutOfBoundsException.class);
  }

  @Test
  void patternEntryPointsRejectNullInput() {
    Pattern pattern = Pattern.compile("a");

    assertThatThrownBy(() -> pattern.matcher((Utf8Input) null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> pattern.find((Utf8Input) null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void wholeArrayAndWindowViewsProduceTheSameFindTrace() {
    byte[] logical = "xé😀y".getBytes(UTF_8);
    byte[] storage = new byte[logical.length + 6];
    System.arraycopy(logical, 0, storage, 3, logical.length);
    Pattern pattern = Pattern.compile("é|😀");

    assertThat(findTrace(pattern.matcher(Utf8Input.validated(logical))))
        .isEqualTo(findTrace(pattern.matcher(Utf8Input.validated(storage, 3, logical.length))));
  }

  @Test
  void inputWindowHidesBackingStorageAndKeepsRelativeBounds() {
    byte[] logical = "xéy".getBytes(UTF_8);
    byte[] storage = "before--------after".getBytes(UTF_8);
    System.arraycopy(logical, 0, storage, 7, logical.length);
    Utf8Input input = Utf8Input.validated(storage, 7, logical.length);

    Utf8Matcher matcher = Pattern.compile("é").matcher(input);

    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(1);
    assertThat(matcher.end()).isEqualTo(3);
    assertThat(matcher.find()).isFalse();
  }

  @Test
  void patternBooleanSearchUsesUtf8Input() {
    Pattern pattern = Pattern.compile("😀$");

    assertThat(pattern.find(Utf8Input.validated("x😀".getBytes(UTF_8)))).isTrue();
    assertThat(pattern.find(Utf8Input.validated("😀x".getBytes(UTF_8)))).isFalse();
    assertThat(Pattern.compile("(?<letter>é)|😀").find(Utf8Input.validated("xé".getBytes(UTF_8))))
        .isTrue();
    assertThat(Pattern.compile("\\Xz").find(Utf8Input.validated("👩‍💻x".getBytes(UTF_8))))
        .isFalse();
  }

  @Test
  void graphemePatternsWorkAcrossUtf8Scalars() {
    for (String input : List.of("a\r\nb", "a\u0301b", "👩‍💻x", "🇺🇸x", "क्‍षx")) {
      byte[] bytes = input.getBytes(UTF_8);
      Utf8Matcher clusters = Pattern.compile("\\X").matcher(Utf8Input.validated(bytes));
      List<List<Integer>> actualClusters = new ArrayList<>();
      while (clusters.find()) {
        actualClusters.add(List.of(clusters.start(), clusters.end()));
      }

      Matcher stringClusters = Pattern.compile("\\X").matcher(input);
      List<List<Integer>> expectedClusters = new ArrayList<>();
      while (stringClusters.find()) {
        expectedClusters.add(
            List.of(
                utf8Offset(input, stringClusters.start()),
                utf8Offset(input, stringClusters.end())));
      }
      assertThat(actualClusters).as("grapheme clusters for %s", input).isEqualTo(expectedClusters);

      Utf8Matcher boundaries = Pattern.compile("\\b{g}").matcher(Utf8Input.validated(bytes));
      List<Integer> actualBoundaries = new ArrayList<>();
      while (boundaries.find()) {
        actualBoundaries.add(boundaries.start());
      }
      Matcher stringBoundaries = Pattern.compile("\\b{g}").matcher(input);
      List<Integer> expectedBoundaries = new ArrayList<>();
      while (stringBoundaries.find()) {
        expectedBoundaries.add(utf8Offset(input, stringBoundaries.start()));
      }
      assertThat(actualBoundaries)
          .as("grapheme boundaries for %s", input)
          .isEqualTo(expectedBoundaries);
    }
  }

  private static Utf8Matcher matcher(String pattern, String input) {
    return Pattern.compile(pattern).matcher(Utf8Input.validated(input.getBytes(UTF_8)));
  }

  private static List<List<Integer>> findTrace(Utf8Matcher matcher) {
    List<List<Integer>> trace = new ArrayList<>();
    while (matcher.find()) {
      trace.add(List.of(matcher.start(), matcher.end()));
    }
    return trace;
  }

  private static int utf8Offset(String text, int utf16Offset) {
    return text.substring(0, utf16Offset).getBytes(UTF_8).length;
  }
}
