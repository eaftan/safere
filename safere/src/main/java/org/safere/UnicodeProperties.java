// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Unicode property lookups for JDK-compatible {@code \p{...}} syntax extensions.
 *
 * <p>SafeRE-owned Unicode property tables are generated from the maintainer JDK and checked into
 * the repository. They do not depend on the JDK running SafeRE.
 */
final class UnicodeProperties {
  private UnicodeProperties() {}

  private static final class ScriptOrCategoryNormalizedHolder {
    static final Map<String, String> NORMALIZED_KEYS =
        buildNormalizedKeys(UnicodeTables.UNICODE_GROUPS);
  }

  private static final class ScriptNormalizedHolder {
    static final Map<String, String> NORMALIZED_KEYS =
        buildNormalizedKeys(UnicodeGeneratedTables.SCRIPTS);
  }

  private static final class BlockNormalizedHolder {
    static final Map<String, String> NORMALIZED_KEYS =
        buildNormalizedKeys(UnicodeGeneratedTables.BLOCKS);
  }

  private static final class BinaryNormalizedHolder {
    static final Map<String, String> NORMALIZED_KEYS =
        buildNormalizedKeys(UnicodeGeneratedTables.BINARY_PROPERTIES);
  }

  /**
   * Looks up a binary Unicode property by name (e.g., "Alphabetic", "Lowercase"). The lookup is
   * loose per UTS#18: case, underscores, hyphens, and spaces are ignored.
   *
   * @return the range table, or {@code null} if not a recognized binary property
   */
  static int[][] lookupBinaryProperty(String name) {
    int[][] table = UnicodeGeneratedTables.BINARY_PROPERTIES.get(name);
    if (table != null) {
      return table;
    }
    String canonicalKey = BinaryNormalizedHolder.NORMALIZED_KEYS.get(normalize(name));
    return canonicalKey == null ? null : UnicodeGeneratedTables.BINARY_PROPERTIES.get(canonicalKey);
  }

  /**
   * Looks up a Unicode block by name. The lookup is case-insensitive and ignores spaces, hyphens,
   * and underscores.
   *
   * @return the range table, or {@code null} if not a recognized block
   */
  static int[][] lookupBlock(String name) {
    int[][] table = UnicodeGeneratedTables.BLOCKS.get(name);
    if (table != null) {
      return table;
    }
    String canonicalKey = BlockNormalizedHolder.NORMALIZED_KEYS.get(normalize(name));
    if (canonicalKey != null) {
      return UnicodeGeneratedTables.BLOCKS.get(canonicalKey);
    }
    try {
      String runtimeJdkName = toTitleSnake(Character.UnicodeBlock.forName(name).toString());
      return UnicodeGeneratedTables.BLOCKS.get(runtimeJdkName);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * Looks up a Unicode script by name. The lookup is case-insensitive and ignores spaces, hyphens,
   * and underscores.
   *
   * @return the range table, or {@code null} if not a recognized script
   */
  static int[][] lookupScript(String name) {
    int[][] table = UnicodeGeneratedTables.SCRIPTS.get(name);
    if (table != null) {
      return table;
    }
    String canonicalKey = ScriptNormalizedHolder.NORMALIZED_KEYS.get(normalize(name));
    if (canonicalKey != null) {
      return UnicodeGeneratedTables.SCRIPTS.get(canonicalKey);
    }
    try {
      String runtimeJdkName = scriptName(Character.UnicodeScript.forName(name));
      return UnicodeGeneratedTables.SCRIPTS.get(runtimeJdkName);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * Looks up a Unicode general category by its one- or two-letter abbreviation.
   *
   * @return the range table, or {@code null} if not a recognized category
   */
  static int[][] lookupCategory(String name) {
    return switch (name) {
      case "L",
          "M",
          "N",
          "P",
          "S",
          "Z",
          "C",
          "Lu",
          "Ll",
          "Lt",
          "Lm",
          "Lo",
          "Mn",
          "Me",
          "Mc",
          "Nd",
          "Nl",
          "No",
          "Zs",
          "Zl",
          "Zp",
          "Cc",
          "Cn",
          "Cf",
          "Co",
          "Cs",
          "Pd",
          "Ps",
          "Pe",
          "Pc",
          "Po",
          "Pi",
          "Pf",
          "Sm",
          "Sc",
          "Sk",
          "So" ->
          lookupGeneratedOrSyntheticCategory(name);
      default -> null;
    };
  }

  private static int[][] lookupGeneratedOrSyntheticCategory(String name) {
    int[][] table = UnicodeGeneratedTables.CATEGORIES.get(name);
    if (table != null) {
      return table;
    }
    if ("Cn".equals(name)) {
      return UnassignedCategoryHolder.TABLE;
    }
    return null;
  }

  private static final class UnassignedCategoryHolder {
    static final int[][] TABLE = buildTable();

    private static int[][] buildTable() {
      CharClassBuilder ccb = new CharClassBuilder();
      int lo = -1;
      for (int cp = 0; cp <= Utils.MAX_RUNE; cp++) {
        if (Character.getType(cp) == Character.UNASSIGNED) {
          if (lo < 0) {
            lo = cp;
          }
        } else if (lo >= 0) {
          ccb.addRange(lo, cp - 1);
          lo = -1;
        }
      }
      if (lo >= 0) {
        ccb.addRange(lo, Utils.MAX_RUNE);
      }
      CharClass cc = ccb.build();
      int[][] table = new int[cc.numRanges()][2];
      for (int i = 0; i < cc.numRanges(); i++) {
        table[i][0] = cc.lo(i);
        table[i][1] = cc.hi(i);
      }
      return table;
    }
  }

  /**
   * Case-insensitive lookup of a script or category name in {@link UnicodeTables#UNICODE_GROUPS}.
   * Spaces and hyphens are normalized to underscores.
   *
   * @return the range table, or {@code null} if not found
   */
  static int[][] lookupScriptOrCategory(String name) {
    int[][] table = UnicodeTables.UNICODE_GROUPS.get(name);
    if (table != null) {
      return table;
    }
    String canonicalKey = ScriptOrCategoryNormalizedHolder.NORMALIZED_KEYS.get(normalize(name));
    return canonicalKey == null ? null : UnicodeTables.UNICODE_GROUPS.get(canonicalKey);
  }

  /**
   * Normalizes a property name for loose matching per UTS#18: uppercases and removes underscores,
   * hyphens, and spaces.
   */
  private static String normalize(String name) {
    return name.toUpperCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
  }

  private static Map<String, String> buildNormalizedKeys(Map<String, int[][]> tables) {
    Map<String, String> map = new HashMap<>();
    for (String key : tables.keySet()) {
      map.put(normalize(key), key);
    }
    return map;
  }

  private static String scriptName(Character.UnicodeScript script) {
    if (script == Character.UnicodeScript.SIGNWRITING) {
      return "SignWriting";
    }
    return toTitleSnake(script.name());
  }

  private static String toTitleSnake(String upperSnake) {
    StringBuilder result = new StringBuilder(upperSnake.length());
    for (String part : upperSnake.split("_", -1)) {
      if (!result.isEmpty()) {
        result.append('_');
      }
      result.append(Character.toUpperCase(part.charAt(0)));
      if (part.length() > 1) {
        result.append(part.substring(1).toLowerCase(Locale.ROOT));
      }
    }
    return result.toString();
  }
}
