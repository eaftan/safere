// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.lang.Character.UnicodeBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Unicode property lookups for JDK-compatible {@code \p{...}} syntax extensions.
 *
 * <p>This class provides support for:
 *
 * <ul>
 *   <li>Binary properties via the {@code Is} prefix (e.g., {@code \p{IsAlphabetic}})
 *   <li>Unicode block lookups via the {@code In} prefix or {@code block=}/{@code blk=} keywords
 *       (e.g., {@code \p{InBasicLatin}}, {@code \p{block=BasicLatin}})
 *   <li>Case-insensitive script and category lookups for the {@code Is} prefix, {@code script=}/
 *       {@code sc=}, and {@code general_category=}/{@code gc=} keywords
 * </ul>
 *
 * <p>Range tables are computed lazily from the running JVM's {@code Character} implementation, so
 * they always match the JVM's Unicode version.
 */
final class UnicodeProperties {
  private UnicodeProperties() {}

  // ---- Binary properties (lazy init) ----

  private static final class BinaryHolder {
    static final Map<String, int[][]> BINARY_PROPERTIES = buildAll();

    @SuppressWarnings("Convert2MethodRef")
    private static Map<String, int[][]> buildAll() {
      return Map.ofEntries(
          entry("Alphabetic", Character::isAlphabetic),
          entry("Ideographic", Character::isIdeographic),
          entry("Letter", Character::isLetter),
          entry("Lowercase", Character::isLowerCase),
          entry("Uppercase", Character::isUpperCase),
          entry("Titlecase", Character::isTitleCase),
          entry(
              "Punctuation",
              cp -> {
                int t = Character.getType(cp);
                return t == Character.CONNECTOR_PUNCTUATION
                    || t == Character.DASH_PUNCTUATION
                    || t == Character.START_PUNCTUATION
                    || t == Character.END_PUNCTUATION
                    || t == Character.OTHER_PUNCTUATION
                    || t == Character.INITIAL_QUOTE_PUNCTUATION
                    || t == Character.FINAL_QUOTE_PUNCTUATION;
              }),
          entry("Control", cp -> Character.getType(cp) == Character.CONTROL),
          // Unicode White_Space = Character.isWhitespace() ∪ Character.isSpaceChar().
          // isWhitespace() covers HT, LF, VT, FF, CR, SP, NEL, etc. but NOT NBSP.
          // isSpaceChar() covers Zs, Zl, Zp categories (includes NBSP).
          entry(
              "White_Space", cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp)),
          entry("Digit", Character::isDigit),
          entry(
              "Hex_Digit",
              cp ->
                  (cp >= '0' && cp <= '9')
                      || (cp >= 'A' && cp <= 'F')
                      || (cp >= 'a' && cp <= 'f')
                      || (cp >= 0xFF10 && cp <= 0xFF19)
                      || (cp >= 0xFF21 && cp <= 0xFF26)
                      || (cp >= 0xFF41 && cp <= 0xFF46)),
          entry("Join_Control", cp -> cp == 0x200C || cp == 0x200D),
          entry(
              "Noncharacter_Code_Point",
              cp ->
                  (cp >= 0xFDD0 && cp <= 0xFDEF)
                      || ((cp & 0xFFFE) == 0xFFFE && cp <= Character.MAX_CODE_POINT)),
          entry("Assigned", Character::isDefined),
          entry("Emoji", cp -> Character.isEmoji(cp)),
          entry("Emoji_Presentation", cp -> Character.isEmojiPresentation(cp)),
          entry("Emoji_Modifier", cp -> Character.isEmojiModifier(cp)),
          entry("Emoji_Modifier_Base", cp -> Character.isEmojiModifierBase(cp)),
          entry("Emoji_Component", cp -> Character.isEmojiComponent(cp)),
          entry("Extended_Pictographic", cp -> Character.isExtendedPictographic(cp)));
    }

    private static Map.Entry<String, int[][]> entry(String name, IntPredicate predicate) {
      return Map.entry(name, buildRanges(predicate));
    }
  }

  // ---- Unicode block ranges (lazy init) ----

  private static final class BlockHolder {
    static final Map<UnicodeBlock, int[][]> BLOCK_RANGES = buildBlockRanges();

    private static Map<UnicodeBlock, int[][]> buildBlockRanges() {
      // Scan all code points once and record the contiguous range for each block.
      Map<UnicodeBlock, int[]> ranges = new HashMap<>();
      UnicodeBlock currentBlock = null;
      int start = 0;

      for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
        UnicodeBlock block = UnicodeBlock.of(cp);
        if (!java.util.Objects.equals(block, currentBlock)) {
          if (currentBlock != null) {
            ranges.put(currentBlock, new int[] {start, cp - 1});
          }
          currentBlock = block;
          start = cp;
        }
      }
      if (currentBlock != null) {
        ranges.put(currentBlock, new int[] {start, Character.MAX_CODE_POINT});
      }

      Map<UnicodeBlock, int[][]> result = new HashMap<>();
      for (var entry : ranges.entrySet()) {
        result.put(entry.getKey(), new int[][] {entry.getValue()});
      }
      return result;
    }
  }

  // ---- Case-insensitive index for UNICODE_GROUPS (lazy init) ----

  private static final class NormalizedHolder {
    // Maps normalized (uppercase, underscores) key → original UNICODE_GROUPS key.
    static final Map<String, String> NORMALIZED_KEYS = buildNormalizedKeys();

    private static Map<String, String> buildNormalizedKeys() {
      Map<String, String> map = new HashMap<>();
      for (String key : UnicodeTables.UNICODE_GROUPS.keySet()) {
        map.put(normalize(key), key);
      }
      return map;
    }
  }

  // ---- Public lookup methods ----

  /**
   * Looks up a binary Unicode property by name (e.g., "Alphabetic", "Lowercase").
   *
   * @return the range table, or {@code null} if not a recognized binary property
   */
  static int[][] lookupBinaryProperty(String name) {
    return BinaryHolder.BINARY_PROPERTIES.get(name);
  }

  /**
   * Looks up a Unicode block by name (e.g., "BasicLatin", "GreekandCoptic"). The name is resolved
   * via {@link Character.UnicodeBlock#forName(String)}, which is case-insensitive and tolerates
   * spaces, hyphens, and underscores.
   *
   * @return the range table, or {@code null} if not a recognized block
   */
  static int[][] lookupBlock(String name) {
    try {
      UnicodeBlock block = UnicodeBlock.forName(name);
      return BlockHolder.BLOCK_RANGES.get(block);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * Case-insensitive lookup of a script or category name in {@link
   * UnicodeTables#UNICODE_GROUPS}. Spaces and hyphens are normalized to underscores.
   *
   * @return the range table, or {@code null} if not found
   */
  static int[][] lookupScriptOrCategory(String name) {
    // Try exact match first (fast path for common cases like "Latin", "Lu").
    int[][] table = UnicodeTables.UNICODE_GROUPS.get(name);
    if (table != null) {
      return table;
    }
    // Fall back to case-insensitive lookup.
    String canonicalKey = NormalizedHolder.NORMALIZED_KEYS.get(normalize(name));
    if (canonicalKey != null) {
      return UnicodeTables.UNICODE_GROUPS.get(canonicalKey);
    }
    return null;
  }

  /**
   * Normalizes a property name for case-insensitive comparison: uppercases and replaces spaces and
   * hyphens with underscores.
   */
  private static String normalize(String name) {
    return name.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
  }

  /**
   * Builds a sorted, non-overlapping {@code int[][]} of {@code {lo, hi}} inclusive ranges for all
   * Unicode code points matching the given predicate.
   */
  private static int[][] buildRanges(IntPredicate predicate) {
    List<int[]> ranges = new ArrayList<>();
    int lo = -1;
    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      if (predicate.test(cp)) {
        if (lo < 0) {
          lo = cp;
        }
      } else {
        if (lo >= 0) {
          ranges.add(new int[] {lo, cp - 1});
          lo = -1;
        }
      }
    }
    if (lo >= 0) {
      ranges.add(new int[] {lo, Character.MAX_CODE_POINT});
    }
    return ranges.toArray(new int[0][]);
  }
}
