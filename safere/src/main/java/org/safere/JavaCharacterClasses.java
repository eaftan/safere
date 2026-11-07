// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Character class tables for the {@code \p{java...}} property classes supported by {@code
 * java.util.regex.Pattern}.
 *
 * <p>Each class matches the code points for which the corresponding {@code java.lang.Character}
 * predicate returns {@code true} (e.g., {@code \p{javaLowerCase}} matches code points where {@link
 * Character#isLowerCase(int)} returns {@code true}), as of the Unicode version of SafeRE's bundled
 * tables ({@link UnicodeGeneratedTables#UNICODE_DATA_VERSION}). The tables are composed from the
 * bundled general categories and binary properties, so they don't depend on the running JDK: on a
 * JDK with an older Unicode version, they can disagree with {@code Character} about code points
 * assigned or changed since that version.
 */
final class JavaCharacterClasses {
  private JavaCharacterClasses() {}

  private static final class LazyTable {
    private final Supplier<int[][]> supplier;
    private volatile int[][] ranges;

    LazyTable(Supplier<int[][]> supplier) {
      this.supplier = supplier;
    }

    int[][] ranges() {
      int[][] result = ranges;
      if (result == null) {
        synchronized (this) {
          result = ranges;
          if (result == null) {
            result = supplier.get();
            ranges = result;
          }
        }
      }
      return result;
    }
  }

  /** {@code Character.isISOControl}: U+0000..U+001F and U+007F..U+009F. */
  private static final int[][] ISO_CONTROL = {{0x00, 0x1F}, {0x7F, 0x9F}};

  /**
   * The non-whitespace ISO controls that {@code Character.isIdentifierIgnorable} accepts, in
   * addition to the format characters (Cf).
   */
  private static final int[][] IGNORABLE_CONTROLS = {{0x00, 0x08}, {0x0E, 0x1B}, {0x7F, 0x9F}};

  /** The controls that {@code Character.isWhitespace} accepts, in addition to Zl, Zp, and Zs. */
  private static final int[][] WHITESPACE_CONTROLS = {{0x09, 0x0D}, {0x1C, 0x1F}};

  /** The no-break space separators that {@code Character.isWhitespace} excludes. */
  private static final int[] NO_BREAK_SPACES = {0x00A0, 0x2007, 0x202F};

  /**
   * Other_ID_Start: the code points that {@code Character.isUnicodeIdentifierStart} accepts outside
   * L and Nl.
   */
  private static final int[][] OTHER_ID_START = {
    {0x1885, 0x1886}, {0x2118, 0x2118}, {0x212E, 0x212E}, {0x309B, 0x309C},
  };

  /**
   * Other_ID_Start and Other_ID_Continue: the code points that {@code
   * Character.isUnicodeIdentifierPart} accepts outside L, Nl, Pc, Nd, Mc, Mn, and the identifier
   * ignorables.
   */
  private static final int[][] OTHER_ID_PART = {
    {0x00B7, 0x00B7},
    {0x0387, 0x0387},
    {0x1369, 0x1371},
    {0x19DA, 0x19DA},
    {0x2118, 0x2118},
    {0x212E, 0x212E},
    {0x309B, 0x309C},
    {0x30FB, 0x30FB},
    {0xFF65, 0xFF65},
  };

  // Lazy holder pattern: the name index is computed on first access, and each range table is
  // computed only when that specific java character class is requested.
  private static final class Holder {
    static final Map<String, LazyTable> JAVA_GROUPS = buildIndex();

    private static Map<String, LazyTable> buildIndex() {
      return Map.ofEntries(
          entry("javaLowerCase", () -> property("Lowercase")),
          entry("javaUpperCase", () -> property("Uppercase")),
          entry("javaTitleCase", () -> property("Titlecase")),
          entry("javaAlphabetic", () -> property("Alphabetic")),
          entry("javaIdeographic", () -> property("Ideographic")),
          entry("javaDefined", () -> property("Assigned")),
          entry("javaLetter", () -> property("Letter")),
          entry("javaDigit", () -> property("Digit")),
          entry(
              "javaLetterOrDigit",
              () -> UnicodeTables.mergeRangeTables(property("Letter"), property("Digit"))),
          entry("javaSpaceChar", () -> category("Z")),
          entry("javaWhitespace", JavaCharacterClasses::whitespace),
          entry("javaISOControl", () -> ISO_CONTROL),
          entry("javaIdentifierIgnorable", JavaCharacterClasses::identifierIgnorable),
          entry("javaJavaIdentifierStart", JavaCharacterClasses::javaIdentifierStart),
          entry(
              "javaJavaIdentifierPart",
              () ->
                  UnicodeTables.mergeRangeTables(
                      javaIdentifierStart(),
                      category("Nd"),
                      category("Mc"),
                      category("Mn"),
                      identifierIgnorable())),
          entry(
              "javaUnicodeIdentifierStart",
              () ->
                  UnicodeTables.mergeRangeTables(
                      property("Letter"), category("Nl"), OTHER_ID_START)),
          entry(
              "javaUnicodeIdentifierPart",
              () ->
                  UnicodeTables.mergeRangeTables(
                      property("Letter"),
                      category("Nl"),
                      category("Pc"),
                      category("Nd"),
                      category("Mc"),
                      category("Mn"),
                      identifierIgnorable(),
                      OTHER_ID_PART)),
          entry(
              "javaMirrored",
              () ->
                  requireNonNull(
                      UnicodeGeneratedTables.JAVA_CHARACTER_CLASSES.get("javaMirrored"))));
    }

    private static Map.Entry<String, LazyTable> entry(String name, Supplier<int[][]> supplier) {
      return Map.entry(name, new LazyTable(supplier));
    }
  }

  private static int[][] property(String name) {
    return requireNonNull(UnicodeProperties.lookupBinaryProperty(name), name);
  }

  private static int[][] category(String name) {
    return requireNonNull(UnicodeProperties.lookupCategory(name), name);
  }

  /** {@code Character.isWhitespace}: Zl, Zp, and Zs other than the no-break spaces. */
  private static int[][] whitespace() {
    return UnicodeTables.mergeRangeTables(
        category("Zl"),
        category("Zp"),
        withoutCodePoints(category("Zs"), NO_BREAK_SPACES),
        WHITESPACE_CONTROLS);
  }

  /** {@code Character.isIdentifierIgnorable}: Cf plus some ISO controls. */
  private static int[][] identifierIgnorable() {
    return UnicodeTables.mergeRangeTables(category("Cf"), IGNORABLE_CONTROLS);
  }

  /** {@code Character.isJavaIdentifierStart}: L, Nl, Sc, and Pc. */
  private static int[][] javaIdentifierStart() {
    return UnicodeTables.mergeRangeTables(
        property("Letter"), category("Nl"), category("Sc"), category("Pc"));
  }

  /** Returns {@code table} without the given sorted code points. */
  private static int[][] withoutCodePoints(int[][] table, int[] codePoints) {
    int[][] result = table;
    for (int cp : codePoints) {
      result = withoutCodePoint(result, cp);
    }
    return result;
  }

  private static int[][] withoutCodePoint(int[][] table, int cp) {
    int[][] result = new int[table.length + 1][];
    int count = 0;
    for (int[] range : table) {
      if (cp < range[0] || cp > range[1]) {
        result[count++] = range;
        continue;
      }
      if (range[0] < cp) {
        result[count++] = new int[] {range[0], cp - 1};
      }
      if (cp < range[1]) {
        result[count++] = new int[] {cp + 1, range[1]};
      }
    }
    return Arrays.copyOf(result, count);
  }

  /**
   * Returns the range table for the given java character class name, or {@code null} if the name is
   * not a java character class.
   */
  static int[][] lookup(String name) {
    if (!name.startsWith("java")) {
      return null;
    }
    LazyTable table = Holder.JAVA_GROUPS.get(name);
    return table == null ? null : table.ranges();
  }
}
