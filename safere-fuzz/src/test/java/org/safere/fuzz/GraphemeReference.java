// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Literal Unicode 17 UAX #29 rule interpreter. Uses official UCD text independently of ICU,
 * SafeRE's classifiers/caches, and the runtime JDK. Backward scans are deliberately test-only.
 */
public final class GraphemeReference {
  /** Independent property tuple, including overlaps used by GB9c and GB11. */
  public record Properties(String gcb, String incb, boolean pictograph) {}

  private static final Properties[] PROPERTIES = loadProperties();
  private static final int[] REPRESENTATIVES = buildRepresentatives();

  private GraphemeReference() {}

  /** Returns the Unicode properties for a code point. */
  public static Properties properties(int cp) {
    return PROPERTIES[cp];
  }

  /** One scalar representative of every distinct property tuple, in code point order. */
  public static int[] representatives() {
    return REPRESENTATIVES.clone();
  }

  private static int[] buildRepresentatives() {
    Map<Properties, Integer> representatives = new LinkedHashMap<>();
    for (int cp = 0; cp < PROPERTIES.length; cp++) {
      if (cp < 0xD800 || cp > 0xDFFF) {
        representatives.putIfAbsent(PROPERTIES[cp], cp);
      }
    }
    return representatives.values().stream().mapToInt(Integer::intValue).toArray();
  }

  /** Segments the whole input, with GB1/GB2 at its edges and no clusters for empty text. */
  public static List<String> clusters(int[] codePoints) {
    List<String> clusters = new ArrayList<>();
    int start = 0;
    for (int i = 1; i < codePoints.length; i++) {
      if (breakBefore(codePoints, i)) {
        clusters.add(new String(codePoints, start, i - start));
        start = i;
      }
    }
    if (start < codePoints.length) {
      clusters.add(new String(codePoints, start, codePoints.length - start));
    }
    return clusters;
  }

  private static boolean breakBefore(int[] cps, int i) {
    Properties left = properties(cps[i - 1]);
    Properties right = properties(cps[i]);
    String l = left.gcb();
    String r = right.gcb();
    if (l.equals("CR") && r.equals("LF")) { // GB3
      return false;
    }
    if (isControl(l) || isControl(r)) { // GB4/5 precede every joining rule
      return true;
    }
    if (l.equals("L") && List.of("L", "V", "LV", "LVT").contains(r)) { // GB6
      return false;
    }
    if (List.of("LV", "V").contains(l) && List.of("V", "T").contains(r)) { // GB7
      return false;
    }
    if (List.of("LVT", "T").contains(l) && r.equals("T")) { // GB8
      return false;
    }
    if (List.of("Extend", "ZWJ", "SpacingMark").contains(r)) { // GB9/9a
      return false;
    }
    if (l.equals("Prepend")) { // GB9b
      return false;
    }
    if (right.incb().equals("Consonant")) { // GB9c, independent backward context
      int j = i - 1;
      boolean linker = false;
      while (j >= 0 && List.of("Extend", "Linker").contains(properties(cps[j]).incb())) {
        linker |= properties(cps[j]).incb().equals("Linker");
        j--;
      }
      if (linker && j >= 0 && properties(cps[j]).incb().equals("Consonant")) {
        return false;
      }
    }
    if (l.equals("ZWJ") && right.pictograph()) { // GB11
      int j = i - 2;
      while (j >= 0 && properties(cps[j]).gcb().equals("Extend")) {
        j--;
      }
      if (j >= 0 && properties(cps[j]).pictograph()) {
        return false;
      }
    }
    if (l.equals("Regional_Indicator") && r.equals("Regional_Indicator")) { // GB12/13
      int count = 0;
      for (int j = i - 1; j >= 0 && properties(cps[j]).gcb().equals("Regional_Indicator"); j--) {
        count++;
      }
      return count % 2 == 0;
    }
    return true; // GB999
  }

  private static boolean isControl(String gcb) {
    return List.of("Control", "CR", "LF").contains(gcb);
  }

  private static Properties[] loadProperties() {
    Properties[] properties = new Properties[Character.MAX_CODE_POINT + 1];
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                Objects.requireNonNull(
                    GraphemeReference.class.getResourceAsStream("grapheme-properties-17.0.0.txt")),
                UTF_8))) {
      int next = 0;
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("#")) {
          continue;
        }
        String[] fields = line.split(";");
        String[] range = fields[0].split("\\.\\.");
        int start = Integer.parseInt(range[0], 16);
        int end = Integer.parseInt(range[1], 16);
        if (start != next || end < start) {
          throw new IllegalStateException("Noncontiguous Unicode fixture: " + line);
        }
        Arrays.fill(
            properties,
            start,
            end + 1,
            new Properties(fields[1], fields[2], fields[3].equals("Y")));
        next = end + 1;
      }
      if (next != properties.length) {
        throw new IllegalStateException("Incomplete Unicode fixture");
      }
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
    return properties;
  }
}
