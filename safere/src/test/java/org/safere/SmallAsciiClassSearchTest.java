// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisabledForCrosscheck("Already compares the String and UTF-8 matchers directly with the JDK")
class SmallAsciiClassSearchTest {
  @ParameterizedTest
  @ValueSource(strings = {"[YZ]", "[XZ_]", "[\\x00?\\x7F]", "[a-b]", "[iI]"})
  void searchPreservesRegionsSpansAndMatcherState(String regex) {
    String noise = "é中😀abc\uD800d\uDC00".repeat(700);
    String text = "Z_YX\u0000?\u007F" + noise + "_YXZbiI?\u007F\u0000";
    for (int flags :
        new int[] {0, Pattern.CASE_INSENSITIVE, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE}) {
      Pattern pattern = Pattern.compile(regex, flags);
      java.util.regex.Pattern expectedPattern = java.util.regex.Pattern.compile(regex, flags);
      for (int start : new int[] {0, 1, 16, 4095, text.length() - 12}) {
        for (int end : new int[] {text.length() - 12, text.length()}) {
          for (boolean anchoring : new boolean[] {false, true}) {
            Matcher actual = pattern.matcher(text).region(start, end).useAnchoringBounds(anchoring);
            java.util.regex.Matcher expected =
                expectedPattern.matcher(text).region(start, end).useAnchoringBounds(anchoring);
            while (expected.find()) {
              assertThat(actual.find())
                  .as("%s, flags %s, region %s:%s", regex, flags, start, end)
                  .isTrue();
              assertThat(actual.start()).isEqualTo(expected.start());
              assertThat(actual.end()).isEqualTo(expected.end());
              assertThat(actual.group()).isEqualTo(expected.group());
              assertThat(actual.toMatchResult().group()).isEqualTo(expected.group());
            }
            assertThat(actual.find()).isFalse();
          }
        }
      }
      assertThat(pattern.matcher(text).replaceAll("$0!"))
          .isEqualTo(expectedPattern.matcher(text).replaceAll("$0!"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"[YZ]", "[XZ_]"})
  void denseIterationAndResetPreserveEveryMatch(String regex) {
    // Only the later member is present: scans for earlier absent members must stay bounded.
    for (int length : new int[] {1024, 8192, 65536}) {
      String text = "Z".repeat(length);
      Matcher matcher = Pattern.compile(regex).matcher(text);
      int matches = 0;
      while (matcher.find()) {
        assertThat(matcher.start()).isEqualTo(matches);
        assertThat(matcher.end()).isEqualTo(matches + 1);
        matches++;
      }
      assertThat(matches).isEqualTo(length);
      assertThat(matcher.reset().find(length - 1)).isTrue();
      assertThat(matcher.start()).isEqualTo(length - 1);
      assertThat(matcher.find()).isFalse();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"[YZ]", "[XZ_]"})
  void repeatedSearchReturnsNearestMatchAfterSmallGaps(String regex) {
    for (int gap : new int[] {16, 32, 64, 128, 4096}) {
      String text = ("a".repeat(gap) + "Z").repeat(257);
      Matcher matcher = Pattern.compile(regex).matcher(text);
      int matches = 0;
      while (matcher.find()) {
        assertThat(matcher.start()).isEqualTo(matches * (gap + 1) + gap);
        assertThat(matcher.end()).isEqualTo((matches + 1) * (gap + 1));
        matches++;
      }
      assertThat(matches).isEqualTo(257);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"[YZ]", "[XZ_]"})
  void utf8SearchHonorsMultibyteInputWindowsAndLimits(String regex) {
    String text = "é中😀".repeat(1000) + "_YXZ";
    byte[] storage = ("ZZ" + text + "ZZ").getBytes(UTF_8);
    int byteLength = text.getBytes(UTF_8).length;
    Utf8Input input = Utf8Input.validated(storage, 2, byteLength);
    Pattern pattern = Pattern.compile(regex);
    Utf8Matcher actual = pattern.matcher(input);
    java.util.regex.Matcher expected = java.util.regex.Pattern.compile(regex).matcher(text);
    assertThat(pattern.find(input)).isTrue();
    while (expected.find()) {
      assertThat(actual.find()).isTrue();
      assertThat(actual.start())
          .isEqualTo(text.substring(0, expected.start()).getBytes(UTF_8).length);
      assertThat(actual.end()).isEqualTo(text.substring(0, expected.end()).getBytes(UTF_8).length);
      assertThat(new String(storage, 2 + actual.start(), actual.end() - actual.start(), UTF_8))
          .isEqualTo(expected.group());
    }
    assertThat(actual.find()).isFalse();
    assertThat(actual.region(0, byteLength - 4).find()).isFalse();
  }
}
