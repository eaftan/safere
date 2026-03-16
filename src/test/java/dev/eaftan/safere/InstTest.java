// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link Inst}. */
class InstTest {

  @Test
  void defaultIssFail() {
    Inst inst = new Inst();
    assertThat(inst.op).isEqualTo(InstOp.FAIL);
  }

  @Test
  void initAlt() {
    Inst inst = new Inst();
    inst.initAlt(3, 5);
    assertThat(inst.op).isEqualTo(InstOp.ALT);
    assertThat(inst.out).isEqualTo(3);
    assertThat(inst.out1).isEqualTo(5);
  }

  @Test
  void initCharRange() {
    Inst inst = new Inst();
    inst.initCharRange('a', 'z', false, 7);
    assertThat(inst.op).isEqualTo(InstOp.CHAR_RANGE);
    assertThat(inst.lo).isEqualTo('a');
    assertThat(inst.hi).isEqualTo('z');
    assertThat(inst.foldCase).isFalse();
    assertThat(inst.out).isEqualTo(7);
  }

  @Test
  void initCapture() {
    Inst inst = new Inst();
    inst.initCapture(2, 10);
    assertThat(inst.op).isEqualTo(InstOp.CAPTURE);
    assertThat(inst.arg).isEqualTo(2);
    assertThat(inst.out).isEqualTo(10);
  }

  @Test
  void initEmptyWidth() {
    Inst inst = new Inst();
    inst.initEmptyWidth(EmptyOp.BEGIN_LINE | EmptyOp.END_LINE, 4);
    assertThat(inst.op).isEqualTo(InstOp.EMPTY_WIDTH);
    assertThat(inst.arg).isEqualTo(EmptyOp.BEGIN_LINE | EmptyOp.END_LINE);
    assertThat(inst.out).isEqualTo(4);
  }

  @Test
  void initMatch() {
    Inst inst = new Inst();
    inst.initMatch(0);
    assertThat(inst.op).isEqualTo(InstOp.MATCH);
    assertThat(inst.arg).isEqualTo(0);
  }

  @Test
  void initNop() {
    Inst inst = new Inst();
    inst.initNop(42);
    assertThat(inst.op).isEqualTo(InstOp.NOP);
    assertThat(inst.out).isEqualTo(42);
  }

  @Test
  void matchesChar_inRange() {
    Inst inst = new Inst();
    inst.initCharRange('a', 'z', false, 0);
    assertThat(inst.matchesChar('a')).isTrue();
    assertThat(inst.matchesChar('m')).isTrue();
    assertThat(inst.matchesChar('z')).isTrue();
    assertThat(inst.matchesChar('A')).isFalse();
    assertThat(inst.matchesChar('0')).isFalse();
  }

  @Test
  void matchesChar_foldCase() {
    Inst inst = new Inst();
    inst.initCharRange('A', 'Z', true, 0);
    assertThat(inst.matchesChar('A')).isTrue();
    assertThat(inst.matchesChar('a')).isTrue(); // case-folded match
  }

  @Test
  void toStringFormats() {
    Inst alt = new Inst();
    alt.initAlt(1, 2);
    assertThat(alt.toString()).contains("alt");

    Inst match = new Inst();
    match.initMatch(0);
    assertThat(match.toString()).contains("match");

    Inst fail = new Inst();
    assertThat(fail.toString()).isEqualTo("fail");
  }
}
