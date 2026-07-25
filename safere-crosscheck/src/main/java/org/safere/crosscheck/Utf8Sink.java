// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.crosscheck;

/** Synchronous destination for borrowed UTF-8 ranges emitted by a crosscheck matcher. */
@FunctionalInterface
public interface Utf8Sink extends org.safere.Utf8Sink {
  /** Consumes the supplied borrowed byte range before returning. */
  @Override
  void append(byte[] bytes, int offset, int length);
}
