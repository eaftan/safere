// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Durable fixed-size logs for divergences found by deterministic indexed sweeps. */
final class CompactDivergenceLogs implements AutoCloseable {
  static final int FORMAT_VERSION = 2;
  static final int RECORD_SIZE = Long.BYTES + Byte.BYTES;

  private final Path outputDir;
  private final Path indicesDir;
  private final String sweepName;
  private final long rangeStartInclusive;
  private final long rangeEndExclusive;
  private final long totalCases;
  private final List<String> classifications;
  private final List<DivergenceStatus> classificationStatuses;
  private final WorkerLog[] workers;
  private boolean closed;

  CompactDivergenceLogs(
      Path outputDir,
      String sweepName,
      long rangeStartInclusive,
      long rangeEndExclusive,
      long totalCases,
      int threads,
      List<String> classifications,
      List<DivergenceStatus> classificationStatuses)
      throws IOException {
    this.outputDir = outputDir;
    this.indicesDir = outputDir.resolve("divergence-indices");
    this.sweepName = sweepName;
    this.rangeStartInclusive = rangeStartInclusive;
    this.rangeEndExclusive = rangeEndExclusive;
    this.totalCases = totalCases;
    this.classifications = List.copyOf(classifications);
    this.classificationStatuses = List.copyOf(classificationStatuses);
    if (classifications.size() != classificationStatuses.size()) {
      throw new IllegalArgumentException("classification names and statuses must have equal sizes");
    }
    if (Files.exists(outputDir.resolve("run-manifest.json"))
        || Files.exists(outputDir.resolve("progress.json"))
        || Files.exists(indicesDir)) {
      throw new IllegalArgumentException(
          "output directory already contains a compact sweep archive: " + outputDir);
    }
    Files.createDirectories(indicesDir);
    this.workers = new WorkerLog[threads];
    for (int i = 0; i < threads; i++) {
      workers[i] =
          new WorkerLog(
              indicesDir.resolve(String.format("worker-%02d.bin", i)), classifications.size());
    }
    writeManifest();
    checkpoint(0);
  }

  void record(int workerIndex, long caseIndex, int classificationId) {
    if (classificationId < 0 || classificationId >= classifications.size()) {
      throw new IllegalArgumentException("invalid classification id: " + classificationId);
    }
    try {
      workers[workerIndex].write(caseIndex, classificationId);
    } catch (IOException e) {
      throw new IllegalStateException("failed to write compact divergence record", e);
    }
  }

  void updateWorkerNextCaseIndex(int workerIndex, long nextCaseIndex) {
    workers[workerIndex].updateNextCaseIndex(nextCaseIndex);
  }

  synchronized void checkpoint(long checked) {
    if (closed) {
      return;
    }
    try {
      List<WorkerSnapshot> workerSnapshots = new ArrayList<>(workers.length);
      for (WorkerLog worker : workers) {
        workerSnapshots.add(worker.flushAndSnapshot());
      }
      var object = SweepJson.object();
      object.addProperty("formatVersion", FORMAT_VERSION);
      object.addProperty("sweep", sweepName);
      object.addProperty("checked", checked);
      long divergences = 0;
      for (WorkerSnapshot snapshot : workerSnapshots) {
        divergences += snapshot.durableBytes() / RECORD_SIZE;
      }
      object.addProperty("divergences", divergences);
      var classCounts = SweepJson.object();
      for (int i = 0; i < classifications.size(); i++) {
        long count = 0;
        for (WorkerSnapshot snapshot : workerSnapshots) {
          count += snapshot.classCounts()[i];
        }
        classCounts.addProperty(classifications.get(i), count);
      }
      object.add("classCounts", classCounts);
      var workerStates = new com.google.gson.JsonArray();
      for (int i = 0; i < workers.length; i++) {
        var workerObject = SweepJson.object();
        workerObject.addProperty("worker", i);
        workerObject.addProperty("nextCaseIndex", workerSnapshots.get(i).nextCaseIndex());
        workerObject.addProperty("durableBytes", workerSnapshots.get(i).durableBytes());
        workerStates.add(workerObject);
      }
      object.add("workers", workerStates);
      atomicWrite(outputDir.resolve("progress.json"), SweepJson.toJson(object));
    } catch (IOException e) {
      throw new IllegalStateException("failed to checkpoint compact divergence logs", e);
    }
  }

  List<String> classifications() {
    return classifications;
  }

  private void writeManifest() throws IOException {
    var object = SweepJson.object();
    object.addProperty("formatVersion", FORMAT_VERSION);
    object.addProperty("recordSize", RECORD_SIZE);
    object.addProperty("sweep", sweepName);
    object.addProperty("rangeStart", rangeStartInclusive);
    object.addProperty("rangeEnd", rangeEndExclusive);
    object.addProperty("totalCases", totalCases);
    object.addProperty("threads", workers.length);
    var classes = new com.google.gson.JsonArray();
    for (String classification : classifications) {
      classes.add(classification);
    }
    object.add("classifications", classes);
    var statuses = new com.google.gson.JsonArray();
    for (DivergenceStatus status : classificationStatuses) {
      statuses.add(status.name());
    }
    object.add("classificationStatuses", statuses);
    atomicWrite(outputDir.resolve("run-manifest.json"), SweepJson.toJson(object));
  }

  private static void atomicWrite(Path path, String value) throws IOException {
    Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
    Files.writeString(
        temporary,
        value + System.lineSeparator(),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
    Files.move(
        temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    IOException failure = null;
    for (WorkerLog worker : workers) {
      try {
        worker.close();
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

  static Manifest readManifest(Path inputDir) throws IOException {
    var object = SweepJson.parseObject(Files.readString(inputDir.resolve("run-manifest.json")));
    int formatVersion = SweepJson.integer(object, "formatVersion");
    int recordSize = SweepJson.integer(object, "recordSize");
    if (formatVersion != FORMAT_VERSION || recordSize != RECORD_SIZE) {
      throw new IllegalArgumentException("unsupported compact divergence log format");
    }
    List<String> classifications = new ArrayList<>();
    for (var element : SweepJson.array(object, "classifications")) {
      classifications.add(element.getAsString());
    }
    List<DivergenceStatus> classificationStatuses = new ArrayList<>();
    for (var element : SweepJson.array(object, "classificationStatuses")) {
      classificationStatuses.add(DivergenceStatus.valueOf(element.getAsString()));
    }
    if (classifications.size() != classificationStatuses.size()) {
      throw new IllegalArgumentException("classification names and statuses must have equal sizes");
    }
    return new Manifest(
        formatVersion,
        recordSize,
        SweepJson.string(object, "sweep"),
        SweepJson.longInteger(object, "totalCases"),
        SweepJson.integer(object, "threads"),
        classifications,
        classificationStatuses);
  }

  static void readRecords(Path inputDir, Manifest manifest, RecordConsumer consumer)
      throws IOException {
    if (manifest.formatVersion() != FORMAT_VERSION || manifest.recordSize() != RECORD_SIZE) {
      throw new IllegalArgumentException("unsupported compact divergence log format");
    }
    Path indicesDir = inputDir.resolve("divergence-indices");
    for (int worker = 0; worker < manifest.threads(); worker++) {
      Path path = indicesDir.resolve(String.format("worker-%02d.bin", worker));
      if (!Files.exists(path)) {
        continue;
      }
      try (DataInputStream input =
          new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
        while (true) {
          try {
            long caseIndex = input.readLong();
            int classificationId = input.readUnsignedByte();
            consumer.accept(new Record(caseIndex, classificationId));
          } catch (EOFException e) {
            break;
          }
        }
      }
    }
  }

  static void readRecordsSorted(Path inputDir, Manifest manifest, RecordConsumer consumer)
      throws IOException {
    if (manifest.formatVersion() != FORMAT_VERSION || manifest.recordSize() != RECORD_SIZE) {
      throw new IllegalArgumentException("unsupported compact divergence log format");
    }
    List<WorkerInput> inputs = new ArrayList<>();
    PriorityQueue<WorkerInput> queue =
        new PriorityQueue<>(Comparator.comparingLong(input -> input.record.caseIndex()));
    try {
      Path indicesDir = inputDir.resolve("divergence-indices");
      for (int worker = 0; worker < manifest.threads(); worker++) {
        Path path = indicesDir.resolve(String.format("worker-%02d.bin", worker));
        if (!Files.exists(path)) {
          continue;
        }
        WorkerInput input = new WorkerInput(path);
        inputs.add(input);
        if (input.advance()) {
          queue.add(input);
        }
      }
      while (!queue.isEmpty()) {
        WorkerInput input = queue.remove();
        consumer.accept(input.record);
        if (input.advance()) {
          queue.add(input);
        }
      }
    } finally {
      IOException failure = null;
      for (WorkerInput input : inputs) {
        try {
          input.close();
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
  }

  record Manifest(
      int formatVersion,
      int recordSize,
      String sweep,
      long totalCases,
      int threads,
      List<String> classifications,
      List<DivergenceStatus> classificationStatuses) {}

  record Record(long caseIndex, int classificationId) {}

  interface RecordConsumer {
    void accept(Record record) throws IOException;
  }

  private static final class WorkerInput implements AutoCloseable {
    private final DataInputStream input;
    Record record;

    WorkerInput(Path path) throws IOException {
      input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)));
    }

    boolean advance() throws IOException {
      try {
        record = new Record(input.readLong(), input.readUnsignedByte());
        return true;
      } catch (EOFException e) {
        record = null;
        return false;
      }
    }

    @Override
    public void close() throws IOException {
      input.close();
    }
  }

  private static final class WorkerLog implements AutoCloseable {
    private final DataOutputStream output;
    private final long[] classCounts;
    private long bytesWritten;
    private long nextCaseIndex;

    WorkerLog(Path path, int classificationCount) throws IOException {
      output =
          new DataOutputStream(
              new BufferedOutputStream(
                  Files.newOutputStream(
                      path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)));
      classCounts = new long[classificationCount];
    }

    synchronized void write(long caseIndex, int classificationId) throws IOException {
      output.writeLong(caseIndex);
      output.writeByte(classificationId);
      bytesWritten += RECORD_SIZE;
      classCounts[classificationId]++;
    }

    synchronized void updateNextCaseIndex(long nextCaseIndex) {
      this.nextCaseIndex = nextCaseIndex;
    }

    synchronized WorkerSnapshot flushAndSnapshot() throws IOException {
      output.flush();
      return new WorkerSnapshot(nextCaseIndex, bytesWritten, classCounts.clone());
    }

    @Override
    public synchronized void close() throws IOException {
      output.close();
    }
  }

  private record WorkerSnapshot(long nextCaseIndex, long durableBytes, long[] classCounts) {}
}
