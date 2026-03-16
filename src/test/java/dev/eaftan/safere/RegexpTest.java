// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for {@link Regexp}. */
class RegexpTest {

  @Test
  void noMatch() {
    Regexp re = Regexp.noMatch(0);
    assertEquals(RegexpOp.NO_MATCH, re.op);
  }

  @Test
  void emptyMatch() {
    Regexp re = Regexp.emptyMatch(0);
    assertEquals(RegexpOp.EMPTY_MATCH, re.op);
  }

  @Test
  void literal() {
    Regexp re = Regexp.literal('a', 0);
    assertEquals(RegexpOp.LITERAL, re.op);
    assertEquals('a', re.rune);
    assertEquals("a", re.toString());
  }

  @Test
  void literalString() {
    int[] runes = {'h', 'e', 'l', 'l', 'o'};
    Regexp re = Regexp.literalString(runes, 0);
    assertEquals(RegexpOp.LITERAL_STRING, re.op);
    assertArrayEquals(runes, re.runes);
    assertEquals("hello", re.toString());
  }

  @Test
  void concat() {
    Regexp a = Regexp.literal('a', 0);
    Regexp b = Regexp.literal('b', 0);
    Regexp re = Regexp.concat(new Regexp[] {a, b}, 0);
    assertEquals(RegexpOp.CONCAT, re.op);
    assertEquals(2, re.subs.length);
    assertEquals("ab", re.toString());
  }

  @Test
  void alternate() {
    Regexp a = Regexp.literal('a', 0);
    Regexp b = Regexp.literal('b', 0);
    Regexp re = Regexp.alternate(new Regexp[] {a, b}, 0);
    assertEquals(RegexpOp.ALTERNATE, re.op);
    assertEquals(2, re.subs.length);
    assertEquals("(?:a|b)", re.toString());
  }

  @Test
  void star() {
    Regexp a = Regexp.literal('a', 0);
    Regexp re = Regexp.star(a, 0);
    assertEquals(RegexpOp.STAR, re.op);
    assertEquals(a, re.sub());
    assertEquals("a*", re.toString());
  }

  @Test
  void plus() {
    Regexp a = Regexp.literal('a', 0);
    Regexp re = Regexp.plus(a, 0);
    assertEquals(RegexpOp.PLUS, re.op);
    assertEquals("a+", re.toString());
  }

  @Test
  void quest() {
    Regexp a = Regexp.literal('a', 0);
    Regexp re = Regexp.quest(a, 0);
    assertEquals(RegexpOp.QUEST, re.op);
    assertEquals("a?", re.toString());
  }

  @Test
  void repeat_bounded() {
    Regexp a = Regexp.literal('a', 0);
    Regexp re = Regexp.repeat(a, 0, 2, 5);
    assertEquals(RegexpOp.REPEAT, re.op);
    assertEquals(2, re.min);
    assertEquals(5, re.max);
    assertEquals("a{2,5}", re.toString());
  }

  @Test
  void repeat_unbounded() {
    Regexp a = Regexp.literal('a', 0);
    Regexp re = Regexp.repeat(a, 0, 3, -1);
    assertEquals(3, re.min);
    assertEquals(-1, re.max);
    assertEquals("a{3,}", re.toString());
  }

  @Test
  void capture_unnamed() {
    Regexp a = Regexp.literal('a', 0);
    Regexp re = Regexp.capture(a, 0, 1, null);
    assertEquals(RegexpOp.CAPTURE, re.op);
    assertEquals(1, re.cap);
    assertNull(re.name);
    assertEquals("(a)", re.toString());
  }

  @Test
  void capture_named() {
    Regexp a = Regexp.literal('a', 0);
    Regexp re = Regexp.capture(a, 0, 1, "foo");
    assertEquals("foo", re.name);
  }

  @Test
  void anyChar() {
    Regexp re = Regexp.anyChar(0);
    assertEquals(RegexpOp.ANY_CHAR, re.op);
    assertEquals(".", re.toString());
  }

  @Test
  void anchors() {
    assertEquals("^", Regexp.beginLine(0).toString());
    assertEquals("$", Regexp.endLine(0).toString());
    assertEquals("\\A", Regexp.beginText(0).toString());
    assertEquals("\\z", Regexp.endText(0).toString());
    assertEquals("\\b", Regexp.wordBoundary(0).toString());
    assertEquals("\\B", Regexp.noWordBoundary(0).toString());
  }

  @Test
  void charClassNode() {
    CharClass cc = new CharClassBuilder().addRange('a', 'z').build();
    Regexp re = Regexp.charClass(cc, 0);
    assertEquals(RegexpOp.CHAR_CLASS, re.op);
    assertNotNull(re.charClass);
    assertTrue(re.toString().contains("0x61-0x7A"));
  }

  @Test
  void haveMatch() {
    Regexp re = Regexp.haveMatch(42, 0);
    assertEquals(RegexpOp.HAVE_MATCH, re.op);
    assertEquals(42, re.matchId);
  }

  @Test
  void sub_throwsForWrongArity() {
    Regexp re = Regexp.concat(new Regexp[] {Regexp.literal('a', 0), Regexp.literal('b', 0)}, 0);
    assertThrows(IllegalStateException.class, re::sub);
  }

  @Test
  void foldCase_flag() {
    Regexp re = Regexp.literal('a', ParseFlags.FOLD_CASE);
    assertTrue(re.foldCase());
    assertFalse(re.nonGreedy());
  }

  @Test
  void nonGreedy_flag() {
    Regexp re = Regexp.star(Regexp.literal('a', 0), ParseFlags.NON_GREEDY);
    assertTrue(re.nonGreedy());
    assertFalse(re.foldCase());
  }
}
