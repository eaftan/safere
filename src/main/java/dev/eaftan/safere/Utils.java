// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

/** Utility methods for Unicode code point handling and character classification. */
public final class Utils {

  /** The maximum valid Unicode code point. */
  public static final int MAX_RUNE = 0x10FFFF;

  /** The minimum valid Unicode code point. */
  public static final int MIN_RUNE = 0;

  /** Unicode replacement character, used for invalid code points. */
  public static final int REPLACEMENT_CHAR = 0xFFFD;

  /** The maximum value of a Basic Multilingual Plane (BMP) code point. */
  public static final int MAX_BMP = 0xFFFF;

  /** Start of the surrogate range (not valid code points). */
  public static final int MIN_SURROGATE = 0xD800;

  /** End of the surrogate range (not valid code points). */
  public static final int MAX_SURROGATE = 0xDFFF;

  private Utils() {} // Non-instantiable.

  /** Returns true if {@code r} is a valid Unicode code point. */
  public static boolean isValidRune(int r) {
    return r >= MIN_RUNE && r <= MAX_RUNE && (r < MIN_SURROGATE || r > MAX_SURROGATE);
  }

  /** Returns true if the code point is an ASCII letter or digit. */
  public static boolean isAlnum(int r) {
    return (r >= '0' && r <= '9') || (r >= 'A' && r <= 'Z') || (r >= 'a' && r <= 'z');
  }

  /** Returns true if the code point is an ASCII letter. */
  public static boolean isAlpha(int r) {
    return (r >= 'A' && r <= 'Z') || (r >= 'a' && r <= 'z');
  }

  /** Returns true if the code point is an ASCII digit. */
  public static boolean isDigit(int r) {
    return r >= '0' && r <= '9';
  }

  /** Returns true if the code point is an ASCII hex digit. */
  public static boolean isHexDigit(int r) {
    return (r >= '0' && r <= '9') || (r >= 'A' && r <= 'F') || (r >= 'a' && r <= 'f');
  }

  /** Returns true if the code point is an ASCII word character (letter, digit, or underscore). */
  public static boolean isWordChar(int r) {
    return isAlnum(r) || r == '_';
  }

  /** Returns true if the code point is an ASCII uppercase letter. */
  public static boolean isUpper(int r) {
    return r >= 'A' && r <= 'Z';
  }

  /** Returns true if the code point is an ASCII lowercase letter. */
  public static boolean isLower(int r) {
    return r >= 'a' && r <= 'z';
  }

  /** Converts an ASCII uppercase letter to lowercase. Returns the code point unchanged if not. */
  public static int toLower(int r) {
    if (isUpper(r)) {
      return r + ('a' - 'A');
    }
    return r;
  }

  /** Converts an ASCII lowercase letter to uppercase. Returns the code point unchanged if not. */
  public static int toUpper(int r) {
    if (isLower(r)) {
      return r - ('a' - 'A');
    }
    return r;
  }

  /**
   * Returns the value of a hex digit, or -1 if not a hex digit.
   *
   * @param r a code point
   * @return 0-15 for valid hex digits, -1 otherwise
   */
  public static int unhex(int r) {
    if (r >= '0' && r <= '9') {
      return r - '0';
    }
    if (r >= 'A' && r <= 'F') {
      return r - 'A' + 10;
    }
    if (r >= 'a' && r <= 'f') {
      return r - 'a' + 10;
    }
    return -1;
  }

  /**
   * Converts a code point to a Java String, handling supplementary characters correctly.
   *
   * @param r a Unicode code point
   * @return a String containing the character(s) for that code point
   */
  public static String runeToString(int r) {
    return new String(Character.toChars(r));
  }

  /**
   * Returns the number of Unicode code points in the string. Unlike {@link String#length()}, this
   * correctly counts supplementary (non-BMP) characters as one.
   */
  public static int runeCount(String s) {
    return s.codePointCount(0, s.length());
  }

  /**
   * Returns the code point at a given code-point index (not char index) in a string.
   *
   * @param s the string
   * @param index the code point index
   * @return the code point at that index
   * @throws IndexOutOfBoundsException if index is out of bounds
   */
  public static int runeAt(String s, int index) {
    int charIndex = s.offsetByCodePoints(0, index);
    return s.codePointAt(charIndex);
  }
}
