// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link UnicodeTables}. */
class UnicodeTablesTest {

  // --- Perl groups ---

  @Test
  void perlGroups_hasThreeEntries() {
    assertThat(UnicodeTables.PERL_GROUPS.size()).isEqualTo(3);
  }

  @Test
  void perlDigit_matchesZeroToNine() {
    int[][] ranges = UnicodeTables.PERL_GROUPS.get("\\d");
    assertThat(ranges).isNotNull();
    assertThat(ranges.length).isEqualTo(1);
    assertThat(ranges[0]).isEqualTo(new int[] {0x30, 0x39});
  }

  @Test
  void perlSpace_matchesWhitespace() {
    int[][] ranges = UnicodeTables.PERL_GROUPS.get("\\s");
    assertThat(ranges).isNotNull();
    assertThat(ranges.length >= 2).isTrue();
    // Should include tab (0x09) and space (0x20)
    assertThat(containsCodePoint(ranges, 0x09)).isTrue();
    assertThat(containsCodePoint(ranges, 0x20)).isTrue();
  }

  @Test
  void perlWord_matchesWordChars() {
    int[][] ranges = UnicodeTables.PERL_GROUPS.get("\\w");
    assertThat(ranges).isNotNull();
    assertThat(containsCodePoint(ranges, '0')).isTrue();
    assertThat(containsCodePoint(ranges, '9')).isTrue();
    assertThat(containsCodePoint(ranges, 'A')).isTrue();
    assertThat(containsCodePoint(ranges, 'Z')).isTrue();
    assertThat(containsCodePoint(ranges, '_')).isTrue();
    assertThat(containsCodePoint(ranges, 'a')).isTrue();
    assertThat(containsCodePoint(ranges, 'z')).isTrue();
  }

  // --- POSIX groups ---

  @Test
  void posixGroups_hasFourteenEntries() {
    assertThat(UnicodeTables.POSIX_GROUPS.size()).isEqualTo(14);
  }

  @Test
  void posixDigit_matchesZeroToNine() {
    int[][] ranges = UnicodeTables.POSIX_GROUPS.get("[:digit:]");
    assertThat(ranges).isNotNull();
    assertThat(ranges.length).isEqualTo(1);
    assertThat(ranges[0]).isEqualTo(new int[] {0x30, 0x39});
  }

  @Test
  void posixAscii_matchesFullRange() {
    int[][] ranges = UnicodeTables.POSIX_GROUPS.get("[:ascii:]");
    assertThat(ranges).isNotNull();
    assertThat(ranges.length).isEqualTo(1);
    assertThat(ranges[0]).isEqualTo(new int[] {0x00, 0x7F});
  }

  @Test
  void posixUpper_matchesUppercase() {
    int[][] ranges = UnicodeTables.POSIX_GROUPS.get("[:upper:]");
    assertThat(ranges).isNotNull();
    assertThat(ranges.length).isEqualTo(1);
    assertThat(ranges[0]).isEqualTo(new int[] {0x41, 0x5A});
  }

  // --- Case folding ---

  @Test
  void caseFold_hasExpectedSize() {
    assertThat(UnicodeTables.CASE_FOLD.length).isEqualTo(372);
  }

  @Test
  void caseFold_firstEntryIsUppercaseAscii() {
    // A-Z folds to a-z by adding 32
    assertThat(UnicodeTables.CASE_FOLD[0]).isEqualTo(new int[] {65, 90, 32});
  }

  @Test
  void caseFold_entriesHaveThreeElements() {
    for (int[] entry : UnicodeTables.CASE_FOLD) {
      assertThat(entry.length).as("CaseFold entry should have {lo, hi, delta}").isEqualTo(3);
    }
  }

  @Test
  void caseFold_rangesAreOrdered() {
    for (int i = 1; i < UnicodeTables.CASE_FOLD.length; i++) {
      assertThat(UnicodeTables.CASE_FOLD[i][0] > UnicodeTables.CASE_FOLD[i - 1][1])
          .withFailMessage(
              "CaseFold ranges should be non-overlapping and ordered: entry "
                  + i
                  + " lo="
                  + UnicodeTables.CASE_FOLD[i][0]
                  + " <= prev hi="
                  + UnicodeTables.CASE_FOLD[i - 1][1])
          .isTrue();
    }
  }

  // --- To-lower ---

  @Test
  void toLower_hasExpectedSize() {
    assertThat(UnicodeTables.TO_LOWER.length).isEqualTo(208);
  }

  @Test
  void toLower_firstEntryIsUppercaseAscii() {
    // A-Z maps to a-z by adding 32
    assertThat(UnicodeTables.TO_LOWER[0]).isEqualTo(new int[] {65, 90, 32});
  }

  // --- Unicode groups ---

  @Test
  void unicodeGroups_hasExpectedSize() {
    assertThat(UnicodeTables.UNICODE_GROUPS.size()).isEqualTo(199);
  }

  @Test
  void unicodeGroups_containsMajorScripts() {
    assertThat(UnicodeTables.UNICODE_GROUPS.get("Arabic")).isNotNull();
    assertThat(UnicodeTables.UNICODE_GROUPS.get("Latin")).isNotNull();
    assertThat(UnicodeTables.UNICODE_GROUPS.get("Greek")).isNotNull();
    assertThat(UnicodeTables.UNICODE_GROUPS.get("Han")).isNotNull();
    assertThat(UnicodeTables.UNICODE_GROUPS.get("Cyrillic")).isNotNull();
    assertThat(UnicodeTables.UNICODE_GROUPS.get("Hiragana")).isNotNull();
    assertThat(UnicodeTables.UNICODE_GROUPS.get("Katakana")).isNotNull();
  }

  @Test
  void unicodeGroups_containsGeneralCategories() {
    // Major categories
    assertThat(UnicodeTables.UNICODE_GROUPS.get("L")).isNotNull(); // Letter
    assertThat(UnicodeTables.UNICODE_GROUPS.get("N")).isNotNull(); // Number
    assertThat(UnicodeTables.UNICODE_GROUPS.get("P")).isNotNull(); // Punctuation
    assertThat(UnicodeTables.UNICODE_GROUPS.get("S")).isNotNull(); // Symbol
    assertThat(UnicodeTables.UNICODE_GROUPS.get("Z")).isNotNull(); // Separator
    assertThat(UnicodeTables.UNICODE_GROUPS.get("C")).isNotNull(); // Other

    // Subcategories
    assertThat(UnicodeTables.UNICODE_GROUPS.get("Lu")).isNotNull(); // Uppercase Letter
    assertThat(UnicodeTables.UNICODE_GROUPS.get("Ll")).isNotNull(); // Lowercase Letter
    assertThat(UnicodeTables.UNICODE_GROUPS.get("Nd")).isNotNull(); // Decimal Digit
  }

  @Test
  void unicodeGroups_latinContainsAsciiLetters() {
    int[][] latin = UnicodeTables.UNICODE_GROUPS.get("Latin");
    assertThat(latin).isNotNull();
    assertThat(containsCodePoint(latin, 'A')).isTrue();
    assertThat(containsCodePoint(latin, 'Z')).isTrue();
    assertThat(containsCodePoint(latin, 'a')).isTrue();
    assertThat(containsCodePoint(latin, 'z')).isTrue();
  }

  @Test
  void unicodeGroups_rangesAreValid() {
    for (var entry : UnicodeTables.UNICODE_GROUPS.entrySet()) {
      for (int[] range : entry.getValue()) {
        assertThat(range.length)
            .withFailMessage(
                "Range in " + entry.getKey() + " should have {lo, hi}")
            .isEqualTo(2);
        assertThat(range[0] <= range[1])
            .withFailMessage(
                "Range in " + entry.getKey()
                    + ": lo=" + range[0] + " > hi=" + range[1])
            .isTrue();
        assertThat(range[0] >= 0)
            .withFailMessage(
                "Range in " + entry.getKey()
                    + ": lo=" + range[0] + " is negative")
            .isTrue();
        assertThat(range[1] <= 0x10FFFF)
            .withFailMessage(
                "Range in " + entry.getKey()
                    + ": hi=" + range[1] + " exceeds max code point")
            .isTrue();
      }
    }
  }

  // --- Sentinel constants ---

  @Test
  void sentinelValues() {
    assertThat(UnicodeTables.EVEN_ODD).isEqualTo(1);
    assertThat(UnicodeTables.ODD_EVEN).isEqualTo(-1);
    assertThat(UnicodeTables.EVEN_ODD_SKIP).isEqualTo(1 << 30);
    assertThat(UnicodeTables.ODD_EVEN_SKIP).isEqualTo((1 << 30) + 1);
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
