// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Focused differential matrix for end anchors under matcher regions and bounds. */
@DisabledForCrosscheck("differential matrix already compares SafeRE with java.util.regex")
class EndAnchorRegionCompatibilityMatrixTest {
  private static final int FIND_LIMIT = 16;

  @ParameterizedTest(name = "[{index}] {0} /{1}/")
  @MethodSource("endAnchorRegionCases")
  @DisplayName("end-anchor region behavior matches java.util.regex")
  void endAnchorRegionBehaviorMatchesJdk(Scenario scenario, RegexCase regexCase) {
    Trace safeTrace = safeTrace(regexCase, scenario);
    Trace jdkTrace = jdkTrace(regexCase, scenario);

    assertThat(safeTrace)
        .as(
            "%s%nInput length=%s region=[%s,%s] transparent=%s anchoring=%s flags=%s",
            scenario.name(),
            scenario.input().length(),
            scenario.start(),
            scenario.end(),
            scenario.transparentBounds(),
            scenario.anchoringBounds(),
            regexCase.flags())
        .isEqualTo(jdkTrace);
  }

  private static Stream<Arguments> endAnchorRegionCases() {
    return scenarios().stream()
        .flatMap(
            scenario -> regexCases().stream().map(regexCase -> Arguments.of(scenario, regexCase)));
  }

  private static List<Scenario> scenarios() {
    List<Scenario> scenarios = new ArrayList<>();
    for (InputRegion inputRegion : inputRegions()) {
      for (BoundsCase boundsCase : boundsCases()) {
        scenarios.add(
            new Scenario(
                inputRegion.name() + " " + boundsCase.name(),
                inputRegion.input(),
                inputRegion.start(),
                inputRegion.end(),
                boundsCase.transparentBounds(),
                boundsCase.anchoringBounds()));
      }
    }
    return scenarios;
  }

  private static List<InputRegion> inputRegions() {
    return List.of(
        new InputRegion("before final lf", "ab\n", 0, 2),
        new InputRegion("including final lf", "ab\n", 0, 3),
        new InputRegion("before final cr", "ab\r", 0, 2),
        new InputRegion("including final cr", "ab\r", 0, 3),
        new InputRegion("before final nel", "ab\u0085", 0, 2),
        new InputRegion("before final ls", "ab\u2028", 0, 2),
        new InputRegion("before final ps", "ab\u2029", 0, 2),
        new InputRegion("before final crlf", "ab\r\n", 0, 2),
        new InputRegion("split final crlf", "ab\r\n", 0, 3),
        new InputRegion("including final crlf", "ab\r\n", 0, 4),
        new InputRegion("interior lf before suffix", "ab\nx", 0, 3),
        new InputRegion("region end before full-text final lf", "a\u0000\n\n", 0, 3));
  }

  private static List<BoundsCase> boundsCases() {
    return List.of(
        new BoundsCase("opaque anchoring", false, true),
        new BoundsCase("opaque non-anchoring", false, false),
        new BoundsCase("transparent anchoring", true, true),
        new BoundsCase("transparent non-anchoring", true, false));
  }

  private static List<RegexCase> regexCases() {
    return List.of(
        regex("dollar only", "$", 0),
        regex("Z only", "\\Z", 0),
        regex("z only", "\\z", 0),
        regex("two chars dollar", "[\\s\\S][\\s\\S]$", 0),
        regex("anchored two chars dollar", "^[\\s\\S][\\s\\S]$", 0),
        regex("captured two chars dollar", "([\\s\\S])([\\s\\S])$", 0),
        regex("two chars Z", "[\\s\\S][\\s\\S]\\Z", 0),
        regex("anchored two chars Z", "^[\\s\\S][\\s\\S]\\Z", 0),
        regex("two chars z", "[\\s\\S][\\s\\S]\\z", 0),
        regex("anchored two chars z", "^[\\s\\S][\\s\\S]\\z", 0),
        regex("greedy chars dollar", "[\\s\\S]+$", 0),
        regex("dotall two chars dollar", "..$", Pattern.DOTALL),
        regex("unix lines dollar", "[\\s\\S][\\s\\S]$", Pattern.UNIX_LINES),
        regex("unix lines Z", "[\\s\\S][\\s\\S]\\Z", Pattern.UNIX_LINES),
        regex("multiline dollar", "[\\s\\S][\\s\\S]$", Pattern.MULTILINE),
        regex("multiline anchored dollar", "^[\\s\\S][\\s\\S]$", Pattern.MULTILINE));
  }

  private static RegexCase regex(String name, String regex, int flags) {
    return new RegexCase(name, regex, flags);
  }

  private static Trace safeTrace(RegexCase regexCase, Scenario scenario) {
    Pattern pattern = Pattern.compile(regexCase.regex(), regexCase.flags());
    Matcher matcher = configure(pattern.matcher(scenario.input()), scenario);
    int groupCount = matcher.groupCount();

    String matches =
        matchTrace(configure(pattern.matcher(scenario.input()), scenario), Operation.MATCHES);
    String lookingAt =
        matchTrace(configure(pattern.matcher(scenario.input()), scenario), Operation.LOOKING_AT);
    List<String> finds = findTrace(configure(pattern.matcher(scenario.input()), scenario));
    List<String> results = resultsTrace(configure(pattern.matcher(scenario.input()), scenario));
    List<String> resultsAfterFind =
        resultsAfterFindTrace(configure(pattern.matcher(scenario.input()), scenario));

    return new Trace(groupCount, matches, lookingAt, finds, results, resultsAfterFind);
  }

  private static Trace jdkTrace(RegexCase regexCase, Scenario scenario) {
    java.util.regex.Pattern pattern =
        java.util.regex.Pattern.compile(regexCase.regex(), regexCase.flags());
    java.util.regex.Matcher matcher = configure(pattern.matcher(scenario.input()), scenario);
    int groupCount = matcher.groupCount();

    String matches =
        matchTrace(configure(pattern.matcher(scenario.input()), scenario), Operation.MATCHES);
    String lookingAt =
        matchTrace(configure(pattern.matcher(scenario.input()), scenario), Operation.LOOKING_AT);
    List<String> finds = findTrace(configure(pattern.matcher(scenario.input()), scenario));
    List<String> results = resultsTrace(configure(pattern.matcher(scenario.input()), scenario));
    List<String> resultsAfterFind =
        resultsAfterFindTrace(configure(pattern.matcher(scenario.input()), scenario));

    return new Trace(groupCount, matches, lookingAt, finds, results, resultsAfterFind);
  }

  private static Matcher configure(Matcher matcher, Scenario scenario) {
    return matcher
        .region(scenario.start(), scenario.end())
        .useTransparentBounds(scenario.transparentBounds())
        .useAnchoringBounds(scenario.anchoringBounds());
  }

  private static java.util.regex.Matcher configure(
      java.util.regex.Matcher matcher, Scenario scenario) {
    return matcher
        .region(scenario.start(), scenario.end())
        .useTransparentBounds(scenario.transparentBounds())
        .useAnchoringBounds(scenario.anchoringBounds());
  }

  private static String matchTrace(Matcher matcher, Operation operation) {
    boolean matched =
        switch (operation) {
          case MATCHES -> matcher.matches();
          case LOOKING_AT -> matcher.lookingAt();
        };
    return matched ? capturedMatchTrace(matcher) : "NO_MATCH";
  }

  private static String matchTrace(java.util.regex.Matcher matcher, Operation operation) {
    boolean matched =
        switch (operation) {
          case MATCHES -> matcher.matches();
          case LOOKING_AT -> matcher.lookingAt();
        };
    return matched ? capturedMatchTrace(matcher) : "NO_MATCH";
  }

  private static List<String> findTrace(Matcher matcher) {
    List<String> trace = new ArrayList<>();
    while (matcher.find()) {
      trace.add(capturedMatchTrace(matcher));
      if (trace.size() > FIND_LIMIT) {
        throw new AssertionError("find() trace exceeded " + FIND_LIMIT + " matches");
      }
    }
    return trace;
  }

  private static List<String> findTrace(java.util.regex.Matcher matcher) {
    List<String> trace = new ArrayList<>();
    while (matcher.find()) {
      trace.add(capturedMatchTrace(matcher));
      if (trace.size() > FIND_LIMIT) {
        throw new AssertionError("find() trace exceeded " + FIND_LIMIT + " matches");
      }
    }
    return trace;
  }

  private static List<String> resultsTrace(Matcher matcher) {
    return matcher.results().map(EndAnchorRegionCompatibilityMatrixTest::matchResultTrace).toList();
  }

  private static List<String> resultsTrace(java.util.regex.Matcher matcher) {
    return matcher.results().map(EndAnchorRegionCompatibilityMatrixTest::matchResultTrace).toList();
  }

  private static List<String> resultsAfterFindTrace(Matcher matcher) {
    if (!matcher.find()) {
      return List.of();
    }
    return resultsTrace(matcher);
  }

  private static List<String> resultsAfterFindTrace(java.util.regex.Matcher matcher) {
    if (!matcher.find()) {
      return List.of();
    }
    return resultsTrace(matcher);
  }

  private static String capturedMatchTrace(Matcher matcher) {
    StringBuilder builder = new StringBuilder();
    builder.append(matcher.start()).append('-').append(matcher.end());
    for (int group = 0; group <= matcher.groupCount(); group++) {
      appendGroup(builder, group, matcher.start(group), matcher.end(group), matcher.group(group));
    }
    return builder.toString();
  }

  private static String capturedMatchTrace(java.util.regex.Matcher matcher) {
    StringBuilder builder = new StringBuilder();
    builder.append(matcher.start()).append('-').append(matcher.end());
    for (int group = 0; group <= matcher.groupCount(); group++) {
      appendGroup(builder, group, matcher.start(group), matcher.end(group), matcher.group(group));
    }
    return builder.toString();
  }

  private static String matchResultTrace(MatchResult result) {
    StringBuilder builder = new StringBuilder();
    builder.append(result.start()).append('-').append(result.end());
    for (int group = 0; group <= result.groupCount(); group++) {
      appendGroup(builder, group, result.start(group), result.end(group), result.group(group));
    }
    return builder.toString();
  }

  private static void appendGroup(
      StringBuilder builder, int group, int start, int end, String value) {
    builder
        .append(";g")
        .append(group)
        .append('=')
        .append(start)
        .append('-')
        .append(end)
        .append(':')
        .append(value == null ? "<null>" : value);
  }

  private record InputRegion(String name, String input, int start, int end) {}

  private record BoundsCase(String name, boolean transparentBounds, boolean anchoringBounds) {}

  private record Scenario(
      String name,
      String input,
      int start,
      int end,
      boolean transparentBounds,
      boolean anchoringBounds) {
    @Override
    public String toString() {
      return name;
    }
  }

  private record RegexCase(String name, String regex, int flags) {
    @Override
    public String toString() {
      return name;
    }
  }

  private record Trace(
      int groupCount,
      String matches,
      String lookingAt,
      List<String> finds,
      List<String> results,
      List<String> resultsAfterFind) {}

  private enum Operation {
    MATCHES,
    LOOKING_AT
  }
}
