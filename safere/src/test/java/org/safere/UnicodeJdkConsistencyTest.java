// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import com.ibm.icu.lang.UScript;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Canary tests for SafeRE Unicode tables against pinned ICU4J data and the running JDK's {@link
 * Character} implementation. Version-sensitive JDK comparisons require a matching Unicode
 * repertoire; pinned ICU4J checks preserve script and case-fold coverage on every runtime JDK.
 *
 * <p>See <a href="https://github.com/safere/safere/issues/107">#107</a>.
 */
@DisplayName("Unicode table consistency canary tests")
@DisabledForCrosscheck("Unicode table canary uses package-private SafeRE internals")
class UnicodeJdkConsistencyTest {

  // --- General category and pinned Unicode script coverage ---

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

    Map<String, int[][]> groups = UnicodeTables.unicodeGroups();
    for (String cat : subcategories) {
      assertThat(groups.get(cat)).as("Subcategory %s", cat).isNotNull();
    }
    for (String cat : majorCategories) {
      assertThat(groups.get(cat)).as("Major category %s", cat).isNotNull();
    }
  }

  @Test
  @DisplayName("Every script in the pinned Unicode version is present in UNICODE_GROUPS")
  void allPinnedUnicodeScriptsPresent() {
    assertPinnedUnicodeVersion();
    Set<Integer> scripts = new HashSet<>();
    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      scripts.add(UScript.getScript(cp));
    }
    scripts.remove(UScript.UNKNOWN);
    for (int script : scripts) {
      String name = UScript.getName(script);
      assertThat(UnicodeTables.unicodeGroups())
          .as("Pinned Unicode script %s", name)
          .containsKey(name);
    }
  }

  @Test
  @DisplayName("Runtime-generated categories match Character.getType() for all code points")
  void categoriesMatchCharacterGetType() {
    Map<String, int[][]> groups = UnicodeTables.unicodeGroups();

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
  @DisplayName("CASE_FOLD covers all symmetric case pairs in the pinned Unicode version")
  void caseFoldCoversAllPinnedUnicodePairs() {
    assertPinnedUnicodeVersion();
    // Scan all code points and find "clean" symmetric pairs where toLower and toUpper
    // are inverses: toLower(cp) != cp AND toUpper(toLower(cp)) == cp. This means
    // cp is uppercase and its lowercase form round-trips cleanly back to cp.
    //
    // This excludes asymmetric mappings like Turkish İ (U+0130) where
    // toLower(İ) = i but toUpper(i) = I ≠ İ. RE2 intentionally excludes these
    // from its fold orbits because they don't form a clean cycle.
    List<String> missing = new ArrayList<>();

    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      int lower = UCharacter.toLowerCase(cp);
      if (lower != cp && UCharacter.toUpperCase(lower) == cp) {
        // cp is uppercase, lower is its lowercase, and the mapping is symmetric.
        if (!inFoldOrbit(cp, lower)) {
          missing.add(
              String.format(
                  "U+%04X <-> U+%04X (delta %d) not in CASE_FOLD orbit", cp, lower, lower - cp));
        }
      }
    }

    assertThat(missing).as("CASE_FOLD should cover pinned Unicode symmetric case pairs").isEmpty();
  }

  // --- Unicode White_Space canary ---

  @Test
  @DisplayName("unicodeSpace() matches pinned Unicode White_Space")
  void unicodeSpaceMatchesPinnedUnicode() {
    assertPinnedUnicodeVersion();
    assertTableMatches(
        "White_Space",
        UnicodeTables.unicodeSpace(),
        cp -> UCharacter.hasBinaryProperty(cp, UProperty.WHITE_SPACE));
  }

  // --- Generated binary property canaries ---

  @ParameterizedTest(name = "Binary property {0} matches its JDK or Unicode oracle")
  @MethodSource("binaryPropertyNames")
  @EnabledForJreRange(min = JRE.JAVA_26)
  void binaryPropertiesMatchJdkExceptUnicodeHexDigit(String name) {
    int[][] ranges = requireNonNull(UnicodeGeneratedTables.BINARY_PROPERTIES.get(name));
    if (name.equals("Extended_Pictographic") || name.equals("White_Space")) {
      assertPinnedUnicodeVersion();
      int property = UCharacter.getPropertyEnum(name);
      assertTableMatches(name, ranges, cp -> UCharacter.hasBinaryProperty(cp, property));
      return;
    }
    if (!name.equals("Hex_Digit")) {
      // JDK-derived property tables are compatibility-checked only when the runtime JDK
      // and generator JDK use the same Unicode repertoire.
      assumeTrue(
          runtimeJdkUnicodeMatchesGeneratedTables(),
          "Runtime JDK Unicode repertoire differs from the generator JDK");
    }
    java.util.regex.Matcher jdk =
        java.util.regex.Pattern.compile("\\p{Is" + name + "}").matcher("");
    List<String> differences = new ArrayList<>();
    List<String> jdkHexDifferences = new ArrayList<>();
    int rangeIndex = 0;
    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      while (rangeIndex < ranges.length && ranges[rangeIndex][1] < cp) {
        rangeIndex++;
      }
      boolean inTable = rangeIndex < ranges.length && ranges[rangeIndex][0] <= cp;
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

  private static void assertPinnedUnicodeVersion() {
    assertThat(UCharacter.getUnicodeVersion().toString())
        .as("ICU4J oracle must match the bundled Unicode data")
        .isEqualTo(UnicodeGeneratedTables.UNICODE_DATA_VERSION + ".0");
  }

  private static boolean runtimeJdkUnicodeMatchesGeneratedTables() {
    int[][] assigned = requireNonNull(UnicodeGeneratedTables.BINARY_PROPERTIES.get("Assigned"));
    int rangeIndex = 0;
    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      while (rangeIndex < assigned.length && assigned[rangeIndex][1] < cp) {
        rangeIndex++;
      }
      boolean inTable = rangeIndex < assigned.length && assigned[rangeIndex][0] <= cp;
      if (Character.isDefined(cp) != inTable) {
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
