// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Locale;

/**
 * Expands parsed character-class atoms according to SafeRE's regex flags and Unicode property
 * semantics.
 */
final class CharClassExpander {
  private CharClassExpander() {}

  enum CaseFoldPolicy {
    NONE,
    ASCII_POSIX,
    UNICODE_CASED_LETTER_CATEGORY,
    JAVA_CASED_PROPERTY
  }

  static final class Group {
    private final int[][] table;
    private final CaseFoldPolicy caseFoldPolicy;

    Group(int[][] table, CaseFoldPolicy caseFoldPolicy) {
      this.table = table;
      this.caseFoldPolicy = caseFoldPolicy;
    }

    int[][] table() {
      return table;
    }

    CaseFoldPolicy caseFoldPolicy() {
      return caseFoldPolicy;
    }
  }

  static Group lookupPerlGroup(String name, boolean unicodeCharacterClass) {
    int[][] table =
        unicodeCharacterClass
            ? UnicodeTables.unicodePerlGroups().get(name)
            : UnicodeTables.PERL_GROUPS.get(name);
    return table == null ? null : new Group(table, CaseFoldPolicy.NONE);
  }

  static Group lookupUnicodeGroup(String name, boolean unicodeCharacterClass) {
    int[][] table = JavaCharacterClasses.lookup(name);
    if (table != null) {
      return new Group(table, javaClassCaseFoldPolicy(name));
    }
    table =
        unicodeCharacterClass
            ? UnicodeTables.unicodePosixPropertyGroups().get(name)
            : UnicodeTables.POSIX_PROPERTY_GROUPS.get(name);
    if (table != null) {
      return new Group(table, posixPropertyCaseFoldPolicy(name, unicodeCharacterClass));
    }

    int eq = name.indexOf('=');
    if (eq >= 0) {
      return lookupKeywordProperty(name.substring(0, eq), name.substring(eq + 1));
    }

    if (name.startsWith("Is") && name.length() > 2) {
      String stripped = name.substring(2);
      table = UnicodeProperties.lookupScriptOrCategory(stripped);
      if (table != null) {
        return new Group(table, scriptOrCategoryCaseFoldPolicy(stripped));
      }
      table = UnicodeProperties.lookupBinaryProperty(stripped);
      return table == null ? null : new Group(table, binaryPropertyCaseFoldPolicy(stripped));
    }

    if (name.startsWith("In") && name.length() > 2) {
      table = UnicodeProperties.lookupBlock(name.substring(2));
      return table == null ? null : new Group(table, CaseFoldPolicy.NONE);
    }

    table = UnicodeProperties.lookupCategory(name);
    return table == null ? null : new Group(table, categoryCaseFoldPolicy(name));
  }

  static void addPositiveGroup(CharClassBuilder ccb, Group group, int parseFlags) {
    if ((parseFlags & ParseFlags.FOLD_CASE) == 0) {
      addPropertyTableWithoutCaseFolding(ccb, group.table(), parseFlags);
      return;
    }
    switch (group.caseFoldPolicy()) {
      case ASCII_POSIX -> addAsciiFoldedPropertyTable(ccb, group.table(), parseFlags);
      case UNICODE_CASED_LETTER_CATEGORY -> addCasedLetterCategoryClosure(ccb);
      case JAVA_CASED_PROPERTY -> ccb.addTable(JavaCasedPropertyClosureHolder.TABLE);
      case NONE -> addPropertyTableWithoutCaseFolding(ccb, group.table(), parseFlags);
    }
  }

  static void addNegatedGroup(CharClassBuilder ccb, Group group, int parseFlags) {
    if ((parseFlags & ParseFlags.FOLD_CASE) != 0) {
      CharClassBuilder positive = new CharClassBuilder();
      addPositiveGroup(positive, group, parseFlags);
      boolean cutnl =
          (parseFlags & ParseFlags.CLASS_NL) == 0 || (parseFlags & ParseFlags.NEVER_NL) != 0;
      if (cutnl) {
        positive.addRune('\n');
      }
      positive.negate();
      ccb.addCharClass(positive);
      return;
    }
    int next = 0;
    for (int[] row : group.table()) {
      if (next < row[0]) {
        addRange(ccb, next, row[0] - 1, parseFlags);
      }
      next = row[1] + 1;
    }
    if (next <= Utils.MAX_RUNE) {
      addRange(ccb, next, Utils.MAX_RUNE, parseFlags);
    }
  }

  static void addRange(CharClassBuilder ccb, int lo, int hi, int parseFlags) {
    boolean cutnl =
        (parseFlags & ParseFlags.CLASS_NL) == 0 || (parseFlags & ParseFlags.NEVER_NL) != 0;
    if (cutnl && lo <= '\n' && hi >= '\n') {
      if (lo < '\n') {
        addRange(ccb, lo, '\n' - 1, parseFlags);
      }
      if (hi > '\n') {
        addRange(ccb, '\n' + 1, hi, parseFlags);
      }
      return;
    }

    if ((parseFlags & ParseFlags.FOLD_CASE) != 0) {
      if ((parseFlags & ParseFlags.UNICODE_CASE) == 0) {
        UnicodeCaseFolding.addAsciiFoldedRange(ccb, lo, hi);
        return;
      }
      UnicodeCaseFolding.addUnicodeFoldedRange(ccb, lo, hi);
    } else {
      ccb.addRange(lo, hi);
    }
  }

  private static Group lookupKeywordProperty(String key, String value) {
    String normalizedKey = normalizePropertyName(key);
    return switch (normalizedKey) {
      case "SCRIPT", "SC" -> {
        int[][] table = UnicodeProperties.lookupScript(value);
        yield table == null ? null : new Group(table, CaseFoldPolicy.NONE);
      }
      case "BLOCK", "BLK" -> {
        int[][] table = UnicodeProperties.lookupBlock(value);
        yield table == null ? null : new Group(table, CaseFoldPolicy.NONE);
      }
      case "GENERALCATEGORY", "GC" -> {
        int[][] table = UnicodeProperties.lookupCategory(value);
        yield table == null ? null : new Group(table, categoryCaseFoldPolicy(value));
      }
      default -> null;
    };
  }

  private static void addPropertyTableWithoutCaseFolding(
      CharClassBuilder ccb, int[][] table, int parseFlags) {
    int noFoldFlags = parseFlags & ~ParseFlags.FOLD_CASE;
    if ((noFoldFlags & ParseFlags.CLASS_NL) != 0 && (noFoldFlags & ParseFlags.NEVER_NL) == 0) {
      ccb.addTable(table);
      return;
    }
    for (int[] row : table) {
      addRange(ccb, row[0], row[1], noFoldFlags);
    }
  }

  private static void addAsciiFoldedPropertyTable(
      CharClassBuilder ccb, int[][] table, int parseFlags) {
    int asciiFoldFlags = parseFlags & ~ParseFlags.UNICODE_CASE;
    for (int[] row : table) {
      addRange(ccb, row[0], row[1], asciiFoldFlags);
    }
  }

  private static void addCasedLetterCategoryClosure(CharClassBuilder ccb) {
    ccb.addTable(UnicodeTables.UNICODE_GROUPS.get("Lu"));
    ccb.addTable(UnicodeTables.UNICODE_GROUPS.get("Ll"));
    ccb.addTable(UnicodeTables.UNICODE_GROUPS.get("Lt"));
  }

  private static CaseFoldPolicy posixPropertyCaseFoldPolicy(
      String name, boolean unicodeCharacterClass) {
    if (unicodeCharacterClass) {
      return switch (name) {
        case "Lower", "Upper" -> CaseFoldPolicy.JAVA_CASED_PROPERTY;
        default -> CaseFoldPolicy.NONE;
      };
    }
    return CaseFoldPolicy.ASCII_POSIX;
  }

  private static CaseFoldPolicy javaClassCaseFoldPolicy(String name) {
    return switch (name) {
      case "javaUpperCase", "javaLowerCase", "javaTitleCase" -> CaseFoldPolicy.JAVA_CASED_PROPERTY;
      default -> CaseFoldPolicy.NONE;
    };
  }

  private static CaseFoldPolicy scriptOrCategoryCaseFoldPolicy(String name) {
    return isCasedLetterCategoryName(name)
        ? CaseFoldPolicy.UNICODE_CASED_LETTER_CATEGORY
        : CaseFoldPolicy.NONE;
  }

  private static CaseFoldPolicy categoryCaseFoldPolicy(String name) {
    return switch (name) {
      case "Lu", "Ll", "Lt" -> CaseFoldPolicy.UNICODE_CASED_LETTER_CATEGORY;
      default -> CaseFoldPolicy.NONE;
    };
  }

  private static CaseFoldPolicy binaryPropertyCaseFoldPolicy(String name) {
    String normalized = normalizePropertyName(name);
    return switch (normalized) {
      case "UPPERCASE", "LOWERCASE", "TITLECASE" -> CaseFoldPolicy.JAVA_CASED_PROPERTY;
      default -> CaseFoldPolicy.NONE;
    };
  }

  private static boolean isCasedLetterCategoryName(String name) {
    return switch (normalizePropertyName(name)) {
      case "LU", "LL", "LT" -> true;
      default -> false;
    };
  }

  private static String normalizePropertyName(String name) {
    return name.toUpperCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
  }

  private static final class JavaCasedPropertyClosureHolder {
    static final int[][] TABLE = buildTable();

    private static int[][] buildTable() {
      CharClassBuilder ccb = new CharClassBuilder();
      for (int cp = 0; cp <= Utils.MAX_RUNE; cp++) {
        if (Character.isUpperCase(cp) || Character.isLowerCase(cp) || Character.isTitleCase(cp)) {
          ccb.addRune(cp);
        }
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
}
