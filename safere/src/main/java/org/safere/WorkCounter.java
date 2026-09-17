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
    private long startScanCalls;
    private long startSkippedUnits;
    private long quarantineCount;
    private int largestQuarantine;
  }

  record StartAccelerationWork(
      long calls, long skippedUnits, long quarantines, int largestQuarantine) {}

  static long countForTesting(Runnable task) {
    return count(task).units;
  }

  static StartAccelerationWork countStartAccelerationForTesting(Runnable task) {
    Counter counter = count(task);
    return new StartAccelerationWork(
        counter.startScanCalls,
        counter.startSkippedUnits,
        counter.quarantineCount,
        counter.largestQuarantine);
  }

  private static Counter count(Runnable task) {
    if (!WorkCounterConfig.ENABLED) {
      throw new IllegalStateException("WorkCounter is disabled; run tests with -Pwork-counters");
    }
    Counter previous = COUNTER.get();
    Counter current = new Counter();
    COUNTER.set(current);
    try {
      task.run();
      return current;
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

  static void recordStartScan(int skippedUnits) {
    Counter counter = COUNTER.get();
    if (counter != null) {
      counter.startScanCalls++;
      counter.startSkippedUnits += skippedUnits;
    }
  }

  static void recordStartQuarantine(int window) {
    Counter counter = COUNTER.get();
    if (counter != null) {
      counter.quarantineCount++;
      counter.largestQuarantine = Math.max(counter.largestQuarantine, window);
    }
  }
}
