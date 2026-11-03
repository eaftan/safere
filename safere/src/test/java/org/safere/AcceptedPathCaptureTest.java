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

/** Captures and match priority belong to the accepted path, including every repeat copy. */
@DisabledForCrosscheck("exercises engine controls and intentional accepted-path capture semantics")
class AcceptedPathCaptureTest {
  private static final Bounds UNMATCHED = new Bounds(-1, -1);

  private static Stream<Arguments> acceptedPaths() {
    // Expected results are independent of the JDK's wrapper-dependent nested-repeat captures.
    List<CaptureCase> cases =
        List.of(
            capture("(?:(a){0,2})*", "aaaa", new Bounds(3, 4)),
            capture("(?:(?:(a)){0,2})*", "aaaa", new Bounds(3, 4)),
            capture("(?:(a){1,2}b)*", "aabab", new Bounds(3, 4)),
            capture("(?:(a)+){2}", "aaa", new Bounds(2, 3)),
            capture("(?:(a){1,}){2,3}", "aaaaa", new Bounds(4, 5)),
            fullMatch("(?:(a){0,2})*?", "aaa", new Bounds(2, 3)),
            fullMatch("(?:(?:(a){0,2})*?)", "aaa", new Bounds(2, 3)),
            fullMatch("(?:(a){1,2})*?", "aaa", new Bounds(2, 3)),
            fullMatch("(?:(a){1,2})+?", "aaa", new Bounds(2, 3)),
            capture("(?:(a){1,2}){2,}?", "aaa", new Bounds(2, 3)),
            capture("[x](?:(a){1,2})*", "xaaa", new Bounds(3, 4)),
            capture("(?:(a){1,2}){2}", "aaa", new Bounds(2, 3)),
            capture("((a)+){2}", "aaa", new Bounds(2, 3), new Bounds(2, 3)),
            capture("(?:((a))+){2}", "aaa", new Bounds(2, 3), new Bounds(2, 3)),
            capture("(?:((a)){1,}){2}", "aaa", new Bounds(2, 3), new Bounds(2, 3)),
            capture("(?:((a)){0,2})*", "aaa", new Bounds(2, 3), new Bounds(2, 3)),
            capture("x(?:(a){1,2})*y", "xaaay", new Bounds(3, 4)),
            capture("x(?:(a){1,}){2}y", "xaaay", new Bounds(3, 4)),
            capture("(?:(a){0,2})*?b", "aaaab", new Bounds(3, 4)),
            capture("(?:(a){1,2})+?b", "aaaab", new Bounds(3, 4)),
            capture("(?:(?:a(b)?){1,2})*", "aaab", new Bounds(3, 4)),
            capture("(?:(?:a(b)?){0,2})+", "aaab", new Bounds(3, 4)),
            capture("(?:(?<last>😀){0,2})*", "😀😀😀😀", new Bounds(6, 8)),
            capture("((a){0,2})*", "aaaa", new Bounds(4, 4), new Bounds(3, 4)),
            capture("(?:(a){0,2}(a){0,2})+?", "aa", new Bounds(1, 2), UNMATCHED),
            capture("(?:(ab?)+(a){0,2})+", "aaab", new Bounds(2, 4), UNMATCHED),
            capture("(?:(ab?){0,2}(a){0,2})*", "aab", new Bounds(1, 3), UNMATCHED),
            capture("(a(b)?)+", "aba", new Bounds(2, 3), new Bounds(1, 2)),
            capture("(a)b|ac", "ac", UNMATCHED));
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
  @MethodSource("acceptedPaths")
  void everyRepeatCopyPublishesItsAcceptedCaptures(CaptureCase testCase, EnginePathOptions engine) {
    Pattern pattern = Pattern.compile(testCase.regex(), 0, engine);
    for (boolean region : new boolean[] {false, true}) {
      String prefix = region ? "😀#" : "";
      String input = prefix + testCase.input() + (region ? "!" : "");
      int offset = prefix.length();
      for (int operation = 0; operation < 3; operation++) {
        if (testCase.fullMatchOnly() && operation != 1) {
          continue;
        }
        Matcher matcher = pattern.matcher(input);
        Utf8Matcher utf8 = pattern.matcher(Utf8Input.trusted(input.getBytes(UTF_8)));
        if (region) {
          matcher.region(offset, offset + testCase.input().length());
          utf8.region(
              byteOffset(input, offset), byteOffset(input, offset + testCase.input().length()));
        }
        assertThat(match(matcher, operation))
            .as("operation %d for %s", operation, testCase)
            .isTrue();
        assertThat(match(utf8, operation)).isTrue();
        assertCaptures(matcher, input, offset, testCase.groups());
        assertThat(utf8.groupCount()).isEqualTo(testCase.groups().size() - 1);
        for (int group = 0; group < testCase.groups().size(); group++) {
          Bounds bounds = testCase.groups().get(group);
          int start = bounds.start() < 0 ? -1 : byteOffset(input, offset + bounds.start());
          int end = bounds.end() < 0 ? -1 : byteOffset(input, offset + bounds.end());
          assertThat(utf8.start(group))
              .as("UTF-8 group %d for %s", group, testCase)
              .isEqualTo(start);
          assertThat(utf8.end(group)).isEqualTo(end);
        }
      }
    }
  }

  @Test
  void fullMatchAcceptanceCanSelectADifferentRepeatPartition() {
    Pattern pattern = Pattern.compile("(?:(a){3,4})*");
    Matcher matcher = pattern.matcher("aaaaaa");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.end()).isEqualTo(4);
    assertThat(matcher.start(1)).isEqualTo(3);
    assertThat(matcher.end(1)).isEqualTo(4);
    matcher.reset();
    assertThat(matcher.matches()).isTrue();
    assertThat(matcher.end()).isEqualTo(6);
    assertThat(matcher.start(1)).isEqualTo(5);
    assertThat(matcher.end(1)).isEqualTo(6);
  }

  @Test
  void laterFindsAndSnapshotsKeepTheirOwnLatestCapture() {
    Matcher matcher = Pattern.compile("(?:(a){1,2})+b").matcher("zzaaaabxxaaaab");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(2);
    assertThat(matcher.start(1)).isEqualTo(5);
    MatchResult first = matcher.toMatchResult();
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(9);
    assertThat(matcher.start(1)).isEqualTo(12);
    MatchResult second = matcher.toMatchResult();
    assertThat(matcher.find()).isFalse();
    matcher.reset();
    assertThat(first.start(1)).isEqualTo(5);
    assertThat(first.end(1)).isEqualTo(6);
    assertThat(second.start(1)).isEqualTo(12);
    assertThat(second.end(1)).isEqualTo(13);
  }

  @Test
  void replacementObservesCapturesFirstWrittenInLaterIterations() {
    Pattern pattern = Pattern.compile("(?:(?:a(?<last>b)?){1,2})+");
    String input = "aaab";
    assertThat(pattern.matcher(input).replaceAll("[$1]")).isEqualTo("[b]");
    assertThat(pattern.matcher(input).replaceFirst("[${last}]")).isEqualTo("[b]");
    assertThat(pattern.matcher(input).replaceAll(result -> "[" + result.start(1) + "]"))
        .isEqualTo("[3]");
    Matcher matcher = pattern.matcher(input);
    StringBuilder output = new StringBuilder();
    while (matcher.find()) {
      matcher.appendReplacement(output, "[${last}]");
    }
    matcher.appendTail(output);
    assertThat(output.toString()).isEqualTo("[b]");
  }

  private static void assertCaptures(
      Matcher matcher, String input, int offset, List<Bounds> groups) {
    int matchStart = matcher.start();
    int matchEnd = matcher.end();
    assertThat(matcher.groupCount()).isEqualTo(groups.size() - 1);
    MatchResult snapshot = matcher.toMatchResult();
    for (int group = 0; group < groups.size(); group++) {
      Bounds bounds = groups.get(group);
      int start = bounds.start() < 0 ? -1 : offset + bounds.start();
      int end = bounds.end() < 0 ? -1 : offset + bounds.end();
      String value = start < 0 ? null : input.substring(start, end);
      assertThat(matcher.start(group)).as("group %d", group).isEqualTo(start);
      assertThat(matcher.end(group)).isEqualTo(end);
      assertThat(matcher.group(group)).isEqualTo(value);
      assertThat(snapshot.start(group)).isEqualTo(start);
      assertThat(snapshot.end(group)).isEqualTo(end);
      assertThat(snapshot.group(group)).isEqualTo(value);
    }
    assertThat(matcher.start()).isEqualTo(matchStart);
    assertThat(matcher.end()).isEqualTo(matchEnd);
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

  private static int byteOffset(String input, int offset) {
    return input.substring(0, offset).getBytes(UTF_8).length;
  }

  private static CaptureCase capture(String regex, String input, Bounds... captures) {
    var groups = new ArrayList<Bounds>();
    groups.add(new Bounds(0, input.length()));
    groups.addAll(List.of(captures));
    return new CaptureCase(regex, input, List.copyOf(groups), false);
  }

  private static CaptureCase fullMatch(String regex, String input, Bounds... captures) {
    CaptureCase testCase = capture(regex, input, captures);
    return new CaptureCase(regex, input, testCase.groups(), true);
  }

  private record Bounds(int start, int end) {}

  private record CaptureCase(
      String regex, String input, List<Bounds> groups, boolean fullMatchOnly) {}
}
