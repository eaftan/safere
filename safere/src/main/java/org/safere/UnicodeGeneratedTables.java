// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Checked-in Unicode tables generated from a maintainer-selected JDK. */
final class UnicodeGeneratedTables {
  private static final int MAGIC = 0x53524555; // SREU
  private static final int FORMAT_VERSION = 1;
  private static final String RESOURCE = "/org/safere/unicode-tables.bin";

  private static final Data DATA = load();

  static final String GENERATOR_JAVA_VERSION = DATA.generatorJavaVersion();
  static final String UNICODE_VERSION = DATA.unicodeVersion();
  static final Map<String, int[][]> CATEGORIES = DATA.categories();
  static final Map<String, int[][]> SCRIPTS = DATA.scripts();
  static final Map<String, int[][]> BLOCKS = DATA.blocks();
  static final Map<String, int[][]> BINARY_PROPERTIES = DATA.binaryProperties();

  private UnicodeGeneratedTables() {}

  static Map<String, int[][]> unicodeGroups() {
    Map<String, int[][]> groups = new LinkedHashMap<>();
    groups.putAll(CATEGORIES);
    groups.putAll(SCRIPTS);
    return Collections.unmodifiableMap(groups);
  }

  private static Data load() {
    try (InputStream stream = UnicodeGeneratedTables.class.getResourceAsStream(RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException("Missing generated Unicode table resource: " + RESOURCE);
      }
      try (DataInputStream in = new DataInputStream(new BufferedInputStream(stream))) {
        int magic = in.readInt();
        if (magic != MAGIC) {
          throw new IllegalStateException("Invalid Unicode table resource magic: " + magic);
        }
        int version = in.readInt();
        if (version != FORMAT_VERSION) {
          throw new IllegalStateException("Unsupported Unicode table format version: " + version);
        }
        return new Data(
            in.readUTF(), in.readUTF(), readMap(in), readMap(in), readMap(in), readMap(in));
      }
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static Map<String, int[][]> readMap(DataInputStream in) throws IOException {
    int count = in.readInt();
    Map<String, int[][]> result = new LinkedHashMap<>();
    for (int i = 0; i < count; i++) {
      String name = in.readUTF();
      int rangeCount = in.readInt();
      int[][] ranges = new int[rangeCount][];
      for (int j = 0; j < rangeCount; j++) {
        ranges[j] = new int[] {in.readInt(), in.readInt()};
      }
      result.put(name, ranges);
    }
    return Collections.unmodifiableMap(result);
  }

  private record Data(
      String generatorJavaVersion,
      String unicodeVersion,
      Map<String, int[][]> categories,
      Map<String, int[][]> scripts,
      Map<String, int[][]> blocks,
      Map<String, int[][]> binaryProperties) {}
}
