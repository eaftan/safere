// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Executable boundary rules and bounded composition checks; see docs/GRAPHEME_REGIONS.md. */
@DisabledForCrosscheck("Includes approved region-local surrogate and grapheme search semantics")
class RegionAssertionCompositionTest {
  private static final String EMOJI = "\uD83D\uDC4D";
  private static final String LETTER = "\uD801\uDC00";
  private static final String RI = "\uD83C\uDDE6";

  // The fuzzer uses the same atom/assertion/atom grammar, with generated inputs and regions.
  private static final List<String> LEFT =
      List.of(".", "(.)?", "(.)*", "(.)+?", "[\\s\\S]", "\\p{Cs}", "\\P{Cs}");
  private static final List<String> ASSERTIONS =
      List.of(
          "\\b",
          "\\B",
          "(?U:\\b)",
          "(?U:\\B)",
          "\\b{g}",
          "^",
          "$",
          "\\A",
          "\\z",
          "\\Z",
          "(?m:^)",
          "(?m:$)",
          "(?dm:$)",
          "\\b{g}$",
          "(?U:\\b)\\b{g}",
          "\\b{g}\\b{g}");
  private static final List<String> RIGHT = List.of("", ".", "(.)?");
  private static final List<String> INPUTS =
      List.of(
          "",
          "a",
          EMOJI,
          "a" + LETTER,
          RI.repeat(3),
          "a\u0301",
          "\uD83D\uDC69\u200D\uD83D\uDC69",
          "\uD83Dx\uDC4D",
          "\u1100\u1161",
          "\u0915\u094D\u0937",
          " a ");

  private record Rule(
      String name,
      String regex,
      String input,
      int start,
      int end,
      boolean anchoring,
      int opaqueEnd,
      int transparentEnd) {
    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Arguments> boundaryRules() {
    // These rows have a single possible prefix endpoint, so full matching additionally requires
    // that endpoint to equal the logical region end. -1 means no prefix match.
    List<Rule> rules =
        List.of(
            new Rule("ordinary split high", ".", EMOJI, 0, 1, true, 1, 1),
            new Rule("ordinary complete pair", ".", EMOJI, 0, 2, true, 2, 2),
            new Rule("ordinary exposed low", ".", EMOJI, 1, 2, true, 2, 2),
            new Rule("surrogate category", "\\p{Cs}", EMOJI, 0, 1, true, 1, 1),
            new Rule("non-surrogate category", "\\P{Cs}", EMOJI, 0, 1, true, -1, -1),
            new Rule("grapheme completion", "\\X", EMOJI, 0, 1, true, 2, 2),
            new Rule("assertion after completion", "\\X\\b{g}", EMOJI, 0, 1, true, 2, 2),
            new Rule("scalar grapheme endpoint", ".\\b{g}", EMOJI, 0, 1, true, 1, -1),
            new Rule("opaque start edge", "\\b{g}.", EMOJI, 1, 2, true, 2, -1),
            new Rule("empty region inside pair", "\\b{g}", EMOJI, 1, 1, true, 1, -1),
            new Rule("Unicode word category", "(?U:\\b).", LETTER, 0, 1, true, -1, 1),
            new Rule("Unicode non-word category", "(?U:\\B).", LETTER, 0, 1, true, 1, -1),
            new Rule(
                "word context after completion", "\\X(?U:\\b)", LETTER + "a", 0, 1, true, 2, -1),
            new Rule("RI context after completion", "\\X\\b{g}", RI.repeat(2), 0, 1, true, 2, -1),
            new Rule("interior RI context", ".\\b{g}.", RI.repeat(2), 1, 3, true, -1, -1),
            new Rule("region end anchor", ".$", EMOJI, 0, 1, true, 1, 1),
            new Rule("input end anchor", ".$", EMOJI, 0, 1, false, -1, -1),
            new Rule("completed region end anchor", "\\X$", EMOJI, 0, 1, true, 2, 2),
            new Rule("completed input end anchor", "\\X$", EMOJI, 0, 1, false, 2, 2),
            new Rule("strict region end", ".\\z", EMOJI, 0, 1, true, 1, 1),
            new Rule("strict input end", ".\\z", EMOJI, 0, 1, false, -1, -1),
            new Rule("region start anchor", "^.", "a" + EMOJI, 1, 2, true, 2, 2),
            new Rule("input start anchor", "^.", "a" + EMOJI, 1, 2, false, -1, -1),
            new Rule("multiline input start", "(?m:^.)", "\n" + EMOJI, 1, 2, false, 2, 2));
    return rules.stream()
        .flatMap(
            rule ->
                Stream.of(false, true)
                    .flatMap(
                        transparent ->
                            Stream.of(false, true)
                                .map(full -> Arguments.of(rule, transparent, full))));
  }

  @ParameterizedTest(name = "{0}, transparent={1}, full={2}")
  @MethodSource("boundaryRules")
  void consumptionAssertionsAndAnchorsFollowTheDecisionTable(
      Rule rule, boolean transparent, boolean full) {
    Matcher matcher = Pattern.compile(rule.regex()).matcher(rule.input());
    configure(matcher, rule.start(), rule.end(), transparent, rule.anchoring());
    int expectedEnd = transparent ? rule.transparentEnd() : rule.opaqueEnd();
    boolean expected = expectedEnd >= 0 && (!full || expectedEnd == rule.end());
    assertThat(full ? matcher.matches() : matcher.lookingAt()).isEqualTo(expected);
    if (expected) {
      assertThat(matcher.start()).isEqualTo(rule.start());
      assertThat(matcher.end()).isEqualTo(expectedEnd);
    }
  }

  @Test
  void unreachableGraphemeBranchesPreserveAnchoredResultsAndCaptures() {
    checkGeneratedComposition(INPUTS, true);
  }

  @Test
  void lineAssertionCompositionIncludesSlicedRegions() {
    checkGeneratedComposition(List.of("\n", "a\n", "\u0085a", "a\u0085"), true);
    // CRLF-sliced compositions containing grapheme assertions are tracked in #1038.
    // Keep this restriction on the input domain, not on individual patterns.
    checkGeneratedComposition(List.of("\r\na"), false);
  }

  private static void checkGeneratedComposition(List<String> inputs, boolean everyRegion) {
    for (String left : LEFT) {
      for (String assertion : ASSERTIONS) {
        for (String right : RIGHT) {
          String regex = left + assertion + right;
          Pattern ordinary = Pattern.compile(regex);
          Pattern appended = Pattern.compile("(?:" + regex + ")|(?:\\Xz)");
          Pattern prepended = Pattern.compile("(?:\\Xz)|(?:" + regex + ")");
          for (String input : inputs) {
            assertThat(input).doesNotContain("z");
            boolean comparableFind = input.codePoints().allMatch(Character::isBmpCodePoint);
            Matcher expected = ordinary.matcher(input);
            Matcher after = appended.matcher(input);
            Matcher before = prepended.matcher(input);
            for (int start = 0; start <= (everyRegion ? input.length() : 0); start++) {
              for (int end = everyRegion ? start : input.length(); end <= input.length(); end++) {
                for (boolean transparent : new boolean[] {false, true}) {
                  for (boolean anchoring : new boolean[] {false, true}) {
                    for (boolean full : new boolean[] {false, true}) {
                      configure(expected, start, end, transparent, anchoring);
                      configure(after, start, end, transparent, anchoring);
                      configure(before, start, end, transparent, anchoring);
                      boolean matched = full ? expected.matches() : expected.lookingAt();
                      compareResult(
                          expected,
                          matched,
                          after,
                          full ? after.matches() : after.lookingAt(),
                          input,
                          full);
                      compareResult(
                          expected,
                          matched,
                          before,
                          full ? before.matches() : before.lookingAt(),
                          input,
                          full);
                    }
                    // Supplementary find() candidate policies are tested separately. An
                    // existing grapheme assertion alone does not establish equal candidate
                    // sets: ordinary prefix acceleration may still skip complete pairs.
                    if (comparableFind) {
                      configure(expected, start, end, transparent, anchoring);
                      configure(after, start, end, transparent, anchoring);
                      configure(before, start, end, transparent, anchoring);
                      List<String> trace = findTrace(expected, input.length());
                      assertThat(findTrace(after, input.length()))
                          .as(label(after))
                          .isEqualTo(trace);
                      assertThat(findTrace(before, input.length()))
                          .as(label(before))
                          .isEqualTo(trace);
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  @Test
  void findPreservesItsCandidateStartsAndTerminalProgress() {
    Matcher ordinary = Pattern.compile("\\p{Cs}").matcher(EMOJI);
    Matcher grapheme = Pattern.compile("(?:\\p{Cs})|(?:\\Xz)").matcher(EMOJI);
    assertThat(ordinary.find()).isFalse();
    assertThat(grapheme.find()).isTrue();
    assertThat(grapheme.start()).isEqualTo(1);
    assertThat(grapheme.end()).isEqualTo(2);
    assertThat(grapheme.find()).isFalse();

    for (boolean transparent : new boolean[] {false, true}) {
      for (Map.Entry<String, Integer> row :
          Map.of(".", 1, "(?:.|\\X)", 1, "(?:\\X|.)", 2, "\\X", 2).entrySet()) {
        Matcher consuming = Pattern.compile(row.getKey()).matcher(EMOJI).region(0, 1);
        consuming.useTransparentBounds(transparent);
        assertThat(consuming.find()).as(row.getKey()).isTrue();
        assertThat(consuming.start()).isZero();
        assertThat(consuming.end()).isEqualTo(row.getValue());
        assertThat(consuming.find()).isFalse();
      }
      Matcher boundary = Pattern.compile("\\b{g}").matcher(EMOJI).region(0, 1);
      boundary.useTransparentBounds(transparent);
      List<String> expected =
          transparent ? List.of("0:0", "false") : List.of("0:0", "1:1", "false");
      assertThat(findTrace(boundary, EMOJI.length())).isEqualTo(expected);
      if (!transparent) {
        // A terminal empty match exhausts find(); a failure at an earlier position has a
        // separate continuation contract and is not asserted to be permanently exhausted.
        assertThat(boundary.find()).isFalse();
      }
    }
  }

  @Test
  void disablingAccelerationPreservesGraphemeAssertionCandidateStarts() {
    String regex = "\\p{Cs}\\b{g}";
    Pattern accelerated = Pattern.compile(regex);
    Pattern unaccelerated =
        Pattern.compile(regex, 0, EnginePathOptions.builder().startAcceleration(false).build());
    assertThat(findTrace(accelerated.matcher(EMOJI), EMOJI.length()))
        .isEqualTo(findTrace(unaccelerated.matcher(EMOJI), EMOJI.length()));
  }

  @Test
  void unreachableGraphemeBranchPreservesOpaqueSplitCrlfAnchorContext() {
    for (String regex : List.of("(.)?$", "(?:(.)?$)|(?:\\Xz)", "(?:\\Xz)|(?:(.)?$)")) {
      Matcher matcher = Pattern.compile(regex).matcher("\r\na").region(1, 2);
      assertThat(matcher.lookingAt()).as(regex).isTrue();
      assertThat(matcher.start()).isEqualTo(1);
      assertThat(matcher.end()).isEqualTo(1);
    }
  }

  @Test
  void unreachableGraphemeBranchPreservesMultilineAnchorAtTheRegionEnd() {
    for (String regex :
        List.of("[\\s\\S](?m:^)", "(?:[\\s\\S](?m:^))|(?:\\Xz)", "(?:\\Xz)|(?:[\\s\\S](?m:^))")) {
      Matcher matcher = Pattern.compile(regex).matcher("\na\u0085").region(0, 1);
      assertThat(matcher.lookingAt()).as(regex).isFalse();
    }
  }

  @Test
  @Disabled("Pre-existing split-CRLF grapheme/line assertion inconsistency; #1038")
  void unreachableGraphemeBranchPreservesSplitCrlfGraphemeAndLineAssertions() {
    for (String regex :
        List.of("(.)?\\b{g}$", "(?:(.)?\\b{g}$)|(?:\\Xz)", "(?:\\Xz)|(?:(.)?\\b{g}$)")) {
      Matcher matcher = Pattern.compile(regex).matcher("\r\na").region(1, 2);
      assertThat(matcher.lookingAt()).as(regex).isTrue();
      assertThat(matcher.start()).isEqualTo(1);
      assertThat(matcher.end()).isEqualTo(1);
    }
  }

  private static void configure(
      Matcher matcher, int start, int end, boolean transparent, boolean anchoring) {
    matcher.region(start, end).useTransparentBounds(transparent).useAnchoringBounds(anchoring);
  }

  private static void compareResult(
      Matcher expected,
      boolean expectedMatch,
      Matcher actual,
      boolean actualMatch,
      String input,
      boolean full) {
    boolean same = actualMatch == expectedMatch;
    if (same && actualMatch) {
      for (int group = 0; group <= expected.groupCount(); group++) {
        if (actual.start(group) != expected.start(group)
            || actual.end(group) != expected.end(group)
            || !Objects.equals(actual.group(group), expected.group(group))) {
          same = false;
          break;
        }
      }
    }
    if (!same) {
      assertThat(resultTrace(actual, actualMatch))
          .as(
              "%s input=%s full=%s",
              label(actual), input.codePoints().mapToObj(Integer::toHexString).toList(), full)
          .isEqualTo(resultTrace(expected, expectedMatch));
    }
  }

  private static List<String> resultTrace(Matcher matcher, boolean matched) {
    List<String> result = new ArrayList<>();
    result.add(Boolean.toString(matched));
    if (matched) {
      for (int group = 0; group <= matcher.groupCount(); group++) {
        result.add(matcher.start(group) + ":" + matcher.end(group));
        result.add(matcher.group(group));
      }
    }
    return result;
  }

  private static List<String> findTrace(Matcher matcher, int inputLength) {
    List<String> trace = new ArrayList<>();
    for (int step = 0; step <= inputLength + 1; step++) {
      if (!matcher.find()) {
        trace.add("false");
        return trace;
      }
      for (int group = 0; group <= matcher.groupCount(); group++) {
        trace.add(matcher.start(group) + ":" + matcher.end(group));
      }
    }
    throw new AssertionError("find() did not exhaust: " + label(matcher));
  }

  private static String label(Matcher matcher) {
    return matcher.pattern()
        + " region="
        + matcher.regionStart()
        + ":"
        + matcher.regionEnd()
        + " transparent="
        + matcher.hasTransparentBounds()
        + " anchoring="
        + matcher.hasAnchoringBounds();
  }
}
