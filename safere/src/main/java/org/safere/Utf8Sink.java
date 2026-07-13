// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.nio.ByteBuffer;

/**
 * A synchronous destination for borrowed UTF-8 byte ranges.
 *
 * <p>An implementation must consume each range before returning and must not retain or mutate the
 * supplied storage.
 */
@FunctionalInterface
public interface Utf8Sink {
  /**
   * Appends the specified borrowed array range before this method returns.
   *
   * @param bytes borrowed storage
   * @param offset first byte to consume
   * @param length number of bytes to consume
   */
  void append(byte[] bytes, int offset, int length);

  /**
   * Appends the remaining bytes of a bounded duplicate before this method returns.
   *
   * <p>The default implementation does not change the caller's buffer position. It transfers
   * array-backed buffers directly and copies other buffers through bounded chunks.
   *
   * @param buffer buffer whose remaining bytes are consumed synchronously
   */
  default void append(ByteBuffer buffer) {
    ByteBuffer source = buffer.duplicate();
    if (source.hasArray()) {
      append(source.array(), source.arrayOffset() + source.position(), source.remaining());
      return;
    }
    byte[] chunk = new byte[Math.min(source.remaining(), 8192)];
    while (source.hasRemaining()) {
      int length = Math.min(source.remaining(), chunk.length);
      source.get(chunk, 0, length);
      append(chunk, 0, length);
    }
  }
}
