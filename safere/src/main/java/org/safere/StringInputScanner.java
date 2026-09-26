// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

final class StringInputScanner implements InputScanner {
  /**
   * Most distinct chars {@link #memoizedIndexOf} remembers beyond the two held in fields. A start
   * accelerator queries at most two chars, and each small-set reject prefilter at most two, so this
   * leaves room for a composite prefilter with a few such children. Exceeding it only costs
   * rescans, never correctness.
   */
  private static final int MEMO_OVERFLOW_SLOTS = 6;

  private final String text;

  // State for memoizedIndexOf: for each remembered char c, the next occurrence of c at or after
  // `from` is `next` (-1 if there is none). The first two chars live in fields, so the common case
  // of one two-member class never allocates; further chars go to lazily allocated arrays.
  private int memoCount;
  private char memoChar0;
  private int memoFrom0;
  private int memoNext0;
  private char memoChar1;
  private int memoFrom1;
  private int memoNext1;
  private char[] memoChars;
  private int[] memoFroms;
  private int[] memoNexts;
  private int memoVictim;

  StringInputScanner(String text) {
    this.text = text;
  }

  String text() {
    return text;
  }

  /** Returns {@code text.indexOf(c, fromIndex)}, charging the work counter for the span scanned. */
  int indexOfChar(char c, int fromIndex) {
    int from = Math.max(0, fromIndex);
    int idx = from < text.length() ? text.indexOf(c, from) : -1;
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.record(Math.max(0, (idx >= 0 ? idx + 1 : text.length()) - from));
    }
    return idx;
  }

  /**
   * Scans at most {@code window} chars from {@code fromIndex} for {@code c0} or {@code c1}. Returns
   * the index of the first one found, or {@code ~end} if neither occurs before {@code end}, the
   * position where the probe stopped. Charges the work counter for the span scanned.
   *
   * <p>Callers run this before the per-member {@code indexOf} searches for a two-member set. When
   * one member is close and the other is absent, the probe answers without searching the rest of
   * the text for the absent member. The window is a small constant, so it adds constant work per
   * call and does not affect linearity.
   */
  int probeEither(char c0, char c1, int fromIndex, int window) {
    int from = Math.max(0, fromIndex);
    int length = text.length();
    int end = from < length - window ? from + window : Math.max(from, length);
    for (int i = from; i < end; i++) {
      char ch = text.charAt(i);
      if (ch == c0 || ch == c1) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(i + 1 - from);
        }
        return i;
      }
    }
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.record(end - from);
    }
    return ~end;
  }

  /**
   * Returns {@code text.indexOf(c, fromIndex)}, reusing an earlier answer for {@code c} when it
   * still applies.
   *
   * <p>Searching a small character set as one {@code String.indexOf} per member keeps the JDK's
   * vectorized single-character intrinsic, which has no two-character counterpart. But a caller
   * that repeats the search from increasing positions, as successive {@code find()} calls and DFA
   * restarts do, would rescan the whole remainder for a member that never occurs, which is
   * quadratic. Remembering each member's next occurrence means its scan only ever moves forward, so
   * the total work per member per scanner is linear in the text (the {@code
   * memoized-small-set-search} invariant in {@code design/SEMANTIC_INVARIANTS.md}).
   */
  int memoizedIndexOf(char c, int fromIndex) {
    int from = Math.max(0, fromIndex);
    if (memoCount > 0 && memoChar0 == c && memoStillValid(from, memoFrom0, memoNext0)) {
      return memoNext0;
    }
    if (memoCount > 1 && memoChar1 == c && memoStillValid(from, memoFrom1, memoNext1)) {
      return memoNext1;
    }
    return updateMemoizedIndexOf(c, from);
  }

  private int updateMemoizedIndexOf(char c, int from) {
    if (memoCount == 0 || memoChar0 == c) {
      if (memoCount == 0) {
        memoCount = 1;
        memoChar0 = c;
      }
      memoFrom0 = from;
      memoNext0 = indexOfChar(c, from);
      return memoNext0;
    }
    if (memoCount == 1 || memoChar1 == c) {
      if (memoCount == 1) {
        memoCount = 2;
        memoChar1 = c;
      }
      memoFrom1 = from;
      memoNext1 = indexOfChar(c, from);
      return memoNext1;
    }
    return overflowMemoizedIndexOf(c, from);
  }

  private int overflowMemoizedIndexOf(char c, int from) {
    if (memoChars == null) {
      memoChars = new char[MEMO_OVERFLOW_SLOTS];
      memoFroms = new int[MEMO_OVERFLOW_SLOTS];
      memoNexts = new int[MEMO_OVERFLOW_SLOTS];
    }
    int used = memoCount - 2;
    int slot = -1;
    for (int i = 0; i < used; i++) {
      if (memoChars[i] == c) {
        slot = i;
        break;
      }
    }
    if (slot >= 0) {
      if (memoStillValid(from, memoFroms[slot], memoNexts[slot])) {
        return memoNexts[slot];
      }
    } else if (used < MEMO_OVERFLOW_SLOTS) {
      slot = used;
      memoCount++;
      memoChars[slot] = c;
    } else {
      slot = memoVictim;
      memoVictim = (memoVictim + 1) % MEMO_OVERFLOW_SLOTS;
      memoChars[slot] = c;
    }
    memoFroms[slot] = from;
    memoNexts[slot] = indexOfChar(c, from);
    return memoNexts[slot];
  }

  /**
   * Whether a remembered answer "the next occurrence at or after {@code memoFrom} is {@code
   * memoNext}" also answers a search from {@code from}: nothing lies between {@code memoFrom} and
   * {@code from} that the earlier search could have skipped.
   */
  private static boolean memoStillValid(int from, int memoFrom, int memoNext) {
    return from >= memoFrom && (memoNext < 0 || from <= memoNext);
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
  public int indexOfAsciiOrNonAscii(int ascii, int fromIndex, int limit) {
    int end = Math.min(limit, text.length());
    for (int i = Math.max(0, fromIndex); i < end; i++) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      char ch = text.charAt(i);
      if (ch == ascii || ch >= 0x80) {
        return i;
      }
    }
    return -1;
  }

  @Override
  public int indexOfAsciiPair(int c1, int c2, int fromIndex, int limit) {
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
  public int indexOfAsciiPairOrNonAscii(int c1, int c2, int fromIndex, int limit) {
    int end = Math.min(limit, text.length());
    for (int i = Math.max(0, fromIndex); i < end; i++) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      char ch = text.charAt(i);
      if (ch == c1 || ch == c2 || ch >= 0x80) {
        return i;
      }
    }
    return -1;
  }

  @Override
  public int indexOfAsciiTriple(int c1, int c2, int c3, int fromIndex, int limit) {
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
  public int indexOfAsciiTripleOrNonAscii(int c1, int c2, int c3, int fromIndex, int limit) {
    int end = Math.min(limit, text.length());
    for (int i = Math.max(0, fromIndex); i < end; i++) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      char ch = text.charAt(i);
      if (ch == c1 || ch == c2 || ch == c3 || ch >= 0x80) {
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
