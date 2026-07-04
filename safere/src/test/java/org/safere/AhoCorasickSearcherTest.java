// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class AhoCorasickSearcherTest {

  @Test
  void testLiteralExtractor_exactAlternation() {
    Regexp re = Parser.parse("(?:AL|AK|AZ)", ParseFlags.LIKE_PERL);
    LiteralExtractor.Result result = LiteralExtractor.extract(re);
    assertThat(result).isNotNull();
    assertThat(result.literals).containsExactly("AL", "AK", "AZ");
    assertThat(result.isCaseInsensitive).isFalse();
    assertThat(result.isPureLiteralAlternation).isTrue();
  }

  @Test
  void testLiteralExtractor_prefixAlternation() {
    Regexp re = Parser.parse("(?:abc.*|def.*)", ParseFlags.LIKE_PERL);
    LiteralExtractor.Result result = LiteralExtractor.extract(re);
    assertThat(result).isNotNull();
    assertThat(result.literals).containsExactly("abc", "def");
    assertThat(result.isCaseInsensitive).isFalse();
    assertThat(result.isPureLiteralAlternation).isTrue();
  }

  @Test
  void testLiteralExtractor_requiredPrefixConcat() {
    Regexp re = Parser.parse("hello.*world", ParseFlags.LIKE_PERL);
    LiteralExtractor.Result result = LiteralExtractor.extract(re);
    assertThat(result).isNotNull();
    assertThat(result.literals).containsExactly("hello");
    assertThat(result.isCaseInsensitive).isFalse();
    assertThat(result.isPureLiteralAlternation).isTrue();
  }

  @Test
  void testLiteralExtractor_leadingAlternationInsideConcat() {
    Regexp re = Parser.parse("(?:abc|def)\\d+", ParseFlags.LIKE_PERL);
    LiteralExtractor.Result result = LiteralExtractor.extract(re);
    assertThat(result).isNotNull();
    assertThat(result.literals).containsExactly("abc", "def");
    assertThat(result.isCaseInsensitive).isFalse();
    assertThat(result.isPureLiteralAlternation).isTrue();
  }

  @Test
  void testLiteralExtractor_caseInsensitiveAlternation() {
    Regexp re = Parser.parse("(?:AL|AK|AZ)", ParseFlags.LIKE_PERL | ParseFlags.FOLD_CASE);
    LiteralExtractor.Result result = LiteralExtractor.extract(re);
    assertThat(result).isNotNull();
    assertThat(result.literals).containsExactly("al", "ak", "az");
    assertThat(result.isCaseInsensitive).isTrue();
    assertThat(result.isPureLiteralAlternation).isTrue();
  }

  @Test
  void testLiteralExtractor_requiredSubstringInfix() {
    Regexp re = Parser.parse(".*careers/.*", ParseFlags.LIKE_PERL);
    LiteralExtractor.Result result = LiteralExtractor.extract(re);
    assertThat(result).isNotNull();
    assertThat(result.literals).containsExactly("careers/");
    assertThat(result.isCaseInsensitive).isFalse();
    assertThat(result.isPureLiteralAlternation).isFalse();
  }

  @Test
  void testLiteralExtractor_singleLiteralString() {
    Regexp re = Parser.parse("hello", ParseFlags.LIKE_PERL);
    LiteralExtractor.Result result = LiteralExtractor.extract(re);
    assertThat(result).isNotNull();
    assertThat(result.literals).containsExactly("hello");
    assertThat(result.isCaseInsensitive).isFalse();
    assertThat(result.isPureLiteralAlternation).isTrue();
  }

  @Test
  void testAhoCorasickSearcher_basicMatch() {
    AhoCorasickSearcher searcher = new AhoCorasickSearcher(List.of("AL", "AK", "AZ"), false);
    assertThat(searcher.findNext("This is AL matching text", 0)).isEqualTo(8);
    assertThat(searcher.findNext("This is AK matching text", 0)).isEqualTo(8);
    assertThat(searcher.findNext("This has no state codes", 0)).isEqualTo(-1);
  }

  @Test
  void testAhoCorasickSearcher_caseInsensitiveMatch() {
    AhoCorasickSearcher searcher = new AhoCorasickSearcher(List.of("AL", "AK", "AZ"), true);
    assertThat(searcher.findNext("This is al matching text", 0)).isEqualTo(8);
    assertThat(searcher.findNext("This is Ak matching text", 0)).isEqualTo(8);
    assertThat(searcher.findNext("This is AZ matching text", 0)).isEqualTo(8);
  }

  @Test
  void testAhoCorasickSearcher_multipleMatches() {
    AhoCorasickSearcher searcher =
        new AhoCorasickSearcher(List.of("he", "she", "his", "hers"), false);
    assertThat(searcher.findNext("ushers", 0)).isEqualTo(1); // Matches "she" starting at index 1
    assertThat(searcher.findNext("ushers", 2)).isEqualTo(2); // Matches "he" starting at index 2
  }

  @Test
  void testAhoCorasickSearcher_returnsEarliestStartForOverlappingMatches() {
    AhoCorasickSearcher searcher = new AhoCorasickSearcher(List.of("abcd", "bc"), false);
    assertThat(searcher.findNext("abcdz", 0)).isEqualTo(0);
  }

  @Test
  void testAhoCorasickSearcher_unicodeMatch() {
    AhoCorasickSearcher searcher = new AhoCorasickSearcher(List.of("乳", "卵", "奶"), false);
    assertThat(searcher.findNext("这包含乳製品", 0)).isEqualTo(3);
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
