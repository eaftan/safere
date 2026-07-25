// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.util.Objects.requireNonNull;

/** Immutable execution bounds shared by regex engines. */
record EngineContext(
    InputScanner text,
    int searchStart,
    int searchLimit,
    int endPos,
    int graphemeConsumeEndPos,
    int consumeRegionStart,
    int boundaryRegionStart,
    int boundaryEndPos,
    int anchorEndPos,
    int emptyAnchorStartPos,
    int emptyAnchorEndPos,
    GraphemeSupport.Context graphemeContext) {

  EngineContext {
    requireNonNull(text, "text");
    requireNonNull(graphemeContext, "graphemeContext");
  }

  static EngineContext create(
      Prog prog,
      InputScanner text,
      int searchStart,
      int searchLimit,
      int endPos,
      int graphemeConsumeEndPos,
      int consumeRegionStart,
      int boundaryRegionStart,
      int boundaryEndPos,
      int anchorEndPos,
      int emptyAnchorStartPos,
      int emptyAnchorEndPos,
      GraphemeSupport.Context graphemeContext) {
    GraphemeSupport.Context context =
        graphemeContext != null
            ? graphemeContext
            : GraphemeSupport.Context.create(text, prog.hasGraphemeSemantics());
    return new EngineContext(
        text,
        searchStart,
        searchLimit,
        endPos,
        graphemeConsumeEndPos,
        consumeRegionStart,
        boundaryRegionStart,
        boundaryEndPos,
        anchorEndPos,
        emptyAnchorStartPos,
        emptyAnchorEndPos,
        context);
  }

  int engineEndPos() {
    return Math.max(endPos, graphemeConsumeEndPos);
  }

  int effectiveBoundaryEndPos(boolean consumedInput) {
    return consumedInput && graphemeConsumeEndPos > boundaryEndPos
        ? graphemeConsumeEndPos
        : boundaryEndPos;
  }
}
