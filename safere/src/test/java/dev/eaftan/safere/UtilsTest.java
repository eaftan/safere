// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link Utils}. */
class UtilsTest {

  @Test
  void isValidRune_validCodePoints() {
    assertThat(Utils.isValidRune(0)).isTrue(); // NUL
    assertThat(Utils.isValidRune('A')).isTrue();
    assertThat(Utils.isValidRune(0x7F)).isTrue(); // DEL
    assertThat(Utils.isValidRune(0x80)).isTrue(); // First non-ASCII
    assertThat(Utils.isValidRune(0xFFFF)).isTrue(); // Last BMP
    assertThat(Utils.isValidRune(0x10000)).isTrue(); // First supplementary
    assertThat(Utils.isValidRune(0x10FFFF)).isTrue(); // Max code point
  }

  @Test
  void isValidRune_invalidCodePoints() {
    assertThat(Utils.isValidRune(-1)).isFalse();
    assertThat(Utils.isValidRune(0x110000)).isFalse(); // Beyond max
    assertThat(Utils.isValidRune(0xD800)).isFalse(); // Surrogate start
    assertThat(Utils.isValidRune(0xDBFF)).isFalse(); // High surrogate end
    assertThat(Utils.isValidRune(0xDC00)).isFalse(); // Low surrogate start
    assertThat(Utils.isValidRune(0xDFFF)).isFalse(); // Surrogate end
  }

  @ParameterizedTest
  @ValueSource(ints = {'0', '5', '9', 'a', 'z', 'A', 'Z'})
  void isAlnum_trueForAlnumChars(int r) {
    assertThat(Utils.isAlnum(r)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(ints = {'!', ' ', '@', '[', '{', 0x00, 0xFF})
  void isAlnum_falseForNonAlnumChars(int r) {
    assertThat(Utils.isAlnum(r)).isFalse();
  }

  @Test
  void isWordChar_includesUnderscore() {
    assertThat(Utils.isWordChar('_')).isTrue();
    assertThat(Utils.isWordChar('a')).isTrue();
    assertThat(Utils.isWordChar('0')).isTrue();
    assertThat(Utils.isWordChar('-')).isFalse();
    assertThat(Utils.isWordChar(' ')).isFalse();
  }

  @Test
  void unhex_validDigits() {
    assertThat(Utils.unhex('0')).isEqualTo(0);
    assertThat(Utils.unhex('9')).isEqualTo(9);
    assertThat(Utils.unhex('A')).isEqualTo(10);
    assertThat(Utils.unhex('F')).isEqualTo(15);
    assertThat(Utils.unhex('a')).isEqualTo(10);
    assertThat(Utils.unhex('f')).isEqualTo(15);
  }

  @Test
  void unhex_invalidChars() {
    assertThat(Utils.unhex('G')).isEqualTo(-1);
    assertThat(Utils.unhex('g')).isEqualTo(-1);
    assertThat(Utils.unhex(' ')).isEqualTo(-1);
    assertThat(Utils.unhex('/')).isEqualTo(-1);
    assertThat(Utils.unhex(':')).isEqualTo(-1);
  }

  @Test
  void toLower_convertsUppercase() {
    assertThat(Utils.toLower('A')).isEqualTo('a');
    assertThat(Utils.toLower('Z')).isEqualTo('z');
  }

  @Test
  void toLower_leavesOthersUnchanged() {
    assertThat(Utils.toLower('a')).isEqualTo('a');
    assertThat(Utils.toLower('0')).isEqualTo('0');
    assertThat(Utils.toLower('!')).isEqualTo('!');
  }

  @Test
  void toUpper_convertsLowercase() {
    assertThat(Utils.toUpper('a')).isEqualTo('A');
    assertThat(Utils.toUpper('z')).isEqualTo('Z');
  }

  @Test
  void runeToString_ascii() {
    assertThat(Utils.runeToString('A')).isEqualTo("A");
    assertThat(Utils.runeToString('0')).isEqualTo("0");
  }

  @Test
  void runeToString_supplementary() {
    // U+1F600 = 😀 (grinning face)
    assertThat(Utils.runeToString(0x1F600)).isEqualTo("\uD83D\uDE00");
  }

  @Test
  void runeCount_ascii() {
    assertThat(Utils.runeCount("hello")).isEqualTo(5);
  }

  @Test
  void runeCount_supplementary() {
    // "A😀B" = 3 code points but 4 chars
    assertThat(Utils.runeCount("A\uD83D\uDE00B")).isEqualTo(3);
  }

  @Test
  void runeAt_ascii() {
    assertThat(Utils.runeAt("hello", 0)).isEqualTo('h');
    assertThat(Utils.runeAt("hello", 4)).isEqualTo('o');
  }

  @Test
  void runeAt_supplementary() {
    // "A😀B" — code point index 1 is the emoji
    String s = "A\uD83D\uDE00B";
    assertThat(Utils.runeAt(s, 0)).isEqualTo('A');
    assertThat(Utils.runeAt(s, 1)).isEqualTo(0x1F600);
    assertThat(Utils.runeAt(s, 2)).isEqualTo('B');
  }
}
