// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Replays sampled compact known-divergence buckets through the current sweep classifier. */
public final class CompactDivergenceAudit {
  private static final int DEFAULT_SAMPLE_LIMIT = 1000;
  private static final String NO_DIVERGENCE = "NO_DIVERGENCE";
  private static final String NO_DIVERGENCE_STATUS = "NO_DIVERGENCE";

  private CompactDivergenceAudit() {}

  public static void main(String[] args) throws IOException {
    Options options = Options.parse(args);
    CompactDivergenceLogs.Manifest manifest =
        CompactDivergenceLogs.readManifest(options.inputDir());
    AuditSweep sweep = auditSweep(manifest.sweep());
    validateCompatibleSweep(manifest, sweep);

    Map<String, SourceSamples> sourceSamples = sampleSourceBuckets(options, manifest);
    Map<String, Map<String, Long>> transitions = new LinkedHashMap<>();
    for (var entry : sourceSamples.entrySet()) {
      String sourceClass = entry.getKey();
      Map<String, Long> replayCounts = new LinkedHashMap<>();
      transitions.put(sourceClass, replayCounts);
      for (long caseIndex : entry.getValue().samples()) {
        AuditClassification replayClass = sweep.classify(caseIndex);
        replayCounts.merge(replayClass.name(), 1L, Long::sum);
      }
    }

    Path outputDir = options.outputDir();
    Files.createDirectories(outputDir);
    writeSourceCounts(outputDir.resolve("source-counts.tsv"), sourceSamples);
    writeTransitions(outputDir.resolve("transition-counts.tsv"), manifest, sweep, transitions);

    System.out.println("inputDir=" + options.inputDir());
    System.out.println("outputDir=" + outputDir);
    System.out.println("sampleLimit=" + options.sampleLimit());
    for (var entry : sourceSamples.entrySet()) {
      System.out.println(entry.getKey() + "=" + entry.getValue().count());
    }
  }

  private static Map<String, SourceSamples> sampleSourceBuckets(
      Options options, CompactDivergenceLogs.Manifest manifest) throws IOException {
    Map<String, SourceSamples> samples = new LinkedHashMap<>();
    for (int i = 0; i < manifest.classifications().size(); i++) {
      if (manifest.classificationStatuses().get(i) != DivergenceStatus.KNOWN_INTENTIONAL) {
        continue;
      }
      String classification = manifest.classifications().get(i);
      samples.put(
          classification, new SourceSamples(options.sampleLimit(), classification.hashCode()));
    }
    CompactDivergenceLogs.readRecords(
        options.inputDir(),
        manifest,
        record -> {
          String classification = manifest.classifications().get(record.classificationId());
          SourceSamples sourceSamples = samples.get(classification);
          if (sourceSamples != null) {
            sourceSamples.record(record.caseIndex());
          }
        });
    return samples;
  }

  private static void writeSourceCounts(Path path, Map<String, SourceSamples> sourceSamples)
      throws IOException {
    try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      writer.write("sourceClass\tsourceCount\tsampled");
      writer.newLine();
      for (var entry : sourceSamples.entrySet()) {
        writer.write(entry.getKey());
        writer.write('\t');
        writer.write(Long.toString(entry.getValue().count()));
        writer.write('\t');
        writer.write(Integer.toString(entry.getValue().samples().size()));
        writer.newLine();
      }
    }
  }

  private static void writeTransitions(
      Path path,
      CompactDivergenceLogs.Manifest manifest,
      AuditSweep sweep,
      Map<String, Map<String, Long>> transitions)
      throws IOException {
    try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      writer.write("sourceClass\tsourceStatus\treplayClass\treplayStatus\tcount");
      writer.newLine();
      for (var sourceEntry : transitions.entrySet()) {
        String sourceClass = sourceEntry.getKey();
        String sourceStatus = sourceStatus(manifest, sourceClass);
        for (var replayEntry : sourceEntry.getValue().entrySet()) {
          writer.write(sourceClass);
          writer.write('\t');
          writer.write(sourceStatus);
          writer.write('\t');
          writer.write(replayEntry.getKey());
          writer.write('\t');
          writer.write(replayStatus(sweep, replayEntry.getKey()));
          writer.write('\t');
          writer.write(Long.toString(replayEntry.getValue()));
          writer.newLine();
        }
      }
    }
  }

  private static String sourceStatus(CompactDivergenceLogs.Manifest manifest, String sourceClass) {
    int index = manifest.classifications().indexOf(sourceClass);
    if (index < 0) {
      throw new IllegalArgumentException("source class is not in manifest: " + sourceClass);
    }
    return manifest.classificationStatuses().get(index).name();
  }

  private static String replayStatus(AuditSweep sweep, String replayClass) {
    if (replayClass.equals(NO_DIVERGENCE)) {
      return NO_DIVERGENCE_STATUS;
    }
    DivergenceStatus status = sweep.statuses().get(replayClass);
    if (status == null) {
      throw new IllegalArgumentException("replay class is not in current sweep: " + replayClass);
    }
    return status.name();
  }

  private static void validateCompatibleSweep(
      CompactDivergenceLogs.Manifest manifest, AuditSweep sweep) {
    // This is a best-effort guard against stale indices, not a proof that ordering is unchanged.
    if (manifest.totalCases() != sweep.totalCases()) {
      throw new IllegalArgumentException(
          "compact log case count does not match current sweep: archive="
              + manifest.totalCases()
              + " current="
              + sweep.totalCases());
    }
  }

  private static AuditSweep auditSweep(String sweep) {
    return switch (sweep) {
      case "zero-width-quantifier" ->
          new AuditSweep(
              ZeroWidthQuantifierDivergenceSweep.totalCases(),
              ZeroWidthQuantifierDivergenceSweep.divergenceClassificationStatusMap(),
              caseIndex -> {
                String classification =
                    ZeroWidthQuantifierDivergenceSweep.auditClassificationName(caseIndex);
                if (classification == null) {
                  return new AuditClassification(NO_DIVERGENCE);
                }
                return new AuditClassification(classification);
              });
      default -> throw new IllegalArgumentException("unsupported compact audit sweep: " + sweep);
    };
  }

  private record Options(Path inputDir, Path outputDir, int sampleLimit) {
    static Options parse(String[] args) {
      Path inputDir = null;
      Path outputDir = null;
      int sampleLimit = DEFAULT_SAMPLE_LIMIT;
      for (String arg : args) {
        if (arg.startsWith("--input-dir=")) {
          inputDir = Path.of(arg.substring("--input-dir=".length()));
        } else if (arg.startsWith("--output-dir=")) {
          outputDir = Path.of(arg.substring("--output-dir=".length()));
        } else if (arg.startsWith("--sample-limit=")) {
          sampleLimit = Integer.parseInt(arg.substring("--sample-limit=".length()));
        } else {
          throw new IllegalArgumentException("unknown argument: " + arg);
        }
      }
      if (inputDir == null) {
        throw new IllegalArgumentException("--input-dir is required");
      }
      if (outputDir == null) {
        outputDir = inputDir.resolve("audit");
      }
      if (sampleLimit < 0) {
        throw new IllegalArgumentException("--sample-limit must be non-negative");
      }
      return new Options(inputDir, outputDir, sampleLimit);
    }
  }

  private static final class SourceSamples {
    private final int limit;
    private final Random random;
    private final List<Long> samples = new ArrayList<>();
    private long count;

    SourceSamples(int limit, int seed) {
      this.limit = limit;
      this.random = new Random(seed);
    }

    void record(long caseIndex) {
      count++;
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

  private record AuditClassification(String name) {}

  private interface AuditCaseClassifier {
    AuditClassification classify(long caseIndex);
  }

  private record AuditSweep(
      long totalCases, Map<String, DivergenceStatus> statuses, AuditCaseClassifier classifier) {
    AuditClassification classify(long caseIndex) {
      return classifier.classify(caseIndex);
    }
  }
}
