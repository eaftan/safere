// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import com.ibm.icu.text.BreakIterator;
import com.ibm.icu.util.ULocale;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Literal Unicode 17 UAX #29 rule interpreter with ICU4J property values. Its boundary algorithm is
 * independent of SafeRE's classifiers/caches, ICU's break iterator, and the runtime JDK. Backward
 * scans are deliberately test-only.
 */
public final class GraphemeReference {
  /** ICU property tuple, including overlaps used by GB9c and GB11. */
  public record Properties(String gcb, String incb, boolean pictograph) {}

  private static final Properties[] PROPERTIES = loadProperties();
  private static final int[] REPRESENTATIVES = buildRepresentatives();
  // ICU iterators are mutable; each test/fuzz worker owns its iterator.
  private static final ThreadLocal<BreakIterator> ICU_ITERATOR =
      ThreadLocal.withInitial(() -> BreakIterator.getCharacterInstance(ULocale.ROOT));

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

  /** Segments scalars with ICU's separate UAX #29 implementation. */
  public static List<String> icuClusters(int[] codePoints) {
    String text = new String(codePoints, 0, codePoints.length);
    BreakIterator iterator = ICU_ITERATOR.get();
    iterator.setText(text);
    List<String> clusters = new ArrayList<>();
    int start = iterator.first();
    for (int end = iterator.next(); end != BreakIterator.DONE; end = iterator.next()) {
      clusters.add(text.substring(start, end));
      start = end;
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
    Map<Integer, String> gcbNames = new HashMap<>();
    Map<Integer, String> incbNames = new HashMap<>();
    Map<Properties, Properties> tuples = new HashMap<>();
    Properties[] properties = new Properties[Character.MAX_CODE_POINT + 1];
    for (int cp = 0; cp < properties.length; cp++) {
      int gcb = UCharacter.getIntPropertyValue(cp, UProperty.GRAPHEME_CLUSTER_BREAK);
      int incb = UCharacter.getIntPropertyValue(cp, UProperty.INDIC_CONJUNCT_BREAK);
      boolean pictograph = UCharacter.hasBinaryProperty(cp, UProperty.EXTENDED_PICTOGRAPHIC);
      String gcbName =
          gcbNames.computeIfAbsent(
              gcb,
              value ->
                  UCharacter.getPropertyValueName(
                      UProperty.GRAPHEME_CLUSTER_BREAK, value, UProperty.NameChoice.LONG));
      String incbName =
          incbNames.computeIfAbsent(
              incb,
              value ->
                  UCharacter.getPropertyValueName(
                      UProperty.INDIC_CONJUNCT_BREAK, value, UProperty.NameChoice.LONG));
      Properties tuple = new Properties(gcbName, incbName, pictograph);
      properties[cp] = tuples.computeIfAbsent(tuple, key -> key);
    }
    return properties;
  }
}
