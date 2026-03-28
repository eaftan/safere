// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link InstOp}. */
class InstOpTest {

  @Test
  void allOpsAreDefined() {
    // 8 opcodes as defined in RE2's prog.h
    assertThat(InstOp.values().length).isEqualTo(9);
  }

  @Test
  void orderMatchesRe2() {
    assertThat(InstOp.ALT.ordinal()).isEqualTo(0);
    assertThat(InstOp.ALT_MATCH.ordinal()).isEqualTo(1);
    assertThat(InstOp.CHAR_RANGE.ordinal()).isEqualTo(2);
    assertThat(InstOp.CAPTURE.ordinal()).isEqualTo(3);
    assertThat(InstOp.EMPTY_WIDTH.ordinal()).isEqualTo(4);
    assertThat(InstOp.MATCH.ordinal()).isEqualTo(5);
    assertThat(InstOp.NOP.ordinal()).isEqualTo(6);
    assertThat(InstOp.FAIL.ordinal()).isEqualTo(7);
  }
}
