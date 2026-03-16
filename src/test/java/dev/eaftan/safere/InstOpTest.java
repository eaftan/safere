// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Tests for {@link InstOp}. */
class InstOpTest {

  @Test
  void allOpsAreDefined() {
    // 8 opcodes as defined in RE2's prog.h
    assertEquals(8, InstOp.values().length);
  }

  @Test
  void orderMatchesRe2() {
    assertEquals(0, InstOp.ALT.ordinal());
    assertEquals(1, InstOp.ALT_MATCH.ordinal());
    assertEquals(2, InstOp.CHAR_RANGE.ordinal());
    assertEquals(3, InstOp.CAPTURE.ordinal());
    assertEquals(4, InstOp.EMPTY_WIDTH.ordinal());
    assertEquals(5, InstOp.MATCH.ordinal());
    assertEquals(6, InstOp.NOP.ordinal());
    assertEquals(7, InstOp.FAIL.ordinal());
  }
}
