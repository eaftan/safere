// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("WorkCounter and input scanners are internal SafeRE APIs")
@Tag("work-counter")
class SmallAsciiClassWorkTest {
  @Test
  void failedSearchAccountsForEachMemberSearch() {
    String text = "a".repeat(8_192);
    InputScanner scanner = new StringInputScanner(text);
    CharClassScanInfo info = smallSet("XZ_");
    long work =
        WorkCounter.countForTesting(
            () -> assertThat(scanner.indexOfCharClass(info, 0)).isEqualTo(-1));
    // The bitmap prologue inspects each unit once; each absent member searches the remainder.
    assertThat(work).isEqualTo(16L + 3L * (text.length() - 16));
  }

  @Test
  void repeatedSearchWorkIsBoundedByDistanceAdvanced() {
    for (String members : new String[] {"YZ", "XZ_"}) {
      CharClassScanInfo info = smallSet(members);
      for (String noise : new String[] {"a", "é", "中"}) {
        for (int gap : new int[] {17, 65, 200, 5_000}) {
          for (int matches : new int[] {16, 128}) {
            String text = (noise.repeat(gap - 1) + "Z").repeat(matches);
            InputScanner scanner = new StringInputScanner(text);
            long work =
                WorkCounter.countForTesting(
                    () -> {
                      int from = 0;
                      for (int i = 0; i < matches; i++) {
                        int found = scanner.indexOfCharClass(info, from);
                        assertThat(found).isEqualTo((i + 1) * gap - 1);
                        from = found + 1;
                      }
                      assertThat(scanner.indexOfCharClass(info, from)).isEqualTo(-1);
                    });
            assertThat(work).as("observed %s gap %s", members, gap).isPositive();
            assertThat(work)
                .as("%s gap %s matches %s", members, gap, matches)
                .isLessThanOrEqualTo(members.length() * (2L * text.length() + 64L * matches));
          }
        }
      }
    }
  }

  @Test
  void matcherIterationUsesTheInstrumentedWindowSearch() {
    Pattern pattern = Pattern.compile("[XZ_]");
    for (int gap : new int[] {17, 65, 200, 5_000}) {
      String text = ("a".repeat(gap - 1) + "Z").repeat(128);
      long work =
          WorkCounter.countForTesting(
              () -> {
                Matcher matcher = pattern.matcher(text);
                int matches = 0;
                while (matcher.find()) {
                  matches++;
                }
                assertThat(matches).isEqualTo(128);
              });
      assertThat(work).isGreaterThan(text.length());
      assertThat(work).isLessThanOrEqualTo(4L * (2L * text.length() + 64L * 128));
    }
  }

  private static CharClassScanInfo smallSet(String members) {
    AsciiBitmap.Builder builder = new AsciiBitmap.Builder();
    for (int i = 0; i < members.length(); i++) {
      builder.add(members.charAt(i));
    }
    return CharClassScanInfo.fromAsciiBitmap(builder.build());
  }
}
