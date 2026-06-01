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
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/** Accumulates classified exhaustive-sweep divergences and writes shared report files. */
final class ClassifiedDivergenceSummary<C extends Enum<C> & DivergenceClassification> {
  private final Class<C> classificationType;
  private final C unknownClass;
  private final String outputPrefix;
  private final int firstUnknownLimit;
  private final int actionableSampleLimit;
  private final EnumMap<C, LongAdder> counts;
  private final List<DivergenceExample> firstUnknownExamples = new ArrayList<>();
  private final List<DivergenceExample> actionableExamples = new ArrayList<>();

  ClassifiedDivergenceSummary(
      Class<C> classificationType,
      C unknownClass,
      String outputPrefix,
      int firstUnknownLimit,
      int actionableSampleLimit) {
    this.classificationType = classificationType;
    this.unknownClass = unknownClass;
    this.outputPrefix = outputPrefix;
    this.firstUnknownLimit = firstUnknownLimit;
    this.actionableSampleLimit = actionableSampleLimit;
    this.counts = new EnumMap<>(classificationType);
    for (C classification : classificationType.getEnumConstants()) {
      counts.put(classification, new LongAdder());
    }
  }

  void record(C classification, long caseIndex, String json) {
    counts.get(classification).increment();
    if (json == null) {
      return;
    }
    if (classification == unknownClass) {
      recordUnknown(json, caseIndex);
    } else if (classification.actionable()) {
      recordActionable(json, caseIndex);
    }
  }

  void merge(ClassifiedDivergenceSummary<C> other) {
    if (other == null) {
      return;
    }
    for (C classification : classificationType.getEnumConstants()) {
      counts.get(classification).add(other.count(classification));
    }
    firstUnknownExamples.addAll(other.firstUnknownExamples);
    firstUnknownExamples.sort(Comparator.comparingLong(DivergenceExample::caseIndex));
    truncate(firstUnknownExamples, firstUnknownLimit);

    actionableExamples.addAll(other.actionableExamples);
    actionableExamples.sort(Comparator.comparingLong(DivergenceExample::caseIndex));
    truncate(actionableExamples, actionableSampleLimit);
  }

  long count(C classification) {
    return counts.get(classification).sum();
  }

  long actionableCount() {
    long total = 0;
    for (C classification : classificationType.getEnumConstants()) {
      if (classification.actionable()) {
        total += count(classification);
      }
    }
    return total;
  }

  void writeReports(Path outputDir) throws IOException {
    writeClassCounts(outputDir.resolve(outputPrefix + "-class-counts.tsv"));
    writeExamples(outputDir.resolve(outputPrefix + "-unknown-first.jsonl"), firstUnknownExamples);
    writeExamples(
        outputDir.resolve(outputPrefix + "-actionable-examples.jsonl"), actionableExamples);
  }

  private void recordUnknown(String json, long caseIndex) {
    recordExample(firstUnknownExamples, new DivergenceExample(caseIndex, json), firstUnknownLimit);
  }

  private void recordActionable(String json, long caseIndex) {
    recordExample(
        actionableExamples, new DivergenceExample(caseIndex, json), actionableSampleLimit);
  }

  private static void recordExample(
      List<DivergenceExample> examples, DivergenceExample example, int limit) {
    synchronized (examples) {
      if (examples.size() < limit) {
        examples.add(example);
      }
    }
  }

  private void writeClassCounts(Path path) throws IOException {
    try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      writer.write("class\tstatus\tcount\trationale");
      writer.newLine();
      for (C classification : classificationType.getEnumConstants()) {
        writer.write(classification.name());
        writer.write('\t');
        writer.write(classification.status().name());
        writer.write('\t');
        writer.write(Long.toString(count(classification)));
        writer.write('\t');
        writer.write(classification.rationale());
        writer.newLine();
      }
    }
  }

  private static void writeExamples(Path path, List<DivergenceExample> examples)
      throws IOException {
    try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      for (DivergenceExample example : examples) {
        writer.write(example.json());
        writer.newLine();
      }
    }
  }

  private static void truncate(List<?> list, int limit) {
    while (list.size() > limit) {
      list.remove(list.size() - 1);
    }
  }

  private record DivergenceExample(long caseIndex, String json) {}
}
