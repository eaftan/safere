// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Expands compact indexed-sweep divergence logs into replayable JSONL reports. */
public final class CompactDivergenceExpander {
  private static final int DEFAULT_KNOWN_INTENTIONAL_AUDIT_SAMPLE_LIMIT = 1000;

  private CompactDivergenceExpander() {}

  public static void main(String[] args) throws IOException {
    Options options = Options.parse(args);
    CompactDivergenceLogs.Manifest manifest =
        CompactDivergenceLogs.readManifest(options.inputDir());
    SweepDefinition sweep = sweepDefinition(manifest.sweep());
    validateCompatibleSweep(manifest, sweep);
    Map<String, Reservoir> reservoirs = new LinkedHashMap<>();
    for (int i = 0; i < manifest.classifications().size(); i++) {
      String classification = manifest.classifications().get(i);
      reservoirs.put(
          classification,
          new Reservoir(
              reservoirLimit(options, manifest.classificationStatuses().get(i)),
              classification.hashCode()));
    }
    Path expandedDir = options.inputDir().resolve("expanded");
    Files.createDirectories(expandedDir);
    deleteExampleFiles(expandedDir, manifest);
    if (options.sampleLimit() == null) {
      expandSelected(
          options.inputDir(),
          expandedDir,
          manifest,
          sweep,
          reservoirs,
          status -> status != DivergenceStatus.KNOWN_INTENTIONAL);
    } else if (options.sampleLimit() < 0) {
      expandSelected(options.inputDir(), expandedDir, manifest, sweep, reservoirs, unused -> true);
    } else {
      expandSampled(options.inputDir(), expandedDir, manifest, sweep, reservoirs);
    }
    System.out.println("inputDir=" + options.inputDir());
    System.out.println("expandedDir=" + expandedDir);
    System.out.println("sampleLimit=" + options.displaySampleLimit());
    for (var entry : reservoirs.entrySet()) {
      System.out.println(entry.getKey() + "=" + entry.getValue().count());
    }
  }

  private static int reservoirLimit(Options options, DivergenceStatus status) {
    if (options.sampleLimit() != null) {
      return options.sampleLimit();
    }
    if (status == DivergenceStatus.KNOWN_INTENTIONAL) {
      return DEFAULT_KNOWN_INTENTIONAL_AUDIT_SAMPLE_LIMIT;
    }
    return -1;
  }

  private static void expandSampled(
      Path inputDir,
      Path expandedDir,
      CompactDivergenceLogs.Manifest manifest,
      SweepDefinition sweep,
      Map<String, Reservoir> reservoirs)
      throws IOException {
    CompactDivergenceLogs.readRecords(
        inputDir,
        manifest,
        record -> {
          String classification = classification(manifest, record.classificationId());
          reservoirs.get(classification).record(record.caseIndex());
        });

    writeCounts(expandedDir.resolve("class-counts.tsv"), reservoirs);
    for (var entry : reservoirs.entrySet()) {
      writeExamples(
          expandedDir.resolve(fileName(entry.getKey()) + "-sample.jsonl"),
          entry.getKey(),
          entry.getValue().samples(),
          sweep.exampleWriter());
    }
  }

  private static void expandSelected(
      Path inputDir,
      Path expandedDir,
      CompactDivergenceLogs.Manifest manifest,
      SweepDefinition sweep,
      Map<String, Reservoir> reservoirs,
      StatusPredicate shouldExpand)
      throws IOException {
    Map<String, Path> indexPaths = new LinkedHashMap<>();
    Map<String, DataOutputStream> indexOutputs = new LinkedHashMap<>();
    try {
      try {
        for (int i = 0; i < manifest.classifications().size(); i++) {
          String classification = manifest.classifications().get(i);
          if (!shouldExpand.test(manifest.classificationStatuses().get(i))) {
            continue;
          }
          Path path = expandedDir.resolve("." + fileName(classification) + "-indices.tmp");
          indexPaths.put(classification, path);
          indexOutputs.put(
              classification,
              new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path))));
        }
        CompactDivergenceLogs.readRecordsSorted(
            inputDir,
            manifest,
            record -> {
              String classification = classification(manifest, record.classificationId());
              reservoirs.get(classification).record(record.caseIndex());
              DataOutputStream output = indexOutputs.get(classification);
              if (output != null) {
                output.writeLong(record.caseIndex());
              }
            });
      } finally {
        IOException failure = null;
        for (DataOutputStream output : indexOutputs.values()) {
          try {
            output.close();
          } catch (IOException e) {
            if (failure == null) {
              failure = e;
            } else {
              failure.addSuppressed(e);
            }
          }
        }
        if (failure != null) {
          throw failure;
        }
      }
      for (String classification : indexPaths.keySet()) {
        try (BufferedWriter writer =
            Files.newBufferedWriter(
                expandedDir.resolve(fileName(classification) + "-sample.jsonl"),
                StandardCharsets.UTF_8)) {
          sweep.allExampleWriter().write(writer, classification, indexPaths.get(classification));
        }
      }
      for (int i = 0; i < manifest.classifications().size(); i++) {
        if (manifest.classificationStatuses().get(i) != DivergenceStatus.KNOWN_INTENTIONAL) {
          continue;
        }
        String classification = manifest.classifications().get(i);
        writeExamples(
            expandedDir.resolve(fileName(classification) + "-sample.jsonl"),
            classification,
            reservoirs.get(classification).samples(),
            sweep.exampleWriter());
      }
      writeCounts(expandedDir.resolve("class-counts.tsv"), reservoirs);
    } finally {
      for (Path path : indexPaths.values()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static String classification(
      CompactDivergenceLogs.Manifest manifest, int classificationId) {
    if (classificationId < 0 || classificationId >= manifest.classifications().size()) {
      throw new IllegalArgumentException("invalid classification id in compact log");
    }
    return manifest.classifications().get(classificationId);
  }

  private static void validateCompatibleSweep(
      CompactDivergenceLogs.Manifest manifest, SweepDefinition sweep) {
    // This is a best-effort guard against stale indices, not a proof that ordering is unchanged.
    if (manifest.totalCases() != sweep.totalCases()) {
      throw new IllegalArgumentException(
          "compact log case count does not match current sweep: archive="
              + manifest.totalCases()
              + " current="
              + sweep.totalCases());
    }
  }

  private static SweepDefinition sweepDefinition(String sweep) {
    return switch (sweep) {
      case "zero-width-quantifier" ->
          sweepDefinition(
              ZeroWidthQuantifierDivergenceSweep.totalCases(),
              ZeroWidthQuantifierDivergenceSweep::compactReplayJson);
      case "grapheme-cluster" ->
          sweepDefinition(
              GraphemeClusterDivergenceSweep.totalCases(),
              GraphemeClusterDivergenceSweep::compactReplayJson);
      case "character-class" ->
          new SweepDefinition(
              CharacterClassDivergenceSweep.totalCases(),
              CharacterClassDivergenceSweep::compactReplayJson,
              CharacterClassDivergenceSweep::writeCompactReplayJson,
              CharacterClassDivergenceSweep::writeAllCompactReplayJson);
      case "control-escape" ->
          sweepDefinition(
              ControlEscapeDivergenceSweep.totalCases(),
              ControlEscapeDivergenceSweep::compactReplayJson);
      case "unicode-character-class" ->
          sweepDefinition(
              UnicodeCharacterClassDivergenceSweep.totalCases(),
              UnicodeCharacterClassDivergenceSweep::compactReplayJson);
      case "region-scalar" ->
          sweepDefinition(
              RegionScalarDivergenceSweep.totalCases(),
              RegionScalarDivergenceSweep::compactReplayJson);
      case "region-zero-width" ->
          sweepDefinition(
              RegionZeroWidthDivergenceSweep.totalCases(),
              RegionZeroWidthDivergenceSweep::compactReplayJson);
      case "dfa-sandwich-leftmost-start" ->
          sweepDefinition(
              DfaSandwichLeftmostStartDivergenceSweep.totalCases(),
              DfaSandwichLeftmostStartDivergenceSweep::compactReplayJson);
      case "case-folding-character-class" ->
          sweepDefinition(
              CaseFoldingCharacterClassDivergenceSweep.totalCases(),
              CaseFoldingCharacterClassDivergenceSweep::compactReplayJson);
      default -> throw new IllegalArgumentException("unsupported indexed sweep: " + sweep);
    };
  }

  private static SweepDefinition sweepDefinition(long totalCases, CaseJson caseJson) {
    return new SweepDefinition(
        totalCases,
        caseJson,
        (writer, classification, indices) -> {
          for (long caseIndex : indices) {
            writer.write(caseJson.toJson(caseIndex, classification));
            writer.newLine();
          }
        },
        (writer, classification, indicesPath) -> {
          try (DataInputStream input =
              new DataInputStream(new BufferedInputStream(Files.newInputStream(indicesPath)))) {
            while (true) {
              try {
                writer.write(caseJson.toJson(input.readLong(), classification));
                writer.newLine();
              } catch (EOFException e) {
                return;
              }
            }
          }
        });
  }

  private static void writeCounts(Path path, Map<String, Reservoir> reservoirs) throws IOException {
    try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      writer.write("classification\tcount");
      writer.newLine();
      for (var entry : reservoirs.entrySet()) {
        writer.write(entry.getKey());
        writer.write('\t');
        writer.write(Long.toString(entry.getValue().count()));
        writer.newLine();
      }
    }
  }

  private static void writeExamples(
      Path path, String classification, List<Long> indices, ExampleWriter exampleWriter)
      throws IOException {
    indices.sort(Long::compare);
    try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      exampleWriter.write(writer, classification, indices);
    }
  }

  private static String fileName(String classification) {
    return classification.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
  }

  private static void deleteExampleFiles(Path expandedDir, CompactDivergenceLogs.Manifest manifest)
      throws IOException {
    for (String classification : manifest.classifications()) {
      Files.deleteIfExists(expandedDir.resolve(fileName(classification) + "-sample.jsonl"));
    }
  }

  private record Options(Path inputDir, Integer sampleLimit) {
    static Options parse(String[] args) {
      Path inputDir = null;
      Integer sampleLimit = null;
      for (String arg : args) {
        if (arg.startsWith("--input-dir=")) {
          inputDir = Path.of(arg.substring("--input-dir=".length()));
        } else if (arg.startsWith("--sample-limit=")) {
          String value = arg.substring("--sample-limit=".length());
          sampleLimit = value.equals("all") ? -1 : Integer.parseInt(value);
        } else {
          throw new IllegalArgumentException("unknown argument: " + arg);
        }
      }
      if (inputDir == null) {
        throw new IllegalArgumentException("--input-dir is required");
      }
      if (sampleLimit != null && sampleLimit < -1) {
        throw new IllegalArgumentException("--sample-limit must be non-negative or all");
      }
      return new Options(inputDir, sampleLimit);
    }

    String displaySampleLimit() {
      if (sampleLimit == null) {
        return "default-known-intentional-audit-" + DEFAULT_KNOWN_INTENTIONAL_AUDIT_SAMPLE_LIMIT;
      }
      return sampleLimit < 0 ? "all" : sampleLimit.toString();
    }
  }

  private static final class Reservoir {
    private final int limit;
    private final Random random;
    private final List<Long> samples = new ArrayList<>();
    private long count;

    Reservoir(int limit, int seed) {
      this.limit = limit;
      this.random = new Random(seed);
    }

    void record(long caseIndex) {
      count++;
      if (limit < 0) {
        return;
      }
      if (samples.size() < limit) {
        samples.add(caseIndex);
        return;
      }
      long replacement = random.nextLong(count);
      if (replacement < limit) {
        samples.set((int) replacement, caseIndex);
      }
    }

    long count() {
      return count;
    }

    List<Long> samples() {
      return samples;
    }
  }

  interface CaseJson {
    String toJson(long caseIndex, String classification);
  }

  interface ExampleWriter {
    void write(BufferedWriter writer, String classification, List<Long> indices) throws IOException;
  }

  interface AllExampleWriter {
    void write(BufferedWriter writer, String classification, Path indicesPath) throws IOException;
  }

  interface StatusPredicate {
    boolean test(DivergenceStatus status);
  }

  private record SweepDefinition(
      long totalCases,
      CaseJson caseJson,
      ExampleWriter exampleWriter,
      AllExampleWriter allExampleWriter) {}
}
