// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** DFA start states memoized by word context must give the same results as the NFA. */
@DisabledForCrosscheck("compares SafeRE engine paths through package-private EnginePathOptions")
class DfaStartStateContextTest {
  private static final List<String> INPUTS =
      List.of(
          "foo bar_baz foo",
          "éfoo foo-é föo_",
          "x\u00DFfoo\u00DF foo",
          "1foo2 _foo_ \u0661foo",
          "foo\u0301 foo\u200Dfoo",
          "\uD835\uDC00foo foo\uD835\uDC00",
          "\uD801\uDC00b",
          "\uD801\uDC00",
          "\uDC00b",
          "\uD801b",
          "\uD801\uDC00b\uD801\uDC00",
          "");

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\\bfoo",
        "foo\\b",
        "\\bfoo\\b",
        "\\Bfoo",
        "\\B\\w+",
        "(?U)\\bfoo",
        "(?U)\\w+\\b",
        "(?U)\\Bo",
        "\\b",
        "\\B",
        "(?U)\\b",
        "(?U)\\B",
        "(?U)\\ba?",
        "(?U).\\b",
        "[a-z]+",
        "o+"
      })
  void findFromEveryStartMatchesTheNfa(String regex) {
    Pattern dfa = Pattern.compile(regex);
    Pattern nfa = Pattern.compile(regex, 0, EnginePathOptions.builder().dfa(false).build());
    for (String input : INPUTS) {
      for (int from = 0; from <= input.length(); from++) {
        assertThat(trace(dfa.matcher(input), from))
            .as("/%s/ on %s from %d", regex, input, from)
            .isEqualTo(trace(nfa.matcher(input), from));
      }
      byte[] bytes = input.getBytes(UTF_8);
      assertThat(trace(dfa.matcher(Utf8Input.trusted(bytes))))
          .as("UTF-8 /%s/ on %s", regex, input)
          .isEqualTo(trace(nfa.matcher(Utf8Input.trusted(bytes))));
    }
  }

  private static List<String> trace(Matcher matcher, int from) {
    List<String> out = new ArrayList<>();
    boolean found = matcher.find(from);
    while (found) {
      out.add(matcher.start() + ":" + matcher.end());
      found = matcher.find();
    }
    return out;
  }

  @ParameterizedTest
  @ValueSource(strings = {"(?U)\\b", "(?U)\\ba?", "(?U).\\b"})
  void wordAssertionsAfterSplitSurrogateCursorsMatchInputContext(String regex) {
    Pattern pattern = Pattern.compile(regex);
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);
    for (String input :
        List.of("\uD801\uDC00b", "\uD801\uDC00", "\uDC00b", "\uD801b", "\uD83D\uDE00b")) {
      for (int from = 0; from <= input.length(); from++) {
        java.util.regex.Matcher expected = jdk.matcher(input);
        List<String> bounds = new ArrayList<>();
        boolean found = expected.find(from);
        while (found) {
          bounds.add(expected.start() + ":" + expected.end());
          found = expected.find();
        }
        assertThat(trace(pattern.matcher(input), from))
            .as("/%s/ input=%s from=%s", regex, input, from)
            .isEqualTo(bounds);
      }
    }
  }

  private static List<String> trace(Utf8Matcher matcher) {
    List<String> out = new ArrayList<>();
    while (matcher.find()) {
      out.add(matcher.start() + ":" + matcher.end());
    }
    return out;
  }
}
