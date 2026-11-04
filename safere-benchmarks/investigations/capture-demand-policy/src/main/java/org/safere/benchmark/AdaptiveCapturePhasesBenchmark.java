// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import org.openjdk.jmh.annotations.Benchmark;

/** Separately labeled phase diagnostics; its clocks are excluded from ordinary cycle timing. */
public class AdaptiveCapturePhasesBenchmark extends AdaptiveCaptureBenchmark {
  /** Records phase time and document counts for diagnosis of adaptation and recovery. */
  @Benchmark
  public long phases(AdaptivePhaseCounters counters) {
    return task.run(counters);
  }
}
