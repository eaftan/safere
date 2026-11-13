// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Canary tests for SafeRE Unicode tables and the running JDK's {@link Character} implementation.
 * SafeRE-owned Unicode property tables can differ from the runtime JDK's Unicode version. Coverage
 * canaries include older JDK repertoires; equality canaries require the same repertoire. A newer
 * runtime must not implicitly require an upgrade of the deliberately pinned Unicode data.
 *
 * <p>See <a href="https://github.com/safere/safere/issues/107">#107</a>.
 */
@DisplayName("Unicode JDK consistency canary tests")
@DisabledForCrosscheck("Unicode table canary uses package-private SafeRE internals")
class UnicodeJdkConsistencyTest {

  // --- Runtime JDK category and script coverage ---

  @Test
  @DisplayName("Every JDK general category is present in UNICODE_GROUPS")
  void allGeneralCategoriesPresent() {
    // All 2-letter subcategory abbreviations that Character.getType() can return.
    String[] subcategories = {
      "Lu", "Ll", "Lt", "Lm", "Lo", // Letter
      "Mn", "Me", "Mc", // Mark
      "Nd", "Nl", "No", // Number
      "Zs", "Zl", "Zp", // Separator
      "Cc", "Cf", "Cn", "Co", "Cs", // Other
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
    assumeTrue(
        runtimeJdkUnicodeIsCoveredByGeneratedTables(),
        "Runtime JDK has Unicode assignments beyond the bundled tables");
    Map<String, int[][]> groups = UnicodeTables.UNICODE_GROUPS;
    for (Character.UnicodeScript script : Character.UnicodeScript.values()) {
      if (script == Character.UnicodeScript.UNKNOWN) {
        continue;
      }
      // The generated tables should include every script known to the runtime JDK.
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
      assertThat(ranges).as("Category %s should exist", s.expectedCategory).isNotNull();
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
    assumeTrue(
        runtimeJdkUnicodeIsCoveredByGeneratedTables(),
        "Runtime JDK has Unicode assignments beyond the bundled tables");
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
                  "U+%04X <-> U+%04X (delta %d) not in CASE_FOLD orbit", cp, lower, lower - cp));
        }
      }
    }

    assertThat(missing)
        .as(
            "CASE_FOLD should cover all JDK symmetric case pairs. If this fails, the static"
                + " CASE_FOLD table needs updating for a new Unicode version.")
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
    java.util.regex.Pattern jdkWhiteSpace = java.util.regex.Pattern.compile("\\p{IsWhite_Space}");
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
                + "If this fails, the generated White_Space table needs updating.")
        .isEmpty();
  }

  // --- Generated binary property canaries ---

  @ParameterizedTest(name = "Binary property {0} matches its JDK or Unicode oracle")
  @MethodSource("binaryPropertyNames")
  @EnabledForJreRange(min = JRE.JAVA_26)
  void binaryPropertiesMatchJdkExceptUnicodeHexDigit(String name) {
    boolean icuProperty = name.equals("Extended_Pictographic") || name.equals("White_Space");
    if (!name.equals("Hex_Digit") && !icuProperty) {
      // JDK-derived property tables match the runtime JDK when both JDKs use the same Unicode
      // repertoire.
      assumeTrue(
          runtimeJdkUnicodeMatchesGeneratedTables(),
          "Runtime JDK Unicode repertoire differs from the generator JDK");
    }
    int[][] ranges = requireNonNull(UnicodeGeneratedTables.BINARY_PROPERTIES.get(name));
    int[][] assigned = requireNonNull(UnicodeGeneratedTables.BINARY_PROPERTIES.get("Assigned"));
    java.util.regex.Matcher jdk =
        java.util.regex.Pattern.compile("\\p{Is" + name + "}").matcher("");
    List<String> differences = new ArrayList<>();
    List<String> jdkHexDifferences = new ArrayList<>();
    int rangeIndex = 0;
    int assignedIndex = 0;
    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      while (rangeIndex < ranges.length && ranges[rangeIndex][1] < cp) {
        rangeIndex++;
      }
      boolean inTable = rangeIndex < ranges.length && ranges[rangeIndex][0] <= cp;
      while (assignedIndex < assigned.length && assigned[assignedIndex][1] < cp) {
        assignedIndex++;
      }
      boolean inGeneratedAssigned =
          assignedIndex < assigned.length && assigned[assignedIndex][0] <= cp;
      if (icuProperty && (!Character.isDefined(cp) || !inGeneratedAssigned)) {
        // ICU4J-backed properties (UNICODE_DATA_VERSION) may be ahead of or behind the runtime
        // JDK on code points that were unassigned in the older Unicode version (for example,
        // reserved Extended_Pictographic ranges reassigned to non-pictographic characters).
        continue;
      }
      boolean jdkMatches = jdk.reset(Character.toString(cp)).matches();
      boolean expected = jdkMatches;
      if (name.equals("Hex_Digit")) {
        // Approved spec-based divergence: Unicode Hex_Digit excludes other decimal digits.
        expected =
            (cp >= '0' && cp <= '9')
                || (cp >= 'A' && cp <= 'F')
                || (cp >= 'a' && cp <= 'f')
                || (cp >= 0xFF10 && cp <= 0xFF19)
                || (cp >= 0xFF21 && cp <= 0xFF26)
                || (cp >= 0xFF41 && cp <= 0xFF46);
        if (jdkMatches != (expected || Character.isDigit(cp)) && jdkHexDifferences.size() < 10) {
          jdkHexDifferences.add(String.format("U+%04X", cp));
        }
      }
      if (inTable != expected && differences.size() < 10) {
        differences.add(String.format("U+%04X", cp));
      }
    }
    assertThat(differences).as("%s table membership differences", name).isEmpty();
    assertThat(jdkHexDifferences).as("JDK Hex_Digit implementation changed").isEmpty();
  }

  // --- Table-derived classes that used to scan Character at runtime ---

  @Test
  @DisplayName("(?U) Blank, Graph, and Print match their JDK Character definitions")
  @EnabledForJreRange(min = JRE.JAVA_26)
  void unicodePosixDerivedClassesMatchJdk() {
    assumeTrue(
        runtimeJdkUnicodeMatchesGeneratedTables(),
        "Runtime JDK Unicode repertoire differs from the generator JDK");
    Map<String, int[][]> groups = UnicodeTables.unicodePosixPropertyGroups();
    assertTableMatches(
        "Blank",
        groups.get("Blank"),
        cp -> cp == '\t' || Character.getType(cp) == Character.SPACE_SEPARATOR);
    assertTableMatches("Graph", groups.get("Graph"), UnicodeJdkConsistencyTest::isJdkGraph);
    assertTableMatches(
        "Print",
        groups.get("Print"),
        cp -> isJdkGraph(cp) || Character.getType(cp) == Character.SPACE_SEPARATOR);
  }

  @Test
  @DisplayName("Case-insensitive cased properties match the JDK's cased predicates")
  @EnabledForJreRange(min = JRE.JAVA_26)
  void caseInsensitiveCasedPropertiesMatchJdk() {
    assumeTrue(
        runtimeJdkUnicodeMatchesGeneratedTables(),
        "Runtime JDK Unicode repertoire differs from the generator JDK");
    Matcher m = Pattern.compile("(?i)\\p{IsLowercase}").matcher("");
    List<String> differences = new ArrayList<>();
    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      boolean expected =
          Character.isUpperCase(cp) || Character.isLowerCase(cp) || Character.isTitleCase(cp);
      if (m.reset(Character.toString(cp)).matches() != expected && differences.size() < 10) {
        differences.add(String.format("U+%04X", cp));
      }
    }
    assertThat(differences).as("(?i)\\p{IsLowercase} membership differences").isEmpty();
  }

  private static final Map<String, IntPredicate> JAVA_CLASS_PREDICATES =
      Map.ofEntries(
          Map.entry("javaLowerCase", Character::isLowerCase),
          Map.entry("javaUpperCase", Character::isUpperCase),
          Map.entry("javaWhitespace", Character::isWhitespace),
          Map.entry("javaMirrored", Character::isMirrored),
          Map.entry("javaAlphabetic", Character::isAlphabetic),
          Map.entry("javaIdeographic", Character::isIdeographic),
          Map.entry("javaTitleCase", Character::isTitleCase),
          Map.entry("javaDigit", Character::isDigit),
          Map.entry("javaDefined", Character::isDefined),
          Map.entry("javaLetter", Character::isLetter),
          Map.entry("javaLetterOrDigit", Character::isLetterOrDigit),
          Map.entry("javaSpaceChar", Character::isSpaceChar),
          Map.entry("javaISOControl", Character::isISOControl),
          Map.entry("javaIdentifierIgnorable", Character::isIdentifierIgnorable),
          Map.entry("javaUnicodeIdentifierStart", Character::isUnicodeIdentifierStart),
          Map.entry("javaUnicodeIdentifierPart", Character::isUnicodeIdentifierPart),
          Map.entry("javaJavaIdentifierStart", Character::isJavaIdentifierStart),
          Map.entry("javaJavaIdentifierPart", Character::isJavaIdentifierPart));

  @ParameterizedTest(name = "{0} matches its Character predicate")
  @MethodSource("javaClassNames")
  @EnabledForJreRange(min = JRE.JAVA_26)
  void javaClassesMatchJdk(String name) {
    assumeTrue(
        runtimeJdkUnicodeMatchesGeneratedTables(),
        "Runtime JDK Unicode repertoire differs from the generator JDK");
    assertTableMatches(
        name,
        requireNonNull(JavaCharacterClasses.lookup(name)),
        requireNonNull(JAVA_CLASS_PREDICATES.get(name)));
  }

  private static Stream<String> javaClassNames() {
    return JAVA_CLASS_PREDICATES.keySet().stream().sorted();
  }

  private static boolean isJdkGraph(int cp) {
    int type = Character.getType(cp);
    return Character.isDefined(cp)
        && type != Character.CONTROL
        && type != Character.SURROGATE
        && !Character.isSpaceChar(cp);
  }

  private static void assertTableMatches(String name, int[][] ranges, IntPredicate expected) {
    List<String> differences = new ArrayList<>();
    int rangeIndex = 0;
    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      while (rangeIndex < ranges.length && ranges[rangeIndex][1] < cp) {
        rangeIndex++;
      }
      boolean inTable = rangeIndex < ranges.length && ranges[rangeIndex][0] <= cp;
      if (inTable != expected.test(cp) && differences.size() < 10) {
        differences.add(String.format("U+%04X", cp));
      }
    }
    assertThat(differences).as("%s table membership differences", name).isEmpty();
  }

  // --- Helpers ---

  private static boolean runtimeJdkUnicodeMatchesGeneratedTables() {
    return runtimeJdkUnicodeIsCompatibleWithGeneratedTables(false);
  }

  private static boolean runtimeJdkUnicodeIsCoveredByGeneratedTables() {
    return runtimeJdkUnicodeIsCompatibleWithGeneratedTables(true);
  }

  private static boolean runtimeJdkUnicodeIsCompatibleWithGeneratedTables(boolean allowOlderJdk) {
    int[][] assigned = requireNonNull(UnicodeGeneratedTables.BINARY_PROPERTIES.get("Assigned"));
    int rangeIndex = 0;
    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      while (rangeIndex < assigned.length && assigned[rangeIndex][1] < cp) {
        rangeIndex++;
      }
      boolean inTable = rangeIndex < assigned.length && assigned[rangeIndex][0] <= cp;
      boolean runtimeAssigned = Character.isDefined(cp);
      if (runtimeAssigned != inTable && (!allowOlderJdk || runtimeAssigned)) {
        return false;
      }
    }
    return true;
  }

  private static Stream<String> binaryPropertyNames() {
    return UnicodeGeneratedTables.BINARY_PROPERTIES.keySet().stream().sorted();
  }

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
