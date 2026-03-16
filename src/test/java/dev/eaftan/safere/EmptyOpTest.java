// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for {@link EmptyOp}. */
class EmptyOpTest {

  @Test
  void flagsArePowersOfTwo() {
    assertEquals(1, EmptyOp.BEGIN_LINE);
    assertEquals(2, EmptyOp.END_LINE);
    assertEquals(4, EmptyOp.BEGIN_TEXT);
    assertEquals(8, EmptyOp.END_TEXT);
    assertEquals(16, EmptyOp.WORD_BOUNDARY);
    assertEquals(32, EmptyOp.NON_WORD_BOUNDARY);
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
    assertEquals(combined, EmptyOp.ALL_FLAGS);
    assertEquals(63, EmptyOp.ALL_FLAGS);
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
        assertEquals(0, flags[i] & flags[j], "Flags " + i + " and " + j + " overlap");
      }
    }
  }
}
