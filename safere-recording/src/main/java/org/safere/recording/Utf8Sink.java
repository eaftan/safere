// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.recording;

/** Synchronous destination for borrowed UTF-8 ranges emitted by a recording matcher. */
@FunctionalInterface
public interface Utf8Sink extends org.safere.Utf8Sink {
  /** Consumes the supplied borrowed byte range before returning. */
  @Override
  void append(byte[] bytes, int offset, int length);
}
