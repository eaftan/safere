// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import org.safere.MultiAnchorDescriptor.Gap;
import org.safere.MultiAnchorDescriptor.GapKind;

/**
 * Scanning and matching routines for pattern gaps against character sequences and UTF-8 scanner
 * inputs.
 */
final class GapScanner {
  private GapScanner() {}

  static int findFirstGuardByte(byte[] guardBytes, String text, int from, int to) {
    if (guardBytes == null || from >= to) {
      return -1;
    }
    int len = guardBytes.length;
    if (len == 1) {
      int idx = text.indexOf((char) guardBytes[0], from);
      return (idx >= from && idx < to) ? idx : -1;
    }
    if (len == 2) {
      int i0 = text.indexOf((char) guardBytes[0], from);
      int i1 = text.indexOf((char) guardBytes[1], from);
      int min = -1;
      if (i0 >= from && i0 < to) {
        min = i0;
      }
      if (i1 >= from && i1 < to && (min < 0 || i1 < min)) {
        min = i1;
      }
      return min;
    }
    if (len == 3) {
      int i0 = text.indexOf((char) guardBytes[0], from);
      int i1 = text.indexOf((char) guardBytes[1], from);
      int i2 = text.indexOf((char) guardBytes[2], from);
      int min = -1;
      if (i0 >= from && i0 < to) {
        min = i0;
      }
      if (i1 >= from && i1 < to && (min < 0 || i1 < min)) {
        min = i1;
      }
      if (i2 >= from && i2 < to && (min < 0 || i2 < min)) {
        min = i2;
      }
      return min;
    }
    for (int i = from; i < to; i++) {
      char c = text.charAt(i);
      for (byte b : guardBytes) {
        if (c == (char) b) {
          return i;
        }
      }
    }
    return -1;
  }

  static int findFirstGuardByte(byte[] guardBytes, Utf8InputScanner scanner, int from, int to) {
    if (guardBytes == null || from >= to) {
      return -1;
    }
    int len = guardBytes.length;
    if (len == 1) {
      int idx = scanner.indexOfAscii(guardBytes[0] & 0xFF, from, to);
      return (idx >= from && idx < to) ? idx : -1;
    }
    if (len == 2) {
      int idx = scanner.indexOfAsciiPair(guardBytes[0] & 0xFF, guardBytes[1] & 0xFF, from, to);
      return (idx >= from && idx < to) ? idx : -1;
    }
    if (len == 3) {
      int idx =
          scanner.indexOfAsciiTriple(
              guardBytes[0] & 0xFF, guardBytes[1] & 0xFF, guardBytes[2] & 0xFF, from, to);
      return (idx >= from && idx < to) ? idx : -1;
    }
    for (int i = from; i < to; i++) {
      int b = scanner.asciiAt(i);
      for (byte gb : guardBytes) {
        if (b == (gb & 0xFF)) {
          return i;
        }
      }
    }
    return -1;
  }

  static int findLastGuardByte(byte[] guardBytes, String text, int minLimit, int fromIndex) {
    if (guardBytes == null || fromIndex < minLimit) {
      return -1;
    }
    int len = guardBytes.length;
    if (len == 1) {
      int idx = text.lastIndexOf((char) guardBytes[0], fromIndex);
      return (idx >= minLimit) ? idx : -1;
    }
    if (len == 2) {
      int i0 = text.lastIndexOf((char) guardBytes[0], fromIndex);
      int i1 = text.lastIndexOf((char) guardBytes[1], fromIndex);
      int max = -1;
      if (i0 >= minLimit) {
        max = i0;
      }
      if (i1 >= minLimit && i1 > max) {
        max = i1;
      }
      return max;
    }
    if (len == 3) {
      int i0 = text.lastIndexOf((char) guardBytes[0], fromIndex);
      int i1 = text.lastIndexOf((char) guardBytes[1], fromIndex);
      int i2 = text.lastIndexOf((char) guardBytes[2], fromIndex);
      int max = -1;
      if (i0 >= minLimit) {
        max = i0;
      }
      if (i1 >= minLimit && i1 > max) {
        max = i1;
      }
      if (i2 >= minLimit && i2 > max) {
        max = i2;
      }
      return max;
    }
    for (int i = fromIndex; i >= minLimit; i--) {
      char c = text.charAt(i);
      for (byte b : guardBytes) {
        if (c == (char) b) {
          return i;
        }
      }
    }
    return -1;
  }

  static int findLastGuardByte(
      byte[] guardBytes, Utf8InputScanner scanner, int minLimit, int fromIndex) {
    if (guardBytes == null || fromIndex < minLimit) {
      return -1;
    }
    int max = -1;
    for (byte gb : guardBytes) {
      int idx = scanner.lastIndexOfAscii(gb & 0xFF, fromIndex, minLimit);
      if (idx >= minLimit && idx > max) {
        max = idx;
      }
    }
    return max;
  }

  static int scanClassEnd(Gap gap, String text, int fromPos, int maxPos) {
    if (gap.kind() == GapKind.BOUNDED_CLASS_REPEAT) {
      int limit =
          Math.min(
              maxPos, gap.maxLength() == Integer.MAX_VALUE ? maxPos : fromPos + gap.maxLength());
      if (gap.guardBytes() != null) {
        int g = findFirstGuardByte(gap.guardBytes(), text, fromPos, limit);
        if (g >= fromPos && g < limit) {
          limit = g;
        }
        if (gap.isPureComplement()) {
          return limit;
        }
      }
      int cur = fromPos;
      while (cur < limit) {
        int cp = text.codePointAt(cur);
        if (gap.scanInfo() != null && !gap.scanInfo().contains(cp)) {
          break;
        }
        cur += Character.charCount(cp);
      }
      return cur;
    }
    if (gap.kind() == GapKind.SINGLE_LINE_ANY_STAR) {
      int limit =
          Math.min(
              maxPos, gap.maxLength() == Integer.MAX_VALUE ? maxPos : fromPos + gap.maxLength());
      if (gap.guardBytes() != null) {
        int g = findFirstGuardByte(gap.guardBytes(), text, fromPos, limit);
        if (g >= fromPos && g < limit) {
          return g;
        }
        if (gap.isPureComplement()) {
          return limit;
        }
      }
      int cur = fromPos;
      while (cur < limit) {
        int cp = text.codePointAt(cur);
        if (Nfa.isLineTerminator(cp)) {
          break;
        }
        cur += Character.charCount(cp);
      }
      return cur;
    }
    if (gap.maxLength() != Integer.MAX_VALUE) {
      return Math.min(maxPos, fromPos + gap.maxLength());
    }
    return maxPos;
  }

  static int scanClassEnd(Gap gap, Utf8InputScanner scanner, int fromPos, int maxPos) {
    if (gap.kind() == GapKind.BOUNDED_CLASS_REPEAT) {
      int limit =
          Math.min(
              maxPos, gap.maxLength() == Integer.MAX_VALUE ? maxPos : fromPos + gap.maxLength());
      if (gap.guardBytes() != null) {
        int g = findFirstGuardByte(gap.guardBytes(), scanner, fromPos, limit);
        if (g >= fromPos && g < limit) {
          limit = g;
        }
        if (gap.isPureComplement()) {
          return limit;
        }
      }
      int cur = fromPos;
      while (cur < limit) {
        long decoded = scanner.decodeForward(cur);
        int cp = InputScanner.codePoint(decoded);
        int nextPos = InputScanner.position(decoded);
        if (gap.scanInfo() != null && !gap.scanInfo().contains(cp)) {
          break;
        }
        cur = nextPos;
      }
      return cur;
    }
    if (gap.kind() == GapKind.SINGLE_LINE_ANY_STAR) {
      int limit =
          Math.min(
              maxPos, gap.maxLength() == Integer.MAX_VALUE ? maxPos : fromPos + gap.maxLength());
      if (gap.guardBytes() != null) {
        int g = findFirstGuardByte(gap.guardBytes(), scanner, fromPos, limit);
        if (g >= fromPos && g < limit) {
          return g;
        }
        if (gap.isPureComplement()) {
          return limit;
        }
      }
      int cur = fromPos;
      while (cur < limit) {
        long decoded = scanner.decodeForward(cur);
        int cp = InputScanner.codePoint(decoded);
        if (Nfa.isLineTerminator(cp)) {
          break;
        }
        cur = InputScanner.position(decoded);
      }
      return cur;
    }
    if (gap.maxLength() != Integer.MAX_VALUE) {
      return Math.min(maxPos, fromPos + gap.maxLength());
    }
    return maxPos;
  }

  static int matchExecutorFixedForward(Gap gap, String text, int fromPos, int maxPos) {
    if (gap.equals(Gap.EMPTY)) {
      return fromPos;
    }
    if (!gap.isExecutorFixedGap()) {
      return -1;
    }
    int cur = fromPos;
    for (int count = 0; count < gap.minLength(); count++) {
      if (cur >= maxPos) {
        return -1;
      }
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      int cp = text.codePointAt(cur);
      if (gap.scanInfo() != null && !gap.scanInfo().contains(cp)) {
        return -1;
      }
      cur += Character.charCount(cp);
    }
    return cur <= maxPos ? cur : -1;
  }

  static int matchExecutorFixedForward(Gap gap, Utf8InputScanner scanner, int fromPos, int maxPos) {
    if (gap.equals(Gap.EMPTY)) {
      return fromPos;
    }
    if (!gap.isExecutorFixedGap()) {
      return -1;
    }
    int cur = fromPos;
    for (int count = 0; count < gap.minLength(); count++) {
      if (cur >= maxPos) {
        return -1;
      }
      long decoded = scanner.decodeForward(cur);
      int cp = InputScanner.codePoint(decoded);
      if (gap.scanInfo() != null && !gap.scanInfo().contains(cp)) {
        return -1;
      }
      cur = InputScanner.position(decoded);
    }
    return cur <= maxPos ? cur : -1;
  }

  static boolean isAsciiWord(int ch) {
    return (ch >= 'a' && ch <= 'z')
        || (ch >= 'A' && ch <= 'Z')
        || (ch >= '0' && ch <= '9')
        || ch == '_';
  }

  static boolean isWordBoundary(String text, int pos) {
    boolean prev = pos > 0 && isAsciiWord(text.charAt(pos - 1));
    boolean next = pos < text.length() && isAsciiWord(text.charAt(pos));
    return prev != next;
  }

  static boolean isWordBoundary(Utf8InputScanner scanner, int pos) {
    boolean prev = pos > 0 && isAsciiWord(scanner.asciiAt(pos - 1));
    boolean next = pos < scanner.length() && isAsciiWord(scanner.asciiAt(pos));
    return prev != next;
  }

  static boolean isLineStart(String text, int pos) {
    return pos == 0 || text.charAt(pos - 1) == '\n';
  }

  static boolean isLineStart(Utf8InputScanner scanner, int pos) {
    return pos == 0 || scanner.asciiAt(pos - 1) == '\n';
  }

  static boolean isLineEnd(String text, int pos) {
    return pos == text.length()
        || text.charAt(pos) == '\n'
        || (text.charAt(pos) == '\r' && (pos + 1 == text.length() || text.charAt(pos + 1) == '\n'));
  }

  static boolean isLineEnd(Utf8InputScanner scanner, int pos) {
    return pos == scanner.length()
        || scanner.asciiAt(pos) == '\n'
        || (scanner.asciiAt(pos) == '\r'
            && (pos + 1 == scanner.length() || scanner.asciiAt(pos + 1) == '\n'));
  }

  static boolean matchesSlice(Gap gap, String text, int from, int to) {
    if (from > to) {
      return false;
    }
    int len = to - from;
    return switch (gap.kind()) {
      case EMPTY -> len == 0;
      case TEXT_START -> len == 0 && from == 0;
      case TEXT_END -> len == 0 && from == text.length();
      case WORD_BOUNDARY -> len == 0 && isWordBoundary(text, from);
      case NO_WORD_BOUNDARY -> len == 0 && !isWordBoundary(text, from);
      case LINE_START -> len == 0 && isLineStart(text, from);
      case LINE_END -> len == 0 && isLineEnd(text, from);
      case ANY_STAR -> len >= gap.minLength() && len <= gap.maxLength();
      case SINGLE_LINE_ANY_STAR -> {
        if (gap.guardBytes() != null) {
          int g = findFirstGuardByte(gap.guardBytes(), text, from, to);
          if (g >= from && g < to) {
            yield false;
          }
        }
        int count = 0;
        for (int i = from; i < to; ) {
          int cp = text.codePointAt(i);
          if (Nfa.isLineTerminator(cp)) {
            yield false;
          }
          count++;
          i += Character.charCount(cp);
        }
        yield count >= gap.minLength() && count <= gap.maxLength();
      }
      case BOUNDED_CLASS_REPEAT -> {
        if (gap.guardBytes() != null) {
          int g = findFirstGuardByte(gap.guardBytes(), text, from, to);
          if (g >= from && g < to) {
            yield false;
          }
          if (gap.isPureComplement()) {
            if (len < gap.minLength()) {
              yield false;
            }
            if (gap.maxLength() == Integer.MAX_VALUE && gap.minLength() == 0) {
              yield true;
            }
            int count = Character.codePointCount(text, from, to);
            yield count >= gap.minLength() && count <= gap.maxLength();
          }
        }
        int count = 0;
        for (int i = from; i < to; ) {
          int cp = text.codePointAt(i);
          if (gap.scanInfo() != null) {
            if (!gap.scanInfo().contains(cp)) {
              yield false;
            }
          } else if (gap.charClass() != null) {
            if (!gap.charClass().contains(cp)) {
              yield false;
            }
          }
          count++;
          i += Character.charCount(cp);
        }
        yield count >= gap.minLength() && count <= gap.maxLength();
      }
    };
  }

  static boolean matchesSlice(Gap gap, Utf8InputScanner scanner, int from, int to) {
    if (from > to) {
      return false;
    }
    int len = to - from;
    return switch (gap.kind()) {
      case EMPTY -> len == 0;
      case TEXT_START -> len == 0 && from == 0;
      case TEXT_END -> len == 0 && from == scanner.length();
      case WORD_BOUNDARY -> len == 0 && isWordBoundary(scanner, from);
      case NO_WORD_BOUNDARY -> len == 0 && !isWordBoundary(scanner, from);
      case LINE_START -> len == 0 && isLineStart(scanner, from);
      case LINE_END -> len == 0 && isLineEnd(scanner, from);
      case ANY_STAR -> len >= gap.minLength() && len <= gap.maxLength();
      case SINGLE_LINE_ANY_STAR -> {
        if (gap.guardBytes() != null) {
          int g = findFirstGuardByte(gap.guardBytes(), scanner, from, to);
          if (g >= from && g < to) {
            yield false;
          }
        }
        int count = 0;
        for (int i = from; i < to; ) {
          long decoded = scanner.decodeForward(i);
          int cp = InputScanner.codePoint(decoded);
          if (Nfa.isLineTerminator(cp)) {
            yield false;
          }
          count++;
          i = InputScanner.position(decoded);
        }
        yield count >= gap.minLength() && count <= gap.maxLength();
      }
      case BOUNDED_CLASS_REPEAT -> {
        if (gap.guardBytes() != null) {
          int g = findFirstGuardByte(gap.guardBytes(), scanner, from, to);
          if (g >= from && g < to) {
            yield false;
          }
          if (gap.isPureComplement()) {
            if (len < gap.minLength()) {
              yield false;
            }
            if (gap.maxLength() == Integer.MAX_VALUE && gap.minLength() == 0) {
              yield true;
            }
            int count = 0;
            for (int i = from; i < to; ) {
              long decoded = scanner.decodeForward(i);
              count++;
              i = InputScanner.position(decoded);
            }
            yield count >= gap.minLength() && count <= gap.maxLength();
          }
        }
        int count = 0;
        for (int i = from; i < to; ) {
          long decoded = scanner.decodeForward(i);
          int cp = InputScanner.codePoint(decoded);
          if (gap.scanInfo() != null) {
            if (!gap.scanInfo().contains(cp)) {
              yield false;
            }
          } else if (gap.charClass() != null) {
            if (!gap.charClass().contains(cp)) {
              yield false;
            }
          }
          count++;
          i = InputScanner.position(decoded);
        }
        yield count >= gap.minLength() && count <= gap.maxLength();
      }
    };
  }

  static int expandLeading(Gap gap, String text, int anchorPos, int minPos) {
    return switch (gap.kind()) {
      case EMPTY -> anchorPos;
      case TEXT_START -> anchorPos == 0 ? 0 : -1;
      case TEXT_END -> anchorPos == text.length() ? anchorPos : -1;
      case WORD_BOUNDARY -> isWordBoundary(text, anchorPos) ? anchorPos : -1;
      case NO_WORD_BOUNDARY -> !isWordBoundary(text, anchorPos) ? anchorPos : -1;
      case LINE_START -> isLineStart(text, anchorPos) ? anchorPos : -1;
      case LINE_END -> isLineEnd(text, anchorPos) ? anchorPos : -1;
      case BOUNDED_CLASS_REPEAT -> {
        int count = 0;
        int cur = anchorPos;
        int minMatchPos = -1;
        if (gap.minLength() == 0) {
          minMatchPos = cur;
        }
        while (count < gap.maxLength() && cur > minPos) {
          int cp = text.codePointBefore(cur);
          int prevPos = cur - Character.charCount(cp);
          if (prevPos < minPos) {
            break;
          }
          if (gap.scanInfo() != null) {
            if (!gap.scanInfo().contains(cp)) {
              break;
            }
          } else if (gap.charClass() != null) {
            if (!gap.charClass().contains(cp)) {
              break;
            }
          }
          cur = prevPos;
          count++;
          if (count == gap.minLength()) {
            minMatchPos = cur;
          }
        }
        if (count < gap.minLength()) {
          yield -1;
        }
        yield gap.isGreedy() ? cur : minMatchPos;
      }
      case SINGLE_LINE_ANY_STAR -> expandLeadingWildcard(gap, text, anchorPos, minPos, true);
      case ANY_STAR -> expandLeadingWildcard(gap, text, anchorPos, minPos, false);
    };
  }

  static int expandLeading(Gap gap, Utf8InputScanner scanner, int anchorPos, int minPos) {
    return switch (gap.kind()) {
      case EMPTY -> anchorPos;
      case TEXT_START -> anchorPos == 0 ? 0 : -1;
      case TEXT_END -> anchorPos == scanner.length() ? anchorPos : -1;
      case WORD_BOUNDARY -> isWordBoundary(scanner, anchorPos) ? anchorPos : -1;
      case NO_WORD_BOUNDARY -> !isWordBoundary(scanner, anchorPos) ? anchorPos : -1;
      case LINE_START -> isLineStart(scanner, anchorPos) ? anchorPos : -1;
      case LINE_END -> isLineEnd(scanner, anchorPos) ? anchorPos : -1;
      case BOUNDED_CLASS_REPEAT -> {
        int count = 0;
        int cur = anchorPos;
        int minMatchPos = -1;
        if (gap.minLength() == 0) {
          minMatchPos = cur;
        }
        while (count < gap.maxLength() && cur > minPos) {
          long decoded = scanner.decodeBackward(cur);
          int cp = InputScanner.codePoint(decoded);
          int prevPos = InputScanner.position(decoded);
          if (prevPos < minPos) {
            break;
          }
          if (gap.scanInfo() != null) {
            if (!gap.scanInfo().contains(cp)) {
              break;
            }
          } else if (gap.charClass() != null) {
            if (!gap.charClass().contains(cp)) {
              break;
            }
          }
          cur = prevPos;
          count++;
          if (count == gap.minLength()) {
            minMatchPos = cur;
          }
        }
        if (count < gap.minLength()) {
          yield -1;
        }
        yield gap.isGreedy() ? cur : minMatchPos;
      }
      case SINGLE_LINE_ANY_STAR -> expandLeadingWildcard(gap, scanner, anchorPos, minPos, true);
      case ANY_STAR -> expandLeadingWildcard(gap, scanner, anchorPos, minPos, false);
    };
  }

  static int expandTrailing(Gap gap, String text, int fromPos, int maxPos) {
    return switch (gap.kind()) {
      case EMPTY -> fromPos;
      case TEXT_START -> fromPos == 0 ? 0 : -1;
      case TEXT_END -> fromPos == text.length() ? fromPos : -1;
      case WORD_BOUNDARY -> isWordBoundary(text, fromPos) ? fromPos : -1;
      case NO_WORD_BOUNDARY -> !isWordBoundary(text, fromPos) ? fromPos : -1;
      case LINE_START -> isLineStart(text, fromPos) ? fromPos : -1;
      case LINE_END -> isLineEnd(text, fromPos) ? fromPos : -1;
      case BOUNDED_CLASS_REPEAT -> {
        if (gap.guardBytes() != null && gap.isPureComplement()) {
          int limit =
              Math.min(
                  maxPos,
                  gap.maxLength() == Integer.MAX_VALUE ? maxPos : fromPos + gap.maxLength());
          int g = findFirstGuardByte(gap.guardBytes(), text, fromPos, limit);
          int end = (g >= fromPos && g < limit) ? g : limit;
          int count = Character.codePointCount(text, fromPos, end);
          if (count < gap.minLength()) {
            yield -1;
          }
          if (!gap.isGreedy()) {
            int cur = fromPos;
            for (int c = 0; c < gap.minLength(); c++) {
              cur += Character.charCount(text.codePointAt(cur));
            }
            yield cur;
          }
          yield end;
        }
        int count = 0;
        int cur = fromPos;
        int minMatchPos = -1;
        if (gap.minLength() == 0) {
          minMatchPos = cur;
        }
        while (count < gap.maxLength() && cur < maxPos) {
          int cp = text.codePointAt(cur);
          if (gap.scanInfo() != null) {
            if (!gap.scanInfo().contains(cp)) {
              break;
            }
          } else if (gap.charClass() != null) {
            if (!gap.charClass().contains(cp)) {
              break;
            }
          }
          cur += Character.charCount(cp);
          count++;
          if (count == gap.minLength()) {
            minMatchPos = cur;
          }
        }
        if (count < gap.minLength()) {
          yield -1;
        }
        yield gap.isGreedy() ? cur : minMatchPos;
      }
      case SINGLE_LINE_ANY_STAR -> expandTrailingWildcard(gap, text, fromPos, maxPos, true);
      case ANY_STAR -> expandTrailingWildcard(gap, text, fromPos, maxPos, false);
    };
  }

  static int expandTrailing(Gap gap, Utf8InputScanner scanner, int fromPos, int maxPos) {
    return switch (gap.kind()) {
      case EMPTY -> fromPos;
      case TEXT_START -> fromPos == 0 ? 0 : -1;
      case TEXT_END -> fromPos == scanner.length() ? fromPos : -1;
      case WORD_BOUNDARY -> isWordBoundary(scanner, fromPos) ? fromPos : -1;
      case NO_WORD_BOUNDARY -> !isWordBoundary(scanner, fromPos) ? fromPos : -1;
      case LINE_START -> isLineStart(scanner, fromPos) ? fromPos : -1;
      case LINE_END -> isLineEnd(scanner, fromPos) ? fromPos : -1;
      case BOUNDED_CLASS_REPEAT -> {
        if (gap.guardBytes() != null && gap.isPureComplement()) {
          int limit =
              Math.min(
                  maxPos,
                  gap.maxLength() == Integer.MAX_VALUE ? maxPos : fromPos + gap.maxLength());
          int g = findFirstGuardByte(gap.guardBytes(), scanner, fromPos, limit);
          int end = (g >= fromPos && g < limit) ? g : limit;
          int count = 0;
          int minMatchPos = -1;
          if (gap.minLength() == 0) {
            minMatchPos = fromPos;
          }
          for (int p = fromPos; p < end; ) {
            long decoded = scanner.decodeForward(p);
            p = InputScanner.position(decoded);
            count++;
            if (count == gap.minLength()) {
              minMatchPos = p;
              if (!gap.isGreedy()) {
                break;
              }
            }
          }
          if (count < gap.minLength()) {
            yield -1;
          }
          yield gap.isGreedy() ? end : minMatchPos;
        }
        int count = 0;
        int cur = fromPos;
        int minMatchPos = -1;
        if (gap.minLength() == 0) {
          minMatchPos = cur;
        }
        while (count < gap.maxLength() && cur < maxPos) {
          long decoded = scanner.decodeForward(cur);
          int cp = InputScanner.codePoint(decoded);
          int nextPos = InputScanner.position(decoded);
          if (gap.scanInfo() != null) {
            if (!gap.scanInfo().contains(cp)) {
              break;
            }
          } else if (gap.charClass() != null) {
            if (!gap.charClass().contains(cp)) {
              break;
            }
          }
          cur = nextPos;
          count++;
          if (count == gap.minLength()) {
            minMatchPos = cur;
          }
        }
        if (count < gap.minLength()) {
          yield -1;
        }
        yield gap.isGreedy() ? cur : minMatchPos;
      }
      case SINGLE_LINE_ANY_STAR -> expandTrailingWildcard(gap, scanner, fromPos, maxPos, true);
      case ANY_STAR -> expandTrailingWildcard(gap, scanner, fromPos, maxPos, false);
    };
  }

  private static int expandLeadingWildcard(
      Gap gap, String text, int anchorPos, int minPos, boolean stopAtLineTerminator) {
    int count = 0;
    int cur = anchorPos;
    int minMatchPos = gap.minLength() == 0 ? cur : -1;
    while (count < gap.maxLength() && cur > minPos) {
      int cp = text.codePointBefore(cur);
      if (stopAtLineTerminator && Nfa.isLineTerminator(cp)) {
        break;
      }
      cur -= Character.charCount(cp);
      count++;
      if (count == gap.minLength()) {
        minMatchPos = cur;
      }
    }
    if (count < gap.minLength()) {
      return -1;
    }
    return gap.isGreedy() ? cur : minMatchPos;
  }

  private static int expandLeadingWildcard(
      Gap gap, Utf8InputScanner scanner, int anchorPos, int minPos, boolean stopAtLineTerminator) {
    int count = 0;
    int cur = anchorPos;
    int minMatchPos = gap.minLength() == 0 ? cur : -1;
    while (count < gap.maxLength() && cur > minPos) {
      long decoded = scanner.decodeBackward(cur);
      int cp = InputScanner.codePoint(decoded);
      if (stopAtLineTerminator && Nfa.isLineTerminator(cp)) {
        break;
      }
      cur = InputScanner.position(decoded);
      count++;
      if (count == gap.minLength()) {
        minMatchPos = cur;
      }
    }
    if (count < gap.minLength()) {
      return -1;
    }
    return gap.isGreedy() ? cur : minMatchPos;
  }

  private static int expandTrailingWildcard(
      Gap gap, String text, int fromPos, int maxPos, boolean stopAtLineTerminator) {
    int count = 0;
    int cur = fromPos;
    int minMatchPos = gap.minLength() == 0 ? cur : -1;
    while (count < gap.maxLength() && cur < maxPos) {
      int cp = text.codePointAt(cur);
      if (stopAtLineTerminator && Nfa.isLineTerminator(cp)) {
        break;
      }
      cur += Character.charCount(cp);
      count++;
      if (count == gap.minLength()) {
        minMatchPos = cur;
      }
    }
    if (count < gap.minLength()) {
      return -1;
    }
    return gap.isGreedy() ? cur : minMatchPos;
  }

  private static int expandTrailingWildcard(
      Gap gap, Utf8InputScanner scanner, int fromPos, int maxPos, boolean stopAtLineTerminator) {
    int count = 0;
    int cur = fromPos;
    int minMatchPos = gap.minLength() == 0 ? cur : -1;
    while (count < gap.maxLength() && cur < maxPos) {
      long decoded = scanner.decodeForward(cur);
      int cp = InputScanner.codePoint(decoded);
      if (stopAtLineTerminator && Nfa.isLineTerminator(cp)) {
        break;
      }
      cur = InputScanner.position(decoded);
      count++;
      if (count == gap.minLength()) {
        minMatchPos = cur;
      }
    }
    if (count < gap.minLength()) {
      return -1;
    }
    return gap.isGreedy() ? cur : minMatchPos;
  }
}
