// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.recording;

import java.io.ByteArrayOutputStream;

/** Recording facade for {@link org.safere.Utf8Matcher}. */
public final class Utf8Matcher {

  private final org.safere.Utf8Matcher delegate;
  private final long recordingId = RecordingRuntime.newObjectId();
  private long sequence;

  Utf8Matcher(Pattern pattern, Utf8Input input, org.safere.Utf8Matcher delegate) {
    this.delegate = delegate;
    RecordingRuntime.record(
        "U",
        recordingId,
        sequence,
        "matcherUtf8",
        RecordingRuntime.encodeArguments(
            pattern.delegate().pattern(),
            pattern.delegate().flags(),
            input.bytes(),
            input.offset(),
            input.length()),
        "created");
  }

  /** Finds the next match. */
  public boolean find() {
    return record("find", delegate::find);
  }

  /** Returns the full match's relative byte start. */
  public int start() {
    return record("start", delegate::start);
  }

  /** Returns a group's relative byte start. */
  public int start(int group) {
    return record("start", () -> delegate.start(group), group);
  }

  /** Returns the full match's relative byte end. */
  public int end() {
    return record("end", delegate::end);
  }

  /** Returns a group's relative byte end. */
  public int end(int group) {
    return record("end", () -> delegate.end(group), group);
  }

  /** Returns the capturing-group count. */
  public int groupCount() {
    return record("groupCount", delegate::groupCount);
  }

  /** Appends the unmatched prefix and expanded replacement. */
  public Utf8Matcher appendReplacement(Utf8Sink sink, Utf8Input replacement) {
    ByteArrayOutputStream recordedOutput = new ByteArrayOutputStream();
    record(
        "appendReplacement",
        () -> {
          delegate.appendReplacement(
              (bytes, offset, length) -> {
                recordedOutput.write(bytes, offset, length);
                sink.append(bytes, offset, length);
              },
              replacement.delegate());
          return recordedOutput.toByteArray();
        },
        replacement.bytes(),
        replacement.offset(),
        replacement.length());
    return this;
  }

  /** Appends the remaining unmatched input. */
  public void appendTail(Utf8Sink sink) {
    ByteArrayOutputStream recordedOutput = new ByteArrayOutputStream();
    record(
        "appendTail",
        () -> {
          delegate.appendTail(
              (bytes, offset, length) -> {
                recordedOutput.write(bytes, offset, length);
                sink.append(bytes, offset, length);
              });
          return recordedOutput.toByteArray();
        });
  }

  private <T> T record(String method, Operation<T> operation, Object... arguments) {
    long callSequence = ++sequence;
    String encodedArguments = RecordingRuntime.encodeArguments(arguments);
    try {
      T result = operation.run();
      RecordingRuntime.record("U", recordingId, callSequence, method, encodedArguments, result);
      return result;
    } catch (RuntimeException e) {
      RecordingRuntime.recordException("U", recordingId, callSequence, method, encodedArguments, e);
      throw e;
    }
  }

  @FunctionalInterface
  private interface Operation<T> {
    T run();
  }
}
