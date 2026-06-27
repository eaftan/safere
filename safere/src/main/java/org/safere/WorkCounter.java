// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Package-private deterministic work accounting for performance regression tests. */
final class WorkCounter {
  private static final ThreadLocal<Counter> COUNTER = new ThreadLocal<>();

  private WorkCounter() {}

  private static final class Counter {
    private long units;
  }

  static long countForTesting(Runnable task) {
    if (!WorkCounterConfig.ENABLED) {
      throw new IllegalStateException("WorkCounter is disabled; run tests with -Pwork-counters");
    }
    Counter previous = COUNTER.get();
    Counter current = new Counter();
    COUNTER.set(current);
    try {
      task.run();
      return current.units;
    } finally {
      if (previous == null) {
        COUNTER.remove();
      } else {
        COUNTER.set(previous);
      }
    }
  }

  static void record() {
    record(1);
  }

  static void record(long units) {
    Counter counter = COUNTER.get();
    if (counter != null) {
      counter.units += units;
    }
  }
}
