// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for {@link RegexpOp}. */
class RegexpOpTest {

  @Test
  void allOpsAreDefined() {
    // 21 operators as defined in RE2's regexp.h
    assertEquals(21, RegexpOp.values().length);
  }

  @Test
  void firstOpIsNoMatch() {
    assertEquals(RegexpOp.NO_MATCH, RegexpOp.values()[0]);
  }

  @Test
  void lastOpIsHaveMatch() {
    assertEquals(RegexpOp.HAVE_MATCH, RegexpOp.values()[RegexpOp.values().length - 1]);
  }

  @Test
  void valueOfRoundTrips() {
    for (RegexpOp op : RegexpOp.values()) {
      assertNotNull(op.name());
      assertEquals(op, RegexpOp.valueOf(op.name()));
    }
  }
}
