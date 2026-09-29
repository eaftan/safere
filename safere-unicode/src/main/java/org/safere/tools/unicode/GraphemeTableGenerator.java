// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.tools.unicode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds grapheme ranges from Unicode Character Database files, without JDK data. */
public final class GraphemeTableGenerator {
  /** The Unicode version SafeRE's grapheme tables are pinned to. */
  static final String DEFAULT_UNICODE_VERSION = "17.0.0";

  private static final Pattern GRAPHEME_BREAK_PROPERTY_HEADER =
      Pattern.compile("# GraphemeBreakProperty-(\\d+\\.\\d+\\.\\d+)\\.txt");
  private static final Pattern DERIVED_CORE_PROPERTIES_HEADER =
      Pattern.compile("# DerivedCoreProperties-(\\d+\\.\\d+\\.\\d+)\\.txt");
  private static final Pattern EMOJI_DATA_HEADER = Pattern.compile("# Version: (\\d+\\.\\d+)");

  private static final List<String> TABLE_NAMES =
      List.of(
          "GCB_CONTROL",
          "GCB_EXTEND",
          "GCB_PREPEND",
          "GCB_SPACINGMARK",
          "GCB_L",
          "GCB_V",
          "GCB_T",
          "GCB_LV",
          "GCB_LVT",
          "GCB_ZWJ",
          "GCB_REGIONAL_INDICATOR",
          "INCB_LINKER",
          "INCB_CONSONANT",
          "INCB_EXTEND",
          "EXTENDED_PICTOGRAPHIC");

  /**
   * Locations of the UCD files the grapheme tables are built from.
   *
   * <p>The files are passed individually because UCD distributions differ in layout: SafeRE's
   * checked-in copy is flat, while the published UCD keeps {@code GraphemeBreakProperty.txt} under
   * {@code auxiliary/} and {@code emoji-data.txt} under {@code emoji/}.
   */
  record Sources(Path graphemeBreakProperty, Path derivedCoreProperties, Path emojiData) {
    /** Returns the sources for a flat directory such as {@code safere-unicode/data/17.0.0}. */
    static Sources inDirectory(Path directory) {
      return new Sources(
          directory.resolve("GraphemeBreakProperty.txt"),
          directory.resolve("DerivedCoreProperties.txt"),
          directory.resolve("emoji-data.txt"));
    }
  }

  /** Generated grapheme ranges, keyed by table name, and the Unicode version they came from. */
  record Result(String unicodeVersion, Map<String, int[][]> tables) {}

  private enum Kind {
    GRAPHEME_BREAK_PROPERTY,
    DERIVED_CORE_PROPERTIES,
    EMOJI_DATA
  }

  private GraphemeTableGenerator() {}

  /**
   * Parses {@code sources} and returns the grapheme tables.
   *
   * @param expectedVersion the Unicode version (for example {@code 17.0.0}) that every file must
   *     declare in its header
   * @throws IllegalArgumentException if a file declares a different Unicode version, the files
   *     disagree with each other, or the data is malformed
   */
  static Result generate(Sources sources, String expectedVersion) throws IOException {
    Map<String, List<Range>> tables = new LinkedHashMap<>();
    for (String name : TABLE_NAMES) {
      tables.put(name, new ArrayList<>());
    }
    String version =
        read(
            sources.graphemeBreakProperty(),
            Kind.GRAPHEME_BREAK_PROPERTY,
            GRAPHEME_BREAK_PROPERTY_HEADER,
            tables);
    String derivedVersion =
        read(
            sources.derivedCoreProperties(),
            Kind.DERIVED_CORE_PROPERTIES,
            DERIVED_CORE_PROPERTIES_HEADER,
            tables);
    String emojiVersion = read(sources.emojiData(), Kind.EMOJI_DATA, EMOJI_DATA_HEADER, tables);
    if (!derivedVersion.equals(version) || !version.startsWith(emojiVersion + ".")) {
      throw new IllegalArgumentException(
          "Unicode data files disagree on version: %s declares %s, %s declares %s, %s declares %s"
              .formatted(
                  sources.graphemeBreakProperty(),
                  version,
                  sources.derivedCoreProperties(),
                  derivedVersion,
                  sources.emojiData(),
                  emojiVersion));
    }
    if (!version.equals(expectedVersion)) {
      throw new IllegalArgumentException(
          "Wrong Unicode version: expected %s but data files declare %s"
              .formatted(expectedVersion, version));
    }
    Map<String, int[][]> result = new LinkedHashMap<>();
    for (Map.Entry<String, List<Range>> entry : tables.entrySet()) {
      List<Range> ranges = merge(entry.getValue());
      if (ranges.isEmpty()) {
        throw new IllegalArgumentException("Missing Unicode property " + entry.getKey());
      }
      result.put(
          entry.getKey(),
          ranges.stream()
              .map(range -> new int[] {range.first(), range.last()})
              .toArray(int[][]::new));
    }
    return new Result(version, result);
  }

  /** Reads one UCD file into {@code tables} and returns the version declared in its header. */
  private static String read(
      Path path, Kind kind, Pattern versionHeader, Map<String, List<Range>> tables)
      throws IOException {
    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    String version = null;
    for (String line : lines) {
      Matcher matcher = versionHeader.matcher(line.strip());
      if (matcher.matches()) {
        version = matcher.group(1);
        break;
      }
    }
    if (version == null) {
      throw new IllegalArgumentException("Missing Unicode version header in " + path);
    }
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i).split("#", 2)[0].trim();
      if (line.isEmpty()) {
        continue;
      }
      String[] fields = line.split(";", -1);
      if (fields.length < 2) {
        throw new IllegalArgumentException(path + ":" + (i + 1) + ": missing property");
      }
      String property = fields[1].trim();
      String name;
      switch (kind) {
        case GRAPHEME_BREAK_PROPERTY -> {
          name =
              switch (property) {
                case "CR", "LF", "Control" -> "GCB_CONTROL";
                default -> "GCB_" + property.toUpperCase(Locale.ROOT);
              };
          if (!tables.containsKey(name)) {
            throw new IllegalArgumentException("Unknown grapheme property: " + property);
          }
        }
        case DERIVED_CORE_PROPERTIES -> {
          if (!property.equals("InCB")) {
            continue;
          }
          if (fields.length != 3) {
            throw new IllegalArgumentException("Missing InCB value at " + path + ":" + (i + 1));
          }
          name = "INCB_" + fields[2].trim().toUpperCase(Locale.ROOT);
          if (!tables.containsKey(name)) {
            throw new IllegalArgumentException("Unknown InCB value: " + fields[2]);
          }
        }
        case EMOJI_DATA -> {
          if (!property.equals("Extended_Pictographic")) {
            continue;
          }
          name = "EXTENDED_PICTOGRAPHIC";
        }
        default -> throw new AssertionError(kind);
      }
      String[] bounds = fields[0].trim().split("\\.\\.", -1);
      if (bounds.length > 2) {
        throw new IllegalArgumentException("Invalid range at " + path + ":" + (i + 1));
      }
      int first = Integer.parseInt(bounds[0], 16);
      int last = Integer.parseInt(bounds[bounds.length - 1], 16);
      if (first < 0 || last < first || last > 0x10FFFF) {
        throw new IllegalArgumentException("Invalid range at " + path + ":" + (i + 1));
      }
      tables.get(name).add(new Range(first, last));
    }
    return version;
  }

  private static List<Range> merge(List<Range> ranges) {
    ranges.sort(Comparator.comparingInt(Range::first));
    List<Range> result = new ArrayList<>();
    for (Range range : ranges) {
      if (!result.isEmpty()) {
        Range previous = result.getLast();
        if (range.first() <= previous.last()) {
          throw new IllegalArgumentException(
              "Overlapping Unicode ranges: " + previous + " and " + range);
        }
        if (range.first() == previous.last() + 1) {
          result.set(result.size() - 1, new Range(previous.first(), range.last()));
          continue;
        }
      }
      result.add(range);
    }
    return result;
  }

  private record Range(int first, int last) {}
}
