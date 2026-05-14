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

/** Smoke tests for the exhaustive sweep command-line entry points. */
class SweepCliSmokeTest {
  @TempDir Path tempDir;

  @Test
  void characterClassSweepRunsTinyRange() throws Exception {
    Path outputDir = tempDir.resolve("character");

    CharacterClassDivergenceSweep.main(args(outputDir));

    assertThat(Files.exists(outputDir.resolve("character-class-divergences.jsonl"))).isTrue();
  }

  @Test
  void graphemeClusterSweepRunsTinyRange() throws Exception {
    Path outputDir = tempDir.resolve("grapheme");

    GraphemeClusterDivergenceSweep.main(args(outputDir));

    assertThat(Files.exists(outputDir.resolve("grapheme-cluster-divergences.jsonl"))).isTrue();
  }

  @Test
  void controlEscapeSweepRunsTinyRange() throws Exception {
    Path outputDir = tempDir.resolve("control");

    ControlEscapeDivergenceSweep.main(args(outputDir));

    assertThat(Files.exists(outputDir.resolve("control-escape-divergences.jsonl"))).isTrue();
  }

  @Test
  void characterClassReplayUsesConfiguredThreads() throws Exception {
    Path replayFile = tempDir.resolve("character-replay.jsonl");
    Path outputDir = tempDir.resolve("character-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"template":"replay","comments":false,"negated":false,"pieces":[{"label":"literalA","text":"a"}]}}
        """);

    String output =
        captureOutput(() -> CharacterClassDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("mode=replay", "checked=1", "generated=1", "threads=2");
    assertThat(output).doesNotContain("threads=1");
  }

  @Test
  void controlEscapeReplayUsesConfiguredThreads() throws Exception {
    Path replayFile = tempDir.resolve("control-replay.jsonl");
    Path outputDir = tempDir.resolve("control-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"target":65,"contextLabel":"bare","contextTemplate":"\\\\c%s","flagLabel":"none","flagPrefix":"","flags":0}}
        """);

    String output =
        captureOutput(() -> ControlEscapeDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("mode=replay", "checked=1", "generated=1", "threads=2");
    assertThat(output).doesNotContain("threads=1");
  }

  @Test
  void graphemeClusterReplayUsesConfiguredThreads() throws Exception {
    Path replayFile = tempDir.resolve("grapheme-replay.jsonl");
    Path outputDir = tempDir.resolve("grapheme-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"regexLabel":"literal","regex":"a","inputLabel":"literal","input":"a","region":"full","prefix":"","suffix":"","regionStart":0,"regionEnd":1,"bounds":"opaqueAnchoring","operation":"freshTrace"}}
        """);

    String output =
        captureOutput(() -> GraphemeClusterDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("mode=replay", "checked=1", "generated=1", "threads=2");
    assertThat(output).doesNotContain("threads=1");
  }

  private static String[] args(Path outputDir) {
    return new String[] {"--range=:10", "--threads=1", "--output-dir=" + outputDir};
  }

  private static String[] replayArgs(Path outputDir, Path replayFile) {
    return new String[] {"--threads=2", "--output-dir=" + outputDir, "--replay-file=" + replayFile};
  }

  private static String captureOutput(ThrowingRunnable runnable) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    try {
      System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
      runnable.run();
    } finally {
      System.setOut(originalOut);
    }
    return output.toString(StandardCharsets.UTF_8);
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
