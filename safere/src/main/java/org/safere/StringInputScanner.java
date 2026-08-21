// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

final class StringInputScanner implements InputScanner {
  private final String text;
  private final VectorScanProvider scanProvider;

  StringInputScanner(String text) {
    this.text = text;
    this.scanProvider = VectorScanProviders.providerForLength(text.length());
  }

  String text() {
    return text;
  }

  @Override
  public int length() {
    return text.length();
  }

  @Override
  public int asciiAt(int pos) {
    char c = text.charAt(pos);
    return c < 0x80 ? c : -1;
  }

  @Override
  public int indexOfAscii(int ascii, int fromIndex, int limit) {
    if (WorkCounterConfig.ENABLED) {
      int end = Math.min(limit, text.length());
      for (int i = Math.max(0, fromIndex); i < end; i++) {
        WorkCounter.record();
        if (text.charAt(i) == ascii) {
          return i;
        }
      }
      return -1;
    }
    int idx = text.indexOf(ascii, fromIndex);
    return (idx >= 0 && idx < limit) ? idx : -1;
  }

  @Override
  public int indexOfAsciiPair(int c1, int c2, int fromIndex, int limit) {
    if (!WorkCounterConfig.ENABLED
        && scanProvider != null
        && limit - fromIndex >= scanProvider.minimumInputLength()) {
      int idx = StringVectorScan.indexOfAsciiPair(text, c1, c2, fromIndex, limit);
      if (idx != VectorScanProvider.UNSUPPORTED) {
        return idx;
      }
    }
    return scalarIndexOfAsciiPair(c1, c2, fromIndex, limit);
  }

  private int scalarIndexOfAsciiPair(int c1, int c2, int fromIndex, int limit) {
    int end = Math.min(limit, text.length());
    for (int i = Math.max(0, fromIndex); i < end; i++) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      char ch = text.charAt(i);
      if (ch == c1 || ch == c2) {
        return i;
      }
    }
    return -1;
  }

  @Override
  public int indexOfAsciiTriple(int c1, int c2, int c3, int fromIndex, int limit) {
    if (!WorkCounterConfig.ENABLED
        && scanProvider != null
        && limit - fromIndex >= scanProvider.minimumInputLength()) {
      int idx = StringVectorScan.indexOfAsciiTriple(text, c1, c2, c3, fromIndex, limit);
      if (idx != VectorScanProvider.UNSUPPORTED) {
        return idx;
      }
    }
    return scalarIndexOfAsciiTriple(c1, c2, c3, fromIndex, limit);
  }

  private int scalarIndexOfAsciiTriple(int c1, int c2, int c3, int fromIndex, int limit) {
    int end = Math.min(limit, text.length());
    for (int i = Math.max(0, fromIndex); i < end; i++) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      char ch = text.charAt(i);
      if (ch == c1 || ch == c2 || ch == c3) {
        return i;
      }
    }
    return -1;
  }

  @Override
  public int singleUnitCodePointAt(int pos) {
    char c = text.charAt(pos);
    return Character.isHighSurrogate(c)
            && pos + 1 < text.length()
            && Character.isLowSurrogate(text.charAt(pos + 1))
        ? -1
        : c;
  }

  @Override
  public int singleUnitCodePointBefore(int pos) {
    char c = text.charAt(pos - 1);
    return Character.isLowSurrogate(c)
            && pos >= 2
            && Character.isHighSurrogate(text.charAt(pos - 2))
        ? -1
        : c;
  }

  @Override
  public int indexOfCharClass(CharClassScanInfo scanInfo, int start) {
    if (!WorkCounterConfig.ENABLED && scanProvider != null) {
      int vectorIndex = StringVectorScan.indexOfCharClass(text, scanInfo.ranges(), start);
      if (vectorIndex != VectorScanProvider.UNSUPPORTED) {
        return vectorIndex;
      }
    }
    return scalarIndexOfCharClass(scanInfo, start);
  }

  private int scalarIndexOfCharClass(CharClassScanInfo scanInfo, int start) {
    int position = Math.max(0, start);
    int[] ranges = scanInfo.ranges();
    long bitmap0 = scanInfo.bitmap0();
    long bitmap1 = scanInfo.bitmap1();
    while (position < text.length()) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      char ch = text.charAt(position);
      if (InputScanner.classContains(ranges, bitmap0, bitmap1, ch)) {
        return position;
      }
      position++;
    }
    return -1;
  }

  @Override
  public int indexOfCodePointClass(int[] ranges, long bitmap0, long bitmap1, int start, int limit) {
    if (!WorkCounterConfig.ENABLED
        && scanProvider != null
        && limit - start >= scanProvider.minimumInputLength()) {
      int vectorIndex = StringVectorScan.indexOfCharClass(text, ranges, start, limit);
      if (vectorIndex != VectorScanProvider.UNSUPPORTED) {
        return vectorIndex;
      }
    }
    return scalarIndexOfCodePointClass(ranges, bitmap0, bitmap1, start, limit);
  }

  private int scalarIndexOfCodePointClass(
      int[] ranges, long bitmap0, long bitmap1, int start, int limit) {
    int position = Math.max(0, start);
    int bound = Math.min(limit, text.length());
    while (position < bound) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      int codePoint = text.codePointAt(position);
      if (InputScanner.classContains(ranges, bitmap0, bitmap1, codePoint)) {
        return position;
      }
      position += Character.charCount(codePoint);
    }
    return -1;
  }

  @Override
  public long decodeForward(int pos) {
    if (pos >= text.length()) {
      return InputScanner.decoded(END_OF_INPUT, text.length());
    }
    int codePoint = text.codePointAt(pos);
    return InputScanner.decoded(codePoint, pos + Character.charCount(codePoint));
  }

  @Override
  public long decodeBackward(int pos) {
    if (pos <= 0) {
      return InputScanner.decoded(END_OF_INPUT, 0);
    }
    int codePoint = text.codePointBefore(pos);
    return InputScanner.decoded(codePoint, pos - Character.charCount(codePoint));
  }

  @Override
  public int codePointAt(int pos) {
    return pos >= text.length() ? END_OF_INPUT : text.codePointAt(pos);
  }

  @Override
  public int codePointBefore(int pos) {
    return pos <= 0 ? END_OF_INPUT : text.codePointBefore(pos);
  }

  @Override
  public boolean isCodePointBoundary(int pos) {
    if (pos < 0 || pos > text.length()) {
      return false;
    }
    return pos == 0
        || pos == text.length()
        || !Character.isLowSurrogate(text.charAt(pos))
        || !Character.isHighSurrogate(text.charAt(pos - 1));
  }

  static int trailingLineTerminatorStart(String text, boolean unixLines, int logicalEndPos) {
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
  public int trailingLineTerminatorStart(boolean unixLines, int logicalEndPos) {
    return trailingLineTerminatorStart(text, unixLines, logicalEndPos);
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
