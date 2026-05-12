// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import org.junit.jupiter.api.Test;

/** Tests for {@link SweepWorkers}. */
class SweepWorkersTest {
  @Test
  void singleThreadRunUsesWorkerIndexZero() throws Exception {
    Set<Integer> workers = new ConcurrentSkipListSet<>();

    SweepWorkers.run(1, "test-", workers::add);

    assertThat(workers).containsExactly(0);
  }

  @Test
  void multiThreadRunUsesEveryWorkerIndex() throws Exception {
    Set<Integer> workers = new ConcurrentSkipListSet<>();

    SweepWorkers.run(4, "test-", workers::add);

    assertThat(workers).containsExactly(0, 1, 2, 3);
  }

  @Test
  void checkedExceptionsPropagateAsIoException() {
    IOException failure = new IOException("boom");

    assertThatThrownBy(
            () ->
                SweepWorkers.run(
                    1,
                    "test-",
                    workerIndex -> {
                      throw failure;
                    }))
        .isSameAs(failure);
  }

  @Test
  void runtimeExceptionsPropagateWithoutWrapping() {
    IllegalStateException failure = new IllegalStateException("boom");

    assertThatThrownBy(
            () ->
                SweepWorkers.run(
                    2,
                    "test-",
                    workerIndex -> {
                      throw failure;
                    }))
        .isSameAs(failure);
  }

  @Test
  void firstProgressAtRoundsUpToNextIntervalBoundary() {
    assertThat(SweepWorkers.firstProgressAt(0, 10)).isEqualTo(10);
    assertThat(SweepWorkers.firstProgressAt(20, 10)).isEqualTo(20);
    assertThat(SweepWorkers.firstProgressAt(21, 10)).isEqualTo(30);
  }
}
