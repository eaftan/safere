// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;

/** Samples individual document scans, including their public reset/region and capture reads. */
@BenchmarkMode(Mode.SampleTime)
public class AdaptiveCaptureLatencyBenchmark extends AdaptiveCaptureBenchmark {
  /** Advances one document in the same declared phased schedule. */
  @Benchmark
  public long latency() {
    return task.runOne();
  }
}
