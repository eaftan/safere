// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Immutable execution bounds shared by regex engines. */
record EngineContext(
    String text,
    int searchStart,
    int searchLimit,
    int endPos,
    int graphemeConsumeEndPos,
    int consumeRegionStart,
    int boundaryRegionStart,
    int boundaryEndPos,
    int anchorEndPos,
    GraphemeSupport.Context graphemeContext) {

  EngineContext {
    if (text == null) {
      throw new NullPointerException("text");
    }
    if (graphemeContext == null) {
      throw new NullPointerException("graphemeContext");
    }
  }

  static EngineContext create(
      Prog prog,
      String text,
      int searchStart,
      int searchLimit,
      int endPos,
      int graphemeConsumeEndPos,
      int consumeRegionStart,
      int boundaryRegionStart,
      int boundaryEndPos,
      int anchorEndPos,
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
