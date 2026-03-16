// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link RegexpOp}. */
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
