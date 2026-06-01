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
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link SweepRunState}. */
class SweepRunStateTest {
  @TempDir Path tempDir;

  @Test
  void recordDivergenceCountsEveryDivergence() throws Exception {
    SweepOptions options = options();

    try (SweepRunState state = new SweepRunState(options, 10)) {
      state.recordDivergence();
      state.recordDivergence();

      assertThat(state.divergences.sum()).isEqualTo(2);
    }
  }

  @Test
  void appendJsonlWritesOneLinePerCall() throws Exception {
    SweepOptions options = options();

    try (SweepRunState state = new SweepRunState(options, 10)) {
      state.appendJsonl("{\"a\":1}");
      state.appendJsonl("{\"b\":2}");
    }

    assertThat(Files.readAllLines(options.jsonlPath())).containsExactly("{\"a\":1}", "{\"b\":2}");
  }

  @Test
  void recordsLargestGeneratedValue() throws Exception {
    SweepOptions options = options();

    try (SweepRunState state = new SweepRunState(options, 10)) {
      state.recordGenerated(10);
      state.recordGenerated(5);

      assertThat(state.generated).isEqualTo(10);
    }
  }

  @Test
  void progressReportsAreTriggeredByCheckedCases() throws Exception {
    SweepOptions options = options(0);
    ByteArrayOutputStream output = progressOutputAfterCheckedCases(options, 10, 10, 10);

    assertThat(output.toString(StandardCharsets.UTF_8))
        .contains("progress=100.0% elapsed=1m40s eta=0s total=10 checked=10 divergences=0");
  }

  @Test
  void progressReportsPercentageOfTotalChecks() throws Exception {
    SweepOptions options = options(0);
    ByteArrayOutputStream output = progressOutputAfterCheckedCases(options, 853, 853, 1_000);

    assertThat(output.toString(StandardCharsets.UTF_8))
        .contains("progress=85.3% elapsed=1m40s eta=17s total=1,000 checked=853 divergences=0");
  }

  @Test
  void progressReportsUseCurrentRunCheckedCountForNonzeroRanges() throws Exception {
    SweepOptions options = options(1_000_000);
    ByteArrayOutputStream output = progressOutputAfterCheckedCases(options, 10, 1_010_000, 10);

    assertThat(output.toString(StandardCharsets.UTF_8))
        .contains("progress=100.0% elapsed=1m40s eta=0s total=10 checked=10 divergences=0");
  }

  @Test
  void progressReporterCheckpointsLiveWorkerPosition() throws Exception {
    SweepOptions options = options();
    TestClock clock = new TestClock();

    try (SweepRunState state = new SweepRunState(options, 10, clock::nanoTime)) {
      state.enableCompactLogs("test", 10, List.of("UNKNOWN"), List.of(DivergenceStatus.UNKNOWN));
      SweepWorkers.ProgressReporter reporter = new SweepWorkers.ProgressReporter(state, 0);
      reporter.checked();
      reporter.reportIfNeeded(7);
      state.checkpointCompactLogs();
    }

    assertThat(Files.readString(tempDir.resolve("progress.json"))).contains("\"nextCaseIndex\":7");
  }

  private ByteArrayOutputStream progressOutputAfterCheckedCases(
      SweepOptions options, long checkedCases, long generated, long totalChecks) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    TestClock clock = new TestClock();

    try (SweepRunState state = new SweepRunState(options, totalChecks, clock::nanoTime)) {
      try {
        System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));

        state.reportProgressIfNeeded(generated);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();

        clock.advanceSeconds(100);
        for (long i = 0; i < checkedCases; i++) {
          state.checked.increment();
        }
        state.reportProgressIfNeeded(generated);
      } finally {
        System.setOut(originalOut);
      }
    }

    return output;
  }

  private SweepOptions options() {
    return options(0);
  }

  private SweepOptions options(long rangeStartInclusive) {
    return new SweepOptions(rangeStartInclusive, Long.MAX_VALUE, tempDir, 10, 1, null, "out.jsonl");
  }

  private static final class TestClock {
    private long nanos;

    long nanoTime() {
      return nanos;
    }

    void advanceSeconds(long seconds) {
      nanos += TimeUnit.SECONDS.toNanos(seconds);
    }
  }
}
