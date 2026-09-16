// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class DfaArrayGrowthTest {
  @Test
  void acceleratedSearchRetainsStatesAcrossArrayGrowth() {
    // Original fuzz input from #882, including UTF-8 replacement characters.
    String regex =
        decode(
            "7862777c00000000000000006eebba80e78182ef8095d48437f28d9393e3acbddfaf0dd294"
                + "efbfbf022c271854df802e2f696173d494d4bf80dfa5dfbfdfb37a24e3a081d99ede9e");
    String input = decode("9e00000000000000002a0a00242a015e0a242a00a6188c5d7224a2247c9f45737a24");
    for (int flags :
        new int[] {0, Pattern.CASE_INSENSITIVE, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE}) {
      for (String prefix : List.of("", "!", "\n", "a", "😀", "!".repeat(300))) {
        for (String suffix : List.of("", "xbw", "XBW", "xbw xbw")) {
          Pattern pattern = Pattern.compile(regex, flags);
          String text = prefix + input + suffix;
          for (int pass = 0; pass < 2; pass++) {
            Matcher actual = pattern.matcher(text);
            java.util.regex.Matcher expected =
                java.util.regex.Pattern.compile(regex, flags).matcher(text);
            while (expected.find()) {
              assertThat(actual.find()).isTrue();
              assertThat(actual.start()).isEqualTo(expected.start());
              assertThat(actual.end()).isEqualTo(expected.end());
            }
            assertThat(actual.find()).isFalse();
          }
        }
      }
    }
  }

  @Test
  void acceleratedRestartHandlesDifferentStateAndAlphabetSizes() {
    // Long alternatives vary the alphabet size; partial prefixes populate the state cache.
    // A later candidate after a newline requires a different start context (#882).
    for (int count : new int[] {4, 5, 8, 16}) {
      for (int alphabetSize : new int[] {32, 64, 105, 113, 128}) {
        StringBuilder tail = new StringBuilder();
        for (int i = 0; i < alphabetSize; i++) {
          tail.appendCodePoint(0x400 + i);
        }
        String prefix = "\0".repeat(count);
        String regex = "xbw|" + prefix + tail + "$";
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        for (String suffix : List.of("", "xbw", prefix + tail)) {
          String text = "!" + prefix + "!\n\0" + "!".repeat(40) + suffix;
          for (int pass = 0; pass < 2; pass++) {
            Matcher actual = pattern.matcher(text);
            java.util.regex.Matcher expected =
                java.util.regex.Pattern.compile(
                        regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(text);
            while (expected.find()) {
              assertThat(actual.find()).isTrue();
              assertThat(actual.start()).isEqualTo(expected.start());
              assertThat(actual.end()).isEqualTo(expected.end());
            }
            assertThat(actual.find()).isFalse();
          }
        }
      }
    }
  }

  private static String decode(String hex) {
    return new String(HexFormat.of().parseHex(hex), StandardCharsets.UTF_8);
  }
}
