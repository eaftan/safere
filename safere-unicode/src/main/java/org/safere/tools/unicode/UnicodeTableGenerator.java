// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.tools.unicode;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntPredicate;

/** Generates checked-in Unicode tables from the JDK {@link Character} implementation. */
public final class UnicodeTableGenerator {
  private static final int MAX_CODE_POINT = Character.MAX_CODE_POINT;
  private static final String DEFAULT_OUTPUT =
      "safere/src/main/java/org/safere/UnicodeGeneratedTables.java";

  private static final String[] CATEGORY_ABBREVS = {
    "Cn", "Lu", "Ll", "Lt", "Lm", "Lo", "Mn", "Me", "Mc", "Nd", "Nl", "No", "Zs", "Zl", "Zp", "Cc",
    "Cf", null, "Co", "Cs", "Pd", "Ps", "Pe", "Pc", "Po", "Sm", "Sc", "Sk", "So", "Pi", "Pf"
  };

  private static final int[][] MAJOR_CATEGORY_TYPES = {
    {1, 2, 3, 4, 5},
    {6, 7, 8},
    {9, 10, 11},
    {20, 21, 22, 23, 24, 29, 30},
    {25, 26, 27, 28},
    {12, 13, 14},
    {15, 16, 18, 19},
  };

  private static final String[] MAJOR_CATEGORY_NAMES = {"L", "M", "N", "P", "S", "Z", "C"};

  private UnicodeTableGenerator() {}

  public static void main(String[] args) throws IOException {
    Path output = args.length >= 1 ? Path.of(args[0]) : Path.of(DEFAULT_OUTPUT);
    if (args.length > 1) {
      throw new IllegalArgumentException("Usage: UnicodeTableGenerator [output-file]");
    }

    GeneratedTables tables = buildTables();
    Files.createDirectories(output.getParent());
    try (PrintWriter out =
        new PrintWriter(Files.newBufferedWriter(output, StandardCharsets.UTF_8))) {
      writeJava(out, tables);
    }
  }

  private static GeneratedTables buildTables() {
    int[][][] categoryTables = buildCategoryTables();
    Map<String, int[][]> categories = new LinkedHashMap<>();
    for (int i = 1; i < CATEGORY_ABBREVS.length; i++) {
      if (CATEGORY_ABBREVS[i] != null && categoryTables[i].length > 0) {
        categories.put(CATEGORY_ABBREVS[i], categoryTables[i]);
      }
    }
    for (int i = 0; i < MAJOR_CATEGORY_NAMES.length; i++) {
      categories.put(
          MAJOR_CATEGORY_NAMES[i], mergeSubcategories(categoryTables, MAJOR_CATEGORY_TYPES[i]));
    }

    return new GeneratedTables(
        categories, buildScriptTables(), buildBlockTables(), buildBinaryPropertyTables());
  }

  private static int[][][] buildCategoryTables() {
    RangeBuilder[] builders = new RangeBuilder[CATEGORY_ABBREVS.length];
    for (int i = 0; i < builders.length; i++) {
      builders[i] = new RangeBuilder();
    }

    for (int cp = 0; cp <= MAX_CODE_POINT; cp++) {
      int type = Character.getType(cp);
      if (type >= 0 && type < builders.length) {
        builders[type].add(cp);
      }
    }

    int[][][] tables = new int[builders.length][][];
    for (int i = 0; i < builders.length; i++) {
      tables[i] = builders[i].build();
    }
    return tables;
  }

  private static Map<String, int[][]> buildScriptTables() {
    Map<Character.UnicodeScript, RangeBuilder> builders =
        new EnumMap<>(Character.UnicodeScript.class);
    for (int cp = 0; cp <= MAX_CODE_POINT; cp++) {
      Character.UnicodeScript script = Character.UnicodeScript.of(cp);
      builders.computeIfAbsent(script, unused -> new RangeBuilder()).add(cp);
    }

    Map<String, int[][]> tables = new LinkedHashMap<>();
    for (Character.UnicodeScript script : Character.UnicodeScript.values()) {
      RangeBuilder builder = builders.get(script);
      if (builder != null) {
        tables.put(scriptName(script), builder.build());
      }
    }
    return tables;
  }

  private static Map<String, int[][]> buildBlockTables() {
    Map<Character.UnicodeBlock, RangeBuilder> builders = new LinkedHashMap<>();
    for (int cp = 0; cp <= MAX_CODE_POINT; cp++) {
      Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
      if (block != null) {
        builders.computeIfAbsent(block, unused -> new RangeBuilder()).add(cp);
      }
    }

    Map<String, int[][]> tables = new LinkedHashMap<>();
    List<Character.UnicodeBlock> blocks = new ArrayList<>(builders.keySet());
    blocks.sort((a, b) -> blockName(a).compareTo(blockName(b)));
    for (Character.UnicodeBlock block : blocks) {
      tables.put(blockName(block), builders.get(block).build());
    }
    return tables;
  }

  private static Map<String, int[][]> buildBinaryPropertyTables() {
    Map<String, IntPredicate> predicates = new LinkedHashMap<>();
    predicates.put("Alphabetic", Character::isAlphabetic);
    predicates.put("Ideographic", Character::isIdeographic);
    predicates.put("Letter", Character::isLetter);
    predicates.put("Lowercase", Character::isLowerCase);
    predicates.put("Uppercase", Character::isUpperCase);
    predicates.put("Titlecase", Character::isTitleCase);
    predicates.put("Punctuation", UnicodeTableGenerator::isPunctuation);
    predicates.put("Control", cp -> Character.getType(cp) == Character.CONTROL);
    predicates.put("White_Space", cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp));
    predicates.put("Digit", Character::isDigit);
    predicates.put("Hex_Digit", UnicodeTableGenerator::isHexDigit);
    predicates.put("Join_Control", cp -> cp == 0x200C || cp == 0x200D);
    predicates.put("Noncharacter_Code_Point", UnicodeTableGenerator::isNoncharacterCodePoint);
    predicates.put("Assigned", Character::isDefined);
    predicates.put("Emoji", Character::isEmoji);
    predicates.put("Emoji_Presentation", Character::isEmojiPresentation);
    predicates.put("Emoji_Modifier", Character::isEmojiModifier);
    predicates.put("Emoji_Modifier_Base", Character::isEmojiModifierBase);
    predicates.put("Emoji_Component", Character::isEmojiComponent);
    predicates.put("Extended_Pictographic", Character::isExtendedPictographic);

    Map<String, int[][]> tables = new LinkedHashMap<>();
    for (Map.Entry<String, IntPredicate> entry : predicates.entrySet()) {
      tables.put(entry.getKey(), buildRanges(entry.getValue()));
    }
    return tables;
  }

  private static void writeJava(PrintWriter out, GeneratedTables tables) {
    String header =
        """
        // This file is part of a Java port of RE2 (https://github.com/google/re2).
        // Original RE2 code is Copyright (c) 2009 The RE2 Authors.
        // Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
        // Licensed under the BSD 3-Clause License (see LICENSE file).

        package org.safere;

        import java.util.Collections;
        import java.util.LinkedHashMap;
        import java.util.Map;

        /** Checked-in Unicode tables generated from a maintainer-selected JDK. */
        final class UnicodeGeneratedTables {
          static final String GENERATOR_JAVA_VERSION = "%s";
        """
            .formatted(javaVersion());
    out.println(header);
    out.println();

    writeInlineMap(out, "CATEGORIES", tables.categories(), "category");
    writeInlineMap(out, "SCRIPTS", tables.scripts(), "script");
    writeInlineMap(out, "BLOCKS", tables.blocks(), "block");
    writeInlineMap(out, "BINARY_PROPERTIES", tables.binaryProperties(), "property");

    String middle =
        """
          private UnicodeGeneratedTables() {}

          static Map<String, int[][]> unicodeGroups() {
            Map<String, int[][]> groups = new LinkedHashMap<>();
            groups.putAll(CATEGORIES);
            groups.putAll(SCRIPTS);
            return Collections.unmodifiableMap(groups);
          }
        """;
    out.println(middle);
    out.println();

    writeTableMethods(out, "category", tables.categories());
    writeTableMethods(out, "script", tables.scripts());
    writeTableMethods(out, "property", tables.binaryProperties());

    out.println("}");
  }

  private static void writeInlineMap(
      PrintWriter out, String name, Map<String, int[][]> tables, String prefix) {
    // Include explicit type arguments to ofEntries to avoid JDK-8221301
    out.println(
        "  static final Map<String, int[][]> " + name + " = Map.<String, int[][]>ofEntries(");
    int count = 0;
    for (Map.Entry<String, int[][]> entry : tables.entrySet()) {
      String key = entry.getKey();
      String entryStr;
      if (prefix.equals("block")) {
        int[][] ranges = entry.getValue();
        entryStr =
            "    Map.entry(\"%s\", new int[][] {{0x%x, 0x%x}})"
                .formatted(key, ranges[0][0], ranges[0][1]);
      } else {
        entryStr = "    Map.entry(\"%s\", %s_%s())".formatted(key, prefix, safeIdentifier(key));
      }
      out.print(entryStr);
      if (++count < tables.size()) {
        out.println(",");
      } else {
        out.println();
      }
    }
    out.println("  );");
    out.println();
  }

  private static void writeTableMethods(
      PrintWriter out, String prefix, Map<String, int[][]> tables) {
    for (Map.Entry<String, int[][]> entry : tables.entrySet()) {
      String name = entry.getKey();
      int[][] ranges = entry.getValue();
      out.println("  private static int[][] %s_%s() {".formatted(prefix, safeIdentifier(name)));
      out.println("    return new int[][] {");
      for (int[] range : ranges) {
        out.println("      {0x%x, 0x%x},".formatted(range[0], range[1]));
      }
      out.println("    };");
      out.println("  }");
      out.println();
    }
  }

  private static String safeIdentifier(String name) {
    return name.replaceAll("[^a-zA-Z0-9_]", "_");
  }

  private static int[][] buildRanges(IntPredicate predicate) {
    RangeBuilder builder = new RangeBuilder();
    for (int cp = 0; cp <= MAX_CODE_POINT; cp++) {
      if (predicate.test(cp)) {
        builder.add(cp);
      }
    }
    return builder.build();
  }

  private static int[][] mergeSubcategories(int[][][] allTables, int[] types) {
    List<int[]> ranges = new ArrayList<>();
    for (int type : types) {
      Collections.addAll(ranges, allTables[type]);
    }
    ranges.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
    RangeBuilder builder = new RangeBuilder();
    for (int[] range : ranges) {
      builder.addRange(range[0], range[1]);
    }
    return builder.build();
  }

  private static boolean isPunctuation(int cp) {
    int type = Character.getType(cp);
    return type == Character.CONNECTOR_PUNCTUATION
        || type == Character.DASH_PUNCTUATION
        || type == Character.START_PUNCTUATION
        || type == Character.END_PUNCTUATION
        || type == Character.OTHER_PUNCTUATION
        || type == Character.INITIAL_QUOTE_PUNCTUATION
        || type == Character.FINAL_QUOTE_PUNCTUATION;
  }

  private static boolean isHexDigit(int cp) {
    return (cp >= '0' && cp <= '9')
        || (cp >= 'A' && cp <= 'F')
        || (cp >= 'a' && cp <= 'f')
        || (cp >= 0xFF10 && cp <= 0xFF19)
        || (cp >= 0xFF21 && cp <= 0xFF26)
        || (cp >= 0xFF41 && cp <= 0xFF46);
  }

  private static boolean isNoncharacterCodePoint(int cp) {
    return (cp >= 0xFDD0 && cp <= 0xFDEF)
        || ((cp & 0xFFFE) == 0xFFFE && cp <= Character.MAX_CODE_POINT);
  }

  private static String scriptName(Character.UnicodeScript script) {
    if (script == Character.UnicodeScript.SIGNWRITING) {
      return "SignWriting";
    }
    return toTitleSnake(script.name());
  }

  private static String blockName(Character.UnicodeBlock block) {
    return toTitleSnake(block.toString());
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

  private static String javaVersion() {
    return System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")";
  }

  private record GeneratedTables(
      Map<String, int[][]> categories,
      Map<String, int[][]> scripts,
      Map<String, int[][]> blocks,
      Map<String, int[][]> binaryProperties) {}

  private static final class RangeBuilder {
    private final List<int[]> ranges = new ArrayList<>();

    void add(int cp) {
      addRange(cp, cp);
    }

    void addRange(int lo, int hi) {
      if (!ranges.isEmpty()) {
        int[] last = ranges.get(ranges.size() - 1);
        if (lo <= last[1] + 1) {
          last[1] = Math.max(last[1], hi);
          return;
        }
      }
      ranges.add(new int[] {lo, hi});
    }

    int[][] build() {
      return ranges.toArray(new int[0][]);
    }
  }
}
