// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link InstOp}. */
class InstOpTest {

  @Test
  void allOpsAreDefined() {
    // 8 opcodes as defined in RE2's prog.h
    assertThat(InstOp.values().length).isEqualTo(10);
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
