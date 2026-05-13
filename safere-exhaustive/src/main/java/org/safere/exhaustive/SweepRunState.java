// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/** Shared state and reporting for exhaustive sweep runs. */
final class SweepRunState implements AutoCloseable {
  final SweepOptions options;
  final long totalChecks;
  final Map<String, Bucket> buckets = new LinkedHashMap<>();
  final LongAdder checked = new LongAdder();
  final LongAdder divergences = new LongAdder();
  private final BufferedWriter jsonlWriter;
  private final LongSupplier nanoTime;
  private final long startNanos;
  long generated;
  long nextCheckedProgressReport;

  SweepRunState(SweepOptions options, long totalChecks) throws IOException {
    this(options, totalChecks, System::nanoTime);
  }

  SweepRunState(SweepOptions options, long totalChecks, LongSupplier nanoTime) throws IOException {
    if (totalChecks < 0) {
      throw new IllegalArgumentException("totalChecks must be non-negative");
    }
    this.options = options;
    this.totalChecks = totalChecks;
    this.nanoTime = nanoTime;
    this.startNanos = nanoTime.getAsLong();
    this.jsonlWriter =
        Files.newBufferedWriter(
            options.jsonlPath(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
    this.nextCheckedProgressReport = options.progressInterval();
  }

  synchronized void recordGenerated(long workerGenerated) {
    if (workerGenerated > generated) {
      generated = workerGenerated;
    }
  }

  synchronized void reportProgressIfNeeded(long workerGenerated) {
    recordGenerated(workerGenerated);
    long checkedCount = checked.sum();
    if (checkedCount < nextCheckedProgressReport) {
      return;
    }
    double progress = totalChecks == 0 ? 100.0 : checkedCount * 100.0 / totalChecks;
    long elapsedNanos = Math.max(0, nanoTime.getAsLong() - startNanos);
    long remainingNanos = estimatedRemainingNanos(elapsedNanos, checkedCount);
    System.out.printf(
        Locale.ROOT,
        "progress=%.1f%% elapsed=%s eta=%s total=%,d checked=%,d divergences=%,d%n",
        progress,
        formatDuration(elapsedNanos),
        formatDuration(remainingNanos),
        totalChecks,
        checkedCount,
        divergences.sum());
    while (nextCheckedProgressReport <= checkedCount) {
      nextCheckedProgressReport += options.progressInterval();
    }
  }

  private long estimatedRemainingNanos(long elapsedNanos, long checkedCount) {
    if (checkedCount == 0 || checkedCount >= totalChecks) {
      return 0;
    }
    long remainingChecks = totalChecks - checkedCount;
    return (long) (elapsedNanos * ((double) remainingChecks / checkedCount));
  }

  private static String formatDuration(long nanos) {
    long totalSeconds = TimeUnit.NANOSECONDS.toSeconds(nanos);
    long hours = totalSeconds / 3_600;
    long minutes = (totalSeconds % 3_600) / 60;
    long seconds = totalSeconds % 60;
    if (hours > 0) {
      return String.format(Locale.ROOT, "%dh%02dm%02ds", hours, minutes, seconds);
    }
    if (minutes > 0) {
      return String.format(Locale.ROOT, "%dm%02ds", minutes, seconds);
    }
    return seconds + "s";
  }

  boolean reserveDivergenceExample(String bucketName) {
    synchronized (this) {
      divergences.increment();
      Bucket bucket = buckets.computeIfAbsent(bucketName, Bucket::new);
      if (bucket.savedExamples >= options.maxPerBucket()) {
        return false;
      }
      bucket.savedExamples++;
      return true;
    }
  }

  synchronized void appendJsonl(String line) {
    try {
      jsonlWriter.write(line);
      jsonlWriter.newLine();
    } catch (IOException e) {
      throw new IllegalStateException("failed to write divergence report", e);
    }
  }

  @Override
  public synchronized void close() throws IOException {
    jsonlWriter.close();
  }

  private static final class Bucket {
    int savedExamples;

    Bucket(String name) {}
  }
}
