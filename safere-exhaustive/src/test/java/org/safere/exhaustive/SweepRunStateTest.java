// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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

  @Test
  void progressReportsAreTriggeredByCheckedCases() throws Exception {
    SweepOptions options = options(Integer.MAX_VALUE, 0);
    ByteArrayOutputStream output = progressOutputAfterCheckedCases(options, 10);

    assertThat(output.toString(StandardCharsets.UTF_8))
        .contains("progress generated=10 checked=10 divergences=0 buckets=0");
  }

  @Test
  void progressReportsUseCurrentRunCheckedCountForNonzeroRanges() throws Exception {
    SweepOptions options = options(Integer.MAX_VALUE, 1_000_000);
    ByteArrayOutputStream output = progressOutputAfterCheckedCases(options, 1_010_000);

    assertThat(output.toString(StandardCharsets.UTF_8))
        .contains("progress generated=1,010,000 checked=10 divergences=0 buckets=0");
  }

  private ByteArrayOutputStream progressOutputAfterCheckedCases(
      SweepOptions options, long generated) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;

    try (SweepRunState state = new SweepRunState(options)) {
      try {
        System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));

        state.reportProgressIfNeeded(generated);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();

        for (int i = 0; i < 10; i++) {
          state.checked.increment();
        }
        state.reportProgressIfNeeded(generated);
      } finally {
        System.setOut(originalOut);
      }
    }

    return output;
  }

  private SweepOptions options(int maxPerBucket) {
    return options(maxPerBucket, 0);
  }

  private SweepOptions options(int maxPerBucket, long rangeStartInclusive) {
    return new SweepOptions(
        rangeStartInclusive, Long.MAX_VALUE, maxPerBucket, tempDir, 10, 1, null, "out.jsonl");
  }
}
