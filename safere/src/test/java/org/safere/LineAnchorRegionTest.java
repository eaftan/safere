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

/** Line assertions must use the same region context in scalar and full-text execution. */
@DisabledForCrosscheck("checks engine equivalence and the approved opaque CRLF context divergence")
class LineAnchorRegionTest {
  private enum Operation {
    MATCHES,
    LOOKING_AT,
    FIND
  }

  private record InputRegion(String input, int start, int end) {}

  private record Capture(int start, int end, String text) {}

  @Test
  void opaqueEndAnchorsTreatTheVisibleLfAsAStandaloneTerminator() {
    // The hidden CR is intentionally ignored, even though the JDK consults it (issue #1031).
    for (String anchor : List.of("$", "\\Z", "(?m:$)", "(?d:$)", "(?dm:$)", "(?d:\\Z)")) {
      for (String regex : List.of("(.)?" + anchor, "(?:(.)?" + anchor + ")|(?:\\Xz)")) {
        Matcher matcher = Pattern.compile(regex).matcher("\r\na").region(1, 2);
        assertThat(matcher.lookingAt()).as(regex).isTrue();
        assertThat(captures(matcher.toMatchResult()))
            .as(regex)
            .containsExactly(new Capture(1, 1, ""), new Capture(-1, -1, null));
      }
    }
  }

  @ParameterizedTest(name = "{0} {1} {2}")
  @MethodSource("lineAnchorSearchCases")
  void unreachableGraphemeAlternativePreservesLineAssertions(
      InputRegion region, String regex, Operation operation) {
    for (boolean anchoring : List.of(false, true)) {
      for (boolean transparent : List.of(false, true)) {
        Matcher scalar = configure(Pattern.compile(regex), region, anchoring, transparent);
        Pattern mixedPattern = Pattern.compile("(?:" + regex + ")|(?:\\Xz)");
        Matcher mixed = configure(mixedPattern, region, anchoring, transparent);
        List<List<Capture>> expected = trace(scalar, operation);
        assertThat(trace(mixed, operation))
            .as(
                "%s %s %s anchoring=%s transparent=%s",
                region, regex, operation, anchoring, transparent)
            .isEqualTo(expected);
        assertUtf8Trace(mixedPattern, region, anchoring, transparent, operation, expected);
      }
    }
  }

  private static Stream<Arguments> lineAnchorSearchCases() {
    return cases(List.of(Operation.LOOKING_AT, Operation.FIND));
  }

  @ParameterizedTest(name = "{0} {1} {2}")
  @MethodSource("lineAnchorCases")
  void fullTextLineAssertionsMatchJdkOutsideTheApprovedCrLfDivergence(
      InputRegion region, String regex, Operation operation) {
    String mixedRegex = "(?:" + regex + ")|(?:\\Xz)";
    Pattern pattern = Pattern.compile(mixedRegex);
    for (boolean anchoring : List.of(false, true)) {
      for (boolean transparent : List.of(false, true)) {
        // Opaque anchoring contexts starting inside CRLF have an explicit SafeRE oracle above.
        if (anchoring
            && !transparent
            && region.start() > 0
            && region.input().charAt(region.start() - 1) == '\r'
            && region.input().charAt(region.start()) == '\n') {
          continue;
        }
        Matcher actual = configure(pattern, region, anchoring, transparent);
        java.util.regex.Matcher expected =
            java.util.regex.Pattern.compile(mixedRegex)
                .matcher(region.input())
                .region(region.start(), region.end())
                .useAnchoringBounds(anchoring)
                .useTransparentBounds(transparent);
        List<List<Capture>> expectedTrace = trace(expected, operation);
        assertThat(trace(actual, operation))
            .as(
                "%s %s %s anchoring=%s transparent=%s",
                region, regex, operation, anchoring, transparent)
            .isEqualTo(expectedTrace);
        assertUtf8Trace(pattern, region, anchoring, transparent, operation, expectedTrace);
      }
    }
  }

  private static Stream<Arguments> lineAnchorCases() {
    return cases(List.of(Operation.values()));
  }

  private static Stream<Arguments> cases(List<Operation> operations) {
    List<InputRegion> regions = new ArrayList<>();
    regions.add(new InputRegion("\r\na", 1, 2));
    regions.add(new InputRegion("\r\n", 1, 2));
    for (String terminator : List.of("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029")) {
      regions.add(new InputRegion(terminator + "a", 0, terminator.length()));
      regions.add(new InputRegion("é" + terminator + "b", 1, 1 + terminator.length()));
      regions.add(new InputRegion("a" + terminator, 0, 1 + terminator.length()));
    }
    regions.add(new InputRegion("\r\na", 0, 1));
    regions.add(new InputRegion("\r\na", 1, 1));
    regions.add(new InputRegion("abc", 1, 2));
    regions.add(new InputRegion("abc", 1, 1));
    return regions.stream()
        .flatMap(
            region ->
                Stream.of("", "(.)?", "([\\s\\S])?", "([\\s\\S])*?", "([\\s\\S])")
                    .flatMap(
                        prefix ->
                            Stream.of(
                                    "^",
                                    "(?m:^)",
                                    "(?dm:^)",
                                    "$",
                                    "\\Z",
                                    "\\z",
                                    "(?m:$)",
                                    "(?dm:$)",
                                    "(?d:$)",
                                    "(?d:\\Z)",
                                    "(?d:(?-d:$))")
                                .flatMap(
                                    anchor ->
                                        operations.stream()
                                            .map(
                                                op -> Arguments.of(region, prefix + anchor, op)))));
  }

  private static Matcher configure(
      Pattern pattern, InputRegion region, boolean anchoring, boolean transparent) {
    return pattern
        .matcher(region.input())
        .region(region.start(), region.end())
        .useAnchoringBounds(anchoring)
        .useTransparentBounds(transparent);
  }

  private static List<List<Capture>> trace(Matcher matcher, Operation operation) {
    List<List<Capture>> result = new ArrayList<>();
    boolean matched;
    do {
      matched =
          switch (operation) {
            case MATCHES -> matcher.matches();
            case LOOKING_AT -> matcher.lookingAt();
            case FIND -> matcher.find();
          };
      if (matched) {
        result.add(captures(matcher.toMatchResult()));
      }
    } while (operation == Operation.FIND && matched);
    return result;
  }

  private static List<List<Capture>> trace(java.util.regex.Matcher matcher, Operation operation) {
    List<List<Capture>> result = new ArrayList<>();
    boolean matched;
    do {
      matched =
          switch (operation) {
            case MATCHES -> matcher.matches();
            case LOOKING_AT -> matcher.lookingAt();
            case FIND -> matcher.find();
          };
      if (matched) {
        result.add(captures(matcher.toMatchResult()));
      }
    } while (operation == Operation.FIND && matched);
    return result;
  }

  private static List<Capture> captures(MatchResult result) {
    List<Capture> captures = new ArrayList<>();
    for (int group = 0; group <= result.groupCount(); group++) {
      captures.add(new Capture(result.start(group), result.end(group), result.group(group)));
    }
    return captures;
  }

  private static void assertUtf8Trace(
      Pattern pattern,
      InputRegion region,
      boolean anchoring,
      boolean transparent,
      Operation operation,
      List<List<Capture>> expected) {
    byte[] input = region.input().getBytes(UTF_8);
    Matcher matcher =
        new Matcher(pattern, new Utf8InputScanner(input))
            .region(
                byteOffset(region.input(), region.start()),
                byteOffset(region.input(), region.end()))
            .useAnchoringBounds(anchoring)
            .useTransparentBounds(transparent);
    List<List<Capture>> actual = new ArrayList<>();
    boolean matched;
    do {
      matched =
          switch (operation) {
            case MATCHES -> matcher.matches();
            case LOOKING_AT -> matcher.lookingAt();
            case FIND -> matcher.find();
          };
      if (matched) {
        List<Capture> captures = new ArrayList<>();
        for (int group = 0; group <= matcher.groupCount(); group++) {
          int start = matcher.start(group);
          int end = matcher.end(group);
          captures.add(
              new Capture(
                  start, end, start < 0 ? null : new String(input, start, end - start, UTF_8)));
        }
        actual.add(captures);
      }
    } while (operation == Operation.FIND && matched);
    assertThat(actual)
        .as(
            "UTF-8 %s %s %s anchoring=%s transparent=%s",
            region, pattern, operation, anchoring, transparent)
        .isEqualTo(utf8Trace(region.input(), expected));
  }

  private static List<List<Capture>> utf8Trace(String input, List<List<Capture>> trace) {
    return trace.stream()
        .map(
            match ->
                match.stream()
                    .map(
                        capture ->
                            new Capture(
                                byteOffset(input, capture.start()),
                                byteOffset(input, capture.end()),
                                capture.text()))
                    .toList())
        .toList();
  }

  private static int byteOffset(String input, int offset) {
    return offset < 0 ? offset : input.substring(0, offset).getBytes(UTF_8).length;
  }
}
