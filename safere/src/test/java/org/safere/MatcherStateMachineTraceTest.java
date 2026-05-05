// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Trace-based lifecycle tests for {@link Matcher}. */
@DisabledForCrosscheck("this test is itself a SafeRE-vs-JDK lifecycle crosscheck")
class MatcherStateMachineTraceTest {

  @Test
  @DisplayName("failed matches() leaves the next find() sequence JDK-compatible")
  void failedMatchesLeavesNextFindSequenceJdkCompatible() {
    assertTrace("a", "ba",
        Step.matches(),
        Step.hitEnd(),
        Step.requireEnd(),
        Step.find(),
        Step.group(),
        Step.start(),
        Step.end(),
        Step.find());
  }

  @Test
  @DisplayName("failed lookingAt() leaves the next find() sequence JDK-compatible")
  void failedLookingAtLeavesNextFindSequenceJdkCompatible() {
    assertTrace("a", "ba",
        Step.lookingAt(),
        Step.hitEnd(),
        Step.requireEnd(),
        Step.find(),
        Step.group(),
        Step.start(),
        Step.end(),
        Step.find());
  }

  @Test
  @DisplayName("empty matches advance the following find() sequence like the JDK")
  void emptyMatchesAdvanceFollowingFindSequenceLikeJdk() {
    assertTrace("a*", "ba",
        Step.find(),
        Step.group(),
        Step.find(),
        Step.group(),
        Step.find(),
        Step.group(),
        Step.find());
  }

  @Test
  @DisplayName("final nullable find at input end preserves JDK-compatible hitEnd")
  void finalNullableFindAtInputEndPreservesJdkCompatibleHitEnd() {
    assertTrace("(?:(a)?)??c?", "c" + "b".repeat(3),
        Step.find(),
        Step.hitEnd(),
        Step.requireEnd(),
        Step.find(),
        Step.hitEnd(),
        Step.requireEnd(),
        Step.find(),
        Step.hitEnd(),
        Step.requireEnd(),
        Step.find(),
        Step.hitEnd(),
        Step.requireEnd(),
        Step.find(),
        Step.hitEnd(),
        Step.requireEnd(),
        Step.find(),
        Step.hitEnd(),
        Step.requireEnd());
  }

  @Test
  @DisplayName("find failure after terminal empty-pattern match preserves JDK-compatible hitEnd")
  void findFailureAfterTerminalEmptyPatternMatchPreservesJdkCompatibleHitEnd() {
    assertTrace("", "abc",
        Step.find(),
        Step.hitEnd(),
        Step.requireEnd(),
        Step.find(),
        Step.hitEnd(),
        Step.requireEnd(),
        Step.find(),
        Step.hitEnd(),
        Step.requireEnd(),
        Step.find(),
        Step.hitEnd(),
        Step.requireEnd(),
        Step.find(),
        Step.hitEnd(),
        Step.requireEnd(),
        Step.find(),
        Step.hitEnd(),
        Step.requireEnd());
  }

  @Test
  @DisplayName("find(int) resets region and append position before searching")
  void findStartResetsRegionAndAppendPositionBeforeSearching() {
    assertTrace("\\d+", "xx11yy22",
        Step.region(2, 4),
        Step.find(),
        Step.appendReplacement("[$0]"),
        Step.findFrom(6),
        Step.regionStart(),
        Step.regionEnd(),
        Step.appendReplacement("[$0]"),
        Step.appendTail());
  }

  @Test
  @DisplayName("usePattern preserves the JDK-compatible next search position")
  void usePatternPreservesJdkCompatibleNextSearchPosition() {
    assertTrace("a*", "ba",
        Step.find(),
        Step.group(),
        Step.usePattern("."),
        Step.find(),
        Step.group(),
        Step.find(),
        Step.group());
  }

  @Test
  @DisplayName("bounds changes preserve observable match data")
  void boundsChangesPreserveObservableMatchData() {
    assertTrace("(a+)", "xxaaaa",
        Step.region(2, 6),
        Step.find(),
        Step.useAnchoringBounds(false),
        Step.group(1),
        Step.toMatchResult(),
        Step.useTransparentBounds(true),
        Step.start(1),
        Step.end(1));
  }

  @Test
  @DisplayName("reset and region preserve end-state flags until the next match attempt")
  void resetAndRegionPreserveEndStateUntilNextAttempt() {
    assertTrace("a+$", "aaa",
        Step.find(),
        Step.hitEnd(),
        Step.requireEnd(),
        Step.reset(),
        Step.hitEnd(),
        Step.requireEnd(),
        Step.region(0, 2),
        Step.hitEnd(),
        Step.requireEnd(),
        Step.find(),
        Step.hitEnd(),
        Step.requireEnd());
  }

  @Test
  @DisplayName("end-state traces match the JDK across anchors, regions, and resets")
  void endStateTracesMatchJdkAcrossAnchorsRegionsAndResets() {
    assertEndStateTrace("a$", "a");
    assertEndStateTrace("a\\Z", "a");
    assertEndStateTrace("a\\z", "a");
    assertEndStateTrace("a\\b", "a");
    assertEndStateTrace("(?m)^b$", "a\r\nb");
  }

  @Test
  @DisplayName("structural mutations during results() traversal match the JDK")
  void structuralMutationsDuringResultsTraversalMatchJdk() {
    for (Mutation mutation : structuralMutations()) {
      String safere = runResultsMutationTrace(mutation, false);
      String jdk = runResultsMutationTrace(mutation, true);

      assertThat(safere).as(mutation.name()).isEqualTo(jdk);
    }
  }

  @Test
  @DisplayName("structural mutations during functional replacement match the JDK")
  void structuralMutationsDuringFunctionalReplacementMatchJdk() {
    for (Mutation mutation : structuralMutations()) {
      String safereAll = runFunctionalReplacementMutationTrace(mutation, false, false);
      String jdkAll = runFunctionalReplacementMutationTrace(mutation, true, false);
      String safereFirst = runFunctionalReplacementMutationTrace(mutation, false, true);
      String jdkFirst = runFunctionalReplacementMutationTrace(mutation, true, true);

      assertThat(safereAll).as("replaceAll %s", mutation.name()).isEqualTo(jdkAll);
      assertThat(safereFirst).as("replaceFirst %s", mutation.name()).isEqualTo(jdkFirst);
    }
  }

  private static void assertEndStateTrace(String regex, String input) {
    assertTrace(regex, input,
        Step.find(),
        Step.hitEnd(),
        Step.requireEnd(),
        Step.reset(),
        Step.hitEnd(),
        Step.requireEnd(),
        Step.region(0, input.length()),
        Step.hitEnd(),
        Step.requireEnd(),
        Step.useAnchoringBounds(false),
        Step.hitEnd(),
        Step.requireEnd(),
        Step.find(),
        Step.hitEnd(),
        Step.requireEnd(),
        Step.find(),
        Step.hitEnd(),
        Step.requireEnd());
  }

  private static List<Mutation> structuralMutations() {
    return List.of(
        new Mutation(
            "useTransparentBounds(true)",
            subject -> subject.useTransparentBounds(true)),
        new Mutation(
            "useTransparentBounds(false)",
            subject -> subject.useTransparentBounds(false)),
        new Mutation(
            "useAnchoringBounds(true)",
            subject -> subject.useAnchoringBounds(true)),
        new Mutation(
            "useAnchoringBounds(false)",
            subject -> subject.useAnchoringBounds(false)),
        new Mutation(
            "appendReplacement",
            subject -> subject.appendReplacement(new StringBuilder(), "x")),
        new Mutation(
            "appendTail",
            subject -> subject.appendTail(new StringBuilder())));
  }

  private static String runResultsMutationTrace(Mutation mutation, boolean jdk) {
    TraceSubject subject = traceSubject("a", "aa", jdk);
    try {
      List<String> groups =
          subject.resultsWithMutation(mutation).collect(Collectors.toList());
      return "ok:" + groups;
    } catch (RuntimeException e) {
      return "throws " + e.getClass().getSimpleName();
    }
  }

  private static String runFunctionalReplacementMutationTrace(
      Mutation mutation, boolean jdk, boolean first) {
    TraceSubject subject = traceSubject("a", "aa", jdk);
    try {
      String replaced = first
          ? subject.replaceFirstWithMutation(mutation)
          : subject.replaceAllWithMutation(mutation);
      return "ok:" + replaced;
    } catch (RuntimeException e) {
      return "throws " + e.getClass().getSimpleName();
    }
  }

  private static TraceSubject traceSubject(String regex, String input, boolean jdk) {
    if (jdk) {
      return new JdkSubject(java.util.regex.Pattern.compile(regex).matcher(input));
    }
    return new SafeReSubject(Pattern.compile(regex).matcher(input));
  }

  private static void assertTrace(String regex, String input, Step... steps) {
    TraceSubject safere = new SafeReSubject(Pattern.compile(regex).matcher(input));
    TraceSubject jdk =
        new JdkSubject(java.util.regex.Pattern.compile(regex).matcher(input));

    List<String> safereTrace = trace(safere, steps);
    List<String> jdkTrace = trace(jdk, steps);

    assertThat(safereTrace)
        .as("trace for /%s/ on %s", regex, input)
        .isEqualTo(jdkTrace);
  }

  private static List<String> trace(TraceSubject subject, Step... steps) {
    List<String> events = new ArrayList<>();
    for (Step step : steps) {
      events.add(step.apply(subject));
    }
    return events;
  }

  private interface TraceSubject {
    boolean matches();

    boolean lookingAt();

    boolean find();

    boolean find(int start);

    String group();

    String group(int group);

    int start();

    int start(int group);

    int end();

    int end(int group);

    boolean hitEnd();

    boolean requireEnd();

    void reset();

    void region(int start, int end);

    int regionStart();

    int regionEnd();

    void usePattern(String regex);

    void useAnchoringBounds(boolean value);

    void useTransparentBounds(boolean value);

    MatchResult toMatchResult();

    void appendReplacement(StringBuilder builder, String replacement);

    void appendTail(StringBuilder builder);

    java.util.stream.Stream<String> resultsWithMutation(Mutation mutation);

    String replaceAllWithMutation(Mutation mutation);

    String replaceFirstWithMutation(Mutation mutation);
  }

  private static final class SafeReSubject implements TraceSubject {
    private Matcher matcher;

    SafeReSubject(Matcher matcher) {
      this.matcher = matcher;
    }

    @Override
    public boolean matches() {
      return matcher.matches();
    }

    @Override
    public boolean lookingAt() {
      return matcher.lookingAt();
    }

    @Override
    public boolean find() {
      return matcher.find();
    }

    @Override
    public boolean find(int start) {
      return matcher.find(start);
    }

    @Override
    public String group() {
      return matcher.group();
    }

    @Override
    public String group(int group) {
      return matcher.group(group);
    }

    @Override
    public int start() {
      return matcher.start();
    }

    @Override
    public int start(int group) {
      return matcher.start(group);
    }

    @Override
    public int end() {
      return matcher.end();
    }

    @Override
    public int end(int group) {
      return matcher.end(group);
    }

    @Override
    public boolean hitEnd() {
      return matcher.hitEnd();
    }

    @Override
    public boolean requireEnd() {
      return matcher.requireEnd();
    }

    @Override
    public void reset() {
      matcher.reset();
    }

    @Override
    public void region(int start, int end) {
      matcher.region(start, end);
    }

    @Override
    public int regionStart() {
      return matcher.regionStart();
    }

    @Override
    public int regionEnd() {
      return matcher.regionEnd();
    }

    @Override
    public void usePattern(String regex) {
      matcher.usePattern(Pattern.compile(regex));
    }

    @Override
    public void useAnchoringBounds(boolean value) {
      matcher.useAnchoringBounds(value);
    }

    @Override
    public void useTransparentBounds(boolean value) {
      matcher.useTransparentBounds(value);
    }

    @Override
    public MatchResult toMatchResult() {
      return matcher.toMatchResult();
    }

    @Override
    public void appendReplacement(StringBuilder builder, String replacement) {
      matcher.appendReplacement(builder, replacement);
    }

    @Override
    public void appendTail(StringBuilder builder) {
      matcher.appendTail(builder);
    }

    @Override
    public java.util.stream.Stream<String> resultsWithMutation(Mutation mutation) {
      return matcher.results()
          .map(
              result -> {
                mutation.apply(this);
                return result.group();
              });
    }

    @Override
    public String replaceAllWithMutation(Mutation mutation) {
      return matcher.replaceAll(
          result -> {
            mutation.apply(this);
            return "x";
          });
    }

    @Override
    public String replaceFirstWithMutation(Mutation mutation) {
      return matcher.replaceFirst(
          result -> {
            mutation.apply(this);
            return "x";
          });
    }
  }

  private static final class JdkSubject implements TraceSubject {
    private java.util.regex.Matcher matcher;

    JdkSubject(java.util.regex.Matcher matcher) {
      this.matcher = matcher;
    }

    @Override
    public boolean matches() {
      return matcher.matches();
    }

    @Override
    public boolean lookingAt() {
      return matcher.lookingAt();
    }

    @Override
    public boolean find() {
      return matcher.find();
    }

    @Override
    public boolean find(int start) {
      return matcher.find(start);
    }

    @Override
    public String group() {
      return matcher.group();
    }

    @Override
    public String group(int group) {
      return matcher.group(group);
    }

    @Override
    public int start() {
      return matcher.start();
    }

    @Override
    public int start(int group) {
      return matcher.start(group);
    }

    @Override
    public int end() {
      return matcher.end();
    }

    @Override
    public int end(int group) {
      return matcher.end(group);
    }

    @Override
    public boolean hitEnd() {
      return matcher.hitEnd();
    }

    @Override
    public boolean requireEnd() {
      return matcher.requireEnd();
    }

    @Override
    public void reset() {
      matcher.reset();
    }

    @Override
    public void region(int start, int end) {
      matcher.region(start, end);
    }

    @Override
    public int regionStart() {
      return matcher.regionStart();
    }

    @Override
    public int regionEnd() {
      return matcher.regionEnd();
    }

    @Override
    public void usePattern(String regex) {
      matcher.usePattern(java.util.regex.Pattern.compile(regex));
    }

    @Override
    public void useAnchoringBounds(boolean value) {
      matcher.useAnchoringBounds(value);
    }

    @Override
    public void useTransparentBounds(boolean value) {
      matcher.useTransparentBounds(value);
    }

    @Override
    public MatchResult toMatchResult() {
      return matcher.toMatchResult();
    }

    @Override
    public void appendReplacement(StringBuilder builder, String replacement) {
      matcher.appendReplacement(builder, replacement);
    }

    @Override
    public void appendTail(StringBuilder builder) {
      matcher.appendTail(builder);
    }

    @Override
    public java.util.stream.Stream<String> resultsWithMutation(Mutation mutation) {
      return matcher.results()
          .map(
              result -> {
                mutation.apply(this);
                return result.group();
              });
    }

    @Override
    public String replaceAllWithMutation(Mutation mutation) {
      return matcher.replaceAll(
          result -> {
            mutation.apply(this);
            return "x";
          });
    }

    @Override
    public String replaceFirstWithMutation(Mutation mutation) {
      return matcher.replaceFirst(
          result -> {
            mutation.apply(this);
            return "x";
          });
    }
  }

  private record Mutation(String name, Effect effect) {
    void apply(TraceSubject subject) {
      effect.apply(subject);
    }
  }

  private record Step(String name, Operation operation) {
    static Step matches() {
      return value("matches", subject -> subject.matches());
    }

    static Step lookingAt() {
      return value("lookingAt", subject -> subject.lookingAt());
    }

    static Step find() {
      return value("find", subject -> subject.find());
    }

    static Step findFrom(int start) {
      return value("find(" + start + ")", subject -> subject.find(start));
    }

    static Step group() {
      return value("group", subject -> subject.group());
    }

    static Step group(int group) {
      return value("group(" + group + ")", subject -> subject.group(group));
    }

    static Step start() {
      return value("start", subject -> subject.start());
    }

    static Step start(int group) {
      return value("start(" + group + ")", subject -> subject.start(group));
    }

    static Step end() {
      return value("end", subject -> subject.end());
    }

    static Step end(int group) {
      return value("end(" + group + ")", subject -> subject.end(group));
    }

    static Step hitEnd() {
      return value("hitEnd", subject -> subject.hitEnd());
    }

    static Step requireEnd() {
      return value("requireEnd", subject -> subject.requireEnd());
    }

    static Step reset() {
      return effect("reset", TraceSubject::reset);
    }

    static Step region(int start, int end) {
      return effect("region(" + start + "," + end + ")", subject -> subject.region(start, end));
    }

    static Step regionStart() {
      return value("regionStart", subject -> subject.regionStart());
    }

    static Step regionEnd() {
      return value("regionEnd", subject -> subject.regionEnd());
    }

    static Step usePattern(String regex) {
      return effect("usePattern(" + regex + ")", subject -> subject.usePattern(regex));
    }

    static Step useAnchoringBounds(boolean value) {
      return effect(
          "useAnchoringBounds(" + value + ")",
          subject -> subject.useAnchoringBounds(value));
    }

    static Step useTransparentBounds(boolean value) {
      return effect(
          "useTransparentBounds(" + value + ")",
          subject -> subject.useTransparentBounds(value));
    }

    static Step toMatchResult() {
      return value("toMatchResult", subject -> snapshot(subject.toMatchResult()));
    }

    static Step appendReplacement(String replacement) {
      return new Step(
          "appendReplacement(" + replacement + ")",
          subject -> {
            StringBuilder builder = new StringBuilder();
            subject.appendReplacement(builder, replacement);
            return builder.toString();
          });
    }

    static Step appendTail() {
      return new Step(
          "appendTail",
          subject -> {
            StringBuilder builder = new StringBuilder();
            subject.appendTail(builder);
            return builder.toString();
          });
    }

    String apply(TraceSubject subject) {
      try {
        return name + "=" + operation.apply(subject);
      } catch (RuntimeException e) {
        return name + " throws " + e.getClass().getSimpleName();
      }
    }

    private static Step value(String name, Operation operation) {
      return new Step(name, operation);
    }

    private static Step effect(String name, Effect effect) {
      return new Step(
          name,
          subject -> {
            effect.apply(subject);
            return "ok";
          });
    }

    private static String snapshot(MatchResult result) {
      List<String> groups = new ArrayList<>();
      for (int group = 0; group <= result.groupCount(); group++) {
        try {
          groups.add(group + ":" + result.start(group) + "-" + result.end(group)
              + "=" + result.group(group));
        } catch (RuntimeException e) {
          groups.add(group + ":throws " + e.getClass().getSimpleName());
        }
      }
      return groups.toString();
    }
  }

  private interface Operation {
    Object apply(TraceSubject subject);
  }

  private interface Effect {
    void apply(TraceSubject subject);
  }
}
