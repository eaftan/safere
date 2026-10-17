// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Anchored forward DFA searches from every start position must agree with the NFA, including
 * searches that leave the cached ASCII loop part way through: on a non-ASCII character, an
 * uncomputed or dead transition, a state with an accelerator, or a cache reset.
 */
class DfaAnchoredForwardTest {
  private static final List<String> INPUTS =
      List.of(
          "",
          "abc",
          "abx",
          "ababcd abcd",
          "foo foobar _foo foo",
          "\"" + "q".repeat(40) + "é" + "q".repeat(40) + "\" \"\"",
          "x" + "a".repeat(50) + "y xay",
          "ééx aéb éa",
          "a\r\nb\nz",
          "aaaaaaaaaaaaaaaaaaaab");

  private static final int[] BUDGETS = {1, 2, 3, 4, 8, 64};

  @ParameterizedTest
  @ValueSource(
      strings = {
        "abc",
        "a|ab",
        "(a|ab)(c|bcd)",
        "[a-z]+",
        "\\bfoo\\b",
        "\\w+\\B",
        "\"[^\"]*\"",
        "x[^y]*y",
        "é+x",
        "a.*b",
        "(?s).*z",
        "a*",
        ""
      })
  void anchoredSearchFromEveryStartMatchesTheNfa(String regex) {
    Pattern pattern = Pattern.compile(regex);
    Pattern nfa = Pattern.compile(regex, 0, EnginePathOptions.builder().dfa(false).build());
    Prog prog = pattern.prog();
    Dfa first = new Dfa(prog, 10_000, Dfa.buildSetup(prog), false);
    Dfa longest = new Dfa(prog, 10_000, Dfa.buildSetup(prog), true);
    for (String input : INPUTS) {
      for (int start = 0; start <= input.length(); start++) {
        String where = String.format("/%s/ on %s from %d", regex, input, start);
        assertThat(describe(first.doSearch(input, start, true, false)))
            .as("first " + where)
            .isEqualTo(firstMatchEnd(nfa, input, start));
        assertThat(describe(longest.doSearch(input, start, true, true)))
            .as("longest " + where)
            .isEqualTo(longestMatchEnd(nfa, input, start));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"(a|ab)(c|bcd)", "[a-z]+", "\\bfoo\\b", "\"[^\"]*\"", "a.*b"})
  void anchoredSearchWithASmallCacheMatchesOrBails(String regex) {
    Prog prog = Pattern.compile(regex).prog();
    Dfa ample = new Dfa(prog, 10_000, Dfa.buildSetup(prog), true);
    for (int budget : BUDGETS) {
      // Reuse each DFA across searches so cached states and resets carry over between them.
      Dfa small = new Dfa(prog, budget, Dfa.buildSetup(prog), true);
      for (String input : INPUTS) {
        for (int start = 0; start <= input.length(); start++) {
          Dfa.SearchResult result = small.doSearch(input, start, true, true);
          if (result == null) {
            assertThat(small.lastSearchBailed()).isTrue();
            continue;
          }
          assertThat(describe(result))
              .as("/%s/ budget=%d on %s from %d", regex, budget, input, start)
              .isEqualTo(describe(ample.doSearch(input, start, true, true)));
        }
      }
    }
  }

  private static String describe(Dfa.SearchResult result) {
    assertThat(result).isNotNull();
    return result.matched() ? "match to " + result.pos() : "no match";
  }

  private static String firstMatchEnd(Pattern nfa, String input, int start) {
    Matcher m = regionMatcher(nfa, input, start, input.length());
    return m.lookingAt() ? "match to " + m.end() : "no match";
  }

  private static String longestMatchEnd(Pattern nfa, String input, int start) {
    for (int end = input.length(); end >= start; end--) {
      if (regionMatcher(nfa, input, start, end).matches()) {
        return "match to " + end;
      }
    }
    return "no match";
  }

  private static Matcher regionMatcher(Pattern nfa, String input, int start, int end) {
    return nfa.matcher(input)
        .region(start, end)
        .useTransparentBounds(true)
        .useAnchoringBounds(false);
  }
}
