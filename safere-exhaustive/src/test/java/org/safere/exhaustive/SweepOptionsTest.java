// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link SweepOptions}. */
class SweepOptionsTest {
  @TempDir Path tempDir;

  @Test
  void defaultsUseRuntimeProcessorsAndSweepDefaults() {
    SweepOptions options =
        SweepOptions.parse(new String[0], tempDir.resolve("out"), "divergences.jsonl", 123);

    assertThat(options.rangeStartInclusive()).isZero();
    assertThat(options.rangeEndExclusive()).isEqualTo(Long.MAX_VALUE);
    assertThat(options.outputDir()).isEqualTo(tempDir.resolve("out"));
    assertThat(options.progressInterval()).isEqualTo(123);
    assertThat(options.threads()).isEqualTo(Runtime.getRuntime().availableProcessors());
    assertThat(options.replayFile()).isNull();
    assertThat(options.jsonlPath()).isEqualTo(tempDir.resolve("out").resolve("divergences.jsonl"));
  }

  @Test
  void parsesAllSupportedOptions() throws Exception {
    Path replayFile = tempDir.resolve("replay.jsonl");
    Files.writeString(replayFile, "{}\n");

    SweepOptions options =
        SweepOptions.parse(
            new String[] {
              "--range=5:10",
              "--output-dir=" + tempDir.resolve("custom"),
              "--progress-interval=99",
              "--threads=3",
              "--replay-file=" + replayFile
            },
            tempDir.resolve("out"),
            "divergences.jsonl",
            123);

    assertThat(options.rangeStartInclusive()).isEqualTo(5);
    assertThat(options.rangeEndExclusive()).isEqualTo(10);
    assertThat(options.outputDir()).isEqualTo(tempDir.resolve("custom"));
    assertThat(options.progressInterval()).isEqualTo(99);
    assertThat(options.threads()).isEqualTo(3);
    assertThat(options.replayFile()).isEqualTo(replayFile);
  }

  @Test
  void parsesOpenEndedRange() {
    SweepOptions options =
        SweepOptions.parse(
            new String[] {"--range=5:"}, tempDir.resolve("out"), "divergences.jsonl", 123);

    assertThat(options.rangeStartInclusive()).isEqualTo(5);
    assertThat(options.rangeEndExclusive()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void rejectsInvalidOptions() {
    assertThatThrownBy(
            () ->
                SweepOptions.parse(
                    new String[] {"--range=10:5"},
                    tempDir.resolve("out"),
                    "divergences.jsonl",
                    123))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--range end");

    assertThatThrownBy(
            () ->
                SweepOptions.parse(
                    new String[] {"--threads=0"}, tempDir.resolve("out"), "divergences.jsonl", 123))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--threads");

    assertThatThrownBy(
            () ->
                SweepOptions.parse(
                    new String[] {"--replay-file=" + tempDir.resolve("missing.jsonl")},
                    tempDir.resolve("out"),
                    "divergences.jsonl",
                    123))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--replay-file");
  }
}
