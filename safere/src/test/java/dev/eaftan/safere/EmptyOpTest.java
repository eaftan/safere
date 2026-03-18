// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

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
            | EmptyOp.NON_WORD_BOUNDARY;
    assertThat(EmptyOp.ALL_FLAGS).isEqualTo(combined);
    assertThat(EmptyOp.ALL_FLAGS).isEqualTo(63);
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
