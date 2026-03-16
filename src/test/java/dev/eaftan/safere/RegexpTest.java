// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Tests for {@link Regexp}. */
class RegexpTest {

  @Test
  void noMatch() {
    Regexp re = Regexp.noMatch(0);
    assertThat(re.op).isEqualTo(RegexpOp.NO_MATCH);
  }

  @Test
  void emptyMatch() {
    Regexp re = Regexp.emptyMatch(0);
    assertThat(re.op).isEqualTo(RegexpOp.EMPTY_MATCH);
  }

  @Test
  void literal() {
    Regexp re = Regexp.literal('a', 0);
    assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
    assertThat(re.rune).isEqualTo('a');
    assertThat(re.toString()).isEqualTo("a");
  }

  @Test
  void literalString() {
    int[] runes = {'h', 'e', 'l', 'l', 'o'};
    Regexp re = Regexp.literalString(runes, 0);
    assertThat(re.op).isEqualTo(RegexpOp.LITERAL_STRING);
    assertThat(re.runes).isEqualTo(runes);
    assertThat(re.toString()).isEqualTo("hello");
  }

  @Test
  void concat() {
    Regexp a = Regexp.literal('a', 0);
    Regexp b = Regexp.literal('b', 0);
    Regexp re = Regexp.concat(new Regexp[] {a, b}, 0);
    assertThat(re.op).isEqualTo(RegexpOp.CONCAT);
    assertThat(re.subs.length).isEqualTo(2);
    assertThat(re.toString()).isEqualTo("ab");
  }

  @Test
  void alternate() {
    Regexp a = Regexp.literal('a', 0);
    Regexp b = Regexp.literal('b', 0);
    Regexp re = Regexp.alternate(new Regexp[] {a, b}, 0);
    assertThat(re.op).isEqualTo(RegexpOp.ALTERNATE);
    assertThat(re.subs.length).isEqualTo(2);
    assertThat(re.toString()).isEqualTo("a|b");
  }

  @Test
  void star() {
    Regexp a = Regexp.literal('a', 0);
    Regexp re = Regexp.star(a, 0);
    assertThat(re.op).isEqualTo(RegexpOp.STAR);
    assertThat(re.sub()).isEqualTo(a);
    assertThat(re.toString()).isEqualTo("a*");
  }

  @Test
  void plus() {
    Regexp a = Regexp.literal('a', 0);
    Regexp re = Regexp.plus(a, 0);
    assertThat(re.op).isEqualTo(RegexpOp.PLUS);
    assertThat(re.toString()).isEqualTo("a+");
  }

  @Test
  void quest() {
    Regexp a = Regexp.literal('a', 0);
    Regexp re = Regexp.quest(a, 0);
    assertThat(re.op).isEqualTo(RegexpOp.QUEST);
    assertThat(re.toString()).isEqualTo("a?");
  }

  @Test
  void repeat_bounded() {
    Regexp a = Regexp.literal('a', 0);
    Regexp re = Regexp.repeat(a, 0, 2, 5);
    assertThat(re.op).isEqualTo(RegexpOp.REPEAT);
    assertThat(re.min).isEqualTo(2);
    assertThat(re.max).isEqualTo(5);
    assertThat(re.toString()).isEqualTo("a{2,5}");
  }

  @Test
  void repeat_unbounded() {
    Regexp a = Regexp.literal('a', 0);
    Regexp re = Regexp.repeat(a, 0, 3, -1);
    assertThat(re.min).isEqualTo(3);
    assertThat(re.max).isEqualTo(-1);
    assertThat(re.toString()).isEqualTo("a{3,}");
  }

  @Test
  void capture_unnamed() {
    Regexp a = Regexp.literal('a', 0);
    Regexp re = Regexp.capture(a, 0, 1, null);
    assertThat(re.op).isEqualTo(RegexpOp.CAPTURE);
    assertThat(re.cap).isEqualTo(1);
    assertThat(re.name).isNull();
    assertThat(re.toString()).isEqualTo("(a)");
  }

  @Test
  void capture_named() {
    Regexp a = Regexp.literal('a', 0);
    Regexp re = Regexp.capture(a, 0, 1, "foo");
    assertThat(re.name).isEqualTo("foo");
  }

  @Test
  void anyChar() {
    Regexp re = Regexp.anyChar(0);
    assertThat(re.op).isEqualTo(RegexpOp.ANY_CHAR);
    assertThat(re.toString()).isEqualTo(".");
  }

  @Test
  void anchors() {
    assertThat(Regexp.beginLine(0).toString()).isEqualTo("^");
    assertThat(Regexp.endLine(0).toString()).isEqualTo("$");
    assertThat(Regexp.beginText(0).toString()).isEqualTo("(?-m:^)");
    assertThat(Regexp.endText(0).toString()).isEqualTo("\\z");
    assertThat(Regexp.wordBoundary(0).toString()).isEqualTo("\\b");
    assertThat(Regexp.noWordBoundary(0).toString()).isEqualTo("\\B");
  }

  @Test
  void charClassNode() {
    CharClass cc = new CharClassBuilder().addRange('a', 'z').build();
    Regexp re = Regexp.charClass(cc, 0);
    assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
    assertThat(re.charClass).isNotNull();
    assertThat(re.toString()).contains("a-z");
  }

  @Test
  void haveMatch() {
    Regexp re = Regexp.haveMatch(42, 0);
    assertThat(re.op).isEqualTo(RegexpOp.HAVE_MATCH);
    assertThat(re.matchId).isEqualTo(42);
  }

  @Test
  void sub_throwsForWrongArity() {
    Regexp re = Regexp.concat(new Regexp[] {Regexp.literal('a', 0), Regexp.literal('b', 0)}, 0);
    assertThatThrownBy(re::sub).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void foldCase_flag() {
    Regexp re = Regexp.literal('a', ParseFlags.FOLD_CASE);
    assertThat(re.foldCase()).isTrue();
    assertThat(re.nonGreedy()).isFalse();
  }

  @Test
  void nonGreedy_flag() {
    Regexp re = Regexp.star(Regexp.literal('a', 0), ParseFlags.NON_GREEDY);
    assertThat(re.nonGreedy()).isTrue();
    assertThat(re.foldCase()).isFalse();
  }
}
