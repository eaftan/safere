// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.recording;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Process-wide event sink used by generated recording tests. */
public final class RecordingRuntime {

  private static final String OUTPUT_PROPERTY = "org.safere.recording.output";
  private static final AtomicReference<String> ACTIVE_TEST =
      new AtomicReference<>("<unattributed>");
  private static final AtomicLong NEXT_OBJECT_ID = new AtomicLong();
  private static final Object OUTPUT_LOCK = new Object();

  private static BufferedWriter writer;

  private RecordingRuntime() {}

  /** Starts attributing facade calls to the given JUnit invocation. */
  public static void startTest(String testId) {
    if (!ACTIVE_TEST.compareAndSet("<unattributed>", Objects.requireNonNull(testId))) {
      throw new IllegalStateException("recording tests must execute serially");
    }
  }

  /** Stops attributing facade calls to the given JUnit invocation. */
  public static void finishTest(String testId) {
    if (!ACTIVE_TEST.compareAndSet(testId, "<unattributed>")) {
      throw new IllegalStateException("recording test attribution changed unexpectedly");
    }
  }

  static long newObjectId() {
    return NEXT_OBJECT_ID.incrementAndGet();
  }

  static void record(
      String kind, long objectId, long sequence, String method, String arguments, Object result) {
    write(kind, objectId, sequence, method, arguments, ValueEncoder.encode(result), "");
  }

  static void recordException(
      String kind,
      long objectId,
      long sequence,
      String method,
      String arguments,
      Throwable throwable) {
    write(
        kind,
        objectId,
        sequence,
        method,
        arguments,
        "",
        throwable.getClass().getName() + ":" + Objects.toString(throwable.getMessage(), ""));
  }

  private static void write(
      String kind,
      long objectId,
      long sequence,
      String method,
      String arguments,
      String result,
      String exception) {
    synchronized (OUTPUT_LOCK) {
      try {
        BufferedWriter output = writer();
        output.write(kind);
        output.write('\t');
        output.write(encodeField(ACTIVE_TEST.get()));
        output.write('\t');
        output.write(Long.toString(objectId));
        output.write('\t');
        output.write(Long.toString(sequence));
        output.write('\t');
        output.write(encodeField(method));
        output.write('\t');
        output.write(encodeField(arguments));
        output.write('\t');
        output.write(encodeField(result));
        output.write('\t');
        output.write(encodeField(exception));
        output.newLine();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private static BufferedWriter writer() throws IOException {
    if (writer == null) {
      String configuredPath = System.getProperty(OUTPUT_PROPERTY);
      if (configuredPath == null || configuredPath.isBlank()) {
        throw new IllegalStateException("missing system property: " + OUTPUT_PROPERTY);
      }
      Path path = Path.of(configuredPath);
      Files.createDirectories(path.getParent());
      writer =
          Files.newBufferedWriter(
              path,
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE);
      Runtime.getRuntime().addShutdownHook(new Thread(RecordingRuntime::closeWriter));
    }
    return writer;
  }

  private static void closeWriter() {
    synchronized (OUTPUT_LOCK) {
      if (writer == null) {
        return;
      }
      try {
        writer.close();
      } catch (IOException ignored) {
        // The process is already shutting down; the completed records remain useful.
      }
    }
  }

  static String encodeArguments(Object... arguments) {
    StringBuilder result = new StringBuilder();
    for (Object argument : arguments) {
      String encoded = ValueEncoder.encode(argument);
      result.append(encoded.length()).append(':').append(encoded);
    }
    return result.toString();
  }

  static String encodeField(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
