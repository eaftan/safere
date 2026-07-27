// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.LongAdder;
import org.openjdk.jmh.infra.Blackhole;
import org.safere.PatternSet;

/** Prepared execution state for one SafeRE-specific declarative trial. */
final class SpecializedTrialRunner implements AutoCloseable {
  private static final org.safere.SafeReMatchDiagnostics NO_OP =
      new org.safere.SafeReMatchDiagnostics() {};

  private final Task task;
  private final boolean diagnostics;

  private SpecializedTrialRunner(Task task, boolean diagnostics) {
    this.task = task;
    this.diagnostics = diagnostics;
  }

  static SpecializedTrialRunner prepare(String trialId) {
    SpecializedBenchmarkPlan.Trial trial = SpecializedBenchmarkPlan.load().resolve(trialId);
    if (trial.variant() != RegexEngineVariant.SAFERE_STRING
        && trial.variant() != RegexEngineVariant.SAFERE_UTF8) {
      throw new IllegalArgumentException("Specialized SafeRE trial has wrong variant: " + trialId);
    }
    DeclarativeBenchmarkPlan.ExpandedWorkload workload = trial.workload();
    return switch (workload.operation()) {
      case PATTERN_SET_MATCHES -> new SpecializedTrialRunner(patternSet(workload), false);
      case UTF8_CAPTURE_BOUNDS -> new SpecializedTrialRunner(captureBounds(workload), false);
      case UTF8_DECODE_FIND -> new SpecializedTrialRunner(decodeFind(workload), false);
      case UTF8_REPLACEMENT -> new SpecializedTrialRunner(utf8Replacement(workload), false);
      case FIND_IN_WINDOW -> new SpecializedTrialRunner(findInWindow(workload), false);
      case MATCHER_CONSTRUCTION -> new SpecializedTrialRunner(constructUtf8(workload), false);
      case ANALYZE_PATTERN -> new SpecializedTrialRunner(analyze(workload), false);
      case CACHED_ANALYSIS -> new SpecializedTrialRunner(cachedAnalysis(workload), false);
      case COMPILE_AND_ANALYZE -> new SpecializedTrialRunner(compileAndAnalyze(workload), false);
      case DIAGNOSTICS_FIND -> new SpecializedTrialRunner(diagnostics(workload), true);
      default ->
          throw new IllegalArgumentException(
              "Unsupported specialized operation: " + workload.operation());
    };
  }

  private static Task patternSet(DeclarativeBenchmarkPlan.ExpandedWorkload workload) {
    String anchor = stringArgument(workload, "anchor", "unanchored");
    int patternCount = integerArgument(workload, "patternCount", workload.patterns().size());
    PatternSet.Anchor patternAnchor =
        anchor.equals("anchored") ? PatternSet.Anchor.ANCHOR_START : PatternSet.Anchor.UNANCHORED;
    PatternSet.Builder builder = new PatternSet.Builder(patternAnchor);
    for (int i = 0; i < patternCount; i++) {
      builder.add(workload.patterns().get(i % workload.patterns().size()));
    }
    PatternSet patternSet = builder.compile();
    String input = inputString(workload);
    return blackhole -> blackhole.consume(patternSet.match(input));
  }

  private static Task captureBounds(DeclarativeBenchmarkPlan.ExpandedWorkload workload) {
    org.safere.Pattern pattern = org.safere.Pattern.compile(workload.patterns().getFirst());
    org.safere.Utf8Input input = utf8Input(workload);
    int[] groups =
        ((DeclarativeBenchmarkPlan.RecipeIntegerList) workload.arguments().get("groups"))
            .values().stream().mapToInt(Integer::intValue).toArray();
    boolean includeEnd = stringArgument(workload, "bounds", "startEnd").equals("startEnd");
    return blackhole -> {
      org.safere.Utf8Matcher matcher = pattern.matcher(input);
      int sum = 0;
      while (matcher.find()) {
        for (int group : groups) {
          sum += matcher.start(group);
          if (includeEnd) {
            sum += matcher.end(group);
          }
        }
      }
      blackhole.consume(sum);
    };
  }

  private static Task decodeFind(DeclarativeBenchmarkPlan.ExpandedWorkload workload) {
    org.safere.Pattern pattern = org.safere.Pattern.compile(workload.patterns().getFirst());
    byte[] bytes = BenchmarkData.get().getInputBytes(workload.inputIds().getFirst());
    return blackhole ->
        blackhole.consume(pattern.matcher(new String(bytes, StandardCharsets.UTF_8)).find());
  }

  private static Task utf8Replacement(DeclarativeBenchmarkPlan.ExpandedWorkload workload) {
    org.safere.Pattern pattern = org.safere.Pattern.compile(workload.patterns().getFirst());
    org.safere.Utf8Input input = utf8Input(workload);
    org.safere.Utf8Input replacement =
        org.safere.Utf8Input.validated(
            stringArgument(workload, "replacement", "").getBytes(StandardCharsets.UTF_8));
    CountingSink sink = new CountingSink();
    return blackhole -> {
      sink.reset();
      org.safere.Utf8Matcher matcher = pattern.matcher(input);
      while (matcher.find()) {
        matcher.appendReplacement(sink, replacement);
      }
      matcher.appendTail(sink);
      blackhole.consume(sink.length());
    };
  }

  private static Task findInWindow(DeclarativeBenchmarkPlan.ExpandedWorkload workload) {
    byte[] storage = BenchmarkData.get().getInputBytes(workload.inputIds().getFirst());
    DeclarativeBenchmarkPlan.LifecycleStep region =
        workload.lifecycle().steps().stream()
            .filter(step -> step.kind() == DeclarativeBenchmarkPlan.LifecycleStepKind.REGION)
            .findFirst()
            .orElseThrow();
    org.safere.Utf8Input input =
        org.safere.Utf8Input.trusted(storage, region.start(), region.end() - region.start());
    org.safere.Pattern pattern = org.safere.Pattern.compile(workload.patterns().getFirst());
    return blackhole -> blackhole.consume(pattern.find(input));
  }

  private static Task constructUtf8(DeclarativeBenchmarkPlan.ExpandedWorkload workload) {
    byte[] bytes = BenchmarkData.get().getInputBytes(workload.inputIds().getFirst());
    boolean validated = stringArgument(workload, "mode", "trusted").equals("validated");
    return blackhole ->
        blackhole.consume(
            validated
                ? org.safere.Utf8Input.validated(bytes)
                : org.safere.Utf8Input.trusted(bytes));
  }

  private static Task analyze(DeclarativeBenchmarkPlan.ExpandedWorkload workload) {
    String regex = workload.patterns().getFirst();
    return blackhole -> blackhole.consume(org.safere.Pattern.compile(regex));
  }

  private static Task cachedAnalysis(DeclarativeBenchmarkPlan.ExpandedWorkload workload) {
    org.safere.Pattern pattern = org.safere.Pattern.compile(workload.patterns().getFirst());
    pattern.analysis();
    return blackhole -> blackhole.consume(pattern.analysis());
  }

  private static Task compileAndAnalyze(DeclarativeBenchmarkPlan.ExpandedWorkload workload) {
    String regex = workload.patterns().getFirst();
    return blackhole -> blackhole.consume(org.safere.Pattern.compile(regex).analysis());
  }

  private static Task diagnostics(DeclarativeBenchmarkPlan.ExpandedWorkload workload) {
    String listener = stringArgument(workload, "listener", "disabled");
    org.safere.Pattern.setDiagnostics(
        switch (listener) {
          case "disabled" -> org.safere.SafeReMatchDiagnostics.NONE;
          case "noop" -> NO_OP;
          case "longAdder" -> new AggregatingDiagnostics();
          default ->
              throw new IllegalArgumentException("Unknown diagnostics listener: " + listener);
        });
    org.safere.Pattern pattern = org.safere.Pattern.compile(workload.patterns().getFirst());
    String input = inputString(workload);
    String action = stringArgument(workload, "action", "find");
    String replacement = stringArgument(workload, "replacement", "");
    return switch (action) {
      case "find" -> blackhole -> blackhole.consume(pattern.matcher(input).find());
      case "matches" -> blackhole -> blackhole.consume(pattern.matcher(input).matches());
      case "replaceAll" ->
          blackhole -> blackhole.consume(pattern.matcher(input).replaceAll(replacement));
      default -> throw new IllegalArgumentException("Unknown diagnostics action: " + action);
    };
  }

  private static String inputString(DeclarativeBenchmarkPlan.ExpandedWorkload workload) {
    return BenchmarkData.get().getInputString(workload.inputIds().getFirst());
  }

  private static org.safere.Utf8Input utf8Input(
      DeclarativeBenchmarkPlan.ExpandedWorkload workload) {
    return org.safere.Utf8Input.trusted(
        BenchmarkData.get().getInputBytes(workload.inputIds().getFirst()));
  }

  private static String stringArgument(
      DeclarativeBenchmarkPlan.ExpandedWorkload workload, String name, String defaultValue) {
    DeclarativeBenchmarkPlan.RecipeValue value = workload.arguments().get(name);
    return value == null ? defaultValue : ((DeclarativeBenchmarkPlan.RecipeString) value).value();
  }

  private static int integerArgument(
      DeclarativeBenchmarkPlan.ExpandedWorkload workload, String name, int defaultValue) {
    DeclarativeBenchmarkPlan.RecipeValue value = workload.arguments().get(name);
    return value == null ? defaultValue : ((DeclarativeBenchmarkPlan.RecipeInteger) value).value();
  }

  void run(Blackhole blackhole) {
    task.run(blackhole);
  }

  @Override
  public void close() {
    if (diagnostics) {
      org.safere.Pattern.setDiagnostics(org.safere.SafeReMatchDiagnostics.NONE);
    }
  }

  private interface Task {
    void run(Blackhole blackhole);
  }

  private static final class CountingSink implements org.safere.Utf8Sink {
    private int length;

    @Override
    public void append(byte[] bytes, int offset, int rangeLength) {
      length += rangeLength;
    }

    void reset() {
      length = 0;
    }

    int length() {
      return length;
    }
  }

  private static final class AggregatingDiagnostics extends org.safere.SafeReMatchDiagnostics {
    private final LongAdder operations = new LongAdder();
    private final LongAdder matches = new LongAdder();

    @Override
    public void onOperationCompleted(org.safere.OperationDiagnostics event) {
      operations.increment();
      matches.add(event.matchCount());
    }
  }
}
