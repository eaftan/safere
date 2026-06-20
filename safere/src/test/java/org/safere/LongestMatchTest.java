// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class LongestMatchTest {

  private static Pattern compileLongest(String regex) {
    return Pattern.compile(regex, 0, EnginePathOptions.builder().longestMatch(true).build());
  }

  @Test
  void testLeftmostLongestAlternation() {
    // With default leftmost-first semantics:
    Pattern p1 = Pattern.compile("a|ab");
    Matcher m1 = p1.matcher("ab");
    assertThat(m1.find()).isTrue();
    assertThat(m1.group()).isEqualTo("a"); // matches first branch

    // With leftmost-longest semantics:
    Pattern p2 = compileLongest("a|ab");
    Matcher m2 = p2.matcher("ab");
    assertThat(m2.find()).isTrue();
    assertThat(m2.group()).isEqualTo("ab"); // matches longest branch
  }

  @Test
  void testLeftmostLongestGreedyQuantifier() {
    Pattern p = compileLongest("a*");
    Matcher m = p.matcher("aaa");
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("aaa");
    assertThat(m.start()).isEqualTo(0);
    assertThat(m.end()).isEqualTo(3);
  }

  @Test
  void testLeftmostLongestLazyQuantifier() {
    // POSIX leftmost-longest ignores lazy quantifiers' preference for shortest match!
    // It still finds the longest match.
    Pattern p = compileLongest("a*?");
    Matcher m = p.matcher("aaa");
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("aaa"); // POSIX leftmost-longest gives longest match
  }

  @Test
  void testLeftmostLongestUnanchored() {
    Pattern p = compileLongest("a|ab");
    Matcher m = p.matcher("xabx");
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("ab");
    assertThat(m.start()).isEqualTo(1);
    assertThat(m.end()).isEqualTo(3);
  }

  @Test
  void testLeftmostLongestMatches() {
    Pattern p = compileLongest("a|ab");
    Matcher m = p.matcher("ab");
    assertThat(m.matches()).isTrue();
    assertThat(m.group()).isEqualTo("ab");
  }

  @Test
  void testLeftmostLongestLookingAt() {
    Pattern p = compileLongest("a|ab");
    Matcher m = p.matcher("abc");
    assertThat(m.lookingAt()).isTrue();
    assertThat(m.group()).isEqualTo("ab");
  }

  @Test
  void testLeftmostLongestKeywordAlternation() {
    // Both leftmost-first and leftmost-longest must match "cats" on "cats"
    // because "cat" does not have a word boundary after it in "cats".
    Pattern p1 = Pattern.compile("\\b(cat|cats)\\b");
    Matcher m1 = p1.matcher("cats");
    assertThat(m1.find()).isTrue();
    assertThat(m1.group()).isEqualTo("cats");

    Pattern p2 =
        Pattern.compile(
            "\\b(cat|cats)\\b", 0, EnginePathOptions.builder().longestMatch(true).build());
    Matcher m2 = p2.matcher("cats");
    assertThat(m2.find()).isTrue();
    assertThat(m2.group()).isEqualTo("cats");
  }

  @Test
  void testAlternationPruning() {
    String input = "prefix.middle_suffix.end";
    String regex = "\\b(prefix\\.middle_suffix\\.|middle_suffix\\.)";

    // With longestMatch = true
    Pattern p1 = Pattern.compile(regex, 0, EnginePathOptions.builder().longestMatch(true).build());
    Matcher m1 = p1.matcher(input);
    assertThat(m1.find()).isTrue();
    assertThat(m1.start()).isEqualTo(0);

    // With longestMatch = false (default)
    Pattern p2 = Pattern.compile(regex, 0, EnginePathOptions.builder().longestMatch(false).build());
    Matcher m2 = p2.matcher(input);
    assertThat(m2.find()).isTrue();
    assertThat(m2.start()).isEqualTo(0);
  }

  @Test
  void testReverseDfaAlternationPruning() {
    String input = "prefix.middle_suffix.end";
    String regex = "\\b(prefix\\.middle_suffix\\.|middle_suffix\\.)";
    Pattern p = Pattern.compile(regex, 0, EnginePathOptions.builder().longestMatch(true).build());
    Dfa revDfa = p.reverseDfa();
    Dfa.SearchResult revResult = revDfa.doSearchReverse(input, 21, 0, true, true);
    assertThat(revResult.pos()).isEqualTo(0);
  }

  @Test
  void testFilterTranslatorPattern() {
    String input = "name=\"type\" AND type=\"TABLE\"";
    String regex =
        "(?P<STRING>\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*')|(?P<TYPE_KEYWORD>\\btype\\b)";
    Pattern p =
        Pattern.compile(
            regex, 0, EnginePathOptions.builder().semanticGuards(true).longestMatch(true).build());
    Matcher m = p.matcher(input);

    // First match: name="type" -> matches "type"
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("\"type\"");
    assertThat(m.group("STRING")).isEqualTo("\"type\"");
    assertThat(m.group("TYPE_KEYWORD")).isNull();

    // Second match: type
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("type");
    assertThat(m.group("STRING")).isNull();
    assertThat(m.group("TYPE_KEYWORD")).isEqualTo("type");

    // Third match: "TABLE"
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("\"TABLE\"");
    assertThat(m.group("STRING")).isEqualTo("\"TABLE\"");
    assertThat(m.group("TYPE_KEYWORD")).isNull();

    assertThat(m.find()).isFalse();
  }

  @Test
  void testPerformanceLongestMatchWithSemanticGuardsDisabled() {
    String input = "abc";
    String regex = "a|ab";
    Pattern p =
        Pattern.compile(
            regex, 0, EnginePathOptions.builder().semanticGuards(false).longestMatch(true).build());
    Matcher m = p.matcher(input);

    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("ab"); // Leftmost-longest match is "ab", not "a"
    assertThat(m.find()).isFalse();
  }
}
