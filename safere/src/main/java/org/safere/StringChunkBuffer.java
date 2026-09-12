// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * Reusable ThreadLocal buffer for chunked vector scanning of {@link String} inputs when {@code
 * --add-opens} is not available.
 */
final class StringChunkBuffer {
  static final int CHUNK_SIZE = 512;
  static final int MIN_CHUNK_THRESHOLD = 64;

  private static final ThreadLocal<char[]> BUFFER =
      ThreadLocal.withInitial(() -> new char[CHUNK_SIZE]);

  static char[] get() {
    return BUFFER.get();
  }

  static int copyChunk(String text, int pos, int scanLimit, char[] buffer) {
    int chunkSize = Math.min(CHUNK_SIZE, scanLimit - pos);
    text.getChars(pos, pos + chunkSize, buffer, 0);
    return chunkSize;
  }

  private StringChunkBuffer() {}
}
