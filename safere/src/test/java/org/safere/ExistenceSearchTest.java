// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("compares optimized searches with the JDK")
class ExistenceSearchTest {
  private static final List<String> PATTERNS =
      List.of(
          "([\\S]+)\\b\\[(.+)\\]",
          "a.*z",
          "(a.*z|b)",
          "(b|a.*z)",
          "a.*?z",
          "(a.*z)?",
          "(a|ab)+",
          "a.*$",
          "(?s:a.*)\\z",
          "(a?)*y",
          "\\b[a-z]+\\b",
          "(?:a.*z|.)");

  @Test
  void existenceAndDeferredSelectionPreserveAllMatches() {
    for (String regex : PATTERNS) {
      for (String input :
          List.of(
              "config.overrides[some_value] ".repeat(80),
              "a b " + "x".repeat(2000) + "z suffix",
              "a b\n" + "x".repeat(2000) + "z\n",
              "éλ a b " + "x".repeat(2000) + "z",
              "x".repeat(2000))) {
        Pattern pattern = Pattern.compile(regex);
        assertThat(pattern.matcher(input).find())
            .as(regex)
            .isEqualTo(java.util.regex.Pattern.compile(regex).matcher(input).find());
        assertThat(pattern.find(Utf8Input.trusted(input.getBytes(UTF_8))))
            .as(regex)
            .isEqualTo(java.util.regex.Pattern.compile(regex).matcher(input).find());
        checkSequence(regex, input, false, false);
        checkSequence(regex, input, true, false);
        checkSequence(regex, input, false, true);
      }
    }
  }

  private static void checkSequence(String regex, String input, boolean utf8, boolean region) {
    Matcher actual =
        utf8
            ? new Matcher(Pattern.compile(regex), new Utf8InputScanner(input.getBytes(UTF_8)))
            : Pattern.compile(regex).matcher(input);
    java.util.regex.Matcher expected = java.util.regex.Pattern.compile(regex).matcher(input);
    if (region) {
      actual.region(
          1, utf8 ? Utf8Input.trusted(input.getBytes(UTF_8)).length() - 1 : input.length() - 1);
      expected.region(1, input.length() - 1);
    }
    while (true) {
      boolean found = expected.find();
      assertThat(actual.find()).as(regex).isEqualTo(found);
      if (!found) {
        break;
      }
      for (int group = 0; group <= expected.groupCount(); group++) {
        int start = expected.start(group);
        int end = expected.end(group);
        assertThat(actual.start(group))
            .as(regex)
            .isEqualTo(
                utf8 && start >= 0 ? input.substring(0, start).getBytes(UTF_8).length : start);
        assertThat(actual.end(group))
            .as(regex)
            .isEqualTo(utf8 && end >= 0 ? input.substring(0, end).getBytes(UTF_8).length : end);
      }
    }
  }

  @Test
  void unreadMatchesStillAdvanceAndResetCorrectly() {
    String input = "a b " + "x".repeat(2000) + "z tail";
    Matcher matcher = Pattern.compile("a.*z|b").matcher(input);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.find()).isFalse();
    assertThat(matcher.reset().find()).isTrue();
    assertThat(matcher.toString())
        .contains("lastmatch=" + input.substring(0, input.indexOf(" tail")));
    assertThat(matcher.reset().replaceAll("!")).isEqualTo("! tail");
    assertThat(matcher.reset().find()).isTrue();
    assertThat(matcher.toMatchResult().group())
        .isEqualTo(input.substring(0, input.indexOf(" tail")));
  }

  @Test
  void selectingGroupZeroKeepsInnerCapturesLazy() {
    String input = "az" + "x".repeat(4096) + "z";
    for (boolean utf8 : List.of(false, true)) {
      Pattern pattern = Pattern.compile("(a.*z)");
      Matcher matcher =
          utf8
              ? new Matcher(pattern, new Utf8InputScanner(input.getBytes(UTF_8)))
              : pattern.matcher(input);
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.capturesResolved()).isFalse();
      assertThat(matcher.end()).isEqualTo(input.length());
      assertThat(matcher.capturesResolved()).isFalse();
      assertThat(matcher.start(1)).isZero();
      assertThat(matcher.capturesResolved()).isTrue();
      assertThat(matcher.end(1)).isEqualTo(input.length());
    }
  }

  @Test
  void boundedProbeKeepsLateSelectionInTheOriginalPass() {
    Pattern pattern = Pattern.compile("(a.*z)");
    for (int size : List.of(511, 512, 513, 4096)) {
      String earlyInput = "az" + "x".repeat(size) + "z";
      Matcher matcher = pattern.matcher(earlyInput);
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.start()).isZero();
      assertThat(matcher.end()).isEqualTo(earlyInput.length());
      assertThat(matcher.find()).isFalse();
    }
    String input = "a" + "x".repeat(4096) + "z";
    Dfa.SearchResult late =
        pattern
            .forwardExistenceDfa()
            .searchWithEarlyExistence(
                new StringInputScanner(input), 0, AdaptiveBackoff.NEUTRAL, 256);
    assertThat(late.matched()).isFalse();
    assertThat(late.scanLimitReached()).isTrue();
    assertThat(pattern.forwardFirstMatchDfa().doSearch(input, false, false).pos())
        .isEqualTo(input.length());
  }

  @Test
  void minimumLengthBeyondProbeWindowUsesExactSelection() {
    Pattern pattern = Pattern.compile("([a-z]{300})#[0-9]{300}");
    String input = "a".repeat(300) + "#" + "1".repeat(300) + " ".repeat(4096);
    Matcher matcher = pattern.matcher(input);
    assertThat(matcher.find()).isTrue();
    assertThat(pattern.getOrCreateThreadCache().forwardExistenceDfa).isNull();
    assertThat(matcher.end()).isEqualTo(601);
    assertThat(matcher.group(1)).isEqualTo("a".repeat(300));
  }

  @Test
  void probeLimitIsNotAnAssertionOrScalarBoundary() {
    for (String regex : List.of("x{256}$", "(?m)x{256}$", "x{256}\\b", "x{255}.\\z", "x{255}.")) {
      for (String input :
          List.of("x".repeat(256) + "y".repeat(1024), "x".repeat(255) + "😀" + "y".repeat(1024))) {
        boolean expected = java.util.regex.Pattern.compile(regex).matcher(input).find();
        Pattern pattern = Pattern.compile(regex);
        for (InputScanner scanner :
            List.of(new StringInputScanner(input), new Utf8InputScanner(input.getBytes(UTF_8)))) {
          Dfa.SearchResult probe =
              pattern
                  .forwardExistenceDfa()
                  .searchWithEarlyExistence(scanner, 0, AdaptiveBackoff.NEUTRAL, 256);
          if (!expected) {
            assertThat(probe.matched()).as(regex).isFalse();
          }
          assertThat(
                  pattern
                      .forwardExistenceDfa()
                      .searchExists(scanner, 0, false, false, AdaptiveBackoff.NEUTRAL)
                      .matched())
              .as(regex)
              .isEqualTo(expected);
        }
        assertThat(pattern.matcher(input).find()).as(regex).isEqualTo(expected);
      }
    }
  }

  @Test
  void impossibleProbeDoesNotReadDeadTransitionSlots() {
    String input = "x".repeat(1024);
    Dfa dfa = Pattern.compile("[a&&b]").forwardExistenceDfa();
    for (InputScanner scanner :
        List.of(new StringInputScanner(input), new Utf8InputScanner(input.getBytes(UTF_8)))) {
      assertThat(dfa.searchWithEarlyExistence(scanner, 0, AdaptiveBackoff.NEUTRAL, 256).matched())
          .isFalse();
    }
  }

  @Test
  void completeProbeProcessesRealEndAssertions() {
    String input = "x".repeat(32);
    for (String regex : List.of("x+\\z", "x+\\b", "(?m)x+$", "x+")) {
      Pattern pattern = Pattern.compile(regex);
      for (InputScanner scanner :
          List.of(new StringInputScanner(input), new Utf8InputScanner(input.getBytes(UTF_8)))) {
        Dfa.SearchResult result =
            pattern
                .forwardExistenceDfa()
                .searchWithEarlyExistence(scanner, 0, AdaptiveBackoff.NEUTRAL, 256);
        assertThat(result.matched()).as(regex).isTrue();
        assertThat(result.pos()).isBetween(1, input.length());
      }
    }
  }

  @Test
  void supplementaryInputPreservesGreedyBoundsAndCaptures() {
    checkSequence("(a.*z)", "aé😀z" + "é😀".repeat(1000) + "z", false, false);
    checkSequence("(a.*z)", "aé😀z" + "é😀".repeat(1000) + "z", true, false);
  }

  @Test
  void existenceProbeStopsBeforeGreedyPreferredEnd() {
    Pattern pattern = Pattern.compile("a.*z");
    String input = "az" + "x".repeat(2000) + "z";
    Dfa dfa = pattern.forwardExistenceDfa();
    for (InputScanner scanner :
        List.of(new StringInputScanner(input), new Utf8InputScanner(input.getBytes(UTF_8)))) {
      Dfa.SearchResult result = dfa.searchExists(scanner, 0, false, false, AdaptiveBackoff.NEUTRAL);
      assertThat(result.matched()).isTrue();
      assertThat(result.pos()).isLessThan(10);
      assertThat(pattern.forwardFirstMatchDfa().doSearch(scanner, 0, false, false).pos())
          .isEqualTo(input.length());
    }
  }

  @Test
  void deferredSelectionSupportsBothProgramKindsAcrossPatternChanges() {
    for (boolean utf8 : List.of(false, true)) {
      for (boolean nullableFirst : List.of(false, true)) {
        String greedyInput = "az" + "x".repeat(1024) + "z";
        String nullableInput = "a".repeat(1024) + "y";
        String[] regexes =
            nullableFirst ? new String[] {"(a?)*y", "(a.*z)"} : new String[] {"(a.*z)", "(a?)*y"};
        String input = greedyInput + nullableInput;
        Pattern pattern = Pattern.compile(regexes[0]);
        Matcher matcher =
            utf8
                ? new Matcher(pattern, new Utf8InputScanner(input.getBytes(UTF_8)))
                : pattern.matcher(input);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.capturesResolved()).isFalse();
        // Changing patterns consumes the original deferred result with its original program.
        matcher.usePattern(Pattern.compile(regexes[1]));
        assertThat(input.substring(matcher.start(), matcher.end()))
            .isEqualTo(nullableFirst ? nullableInput : greedyInput);
        matcher.reset();
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(nullableFirst ? 0 : greedyInput.length());
        assertThat(matcher.end()).isEqualTo(nullableFirst ? greedyInput.length() : input.length());
        assertThat(input.substring(matcher.start(1), matcher.end(1)))
            .isEqualTo(nullableFirst ? greedyInput : "");
        assertThat(matcher.find()).isFalse();
      }
    }
  }
}
