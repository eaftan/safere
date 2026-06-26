// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

final class StringInputScanner implements InputScanner {
  private final String text;

  StringInputScanner(String text) {
    this.text = text;
  }

  String text() {
    return text;
  }

  @Override
  public int length() {
    return text.length();
  }

  @Override
  public int charOrByteAt(int pos) {
    return text.charAt(pos);
  }

  @Override
  public int codePointAt(int pos) {
    return text.codePointAt(pos);
  }

  @Override
  public int codePointAt(int pos, int[] nextPos) {
    char ch = text.charAt(pos);
    if (Character.isHighSurrogate(ch)
        && pos + 1 < text.length()
        && Character.isLowSurrogate(text.charAt(pos + 1))) {
      nextPos[0] = pos + 2;
      return Character.toCodePoint(ch, text.charAt(pos + 1));
    }
    nextPos[0] = pos + 1;
    return ch;
  }

  @Override
  public int codePointBefore(int pos) {
    return text.codePointBefore(pos);
  }

  @Override
  public int codePointBefore(int pos, int[] prevPos) {
    char ch = text.charAt(pos - 1);
    if (Character.isLowSurrogate(ch)
        && pos - 2 >= 0
        && Character.isHighSurrogate(text.charAt(pos - 2))) {
      prevPos[0] = pos - 2;
      return Character.toCodePoint(text.charAt(pos - 2), ch);
    }
    prevPos[0] = pos - 1;
    return ch;
  }

  @Override
  public int trailingLineTerminatorStart(boolean unixLines, int logicalEndPos) {
    int len = logicalEndPos;
    if (len <= 0 || len > text.length()) {
      return -1;
    }
    char ch = text.charAt(len - 1);
    if (unixLines) {
      return ch == '\n' ? len - 1 : -1;
    }
    if (ch == '\n') {
      return len >= 2 && text.charAt(len - 2) == '\r' ? len - 2 : len - 1;
    }
    if (ch == '\r' || ch == '\u0085' || ch == '\u2028' || ch == '\u2029') {
      return len - 1;
    }
    return -1;
  }

  @Override
  public int positionDependentThreshold(boolean dollarAnchorEnd, boolean unixLines) {
    int threshold = Integer.MAX_VALUE;
    // Note: the caller handles hasTextAnchor threshold (= 1).
    if (dollarAnchorEnd) {
      int trailingTermStart = trailingLineTerminatorStart(unixLines, text.length());
      if (trailingTermStart >= 0) {
        threshold = trailingTermStart;
      } else {
        threshold = text.length();
      }
    }
    return threshold;
  }
}
