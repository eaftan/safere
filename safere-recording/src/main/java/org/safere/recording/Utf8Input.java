// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.recording;

import static java.util.Objects.requireNonNull;

/** Recording-facade view of caller-owned UTF-8 storage. */
public final class Utf8Input {

  private final org.safere.Utf8Input delegate;
  private final byte[] bytes;
  private final int offset;
  private final int length;

  private Utf8Input(org.safere.Utf8Input delegate, byte[] bytes, int offset, int length) {
    this.delegate = delegate;
    this.bytes = bytes;
    this.offset = offset;
    this.length = length;
  }

  /** Creates a trusted whole-array input. */
  public static Utf8Input trusted(byte[] bytes) {
    requireNonNull(bytes, "bytes");
    return trusted(bytes, 0, bytes.length);
  }

  /** Creates a trusted logical window. */
  public static Utf8Input trusted(byte[] bytes, int offset, int length) {
    return create("Utf8Input.trusted", bytes, offset, length, false);
  }

  /** Creates a validated whole-array input. */
  public static Utf8Input validated(byte[] bytes) {
    requireNonNull(bytes, "bytes");
    return validated(bytes, 0, bytes.length);
  }

  /** Creates a validated logical window. */
  public static Utf8Input validated(byte[] bytes, int offset, int length) {
    return create("Utf8Input.validated", bytes, offset, length, true);
  }

  /** Returns the logical byte length. */
  public int length() {
    return length;
  }

  org.safere.Utf8Input delegate() {
    return delegate;
  }

  byte[] bytes() {
    return bytes;
  }

  int offset() {
    return offset;
  }

  private static Utf8Input create(
      String method, byte[] bytes, int offset, int length, boolean validated) {
    long id = RecordingRuntime.newObjectId();
    String arguments = RecordingRuntime.encodeArguments(bytes, offset, length);
    try {
      org.safere.Utf8Input delegate =
          validated
              ? org.safere.Utf8Input.validated(bytes, offset, length)
              : org.safere.Utf8Input.trusted(bytes, offset, length);
      RecordingRuntime.record("S", id, 0, method, arguments, "created");
      return new Utf8Input(delegate, bytes, offset, length);
    } catch (RuntimeException e) {
      RecordingRuntime.recordException("S", id, 0, method, arguments, e);
      throw e;
    }
  }
}
