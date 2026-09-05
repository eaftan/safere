// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class AhoCorasickSearcherTest {

  @Test
  void testAhoCorasickSearcher_basicMatch() {
    AhoCorasickSearcher searcher = new AhoCorasickSearcher(List.of("AL", "AK", "AZ"), false);
    byte[] bytes1 = "This is AL matching text".getBytes(StandardCharsets.UTF_8);
    byte[] bytes2 = "This is AK matching text".getBytes(StandardCharsets.UTF_8);
    byte[] bytesNo = "This has no state codes".getBytes(StandardCharsets.UTF_8);

    assertThat(searcher.findNext(bytes1, 0, bytes1.length, 0)).isEqualTo(8);
    assertThat(searcher.findNext(bytes2, 0, bytes2.length, 0)).isEqualTo(8);
    assertThat(searcher.findNext(bytesNo, 0, bytesNo.length, 0)).isEqualTo(-1);
  }

  @Test
  void testAhoCorasickSearcher_caseInsensitiveMatch() {
    AhoCorasickSearcher searcher = new AhoCorasickSearcher(List.of("AL", "AK", "AZ"), true);
    byte[] bytes1 = "This is al matching text".getBytes(StandardCharsets.UTF_8);
    byte[] bytes2 = "This is Ak matching text".getBytes(StandardCharsets.UTF_8);
    byte[] bytes3 = "This is AZ matching text".getBytes(StandardCharsets.UTF_8);

    assertThat(searcher.findNext(bytes1, 0, bytes1.length, 0)).isEqualTo(8);
    assertThat(searcher.findNext(bytes2, 0, bytes2.length, 0)).isEqualTo(8);
    assertThat(searcher.findNext(bytes3, 0, bytes3.length, 0)).isEqualTo(8);
  }

  @Test
  void testAhoCorasickSearcher_multipleMatches() {
    AhoCorasickSearcher searcher =
        new AhoCorasickSearcher(List.of("he", "she", "his", "hers"), false);
    byte[] bytes = "ushers".getBytes(StandardCharsets.UTF_8);
    assertThat(searcher.findNext(bytes, 0, bytes.length, 0))
        .isEqualTo(1); // Matches "she" starting at index 1
    assertThat(searcher.findNext(bytes, 0, bytes.length, 2))
        .isEqualTo(2); // Matches "he" starting at index 2
  }

  @Test
  void testAhoCorasickSearcher_returnsEarliestStartForOverlappingMatches() {
    AhoCorasickSearcher searcher = new AhoCorasickSearcher(List.of("abcd", "bc"), false);
    byte[] bytes = "abcdz".getBytes(StandardCharsets.UTF_8);
    assertThat(searcher.findNext(bytes, 0, bytes.length, 0)).isEqualTo(0);
  }

  @Test
  void testAhoCorasickSearcher_unicodeMatch() {
    AhoCorasickSearcher searcher = new AhoCorasickSearcher(List.of("乳", "卵", "奶"), false);
    byte[] bytes = "这包含乳製品".getBytes(StandardCharsets.UTF_8);
    assertThat(searcher.findNext(bytes, 0, bytes.length, 0))
        .isEqualTo(9); // "乳" is at UTF-8 byte offset 9
  }

  @Test
  void testAhoCorasickSearcher_largeDictionarySimdPrefilter() {
    List<String> keywords = new ArrayList<>();
    for (int i = 0; i < 500; i++) {
      keywords.add(String.format("pattern_%04d", i));
    }
    AhoCorasickSearcher searcher = new AhoCorasickSearcher(keywords, false);

    String haystack = "noise ".repeat(1000) + "pattern_0342" + " trailing noise ".repeat(100);
    byte[] bytes = haystack.getBytes(StandardCharsets.UTF_8);

    int expectedIndex = haystack.indexOf("pattern_0342");
    assertThat(searcher.findNext(bytes, 0, bytes.length, 0)).isEqualTo(expectedIndex);

    // After match
    assertThat(searcher.findNext(bytes, 0, bytes.length, expectedIndex + 1)).isEqualTo(-1);
  }

  @Test
  void testAhoCorasickPrefilterPreservesLeftmostMatchStart() {
    String input = "x".repeat(5000) + "abcdz ";
    Matcher matcher = Pattern.compile("(?:abcd|bc)z\\b").matcher(input);

    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(5000);
    assertThat(matcher.end()).isEqualTo(5005);
  }
}
