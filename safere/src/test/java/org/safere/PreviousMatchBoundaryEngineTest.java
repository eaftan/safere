// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("Uses package-private engines, UTF-8 APIs, and work accounting")
class PreviousMatchBoundaryEngineTest {
  @Test
  void stringAndUtf8EnginesAgreeForMultibyteTokensAndRegions() {
    String input = "é😀,α,!tail";
    for (boolean bitState : List.of(false, true)) {
      for (String regex : List.of("\\G([^!,]+),?", "\\G.", "\\G|,", "(\\G.)*")) {
        Pattern pattern =
            Pattern.compile(regex, 0, EnginePathOptions.builder().bitState(bitState).build());
        for (int start : new int[] {0, 1, 3}) {
          Matcher string = pattern.matcher(input).region(start, input.length());
          Utf8Matcher utf8 =
              pattern
                  .matcher(Utf8Input.validated(input.getBytes(UTF_8)))
                  .region(
                      input.substring(0, start).getBytes(UTF_8).length,
                      input.getBytes(UTF_8).length);
          for (int i = 0; i < 8; i++) {
            boolean matched = string.find();
            assertThat(utf8.find()).isEqualTo(matched);
            if (matched) {
              for (int group = 0; group <= string.groupCount(); group++) {
                int from = string.start(group);
                int to = string.end(group);
                assertThat(utf8.start(group))
                    .isEqualTo(from < 0 ? -1 : input.substring(0, from).getBytes(UTF_8).length);
                assertThat(utf8.end(group))
                    .isEqualTo(to < 0 ? -1 : input.substring(0, to).getBytes(UTF_8).length);
              }
            }
          }
        }
      }
    }
  }

  @Test
  void exactEnginesAndPatternSetsRetainAssertionSemantics() {
    for (boolean bitState : List.of(false, true)) {
      for (String regex :
          List.of("\\G(a*)", "a*\\G", "(?:a|\\G)*", "\\Ga|b", "(?:\\G)*+a", "(?:a*\\G)*z")) {
        Pattern pattern =
            Pattern.compile(
                regex,
                0,
                EnginePathOptions.builder().bitState(bitState).startAcceleration(false).build());
        assertThat(pattern.forwardDfaSupported()).isFalse();
        assertThat(pattern.onePass()).isNull();
        assertThat(pattern.canUseReverseDfa()).isFalse();
        for (String input : List.of("", "aa!ba", "a".repeat(64) + "z")) {
          assertFinds(
              pattern.matcher(input), java.util.regex.Pattern.compile(regex).matcher(input), 5);
        }
      }
    }
    PatternSet.Builder builder = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
    builder.add("\\Ga");
    builder.add("a");
    builder.add("b");
    PatternSet set = builder.compile();
    assertThat(set.match("ba")).containsExactly(1, 2);
    assertThat(set.match("ab")).containsExactly(0, 1, 2);
    assertThat(Pattern.compile("\\Ga").asPredicate().test("ba")).isFalse();
    assertThat(Pattern.compile("\\Ga").asPredicate().test("ab")).isTrue();
  }

  @Test
  void failedSearchesRetainPreviousBoundaryAcrossExactEnginesAndInputViews() {
    String input = "éaa!ba😀";
    int end = input.length() - 2;
    byte[] bytes = input.getBytes(UTF_8);
    for (boolean bitState : List.of(false, true)) {
      for (String regex : List.of("\\G", "a?\\G", "(a?\\G){1,3}", "(?:a|)\\G", "a*\\G")) {
        Pattern pattern =
            Pattern.compile(regex, 0, EnginePathOptions.builder().bitState(bitState).build());
        for (int start : new int[] {0, 1}) {
          Matcher string = pattern.matcher(input).region(start, end);
          int byteStart = input.substring(0, start).getBytes(UTF_8).length;
          Utf8Matcher utf8 =
              pattern
                  .matcher(Utf8Input.validated(bytes))
                  .region(byteStart, input.substring(0, end).getBytes(UTF_8).length);
          for (int retry = 0; retry < 3; retry++) {
            assertThat(string.find()).as("/%s/ retry %s", regex, retry).isTrue();
            assertThat(string.start()).isEqualTo(start);
            assertThat(string.end()).isEqualTo(start);
            assertThat(utf8.find()).isTrue();
            assertThat(utf8.start()).isEqualTo(byteStart);
            assertThat(utf8.end()).isEqualTo(byteStart);
            assertThat(string.find()).isFalse();
            assertThat(utf8.find()).isFalse();
          }
        }
      }
    }
  }

  @Test
  void nullableAssertionLoopsRetainFinalEmptyCapturesAcrossExactEngines() {
    for (boolean bitState : List.of(false, true)) {
      EnginePathOptions options = EnginePathOptions.builder().bitState(bitState).build();
      for (String regex : List.of("((.?\\G)*)", "((.?\\G)+)", "(((.?\\G)*)*)", "((.?\\G){1,3})")) {
        Pattern pattern = Pattern.compile(regex, 0, options);
        for (String token : List.of("a", "é", "😀")) {
          String input = "z" + token + "!";
          int end = 1 + token.length();
          int byteEnd = input.substring(0, end).getBytes(UTF_8).length;
          Matcher string =
              Pattern.compile(java.util.regex.Pattern.quote(token), 0, options)
                  .matcher(input)
                  .region(1, end)
                  .useTransparentBounds(true);
          Matcher utf8 =
              new Matcher(
                      Pattern.compile(java.util.regex.Pattern.quote(token), 0, options),
                      new Utf8InputScanner(input.getBytes(UTF_8)))
                  .region(1, byteEnd)
                  .useTransparentBounds(true);
          var jdk =
              java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(token))
                  .matcher(input)
                  .region(1, end)
                  .useTransparentBounds(true);
          assertThat(string.find()).isTrue();
          assertThat(utf8.find()).isTrue();
          assertThat(jdk.find()).isTrue();
          string.usePattern(pattern);
          utf8.usePattern(pattern);
          jdk.usePattern(java.util.regex.Pattern.compile(regex));
          assertThat(string.lookingAt()).isTrue();
          assertThat(utf8.lookingAt()).isTrue();
          assertThat(jdk.lookingAt()).isTrue();
          for (int group = 0; group <= jdk.groupCount(); group++) {
            int from = jdk.start(group);
            int to = jdk.end(group);
            assertThat(string.start(group)).isEqualTo(from);
            assertThat(string.end(group)).isEqualTo(to);
            assertThat(utf8.start(group))
                .isEqualTo(from < 0 ? -1 : input.substring(0, from).getBytes(UTF_8).length);
            assertThat(utf8.end(group))
                .isEqualTo(to < 0 ? -1 : input.substring(0, to).getBytes(UTF_8).length);
          }
        }
      }
    }
  }

  @Test
  @Tag("work-counter")
  void previousMatchAssertionsDoNotMultiplySearchWork() {
    for (boolean bitState : List.of(false, true)) {
      for (String regex : List.of("(?:a|aa)*\\G$", "(?:(?:a?\\G)*)*z")) {
        Pattern pattern =
            Pattern.compile(
                regex,
                0,
                EnginePathOptions.builder().bitState(bitState).startAcceleration(false).build());
        long small =
            WorkCounter.countForTesting(
                () -> assertThat(pattern.matcher("a".repeat(512)).find()).isFalse());
        long large =
            WorkCounter.countForTesting(
                () -> assertThat(pattern.matcher("a".repeat(2048)).find()).isFalse());
        assertThat(small).isPositive();
        assertThat(large).isLessThanOrEqualTo(5 * small + 64);
      }
    }
  }

  @Test
  void directUtf8SearchesAndCapabilitiesRespectExactEngineEligibility() {
    for (String regex : List.of("\\Ga", "\\G[^!,]+,?", "(?:\\Ga|b)", "a\\G", "\\G.*z")) {
      Pattern pattern = Pattern.compile(regex);
      assertThat(pattern.analysis().capabilities())
          .doesNotContain(
              PatternCapability.DFA_BOUNDARY_SEARCH,
              PatternCapability.DFA_REJECT_PREFILTER,
              PatternCapability.ONE_PASS_PRIMARY,
              PatternCapability.ONE_PASS_CAPTURE_EXTRACTION);
      for (String input : List.of("", "ab", "ba", "aaz", "z", "é😀,")) {
        boolean expected = pattern.matcher(input).find();
        assertThat(pattern.find(Utf8Input.validated(input.getBytes(UTF_8))))
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
