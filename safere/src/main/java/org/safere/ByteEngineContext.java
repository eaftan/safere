// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.util.Objects.requireNonNull;

/** Immutable execution bounds shared by raw byte matching engines. */
final class ByteEngineContext {
  private final byte[] text;
  private final int searchStart;
  private final int searchLimit;
  private final int endPos;
  private final int consumeRegionStart;
  private final int boundaryRegionStart;
  private final int boundaryEndPos;
  private final int anchorEndPos;
  private final int emptyAnchorStartPos;
  private final int emptyAnchorEndPos;

  ByteEngineContext(
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
    this.text = requireNonNull(text, "text");
    this.searchStart = searchStart;
    this.searchLimit = searchLimit;
    this.endPos = endPos;
    this.consumeRegionStart = consumeRegionStart;
    this.boundaryRegionStart = boundaryRegionStart;
    this.boundaryEndPos = boundaryEndPos;
    this.anchorEndPos = anchorEndPos;
    this.emptyAnchorStartPos = emptyAnchorStartPos;
    this.emptyAnchorEndPos = emptyAnchorEndPos;
  }

  byte[] text() {
    return text;
  }

  int searchStart() {
    return searchStart;
  }

  int searchLimit() {
    return searchLimit;
  }

  int endPos() {
    return endPos;
  }

  int consumeRegionStart() {
    return consumeRegionStart;
  }

  int boundaryRegionStart() {
    return boundaryRegionStart;
  }

  int boundaryEndPos() {
    return boundaryEndPos;
  }

  int anchorEndPos() {
    return anchorEndPos;
  }

  int emptyAnchorStartPos() {
    return emptyAnchorStartPos;
  }

  int emptyAnchorEndPos() {
    return emptyAnchorEndPos;
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
