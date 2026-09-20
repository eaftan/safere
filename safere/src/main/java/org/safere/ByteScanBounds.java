// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Inclusive scan limits in bytes, relative to the logical input slice. */
final class ByteScanBounds {
  private ByteScanBounds() {}

  /** Last start at which a match of {@code matchLen} fits; negative if no match fits. */
  static int lastMatchStart(int length, int matchLen) {
    return length - matchLen;
  }

  /** Last start at which a {@code loadBytes}-wide load at {@code +anchorOffset} fits. */
  static int lastLoadStart(int length, int anchorOffset, int loadBytes) {
    return length - anchorOffset - loadBytes;
  }

  /**
   * Last start at which both the widest anchor load and the full match fit.
   *
   * <p>This bounds the match at the scan start. Candidates in later lanes still need their own
   * full-match bounds checks.
   */
  static int lastWideScanStart(int length, int matchLen, int maxAnchorOffset, int loadBytes) {
    return Math.min(
        lastLoadStart(length, maxAnchorOffset, loadBytes), lastMatchStart(length, matchLen));
  }
}
