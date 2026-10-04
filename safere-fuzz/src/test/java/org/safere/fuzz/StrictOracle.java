// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import java.util.concurrent.atomic.LongAdder;

/** Synchronous JDK oracle with a deterministic text-access budget and no worker threads. */
final class StrictOracle {
  static final LongAdder COMPLETED = new LongAdder();
  static final LongAdder UNAVAILABLE = new LongAdder();

  static {
    Runtime.getRuntime()
        .addShutdownHook(new Thread(StrictOracle::report, "safere-strict-oracle-summary"));
  }

  static void completed() {
    COMPLETED.increment();
    if (COMPLETED.sum() % 4096 == 0) {
      report();
    }
  }

  static void unavailable() {
    UNAVAILABLE.increment();
    long count = UNAVAILABLE.sum();
    if ((count & (count - 1)) == 0) {
      report();
    }
  }

  static void report() {
    System.err.println(
        "Strict oracle: completed="
            + COMPLETED.sum()
            + " unavailable="
            + UNAVAILABLE.sum()
            + " excludedObservations="
            + OracleDefects.EXCLUDED_OBSERVATIONS.sum());
  }

  private StrictOracle() {}

  static CharSequence input(String text) {
    return new BudgetText(text, new Budget());
  }

  private static final class Budget {
    private int remaining = 100_000;

    void access() {
      if (--remaining < 0) {
        throw new Unavailable();
      }
    }
  }

  private record BudgetText(String text, Budget budget) implements CharSequence {
    @Override
    public int length() {
      budget.access();
      return text.length();
    }

    @Override
    public char charAt(int index) {
      budget.access();
      return text.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      budget.access();
      return new BudgetText(text.substring(start, end), budget);
    }

    @Override
    public String toString() {
      budget.access();
      return text;
    }
  }

  static final class Unavailable extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
