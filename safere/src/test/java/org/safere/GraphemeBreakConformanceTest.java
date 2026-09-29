// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Conformance tests for Unicode Extended Grapheme Cluster segmentation ({@code \X} and {@code
 * \b{g}}) against UAX #29's {@code GraphemeBreakTest.txt}, plus an exhaustive per-code-point
 * comparison with {@code java.util.regex} when the runtime JDK's Unicode data matches the
 * checked-in tables.
 */
@DisabledForCrosscheck(
    "SafeRE pins Unicode 17 grapheme tables across runtime JDKs and treats unassigned Other code"
        + " points as bases; see INTENTIONAL_DIVERGENCES.md")
class GraphemeBreakConformanceTest {
  private static final Pattern SAFERE_GRAPHEME = Pattern.compile("\\X");
  private static final Pattern SAFERE_BOUNDARY = Pattern.compile("\\b{g}");
  private static final java.util.regex.Pattern JDK_GRAPHEME =
      java.util.regex.Pattern.compile("\\X");

  @Test
  void unicode17GraphemeBreakTestTable() throws IOException {
    int count = 0;
    try (InputStream in =
            Objects.requireNonNull(
                GraphemeBreakConformanceTest.class.getResourceAsStream("GraphemeBreakTest.txt"));
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.startsWith("÷")) {
          continue;
        }
        count++;
        int commentStart = line.indexOf('#');
        String data = (commentStart >= 0 ? line.substring(0, commentStart) : line).trim();
        StringBuilder stringInput = new StringBuilder();
        List<String> expectedClusters = new ArrayList<>();
        StringBuilder currentCluster = new StringBuilder();
        for (String token : data.split("\\s+")) {
          if (token.equals("÷")) {
            if (!currentCluster.isEmpty()) {
              expectedClusters.add(currentCluster.toString());
              currentCluster.setLength(0);
            }
          } else if (!token.equals("×")) {
            int cp = Integer.parseInt(token, 16);
            stringInput.appendCodePoint(cp);
            currentCluster.appendCodePoint(cp);
          }
        }
        String text = stringInput.toString();

        List<String> stringClusters = new ArrayList<>();
        Matcher matcher = SAFERE_GRAPHEME.matcher(text);
        while (matcher.find()) {
          stringClusters.add(matcher.group());
        }
        assertThat(stringClusters).as(line).isEqualTo(expectedClusters);

        assertThat(SAFERE_BOUNDARY.split(text))
            .as(line)
            .containsExactlyElementsOf(expectedClusters);

        byte[] utf8Bytes = text.getBytes(UTF_8);
        List<String> utf8Clusters = new ArrayList<>();
        Utf8Matcher utf8Matcher = SAFERE_GRAPHEME.matcher(Utf8Input.validated(utf8Bytes));
        while (utf8Matcher.find()) {
          utf8Clusters.add(
              new String(
                  utf8Bytes, utf8Matcher.start(), utf8Matcher.end() - utf8Matcher.start(), UTF_8));
        }
        assertThat(utf8Clusters).as(line).isEqualTo(expectedClusters);
      }
    }
    assertThat(count).isEqualTo(766);
  }

  @Test
  void assignedCodePointsMatchJdkAcrossGraphemeContexts() {
    assumeTrue(
        runtimeJdkUnicodeMatchesGeneratedTables(),
        "Runtime JDK Unicode repertoire differs from the generator JDK");

    String[][] contexts = {
      {"a", ""},
      {"", "a"},
      {"", "\u0301"},
      {"\u0915\u094D", ""},
      {"\u0915", "\u0915"},
      {"\u0915\u094D", "\u0915"},
      {"\uD83D\uDC4D", ""},
      {"\uD83D\uDC4D\u200D", ""},
      {"\u1100", ""},
      {"", "\u1161"},
      {"\n", ""},
      {"", "\n"},
    };

    // Each input has at most four code points, so at most four clusters.
    int[] safereEnds = new int[8];
    int[] jdkEnds = new int[8];
    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      int type = Character.getType(cp);
      // Unicode 14 removed GCB=SpacingMark for these Ahom signs. The JDK still uses it.
      // See INTENTIONAL_DIVERGENCES.md and the focused test below.
      if (type == Character.UNASSIGNED
          || type == Character.SURROGATE
          || cp == 0x11720
          || cp == 0x11721) {
        continue;
      }
      String mid = Character.toString(cp);
      for (String[] ctx : contexts) {
        String text = ctx[0] + mid + ctx[1];
        int safereCount = collectEnds(SAFERE_GRAPHEME.matcher(text), safereEnds);
        int jdkCount = collectEnds(JDK_GRAPHEME.matcher(text), jdkEnds);
        if (safereCount != jdkCount || !prefixEquals(safereEnds, jdkEnds, safereCount)) {
          assertThat(slice(safereEnds, safereCount))
              .as("U+%04X in [%s _ %s]", cp, hex(ctx[0]), hex(ctx[1]))
              .isEqualTo(slice(jdkEnds, jdkCount));
        }
      }
    }
  }

  @Test
  void ahomVowelSignsUseUnicode17GraphemeBoundaries() {
    for (int cp : new int[] {0x11720, 0x11721}) {
      String sign = Character.toString(cp);
      String text = "a" + sign;
      Matcher matcher = SAFERE_GRAPHEME.matcher(text);
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.group()).isEqualTo("a");
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.group()).isEqualTo(sign);
      assertThat(matcher.find()).isFalse();
      assertThat(SAFERE_BOUNDARY.split(text)).containsExactly("a", sign);
    }
  }

  private static boolean runtimeJdkUnicodeMatchesGeneratedTables() {
    int[][] assigned =
        Objects.requireNonNull(UnicodeGeneratedTables.BINARY_PROPERTIES.get("Assigned"));
    int rangeIndex = 0;
    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      while (rangeIndex < assigned.length && assigned[rangeIndex][1] < cp) {
        rangeIndex++;
      }
      boolean inTable = rangeIndex < assigned.length && assigned[rangeIndex][0] <= cp;
      if (Character.isDefined(cp) != inTable) {
        return false;
      }
    }
    return true;
  }

  private static int collectEnds(Matcher matcher, int[] out) {
    int n = 0;
    while (matcher.find()) {
      out[n++] = matcher.end();
    }
    return n;
  }

  private static int collectEnds(java.util.regex.Matcher matcher, int[] out) {
    int n = 0;
    while (matcher.find()) {
      out[n++] = matcher.end();
    }
    return n;
  }

  private static boolean prefixEquals(int[] a, int[] b, int len) {
    for (int i = 0; i < len; i++) {
      if (a[i] != b[i]) {
        return false;
      }
    }
    return true;
  }

  private static List<Integer> slice(int[] a, int len) {
    List<Integer> list = new ArrayList<>(len);
    for (int i = 0; i < len; i++) {
      list.add(a[i]);
    }
    return list;
  }

  private static String hex(String s) {
    return s.codePoints().mapToObj(cp -> String.format("%04X", cp)).toList().toString();
  }
}
