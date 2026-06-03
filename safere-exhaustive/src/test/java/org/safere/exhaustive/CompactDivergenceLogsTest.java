// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for durable compact indexed-sweep divergence logs. */
class CompactDivergenceLogsTest {
  @TempDir Path tempDir;

  @Test
  void writesFixedSizeRecordsAndAtomicProgress() throws Exception {
    try (CompactDivergenceLogs logs =
        new CompactDivergenceLogs(
            tempDir,
            "control-escape",
            0,
            100,
            100,
            2,
            List.of("UNKNOWN"),
            List.of(DivergenceStatus.UNKNOWN))) {
      logs.record(0, 12, 0);
      logs.record(1, 33, 0);
      logs.updateWorkerNextCaseIndex(0, 14);
      logs.updateWorkerNextCaseIndex(1, 35);
      logs.checkpoint(2);
    }

    assertThat(Files.size(tempDir.resolve("divergence-indices/worker-00.bin")))
        .isEqualTo(CompactDivergenceLogs.RECORD_SIZE);
    assertThat(Files.size(tempDir.resolve("divergence-indices/worker-01.bin")))
        .isEqualTo(CompactDivergenceLogs.RECORD_SIZE);
    assertThat(Files.readString(tempDir.resolve("progress.json")))
        .contains(
            "\"checked\":2",
            "\"divergences\":2",
            "\"UNKNOWN\":2",
            "\"nextCaseIndex\":14",
            "\"durableBytes\":9");
    assertThat(Files.exists(tempDir.resolve("progress.json.tmp"))).isFalse();
  }

  @Test
  void ignoresIncompleteTrailingRecord() throws Exception {
    try (CompactDivergenceLogs logs =
        new CompactDivergenceLogs(
            tempDir,
            "control-escape",
            0,
            100,
            ControlEscapeDivergenceSweep.totalCases(),
            1,
            List.of("UNKNOWN"),
            List.of(DivergenceStatus.UNKNOWN))) {
      logs.record(0, 12, 0);
    }
    Files.write(
        tempDir.resolve("divergence-indices/worker-00.bin"),
        new byte[] {1, 2, 3},
        StandardOpenOption.APPEND);

    List<CompactDivergenceLogs.Record> records = new ArrayList<>();
    CompactDivergenceLogs.readRecords(
        tempDir, CompactDivergenceLogs.readManifest(tempDir), records::add);

    assertThat(records).containsExactly(new CompactDivergenceLogs.Record(12, 0));
  }

  @Test
  void readsWorkerRecordsInCaseIndexOrder() throws Exception {
    try (CompactDivergenceLogs logs =
        new CompactDivergenceLogs(
            tempDir,
            "control-escape",
            0,
            100,
            100,
            2,
            List.of("UNKNOWN"),
            List.of(DivergenceStatus.UNKNOWN))) {
      logs.record(0, 0, 0);
      logs.record(0, 2, 0);
      logs.record(1, 1, 0);
      logs.record(1, 3, 0);
    }

    List<CompactDivergenceLogs.Record> records = new ArrayList<>();
    CompactDivergenceLogs.readRecordsSorted(
        tempDir, CompactDivergenceLogs.readManifest(tempDir), records::add);

    assertThat(records)
        .extracting(CompactDivergenceLogs.Record::caseIndex)
        .containsExactly(0L, 1L, 2L, 3L);
  }

  @Test
  void refusesToOverwriteExistingArchive() throws Exception {
    try (CompactDivergenceLogs logs =
        new CompactDivergenceLogs(
            tempDir,
            "control-escape",
            0,
            100,
            100,
            1,
            List.of("UNKNOWN"),
            List.of(DivergenceStatus.UNKNOWN))) {
      assertThat(logs.classifications()).containsExactly("UNKNOWN");
    }

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    new CompactDivergenceLogs(
                        tempDir,
                        "control-escape",
                        0,
                        100,
                        100,
                        1,
                        List.of("UNKNOWN"),
                        List.of(DivergenceStatus.UNKNOWN))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("output directory already contains a compact sweep archive");
  }

  @Test
  void expanderWritesExactCountsAndSampledReplayJson() throws Exception {
    try (CompactDivergenceLogs logs =
        new CompactDivergenceLogs(
            tempDir,
            "control-escape",
            0,
            100,
            ControlEscapeDivergenceSweep.totalCases(),
            1,
            List.of("UNKNOWN"),
            List.of(DivergenceStatus.UNKNOWN))) {
      logs.record(0, 0, 0);
      logs.record(0, 1, 0);
      logs.record(0, 2, 0);
    }

    CompactDivergenceExpander.main(new String[] {"--input-dir=" + tempDir, "--sample-limit=2"});

    assertThat(Files.readString(tempDir.resolve("expanded/class-counts.tsv")))
        .contains("UNKNOWN\t3");
    List<String> examples = Files.readAllLines(tempDir.resolve("expanded/unknown-sample.jsonl"));
    assertThat(examples).hasSize(2);
    assertThat(examples).allMatch(line -> line.contains("\"classification\":\"UNKNOWN\""));
    assertThat(examples).allMatch(line -> line.contains("\"case\""));
  }

  @Test
  void expanderRejectsStaleCaseCount() throws Exception {
    try (CompactDivergenceLogs logs =
        new CompactDivergenceLogs(
            tempDir,
            "control-escape",
            0,
            100,
            100,
            1,
            List.of("UNKNOWN"),
            List.of(DivergenceStatus.UNKNOWN))) {
      logs.record(0, 0, 0);
    }

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    CompactDivergenceExpander.main(
                        new String[] {"--input-dir=" + tempDir, "--sample-limit=2"})))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("compact log case count does not match current sweep");
  }

  @Test
  void expanderWritesAllReplayJsonWithoutSampling() throws Exception {
    try (CompactDivergenceLogs logs =
        new CompactDivergenceLogs(
            tempDir,
            "control-escape",
            0,
            100,
            ControlEscapeDivergenceSweep.totalCases(),
            1,
            List.of("UNKNOWN"),
            List.of(DivergenceStatus.UNKNOWN))) {
      logs.record(0, 0, 0);
      logs.record(0, 1, 0);
      logs.record(0, 2, 0);
    }

    CompactDivergenceExpander.main(new String[] {"--input-dir=" + tempDir, "--sample-limit=all"});

    assertThat(Files.readAllLines(tempDir.resolve("expanded/unknown-sample.jsonl"))).hasSize(3);
  }

  @Test
  void characterClassBatchExpansionReconstructsSortedIndices() throws Exception {
    StringWriter output = new StringWriter();
    try (BufferedWriter writer = new BufferedWriter(output)) {
      CharacterClassDivergenceSweep.writeCompactReplayJson(writer, "UNKNOWN", List.of(0L, 1L, 2L));
    }

    assertThat(output.toString().lines()).hasSize(3);
  }

  @Test
  void expanderWritesAllCharacterClassReplayJsonWithSingleGeneratorWalk() throws Exception {
    try (CompactDivergenceLogs logs =
        new CompactDivergenceLogs(
            tempDir,
            "character-class",
            0,
            100,
            CharacterClassDivergenceSweep.totalCases(),
            2,
            List.of("UNKNOWN"),
            List.of(DivergenceStatus.UNKNOWN))) {
      logs.record(0, 0, 0);
      logs.record(0, 2, 0);
      logs.record(1, 1, 0);
    }

    CompactDivergenceExpander.main(new String[] {"--input-dir=" + tempDir, "--sample-limit=all"});

    assertThat(Files.readAllLines(tempDir.resolve("expanded/unknown-sample.jsonl"))).hasSize(3);
  }

  @Test
  void expanderDefaultWritesUnknownExpectedZeroAndKnownIntentionalAuditSamples() throws Exception {
    try (CompactDivergenceLogs logs =
        new CompactDivergenceLogs(
            tempDir,
            "control-escape",
            0,
            100,
            ControlEscapeDivergenceSweep.totalCases(),
            1,
            List.of("KNOWN", "EXPECTED", "UNKNOWN"),
            List.of(
                DivergenceStatus.KNOWN_INTENTIONAL,
                DivergenceStatus.EXPECTED_ZERO,
                DivergenceStatus.UNKNOWN))) {
      logs.record(0, 0, 0);
      logs.record(0, 1, 1);
      logs.record(0, 2, 2);
    }

    CompactDivergenceExpander.main(new String[] {"--input-dir=" + tempDir});

    assertThat(Files.readString(tempDir.resolve("expanded/class-counts.tsv")))
        .contains("KNOWN\t1", "EXPECTED\t1", "UNKNOWN\t1");
    assertThat(Files.readAllLines(tempDir.resolve("expanded/known-sample.jsonl"))).hasSize(1);
    assertThat(Files.readAllLines(tempDir.resolve("expanded/expected-sample.jsonl"))).hasSize(1);
    assertThat(Files.readAllLines(tempDir.resolve("expanded/unknown-sample.jsonl"))).hasSize(1);

    CompactDivergenceExpander.main(new String[] {"--input-dir=" + tempDir, "--sample-limit=1"});

    assertThat(Files.readAllLines(tempDir.resolve("expanded/known-sample.jsonl"))).hasSize(1);

    CompactDivergenceExpander.main(new String[] {"--input-dir=" + tempDir});

    assertThat(Files.readAllLines(tempDir.resolve("expanded/known-sample.jsonl"))).hasSize(1);
  }

  @Test
  void auditSamplesKnownIntentionalBucketsAndReportsCurrentClassification() throws Exception {
    int sourceClass =
        ZeroWidthQuantifierDivergenceSweep.divergenceClassificationNames()
            .indexOf("POSSESSIVE_QUANTIFIER_UNSUPPORTED");
    try (CompactDivergenceLogs logs =
        new CompactDivergenceLogs(
            tempDir,
            "zero-width-quantifier",
            0,
            100,
            ZeroWidthQuantifierDivergenceSweep.totalCasesForTesting(),
            1,
            ZeroWidthQuantifierDivergenceSweep.divergenceClassificationNames(),
            ZeroWidthQuantifierDivergenceSweep.divergenceClassificationStatuses())) {
      logs.record(0, 0, sourceClass);
    }

    CompactDivergenceAudit.main(new String[] {"--input-dir=" + tempDir});

    assertThat(Files.readString(tempDir.resolve("audit/source-counts.tsv")))
        .contains("POSSESSIVE_QUANTIFIER_UNSUPPORTED\t1\t1");
    assertThat(Files.readString(tempDir.resolve("audit/transition-counts.tsv")))
        .contains(
            "POSSESSIVE_QUANTIFIER_UNSUPPORTED\tKNOWN_INTENTIONAL\tNO_DIVERGENCE\t"
                + "NO_DIVERGENCE\t1");
  }
}
