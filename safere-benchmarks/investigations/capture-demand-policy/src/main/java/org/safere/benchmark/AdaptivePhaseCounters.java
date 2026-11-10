// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Phase-diagnostic totals; divide each phase's nanos counter by its documents counter. */
@AuxCounters(AuxCounters.Type.EVENTS)
@State(Scope.Thread)
public class AdaptivePhaseCounters {
  public long phase0Nanos;
  public long phase1Nanos;
  public long phase2Nanos;
  public long phase0Documents;
  public long phase1Documents;
  public long phase2Documents;

  /** Starts fresh totals for each JMH iteration. */
  @Setup(Level.Iteration)
  public void reset() {
    phase0Nanos = 0;
    phase1Nanos = 0;
    phase2Nanos = 0;
    phase0Documents = 0;
    phase1Documents = 0;
    phase2Documents = 0;
  }

  void record(int phase, long nanos, int documents) {
    switch (phase) {
      case 0 -> {
        phase0Nanos += nanos;
        phase0Documents += documents;
      }
      case 1 -> {
        phase1Nanos += nanos;
        phase1Documents += documents;
      }
      case 2 -> {
        phase2Nanos += nanos;
        phase2Documents += documents;
      }
      default -> throw new IllegalArgumentException("Expected three adaptation phases");
    }
  }
}
