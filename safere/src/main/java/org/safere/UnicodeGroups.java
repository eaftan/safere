// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Generates Unicode general category and script range tables at runtime from the JDK's {@link
 * Character} implementation.
 *
 * <p>Tables are computed once at first access (lazy holder pattern) and cached for the lifetime of
 * the JVM. Because the tables are derived from the running JVM's {@code Character} data, they
 * always match the JVM's Unicode version — no manual updates are needed when the JDK upgrades its
 * Unicode support.
 *
 * <p>This replaces the former static tables ported from RE2's {@code unicode_groups.cc}, which
 * could drift from the JDK's Unicode version. See <a
 * href="https://github.com/eaftan/safere/issues/107">#107</a>.
 */
final class UnicodeGroups {
  private UnicodeGroups() {}

  /**
   * Returns an unmodifiable map of Unicode group names to their code point range tables. Keys
   * include:
   *
   * <ul>
   *   <li>Major general categories: {@code L}, {@code M}, {@code N}, {@code P}, {@code S}, {@code
   *       Z}, {@code C}
   *   <li>Subcategories: {@code Lu}, {@code Ll}, {@code Nd}, {@code Zs}, etc.
   *   <li>Script names in Unicode canonical form: {@code Latin}, {@code Greek}, {@code Han}, etc.
   * </ul>
   *
   * <p>Each value is a sorted {@code int[][]} of inclusive {@code {lo, hi}} code point ranges.
   */
  static Map<String, int[][]> groups() {
    return Holder.GROUPS;
  }

  // Lazy holder — tables are computed on first access, then cached.
  private static final class Holder {
    static final Map<String, int[][]> GROUPS = buildAll();
  }

  // Mapping from Character.getType() return value to the Unicode 2-letter abbreviation.
  // Index corresponds to the int constant (e.g., Character.UPPERCASE_LETTER = 1 → "Lu").
  // Note: index 17 is a gap in the JDK constants (there is no type 17).
  private static final String[] CATEGORY_ABBREVS = {
    "Cn", // 0  UNASSIGNED
    "Lu", // 1  UPPERCASE_LETTER
    "Ll", // 2  LOWERCASE_LETTER
    "Lt", // 3  TITLECASE_LETTER
    "Lm", // 4  MODIFIER_LETTER
    "Lo", // 5  OTHER_LETTER
    "Mn", // 6  NON_SPACING_MARK
    "Me", // 7  ENCLOSING_MARK
    "Mc", // 8  COMBINING_SPACING_MARK
    "Nd", // 9  DECIMAL_DIGIT_NUMBER
    "Nl", // 10 LETTER_NUMBER
    "No", // 11 OTHER_NUMBER
    "Zs", // 12 SPACE_SEPARATOR
    "Zl", // 13 LINE_SEPARATOR
    "Zp", // 14 PARAGRAPH_SEPARATOR
    "Cc", // 15 CONTROL
    "Cf", // 16 FORMAT
    null, // 17 (gap — no JDK constant uses this value)
    "Co", // 18 PRIVATE_USE
    "Cs", // 19 SURROGATE
    "Pd", // 20 DASH_PUNCTUATION
    "Ps", // 21 START_PUNCTUATION
    "Pe", // 22 END_PUNCTUATION
    "Pc", // 23 CONNECTOR_PUNCTUATION
    "Po", // 24 OTHER_PUNCTUATION
    "Sm", // 25 MATH_SYMBOL
    "Sc", // 26 CURRENCY_SYMBOL
    "Sk", // 27 MODIFIER_SYMBOL
    "So", // 28 OTHER_SYMBOL
    "Pi", // 29 INITIAL_QUOTE_PUNCTUATION
    "Pf", // 30 FINAL_QUOTE_PUNCTUATION
  };

  // Which getType() values belong to each major category.
  // C excludes Cn (UNASSIGNED) to match RE2's convention.
  private static final int[][] MAJOR_CATEGORY_TYPES = {
    {1, 2, 3, 4, 5}, // L = Lu + Ll + Lt + Lm + Lo
    {6, 7, 8}, // M = Mn + Me + Mc
    {9, 10, 11}, // N = Nd + Nl + No
    {20, 21, 22, 23, 24, 29, 30}, // P = Pd + Ps + Pe + Pc + Po + Pi + Pf
    {25, 26, 27, 28}, // S = Sm + Sc + Sk + So
    {12, 13, 14}, // Z = Zs + Zl + Zp
    {15, 16, 18, 19}, // C = Cc + Cf + Co + Cs (no Cn)
  };

  private static final String[] MAJOR_CATEGORY_NAMES = {"L", "M", "N", "P", "S", "Z", "C"};

  // Script names where the simple UPPER_SNAKE → Title_Snake conversion doesn't match the Unicode
  // canonical name.
  private static final Map<String, String> SCRIPT_NAME_OVERRIDES =
      Map.of("SIGNWRITING", "SignWriting");

  private static Map<String, int[][]> buildAll() {
    // Phase 1: Single pass over all code points — bucket by category and script.
    RangeBuilder[] categoryBuilders = new RangeBuilder[CATEGORY_ABBREVS.length];
    for (int i = 0; i < categoryBuilders.length; i++) {
      categoryBuilders[i] = new RangeBuilder();
    }

    Map<Character.UnicodeScript, RangeBuilder> scriptBuilders =
        new EnumMap<>(Character.UnicodeScript.class);

    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      int type = Character.getType(cp);
      if (type >= 0 && type < categoryBuilders.length) {
        categoryBuilders[type].add(cp);
      }

      Character.UnicodeScript script = Character.UnicodeScript.of(cp);
      if (script != Character.UnicodeScript.UNKNOWN) {
        scriptBuilders.computeIfAbsent(script, k -> new RangeBuilder()).add(cp);
      }
    }

    // Phase 2: Build range tables from builders.
    int[][][] categoryTables = new int[categoryBuilders.length][][];
    for (int i = 0; i < categoryBuilders.length; i++) {
      categoryTables[i] = categoryBuilders[i].build();
    }

    // Phase 3: Assemble the map.
    Map<String, int[][]> map = new HashMap<>();

    // Subcategories (skip Cn = type 0, which RE2 does not expose as a standalone group,
    // and skip the gap at index 17).
    for (int i = 1; i < CATEGORY_ABBREVS.length; i++) {
      if (CATEGORY_ABBREVS[i] == null) {
        continue; // gap at index 17
      }
      if (categoryTables[i].length > 0) {
        map.put(CATEGORY_ABBREVS[i], categoryTables[i]);
      }
    }

    // Major categories (merged from subcategories).
    for (int i = 0; i < MAJOR_CATEGORY_NAMES.length; i++) {
      int[][] merged = mergeSubcategories(categoryTables, MAJOR_CATEGORY_TYPES[i]);
      if (merged.length > 0) {
        map.put(MAJOR_CATEGORY_NAMES[i], merged);
      }
    }

    // Scripts.
    for (var entry : scriptBuilders.entrySet()) {
      String name = scriptName(entry.getKey());
      map.put(name, entry.getValue().build());
    }

    return Collections.unmodifiableMap(map);
  }

  /** Converts a {@link Character.UnicodeScript} enum constant to its Unicode canonical name. */
  private static String scriptName(Character.UnicodeScript script) {
    String enumName = script.name();
    String override = SCRIPT_NAME_OVERRIDES.get(enumName);
    if (override != null) {
      return override;
    }
    return toTitleSnake(enumName);
  }

  private static final Pattern UNDERSCORE = Pattern.compile("_");

  /**
   * Converts an UPPER_SNAKE_CASE string to Title_Snake_Case (e.g., {@code "OLD_ITALIC"} → {@code
   * "Old_Italic"}).
   */
  private static String toTitleSnake(String upperSnake) {
    StringBuilder sb = new StringBuilder(upperSnake.length());
    for (String part : UNDERSCORE.split(upperSnake, -1)) {
      if (!sb.isEmpty()) {
        sb.append('_');
      }
      sb.append(Character.toUpperCase(part.charAt(0)));
      if (part.length() > 1) {
        sb.append(part.substring(1).toLowerCase(Locale.ROOT));
      }
    }
    return sb.toString();
  }

  /**
   * Merges multiple subcategory range tables into a single sorted, non-overlapping range table.
   *
   * @param allTables all category tables indexed by getType() value
   * @param types which getType() indices to merge
   */
  private static int[][] mergeSubcategories(int[][][] allTables, int[] types) {
    int total = 0;
    for (int t : types) {
      total += allTables[t].length;
    }
    if (total == 0) {
      return new int[0][];
    }

    int[][] all = new int[total][];
    int idx = 0;
    for (int t : types) {
      System.arraycopy(allTables[t], 0, all, idx, allTables[t].length);
      idx += allTables[t].length;
    }

    java.util.Arrays.sort(all, (a, b) -> a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]);

    int[][] merged = new int[total][];
    int count = 0;
    for (int[] range : all) {
      if (count > 0 && range[0] <= merged[count - 1][1] + 1) {
        merged[count - 1][1] = Math.max(merged[count - 1][1], range[1]);
      } else {
        merged[count++] = new int[] {range[0], range[1]};
      }
    }
    return java.util.Arrays.copyOf(merged, count);
  }

  /**
   * Efficient builder for constructing sorted, non-overlapping {@code {lo, hi}} range tables from a
   * sequential scan of code points. Code points must be added in ascending order.
   */
  private static final class RangeBuilder {
    private final List<int[]> ranges = new ArrayList<>();
    private int lo = -1;
    private int hi = -1;

    void add(int cp) {
      if (hi >= 0 && cp == hi + 1) {
        hi = cp;
      } else {
        flush();
        lo = cp;
        hi = cp;
      }
    }

    private void flush() {
      if (hi >= 0) {
        ranges.add(new int[] {lo, hi});
      }
    }

    int[][] build() {
      flush();
      return ranges.toArray(new int[0][]);
    }
  }
}
