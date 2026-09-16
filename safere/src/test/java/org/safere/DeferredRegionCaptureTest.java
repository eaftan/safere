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
import org.junit.jupiter.params.provider.ValueSource;

/** Differential coverage for captures resolved against temporary region views (see issue #880). */
@DisabledForCrosscheck("uses engine controls and already compares against java.util.regex")
class DeferredRegionCaptureTest {
  private enum Engine {
    DEFAULT,
    DEFERRED_NFA,
    EAGER_NFA
  }

  private enum Operation {
    MATCHES,
    LOOKING_AT,
    FIND
  }

  private record Capture(int start, int end, String text) {}

  @Test
  void defaultEnginePreservesCapturesBeyondTheBitStateLimit() {
    Pattern pattern = Pattern.compile("(a+?)?");
    String body = "a".repeat(BitState.maxTextSize(pattern.prog()) + 1);
    Matcher matcher = pattern.matcher("x" + body + "!").region(1, 1 + body.length());

    assertThat(matcher.matches()).isTrue();
    assertThat(matcher.group(1)).isEqualTo(body);
    assertThat(matcher.start(1)).isEqualTo(1);
    assertThat(matcher.end(1)).isEqualTo(1 + body.length());
  }

  @ParameterizedTest
  @ValueSource(strings = {"a", "é", "\uD801\uDC00"})
  void deferredUtf8RegionCapturesUseByteCoordinates(String atom) {
    Pattern pattern =
        Pattern.compile(
            "(" + atom + "+?)?",
            0,
            EnginePathOptions.builder().onePass(false).bitState(false).build());
    for (String prefix : List.of("x", "é", "\uD801\uDC00")) {
      String body = atom.repeat(2);
      byte[] input = (prefix + body + "!").getBytes(UTF_8);
      int start = prefix.getBytes(UTF_8).length;
      int end = start + body.getBytes(UTF_8).length;
      Utf8Matcher matcher = pattern.matcher(Utf8Input.validated(input));
      for (int reuse = 0; reuse < 2; reuse++) {
        matcher.reset().region(start, end);
        assertThat(matcher.matches()).isTrue();
        assertThat(matcher.start(1)).isEqualTo(start);
        assertThat(matcher.end(1)).isEqualTo(end);
      }
    }
  }

  private static Stream<Arguments> regionCaptureCases() {
    return Stream.of(
            "(a+?)?",
            "(a+)?",
            "(a*?)?",
            "(a+)??",
            "(a+?)??",
            "(a{1,3}?)?",
            "(a+?)",
            "(a+?)|",
            "((a)+?)?",
            "(?<word>a+?)?",
            "(a+?)\\b",
            "\\b(a+?)",
            "(a+?)$",
            "^(a+?)$",
            "(a+?)\\z")
        .flatMap(
            regex ->
                Stream.of(Engine.values())
                    .flatMap(
                        engine ->
                            Stream.of(Operation.values())
                                .map(operation -> Arguments.of(regex, engine, operation))));
  }

  @ParameterizedTest(name = "{0} {1} {2}")
  @MethodSource("regionCaptureCases")
  void capturesUseTheActiveRegionCoordinates(String regex, Engine engine, Operation operation) {
    EnginePathOptions options =
        switch (engine) {
          case DEFAULT -> EnginePathOptions.allEnabled();
          case DEFERRED_NFA -> EnginePathOptions.builder().onePass(false).bitState(false).build();
          case EAGER_NFA ->
              EnginePathOptions.builder().onePass(false).bitState(false).dfa(false).build();
        };
    Pattern pattern = Pattern.compile(regex, 0, options);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);
    for (String prefix : List.of("", "x", "\uD801\uDC00")) {
      for (String suffix : List.of("", "x", "\n")) {
        for (boolean anchoring : List.of(false, true)) {
          for (boolean transparent : List.of(false, true)) {
            // New matchers borrow pooled engines; reset also exercises reuse after successes and
            // failures with different region lengths and capture participation.
            Matcher actual = pattern.matcher("");
            java.util.regex.Matcher expected = jdkPattern.matcher("");
            for (String body : List.of("a", "", "aa", "b", "aaa", "aa!", "a")) {
              String input = prefix + body + suffix;
              int start = prefix.length();
              int end = start + body.length();
              actual
                  .reset(input)
                  .region(start, end)
                  .useAnchoringBounds(anchoring)
                  .useTransparentBounds(transparent);
              expected
                  .reset(input)
                  .region(start, end)
                  .useAnchoringBounds(anchoring)
                  .useTransparentBounds(transparent);
              String context =
                  "input="
                      + input
                      + " region="
                      + start
                      + ":"
                      + end
                      + " anchoring="
                      + anchoring
                      + " transparent="
                      + transparent;
              boolean matched;
              do {
                matched =
                    switch (operation) {
                      case MATCHES -> actual.matches();
                      case LOOKING_AT -> actual.lookingAt();
                      case FIND -> actual.find();
                    };
                boolean jdkMatched =
                    switch (operation) {
                      case MATCHES -> expected.matches();
                      case LOOKING_AT -> expected.lookingAt();
                      case FIND -> expected.find();
                    };
                assertThat(matched).as(context).isEqualTo(jdkMatched);
                if (matched) {
                  assertThat(captures(actual)).as(context).isEqualTo(captures(expected));
                  assertThat(captures(actual.toMatchResult()))
                      .as(context)
                      .isEqualTo(captures(expected.toMatchResult()));
                  if (regex.contains("?<word>")) {
                    assertThat(actual.group("word")).as(context).isEqualTo(expected.group("word"));
                  }
                }
              } while (operation == Operation.FIND && matched);
              assertThat(actual.regionStart()).as(context).isEqualTo(start);
              assertThat(actual.regionEnd()).as(context).isEqualTo(end);
            }
          }
        }
      }
    }
  }

  private static List<Capture> captures(MatchResult result) {
    List<Capture> captures = new ArrayList<>();
    for (int group = 0; group <= result.groupCount(); group++) {
      captures.add(new Capture(result.start(group), result.end(group), result.group(group)));
    }
    return captures;
  }
}
