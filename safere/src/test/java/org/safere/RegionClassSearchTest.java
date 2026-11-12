// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("Exercises an internal scalar-class existence cache")
class RegionClassSearchTest {
  @Test
  void cachedExistenceAgreesWithIndependentScalarScansAcrossChangingEdges() {
    for (String regex : List.of("\\X?\\p{Cs}", "\\X?\\uDE00", "\\X?[a\\uDE00]")) {
      RejectPrefilter.CharClass cc =
          (RejectPrefilter.CharClass) Pattern.compile(regex).findRejectPrefilter();
      for (String text : List.of("a😀😀!", "😀\uDE00😀!", "a😀\uD83D!", "\uD83Dx\uDE00😀")) {
        RegionClassSearch searches = new RegionClassSearch();
        InputScanner scanner = new StringInputScanner(text);
        for (int direction : new int[] {1, -1, 1}) {
          for (int step = 0; step <= text.length(); step++) {
            int end = direction == 1 ? step : text.length() - step;
            for (int start = 0; start <= end; start++) {
              StringRegionInputScanner region = new StringRegionInputScanner(text, start, end);
              boolean expected =
                  region.indexOfCodePointClass(
                          cc.ranges(), cc.bitmap0(), cc.bitmap1(), 0, region.length())
                      >= 0;
              assertThat(searches.contains(cc, scanner, start, end))
                  .as("%s input=%s range=%s:%s", regex, text, start, end)
                  .isEqualTo(expected);
            }
          }
        }
      }
    }
  }
}
