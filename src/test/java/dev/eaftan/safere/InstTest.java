// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for {@link Inst}. */
class InstTest {

  @Test
  void defaultIssFail() {
    Inst inst = new Inst();
    assertEquals(InstOp.FAIL, inst.op);
  }

  @Test
  void initAlt() {
    Inst inst = new Inst();
    inst.initAlt(3, 5);
    assertEquals(InstOp.ALT, inst.op);
    assertEquals(3, inst.out);
    assertEquals(5, inst.out1);
  }

  @Test
  void initCharRange() {
    Inst inst = new Inst();
    inst.initCharRange('a', 'z', false, 7);
    assertEquals(InstOp.CHAR_RANGE, inst.op);
    assertEquals('a', inst.lo);
    assertEquals('z', inst.hi);
    assertFalse(inst.foldCase);
    assertEquals(7, inst.out);
  }

  @Test
  void initCapture() {
    Inst inst = new Inst();
    inst.initCapture(2, 10);
    assertEquals(InstOp.CAPTURE, inst.op);
    assertEquals(2, inst.arg);
    assertEquals(10, inst.out);
  }

  @Test
  void initEmptyWidth() {
    Inst inst = new Inst();
    inst.initEmptyWidth(EmptyOp.BEGIN_LINE | EmptyOp.END_LINE, 4);
    assertEquals(InstOp.EMPTY_WIDTH, inst.op);
    assertEquals(EmptyOp.BEGIN_LINE | EmptyOp.END_LINE, inst.arg);
    assertEquals(4, inst.out);
  }

  @Test
  void initMatch() {
    Inst inst = new Inst();
    inst.initMatch(0);
    assertEquals(InstOp.MATCH, inst.op);
    assertEquals(0, inst.arg);
  }

  @Test
  void initNop() {
    Inst inst = new Inst();
    inst.initNop(42);
    assertEquals(InstOp.NOP, inst.op);
    assertEquals(42, inst.out);
  }

  @Test
  void matchesChar_inRange() {
    Inst inst = new Inst();
    inst.initCharRange('a', 'z', false, 0);
    assertTrue(inst.matchesChar('a'));
    assertTrue(inst.matchesChar('m'));
    assertTrue(inst.matchesChar('z'));
    assertFalse(inst.matchesChar('A'));
    assertFalse(inst.matchesChar('0'));
  }

  @Test
  void matchesChar_foldCase() {
    Inst inst = new Inst();
    inst.initCharRange('A', 'Z', true, 0);
    assertTrue(inst.matchesChar('A'));
    assertTrue(inst.matchesChar('a')); // case-folded match
  }

  @Test
  void toStringFormats() {
    Inst alt = new Inst();
    alt.initAlt(1, 2);
    assertTrue(alt.toString().contains("alt"));

    Inst match = new Inst();
    match.initMatch(0);
    assertTrue(match.toString().contains("match"));

    Inst fail = new Inst();
    assertEquals("fail", fail.toString());
  }
}
