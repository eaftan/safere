// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Candidate-start policy for scalar programs containing grapheme-boundary assertions. */
class GraphemeBoundarySearchTest {
  @ParameterizedTest
  @ValueSource(strings = {"*", "*?", "+", "+?"})
  void leadingRepetitionPreservesExplicitLowSurrogateCursor(String quantifier) {
    String regex = "(\\uDC4D" + quantifier + ")([ab])(\\b{g})";
    Matcher matcher = Pattern.compile(regex).matcher("\uD83D\uDC4Da\uD83D\uDC4Db");
    assertThat(matcher.find(1)).isTrue();
    assertThat(matcher.start()).isEqualTo(1);
    assertThat(matcher.end()).isEqualTo(3);
    assertThat(matcher.group(1)).isEqualTo("\uDC4D");
    assertThat(matcher.start(1)).isEqualTo(1);
    assertThat(matcher.end(1)).isEqualTo(2);
    assertThat(matcher.group(2)).isEqualTo("a");
    assertThat(matcher.group(3)).isEmpty();

    // Later candidates must still advance by code point past the intact second pair.
    boolean nullable = quantifier.startsWith("*");
    assertThat(matcher.find()).isEqualTo(nullable);
    if (nullable) {
      assertThat(matcher.start()).isEqualTo(5);
      assertThat(matcher.end()).isEqualTo(6);
      assertThat(matcher.group(1)).isEmpty();
      assertThat(matcher.group(2)).isEqualTo("b");
      assertThat(matcher.find()).isFalse();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"*", "*?", "+", "+?"})
  void leadingRepetitionPreservesLowSurrogateRegionStarts(String quantifier) {
    String regex = "(\\uDC4D" + quantifier + ")([ab])(\\b{g})";
    for (boolean transparent : new boolean[] {false, true}) {
      for (boolean anchoring : new boolean[] {false, true}) {
        Matcher matcher =
            Pattern.compile(regex)
                .matcher("\uD83D\uDC4Da\uD83D\uDC4Db")
                .region(1, 5)
                .useTransparentBounds(transparent)
                .useAnchoringBounds(anchoring);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(1);
        assertThat(matcher.end()).isEqualTo(3);
        assertThat(matcher.group(1)).isEqualTo("\uDC4D");
        assertThat(matcher.group(2)).isEqualTo("a");
        assertThat(matcher.group(3)).isEmpty();
        assertThat(matcher.find()).isFalse();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"\\p{Cs}", "[\\uDC00-\\uDFFF]", "\\uDC4D"})
  void trailingBoundaryDoesNotIntroduceStartsInsideSurrogatePairs(String scalar) {
    String input = "\uD83D\uDC4D\uD83D\uDC4D";
    Matcher matcher = Pattern.compile("(" + scalar + ")(\\b{g})").matcher(input);
    assertThat(matcher.find()).isFalse();

    // An explicitly requested UTF-16 search position can still begin at a low surrogate.
    assertThat(matcher.find(1)).isTrue();
    assertThat(matcher.start(1)).isEqualTo(1);
    assertThat(matcher.end(1)).isEqualTo(2);
    assertThat(matcher.start(2)).isEqualTo(2);
    assertThat(matcher.end(2)).isEqualTo(2);
    assertThat(matcher.find()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"\\p{Cs}", "[\\uDC00-\\uDFFF]", "\\uDC4D"})
  void trailingBoundaryPreservesStandaloneSurrogatesAndRegionStarts(String scalar) {
    String regex = "(" + scalar + ")(\\b{g})";
    Matcher matcher = Pattern.compile(regex).matcher("\uD83D\uDC4D\uDC4D");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(2);
    assertThat(matcher.end()).isEqualTo(3);
    assertThat(matcher.group(1)).isEqualTo("\uDC4D");
    assertThat(matcher.group(2)).isEmpty();
    assertThat(matcher.find()).isFalse();

    for (boolean transparent : new boolean[] {false, true}) {
      matcher =
          Pattern.compile(regex)
              .matcher("\uD83D\uDC4D\uD83D\uDC4D")
              .region(1, 4)
              .useTransparentBounds(transparent);
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.start()).isEqualTo(1);
      assertThat(matcher.end()).isEqualTo(2);
      assertThat(matcher.group(1)).isEqualTo("\uDC4D");
      assertThat(matcher.group(2)).isEmpty();
      assertThat(matcher.find()).isFalse();
    }
  }
}
