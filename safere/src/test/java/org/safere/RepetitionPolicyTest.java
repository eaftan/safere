// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Conformance to the minimum-respecting empty-stop rule in the repetition specification. */
@DisabledForCrosscheck("SafeRE's explicit repetition policy and package-private engine controls")
class RepetitionPolicyTest {
  private static Stream<Arguments> policyCases() {
    List<PolicyCase> cases =
        List.of(
            policy("(?:b?|a){0,2}b", "abb", 3),
            policy("(?:b?|a){1,2}b", "abb", 3),
            policy("(?:b?|a){2}b", "abb", 2),
            policy("(?:()|\\W+){0,2}y", " y", 2, 1, 1),
            policy("(?:()|\\W+){1,2}y", " y", 2, 1, 1),
            policy("(?:()|\\W+){2}y", " y", 2, 0, 0),
            policy("(?:()|a){0,2}\\z", "a", 1, 1, 1),
            policy("(?:()|a){1,2}b", "ab", 2, 1, 1),
            policy("(?:()|a){2,3}b", "aab", 3, 0, 0),
            policy("(?:b?|(a)){2,3}b", "abbb", 3, 0, 1),
            policy("^(?:(\\b|a){2,3})*?$", "aa", 2, 1, 2),
            policy("^(?:(\\b|a){2,3})+?$", "aa", 2, 1, 2),
            policy("^(?:(\\b|a){2,4})*?$", "aa", 2, 2, 2),
            policy("^(?:(\\b|a){2,3})*$", "aa", 2, 2, 2),
            policy("(?:\\A|a){2}\\z", "a", 1),
            policy("(?:^()|a)+b", "ab", 2, -1, -1),
            policy("(?:^()|a){1,}b", "ab", 2, -1, -1),
            policy("(?:^()|a)+?b", "ab", 2, -1, -1),
            policy("(?:^()|a){1,}?b", "ab", 2, -1, -1),
            policy("(?:^()|a){2,}b", "ab", 2, 0, 0),
            policy("(?:()|a)+?b", "ab", 2, -1, -1),
            policy("(?:(?:ab)?|a?b){0,2}a", "baba", 4),
            policy("(?:a??b?){0,2}b", "abab", 4),
            policy("(?:\\b|[ab]){0,2}a", "baa", 3),
            policy("(?:a{0,2}|b?){0,2}a", "baa", 3),
            policy("(a|aa){0,2}?b", "aab", 3, 1, 2),
            policy("(a(b)?){0,2}", "aba", 3, 2, 3, 1, 2));
    List<EnginePathOptions> engines =
        List.of(
            EnginePathOptions.allEnabled(),
            EnginePathOptions.builder().lazyCaptureExtraction(false).build(),
            EnginePathOptions.builder()
                .literalFastPaths(false)
                .charClassMatchFastPaths(false)
                .keywordAlternationFastPath(false)
                .startAcceleration(false)
                .shiftDfa(false)
                .onePass(false)
                .dfa(false)
                .reverseDfa(false)
                .bitState(false)
                .lazyCaptureExtraction(false)
                .build());
    return cases.stream()
        .flatMap(testCase -> engines.stream().map(engine -> Arguments.of(testCase, engine)));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("policyCases")
  void emptyIterationStopsOnlyAfterSatisfyingItsOwnMinimum(
      PolicyCase testCase, EnginePathOptions engine) {
    for (int wrappers = 0; wrappers < 2; wrappers++) {
      Pattern pattern =
          Pattern.compile(
              "(?:".repeat(wrappers) + testCase.regex() + ")".repeat(wrappers), 0, engine);
      for (boolean region : new boolean[] {false, true}) {
        String prefix = region ? "😀#" : "";
        String input = prefix + testCase.input() + (region ? "!" : "");
        int offset = prefix.length();
        for (int operation = 0; operation < 3; operation++) {
          Matcher matcher = pattern.matcher(input);
          Utf8Matcher bytes = pattern.matcher(Utf8Input.trusted(input.getBytes(UTF_8)));
          if (region) {
            matcher.region(offset, offset + testCase.input().length());
            bytes.region(
                byteOffset(input, offset), byteOffset(input, offset + testCase.input().length()));
          }
          assertThat(match(matcher, operation))
              .as("/%s/ operation %d", pattern, operation)
              .isTrue();
          assertThat(match(bytes, operation)).isTrue();
          int end = operation == 2 ? testCase.input().length() : testCase.end();
          assertThat(matcher.start()).isEqualTo(offset);
          assertThat(matcher.end()).isEqualTo(offset + end);
          assertThat(bytes.start()).isEqualTo(byteOffset(input, offset));
          assertThat(bytes.end()).isEqualTo(byteOffset(input, offset + end));
          for (int group = 1; group <= matcher.groupCount(); group++) {
            int start = testCase.groups().get(2 * (group - 1));
            int finish = testCase.groups().get(2 * (group - 1) + 1);
            assertThat(matcher.start(group))
                .as("/%s/ group %d", pattern, group)
                .isEqualTo(start < 0 ? -1 : offset + start);
            assertThat(matcher.end(group)).isEqualTo(finish < 0 ? -1 : offset + finish);
            assertThat(bytes.start(group))
                .isEqualTo(start < 0 ? -1 : byteOffset(input, offset + start));
            assertThat(bytes.end(group))
                .isEqualTo(finish < 0 ? -1 : byteOffset(input, offset + finish));
          }
        }
      }
    }
  }

  @Test
  void nestedNullableCountedSearchPreservesTheUnicodeFindHistory() {
    String regex = "(?:(?:(?:(?:(?:[^a])?)(?:(?:|[ab])))+?){0,2})(?:[^a])";
    String input = "béa\na\n.cabaaa";
    for (EnginePathOptions engine :
        List.of(
            EnginePathOptions.allEnabled(),
            EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build())) {
      Pattern pattern = Pattern.compile(regex, 0, engine);
      Matcher matcher = pattern.matcher(input);
      assertThat(matcher.matches()).isFalse();
      matcher.reset();
      assertThat(matcher.lookingAt()).isTrue();
      assertThat(matcher.end()).isEqualTo(4);
      matcher.reset();
      Utf8Matcher bytes = pattern.matcher(Utf8Input.trusted(input.getBytes(UTF_8)));
      int[][] expected = {{0, 4}, {4, 7}, {7, 10}};
      for (int[] bounds : expected) {
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(bounds[0]);
        assertThat(matcher.end()).isEqualTo(bounds[1]);
        assertThat(bytes.find()).isTrue();
        assertThat(bytes.start()).isEqualTo(byteOffset(input, bounds[0]));
        assertThat(bytes.end()).isEqualTo(byteOffset(input, bounds[1]));
      }
      assertThat(matcher.find()).isFalse();
      assertThat(bytes.find()).isFalse();
      assertThat(pattern.matcher(input).replaceAll("X")).isEqualTo("XXXaaa");
      assertThat(pattern.split(input, -1)).containsExactly("", "", "", "aaa");
    }
  }

  @Test
  void operandlessZeroCountsDoNotEraseAnEarlierFiniteEmptyCapture() {
    for (String regex : List.of("(){0,2}", "(){0,2}{0}", "(){0,2}{0}{1}", "(){0,2}{1}?{0}?")) {
      Matcher matcher = Pattern.compile(regex).matcher("");
      assertThat(matcher.matches()).isTrue();
      assertThat(matcher.group(1)).isEmpty();
      assertThat(matcher.start(1)).isZero();
      assertThat(matcher.end(1)).isZero();
    }
  }

  @Test
  void finiteEmptyStopControlsFindReplacementAndSplitSequences() {
    for (String quantifier : List.of("{0,2}", "{1,2}", "{2}")) {
      Pattern pattern = Pattern.compile("(?:b?|a)" + quantifier + "b");
      Matcher matcher = pattern.matcher("abb");
      List<String> history = new ArrayList<>();
      while (matcher.find()) {
        history.add(matcher.group());
        assertThat(matcher.toMatchResult().group()).isEqualTo(matcher.group());
      }
      boolean exact = quantifier.equals("{2}");
      assertThat(history).containsExactlyElementsOf(exact ? List.of("ab", "b") : List.of("abb"));
      assertThat(pattern.matcher("abb").replaceAll("X")).isEqualTo(exact ? "XX" : "X");
      assertThat(pattern.split("abb", -1))
          .containsExactlyElementsOf(exact ? List.of("", "", "") : List.of("", ""));
    }
  }

  private static PolicyCase policy(String regex, String input, int end, Integer... groups) {
    return new PolicyCase(regex, input, end, List.of(groups));
  }

  private record PolicyCase(String regex, String input, int end, List<Integer> groups) {}

  private static int byteOffset(String input, int position) {
    return input.substring(0, position).getBytes(UTF_8).length;
  }

  private static boolean match(Matcher matcher, int operation) {
    return switch (operation) {
      case 0 -> matcher.find();
      case 1 -> matcher.lookingAt();
      default -> matcher.matches();
    };
  }

  private static boolean match(Utf8Matcher matcher, int operation) {
    return switch (operation) {
      case 0 -> matcher.find();
      case 1 -> matcher.lookingAt();
      default -> matcher.matches();
    };
  }
}
