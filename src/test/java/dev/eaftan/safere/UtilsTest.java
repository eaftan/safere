// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link Utils}. */
class UtilsTest {

  @Test
  void isValidRune_validCodePoints() {
    assertTrue(Utils.isValidRune(0)); // NUL
    assertTrue(Utils.isValidRune('A'));
    assertTrue(Utils.isValidRune(0x7F)); // DEL
    assertTrue(Utils.isValidRune(0x80)); // First non-ASCII
    assertTrue(Utils.isValidRune(0xFFFF)); // Last BMP
    assertTrue(Utils.isValidRune(0x10000)); // First supplementary
    assertTrue(Utils.isValidRune(0x10FFFF)); // Max code point
  }

  @Test
  void isValidRune_invalidCodePoints() {
    assertFalse(Utils.isValidRune(-1));
    assertFalse(Utils.isValidRune(0x110000)); // Beyond max
    assertFalse(Utils.isValidRune(0xD800)); // Surrogate start
    assertFalse(Utils.isValidRune(0xDBFF)); // High surrogate end
    assertFalse(Utils.isValidRune(0xDC00)); // Low surrogate start
    assertFalse(Utils.isValidRune(0xDFFF)); // Surrogate end
  }

  @ParameterizedTest
  @ValueSource(ints = {'0', '5', '9', 'a', 'z', 'A', 'Z'})
  void isAlnum_trueForAlnumChars(int r) {
    assertTrue(Utils.isAlnum(r));
  }

  @ParameterizedTest
  @ValueSource(ints = {'!', ' ', '@', '[', '{', 0x00, 0xFF})
  void isAlnum_falseForNonAlnumChars(int r) {
    assertFalse(Utils.isAlnum(r));
  }

  @Test
  void isWordChar_includesUnderscore() {
    assertTrue(Utils.isWordChar('_'));
    assertTrue(Utils.isWordChar('a'));
    assertTrue(Utils.isWordChar('0'));
    assertFalse(Utils.isWordChar('-'));
    assertFalse(Utils.isWordChar(' '));
  }

  @Test
  void unhex_validDigits() {
    assertEquals(0, Utils.unhex('0'));
    assertEquals(9, Utils.unhex('9'));
    assertEquals(10, Utils.unhex('A'));
    assertEquals(15, Utils.unhex('F'));
    assertEquals(10, Utils.unhex('a'));
    assertEquals(15, Utils.unhex('f'));
  }

  @Test
  void unhex_invalidChars() {
    assertEquals(-1, Utils.unhex('G'));
    assertEquals(-1, Utils.unhex('g'));
    assertEquals(-1, Utils.unhex(' '));
    assertEquals(-1, Utils.unhex('/'));
    assertEquals(-1, Utils.unhex(':'));
  }

  @Test
  void toLower_convertsUppercase() {
    assertEquals('a', Utils.toLower('A'));
    assertEquals('z', Utils.toLower('Z'));
  }

  @Test
  void toLower_leavesOthersUnchanged() {
    assertEquals('a', Utils.toLower('a'));
    assertEquals('0', Utils.toLower('0'));
    assertEquals('!', Utils.toLower('!'));
  }

  @Test
  void toUpper_convertsLowercase() {
    assertEquals('A', Utils.toUpper('a'));
    assertEquals('Z', Utils.toUpper('z'));
  }

  @Test
  void runeToString_ascii() {
    assertEquals("A", Utils.runeToString('A'));
    assertEquals("0", Utils.runeToString('0'));
  }

  @Test
  void runeToString_supplementary() {
    // U+1F600 = 😀 (grinning face)
    assertEquals("\uD83D\uDE00", Utils.runeToString(0x1F600));
  }

  @Test
  void runeCount_ascii() {
    assertEquals(5, Utils.runeCount("hello"));
  }

  @Test
  void runeCount_supplementary() {
    // "A😀B" = 3 code points but 4 chars
    assertEquals(3, Utils.runeCount("A\uD83D\uDE00B"));
  }

  @Test
  void runeAt_ascii() {
    assertEquals('h', Utils.runeAt("hello", 0));
    assertEquals('o', Utils.runeAt("hello", 4));
  }

  @Test
  void runeAt_supplementary() {
    // "A😀B" — code point index 1 is the emoji
    String s = "A\uD83D\uDE00B";
    assertEquals('A', Utils.runeAt(s, 0));
    assertEquals(0x1F600, Utils.runeAt(s, 1));
    assertEquals('B', Utils.runeAt(s, 2));
  }
}
