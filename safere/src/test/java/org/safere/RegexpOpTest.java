// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link RegexpOp}. */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class RegexpOpTest {

  @Test
  void allOpsAreDefined() {
    // 21 operators as defined in RE2's regexp.h
    assertThat(RegexpOp.values().length).isEqualTo(21);
  }

  @Test
  void firstOpIsNoMatch() {
    assertThat(RegexpOp.values()[0]).isEqualTo(RegexpOp.NO_MATCH);
  }

  @Test
  void lastOpIsHaveMatch() {
    assertThat(RegexpOp.values()[RegexpOp.values().length - 1]).isEqualTo(RegexpOp.HAVE_MATCH);
  }

  @Test
  void valueOfRoundTrips() {
    for (RegexpOp op : RegexpOp.values()) {
      assertThat(op.name()).isNotNull();
      assertThat(RegexpOp.valueOf(op.name())).isEqualTo(op);
    }
  }
}
