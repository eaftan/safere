// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.List;
import java.util.Objects;

/** One regex workload executed consistently across multiple engines. */
record CrossEngineWorkload(
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

  CrossEngineWorkload {
    Objects.requireNonNull(id);
    Objects.requireNonNull(operation);
    patterns = List.copyOf(patterns);
    inputKeys = List.copyOf(inputKeys);
    groups = groups.clone();
    Objects.requireNonNull(lifecycle);
    Objects.requireNonNull(measurement);
    Objects.requireNonNull(timingGroup);
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

  /** Benchmark schedule group, kept separate so historical output units remain stable. */
  enum TimingGroup {
    NANOSECONDS,
    MICROSECONDS,
    MILLISECONDS
  }
}
