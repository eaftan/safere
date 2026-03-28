// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link Prog}. */
class ProgTest {

  @Test
  void emptyProgram() {
    Prog prog = new Prog();
    assertThat(prog.size()).isEqualTo(0);
  }

  @Test
  void allocInst_growsAndReturnsIndex() {
    Prog prog = new Prog();
    int i0 = prog.allocInst();
    int i1 = prog.allocInst();
    int i2 = prog.allocInst();
    assertThat(i0).isEqualTo(0);
    assertThat(i1).isEqualTo(1);
    assertThat(i2).isEqualTo(2);
    assertThat(prog.size()).isEqualTo(3);
  }

  @Test
  void instReturnsCorrectInstruction() {
    Prog prog = new Prog();
    int idx = prog.allocInst();
    prog.mutableInst(idx).initMatch(42);
    prog.freeze();
    assertThat(prog.inst(idx).op).isEqualTo(InstOp.MATCH);
    assertThat(prog.inst(idx).arg).isEqualTo(42);
  }

  @Test
  void startAndStartUnanchored() {
    Prog prog = new Prog();
    prog.allocInst();
    prog.allocInst();
    prog.setStart(0);
    prog.setStartUnanchored(1);
    assertThat(prog.start()).isEqualTo(0);
    assertThat(prog.startUnanchored()).isEqualTo(1);
  }

  @Test
  void numCaptures() {
    Prog prog = new Prog();
    prog.setNumCaptures(3);
    assertThat(prog.numCaptures()).isEqualTo(3);
  }

  @Test
  void anchorFlags() {
    Prog prog = new Prog();
    prog.setAnchorStart(true);
    prog.setAnchorEnd(true);
    assertThat(prog.anchorStart()).isTrue();
    assertThat(prog.anchorEnd()).isTrue();
  }

  @Test
  void dump_formatsInstructions() {
    Prog prog = new Prog();
    prog.setStart(0);
    int i0 = prog.allocInst();
    int i1 = prog.allocInst();
    prog.mutableInst(i0).initCharRange('a', 'a', false, 1);
    prog.mutableInst(i1).initMatch(0);
    prog.freeze();

    String dump = prog.dump();
    assertThat(dump).contains("char");
    assertThat(dump).contains("match");
  }

  @Test
  void toStringShowsSummary() {
    Prog prog = new Prog();
    prog.allocInst();
    prog.allocInst();
    prog.setStart(0);
    prog.setNumCaptures(1);
    String s = prog.toString();
    assertThat(s).contains("size=2");
    assertThat(s).contains("captures=1");
  }
}
