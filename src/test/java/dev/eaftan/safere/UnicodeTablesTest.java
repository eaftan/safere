// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for {@link UnicodeTables}. */
class UnicodeTablesTest {

  // --- Perl groups ---

  @Test
  void perlGroups_hasThreeEntries() {
    assertEquals(3, UnicodeTables.PERL_GROUPS.size());
  }

  @Test
  void perlDigit_matchesZeroToNine() {
    int[][] ranges = UnicodeTables.PERL_GROUPS.get("\\d");
    assertNotNull(ranges);
    assertEquals(1, ranges.length);
    assertArrayEquals(new int[] {0x30, 0x39}, ranges[0]);
  }

  @Test
  void perlSpace_matchesWhitespace() {
    int[][] ranges = UnicodeTables.PERL_GROUPS.get("\\s");
    assertNotNull(ranges);
    assertTrue(ranges.length >= 2);
    // Should include tab (0x09) and space (0x20)
    assertTrue(containsCodePoint(ranges, 0x09));
    assertTrue(containsCodePoint(ranges, 0x20));
  }

  @Test
  void perlWord_matchesWordChars() {
    int[][] ranges = UnicodeTables.PERL_GROUPS.get("\\w");
    assertNotNull(ranges);
    assertTrue(containsCodePoint(ranges, '0'));
    assertTrue(containsCodePoint(ranges, '9'));
    assertTrue(containsCodePoint(ranges, 'A'));
    assertTrue(containsCodePoint(ranges, 'Z'));
    assertTrue(containsCodePoint(ranges, '_'));
    assertTrue(containsCodePoint(ranges, 'a'));
    assertTrue(containsCodePoint(ranges, 'z'));
  }

  // --- POSIX groups ---

  @Test
  void posixGroups_hasFourteenEntries() {
    assertEquals(14, UnicodeTables.POSIX_GROUPS.size());
  }

  @Test
  void posixDigit_matchesZeroToNine() {
    int[][] ranges = UnicodeTables.POSIX_GROUPS.get("[:digit:]");
    assertNotNull(ranges);
    assertEquals(1, ranges.length);
    assertArrayEquals(new int[] {0x30, 0x39}, ranges[0]);
  }

  @Test
  void posixAscii_matchesFullRange() {
    int[][] ranges = UnicodeTables.POSIX_GROUPS.get("[:ascii:]");
    assertNotNull(ranges);
    assertEquals(1, ranges.length);
    assertArrayEquals(new int[] {0x00, 0x7F}, ranges[0]);
  }

  @Test
  void posixUpper_matchesUppercase() {
    int[][] ranges = UnicodeTables.POSIX_GROUPS.get("[:upper:]");
    assertNotNull(ranges);
    assertEquals(1, ranges.length);
    assertArrayEquals(new int[] {0x41, 0x5A}, ranges[0]);
  }

  // --- Case folding ---

  @Test
  void caseFold_hasExpectedSize() {
    assertEquals(372, UnicodeTables.CASE_FOLD.length);
  }

  @Test
  void caseFold_firstEntryIsUppercaseAscii() {
    // A-Z folds to a-z by adding 32
    assertArrayEquals(new int[] {65, 90, 32}, UnicodeTables.CASE_FOLD[0]);
  }

  @Test
  void caseFold_entriesHaveThreeElements() {
    for (int[] entry : UnicodeTables.CASE_FOLD) {
      assertEquals(3, entry.length, "CaseFold entry should have {lo, hi, delta}");
    }
  }

  @Test
  void caseFold_rangesAreOrdered() {
    for (int i = 1; i < UnicodeTables.CASE_FOLD.length; i++) {
      assertTrue(
          UnicodeTables.CASE_FOLD[i][0] > UnicodeTables.CASE_FOLD[i - 1][1],
          "CaseFold ranges should be non-overlapping and ordered: entry "
              + i
              + " lo="
              + UnicodeTables.CASE_FOLD[i][0]
              + " <= prev hi="
              + UnicodeTables.CASE_FOLD[i - 1][1]);
    }
  }

  // --- To-lower ---

  @Test
  void toLower_hasExpectedSize() {
    assertEquals(208, UnicodeTables.TO_LOWER.length);
  }

  @Test
  void toLower_firstEntryIsUppercaseAscii() {
    // A-Z maps to a-z by adding 32
    assertArrayEquals(new int[] {65, 90, 32}, UnicodeTables.TO_LOWER[0]);
  }

  // --- Unicode groups ---

  @Test
  void unicodeGroups_hasExpectedSize() {
    assertEquals(199, UnicodeTables.UNICODE_GROUPS.size());
  }

  @Test
  void unicodeGroups_containsMajorScripts() {
    assertNotNull(UnicodeTables.UNICODE_GROUPS.get("Arabic"));
    assertNotNull(UnicodeTables.UNICODE_GROUPS.get("Latin"));
    assertNotNull(UnicodeTables.UNICODE_GROUPS.get("Greek"));
    assertNotNull(UnicodeTables.UNICODE_GROUPS.get("Han"));
    assertNotNull(UnicodeTables.UNICODE_GROUPS.get("Cyrillic"));
    assertNotNull(UnicodeTables.UNICODE_GROUPS.get("Hiragana"));
    assertNotNull(UnicodeTables.UNICODE_GROUPS.get("Katakana"));
  }

  @Test
  void unicodeGroups_containsGeneralCategories() {
    // Major categories
    assertNotNull(UnicodeTables.UNICODE_GROUPS.get("L")); // Letter
    assertNotNull(UnicodeTables.UNICODE_GROUPS.get("N")); // Number
    assertNotNull(UnicodeTables.UNICODE_GROUPS.get("P")); // Punctuation
    assertNotNull(UnicodeTables.UNICODE_GROUPS.get("S")); // Symbol
    assertNotNull(UnicodeTables.UNICODE_GROUPS.get("Z")); // Separator
    assertNotNull(UnicodeTables.UNICODE_GROUPS.get("C")); // Other

    // Subcategories
    assertNotNull(UnicodeTables.UNICODE_GROUPS.get("Lu")); // Uppercase Letter
    assertNotNull(UnicodeTables.UNICODE_GROUPS.get("Ll")); // Lowercase Letter
    assertNotNull(UnicodeTables.UNICODE_GROUPS.get("Nd")); // Decimal Digit
  }

  @Test
  void unicodeGroups_latinContainsAsciiLetters() {
    int[][] latin = UnicodeTables.UNICODE_GROUPS.get("Latin");
    assertNotNull(latin);
    assertTrue(containsCodePoint(latin, 'A'));
    assertTrue(containsCodePoint(latin, 'Z'));
    assertTrue(containsCodePoint(latin, 'a'));
    assertTrue(containsCodePoint(latin, 'z'));
  }

  @Test
  void unicodeGroups_rangesAreValid() {
    for (var entry : UnicodeTables.UNICODE_GROUPS.entrySet()) {
      for (int[] range : entry.getValue()) {
        assertEquals(2, range.length, "Range in " + entry.getKey() + " should have {lo, hi}");
        assertTrue(
            range[0] <= range[1],
            "Range in " + entry.getKey() + ": lo=" + range[0] + " > hi=" + range[1]);
        assertTrue(
            range[0] >= 0,
            "Range in " + entry.getKey() + ": lo=" + range[0] + " is negative");
        assertTrue(
            range[1] <= 0x10FFFF,
            "Range in " + entry.getKey() + ": hi=" + range[1] + " exceeds max code point");
      }
    }
  }

  // --- Sentinel constants ---

  @Test
  void sentinelValues() {
    assertEquals(1, UnicodeTables.EVEN_ODD);
    assertEquals(-1, UnicodeTables.ODD_EVEN);
    assertEquals(1 << 30, UnicodeTables.EVEN_ODD_SKIP);
    assertEquals((1 << 30) + 1, UnicodeTables.ODD_EVEN_SKIP);
  }

  // --- Helper ---

  private static boolean containsCodePoint(int[][] ranges, int cp) {
    for (int[] range : ranges) {
      if (cp >= range[0] && cp <= range[1]) {
        return true;
      }
    }
    return false;
  }
}
