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
    assertThat(Files.exists(outputDir.resolve("grapheme-cluster-class-counts.tsv"))).isTrue();
    assertThat(Files.exists(outputDir.resolve("grapheme-cluster-unknown-stratified.jsonl")))
        .isTrue();
  }

  @Test
  void controlEscapeSweepRunsTinyRange() throws Exception {
    Path outputDir = tempDir.resolve("control");

    ControlEscapeDivergenceSweep.main(args(outputDir));

    assertThat(Files.exists(outputDir.resolve("control-escape-divergences.jsonl"))).isTrue();
  }

  @Test
  void unicodeCharacterClassSweepRunsTinyRange() throws Exception {
    Path outputDir = tempDir.resolve("unicode-character");

    UnicodeCharacterClassDivergenceSweep.main(args(outputDir));

    assertThat(Files.exists(outputDir.resolve("unicode-character-class-divergences.jsonl")))
        .isTrue();
  }

  @Test
  void caseFoldingCharacterClassSweepRunsTinyRange() throws Exception {
    Path outputDir = tempDir.resolve("case-folding");

    CaseFoldingCharacterClassDivergenceSweep.main(args(outputDir));

    assertThat(Files.exists(outputDir.resolve("case-folding-character-class-divergences.jsonl")))
        .isTrue();
  }

  @Test
  void zeroWidthQuantifierSweepRunsTinyRange() throws Exception {
    Path outputDir = tempDir.resolve("zero-width");

    ZeroWidthQuantifierDivergenceSweep.main(args(outputDir));

    assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-divergences.jsonl"))).isTrue();
  }

  @Test
  void zeroWidthQuantifierSweepIncludesRepeatedQuantifierRegressions() {
    assertThat(
            ZeroWidthQuantifierDivergenceSweep.containsAllGeneratedRegexesForTesting(
                java.util.List.of(
                    "^*+",
                    "^?+",
                    "^{2}+",
                    "$*+",
                    "()*+",
                    "(?:^|$)*+",
                    "(?x)^ * +",
                    "^*??",
                    "^*{1}+",
                    "\\b{g}{0,2}++")))
        .isTrue();
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
  void unicodeCharacterClassReplayUsesConfiguredThreads() throws Exception {
    Path replayFile = tempDir.resolve("unicode-character-replay.jsonl");
    Path outputDir = tempDir.resolve("unicode-character-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"label":"word","regex":"\\\\w","codePoint":65}}
        """);

    String output =
        captureOutput(
            () -> UnicodeCharacterClassDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("mode=replay", "checked=1", "generated=1", "threads=2");
    assertThat(output).doesNotContain("threads=1");
  }

  @Test
  void caseFoldingCharacterClassReplayUsesConfiguredThreads() throws Exception {
    Path replayFile = tempDir.resolve("case-folding-replay.jsonl");
    Path outputDir = tempDir.resolve("case-folding-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"patternLabel":"rangeLowerHJ","regex":"[h-j]","inputRepeat":1,"flagLabel":"unicodeCase","flags":66,"inputCodePoint":104}}
        """);

    String output =
        captureOutput(
            () -> CaseFoldingCharacterClassDivergenceSweep.main(replayArgs(outputDir, replayFile)));

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

  @Test
  void zeroWidthQuantifierReplayUsesConfiguredThreads() throws Exception {
    Path replayFile = tempDir.resolve("zero-width-replay.jsonl");
    Path outputDir = tempDir.resolve("zero-width-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"operandLabel":"beginLine","operandRegex":"^","wrapperLabel":"bare","wrapperTemplate":"%s","firstQuantifierLabel":"plus","firstQuantifier":"+","suffixQuantifierLabel":"plus","suffixQuantifier":"+","contextLabel":"bare","contextTemplate":"%s","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        """);

    String output =
        captureOutput(
            () -> ZeroWidthQuantifierDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("mode=replay", "checked=1", "generated=1", "threads=2");
    assertThat(output).doesNotContain("threads=1");
  }

  @Test
  void graphemeClusterReplaySuppressesKnownIntentionalDivergences() throws Exception {
    Path replayFile = tempDir.resolve("grapheme-known-replay.jsonl");
    Path outputDir = tempDir.resolve("grapheme-known-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"regexLabel":"boundaryClusterBoundary","regex":"\\\\b{g}\\\\X\\\\b{g}","inputLabel":"twoAscii","input":"ab","region":"full","prefix":"","suffix":"","regionStart":0,"regionEnd":2,"bounds":"opaqueAnchoring","operation":"freshTrace"}}
        {"case":{"regexLabel":"boundaryClusterAlternativeAfterLiteral","regex":"b|\\\\b{g}\\\\X","inputLabel":"crlfTrace","input":"\\r\\r\\n\\r","region":"wrapped","prefix":"#","suffix":"$","regionStart":1,"regionEnd":5,"bounds":"opaqueAnchoring","operation":"freshTrace"}}
        {"case":{"regexLabel":"boundaryClusterAlternativeAfterLiteral","regex":"b|\\\\b{g}\\\\X","inputLabel":"transparentPrependTrace","input":"\\r؀a","region":"wrapped","prefix":"#","suffix":"$","regionStart":1,"regionEnd":4,"bounds":"transparentAnchoring","operation":"freshTrace"}}
        """);

    String output =
        captureOutput(() -> GraphemeClusterDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("divergences=3", "actionableDivergences=0", "unknownDivergences=0");
    assertThat(Files.size(outputDir.resolve("grapheme-cluster-divergences.jsonl"))).isZero();
    assertThat(Files.readString(outputDir.resolve("grapheme-cluster-class-counts.tsv")))
        .contains(
            "BOUNDARY_CLUSTER_BOUNDARY_COMPOSITION\tKNOWN_INTENTIONAL\t1",
            "BOUNDARY_CLUSTER_ALTERNATIVE_FIND_CURSOR\tKNOWN_INTENTIONAL\t2");
  }

  @Test
  void graphemeClusterReplaySamplesUnknownDivergencesWithoutRawOutput() throws Exception {
    Path replayFile = tempDir.resolve("grapheme-unknown-replay.jsonl");
    Path outputDir = tempDir.resolve("grapheme-unknown-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"regexLabel":"unclassifiedGraphemeAlternative","regex":"b|\\\\b{g}\\\\X","inputLabel":"crlfTrace","input":"\\r\\r\\n\\r","region":"wrapped","prefix":"#","suffix":"$","regionStart":1,"regionEnd":5,"bounds":"opaqueAnchoring","operation":"freshTrace"}}
        """);

    String output =
        captureOutput(() -> GraphemeClusterDivergenceSweep.main(replayArgs(outputDir, replayFile)));

    assertThat(output).contains("divergences=1", "actionableDivergences=0", "unknownDivergences=1");
    assertThat(Files.size(outputDir.resolve("grapheme-cluster-divergences.jsonl"))).isZero();
    assertThat(Files.readString(outputDir.resolve("grapheme-cluster-unknown-first.jsonl")))
        .contains("\"classification\":\"UNKNOWN\"");
    assertThat(Files.readString(outputDir.resolve("grapheme-cluster-unknown-stratified.jsonl")))
        .contains("\"classification\":\"UNKNOWN\"");
  }

  @Test
  void graphemeClusterReplayHonorsUnknownStratifiedSampleLimit() throws Exception {
    Path replayFile = tempDir.resolve("grapheme-unknown-limit-replay.jsonl");
    Path outputDir = tempDir.resolve("grapheme-unknown-limit-replay");
    Files.writeString(
        replayFile,
        """
        {"case":{"regexLabel":"unclassifiedGraphemeAlternative","regex":"b|\\\\b{g}\\\\X","inputLabel":"crlfTrace0","input":"\\r\\r\\n\\r","region":"wrapped","prefix":"#","suffix":"$","regionStart":1,"regionEnd":5,"bounds":"opaqueAnchoring","operation":"freshTrace"}}
        {"case":{"regexLabel":"unclassifiedGraphemeAlternative","regex":"b|\\\\b{g}\\\\X","inputLabel":"crlfTrace1","input":"\\r\\r\\n\\r","region":"wrapped","prefix":"#","suffix":"$","regionStart":1,"regionEnd":5,"bounds":"opaqueAnchoring","operation":"freshTrace"}}
        {"case":{"regexLabel":"unclassifiedGraphemeAlternative","regex":"b|\\\\b{g}\\\\X","inputLabel":"crlfTrace2","input":"\\r\\r\\n\\r","region":"wrapped","prefix":"#","suffix":"$","regionStart":1,"regionEnd":5,"bounds":"opaqueAnchoring","operation":"freshTrace"}}
        """);

    String output =
        captureOutput(
            () ->
                GraphemeClusterDivergenceSweep.main(
                    replayArgs(outputDir, replayFile, "--unknown-stratified-samples=2")));

    assertThat(output)
        .contains("unknownStratifiedSamples=2", "unknownDivergences=3", "actionableDivergences=0");
    assertThat(Files.readAllLines(outputDir.resolve("grapheme-cluster-unknown-stratified.jsonl")))
        .hasSize(2);
  }

  private static String[] args(Path outputDir) {
    return new String[] {"--range=:10", "--threads=1", "--output-dir=" + outputDir};
  }

  private static String[] replayArgs(Path outputDir, Path replayFile) {
    return replayArgs(outputDir, replayFile, new String[0]);
  }

  private static String[] replayArgs(Path outputDir, Path replayFile, String... extraArgs) {
    String[] args = new String[3 + extraArgs.length];
    args[0] = "--threads=2";
    args[1] = "--output-dir=" + outputDir;
    args[2] = "--replay-file=" + replayFile;
    System.arraycopy(extraArgs, 0, args, 3, extraArgs.length);
    return args;
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
