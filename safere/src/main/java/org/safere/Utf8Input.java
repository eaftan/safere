// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.util.Objects.requireNonNull;

/**
 * A borrowed, immutable-bounds view of caller-owned UTF-8 text storage.
 *
 * <p>All positions are byte offsets relative to this logical view. The caller must not mutate the
 * covered bytes while the view or a matcher retaining it is in use. Metadata is safe to share
 * between threads under that precondition; a {@link Utf8Matcher} is not thread-safe.
 *
 * <p>This API is provisional while the Trino integration is validated.
 */
public sealed interface Utf8Input permits ArrayUtf8Input {
  /**
   * Returns a trusted view over an entire byte array without validating its UTF-8.
   *
   * <p>Exact match results for malformed input are unspecified, but matching remains bounded,
   * memory-safe, and monotonic.
   *
   * @param bytes caller-owned UTF-8 storage
   * @return the borrowed input view
   * @throws NullPointerException if {@code bytes} is null
   */
  static Utf8Input trusted(byte[] bytes) {
    requireNonNull(bytes, "bytes");
    return trusted(bytes, 0, bytes.length);
  }

  /**
   * Returns a trusted view over a byte-array window without validating its UTF-8.
   *
   * @param bytes caller-owned UTF-8 storage
   * @param offset physical array offset of the logical view
   * @param length logical view length in bytes
   * @return the borrowed input view
   * @throws NullPointerException if {@code bytes} is null
   * @throws IndexOutOfBoundsException if the window is outside {@code bytes}
   */
  static Utf8Input trusted(byte[] bytes, int offset, int length) {
    return new ArrayUtf8Input(bytes, offset, length, false);
  }

  /**
   * Returns a view over an entire byte array after strict RFC 3629 UTF-8 validation.
   *
   * @param bytes caller-owned UTF-8 storage
   * @return the borrowed, validated input view
   * @throws NullPointerException if {@code bytes} is null
   * @throws IllegalArgumentException if the first malformed sequence is encountered
   */
  static Utf8Input validated(byte[] bytes) {
    requireNonNull(bytes, "bytes");
    return validated(bytes, 0, bytes.length);
  }

  /**
   * Returns a byte-array window after strict RFC 3629 UTF-8 validation.
   *
   * @param bytes caller-owned UTF-8 storage
   * @param offset physical array offset of the logical view
   * @param length logical view length in bytes
   * @return the borrowed, validated input view
   * @throws NullPointerException if {@code bytes} is null
   * @throws IndexOutOfBoundsException if the window is outside {@code bytes}
   * @throws IllegalArgumentException if malformed UTF-8 occurs, with a relative byte offset
   */
  static Utf8Input validated(byte[] bytes, int offset, int length) {
    Utf8InputScanner.validate(bytes, offset, length);
    return new ArrayUtf8Input(bytes, offset, length, true);
  }

  /**
   * Returns the length of the logical input in UTF-8 bytes.
   *
   * @return the relative logical end position
   */
  int length();
}
