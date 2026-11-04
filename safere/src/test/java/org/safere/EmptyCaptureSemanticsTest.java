// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Empty iterations publish their completed captures independently of redundant wrappers. */
@DisabledForCrosscheck("explicit empty-iteration policy and package-private engine controls")
class EmptyCaptureSemanticsTest {
  private static Stream<Arguments> repetitions() {
    List<Operand> operands =
        List.of(
            new Operand("()", 1, true),
            new Operand("((?:))", 1, true),
            new Operand("(())", 2, true),
            new Operand("(?<empty>)", 1, true),
            new Operand("(^)", 1, true),
            new Operand("(\\A)", 1, true),
            new Operand("(\\B)", 1, true),
            new Operand("(\\b{g})", 1, true),
            new Operand("(\\b)", 1, false));
    List<Repeat> repeats =
        List.of(
            new Repeat("?", 0, 1),
            new Repeat("*", 0, -1),
            new Repeat("+", 1, -1),
            new Repeat("{0}", 0, 0),
            new Repeat("{1}", 1, 1),
            new Repeat("{2}", 2, 2),
            new Repeat("{0,1}", 0, 1),
            new Repeat("{0,2}", 0, 2),
            new Repeat("{1,2}", 1, 2),
            new Repeat("{2,3}", 2, 3),
            new Repeat("{0,}", 0, -1),
            new Repeat("{1,}", 1, -1),
            new Repeat("{2,}", 2, -1));
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
    return operands.stream()
        .flatMap(
            operand ->
                repeats.stream()
                    .flatMap(
                        repeat ->
                            Stream.of("", "?", "+")
                                .flatMap(
                                    mode ->
                                        engines.stream()
                                            .map(
                                                engine ->
                                                    Arguments.of(operand, repeat, mode, engine)))));
  }

  @ParameterizedTest(name = "[{index}] {0}{1}{2}")
  @MethodSource("repetitions")
  void completedEmptyIterationsPublishCaptures(
      Operand operand, Repeat repeat, String mode, EnginePathOptions engine) {
    boolean matched = repeat.minimum() == 0 || operand.succeeds();
    boolean participates =
        operand.succeeds() && repeat.maximum() != 0 && (repeat.minimum() > 0 || !mode.equals("?"));
    for (int wrappers = 0; wrappers < 3; wrappers++) {
      String wrapped = "(?:".repeat(wrappers) + operand.regex() + ")".repeat(wrappers);
      Pattern pattern = Pattern.compile(wrapped + repeat.regex() + mode, 0, engine);
      for (boolean region : new boolean[] {false, true}) {
        String input = region ? "😀#" : "";
        int position = input.length();
        for (int operation = 0; operation < 3; operation++) {
          Matcher matcher = pattern.matcher(input);
          Utf8Matcher utf8 = pattern.matcher(Utf8Input.trusted(input.getBytes(UTF_8)));
          if (region) {
            matcher.region(position, position);
            utf8.region(input.getBytes(UTF_8).length, input.getBytes(UTF_8).length);
          }
          assertThat(matcher.groupCount()).isEqualTo(operand.groups());
          assertThat(match(matcher, operation))
              .as("/%s/ operation %d", pattern, operation)
              .isEqualTo(matched);
          assertThat(match(utf8, operation)).isEqualTo(matched);
          if (!matched) {
            continue;
          }
          assertEmptyGroups(matcher, position, operand.groups(), participates);
          assertThat(utf8.start()).isEqualTo(input.getBytes(UTF_8).length);
          assertThat(utf8.end()).isEqualTo(utf8.start());
          for (int group = 1; group <= operand.groups(); group++) {
            int expected = participates ? input.getBytes(UTF_8).length : -1;
            assertThat(utf8.start(group)).isEqualTo(expected);
            assertThat(utf8.end(group)).isEqualTo(expected);
          }
        }
      }
    }
  }

  @Test
  void countedZeroWidthProgramsStayBoundedWithoutLosingParticipation() {
    for (String operand :
        List.of("()", "(())", "(()??)", "(?:()())", "(^)", "(\\b)", "(?:()|())")) {
      for (String mode : List.of("", "?", "+")) {
        for (String range : List.of("{0,%d}", "{%d}", "{1,%d}", "{%d,}")) {
          Pattern small = Pattern.compile(operand + range.formatted(2) + mode);
          Pattern large = Pattern.compile(operand + range.formatted(1000) + mode);
          assertThat(large.analysis().programSize())
              .as("/%s/ should not expand identical zero-width iterations", large)
              .isEqualTo(small.analysis().programSize());
          for (String input : List.of("", "a", "😀a")) {
            Matcher expected = small.matcher(input);
            Matcher actual = large.matcher(input);
            Utf8Matcher bytes = large.matcher(Utf8Input.trusted(input.getBytes(UTF_8)));
            while (expected.find()) {
              assertThat(actual.find()).isTrue();
              for (int group = 0; group <= expected.groupCount(); group++) {
                assertThat(actual.start(group)).isEqualTo(expected.start(group));
                assertThat(actual.end(group)).isEqualTo(expected.end(group));
              }
            }
            assertThat(actual.find()).isFalse();
            // UTF-8 cursor advancement differs on supplementary characters; compare directly on
            // the empty and ASCII subjects, and leave code-point coordinates to the matrix above.
            if (!input.contains("😀")) {
              Matcher chars = large.matcher(input);
              while (chars.find()) {
                assertThat(bytes.find()).isTrue();
                for (int group = 0; group <= chars.groupCount(); group++) {
                  assertThat(bytes.start(group)).isEqualTo(chars.start(group));
                  assertThat(bytes.end(group)).isEqualTo(chars.end(group));
                }
              }
              assertThat(bytes.find()).isFalse();
            }
          }
        }
      }
    }
  }

  @Test
  void followingOperandlessCountsDoNotEraseEarlierCaptures() {
    // Adjacent counted tokens after a quantifier have their own implicit empty operand in the
    // supported syntax. Only an actual enclosing zero-count repeat omits the earlier group.
    for (String regex : List.of("(){0,2}", "(){0,2}{0}", "(){0,2}{0}{1}", "(){0,2}{1}?{0}?")) {
      for (int operation = 0; operation < 3; operation++) {
        Matcher matcher = Pattern.compile(regex).matcher("");
        assertThat(match(matcher, operation)).as("/%s/", regex).isTrue();
        assertEmptyGroups(matcher, 0, 1, true);
      }
    }
    for (String regex : List.of("(){0}", "(?:(){0,2}){0}")) {
      Matcher matcher = Pattern.compile(regex).matcher("");
      assertThat(matcher.matches()).isTrue();
      assertEmptyGroups(matcher, 0, 1, false);
    }
  }

  @Test
  void emptyCaptureParticipationIsVisibleToReplacementCallbacks() {
    for (String quantifier : List.of("*", "+", "{0,2}", "{1,2}", "{2,}")) {
      for (String operand : List.of("(?<empty>)", "(?:(?<empty>))")) {
        Pattern pattern = Pattern.compile(operand + quantifier);
        assertThat(
                pattern
                    .matcher("")
                    .replaceAll(result -> result.group(1) == null ? "omitted" : "empty"))
            .isEqualTo("empty");
        assertThat(pattern.matcher("").replaceFirst("[${empty}]")).isEqualTo("[]");
        Matcher matcher = pattern.matcher("");
        assertThat(matcher.find()).isTrue();
        StringBuilder output = new StringBuilder();
        matcher.appendReplacement(output, "[$1]").appendTail(output);
        assertThat(output.toString()).isEqualTo("[]");
      }
    }
    assertThat(
            Pattern.compile("()*?")
                .matcher("")
                .replaceAll(result -> result.group(1) == null ? "omitted" : "empty"))
        .isEqualTo("omitted");
  }

  @Test
  void successiveFindsPublishFreshCapturesAndRepresentationSpecificPositions() {
    for (String regex : List.of("()*", "(?:())*", "(){0,2}", "(?:()){0,2}")) {
      Matcher matcher = Pattern.compile(regex).matcher("😀a");
      List<MatchResult> snapshots = new ArrayList<>();
      for (int position = 0; position <= 3; position++) {
        assertThat(matcher.find()).isTrue();
        assertEmptyGroups(matcher, position, 1, true);
        snapshots.add(matcher.toMatchResult());
      }
      assertThat(matcher.find()).isFalse();
      matcher.reset();
      for (int position = 0; position < snapshots.size(); position++) {
        assertThat(snapshots.get(position).start(1)).isEqualTo(position);
        assertThat(snapshots.get(position).end(1)).isEqualTo(position);
      }
      Utf8Matcher utf8 = Pattern.compile(regex).matcher(Utf8Input.trusted("😀a".getBytes(UTF_8)));
      for (int position : new int[] {0, 4, 5}) {
        assertThat(utf8.find()).isTrue();
        assertThat(utf8.start()).isEqualTo(position);
        assertThat(utf8.start(1)).isEqualTo(position);
        assertThat(utf8.end(1)).isEqualTo(position);
      }
      assertThat(utf8.find()).isFalse();
    }
  }

  @Test
  void failedBranchesAndOmittedDescendantsKeepTheirOwnParticipation() {
    Matcher failed = Pattern.compile("()*a|b").matcher("b");
    assertThat(failed.matches()).isTrue();
    assertThat(failed.group(1)).isNull();
    assertThat(failed.start(1)).isEqualTo(-1);
    Matcher omitted = Pattern.compile("(()??)*").matcher("");
    assertThat(omitted.matches()).isTrue();
    assertThat(omitted.group(1)).isEmpty();
    assertThat(omitted.group(2)).isNull();
    Matcher retained = Pattern.compile("(?:(a)|())*b").matcher("ab");
    assertThat(retained.matches()).isTrue();
    assertThat(retained.group(1)).isEqualTo("a");
    assertThat(retained.start(2)).isEqualTo(1);
    assertThat(retained.end(2)).isEqualTo(1);
  }

  private static void assertEmptyGroups(
      Matcher matcher, int position, int groups, boolean participates) {
    assertThat(matcher.start()).isEqualTo(position);
    assertThat(matcher.end()).isEqualTo(position);
    MatchResult snapshot = matcher.toMatchResult();
    for (int group = 1; group <= groups; group++) {
      int expected = participates ? position : -1;
      assertThat(matcher.start(group)).as("group %d", group).isEqualTo(expected);
      assertThat(matcher.end(group)).isEqualTo(expected);
      assertThat(matcher.group(group)).isEqualTo(participates ? "" : null);
      assertThat(snapshot.start(group)).isEqualTo(expected);
      assertThat(snapshot.end(group)).isEqualTo(expected);
      assertThat(snapshot.group(group)).isEqualTo(participates ? "" : null);
    }
    assertThat(matcher.start()).isEqualTo(position);
    assertThat(matcher.end()).isEqualTo(position);
  }

  private static boolean match(Matcher matcher, int operation) {
    return switch (operation) {
      case 0 -> matcher.find();
      case 1 -> matcher.matches();
      default -> matcher.lookingAt();
    };
  }

  private static boolean match(Utf8Matcher matcher, int operation) {
    return switch (operation) {
      case 0 -> matcher.find();
      case 1 -> matcher.matches();
      default -> matcher.lookingAt();
    };
  }

  private record Operand(String regex, int groups, boolean succeeds) {}

  private record Repeat(String regex, int minimum, int maximum) {}
}
