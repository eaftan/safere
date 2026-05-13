// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Threading utilities for exhaustive sweep workers. */
final class SweepWorkers {
  private SweepWorkers() {}

  static void run(int threads, String threadNamePrefix, Worker worker) throws IOException {
    if (threads == 1) {
      try {
        worker.run(0);
      } catch (Throwable t) {
        propagate(t);
      }
      return;
    }

    AtomicReference<Throwable> failure = new AtomicReference<>();
    List<Thread> workers = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      int workerIndex = i;
      Thread thread =
          new Thread(
              () -> {
                try {
                  worker.run(workerIndex);
                } catch (Throwable t) {
                  failure.compareAndSet(null, t);
                }
              },
              threadNamePrefix + workerIndex);
      thread.start();
      workers.add(thread);
    }
    for (Thread thread : workers) {
      try {
        thread.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted while waiting for sweep workers", e);
      }
    }
    Throwable throwable = failure.get();
    if (throwable != null) {
      propagate(throwable);
    }
  }

  private static void propagate(Throwable throwable) throws IOException {
    if (throwable instanceof Error error) {
      throw error;
    }
    if (throwable instanceof RuntimeException runtimeException) {
      throw runtimeException;
    }
    if (throwable instanceof IOException ioException) {
      throw ioException;
    }
    throw new IOException("sweep worker failed", throwable);
  }

  static long firstProgressAt(long rangeStartInclusive, long progressInterval) {
    if (rangeStartInclusive <= 0) {
      return progressInterval;
    }
    long remainder = rangeStartInclusive % progressInterval;
    if (remainder == 0) {
      return rangeStartInclusive;
    }
    return rangeStartInclusive + (progressInterval - remainder);
  }

  static long progressProbeInterval(long progressInterval, int threads) {
    return Math.max(1, progressInterval / threads);
  }

  static final class ProgressReporter {
    private final SweepRunState runState;
    private final long probeInterval;
    private long checkedByWorker;
    private long nextProbe;

    ProgressReporter(SweepRunState runState) {
      this.runState = runState;
      this.probeInterval =
          progressProbeInterval(runState.options.progressInterval(), runState.options.threads());
      this.nextProbe = probeInterval;
    }

    void checked() {
      checkedByWorker++;
      runState.checked.increment();
    }

    void reportIfNeeded(long generated) {
      if (checkedByWorker < nextProbe) {
        return;
      }
      runState.reportProgressIfNeeded(generated);
      while (nextProbe <= checkedByWorker) {
        nextProbe += probeInterval;
      }
    }
  }

  interface Worker {
    void run(int workerIndex) throws Exception;
  }
}
