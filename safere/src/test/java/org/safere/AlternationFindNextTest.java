// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.safere.MultiAnchorDescriptor.Anchor.Alternation;

/**
 * Tests for {@link Alternation#findNext(String, int)}'s case-insensitive search, which precomputes
 * per-literal anchor and {@link ClassHashChain} state once at {@link Alternation#create} instead of
 * rebuilding it on every probe. Covers the 2-literal fast path and the general N-literal loop,
 * ASCII and non-ASCII literals (which take different code paths in {@code
 * Matcher.indexOfIgnoreCase}), and that per-literal state isn't cross-contaminated by index.
 */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class AlternationFindNextTest {

  @Test
  @DisplayName("two ASCII literals: finds either, case-insensitively")
  void twoAsciiLiterals() {
    Alternation alt = Alternation.create(new String[] {"foo", "bar"}, true);

    assertThat(alt.findNext("xxxBARyyy", 0)).isEqualTo(3);
    assertThat(alt.findNext("xxxFOOyyy", 0)).isEqualTo(3);
    assertThat(alt.findNext("no match here", 0)).isEqualTo(-1);
  }

  @Test
  @DisplayName("N (>2) ASCII literals of length >= 4: each literal matches at its own position")
  void manyAsciiLiteralsEachMatchIndependently() {
    String[] literals = {"alpha", "bravo", "charlie", "delta"};
    Alternation alt = Alternation.create(literals, true);

    assertThat(alt.findNext("zzzzzALPHAzzzz", 0)).isEqualTo(5);
    assertThat(alt.findNext("zzzzzBRAVOzzzz", 0)).isEqualTo(5);
    assertThat(alt.findNext("zzzzzCHARLIEzzzz", 0)).isEqualTo(5);
    assertThat(alt.findNext("zzzzzDELTAzzzz", 0)).isEqualTo(5);
    assertThat(alt.findNext("zzzzzECHOzzzz", 0)).isEqualTo(-1);
  }

  @Test
  @DisplayName("fromIndex skips an earlier match to find a later one, per literal")
  void fromIndexSkipsEarlierMatch() {
    String[] literals = {"alpha", "bravo", "charlie", "delta"};
    Alternation alt = Alternation.create(literals, true);
    String text = "..ALPHA....BRAVO....CHARLIE....DELTA..";

    int p0 = alt.findNext(text, 0);
    assertThat(p0).isEqualTo(2);
    int p1 = alt.findNext(text, p0 + 1);
    assertThat(p1).isEqualTo(11);
    int p2 = alt.findNext(text, p1 + 1);
    assertThat(p2).isEqualTo(20);
    int p3 = alt.findNext(text, p2 + 1);
    assertThat(p3).isEqualTo(31);
    assertThat(alt.findNext(text, p3 + 1)).isEqualTo(-1);
  }

  @Test
  @DisplayName("non-ASCII literal (length >= 4) takes the Unicode fold path and still matches")
  void nonAsciiLiteralUnicodeFold() {
    // "MÜNCHEN" is non-ASCII (Ü); "BERLIN" is ASCII. Mixing both in one Alternation exercises
    // both the pure-Unicode branch and the ASCII branch of Matcher.indexOfIgnoreCase in the same
    // findNext call, from the same precomputed per-literal state.
    String[] literals = {"MÜNCHEN", "BERLIN"};
    Alternation alt = Alternation.create(literals, true);

    assertThat(alt.findNext("prefix münchen suffix", 0)).isEqualTo(7);
    assertThat(alt.findNext("prefix München suffix", 0)).isEqualTo(7);
    assertThat(alt.findNext("prefix berlin suffix", 0)).isEqualTo(7);
    assertThat(alt.findNext("prefix münchen and berlin suffix", 8)).isEqualTo(19);
    assertThat(alt.findNext("no city here", 0)).isEqualTo(-1);
  }

  @Test
  @DisplayName("single-character literals use anchor low/high directly, no ClassHashChain")
  void singleCharacterLiterals() {
    Alternation alt = Alternation.create(new String[] {"x", "q"}, true);

    assertThat(alt.findNext("...X...", 0)).isEqualTo(3);
    assertThat(alt.findNext("...Q...", 0)).isEqualTo(3);
    assertThat(alt.findNext("no match", 0)).isEqualTo(-1);
  }

  @Test
  @DisplayName("non-folded (case-sensitive) alternation is unaffected by the precomputed fields")
  void nonFoldedAlternationUnaffected() {
    Alternation alt = Alternation.create(new String[] {"foo", "bar"}, false);

    assertThat(alt.findNext("xxxbarYYY", 0)).isEqualTo(3);
    assertThat(alt.findNext("xxxBARYYY", 0)).isEqualTo(-1);
  }
}
