// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
    return Math.max(1, Math.min(10_000, progressInterval / threads));
  }

  static long firstOwnedCaseIndex(
      long rangeStartInclusive, long rangeEndExclusive, int threads, int workerIndex) {
    if (rangeStartInclusive >= rangeEndExclusive) {
      return rangeEndExclusive;
    }
    long remainder = rangeStartInclusive % threads;
    long delta = (workerIndex - remainder + threads) % threads;
    if (delta >= rangeEndExclusive - rangeStartInclusive) {
      return rangeEndExclusive;
    }
    return rangeStartInclusive + delta;
  }

  static long runStreamingLines(
      int threads, String threadNamePrefix, BufferedReader reader, LineWorker worker)
      throws IOException {
    BlockingQueue<QueuedLine> queue = new ArrayBlockingQueue<>(Math.max(128, threads * 32));
    AtomicLong produced = new AtomicLong();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread producer =
        new Thread(
            () -> produceLines(reader, threads, queue, produced, failure),
            threadNamePrefix + "reader");
    List<Thread> workers = new ArrayList<>();
    producer.start();
    for (int i = 0; i < threads; i++) {
      Thread thread = new Thread(() -> consumeLines(queue, worker, failure), threadNamePrefix + i);
      thread.start();
      workers.add(thread);
    }
    join(producer, "interrupted while waiting for replay reader");
    for (Thread thread : workers) {
      join(thread, "interrupted while waiting for replay workers");
    }
    Throwable throwable = failure.get();
    if (throwable != null) {
      propagate(throwable);
    }
    return produced.get();
  }

  private static void produceLines(
      BufferedReader reader,
      int threads,
      BlockingQueue<QueuedLine> queue,
      AtomicLong produced,
      AtomicReference<Throwable> failure) {
    try {
      String line;
      while (failure.get() == null && (line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        putQueuedLine(queue, new QueuedLine(trimmed, false), failure);
        produced.incrementAndGet();
      }
    } catch (Throwable t) {
      failure.compareAndSet(null, t);
    } finally {
      if (failure.get() != null) {
        queue.clear();
      }
      for (int i = 0; i < threads; i++) {
        putQueuedLine(queue, new QueuedLine("", true), failure);
      }
    }
  }

  private static void consumeLines(
      BlockingQueue<QueuedLine> queue, LineWorker worker, AtomicReference<Throwable> failure) {
    try {
      while (true) {
        QueuedLine queuedLine = queue.poll(100, TimeUnit.MILLISECONDS);
        if (queuedLine == null) {
          if (failure.get() != null) {
            return;
          }
          continue;
        }
        if (queuedLine.poison()) {
          return;
        }
        worker.run(queuedLine.line());
      }
    } catch (Throwable t) {
      failure.compareAndSet(null, t);
    }
  }

  private static void putQueuedLine(
      BlockingQueue<QueuedLine> queue, QueuedLine queuedLine, AtomicReference<Throwable> failure) {
    try {
      while (failure.get() == null && !queue.offer(queuedLine, 100, TimeUnit.MILLISECONDS)) {
        // Retry until a worker drains the bounded queue or another thread fails.
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      failure.compareAndSet(null, e);
    }
  }

  private static void join(Thread thread, String message) throws IOException {
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(message, e);
    }
  }

  static final class ProgressReporter {
    private final SweepRunState runState;
    private final int workerIndex;
    private final long probeInterval;
    private long checkedByWorker;
    private long nextProbe;

    ProgressReporter(SweepRunState runState, int workerIndex) {
      this.runState = runState;
      this.workerIndex = workerIndex;
      this.probeInterval =
          progressProbeInterval(runState.options.progressInterval(), runState.options.threads());
      this.nextProbe = probeInterval;
    }

    void checked() {
      checkedByWorker++;
      runState.checked.increment();
    }

    void reportIfNeeded(long generated) {
      runState.updateWorkerNextCaseIndex(workerIndex, generated);
      if (checkedByWorker < nextProbe) {
        return;
      }
      runState.checkpointCompactLogsIfNeeded();
      runState.reportProgressIfNeeded(generated);
      while (nextProbe <= checkedByWorker) {
        nextProbe += probeInterval;
      }
    }
  }

  interface Worker {
    void run(int workerIndex) throws Exception;
  }

  interface LineWorker {
    void run(String line) throws Exception;
  }

  private record QueuedLine(String line, boolean poison) {}
}
