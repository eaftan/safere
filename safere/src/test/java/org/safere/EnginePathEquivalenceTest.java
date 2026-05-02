// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Forced-path equivalence coverage for package-private engine-path controls. */
@DisabledForCrosscheck("uses package-private engine-path controls to compare SafeRE internals")
class EnginePathEquivalenceTest {

  @Test
  @DisplayName("every forced engine path has a machine-readable contract")
  void everyForcedEnginePathHasContract() {
    Set<EnginePath> contracted = EnumSet.noneOf(EnginePath.class);
    for (EnginePathContract contract : EnginePathContract.all()) {
      contracted.add(contract.path());
      assertThat(contract.authorities())
          .as("authorities for %s", contract.path())
          .isNotEmpty();
      if (contract.role() != EnginePathRole.FILTER) {
        assertThat(contract.guards())
            .as("guards for %s", contract.path())
            .isNotEmpty();
      }
    }

    assertThat(contracted).containsExactlyInAnyOrderElementsOf(EnginePathOptions.accessors()
        .keySet());
    assertThat(EnginePathOptions.accessors().keySet()).containsExactlyInAnyOrderElementsOf(
        EnumSet.allOf(EnginePath.class));
  }

  @Test
  @DisplayName("engine path options disable only their declared path")
  void enginePathOptionsDisableDeclaredPath() {
    EnginePathOptions allEnabled = EnginePathOptions.allEnabled();
    for (Map.Entry<EnginePath, EnginePathOptions.OptionAccessor> entry :
        EnginePathOptions.accessors().entrySet()) {
      assertThat(entry.getValue().enabled(allEnabled))
          .as("default option for %s", entry.getKey())
          .isTrue();
    }
  }

  @Test
  @DisplayName("literal fast paths match the canonical engine trace")
  void literalFastPathsMatchCanonicalTrace() {
    assertEquivalent(
        "abc",
        "zzabcabc",
        EnginePathOptions.builder().literalFastPaths(false).build());
  }

  @Test
  @DisplayName("character-class replacement fast path matches canonical replacement")
  void characterClassReplacementFastPathMatchesCanonicalReplacement() {
    String regex = "\\d+";
    String input = "a12b345c";
    Pattern defaultPattern = Pattern.compile(regex);
    Pattern canonicalPattern = Pattern.compile(
        regex,
        0,
        EnginePathOptions.builder().charClassReplacementFastPath(false).build());

    assertThat(defaultPattern.matcher(input).replaceAll("X"))
        .isEqualTo(canonicalPattern.matcher(input).replaceAll("X"));
    assertEquivalent(
        regex,
        input,
        EnginePathOptions.builder()
            .charClassMatchFastPaths(false)
            .charClassReplacementFastPath(false)
            .build());
  }

  @Test
  @DisplayName("start accelerators match the canonical engine trace")
  void startAcceleratorsMatchCanonicalTrace() {
    assertEquivalent(
        "foo[0-9]+",
        "xxfoo123 yyfoo45",
        EnginePathOptions.builder().startAcceleration(false).build());
  }

  @Test
  @DisplayName("OnePass paths match the canonical engine trace")
  void onePassPathsMatchCanonicalTrace() {
    assertEquivalent(
        "^([A-Z]+):(\\d+)$",
        "ABC:123",
        EnginePathOptions.builder().onePass(false).build());
  }

  @Test
  @DisplayName("DFA paths match the canonical engine trace")
  void dfaPathsMatchCanonicalTrace() {
    assertEquivalent(
        "([a-z]+)([0-9]+)",
        "xxabc123yydef45",
        EnginePathOptions.builder().dfa(false).reverseDfa(false).build());
  }

  @Test
  @DisplayName("BitState paths match the Pike NFA trace")
  void bitStatePathsMatchPikeNfaTrace() {
    assertEquivalent(
        "(a|aa)*b",
        "aaaaab",
        EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build());
  }

  @Test
  @DisplayName("lazy capture extraction matches eager capture extraction")
  void lazyCaptureExtractionMatchesEagerCaptureExtraction() {
    assertEquivalent(
        "([a-z]+)([0-9]+)",
        "xxabc123yydef45",
        EnginePathOptions.builder().lazyCaptureExtraction(false).build());
  }

  private static void assertEquivalent(String regex, String input, EnginePathOptions options) {
    Pattern defaultPattern = Pattern.compile(regex);
    Pattern forcedPattern = Pattern.compile(regex, 0, options);

    assertThat(operationTrace(defaultPattern.matcher(input), Operation.MATCHES))
        .as("matches trace for /%s/ on %s", regex, input)
        .isEqualTo(operationTrace(forcedPattern.matcher(input), Operation.MATCHES));
    assertThat(operationTrace(defaultPattern.matcher(input), Operation.LOOKING_AT))
        .as("lookingAt trace for /%s/ on %s", regex, input)
        .isEqualTo(operationTrace(forcedPattern.matcher(input), Operation.LOOKING_AT));
    assertThat(findTrace(defaultPattern.matcher(input)))
        .as("find trace for /%s/ on %s", regex, input)
        .isEqualTo(findTrace(forcedPattern.matcher(input)));
    assertThat(defaultPattern.matcher(input).replaceAll("<$0>"))
        .as("replaceAll trace for /%s/ on %s", regex, input)
        .isEqualTo(forcedPattern.matcher(input).replaceAll("<$0>"));
    assertThat(defaultPattern.matcher(input).replaceFirst("<$0>"))
        .as("replaceFirst trace for /%s/ on %s", regex, input)
        .isEqualTo(forcedPattern.matcher(input).replaceFirst("<$0>"));
  }

  private static MatchTrace operationTrace(Matcher matcher, Operation operation) {
    boolean matched =
        switch (operation) {
          case MATCHES -> matcher.matches();
          case LOOKING_AT -> matcher.lookingAt();
        };
    return snapshot(matcher, matched);
  }

  private static List<MatchTrace> findTrace(Matcher matcher) {
    List<MatchTrace> traces = new ArrayList<>();
    while (matcher.find()) {
      traces.add(snapshot(matcher, true));
    }
    traces.add(snapshot(matcher, false));
    return traces;
  }

  private static MatchTrace snapshot(Matcher matcher, boolean matched) {
    List<GroupTrace> groups = new ArrayList<>();
    if (matched) {
      for (int group = 0; group <= matcher.groupCount(); group++) {
        groups.add(
            new GroupTrace(
                matcher.group(group),
                matcher.start(group),
                matcher.end(group)));
      }
    }
    return new MatchTrace(matched, groups, matcher.hitEnd(), matcher.requireEnd());
  }

  private enum Operation {
    MATCHES,
    LOOKING_AT
  }

  private record MatchTrace(
      boolean matched, List<GroupTrace> groups, boolean hitEnd, boolean requireEnd) {}

  private record GroupTrace(String value, int start, int end) {}
}
