// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Objects;

/** An opaque, region-relative String view. Construction never copies the backing text. */
final class StringRegionInputScanner implements InputScanner {
  private final String text;
  private final int base;
  private final int length;

  StringRegionInputScanner(String text, int start, int end) {
    this.text = text;
    this.base = start;
    this.length = end - start;
  }

  boolean startsWith(String literal, int from, boolean foldCase) {
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.record(literal.length());
    }
    return from >= 0
        && from <= length - literal.length()
        && (foldCase
            ? Ascii.regionMatchesIgnoreCase(text, base + from, literal, literal.length())
            : text.startsWith(literal, base + from));
  }

  boolean endsWith(String suffix, boolean wasDollar, boolean unixLines, boolean foldCase) {
    int suffixLength = suffix.length();
    if (length >= suffixLength
        && text.regionMatches(foldCase, base + length - suffixLength, suffix, 0, suffixLength)) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record(suffixLength);
      }
      return true;
    }
    if (!wasDollar) {
      return false;
    }
    int trailing = trailingLineTerminatorStart(unixLines, length);
    if (trailing >= suffixLength
        && text.regionMatches(foldCase, base + trailing - suffixLength, suffix, 0, suffixLength)) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record(suffixLength);
      }
      return true;
    }
    return false;
  }

  int indexOf(String literal, int from, int limit) {
    int start = Math.max(0, from);
    int end = Math.min(length, limit);
    if (start > end - literal.length()) {
      return -1;
    }
    int found = text.indexOf(literal, base + start, base + end);
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.record(found < 0 ? end - start : found - base - start + literal.length());
    }
    return found < 0 ? -1 : found - base;
  }

  /** Bounded ASCII-folded search with an input-sized verification budget and KMP fallback. */
  int indexOfIgnoreCase(String literal, int anchorOffset, char low, char high, int from) {
    int pos = Math.max(0, from);
    int literalLength = literal.length();
    if (literalLength == 0) {
      return Math.min(pos, length);
    }
    int anchorEnd = length - literalLength + anchorOffset + 1;
    long work = 0;
    long budget = WorkLimit.forRemaining(length - pos);
    while (pos <= length - literalLength) {
      int anchor = indexOfCaseAnchor(low, high, pos + anchorOffset, anchorEnd);
      if (anchor < 0) {
        return -1;
      }
      int candidate = anchor - anchorOffset;
      if (startsWith(literal, candidate, true)) {
        return candidate;
      }
      pos = candidate + 1;
      work += literalLength;
      if (WorkLimit.isExhausted(work, budget)) {
        int found =
            Ascii.indexOfLinearIgnoreCase(
                text, literal, Ascii.ignoreCaseFailure(literal), base + pos, base + length);
        return found < 0 ? -1 : found - base;
      }
    }
    return -1;
  }

  private int indexOfCaseAnchor(char low, char high, int from, int limit) {
    int pos = from;
    // Preserve the existing case-insensitive String search's scalar prologue.
    int scalarEnd = pos + Math.min(32, limit - pos);
    for (; pos < scalarEnd; pos++) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      char c = text.charAt(base + pos);
      if (c == low || c == high) {
        return pos;
      }
    }
    // As in StringInputScanner's small-set search, cap and grow disjoint windows. Searching
    // both cases to the end of the region on every find would repeatedly rescan an absent case.
    int window = 64;
    while (pos < limit) {
      int end = pos + Math.min(window, limit - pos);
      int first = indexOfAscii(low, pos, end);
      int second = low == high ? -1 : indexOfAscii(high, pos, first >= 0 ? first : end);
      int found = Ascii.minNonNegative(first, second);
      if (found >= 0) {
        return found;
      }
      pos = end;
      window = Math.min(4096, window * 2);
    }
    return -1;
  }

  boolean matchesCharClass(Pattern.CharClassMatchInfo info) {
    if (length == 0) {
      return info.allowEmpty();
    }
    for (int pos = 0; pos < length; ) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      int cp = codePointAt(pos);
      if (!InputScanner.classContains(info.ranges(), info.bitmap0(), info.bitmap1(), cp)) {
        return false;
      }
      pos += Character.charCount(cp);
    }
    return true;
  }

  @Override
  public int length() {
    return length;
  }

  @Override
  public int asciiAt(int pos) {
    char c = text.charAt(base + Objects.checkIndex(pos, length));
    return c < 128 ? c : -1;
  }

  @Override
  public int singleUnitCodePointAt(int pos) {
    char c = text.charAt(base + Objects.checkIndex(pos, length));
    return Character.isHighSurrogate(c)
            && pos + 1 < length
            && Character.isLowSurrogate(text.charAt(base + pos + 1))
        ? -1
        : c;
  }

  @Override
  public int singleUnitCodePointBefore(int pos) {
    char c = text.charAt(base + Objects.checkIndex(pos - 1, length));
    return Character.isLowSurrogate(c)
            && pos >= 2
            && Character.isHighSurrogate(text.charAt(base + pos - 2))
        ? -1
        : c;
  }

  @Override
  public long decodeForward(int pos) {
    if (pos >= length) {
      return InputScanner.decoded(END_OF_INPUT, length);
    }
    int cp = codePointAt(pos);
    return InputScanner.decoded(cp, pos + Character.charCount(cp));
  }

  @Override
  public long decodeForward(int pos, int limit) {
    int end = Math.min(length, limit);
    if (pos >= end) {
      return InputScanner.decoded(END_OF_INPUT, end);
    }
    char first = text.charAt(base + Objects.checkIndex(pos, length));
    int cp =
        Character.isHighSurrogate(first)
                && pos + 1 < end
                && Character.isLowSurrogate(text.charAt(base + pos + 1))
            ? Character.toCodePoint(first, text.charAt(base + pos + 1))
            : first;
    return InputScanner.decoded(cp, pos + Character.charCount(cp));
  }

  @Override
  public long decodeBackward(int pos) {
    if (pos <= 0) {
      return InputScanner.decoded(END_OF_INPUT, 0);
    }
    int cp = codePointBefore(pos);
    return InputScanner.decoded(cp, pos - Character.charCount(cp));
  }

  @Override
  public long decodeBackward(int pos, int start) {
    int lower = Math.max(0, start);
    if (pos <= lower) {
      return InputScanner.decoded(END_OF_INPUT, lower);
    }
    char last = text.charAt(base + Objects.checkIndex(pos - 1, length));
    int cp =
        Character.isLowSurrogate(last)
                && pos - 2 >= lower
                && Character.isHighSurrogate(text.charAt(base + pos - 2))
            ? Character.toCodePoint(text.charAt(base + pos - 2), last)
            : last;
    return InputScanner.decoded(cp, pos - Character.charCount(cp));
  }

  @Override
  public int codePointAt(int pos) {
    if (pos >= length) {
      return END_OF_INPUT;
    }
    char c = text.charAt(base + Objects.checkIndex(pos, length));
    if (Character.isHighSurrogate(c) && pos + 1 < length) {
      char next = text.charAt(base + pos + 1);
      if (Character.isLowSurrogate(next)) {
        return Character.toCodePoint(c, next);
      }
    }
    return c;
  }

  @Override
  public int codePointBefore(int pos) {
    if (pos <= 0) {
      return END_OF_INPUT;
    }
    char c = text.charAt(base + Objects.checkIndex(pos - 1, length));
    if (Character.isLowSurrogate(c) && pos >= 2) {
      char previous = text.charAt(base + pos - 2);
      if (Character.isHighSurrogate(previous)) {
        return Character.toCodePoint(previous, c);
      }
    }
    return c;
  }

  @Override
  public boolean isCodePointBoundary(int pos) {
    return pos >= 0
        && pos <= length
        && (pos == 0
            || pos == length
            || !Character.isLowSurrogate(text.charAt(base + pos))
            || !Character.isHighSurrogate(text.charAt(base + pos - 1)));
  }

  @Override
  public int indexOfCodePointClass(int[] ranges, long bitmap0, long bitmap1, int start, int limit) {
    int bound = Math.min(limit, length);
    for (int pos = Math.max(0, start); pos < bound; ) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      int cp = codePointAt(pos);
      if (InputScanner.classContains(ranges, bitmap0, bitmap1, cp)) {
        return pos;
      }
      pos += Character.charCount(cp);
    }
    return -1;
  }

  @Override
  public int indexOfCharClass(CharClassScanInfo info, int start) {
    return indexOfCodePointClass(info.ranges(), info.bitmap0(), info.bitmap1(), start, length);
  }

  @Override
  public int indexOfAscii(int ascii, int fromIndex, int limit) {
    int start = Math.max(0, fromIndex);
    int end = Math.min(limit, length);
    if (start >= end) {
      return -1;
    }
    int found = text.indexOf(ascii, base + start, base + end);
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.record(found < 0 ? end - start : found - base - start + 1);
    }
    return found < 0 ? -1 : found - base;
  }

  @Override
  public int indexOfAsciiOrNonAscii(int ascii, int fromIndex, int limit) {
    return indexOfAsciiSet(ascii, ascii, ascii, true, fromIndex, limit);
  }

  @Override
  public int indexOfAsciiPair(int c1, int c2, int fromIndex, int limit) {
    return indexOfAsciiSet(c1, c2, c2, false, fromIndex, limit);
  }

  @Override
  public int indexOfAsciiPairOrNonAscii(int c1, int c2, int fromIndex, int limit) {
    return indexOfAsciiSet(c1, c2, c2, true, fromIndex, limit);
  }

  @Override
  public int indexOfAsciiTriple(int c1, int c2, int c3, int fromIndex, int limit) {
    return indexOfAsciiSet(c1, c2, c3, false, fromIndex, limit);
  }

  @Override
  public int indexOfAsciiTripleOrNonAscii(int c1, int c2, int c3, int fromIndex, int limit) {
    return indexOfAsciiSet(c1, c2, c3, true, fromIndex, limit);
  }

  private int indexOfAsciiSet(int c1, int c2, int c3, boolean nonAscii, int from, int limit) {
    int end = Math.min(limit, length);
    for (int pos = Math.max(0, from); pos < end; pos++) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      char c = text.charAt(base + Objects.checkIndex(pos, length));
      if (c == c1 || c == c2 || c == c3 || (nonAscii && c >= 128)) {
        return pos;
      }
    }
    return -1;
  }

  @Override
  public int trailingLineTerminatorStart(boolean unixLines, int end) {
    if (end <= 0 || end > length) {
      return -1;
    }
    int c = asciiAt(end - 1);
    if (unixLines) {
      return c == '\n' ? end - 1 : -1;
    }
    int cp = codePointBefore(end);
    if (cp == '\n') {
      return end >= 2 && asciiAt(end - 2) == '\r' ? end - 2 : end - 1;
    }
    return cp == '\r' || cp == 0x85 || cp == 0x2028 || cp == 0x2029 ? end - 1 : -1;
  }

  @Override
  public int positionDependentThreshold(boolean dollarAnchorEnd, boolean unixLines) {
    if (!dollarAnchorEnd) {
      return Integer.MAX_VALUE;
    }
    int trailing = trailingLineTerminatorStart(unixLines, length);
    return trailing >= 0 ? trailing : length;
  }
}
