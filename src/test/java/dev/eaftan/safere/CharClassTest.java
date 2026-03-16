// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for {@link CharClass} and {@link CharClassBuilder}. */
class CharClassTest {

  @Test
  void emptyCharClass() {
    assertEquals(0, CharClass.EMPTY.numRanges());
    assertEquals(0, CharClass.EMPTY.numRunes());
    assertTrue(CharClass.EMPTY.isEmpty());
    assertFalse(CharClass.EMPTY.contains('a'));
  }

  @Test
  void singleRange() {
    CharClass cc = new CharClassBuilder().addRange('a', 'z').build();
    assertEquals(1, cc.numRanges());
    assertEquals(26, cc.numRunes());
    assertEquals('a', cc.lo(0));
    assertEquals('z', cc.hi(0));
    assertTrue(cc.contains('a'));
    assertTrue(cc.contains('m'));
    assertTrue(cc.contains('z'));
    assertFalse(cc.contains('A'));
    assertFalse(cc.contains('0'));
  }

  @Test
  void multipleDisjointRanges() {
    CharClass cc =
        new CharClassBuilder().addRange('0', '9').addRange('A', 'Z').addRange('a', 'z').build();
    assertEquals(3, cc.numRanges());
    assertEquals(10 + 26 + 26, cc.numRunes());
    assertTrue(cc.contains('5'));
    assertTrue(cc.contains('G'));
    assertTrue(cc.contains('p'));
    assertFalse(cc.contains('!'));
    assertFalse(cc.contains('['));
  }

  @Test
  void overlappingRangesMerge() {
    CharClass cc = new CharClassBuilder().addRange('a', 'm').addRange('k', 'z').build();
    assertEquals(1, cc.numRanges());
    assertEquals(26, cc.numRunes());
    assertEquals('a', cc.lo(0));
    assertEquals('z', cc.hi(0));
  }

  @Test
  void adjacentRangesMerge() {
    CharClass cc = new CharClassBuilder().addRange('a', 'f').addRange('g', 'z').build();
    assertEquals(1, cc.numRanges());
    assertEquals(26, cc.numRunes());
  }

  @Test
  void singleRune() {
    CharClass cc = new CharClassBuilder().addRune('X').build();
    assertEquals(1, cc.numRanges());
    assertEquals(1, cc.numRunes());
    assertTrue(cc.contains('X'));
    assertFalse(cc.contains('Y'));
  }

  @Test
  void addTable() {
    int[][] table = {{0x30, 0x39}, {0x41, 0x5A}, {0x61, 0x7A}};
    CharClass cc = new CharClassBuilder().addTable(table).build();
    assertEquals(3, cc.numRanges());
    assertTrue(cc.contains('5'));
    assertTrue(cc.contains('G'));
    assertTrue(cc.contains('p'));
  }

  @Test
  void negateAsciiDigits() {
    CharClass digits = new CharClassBuilder().addRange('0', '9').build();
    CharClass notDigits = digits.negate();
    assertFalse(notDigits.contains('5'));
    assertTrue(notDigits.contains('a'));
    assertTrue(notDigits.contains(0));
    assertTrue(notDigits.contains(0x10FFFF));
  }

  @Test
  void negateSkipsSurrogates() {
    // A negated class should not include surrogates (0xD800-0xDFFF).
    CharClass empty = CharClass.EMPTY.negate();
    assertFalse(empty.contains(0xD800));
    assertFalse(empty.contains(0xDBFF));
    assertFalse(empty.contains(0xDC00));
    assertFalse(empty.contains(0xDFFF));
    assertTrue(empty.contains(0xD7FF));
    assertTrue(empty.contains(0xE000));
  }

  @Test
  void doubleNegateRestoresOriginal() {
    CharClass cc = new CharClassBuilder().addRange('a', 'z').build();
    CharClass doubleNeg = cc.negate().negate();
    assertEquals(cc.numRanges(), doubleNeg.numRanges());
    assertEquals(cc.numRunes(), doubleNeg.numRunes());
    for (int i = 0; i < cc.numRanges(); i++) {
      assertEquals(cc.lo(i), doubleNeg.lo(i));
      assertEquals(cc.hi(i), doubleNeg.hi(i));
    }
  }

  @Test
  void removeRange() {
    CharClassBuilder builder = new CharClassBuilder();
    builder.addRange('a', 'z');
    builder.removeRange('m', 'n');
    CharClass cc = builder.build();
    assertEquals(2, cc.numRanges());
    assertTrue(cc.contains('a'));
    assertTrue(cc.contains('l'));
    assertFalse(cc.contains('m'));
    assertFalse(cc.contains('n'));
    assertTrue(cc.contains('o'));
    assertTrue(cc.contains('z'));
  }

  @Test
  void removeEntireRange() {
    CharClassBuilder builder = new CharClassBuilder();
    builder.addRange('a', 'c');
    builder.removeRange('a', 'c');
    assertTrue(builder.isEmpty());
  }

  @Test
  void builderContains() {
    CharClassBuilder builder = new CharClassBuilder();
    builder.addRange('a', 'z');
    assertTrue(builder.contains('a'));
    assertTrue(builder.contains('m'));
    assertTrue(builder.contains('z'));
    assertFalse(builder.contains('A'));
  }

  @Test
  void addCharClassMerges() {
    CharClass cc1 = new CharClassBuilder().addRange('a', 'f').build();
    CharClass cc2 = new CharClassBuilder().addRange('d', 'z').build();
    CharClass merged = new CharClassBuilder().addCharClass(cc1).addCharClass(cc2).build();
    assertEquals(1, merged.numRanges());
    assertEquals(26, merged.numRunes());
  }

  @Test
  void builderFromCharClass() {
    CharClass original = new CharClassBuilder().addRange('a', 'z').addRange('0', '9').build();
    CharClassBuilder copy = new CharClassBuilder(original);
    CharClass rebuilt = copy.build();
    assertEquals(original.numRanges(), rebuilt.numRanges());
    assertEquals(original.numRunes(), rebuilt.numRunes());
  }

  @Test
  void charClassToString() {
    CharClass cc = new CharClassBuilder().addRange('a', 'z').addRune('!').build();
    String s = cc.toString();
    assertTrue(s.startsWith("["));
    assertTrue(s.endsWith("]"));
    assertTrue(s.contains("0x61-0x7A")); // a-z
    assertTrue(s.contains("0x21")); // !
  }

  @Test
  void supplementaryCodePoints() {
    // U+1F600 to U+1F64F (Emoticons)
    CharClass cc = new CharClassBuilder().addRange(0x1F600, 0x1F64F).build();
    assertEquals(1, cc.numRanges());
    assertTrue(cc.contains(0x1F600));
    assertTrue(cc.contains(0x1F64F));
    assertFalse(cc.contains(0x1F5FF));
  }
}
