// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * Empirical character and byte rarity oracle for regex literal and start acceleration.
 *
 * <p>Assigns empirical frequency ranks to ASCII bytes to optimize SIMD anchor selection and literal
 * selectivity scoring. Higher rank indicates rarer characters (0 = most common, e.g. space; 127 =
 * rarest, e.g. non-ASCII or unprintable control characters).
 *
 * <p>Two distinct frequency distributions are calibrated:
 *
 * <ul>
 *   <li><b>Exact-case rarity ({@link #exactByteRarity(int)}):</b> Calibrated against corpus
 *       distributions across source code (Java, C, Python, Go), JSON payloads, log streams, and
 *       English prose. In exact matching, lowercase letters (e.g. {@code 'e'}, {@code 't'}, {@code
 *       'a'}) appear at substantially higher frequencies than uppercase letters (e.g. {@code 'E'},
 *       {@code 'T'}, {@code 'A'}). Anchoring on uppercase letters in camelCase or ALL_CAPS tokens
 *       (such as {@code "AbstractBeanFactory"} or {@code "HTTP_REQUEST"}) drastically reduces
 *       false-positive candidate checks.
 *   <li><b>Case-folded rarity ({@link #caseFoldedByteRarity(int)}):</b> Calibrated for
 *       case-insensitive search (such as {@link Matcher#indexOfIgnoreCase}), where a candidate
 *       broadcast lane must match both lowercase and uppercase variations ({@code P(c) +
 *       P(swapCase(c))}). In this mode, {@code 'e'} and {@code 'E'} share the same combined
 *       frequency rank.
 * </ul>
 */
final class RarityOracle {
  private static final byte[] EXACT_ASCII_RARITY = new byte[128];
  private static final byte[] CASE_FOLDED_ASCII_RARITY = new byte[128];

  static {
    for (int i = 0; i < 128; i++) {
      EXACT_ASCII_RARITY[i] = 100;
      CASE_FOLDED_ASCII_RARITY[i] = 100;
    }
    for (int i = 0; i < 32; i++) {
      EXACT_ASCII_RARITY[i] = 120;
      CASE_FOLDED_ASCII_RARITY[i] = 120;
    }
    EXACT_ASCII_RARITY[127] = 120;
    CASE_FOLDED_ASCII_RARITY[127] = 120;

    EXACT_ASCII_RARITY[' '] = 0;
    EXACT_ASCII_RARITY['\n'] = 3;
    EXACT_ASCII_RARITY['\t'] = 10;
    EXACT_ASCII_RARITY['\r'] = 12;

    CASE_FOLDED_ASCII_RARITY[' '] = 0;
    CASE_FOLDED_ASCII_RARITY['\n'] = 10;
    CASE_FOLDED_ASCII_RARITY['\t'] = 30;
    CASE_FOLDED_ASCII_RARITY['\r'] = 35;

    EXACT_ASCII_RARITY['"'] = 8;
    EXACT_ASCII_RARITY[':'] = 10;
    EXACT_ASCII_RARITY[','] = 10;
    EXACT_ASCII_RARITY['.'] = 12;
    EXACT_ASCII_RARITY['/'] = 14;
    EXACT_ASCII_RARITY['-'] = 15;
    EXACT_ASCII_RARITY['_'] = 15;
    EXACT_ASCII_RARITY['='] = 16;
    EXACT_ASCII_RARITY[';'] = 18;
    EXACT_ASCII_RARITY['('] = 20;
    EXACT_ASCII_RARITY[')'] = 20;
    EXACT_ASCII_RARITY['{'] = 20;
    EXACT_ASCII_RARITY['}'] = 20;
    EXACT_ASCII_RARITY['['] = 20;
    EXACT_ASCII_RARITY[']'] = 20;
    EXACT_ASCII_RARITY['<'] = 62;
    EXACT_ASCII_RARITY['>'] = 62;
    EXACT_ASCII_RARITY['?'] = 65;
    EXACT_ASCII_RARITY['!'] = 65;
    EXACT_ASCII_RARITY['@'] = 70;
    EXACT_ASCII_RARITY['#'] = 70;
    EXACT_ASCII_RARITY['$'] = 72;
    EXACT_ASCII_RARITY['%'] = 72;
    EXACT_ASCII_RARITY['+'] = 68;
    EXACT_ASCII_RARITY['&'] = 75;
    EXACT_ASCII_RARITY['*'] = 75;
    EXACT_ASCII_RARITY['|'] = 78;
    EXACT_ASCII_RARITY['^'] = 80;
    EXACT_ASCII_RARITY['~'] = 85;
    EXACT_ASCII_RARITY['`'] = 85;
    EXACT_ASCII_RARITY['\\'] = 90;

    for (char d = '0'; d <= '9'; d++) {
      EXACT_ASCII_RARITY[d] = (byte) (30 + (d - '0'));
      CASE_FOLDED_ASCII_RARITY[d] = (byte) (30 + (d - '0'));
    }

    setExactLetter('e', 5, 'E', 48);
    setExactLetter('t', 6, 'T', 50);
    setExactLetter('a', 7, 'A', 52);
    setExactLetter('o', 8, 'O', 54);
    setExactLetter('i', 9, 'I', 56);
    setExactLetter('n', 10, 'N', 58);
    setExactLetter('s', 11, 'S', 60);
    setExactLetter('r', 12, 'R', 62);
    setExactLetter('h', 14, 'H', 64);
    setExactLetter('l', 16, 'L', 66);
    setExactLetter('d', 18, 'D', 68);
    setExactLetter('c', 20, 'C', 70);
    setExactLetter('u', 22, 'U', 72);
    setExactLetter('m', 24, 'M', 74);
    setExactLetter('f', 26, 'F', 76);
    setExactLetter('p', 28, 'P', 78);
    setExactLetter('g', 30, 'G', 80);
    setExactLetter('w', 32, 'W', 82);
    setExactLetter('y', 34, 'Y', 84);
    setExactLetter('b', 36, 'B', 86);
    setExactLetter('v', 40, 'V', 88);
    setExactLetter('k', 44, 'K', 90);
    setExactLetter('x', 55, 'X', 94);
    setExactLetter('j', 60, 'J', 96);
    setExactLetter('q', 70, 'Q', 98);
    setExactLetter('z', 75, 'Z', 100);

    CASE_FOLDED_ASCII_RARITY['"'] = 12;
    CASE_FOLDED_ASCII_RARITY[':'] = 14;
    CASE_FOLDED_ASCII_RARITY[','] = 14;
    CASE_FOLDED_ASCII_RARITY['.'] = 15;
    CASE_FOLDED_ASCII_RARITY['/'] = 16;
    CASE_FOLDED_ASCII_RARITY['-'] = 18;
    CASE_FOLDED_ASCII_RARITY['_'] = 18;
    CASE_FOLDED_ASCII_RARITY['='] = 20;
    CASE_FOLDED_ASCII_RARITY[';'] = 20;
    CASE_FOLDED_ASCII_RARITY['('] = 24;
    CASE_FOLDED_ASCII_RARITY[')'] = 24;
    CASE_FOLDED_ASCII_RARITY['{'] = 24;
    CASE_FOLDED_ASCII_RARITY['}'] = 24;
    CASE_FOLDED_ASCII_RARITY['['] = 24;
    CASE_FOLDED_ASCII_RARITY[']'] = 24;

    setCaseFoldedLetter('e', 6);
    setCaseFoldedLetter('t', 8);
    setCaseFoldedLetter('a', 10);
    setCaseFoldedLetter('o', 11);
    setCaseFoldedLetter('i', 12);
    setCaseFoldedLetter('n', 13);
    setCaseFoldedLetter('s', 14);
    setCaseFoldedLetter('r', 15);
    setCaseFoldedLetter('h', 16);
    setCaseFoldedLetter('l', 18);
    setCaseFoldedLetter('d', 20);
    setCaseFoldedLetter('c', 22);
    setCaseFoldedLetter('u', 24);
    setCaseFoldedLetter('m', 26);
    setCaseFoldedLetter('f', 28);
    setCaseFoldedLetter('p', 30);
    setCaseFoldedLetter('g', 32);
    setCaseFoldedLetter('w', 34);
    setCaseFoldedLetter('y', 36);
    setCaseFoldedLetter('b', 38);
    setCaseFoldedLetter('v', 42);
    setCaseFoldedLetter('k', 46);
    setCaseFoldedLetter('x', 65);
    setCaseFoldedLetter('j', 70);
    setCaseFoldedLetter('q', 80);
    setCaseFoldedLetter('z', 85);
  }

  private static void setExactLetter(char lc, int lcRank, char uc, int ucRank) {
    EXACT_ASCII_RARITY[lc] = (byte) lcRank;
    EXACT_ASCII_RARITY[uc] = (byte) ucRank;
  }

  private static void setCaseFoldedLetter(char c, int rank) {
    CASE_FOLDED_ASCII_RARITY[c] = (byte) rank;
    CASE_FOLDED_ASCII_RARITY[Character.toUpperCase(c)] = (byte) rank;
  }

  /**
   * Returns the exact byte frequency rank for an ASCII character (higher = rarer).
   *
   * <p>Distinguishes uppercase and lowercase frequencies for exact matching.
   */
  static int exactByteRarity(int c) {
    return c >= 0 && c < 128 ? (EXACT_ASCII_RARITY[c] & 0xFF) : 127;
  }

  /**
   * Returns the case-folded byte frequency rank for an ASCII character (higher = rarer).
   *
   * <p>Assigns equal rank to {@code 'a'} and {@code 'A'} based on their combined frequency.
   */
  static int caseFoldedByteRarity(int c) {
    return c >= 0 && c < 128 ? (CASE_FOLDED_ASCII_RARITY[c] & 0xFF) : 127;
  }

  /**
   * Returns the byte frequency rank for an ASCII character (higher = rarer).
   *
   * @param c character code point
   * @param caseFolded {@code true} to use case-folded combined letter ranks
   */
  static int byteRarity(int c, boolean caseFolded) {
    return caseFolded ? caseFoldedByteRarity(c) : exactByteRarity(c);
  }

  /** Returns the exact-case byte frequency rank for an ASCII character (higher = rarer). */
  static int byteRarity(int c) {
    return exactByteRarity(c);
  }

  /**
   * Returns the offset of the rarest ASCII character in the prefix (up to {@code prefixLen}). If
   * all characters have identical rank or length is 0, returns 0.
   */
  static int rarestAsciiOffset(CharSequence prefix, int prefixLen) {
    return rarestAsciiOffset(prefix, prefixLen, false);
  }

  /**
   * Returns the offset of the rarest ASCII character in the prefix (up to {@code prefixLen}),
   * optionally applying case-folded frequency ratings.
   *
   * @param prefix the character sequence to scan
   * @param prefixLen length of prefix to evaluate
   * @param caseFolded {@code true} for case-insensitive matching
   * @return 0-based offset of rarest character
   */
  static int rarestAsciiOffset(CharSequence prefix, int prefixLen, boolean caseFolded) {
    int bestOffset = 0;
    int maxRank = -1;
    for (int i = 0; i < prefixLen; i++) {
      char c = prefix.charAt(i);
      int rank = byteRarity(c, caseFolded);
      if (rank > maxRank) {
        maxRank = rank;
        bestOffset = i;
      }
    }
    return bestOffset;
  }

  /**
   * Computes a selectivity score for a literal string. Combines string length with individual
   * character rarity.
   */
  static int literalSelectivityScore(CharSequence s) {
    return literalSelectivityScore(s, false);
  }

  /**
   * Computes a selectivity score for a literal string, optionally using case-folded ratings.
   *
   * @param s the literal candidate sequence
   * @param caseFolded {@code true} if matching will be case-insensitive
   * @return higher score indicates a more selective literal
   */
  static int literalSelectivityScore(CharSequence s, boolean caseFolded) {
    if (s == null || s.isEmpty()) {
      return 0;
    }
    int score = 0;
    int maxCharRarity = 0;
    for (int i = 0; i < s.length(); i++) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      int r = byteRarity(s.charAt(i), caseFolded);
      score += r + 1;
      if (r > maxCharRarity) {
        maxCharRarity = r;
      }
    }
    return score + maxCharRarity;
  }

  private RarityOracle() {}
}
