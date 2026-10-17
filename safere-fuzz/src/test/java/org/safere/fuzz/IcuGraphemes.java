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

/** ICU4J property data and character segmentation for grapheme conformance tests and fuzzing. */
public final class IcuGraphemes {
  /** ICU property tuple, including overlaps used by GB9c and GB11. */
  public record Properties(String gcb, String incb, boolean pictograph) {}

  private static final Properties[] PROPERTIES = loadProperties();
  private static final int[] REPRESENTATIVES = buildRepresentatives();
  // ICU iterators are mutable; each test/fuzz worker owns its iterator.
  private static final ThreadLocal<BreakIterator> ICU_ITERATOR =
      ThreadLocal.withInitial(() -> BreakIterator.getCharacterInstance(ULocale.ROOT));

  private IcuGraphemes() {}

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

  /** Segments scalars using ICU's root-locale character break iterator. */
  public static List<String> clusters(int[] codePoints) {
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
