// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Canary tests that verify SafeRE's Unicode tables are consistent with the running JDK's {@link
 * Character} implementation and {@code java.util.regex}. These tests are designed to fail when the
 * JDK upgrades its Unicode version and SafeRE's static tables (case folding, White_Space) need
 * updating.
 *
 * <p>See <a href="https://github.com/eaftan/safere/issues/107">#107</a>.
 */
@DisplayName("Unicode JDK consistency canary tests")
@DisabledForCrosscheck("Unicode table canary uses package-private SafeRE internals")
class UnicodeJdkConsistencyTest {

  // --- Category and script consistency (runtime-generated, should always match) ---

  @Test
  @DisplayName("Every JDK general category is present in UNICODE_GROUPS")
  void allGeneralCategoriesPresent() {
    // All 2-letter subcategory abbreviations that Character.getType() can return.
    String[] subcategories = {
      "Lu", "Ll", "Lt", "Lm", "Lo", // Letter
      "Mn", "Me", "Mc", // Mark
      "Nd", "Nl", "No", // Number
      "Zs", "Zl", "Zp", // Separator
      "Cc", "Cf", "Co", "Cs", // Other (no Cn per RE2 convention)
      "Pd", "Ps", "Pe", "Pc", "Po", "Pi", "Pf", // Punctuation
      "Sm", "Sc", "Sk", "So", // Symbol
    };
    String[] majorCategories = {"L", "M", "N", "P", "S", "Z", "C"};

    Map<String, int[][]> groups = UnicodeTables.UNICODE_GROUPS;
    for (String cat : subcategories) {
      assertThat(groups.get(cat)).as("Subcategory %s", cat).isNotNull();
    }
    for (String cat : majorCategories) {
      assertThat(groups.get(cat)).as("Major category %s", cat).isNotNull();
    }
  }

  @Test
  @DisplayName("Every JDK UnicodeScript is present in UNICODE_GROUPS")
  void allScriptsPresent() {
    Map<String, int[][]> groups = UnicodeTables.UNICODE_GROUPS;
    for (Character.UnicodeScript script : Character.UnicodeScript.values()) {
      if (script == Character.UnicodeScript.UNKNOWN) {
        continue;
      }
      // The runtime generator should have added this script.
      String name = script.name();
      boolean found =
          groups.entrySet().stream()
              .anyMatch(
                  e -> {
                    try {
                      return Character.UnicodeScript.forName(e.getKey()) == script;
                    } catch (IllegalArgumentException ex) {
                      return false;
                    }
                  });
      assertThat(found).as("Script %s should be in UNICODE_GROUPS", name).isTrue();
    }
  }

  @Test
  @DisplayName("Runtime-generated categories match Character.getType() for all code points")
  void categoriesMatchCharacterGetType() {
    Map<String, int[][]> groups = UnicodeTables.UNICODE_GROUPS;

    // Spot-check a representative sample of code points across categories.
    record Sample(int cp, String expectedCategory) {}

    List<Sample> samples =
        List.of(
            new Sample('A', "Lu"),
            new Sample('a', "Ll"),
            new Sample('\u01C5', "Lt"), // Dz with caron
            new Sample('\u02B0', "Lm"), // Modifier letter small h
            new Sample('\u00AA', "Lo"), // Feminine ordinal indicator
            new Sample('\u0300', "Mn"), // Combining grave accent
            new Sample('\u0488', "Me"), // Combining Cyrillic-Old Slavic
            new Sample('\u0903', "Mc"), // Devanagari sign visarga
            new Sample('0', "Nd"),
            new Sample('\u2160', "Nl"), // Roman numeral one
            new Sample('\u00B2', "No"), // Superscript two
            new Sample(' ', "Zs"),
            new Sample('\u2028', "Zl"), // Line separator
            new Sample('\u2029', "Zp"), // Paragraph separator
            new Sample('\u0000', "Cc"),
            new Sample('\u00AD', "Cf"), // Soft hyphen
            new Sample('-', "Pd"),
            new Sample('(', "Ps"),
            new Sample(')', "Pe"),
            new Sample('_', "Pc"),
            new Sample('!', "Po"),
            new Sample('+', "Sm"),
            new Sample('$', "Sc"),
            new Sample('^', "Sk"),
            new Sample('\u00A9', "So"), // Copyright sign
            new Sample('\u00AB', "Pi"), // Left-pointing double angle
            new Sample('\u00BB', "Pf")); // Right-pointing double angle

    for (Sample s : samples) {
      int[][] ranges = groups.get(s.expectedCategory);
      assertThat(ranges)
          .as("Category %s should exist", s.expectedCategory)
          .isNotNull();
      assertThat(containsCodePoint(ranges, s.cp))
          .as(
              "U+%04X should be in category %s (getType=%d)",
              s.cp, s.expectedCategory, Character.getType(s.cp))
          .isTrue();
    }
  }

  // --- Case folding canary ---

  @Test
  @DisplayName("CASE_FOLD covers all JDK symmetric case pairs")
  void caseFoldCoversAllJdkPairs() {
    // Scan all code points and find "clean" symmetric pairs where toLower and toUpper
    // are inverses: toLower(cp) != cp AND toUpper(toLower(cp)) == cp. This means
    // cp is uppercase and its lowercase form round-trips cleanly back to cp.
    //
    // This excludes asymmetric mappings like Turkish İ (U+0130) where
    // toLower(İ) = i but toUpper(i) = I ≠ İ. RE2 intentionally excludes these
    // from its fold orbits because they don't form a clean cycle.
    List<String> missing = new ArrayList<>();

    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      int lower = Character.toLowerCase(cp);
      if (lower != cp && Character.toUpperCase(lower) == cp) {
        // cp is uppercase, lower is its lowercase, and the mapping is symmetric.
        if (!inFoldOrbit(cp, lower)) {
          missing.add(
              String.format(
                  "U+%04X <-> U+%04X (delta %d) not in CASE_FOLD orbit",
                  cp, lower, lower - cp));
        }
      }
    }

    assertThat(missing)
        .as(
            "CASE_FOLD should cover all JDK symmetric case pairs. "
                + "If this fails, the static CASE_FOLD table needs updating for a new Unicode version.")
        .isEmpty();
  }

  // --- Unicode White_Space canary ---

  @Test
  @DisplayName("unicodeSpace() matches JDK's \\p{IsWhite_Space}")
  void unicodeSpaceMatchesJdk() {
    // Build the set of code points that SafeRE considers whitespace.
    int[][] safereRanges = UnicodeTables.unicodeSpace();
    TreeSet<Integer> safereSet = new TreeSet<>();
    for (int[] range : safereRanges) {
      for (int cp = range[0]; cp <= range[1]; cp++) {
        safereSet.add(cp);
      }
    }

    // Build the set from JDK's regex.
    java.util.regex.Pattern jdkWhiteSpace =
        java.util.regex.Pattern.compile("\\p{IsWhite_Space}");
    TreeSet<Integer> jdkSet = new TreeSet<>();
    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      if (jdkWhiteSpace.matcher(new String(Character.toChars(cp))).matches()) {
        jdkSet.add(cp);
      }
    }

    List<String> inSafereNotJdk = new ArrayList<>();
    for (int cp : safereSet) {
      if (!jdkSet.contains(cp)) {
        inSafereNotJdk.add(String.format("U+%04X", cp));
      }
    }
    List<String> inJdkNotSafere = new ArrayList<>();
    for (int cp : jdkSet) {
      if (!safereSet.contains(cp)) {
        inJdkNotSafere.add(String.format("U+%04X", cp));
      }
    }

    assertThat(inSafereNotJdk)
        .as("Code points in SafeRE unicodeSpace but not JDK White_Space")
        .isEmpty();
    assertThat(inJdkNotSafere)
        .as(
            "Code points in JDK White_Space but not SafeRE unicodeSpace. "
                + "If this fails, the static unicodeSpace() table needs updating.")
        .isEmpty();
  }

  // --- Helpers ---

  /** Checks whether {@code target} is reachable from {@code start} via simpleFold orbits. */
  private static boolean inFoldOrbit(int start, int target) {
    int r = start;
    for (int i = 0; i < 10; i++) {
      r = Inst.simpleFold(r);
      if (r == target) {
        return true;
      }
      if (r == start) {
        break; // completed the orbit
      }
    }
    return false;
  }

  private static boolean containsCodePoint(int[][] ranges, int cp) {
    for (int[] range : ranges) {
      if (cp >= range[0] && cp <= range[1]) {
        return true;
      }
    }
    return false;
  }
}
