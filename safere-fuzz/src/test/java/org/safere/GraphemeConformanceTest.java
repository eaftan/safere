// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.util.VersionInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.safere.fuzz.IcuGraphemes;
import org.safere.fuzz.IcuGraphemes.Properties;

/** Unicode property and boundary conformance against the pinned ICU4J version. */
class GraphemeConformanceTest {
  @Test
  void generatedPropertiesAgreeWithIcuUnicode17ForEveryCodePoint() {
    assertThat(UnicodeGeneratedTables.UNICODE_DATA_VERSION).isEqualTo("17.0.0");
    assertThat(UCharacter.getUnicodeVersion())
        .isEqualTo(VersionInfo.getInstance(UnicodeGeneratedTables.UNICODE_DATA_VERSION));
    for (Map.Entry<String, int[][]> entry : UnicodeGeneratedTables.GRAPHEME_PROPERTIES.entrySet()) {
      String name = entry.getKey();
      boolean[] actual = new boolean[Character.MAX_CODE_POINT + 1];
      for (int[] range : entry.getValue()) {
        Arrays.fill(actual, range[0], range[1] + 1, true);
      }
      for (int cp = 0; cp < actual.length; cp++) {
        Properties property = IcuGraphemes.properties(cp);
        boolean expected =
            name.startsWith("InCB_")
                ? property.incb().equals(name.substring(5))
                : name.equals("Control")
                    ? List.of("CR", "LF", "Control").contains(property.gcb())
                    : property.gcb().equals(name);
        if (actual[cp] != expected) {
          assertThat(actual[cp]).as("%s U+%04X", name, cp).isEqualTo(expected);
        }
      }
    }
    boolean[] pictographs = new boolean[Character.MAX_CODE_POINT + 1];
    for (int[] range : UnicodeProperties.lookupBinaryProperty("Extended_Pictographic")) {
      Arrays.fill(pictographs, range[0], range[1] + 1, true);
    }
    for (int cp = 0; cp < pictographs.length; cp++) {
      Properties property = IcuGraphemes.properties(cp);
      if (pictographs[cp] != property.pictograph()) {
        assertThat(pictographs[cp])
            .as("Extended_Pictographic U+%04X", cp)
            .isEqualTo(property.pictograph());
      }
      // Production has dedicated scalar predicates for these two GCB values.
      assertThat(property.gcb().equals("ZWJ")).isEqualTo(cp == 0x200D);
      assertThat(property.gcb().equals("Regional_Indicator"))
          .isEqualTo(cp >= 0x1F1E6 && cp <= 0x1F1FF);
    }
  }

  @Test
  void icuSegmentationAgreesWithOfficialBreakTest() throws IOException {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("GraphemeBreakTest.txt")),
                UTF_8))) {
      String line;
      int cases = 0;
      while ((line = reader.readLine()) != null) {
        if (!line.startsWith("÷")) {
          continue;
        }
        List<String> expected = new ArrayList<>();
        StringBuilder cluster = new StringBuilder();
        for (String token : line.split("#")[0].trim().split("\\s+")) {
          if (token.equals("÷")) {
            if (!cluster.isEmpty()) {
              expected.add(cluster.toString());
              cluster.setLength(0);
            }
          } else if (!token.equals("×")) {
            cluster.appendCodePoint(Integer.parseInt(token, 16));
          }
        }
        int[] cps = String.join("", expected).codePoints().toArray();
        assertThat(IcuGraphemes.clusters(cps)).as(line).isEqualTo(expected);
        cases++;
      }
      assertThat(cases).isPositive();
    }
  }

  @Test
  void cachedBoundariesAndOpaqueRegionsAgreeWithIcu() {
    int[] alphabet = IcuGraphemes.representatives();
    Random random = new Random(954);
    for (int sample = 0; sample < 300; sample++) {
      int[] cps = new int[random.nextInt(1, 40)];
      for (int i = 0; i < cps.length; i++) {
        cps[i] = alphabet[random.nextInt(alphabet.length)];
      }
      String text = new String(cps, 0, cps.length);
      GraphemeSupport.Context context = GraphemeSupport.Context.create(text, true);
      byte[] bytes = text.getBytes(UTF_8);
      InputScanner utf8 = new Utf8InputScanner(bytes);
      GraphemeSupport.Context utf8Context = GraphemeSupport.Context.create(utf8, true);
      for (int startCp = 0; startCp < cps.length; startCp++) {
        int start = text.offsetByCodePoints(0, startCp);
        String suffix = text.substring(start);
        List<String> expected = IcuGraphemes.clusters(suffix.codePoints().toArray());
        List<Integer> ends = new ArrayList<>();
        int end = start;
        for (String cluster : expected) {
          end += cluster.length();
          ends.add(end);
        }
        // Query in reverse to ensure answers do not depend on a forward matcher traversal.
        for (int atCp = cps.length; atCp > startCp; atCp--) {
          int at = text.offsetByCodePoints(0, atCp);
          int byteAt = text.substring(0, at).getBytes(UTF_8).length;
          int byteStart = text.substring(0, start).getBytes(UTF_8).length;
          boolean boundary = ends.contains(at);
          assertThat(GraphemeSupport.isGraphemeClusterBoundary(text, at, start, context))
              .as("UTF-16 %s at %s, start %s", Arrays.toString(cps), at, start)
              .isEqualTo(boundary);
          assertThat(
                  GraphemeSupport.isGraphemeClusterBoundary(utf8, byteAt, byteStart, utf8Context))
              .isEqualTo(boundary);
        }
      }
    }
  }

  @Test
  void publicRegionsFollowIcuBoundaryVisibility() {
    for (String text :
        List.of(
            "a\u0300\u0903z",
            "\r\n\u0000\u0300",
            "\u0600\u0915\u094D\u200D\u0915",
            "\u0600👩\u0300\u200D👩",
            "©\u0300\u200D©",
            "©\u200D\u200D©",
            "\u0600©\u200D©",
            "🇦🇧🇦🇧🇦",
            "\u1100\u1160\u11A8\u0300\u11A8")) {
      List<String> fullClusters = IcuGraphemes.clusters(text.codePoints().toArray());
      List<Integer> fullBoundaries = boundaries(fullClusters, 0);
      int cpCount = text.codePointCount(0, text.length());
      for (int from = 0; from <= cpCount; from++) {
        int start = text.offsetByCodePoints(0, from);
        for (int to = from; to <= cpCount; to++) {
          int end = text.offsetByCodePoints(0, to);
          List<String> local =
              IcuGraphemes.clusters(text.substring(start, end).codePoints().toArray());
          for (boolean transparent : List.of(false, true)) {
            List<Integer> expectedBoundaries =
                (transparent ? fullBoundaries : boundaries(local, start))
                    .stream().filter(pos -> pos >= start && pos <= end).toList();
            List<String> composed = new ArrayList<>();
            if (transparent) {
              int clusterStart = 0;
              for (String cluster : fullClusters) {
                int clusterEnd = clusterStart + cluster.length();
                if (clusterStart >= start && clusterEnd <= end) {
                  composed.add(cluster);
                }
                clusterStart = clusterEnd;
              }
            } else {
              composed = local;
            }
            Matcher boundary =
                Pattern.compile("\\b{g}")
                    .matcher(text)
                    .region(start, end)
                    .useTransparentBounds(transparent);
            List<Integer> actual = new ArrayList<>();
            while (boundary.find()) {
              actual.add(boundary.start());
            }
            assertThat(actual)
                .as("boundary %s [%s,%s], transparent=%s", text, start, end, transparent)
                .isEqualTo(expectedBoundaries);
            for (String regex : List.of("\\X", "\\b{g}(\\X)\\b{g}")) {
              Pattern pattern = Pattern.compile(regex);
              Matcher matcher =
                  pattern.matcher(text).region(start, end).useTransparentBounds(transparent);
              List<String> actualClusters = new ArrayList<>();
              while (matcher.find()) {
                actualClusters.add(matcher.group());
                if (matcher.groupCount() > 0) {
                  assertThat(matcher.group(1)).isEqualTo(matcher.group());
                }
              }
              List<String> expected = regex.equals("\\X") ? local : composed;
              assertThat(actualClusters)
                  .as("%s on %s [%s,%s], transparent=%s", regex, text, start, end, transparent)
                  .isEqualTo(expected);
              // UTF-8 exposes opaque regions; it has no transparent/anchoring bounds API.
              if (!transparent) {
                byte[] bytes = text.getBytes(UTF_8);
                int byteStart = text.substring(0, start).getBytes(UTF_8).length;
                int byteEnd = text.substring(0, end).getBytes(UTF_8).length;
                Utf8Matcher utf8 =
                    pattern.matcher(Utf8Input.validated(bytes)).region(byteStart, byteEnd);
                actualClusters.clear();
                while (utf8.find()) {
                  actualClusters.add(
                      new String(bytes, utf8.start(), utf8.end() - utf8.start(), UTF_8));
                }
                assertThat(actualClusters)
                    .as(
                        "UTF-8 %s on %s [%s,%s], transparent=%s",
                        regex, text, start, end, transparent)
                    .isEqualTo(expected);
              }
            }
          }
        }
      }
    }
  }

  private static List<Integer> boundaries(List<String> clusters, int start) {
    List<Integer> result = new ArrayList<>();
    result.add(start);
    int end = start;
    for (String cluster : clusters) {
      end += cluster.length();
      result.add(end);
    }
    return result;
  }
}
