// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link SweepRunState}. */
class SweepRunStateTest {
  @TempDir Path tempDir;

  @Test
  void bucketReservationCountsDivergencesEvenPastSavedExampleCap() throws Exception {
    SweepOptions options = options(1);

    try (SweepRunState state = new SweepRunState(options)) {
      assertThat(state.reserveDivergenceExample("bucket")).isTrue();
      assertThat(state.reserveDivergenceExample("bucket")).isFalse();

      assertThat(state.divergences.sum()).isEqualTo(2);
      assertThat(state.buckets).hasSize(1);
    }
  }

  @Test
  void appendJsonlWritesOneLinePerCall() throws Exception {
    SweepOptions options = options(Integer.MAX_VALUE);

    try (SweepRunState state = new SweepRunState(options)) {
      state.appendJsonl("{\"a\":1}");
      state.appendJsonl("{\"b\":2}");
    }

    assertThat(Files.readAllLines(options.jsonlPath())).containsExactly("{\"a\":1}", "{\"b\":2}");
  }

  @Test
  void recordsLargestGeneratedValue() throws Exception {
    SweepOptions options = options(Integer.MAX_VALUE);

    try (SweepRunState state = new SweepRunState(options)) {
      state.recordGenerated(10);
      state.recordGenerated(5);

      assertThat(state.generated).isEqualTo(10);
    }
  }

  private SweepOptions options(int maxPerBucket) {
    return new SweepOptions(0, Long.MAX_VALUE, maxPerBucket, tempDir, 10, 1, null, "out.jsonl");
  }
}
