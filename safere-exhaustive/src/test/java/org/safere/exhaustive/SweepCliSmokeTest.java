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

  private static String[] args(Path outputDir) {
    return new String[] {"--range=:10", "--threads=1", "--output-dir=" + outputDir};
  }
}
