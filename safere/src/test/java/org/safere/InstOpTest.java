// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link InstOp}. */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class InstOpTest {

  @Test
  void allOpsAreDefined() {
    assertThat(InstOp.values().length).isEqualTo(11);
  }

  @Test
  @SuppressWarnings("EnumOrdinal") // Testing precise enum definition layout / ordering
  void orderMatchesRe2() {
    assertThat(InstOp.ALT.ordinal()).isEqualTo(0);
    assertThat(InstOp.ALT_MATCH.ordinal()).isEqualTo(1);
    assertThat(InstOp.CHAR_RANGE.ordinal()).isEqualTo(2);
    assertThat(InstOp.CAPTURE.ordinal()).isEqualTo(3);
    assertThat(InstOp.EMPTY_WIDTH.ordinal()).isEqualTo(4);
    assertThat(InstOp.MATCH.ordinal()).isEqualTo(5);
    assertThat(InstOp.NOP.ordinal()).isEqualTo(6);
    assertThat(InstOp.FAIL.ordinal()).isEqualTo(7);
    assertThat(InstOp.CHAR_CLASS.ordinal()).isEqualTo(8);
    assertThat(InstOp.GRAPHEME_CLUSTER.ordinal()).isEqualTo(9);
    assertThat(InstOp.PROGRESS_CHECK.ordinal()).isEqualTo(10);
  }
}
