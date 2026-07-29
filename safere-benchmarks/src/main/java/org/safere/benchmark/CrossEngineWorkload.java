// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.List;
import java.util.Objects;

/** One regex workload executed consistently across multiple engines. */
final class CrossEngineWorkload {
  private final String id;
  private final BenchmarkOperation operation;
  private final List<String> patterns;
  private final List<String> inputKeys;
  private final int[] groups;
  private final String replacement;
  private final Object expected;
  private final int limit;
  private final DeclarativeBenchmarkPlan.MatcherLifecycle lifecycle;
  private final String flagSet;
  private final int seed;
  private final int count;
  private final DeclarativeBenchmarkPlan.Measurement measurement;
  private final TimingGroup timingGroup;

  CrossEngineWorkload(
      String id,
      BenchmarkOperation operation,
      List<String> patterns,
      List<String> inputKeys,
      int[] groups,
      String replacement,
      Object expected,
      int limit,
      DeclarativeBenchmarkPlan.MatcherLifecycle lifecycle,
      String flagSet,
      int seed,
      int count,
      DeclarativeBenchmarkPlan.Measurement measurement,
      TimingGroup timingGroup) {
    this.id = Objects.requireNonNull(id);
    this.operation = Objects.requireNonNull(operation);
    this.patterns = List.copyOf(patterns);
    this.inputKeys = List.copyOf(inputKeys);
    this.groups = groups.clone();
    this.replacement = replacement;
    this.expected = expected;
    this.limit = limit;
    this.lifecycle = Objects.requireNonNull(lifecycle);
    this.flagSet = flagSet;
    this.seed = seed;
    this.count = count;
    this.measurement = Objects.requireNonNull(measurement);
    this.timingGroup = Objects.requireNonNull(timingGroup);
    if (id.isBlank()) {
      throw new IllegalArgumentException("Cross-engine workload ID must not be blank");
    }
    if (id.indexOf('@') >= 0) {
      throw new IllegalArgumentException("Cross-engine workload ID must not contain '@': " + id);
    }
    if (patterns.isEmpty()) {
      throw new IllegalArgumentException(id + " requires at least one pattern");
    }
    if (inputKeys.isEmpty()
        && operation != BenchmarkOperation.COMPILE
        && operation != BenchmarkOperation.FIND_ROTATING_UTF16
        && operation != BenchmarkOperation.COMPILE_AND_FIND_ROTATING_UTF16) {
      throw new IllegalArgumentException(id + " requires at least one input");
    }
  }

  String id() {
    return id;
  }

  BenchmarkOperation operation() {
    return operation;
  }

  List<String> patterns() {
    return patterns;
  }

  List<String> inputKeys() {
    return inputKeys;
  }

  int[] groups() {
    return groups;
  }

  String replacement() {
    return replacement;
  }

  Object expected() {
    return expected;
  }

  int limit() {
    return limit;
  }

  DeclarativeBenchmarkPlan.MatcherLifecycle lifecycle() {
    return lifecycle;
  }

  String flagSet() {
    return flagSet;
  }

  int seed() {
    return seed;
  }

  int count() {
    return count;
  }

  DeclarativeBenchmarkPlan.Measurement measurement() {
    return measurement;
  }

  TimingGroup timingGroup() {
    return timingGroup;
  }

  /** Benchmark schedule group, kept separate so historical output units remain stable. */
  enum TimingGroup {
    NANOSECONDS,
    MICROSECONDS,
    MILLISECONDS
  }
}
