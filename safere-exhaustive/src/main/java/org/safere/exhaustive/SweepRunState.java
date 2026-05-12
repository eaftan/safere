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
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Shared state and reporting for exhaustive sweep runs. */
final class SweepRunState implements AutoCloseable {
  final SweepOptions options;
  final Map<String, Bucket> buckets = new LinkedHashMap<>();
  final LongAdder checked = new LongAdder();
  final LongAdder divergences = new LongAdder();
  private final BufferedWriter jsonlWriter;
  long generated;
  long nextProgressReport;

  SweepRunState(SweepOptions options) throws IOException {
    this.options = options;
    this.jsonlWriter =
        Files.newBufferedWriter(
            options.jsonlPath(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
    this.nextProgressReport =
        SweepWorkers.firstProgressAt(options.rangeStartInclusive(), options.progressInterval());
  }

  synchronized void recordGenerated(long workerGenerated) {
    if (workerGenerated > generated) {
      generated = workerGenerated;
    }
  }

  synchronized void reportProgressIfNeeded(long workerGenerated) {
    recordGenerated(workerGenerated);
    if (generated < nextProgressReport) {
      return;
    }
    System.out.printf(
        "progress generated=%,d checked=%,d divergences=%,d buckets=%,d jsonl=%s%n",
        generated, checked.sum(), divergences.sum(), buckets.size(), options.jsonlPath());
    while (nextProgressReport <= generated) {
      nextProgressReport += options.progressInterval();
    }
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
