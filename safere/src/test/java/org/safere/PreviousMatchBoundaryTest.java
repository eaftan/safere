// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PreviousMatchBoundaryTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "\\G",
        "\\G.",
        "\\G[a-z]+,?",
        "(\\G)([a-z]+),?",
        "\\Ga|b",
        "a\\G",
        "(?:\\G|a)*",
        "(?:a|\\G)+",
        "\\G?+a",
        "(?:\\G){2}",
        "\\G.*?b",
        "^\\G.*$",
        "\\G\\b\\w+",
        "\\G\\X",
        "(\\G(a?))*",
        "\\G|b"
      })
  void findSequencesMatchJdk(String regex) {
    for (String input : List.of("", "a", "ab", "ba!ba", "one,two,!three", "é😀a", "a\nb")) {
      assertFinds(
          Pattern.compile(regex).matcher(input),
          java.util.regex.Pattern.compile(regex).matcher(input),
          8);
    }
  }

  @Test
  void explicitStartAndResetsEstablishFreshBoundary() {
    Matcher safe = Pattern.compile("\\G.").matcher("abcd");
    var jdk = java.util.regex.Pattern.compile("\\G.").matcher("abcd");
    assertThat(safe.find(2)).isEqualTo(jdk.find(2));
    assertThat(safe.start()).isEqualTo(2);
    assertFinds(safe, jdk, 3);
    safe.reset();
    jdk.reset();
    assertFinds(safe, jdk, 5);
    safe.reset("xyz");
    jdk.reset("xyz");
    assertFinds(safe, jdk, 4);
  }

  @Test
  void successfulAndFailedOperationsPreservePreviousEndAcrossPatternChanges() {
    for (String operation : List.of("find", "lookingAt", "matches")) {
      Matcher safe = Pattern.compile("a").matcher("ba!");
      var jdk = java.util.regex.Pattern.compile("a").matcher("ba!");
      assertThat(safe.find()).isEqualTo(jdk.find());
      boolean actual =
          switch (operation) {
            case "lookingAt" -> safe.lookingAt();
            case "matches" -> safe.matches();
            default -> safe.find();
          };
      boolean expected =
          switch (operation) {
            case "lookingAt" -> jdk.lookingAt();
            case "matches" -> jdk.matches();
            default -> jdk.find();
          };
      assertThat(actual).isEqualTo(expected);
      safe.usePattern(Pattern.compile("\\G!"));
      jdk.usePattern(java.util.regex.Pattern.compile("\\G!"));
      assertFinds(safe, jdk, 3);
    }
  }

  @Test
  void replacementFastPathsRetainBoundaryWhenChangingToPreviousMatchAssertion() {
    for (String regex : List.of("a", "[a]+", "(a)", "a|b", "^aba!$", "a*")) {
      Matcher safe = Pattern.compile(regex).matcher("aba!");
      var jdk = java.util.regex.Pattern.compile(regex).matcher("aba!");
      assertThat(safe.replaceAll("X")).isEqualTo(jdk.replaceAll("X"));
      safe.usePattern(Pattern.compile("\\G!"));
      jdk.usePattern(java.util.regex.Pattern.compile("\\G!"));
      assertFinds(safe, jdk, 3);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"^a", "\\Aa", "^ab", "^a(b)", "^(ab)"})
  void anchoredLiteralReplacementsRetainSuccessfulEndAndContinuation(String regex) {
    for (boolean firstOnly : List.of(false, true)) {
      for (String replacement : List.of("X", "$0X")) {
        Matcher safe = Pattern.compile(regex).matcher("ab!");
        var jdk = java.util.regex.Pattern.compile(regex).matcher("ab!");
        assertThat(firstOnly ? safe.replaceFirst(replacement) : safe.replaceAll(replacement))
            .isEqualTo(firstOnly ? jdk.replaceFirst(replacement) : jdk.replaceAll(replacement));
        safe.usePattern(Pattern.compile("\\G."));
        jdk.usePattern(java.util.regex.Pattern.compile("\\G."));
        assertFinds(safe, jdk, 4);
      }
    }
  }

  @Test
  void anchoredAttemptsUsePreviousEndEvenWhenTheyRestartAtRegionBeginning() {
    Matcher safe = Pattern.compile("\\Ga").matcher("aa");
    var jdk = java.util.regex.Pattern.compile("\\Ga").matcher("aa");
    assertThat(safe.lookingAt()).isEqualTo(jdk.lookingAt());
    assertThat(safe.lookingAt()).isEqualTo(jdk.lookingAt());
    assertFinds(safe, jdk, 3);
    safe.reset();
    jdk.reset();
    assertThat(safe.find()).isEqualTo(jdk.find());
    safe.usePattern(Pattern.compile("a\\G"));
    jdk.usePattern(java.util.regex.Pattern.compile("a\\G"));
    assertThat(safe.lookingAt()).isEqualTo(jdk.lookingAt());
    assertThat(safe.group()).isEqualTo(jdk.group());
  }

  @ParameterizedTest
  @ValueSource(strings = {"((.?\\G)*)", "((.?\\G)+)", "(((.?\\G)*)*)", "((.?\\G){1,3})"})
  void nullableRepetitionsCaptureFinalEmptyIterationAtPreviousMatchEnd(String regex) {
    for (String token : List.of("a", "é", "😀")) {
      for (boolean transparent : List.of(false, true)) {
        for (int start : new int[] {0, 1}) {
          String input = "z".repeat(start) + token + "!";
          Matcher safe =
              Pattern.compile(java.util.regex.Pattern.quote(token))
                  .matcher(input)
                  .region(start, start + token.length())
                  .useTransparentBounds(transparent);
          var jdk =
              java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(token))
                  .matcher(input)
                  .region(start, start + token.length())
                  .useTransparentBounds(transparent);
          assertThat(safe.find()).isEqualTo(jdk.find());
          safe.usePattern(Pattern.compile(regex));
          jdk.usePattern(java.util.regex.Pattern.compile(regex));
          assertThat(safe.lookingAt()).isEqualTo(jdk.lookingAt());
          for (int group = 0; group <= safe.groupCount(); group++) {
            assertThat(safe.start(group)).isEqualTo(jdk.start(group));
            assertThat(safe.end(group)).isEqualTo(jdk.end(group));
            assertThat(safe.group(group)).isEqualTo(jdk.group(group));
          }
        }
      }
    }
  }

  @Test
  void regionsAndBoundsKeepBoundaryInInputCoordinates() {
    for (boolean anchoring : List.of(false, true)) {
      for (boolean transparent : List.of(false, true)) {
        for (String regex : List.of("\\G.", "(\\G)(.)", "\\G|b", "a\\G")) {
          Matcher safe =
              Pattern.compile(regex)
                  .matcher("zabaz")
                  .region(1, 4)
                  .useAnchoringBounds(anchoring)
                  .useTransparentBounds(transparent);
          var jdk =
              java.util.regex.Pattern.compile(regex)
                  .matcher("zabaz")
                  .region(1, 4)
                  .useAnchoringBounds(anchoring)
                  .useTransparentBounds(transparent);
          assertFinds(safe, jdk, 6);
          safe.region(2, 4);
          jdk.region(2, 4);
          assertFinds(safe, jdk, 5);
          assertThat(safe.find(0)).isEqualTo(jdk.find(0));
          assertThat(safe.regionStart()).isEqualTo(0);
        }
      }
    }
  }

  @Test
  void splitAndReplacementUseCanonicalMatchSequence() {
    for (String regex : List.of("\\G[a-z]+,?", "\\G", "\\G|b", "(\\G)([a-z]+),?")) {
      String input = "one,two,!three";
      Pattern safe = Pattern.compile(regex);
      var jdk = java.util.regex.Pattern.compile(regex);
      for (int limit : new int[] {-1, 0, 1, 2, 5}) {
        assertThat(safe.split(input, limit)).isEqualTo(jdk.split(input, limit));
        assertThat(safe.splitWithDelimiters(input, limit))
            .isEqualTo(jdk.splitWithDelimiters(input, limit));
      }
      assertThat(safe.splitAsStream(input).toList()).isEqualTo(jdk.splitAsStream(input).toList());
      assertThat(safe.matcher(input).replaceAll("X")).isEqualTo(jdk.matcher(input).replaceAll("X"));
      assertThat(safe.matcher(input).replaceFirst("X"))
          .isEqualTo(jdk.matcher(input).replaceFirst("X"));
    }
    assertThat(Pattern.compile("\\G([a-z]+),?").matcher("one,two,!three").replaceAll("<$1>"))
        .isEqualTo("<one><two>!three");
  }

  @Test
  @DisabledForCrosscheck("JDK failed searches can overwrite continuation with partial-match ends")
  void failedSearchesPreserveSuccessfulEndForEmptyRetries() {
    // Follow the successful-match boundary contract; see the approved policy for #1164.
    for (String regex : List.of("\\G", "a?\\G", "(a?\\G){1,3}", "(?:a|)\\G", "a*\\G")) {
      Matcher matcher = Pattern.compile(regex).matcher("aa!ba");
      for (int retry = 0; retry < 3; retry++) {
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isZero();
        assertThat(matcher.end()).isZero();
        assertThat(matcher.find()).isFalse();
      }
    }
  }

  @Test
  @DisabledForCrosscheck("JDK failed optional-prefix searches can move the next-search cursor")
  void failedPartialMatchesPreserveBoundaryAndContinuationAcrossPatternChanges() {
    for (String regex : List.of("a?b", "(?:a|)b", "[a]?b", "(?:a{0,1})b", "(a?)b")) {
      Matcher matcher = Pattern.compile(regex).matcher("ba");
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.group()).isEqualTo("b");
      assertThat(matcher.find()).isFalse();
      matcher.usePattern(Pattern.compile("\\Ga"));
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.start()).isEqualTo(1);
      assertThat(matcher.end()).isEqualTo(2);
    }
    for (String regex : List.of("[+-]?[0-9]+", "(?:[+-]|)[0-9]+", "([+-]?)[0-9]+")) {
      Matcher matcher = Pattern.compile(regex).matcher("1+");
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.group()).isEqualTo("1");
      assertThat(matcher.find()).isFalse();
      matcher.usePattern(Pattern.compile("\\G\\+"));
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.start()).isEqualTo(1);
      assertThat(matcher.group()).isEqualTo("+");
    }
  }

  @Test
  void predicatesAgreeWithInitialMatcherSearch() {
    for (String regex : List.of("\\Ga", "\\G[^!,]+,?", "(?:\\Ga|b)", "a\\G", "\\G.*z")) {
      Pattern pattern = Pattern.compile(regex);
      for (String input : List.of("", "ab", "ba", "aaz", "z", "é😀,")) {
        boolean expected = java.util.regex.Pattern.compile(regex).matcher(input).find();
        assertThat(pattern.asPredicate().test(input))
            .as("/%s/ on %s", regex, input)
            .isEqualTo(expected);
      }
    }
  }

  private static void assertFinds(Matcher safe, java.util.regex.Matcher jdk, int attempts) {
    for (int i = 0; i < attempts; i++) {
      boolean matched = jdk.find();
      assertThat(safe.find()).as("find attempt %s for %s", i, jdk.pattern()).isEqualTo(matched);
      if (matched) {
        for (int group = 0; group <= jdk.groupCount(); group++) {
          assertThat(safe.start(group)).isEqualTo(jdk.start(group));
          assertThat(safe.end(group)).isEqualTo(jdk.end(group));
          assertThat(safe.group(group)).isEqualTo(jdk.group(group));
        }
      }
    }
  }
}
