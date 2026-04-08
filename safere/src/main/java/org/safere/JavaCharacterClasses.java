// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Character class tables for the {@code \p{java...}} property classes supported by {@code
 * java.util.regex.Pattern}.
 *
 * <p>These classes delegate to the corresponding {@code java.lang.Character} predicate methods
 * (e.g., {@code \p{javaLowerCase}} matches code points where {@link Character#isLowerCase(int)}
 * returns {@code true}). The range tables are computed at class-load time from the running JVM's
 * {@code Character} implementation, so they always match the JVM's Unicode version.
 */
final class JavaCharacterClasses {
  private JavaCharacterClasses() {}

  // Lazy holder pattern — tables are computed on first access, then cached.
  private static final class Holder {
    static final Map<String, int[][]> JAVA_GROUPS = buildAll();

    private static Map<String, int[][]> buildAll() {
      return Map.ofEntries(
          entry("javaLowerCase", Character::isLowerCase),
          entry("javaUpperCase", Character::isUpperCase),
          entry("javaWhitespace", Character::isWhitespace),
          entry("javaMirrored", Character::isMirrored),
          entry("javaAlphabetic", Character::isAlphabetic),
          entry("javaIdeographic", Character::isIdeographic),
          entry("javaTitleCase", Character::isTitleCase),
          entry("javaDigit", Character::isDigit),
          entry("javaDefined", Character::isDefined),
          entry("javaLetter", Character::isLetter),
          entry("javaLetterOrDigit", Character::isLetterOrDigit),
          entry("javaSpaceChar", Character::isSpaceChar),
          entry("javaISOControl", Character::isISOControl),
          entry("javaIdentifierIgnorable", Character::isIdentifierIgnorable),
          entry("javaUnicodeIdentifierStart", Character::isUnicodeIdentifierStart),
          entry("javaUnicodeIdentifierPart", Character::isUnicodeIdentifierPart),
          entry("javaJavaIdentifierStart", cp -> Character.isJavaIdentifierStart(cp)),
          entry("javaJavaIdentifierPart", cp -> Character.isJavaIdentifierPart(cp)));
    }

    private static Map.Entry<String, int[][]> entry(String name, IntPredicate predicate) {
      return Map.entry(name, buildRanges(predicate));
    }
  }

  /**
   * Returns the map of java character class names to their range tables, or {@code null} if the
   * name does not start with {@code "java"}.
   */
  static int[][] lookup(String name) {
    if (!name.startsWith("java")) {
      return null;
    }
    return Holder.JAVA_GROUPS.get(name);
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
