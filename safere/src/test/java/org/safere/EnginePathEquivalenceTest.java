// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
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
      assertThat(contract.authorities()).as("authorities for %s", contract.path()).isNotEmpty();
      if (contract.role() != EnginePathRole.FILTER) {
        assertThat(contract.guards()).as("guards for %s", contract.path()).isNotEmpty();
      }
    }

    assertThat(contracted)
        .containsExactlyInAnyOrderElementsOf(EnginePathOptions.accessors().keySet());
    assertThat(EnginePathOptions.accessors().keySet())
        .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(EnginePath.class));
  }

  @Test
  @DisplayName("engine path roles constrain declared result authority")
  void enginePathRolesConstrainDeclaredResultAuthority() {
    for (EnginePathContract contract : EnginePathContract.all()) {
      if (contract.role() == EnginePathRole.FILTER) {
        assertThat(contract.authorities())
            .as("filter authorities for %s", contract.path())
            .doesNotContain(
                ResultAuthority.GROUP_ZERO,
                ResultAuthority.CAPTURES,
                ResultAuthority.DEFERRED_CAPTURES,
                ResultAuthority.REPLACEMENT_RESULT);
      }
      if (contract.role() == EnginePathRole.PARTIAL_PRODUCER) {
        assertThat(contract.authorities())
            .as("partial producer authorities for %s", contract.path())
            .doesNotContain(ResultAuthority.CAPTURES, ResultAuthority.REPLACEMENT_RESULT);
      }
    }
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
  @DisplayName("DFA sandwich reports ambiguous reverse starts")
  void dfaSandwichReportsAmbiguousReverseStarts() {
    String regex = "(?:\\B{1}|a).";
    String input = "ab";
    Pattern canonical = Pattern.compile(regex);
    Pattern unguarded =
        Pattern.compile(
            regex, 0, EnginePathOptions.builder().onePass(false).bitState(false).build());

    assertThat(findTrace(unguarded.matcher(input)))
        .as("DFA sandwich should not publish ambiguous reverse-DFA starts")
        .isEqualTo(findTrace(canonical.matcher(input)));
  }

  @Test
  @DisplayName("unguarded DFA paths preserve repeated $ find trace")
  void unguardedDfaPathsPreserveRepeatedDollarFindTrace() {
    assertUnguardedDfaFindEquivalent("$", "x".repeat(2000) + "a\n");
    assertUnguardedDfaFindEquivalent("$", "x".repeat(2000) + "a\r\n");
    assertUnguardedDfaFindEquivalent("$", "x".repeat(2000) + "a\u2028");
  }

  @Test
  @DisplayName("unguarded DFA paths fall back for ambiguous reverse starts")
  void unguardedDfaPathsFallBackForAmbiguousReverseStarts() {
    assertUnguardedDfaFindEquivalent("(?:\\B{1}|a).a?", "ab".repeat(600) + "c");
    assertUnguardedDfaFindEquivalent("(?:\\B{1}|a).a?$", "x".repeat(1100) + "ab");
  }

  @Test
  @DisplayName("unguarded DFA paths preserve end-anchored trailing terminator trace")
  void unguardedDfaPathsPreserveEndAnchoredTrailingTerminatorTrace() {
    assertUnguardedDfaFindEquivalent("(?:a+?|(?:[^x])*)$", "x".repeat(1100) + "a\n");
  }

  @Test
  @DisplayName("unguarded DFA paths preserve boundary candidate priority")
  void unguardedDfaPathsPreserveBoundaryCandidatePriority() {
    assertUnguardedDfaFindEquivalent("(?:a{2,}|(?:.|\\B){1,2}){1,2}", "baax");
  }

  @Test
  @DisplayName("literal fast paths match the canonical engine trace")
  void literalFastPathsMatchCanonicalTrace() {
    assertEquivalent(
        "abc", "zzabcabc", EnginePathOptions.builder().literalFastPaths(false).build());
  }

  @Test
  @DisplayName("character-class replacement fast path matches canonical replacement")
  void characterClassReplacementFastPathMatchesCanonicalReplacement() {
    String regex = "\\d+";
    String input = "a12b345c";
    Pattern defaultPattern = Pattern.compile(regex);
    Pattern canonicalPattern =
        Pattern.compile(
            regex, 0, EnginePathOptions.builder().charClassReplacementFastPath(false).build());

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
  @DisplayName("DFA-loop start acceleration preserves position-dependent state across inputs")
  void dfaLoopStartAccelerationPreservesPositionDependentStateAcrossInputs() {
    String regex = "(b|(?m:^a))c[0-9]";
    for (String lineTerminator : List.of("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029")) {
      Pattern accelerated = Pattern.compile(regex);
      Pattern control =
          Pattern.compile(regex, 0, EnginePathOptions.builder().startAcceleration(false).build());
      for (String input : List.of(lineTerminator + "acz9y", " ycby ", "y0c\n1bac19c1__19x y_")) {
        boolean expected = java.util.regex.Pattern.compile(regex).matcher(input).find();
        Utf8Input utf8 = Utf8Input.validated(input.getBytes(StandardCharsets.UTF_8));

        assertThat(control.find(utf8)).as("control for %s", printable(input)).isEqualTo(expected);
        assertThat(accelerated.find(utf8))
            .as("accelerated search for %s", printable(input))
            .isEqualTo(expected);
      }
    }

    String nonAsciiInput = "bxc\rca9\né\nac9é\r0x\r9a0";
    Pattern accelerated = Pattern.compile(regex);
    Pattern control =
        Pattern.compile(regex, 0, EnginePathOptions.builder().startAcceleration(false).build());
    boolean expected = java.util.regex.Pattern.compile(regex).matcher(nonAsciiInput).find();
    assertThat(control.matcher(nonAsciiInput).find()).isEqualTo(expected);
    assertThat(accelerated.matcher(nonAsciiInput).find()).isEqualTo(expected);
  }

  @Test
  @DisplayName("start-anchored fixed-offset plans preserve UTF-8 anchoring")
  void startAnchoredFixedOffsetPlansPreserveUtf8Anchoring() {
    String lateCandidate = "x".repeat(300) + "1c";
    for (String regex : List.of("\\A\\dc", "(?m:\\A\\d(?m:c))", "(\\A)[0-9]c")) {
      Pattern accelerated = Pattern.compile(regex);
      Pattern control =
          Pattern.compile(regex, 0, EnginePathOptions.builder().startAcceleration(false).build());

      assertThat(
              accelerated.find(Utf8Input.validated(lateCandidate.getBytes(StandardCharsets.UTF_8))))
          .as("accelerated search for %s", regex)
          .isFalse();
      assertThat(control.find(Utf8Input.validated(lateCandidate.getBytes(StandardCharsets.UTF_8))))
          .as("control search for %s", regex)
          .isFalse();
      assertThat(accelerated.find(Utf8Input.validated("1c".getBytes(StandardCharsets.UTF_8))))
          .as("anchored match for %s", regex)
          .isTrue();
    }
  }

  @Test
  @DisplayName("disabled start acceleration is not installed in forward DFAs")
  void disabledStartAccelerationIsNotInstalledInForwardDfas() {
    Pattern pattern =
        Pattern.compile(
            "foo[0-9]+", 0, EnginePathOptions.builder().startAcceleration(false).build());

    assertThat(pattern.forwardFirstMatchDfa().hasStartAcceleration()).isFalse();
    assertThat(pattern.forwardLongestMatchDfa().hasStartAcceleration()).isFalse();
  }

  @Test
  @DisplayName("OnePass paths match the canonical engine trace")
  void onePassPathsMatchCanonicalTrace() {
    assertEquivalent(
        "^([A-Z]+):(\\d+)$", "ABC:123", EnginePathOptions.builder().onePass(false).build());
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
  @DisplayName("forward-reverse DFA bounds match the capture-aware engine trace")
  void forwardReverseDfaBoundsMatchCaptureAwareEngineTrace() {
    assertEquivalent(
        "\\b[a-z]+ing\\b",
        "walking past a thing while talking and singing",
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
    assertThat(defaultPattern.matcher(input).replaceAll(match -> "<" + match.group() + ">"))
        .as("functional replaceAll trace for /%s/ on %s", regex, input)
        .isEqualTo(forcedPattern.matcher(input).replaceAll(match -> "<" + match.group() + ">"));
    assertThat(appendReplacementTrace(defaultPattern.matcher(input)))
        .as("appendReplacement trace for /%s/ on %s", regex, input)
        .isEqualTo(appendReplacementTrace(forcedPattern.matcher(input)));
  }

  private static void assertUnguardedDfaFindEquivalent(String regex, String input) {
    Pattern canonical = Pattern.compile(regex);
    Pattern unguarded =
        Pattern.compile(
            regex, 0, EnginePathOptions.builder().onePass(false).bitState(false).build());

    assertFindPrefixEquivalent(
        unguarded.matcher(input), canonical.matcher(input), input.length() + 2, regex, input);
  }

  private static void assertFindPrefixEquivalent(
      Matcher actual, Matcher expected, int maxSteps, String regex, String input) {
    for (int step = 0; step < maxSteps; step++) {
      boolean actualFound = actual.find();
      boolean expectedFound = expected.find();
      assertThat(actualFound)
          .as("find step %s found state for /%s/ on %s", step, regex, input)
          .isEqualTo(expectedFound);
      if (!expectedFound) {
        return;
      }
      assertThat(snapshot(actual, true))
          .as("find step %s trace for /%s/ on %s", step, regex, input)
          .isEqualTo(snapshot(expected, true));
    }
    throw new AssertionError("find trace exceeded " + maxSteps + " steps for /" + regex + "/");
  }

  @Test
  @DisplayName("region traces are engine-path equivalent")
  void regionTracesAreEnginePathEquivalent() {
    EnginePathOptions forced =
        EnginePathOptions.builder()
            .literalFastPaths(false)
            .charClassMatchFastPaths(false)
            .startAcceleration(false)
            .onePass(false)
            .dfa(false)
            .bitState(false)
            .lazyCaptureExtraction(false)
            .build();
    Pattern defaultPattern = Pattern.compile("^[a-z]+$");
    Pattern forcedPattern = Pattern.compile("^[a-z]+$", 0, forced);
    Matcher defaultMatcher = defaultPattern.matcher("00abc11").region(2, 5);
    Matcher forcedMatcher = forcedPattern.matcher("00abc11").region(2, 5);

    assertThat(operationTrace(defaultMatcher, Operation.MATCHES))
        .isEqualTo(operationTrace(forcedMatcher, Operation.MATCHES));
  }

  @Test
  @DisplayName("transparent and anchoring bounds traces are engine-path equivalent")
  void boundsTracesAreEnginePathEquivalent() {
    EnginePathOptions forced = EnginePathOptions.builder().dfa(false).bitState(false).build();
    Pattern defaultPattern = Pattern.compile("^abc$");
    Pattern forcedPattern = Pattern.compile("^abc$", 0, forced);
    Matcher defaultMatcher =
        defaultPattern.matcher("00abc11").region(2, 5).useAnchoringBounds(false);
    Matcher forcedMatcher = forcedPattern.matcher("00abc11").region(2, 5).useAnchoringBounds(false);

    assertThat(operationTrace(defaultMatcher, Operation.MATCHES))
        .isEqualTo(operationTrace(forcedMatcher, Operation.MATCHES));
  }

  @Test
  @DisplayName("multiline CRLF anchor traces are engine-path equivalent")
  void multilineCrLfAnchorTracesAreEnginePathEquivalent() {
    assertEquivalent(
        "(?m)^abc$",
        "xx\r\nabc\r\nyy",
        EnginePathOptions.builder()
            .startAcceleration(false)
            .onePass(false)
            .dfa(false)
            .reverseDfa(false)
            .bitState(false)
            .build());
  }

  @Test
  @DisplayName("nested nullable loops are compiled for DFA and match correctly")
  void nestedNullableLoopsDfaEquivalence() {
    EnginePathOptions forcedDfa =
        EnginePathOptions.builder().onePass(false).bitState(false).build();

    // Case A: Alternation matching bug from dfa_nullable_loop_analysis.md
    assertEquivalent("(?:a?\\b?)*X|(?:a?b)*c", "bc", forcedDfa);

    // Case B: Greedy optional-matching bug from dfa_nullable_loop_analysis.md
    assertEquivalent("(?:a?\\b?)*X", "aaX", forcedDfa);
  }

  @Test
  @DisplayName("priority inversion boundary patterns match across all engine paths")
  void priorityInversionEquivalence() {
    // 1. Alternation priority inversion
    String regex1 = "(?:\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*')|(\\btype\\b)";
    String input1 = "x = \"type\"";
    // Assert DFA vs canonical (NFA)
    assertEquivalent(
        regex1, input1, EnginePathOptions.builder().onePass(false).bitState(false).build());
    // Assert BitState vs canonical (NFA)
    assertEquivalent(regex1, input1, EnginePathOptions.builder().dfa(false).onePass(false).build());

    // 2. Lazy quantifier priority inversion
    String regex2 = "\\[.*?\\]\\((.*?)\\)|(\\b\\w+\\.md\\b)";
    String input2 = "abc [def](xyz.md) ghi";
    // Assert DFA vs canonical (NFA)
    assertEquivalent(
        regex2, input2, EnginePathOptions.builder().onePass(false).bitState(false).build());
    // Assert BitState vs canonical (NFA)
    assertEquivalent(regex2, input2, EnginePathOptions.builder().dfa(false).onePass(false).build());
  }

  @Test
  @DisplayName("reverse DFA preserves deferred starts at consuming boundaries")
  void reverseDfaPreservesDeferredStartsAtConsumingBoundaries() {
    String regex = "(?:(?:\\ba?)|\\B|[^a])a?";
    String input = "ba";
    EnginePathOptions forcedDfa =
        EnginePathOptions.builder().onePass(false).bitState(false).build();

    assertEquivalent(regex, input, forcedDfa);
  }

  @Test
  @DisplayName("Shift DFA matches and lookingAt equivalent to canonical engine")
  void shiftDfaEquivalence() {
    EnginePathOptions forcedShift =
        EnginePathOptions.builder()
            .shiftDfa(true)
            .dfa(false)
            .onePass(false)
            .bitState(false)
            .build();

    EnginePathOptions disabledShift = EnginePathOptions.builder().shiftDfa(false).build();

    String[] patterns = {
      "true|false",
      "null",
      "[0-9]{1,4}",
      "[a-zA-Z_][a-zA-Z0-9_]*",
      "[0-9]{4}-[0-9]{2}",
      "[\\x00-\\x21\\x23-\\x7F]*\"",
      "abc|abd|xyz"
    };

    String[] inputs = {
      "true",
      "false",
      "null",
      "1234",
      "99",
      "_identifier123",
      "2026-08",
      "some_text\"",
      "abc",
      "xyz",
      "mismatch",
      "",
      "12345",
      "truee",
      "2026-8"
    };

    for (String pattern : patterns) {
      for (String input : inputs) {
        assertEquivalent(pattern, input, forcedShift);
        assertEquivalent(pattern, input, disabledShift);
      }
    }
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

  private static String printable(String input) {
    return input.replace("\r", "\\r").replace("\n", "\\n");
  }

  private static String appendReplacementTrace(Matcher matcher) {
    StringBuilder builder = new StringBuilder();
    while (matcher.find()) {
      matcher.appendReplacement(builder, "<$0>");
    }
    matcher.appendTail(builder);
    return builder.toString();
  }

  private static MatchTrace snapshot(Matcher matcher, boolean matched) {
    List<GroupTrace> groups = new ArrayList<>();
    if (matched) {
      for (int group = 0; group <= matcher.groupCount(); group++) {
        groups.add(new GroupTrace(matcher.group(group), matcher.start(group), matcher.end(group)));
      }
    }
    return new MatchTrace(matched, groups);
  }

  private enum Operation {
    MATCHES,
    LOOKING_AT
  }

  private record MatchTrace(boolean matched, List<GroupTrace> groups) {}

  private record GroupTrace(String value, int start, int end) {}
}
