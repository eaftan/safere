// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link EmptyOp}. */
class EmptyOpTest {

  @Test
  void flagsArePowersOfTwo() {
    assertThat(EmptyOp.BEGIN_LINE).isEqualTo(1);
    assertThat(EmptyOp.END_LINE).isEqualTo(2);
    assertThat(EmptyOp.BEGIN_TEXT).isEqualTo(4);
    assertThat(EmptyOp.END_TEXT).isEqualTo(8);
    assertThat(EmptyOp.WORD_BOUNDARY).isEqualTo(16);
    assertThat(EmptyOp.NON_WORD_BOUNDARY).isEqualTo(32);
  }

  @Test
  void allFlagsCombinesAll() {
    int combined =
        EmptyOp.BEGIN_LINE
            | EmptyOp.END_LINE
            | EmptyOp.BEGIN_TEXT
            | EmptyOp.END_TEXT
            | EmptyOp.WORD_BOUNDARY
            | EmptyOp.NON_WORD_BOUNDARY
            | EmptyOp.DOLLAR_END;
    assertThat(EmptyOp.ALL_FLAGS).isEqualTo(combined);
    assertThat(EmptyOp.ALL_FLAGS).isEqualTo(127);
  }

  @Test
  void flagsAreOrthogonal() {
    // Each pair of flags should have no bits in common.
    int[] flags = {
      EmptyOp.BEGIN_LINE,
      EmptyOp.END_LINE,
      EmptyOp.BEGIN_TEXT,
      EmptyOp.END_TEXT,
      EmptyOp.WORD_BOUNDARY,
      EmptyOp.NON_WORD_BOUNDARY
    };
    for (int i = 0; i < flags.length; i++) {
      for (int j = i + 1; j < flags.length; j++) {
        assertThat(flags[i] & flags[j])
            .withFailMessage("Flags " + i + " and " + j + " overlap")
            .isEqualTo(0);
      }
    }
  }
}
