// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.util.Objects.requireNonNull;

/** Immutable execution bounds shared by raw byte matching engines. */
record ByteEngineContext(
    byte[] text,
    int searchStart,
    int searchLimit,
    int endPos,
    int consumeRegionStart,
    int boundaryRegionStart,
    int boundaryEndPos,
    int anchorEndPos,
    int emptyAnchorStartPos,
    int emptyAnchorEndPos) {

  ByteEngineContext {
    requireNonNull(text, "text");
  }

  static ByteEngineContext create(
      Prog prog,
      byte[] text,
      int searchStart,
      int searchLimit,
      int endPos,
      int consumeRegionStart,
      int boundaryRegionStart,
      int boundaryEndPos,
      int anchorEndPos,
      int emptyAnchorStartPos,
      int emptyAnchorEndPos) {
    return new ByteEngineContext(
        text,
        searchStart,
        searchLimit,
        endPos,
        consumeRegionStart,
        boundaryRegionStart,
        boundaryEndPos,
        anchorEndPos,
        emptyAnchorStartPos,
        emptyAnchorEndPos);
  }
}
