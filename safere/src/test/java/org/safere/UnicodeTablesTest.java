// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link UnicodeTables}. */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class UnicodeTablesTest {

  // --- Perl groups ---

  @Test
  void perlGroups_hasFiveEntries() {
    assertThat(UnicodeTables.PERL_GROUPS.size()).isEqualTo(5);
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
    assertThat(UnicodeTables.CASE_FOLD.length).isEqualTo(380);
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
    assertThat(UnicodeTables.TO_LOWER.length).isEqualTo(212);
  }

  @Test
  void toLower_firstEntryIsUppercaseAscii() {
    // A-Z maps to a-z by adding 32
    assertThat(UnicodeTables.TO_LOWER[0]).isEqualTo(new int[] {65, 90, 32});
  }

  // --- Unicode groups ---

  @Test
  void unicodeGroups_hasAllExpectedEntries() {
    // 7 major categories + subcategories + all JDK scripts (minus UNKNOWN).
    // The exact count depends on the JDK's Unicode version, so we check minimum bounds.
    assertThat(UnicodeTables.UNICODE_GROUPS.size())
        .as("UNICODE_GROUPS should have categories + scripts")
        .isGreaterThanOrEqualTo(199); // RE2's original count; JDK 25 has more
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

  // --- simpleFold (ported from RE2/J UnicodeTest) ---

  @Test
  void simpleFold_asciiLettersCaseFold() {
    // Every lowercase ASCII letter should be in a fold orbit with its uppercase equivalent.
    for (int r = 'a'; r <= 'z'; r++) {
      int upper = r - ('a' - 'A');
      // Follow the fold orbit from lowercase to find uppercase.
      boolean found = false;
      int f = r;
      for (int i = 0; i < 5; i++) {
        f = Inst.simpleFold(f);
        if (f == upper) {
          found = true;
          break;
        }
      }
      assertThat(found)
          .as("simpleFold orbit of '%c' should include '%c'", (char) r, (char) upper)
          .isTrue();
    }
  }

  @Test
  void simpleFold_nonLetterIsIdentity() {
    // Non-letter code points should fold to themselves.
    assertThat(Inst.simpleFold('{')).isEqualTo('{');
    assertThat(Inst.simpleFold('/')).isEqualTo('/');
    assertThat(Inst.simpleFold('0')).isEqualTo('0');
  }

  @Test
  void simpleFold_unicodePairs() {
    // é (U+00E9) should fold to É (U+00C9) or vice versa.
    int e_acute_lower = 0x00E9; // é
    int e_acute_upper = 0x00C9; // É
    assertThat(Inst.simpleFold(e_acute_lower)).isEqualTo(e_acute_upper);
    assertThat(Inst.simpleFold(e_acute_upper)).isEqualTo(e_acute_lower);
  }

  @Test
  void simpleFold_kelvinSign() {
    // Kelvin sign (U+212A) is in the fold orbit with 'K' (U+004B) and 'k' (U+006B).
    int kelvin = 0x212A;
    int upper_k = 'K';
    int lower_k = 'k';
    // Follow the orbit starting from kelvin.
    int r = kelvin;
    boolean foundUpperK = false;
    boolean foundLowerK = false;
    for (int i = 0; i < 4; i++) {
      r = Inst.simpleFold(r);
      if (r == upper_k) {
        foundUpperK = true;
      }
      if (r == lower_k) {
        foundLowerK = true;
      }
    }
    assertThat(foundUpperK).as("Kelvin sign orbit should include 'K'").isTrue();
    assertThat(foundLowerK).as("Kelvin sign orbit should include 'k'").isTrue();
  }

  @Test
  void caseInsensitiveMatching_unicodePairs() {
    // Verify case-insensitive matching works for some interesting Unicode pairs.
    int flags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    assertThat(Pattern.compile("é", flags).matcher("É").matches()).isTrue();
    assertThat(Pattern.compile("Ú", flags).matcher("ú").matches()).isTrue();
    assertThat(Pattern.compile("k", flags).matcher("\u212A").matches())
        .isTrue();
    assertThat(Pattern.compile("K", flags).matcher("\u212A").matches())
        .isTrue();
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
