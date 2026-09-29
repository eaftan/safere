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

/** Generates checked-in Unicode tables from public JDK APIs and pinned Unicode grapheme data. */
public final class UnicodeTableGenerator {
  private static final int MAX_CODE_POINT = Character.MAX_CODE_POINT;
  private static final String DEFAULT_OUTPUT =
      "safere/src/main/java/org/safere/UnicodeGeneratedTables.java";
  private static final Path DEFAULT_UNICODE_DATA =
      Path.of("safere-unicode/data/" + GraphemeTableGenerator.DEFAULT_UNICODE_VERSION);
  private static final String USAGE =
      """
      Usage: UnicodeTableGenerator [options] [output-file]

      Options:
        --unicode-data=DIR                 flat directory containing all of the files below
                                           (default: %s)
        --grapheme-break-property=FILE     GraphemeBreakProperty.txt
        --derived-core-properties=FILE     DerivedCoreProperties.txt
        --emoji-data=FILE                  emoji-data.txt
        --unicode-license=FILE             Unicode license text (LICENSE.txt)
        --unicode-version=VERSION          Unicode version the files must declare
                                           (default: %s)
      """
          .formatted(DEFAULT_UNICODE_DATA, GraphemeTableGenerator.DEFAULT_UNICODE_VERSION);

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
    {0, 15, 16, 18, 19},
  };

  private static final String[] MAJOR_CATEGORY_NAMES = {"L", "M", "N", "P", "S", "Z", "C"};

  private UnicodeTableGenerator() {}

  public static void main(String[] args) throws IOException {
    Options options = Options.parse(args);
    GeneratedTables tables = buildTables(options);
    Path output = options.output().toAbsolutePath();
    Files.createDirectories(output.getParent());
    try (PrintWriter out =
        new PrintWriter(Files.newBufferedWriter(output, StandardCharsets.UTF_8))) {
      writeJava(out, tables);
    }
  }

  /** Command-line options; every Unicode input defaults to the checked-in copy. */
  record Options(
      Path output,
      GraphemeTableGenerator.Sources sources,
      Path unicodeLicense,
      String unicodeVersion) {
    static Options parse(String... args) {
      Path output = null;
      Path data = DEFAULT_UNICODE_DATA;
      Path graphemeBreakProperty = null;
      Path derivedCoreProperties = null;
      Path emojiData = null;
      Path unicodeLicense = null;
      String unicodeVersion = GraphemeTableGenerator.DEFAULT_UNICODE_VERSION;
      for (String arg : args) {
        if (!arg.startsWith("--")) {
          if (output != null) {
            throw new IllegalArgumentException(USAGE);
          }
          output = Path.of(arg);
          continue;
        }
        int equals = arg.indexOf('=');
        if (equals < 0 || equals == arg.length() - 1) {
          throw new IllegalArgumentException(USAGE);
        }
        String value = arg.substring(equals + 1);
        switch (arg.substring(0, equals)) {
          case "--unicode-data" -> data = Path.of(value);
          case "--grapheme-break-property" -> graphemeBreakProperty = Path.of(value);
          case "--derived-core-properties" -> derivedCoreProperties = Path.of(value);
          case "--emoji-data" -> emojiData = Path.of(value);
          case "--unicode-license" -> unicodeLicense = Path.of(value);
          case "--unicode-version" -> unicodeVersion = value;
          default -> throw new IllegalArgumentException(USAGE);
        }
      }
      GraphemeTableGenerator.Sources defaults = GraphemeTableGenerator.Sources.inDirectory(data);
      return new Options(
          output != null ? output : Path.of(DEFAULT_OUTPUT),
          new GraphemeTableGenerator.Sources(
              graphemeBreakProperty != null
                  ? graphemeBreakProperty
                  : defaults.graphemeBreakProperty(),
              derivedCoreProperties != null
                  ? derivedCoreProperties
                  : defaults.derivedCoreProperties(),
              emojiData != null ? emojiData : defaults.emojiData()),
          unicodeLicense != null ? unicodeLicense : data.resolve("LICENSE.txt"),
          unicodeVersion);
    }
  }

  private static GeneratedTables buildTables(Options options) throws IOException {
    GraphemeTableGenerator.Result grapheme =
        GraphemeTableGenerator.generate(options.sources(), options.unicodeVersion());
    Map<String, int[][]> graphemeData = grapheme.tables();
    int[][][] categoryTables = buildCategoryTables();
    Map<String, int[][]> categories = new LinkedHashMap<>();
    for (int i = 0; i < CATEGORY_ABBREVS.length; i++) {
      if (CATEGORY_ABBREVS[i] != null && categoryTables[i].length > 0) {
        categories.put(CATEGORY_ABBREVS[i], categoryTables[i]);
      }
    }
    for (int i = 0; i < MAJOR_CATEGORY_NAMES.length; i++) {
      categories.put(
          MAJOR_CATEGORY_NAMES[i], mergeSubcategories(categoryTables, MAJOR_CATEGORY_TYPES[i]));
    }

    return new GeneratedTables(
        grapheme.unicodeVersion(),
        Files.readAllLines(options.unicodeLicense(), StandardCharsets.UTF_8),
        categories,
        buildScriptTables(),
        buildBlockTables(),
        buildBinaryPropertyTables(graphemeData),
        buildGraphemeTables(graphemeData));
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

  private static Map<String, int[][]> buildBinaryPropertyTables(Map<String, int[][]> graphemeData) {
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

    Map<String, int[][]> tables = new LinkedHashMap<>();
    for (Map.Entry<String, IntPredicate> entry : predicates.entrySet()) {
      tables.put(entry.getKey(), buildRanges(entry.getValue()));
    }
    tables.put("Extended_Pictographic", graphemeData.get("EXTENDED_PICTOGRAPHIC"));
    return tables;
  }

  private static Map<String, int[][]> buildGraphemeTables(Map<String, int[][]> data) {
    Map<String, int[][]> tables = new LinkedHashMap<>();
    for (String name :
        List.of("Control", "Extend", "Prepend", "SpacingMark", "L", "V", "T", "LV", "LVT")) {
      tables.put(name, data.get("GCB_" + name.toUpperCase(Locale.ROOT)));
    }
    for (String name : List.of("Linker", "Consonant", "Extend")) {
      tables.put("InCB_" + name, data.get("INCB_" + name.toUpperCase(Locale.ROOT)));
    }
    return tables;
  }

  private static void writeJava(PrintWriter out, GeneratedTables tables) throws IOException {
    String header =
        """
        // This file is part of a Java port of RE2 (https://github.com/google/re2).
        // Original RE2 code is Copyright (c) 2009 The RE2 Authors.
        // Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
        // Licensed under the BSD 3-Clause License (see LICENSE file).
        """;
    String declaration =
        """

        // WARNING: This file is automatically generated. Do not edit by hand.
        // To regenerate, run:
        //   ./safere-unicode/generate-unicode-tables.sh

        package org.safere;

        import java.util.Collections;
        import java.util.LinkedHashMap;
        import java.util.Map;

        /** Checked-in Unicode tables generated from public JDK APIs and pinned Unicode data. */
        final class UnicodeGeneratedTables {
          static final String GENERATOR_JAVA_VERSION = "%s";
          static final String UNICODE_DATA_VERSION = "%s";
        """
            .formatted(javaVersion(), tables.unicodeDataVersion());
    out.print(header);
    out.println("// Unicode data copyright and permission notice:");
    for (String line : tables.unicodeLicense()) {
      out.println(line.isEmpty() ? "//" : "// " + line);
    }
    out.println();
    out.println(declaration);
    out.println();

    writeInlineMap(out, "CATEGORIES", tables.categories(), "category");
    writeInlineMap(out, "SCRIPTS", tables.scripts(), "script");
    writeInlineMap(out, "BLOCKS", tables.blocks(), "block");
    writeInlineMap(out, "BINARY_PROPERTIES", tables.binaryProperties(), "property");
    out.println(
        "  // Grapheme segmentation classes; internal only, not exposed as \\p{...} names.");
    writeInlineMap(out, "GRAPHEME_PROPERTIES", tables.graphemeProperties(), "grapheme");

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
    writeTableMethods(out, "grapheme", tables.graphemeProperties());

    out.println("}");
  }

  private static void writeInlineMap(
      PrintWriter out, String name, Map<String, int[][]> tables, String prefix) {
    out.println("  // Include explicit type arguments to ofEntries to avoid JDK-8221301");
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
    return Runtime.version() + " (" + System.getProperty("java.vendor") + ")";
  }

  private record GeneratedTables(
      String unicodeDataVersion,
      List<String> unicodeLicense,
      Map<String, int[][]> categories,
      Map<String, int[][]> scripts,
      Map<String, int[][]> blocks,
      Map<String, int[][]> binaryProperties,
      Map<String, int[][]> graphemeProperties) {}

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
