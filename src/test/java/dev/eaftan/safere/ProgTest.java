// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for {@link Prog}. */
class ProgTest {

  @Test
  void emptyProgram() {
    Prog prog = new Prog();
    assertEquals(0, prog.size());
  }

  @Test
  void allocInst_growsAndReturnsIndex() {
    Prog prog = new Prog();
    int i0 = prog.allocInst();
    int i1 = prog.allocInst();
    int i2 = prog.allocInst();
    assertEquals(0, i0);
    assertEquals(1, i1);
    assertEquals(2, i2);
    assertEquals(3, prog.size());
  }

  @Test
  void instReturnsCorrectInstruction() {
    Prog prog = new Prog();
    int idx = prog.allocInst();
    prog.inst(idx).initMatch(42);
    assertEquals(InstOp.MATCH, prog.inst(idx).op);
    assertEquals(42, prog.inst(idx).arg);
  }

  @Test
  void startAndStartUnanchored() {
    Prog prog = new Prog();
    prog.allocInst();
    prog.allocInst();
    prog.setStart(0);
    prog.setStartUnanchored(1);
    assertEquals(0, prog.start());
    assertEquals(1, prog.startUnanchored());
  }

  @Test
  void numCaptures() {
    Prog prog = new Prog();
    prog.setNumCaptures(3);
    assertEquals(3, prog.numCaptures());
  }

  @Test
  void anchorFlags() {
    Prog prog = new Prog();
    prog.setAnchorStart(true);
    prog.setAnchorEnd(true);
    assertTrue(prog.anchorStart());
    assertTrue(prog.anchorEnd());
  }

  @Test
  void dump_formatsInstructions() {
    Prog prog = new Prog();
    prog.setStart(0);
    int i0 = prog.allocInst();
    int i1 = prog.allocInst();
    prog.inst(i0).initCharRange('a', 'a', false, 1);
    prog.inst(i1).initMatch(0);

    String dump = prog.dump();
    assertTrue(dump.contains("char"));
    assertTrue(dump.contains("match"));
  }

  @Test
  void toStringShowsSummary() {
    Prog prog = new Prog();
    prog.allocInst();
    prog.allocInst();
    prog.setStart(0);
    prog.setNumCaptures(1);
    String s = prog.toString();
    assertTrue(s.contains("size=2"));
    assertTrue(s.contains("captures=1"));
  }
}
