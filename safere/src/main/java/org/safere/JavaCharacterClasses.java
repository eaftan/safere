// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

  private record JavaClass(String name, IntPredicate predicate) {
    JavaClass {
      Objects.requireNonNull(name);
      Objects.requireNonNull(predicate);
    }
  }

  private static final class LazyTable {
    private final IntPredicate predicate;
    private volatile int[][] ranges;

    LazyTable(IntPredicate predicate) {
      this.predicate = predicate;
    }

    int[][] ranges() {
      int[][] result = ranges;
      if (result == null) {
        synchronized (this) {
          result = ranges;
          if (result == null) {
            result = buildRanges(predicate);
            ranges = result;
          }
        }
      }
      return result;
    }
  }

  // Lazy holder pattern — the name index is computed on first access, and each range table is
  // computed only when that specific java character class is requested.
  private static final class Holder {
    static final Map<String, LazyTable> JAVA_GROUPS = buildIndex();

    private static Map<String, LazyTable> buildIndex() {
      return Map.ofEntries(
          entry(new JavaClass("javaLowerCase", Character::isLowerCase)),
          entry(new JavaClass("javaUpperCase", Character::isUpperCase)),
          entry(new JavaClass("javaWhitespace", Character::isWhitespace)),
          entry(new JavaClass("javaMirrored", Character::isMirrored)),
          entry(new JavaClass("javaAlphabetic", Character::isAlphabetic)),
          entry(new JavaClass("javaIdeographic", Character::isIdeographic)),
          entry(new JavaClass("javaTitleCase", Character::isTitleCase)),
          entry(new JavaClass("javaDigit", Character::isDigit)),
          entry(new JavaClass("javaDefined", Character::isDefined)),
          entry(new JavaClass("javaLetter", Character::isLetter)),
          entry(new JavaClass("javaLetterOrDigit", Character::isLetterOrDigit)),
          entry(new JavaClass("javaSpaceChar", Character::isSpaceChar)),
          entry(new JavaClass("javaISOControl", Character::isISOControl)),
          entry(new JavaClass("javaIdentifierIgnorable", Character::isIdentifierIgnorable)),
          entry(new JavaClass("javaUnicodeIdentifierStart", Character::isUnicodeIdentifierStart)),
          entry(new JavaClass("javaUnicodeIdentifierPart", Character::isUnicodeIdentifierPart)),
          entry(new JavaClass("javaJavaIdentifierStart", Character::isJavaIdentifierStart)),
          entry(new JavaClass("javaJavaIdentifierPart", Character::isJavaIdentifierPart)));
    }

    private static Map.Entry<String, LazyTable> entry(JavaClass javaClass) {
      return Map.entry(javaClass.name(), new LazyTable(javaClass.predicate()));
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
    LazyTable table = Holder.JAVA_GROUPS.get(name);
    return table == null ? null : table.ranges();
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
