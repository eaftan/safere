// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Substring and original-input anchor models from docs/OPAQUE_REGION_BOUNDS.md. */
@DisabledForCrosscheck("Opaque substring semantics intentionally differ from JDK region behavior")
class OpaqueRegionContractTest {
  private static final List<String> INPUTS =
      List.of(
          "",
          "xcaty",
          "x\uD801\uDC00b\r\n",
          "\uDC4D\uD801\uDC00\r\uD801\uDC00b\r\n\u0085",
          "x\ncat\ny",
          "\r\na",
          "\r\nb",
          "\r\r\n",
          "a\u0085b",
          "\uD83D\uDE00\u0301",
          "x\uDE00\u0301",
          "\uD83D\uDE00\uD83C\uDFFB",
          "\uD83C\uDDE6\uD83C\uDDE7\uD83C\uDDE8",
          "\uD83D\uDC69\u200D\uD83D\uDCBB",
          "\u0600\uD83D\uDC69\u200D\uD83D\uDCBB",
          "a\u0301\u0300b",
          "\u0915\u094D\u0915",
          "\u1100\u1161\u11A8",
          "\uD83Dx\uDE00");

  private static final List<String> LOCAL_PATTERNS =
      List.of(
          ".",
          ".*",
          "(.)*",
          "[\\s\\S]+",
          "\\p{Cs}+",
          "\\P{Cs}+",
          "\\uD83D",
          "(\\uDE00)",
          "[\\uD83D]",
          "a\\uD83D",
          "\\x{1F600}",
          "\\b.",
          "(?U:\\b).",
          "(?U:\\B).",
          "(?U:\\b)a?",
          "(?U:\\B)a?",
          "\\ba?",
          "\\X",
          "(\\X)*",
          "\\b{g}",
          ".\\b{g}",
          "(.)\\b{g}(.)?",
          "[\\s\\S]\\b{g}a?",
          "\\X\\b{g}",
          "\\b{g}\\X",
          "\\b{g}\\X\\b{g}",
          "(\\X)|(.)",
          "(.)|(\\X)",
          "(?:.\\b{g})|(?:\\Xz)",
          "(?:\\Xz)|(?:.\\b{g})",
          "\\X|",
          "|\\X");

  @Test
  void anchoredOpaqueRegionsMatchIndependentSubstrings() {
    List<String> patterns = new ArrayList<>(LOCAL_PATTERNS);
    patterns.addAll(
        List.of(
            "^.*$",
            "\\A[\\s\\S]*\\z",
            "^\\X$",
            "^\\X\\b{g}$",
            "\\A\\X\\z",
            "\\X$",
            "\\X\\Z",
            "(?m)^",
            "(?m)$",
            "(?m)^\\X$",
            "(?d)(?m)^\\X$",
            "(.)?\\b{g}$"));
    checkSubstrings(patterns, true);
  }

  @Test
  void disablingAnchoringDoesNotExposeContextToOtherConstructs() {
    checkSubstrings(LOCAL_PATTERNS, false);
  }

  @Test
  void opaqueSubstringsPreserveTheContractAcrossEnginePaths() {
    for (EnginePathOptions options :
        List.of(
            EnginePathOptions.builder()
                .literalFastPaths(false)
                .charClassMatchFastPaths(false)
                .startAcceleration(false)
                .shiftDfa(false)
                .onePass(false)
                .dfa(false)
                .bitState(false)
                .build(),
            EnginePathOptions.builder()
                .literalFastPaths(false)
                .charClassMatchFastPaths(false)
                .shiftDfa(false)
                .onePass(false)
                .dfa(false)
                .build(),
            EnginePathOptions.builder()
                .literalFastPaths(false)
                .charClassMatchFastPaths(false)
                .shiftDfa(false)
                .onePass(false)
                .bitState(false)
                .build())) {
      checkSubstrings(LOCAL_PATTERNS, true, options);
    }
  }

  private static Stream<Arguments> graphemeCandidateCases() {
    Stream.Builder<Arguments> cases = Stream.builder();
    for (String regex :
        List.of(
            "\\uDE00\\X?",
            "\\p{Cs}\\X?",
            "(\\uDE00)(\\X?)",
            "\\X?\\uDE00",
            "(?:\\p{Cs})|(?:\\Xz)",
            "(?:\\Xz)|(?:\\p{Cs})",
            "(?:\\uDE00\\X?)|(?m:^Q)",
            "(?:\\p{Cs}\\X?)|(?m:^Q)",
            "(?:\\X?\\uDE00)|(?m:^Q)")) {
      for (String visible : List.of("😀", "😀😀", "😀\uDE00", "\uDE00😀", "x😀y", "\uDE00")) {
        for (String prefix : List.of("", "a", "ab", "\uD83D")) {
          for (boolean anchoring : List.of(false, true)) {
            cases.add(Arguments.of(regex, visible, prefix, anchoring));
          }
        }
      }
    }
    return cases.build();
  }

  @ParameterizedTest
  @MethodSource("graphemeCandidateCases")
  void opaqueGraphemeRegionsPreserveSubstringCandidateStarts(
      String regex, String visible, String prefix, boolean anchoring) {
    Pattern pattern = Pattern.compile(regex);
    String input = prefix + visible + "!";
    int start = prefix.length();
    for (int operation = 0; operation < 3; operation++) {
      Matcher region =
          pattern
              .matcher(input)
              .region(start, start + visible.length())
              .useAnchoringBounds(anchoring);
      assertThat(OpaqueRegionChecks.trace(region, operation, start))
          .as("%s regionStart=%s anchoring=%s operation=%s", regex, start, anchoring, operation)
          .isEqualTo(OpaqueRegionChecks.trace(pattern.matcher(visible), operation, 0));
    }
  }

  @Test
  void disabledAnchorsUseOriginalInputWhileOtherAssertionsStayOpaque() {
    for (String regex : List.of("cat", "\\bcat\\b", "\\b{g}cat\\b{g}")) {
      assertThat(
              Pattern.compile(regex)
                  .matcher("xcaty")
                  .region(1, 4)
                  .useAnchoringBounds(false)
                  .matches())
          .as(regex)
          .isTrue();
    }
    for (String regex : List.of("^cat$", "\\Acat\\z", "cat\\Z")) {
      assertThat(
              Pattern.compile(regex)
                  .matcher("xcaty")
                  .region(1, 4)
                  .useAnchoringBounds(false)
                  .matches())
          .as(regex)
          .isFalse();
    }
    for (String regex : List.of("(?m)^cat$", "cat$", "cat\\Z")) {
      assertThat(
              Pattern.compile(regex)
                  .matcher("x\ncat\n")
                  .region(2, 5)
                  .useAnchoringBounds(false)
                  .matches())
          .as(regex)
          .isTrue();
    }
    assertThat(Pattern.compile("\\Acat\\z").matcher("cat").useAnchoringBounds(false).matches())
        .isTrue();
    assertThat(
            Pattern.compile("(?m)^")
                .matcher("\r\n")
                .region(1, 2)
                .useAnchoringBounds(false)
                .lookingAt())
        .isFalse();
    assertThat(
            Pattern.compile("\\b{g}")
                .matcher("\r\n")
                .region(1, 2)
                .useAnchoringBounds(false)
                .lookingAt())
        .isTrue();
  }

  @Test
  void repeatedRegionsAndInputResetsKeepTheCorrectIndependentText() {
    Pattern pattern = Pattern.compile("(\\X)|(.)");
    Matcher actual = pattern.matcher("");
    for (String input : INPUTS) {
      actual.reset(input);
      for (int repeat = 0; repeat < 2; repeat++) {
        for (int start = 0; start <= input.length(); start++) {
          for (int end = start; end <= input.length(); end++) {
            actual.region(start, end);
            assertThat(OpaqueRegionChecks.trace(actual, 2, start))
                .isEqualTo(
                    OpaqueRegionChecks.trace(pattern.matcher(input.substring(start, end)), 2, 0));
          }
        }
      }
      actual.reset();
      assertThat(OpaqueRegionChecks.trace(actual, 2, 0))
          .isEqualTo(OpaqueRegionChecks.trace(pattern.matcher(input), 2, 0));
    }

    StringBuilder mutable = new StringBuilder("x\uD83D\uDE00y");
    actual.reset(mutable).region(1, 2);
    assertThat(actual.matches()).isTrue();
    assertThat(actual.group()).isEqualTo("\uD83D");
    mutable.replace(1, 3, "ab");
    actual.reset().region(1, 2);
    assertThat(actual.matches()).isTrue();
    assertThat(actual.group()).isEqualTo("a");
  }

  @Test
  void transparentAssertionContextDoesNotAllowConsumptionPastTheRegion() {
    String input = "\uD83D\uDC4D\uD83C\uDFFB";
    for (String regex : List.of("\\X\\b{g}", "\\b{g}\\X\\b{g}")) {
      Pattern pattern = Pattern.compile(regex);
      assertThat(pattern.matcher(input).region(1, 3).useTransparentBounds(true).matches())
          .as(regex)
          .isFalse();
      assertThat(pattern.matcher(input).region(1, 3).useTransparentBounds(true).lookingAt())
          .as(regex)
          .isFalse();
      assertThat(pattern.matcher(input).region(1, 3).useTransparentBounds(true).find())
          .as(regex)
          .isFalse();
    }
  }

  @Test
  void originalInputAnchorsDoNotWidenOpaqueGraphemeContext() {
    Matcher startsAfterLineFeed =
        Pattern.compile("(?m)^\\X\\b{g}")
            .matcher("\n\uD83D\uDE00\n")
            .region(1, 2)
            .useAnchoringBounds(false);
    assertThat(startsAfterLineFeed.matches()).isTrue();
    assertThat(startsAfterLineFeed.group()).isEqualTo("\uD83D");
    Matcher startsInsidePair =
        Pattern.compile("(?m)^\\X\\b{g}")
            .matcher("\n\uD83D\uDE00\n")
            .region(2, 3)
            .useAnchoringBounds(false);
    assertThat(startsInsidePair.matches()).isFalse();
    Matcher endsBeforeLineFeed =
        Pattern.compile("\\b{g}\\X(?m:$)")
            .matcher("\uD83D\uDE00\n")
            .region(1, 2)
            .useAnchoringBounds(false);
    assertThat(endsBeforeLineFeed.matches()).isTrue();
    assertThat(endsBeforeLineFeed.group()).isEqualTo("\uDE00");
  }

  @Test
  void sharedByteEngineKeepsOriginalAnchorsSeparateFromLocalGraphemeCoordinates() {
    byte[] bytes = "x\uD83C\uDDE6\uD83C\uDDE7\uD83C\uDDE8y".getBytes(UTF_8);
    Pattern pattern = Pattern.compile("\\b{g}\\X\\b{g}($)?");
    // Exercise the shared engine directly; Utf8Matcher does not expose bounds switches.
    Matcher actual = new Matcher(pattern, new Utf8InputScanner(bytes)).useAnchoringBounds(false);
    for (int start : new int[] {1, 5, 9}) {
      actual.region(start, 13);
      Utf8Matcher expected =
          pattern.matcher(Utf8Input.validated(Arrays.copyOfRange(bytes, start, 13)));
      List<Integer> expectedBounds = new ArrayList<>();
      while (expected.find()) {
        expectedBounds.add(expected.start());
        expectedBounds.add(expected.end());
      }
      List<Integer> actualBounds = new ArrayList<>();
      while (actual.find()) {
        actualBounds.add(actual.start() - start);
        actualBounds.add(actual.end() - start);
      }
      assertThat(actualBounds).isEqualTo(expectedBounds);
    }
  }

  private static void checkSubstrings(List<String> patterns, boolean anchoring) {
    checkSubstrings(patterns, anchoring, EnginePathOptions.allEnabled());
  }

  private static void checkSubstrings(
      List<String> patterns, boolean anchoring, EnginePathOptions options) {
    for (String regex : patterns) {
      Pattern pattern = Pattern.compile(regex, 0, options);
      Pattern reference = Pattern.compile(regex);
      for (String input : INPUTS) {
        for (int start = 0; start <= input.length(); start++) {
          for (int end = start; end <= input.length(); end++) {
            for (int operation = 0; operation < 3; operation++) {
              Matcher actual =
                  pattern.matcher(input).region(start, end).useAnchoringBounds(anchoring);
              Matcher expected = reference.matcher(input.substring(start, end));
              assertThat(OpaqueRegionChecks.trace(actual, operation, start))
                  .as(
                      "%s input=%s region=%s:%s anchoring=%s operation=%s",
                      regex, input, start, end, anchoring, operation)
                  .isEqualTo(OpaqueRegionChecks.trace(expected, operation, 0));
            }
          }
        }
      }
    }
  }
}
