// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.crosscheck;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** Crosscheck view of caller-owned UTF-8 storage. */
public final class Utf8Input {
  private final org.safere.Utf8Input delegate;
  private final byte[] bytes;
  private final int offset;
  private final int length;
  private final String decoded;

  private Utf8Input(
      org.safere.Utf8Input delegate, byte[] bytes, int offset, int length, String decoded) {
    this.delegate = delegate;
    this.bytes = bytes;
    this.offset = offset;
    this.length = length;
    this.decoded = decoded;
  }

  /** Creates a trusted whole-array input without semantic oracle comparison. */
  public static Utf8Input trusted(byte[] bytes) {
    requireNonNull(bytes, "bytes");
    return trusted(bytes, 0, bytes.length);
  }

  /** Creates a trusted logical window without semantic oracle comparison. */
  public static Utf8Input trusted(byte[] bytes, int offset, int length) {
    org.safere.Utf8Input delegate = org.safere.Utf8Input.trusted(bytes, offset, length);
    return new Utf8Input(delegate, bytes, offset, length, null);
  }

  /** Creates a strictly validated whole-array input with String and JDK oracles. */
  public static Utf8Input validated(byte[] bytes) {
    requireNonNull(bytes, "bytes");
    return validated(bytes, 0, bytes.length);
  }

  /** Creates a strictly validated logical window with String and JDK oracles. */
  public static Utf8Input validated(byte[] bytes, int offset, int length) {
    org.safere.Utf8Input delegate = org.safere.Utf8Input.validated(bytes, offset, length);
    return new Utf8Input(delegate, bytes, offset, length, new String(bytes, offset, length, UTF_8));
  }

  /** Returns the logical input length in UTF-8 bytes. */
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

  boolean validated() {
    return decoded != null;
  }

  String decoded() {
    if (decoded == null) {
      throw new IllegalStateException("trusted UTF-8 input has no semantic oracle");
    }
    return decoded;
  }
}
