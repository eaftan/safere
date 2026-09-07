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
    if (len == 1 && !WorkCounterConfig.ENABLED) {
      return text.indexOf((char) guardBytes[0], from, to);
    }
    if (len == 2) {
      char g0 = (char) guardBytes[0];
      char g1 = (char) guardBytes[1];
      for (int i = from; i < to; i++) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        char c = text.charAt(i);
        if (c == g0 || c == g1) {
          return i;
        }
      }
      return -1;
    }
    if (len == 3) {
      char g0 = (char) guardBytes[0];
      char g1 = (char) guardBytes[1];
      char g2 = (char) guardBytes[2];
      for (int i = from; i < to; i++) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        char c = text.charAt(i);
        if (c == g0 || c == g1 || c == g2) {
          return i;
        }
      }
      return -1;
    }
    for (int i = from; i < to; i++) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
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
      char g0 = (char) guardBytes[0];
      for (int i = fromIndex; i >= minLimit; i--) {
        if (text.charAt(i) == g0) {
          return i;
        }
      }
      return -1;
    }
    if (len == 2) {
      char g0 = (char) guardBytes[0];
      char g1 = (char) guardBytes[1];
      for (int i = fromIndex; i >= minLimit; i--) {
        char c = text.charAt(i);
        if (c == g0 || c == g1) {
          return i;
        }
      }
      return -1;
    }
    if (len == 3) {
      char g0 = (char) guardBytes[0];
      char g1 = (char) guardBytes[1];
      char g2 = (char) guardBytes[2];
      for (int i = fromIndex; i >= minLimit; i--) {
        char c = text.charAt(i);
        if (c == g0 || c == g1 || c == g2) {
          return i;
        }
      }
      return -1;
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

  private static int boundedCodePointEnd(Gap gap, String text, int fromPos, int maxPos) {
    if (gap.maxLength() == Integer.MAX_VALUE) {
      return maxPos;
    }
    int cur = fromPos;
    for (int count = 0; count < gap.maxLength() && cur < maxPos; count++) {
      int width = Character.charCount(text.codePointAt(cur));
      if (cur + width > maxPos) {
        break;
      }
      cur += width;
    }
    return cur;
  }

  private static int boundedCodePointEnd(
      Gap gap, Utf8InputScanner scanner, int fromPos, int maxPos) {
    if (gap.maxLength() == Integer.MAX_VALUE) {
      return maxPos;
    }
    int cur = fromPos;
    for (int count = 0; count < gap.maxLength() && cur < maxPos; count++) {
      long decoded = scanner.decodeForward(cur);
      int next = InputScanner.position(decoded);
      if (next > maxPos) {
        break;
      }
      cur = next;
    }
    return cur;
  }

  static int scanClassEnd(Gap gap, String text, int fromPos, int maxPos) {
    if (gap.kind() == GapKind.BOUNDED_CLASS_REPEAT) {
      int limit = boundedCodePointEnd(gap, text, fromPos, maxPos);
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
      int count = 0;
      while (cur < limit && count < gap.maxLength()) {
        int cp = text.codePointAt(cur);
        if (gap.scanInfo() != null && !gap.scanInfo().contains(cp)) {
          break;
        }
        count++;
        cur += Character.charCount(cp);
      }
      return cur;
    }
    if (gap.kind() == GapKind.SINGLE_LINE_ANY_STAR) {
      int maxCharDistance =
          gap.maxLength() == Integer.MAX_VALUE
              ? maxPos - fromPos
              : (int) Math.min(maxPos - fromPos, (long) gap.maxLength() * 2);
      int limit = fromPos + maxCharDistance;
      if (gap.guardBytes() != null) {
        int g = findFirstGuardByte(gap.guardBytes(), text, fromPos, limit);
        if (g >= fromPos && g < limit) {
          limit = g;
        }
        if (gap.guardBytes().length == 1 && gap.isPureComplement()) {
          return limit;
        }
      }
      int cur = fromPos;
      int count = 0;
      while (cur < limit && count < gap.maxLength()) {
        int cp = text.codePointAt(cur);
        if (isTerminator(gap, cp)) {
          break;
        }
        count++;
        cur += Character.charCount(cp);
      }
      return cur;
    }
    if (gap.maxLength() != Integer.MAX_VALUE) {
      return (int) Math.min(maxPos, fromPos + (long) gap.maxLength() * 2);
    }
    return maxPos;
  }

  static int scanClassEnd(Gap gap, Utf8InputScanner scanner, int fromPos, int maxPos) {
    if (gap.kind() == GapKind.BOUNDED_CLASS_REPEAT) {
      int limit = boundedCodePointEnd(gap, scanner, fromPos, maxPos);
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
      int count = 0;
      while (cur < limit && count < gap.maxLength()) {
        long decoded = scanner.decodeForward(cur);
        int cp = InputScanner.codePoint(decoded);
        int nextPos = InputScanner.position(decoded);
        if (gap.scanInfo() != null && !gap.scanInfo().contains(cp)) {
          break;
        }
        count++;
        cur = nextPos;
      }
      return cur;
    }
    if (gap.kind() == GapKind.SINGLE_LINE_ANY_STAR) {
      int maxByteDistance =
          gap.maxLength() == Integer.MAX_VALUE
              ? maxPos - fromPos
              : (int) Math.min(maxPos - fromPos, (long) gap.maxLength() * 4);
      int limit = fromPos + maxByteDistance;
      if (gap.guardBytes() != null) {
        int g = findFirstGuardByte(gap.guardBytes(), scanner, fromPos, limit);
        if (g >= fromPos && g < limit) {
          limit = g;
        }
        if (gap.guardBytes().length == 1 && gap.isPureComplement()) {
          return limit;
        }
      }
      int cur = fromPos;
      int count = 0;
      while (cur < limit && count < gap.maxLength()) {
        long decoded = scanner.decodeForward(cur);
        int cp = InputScanner.codePoint(decoded);
        if (isTerminator(gap, cp)) {
          break;
        }
        count++;
        cur = InputScanner.position(decoded);
      }
      return cur;
    }
    if (gap.maxLength() != Integer.MAX_VALUE) {
      return (int) Math.min(maxPos, fromPos + (long) gap.maxLength() * 4);
    }
    return maxPos;
  }

  static int scanClassStart(Gap gap, String text, int minLimit, int curAnchorStart) {
    if (curAnchorStart <= minLimit) {
      return curAnchorStart;
    }
    int maxCharDistance =
        gap.maxLength() == Integer.MAX_VALUE
            ? curAnchorStart - minLimit
            : (int) Math.min(curAnchorStart - minLimit, (long) gap.maxLength() * 2);
    int minPossible = curAnchorStart - maxCharDistance;
    if (gap.guardBytes() != null) {
      int lastGuard = findLastGuardByte(gap.guardBytes(), text, minPossible, curAnchorStart - 1);
      if (lastGuard >= 0) {
        minPossible = Math.max(minPossible, lastGuard + 1);
      }
      if (gap.isPureComplement()) {
        return minPossible;
      }
    }
    if (gap.kind() == GapKind.BOUNDED_CLASS_REPEAT) {
      int cur = curAnchorStart;
      int count = 0;
      while (cur > minPossible && count < gap.maxLength()) {
        int cp = text.codePointBefore(cur);
        int prev = cur - Character.charCount(cp);
        if (prev < minPossible) {
          break;
        }
        if (gap.scanInfo() != null) {
          if (!gap.scanInfo().contains(cp)) {
            return cur;
          }
        } else if (gap.charClass() != null) {
          if (!gap.charClass().contains(cp)) {
            return cur;
          }
        }
        count++;
        cur = prev;
      }
      return cur;
    }
    if (gap.kind() == GapKind.SINGLE_LINE_ANY_STAR) {
      int cur = curAnchorStart;
      int count = 0;
      while (cur > minPossible && count < gap.maxLength()) {
        int cp = text.codePointBefore(cur);
        int prev = cur - Character.charCount(cp);
        if (prev < minPossible) {
          break;
        }
        if (isTerminator(gap, cp)) {
          return cur;
        }
        count++;
        cur = prev;
      }
      return cur;
    }
    return minPossible;
  }

  static int scanClassStart(Gap gap, Utf8InputScanner scanner, int minLimit, int curAnchorStart) {
    if (curAnchorStart <= minLimit) {
      return curAnchorStart;
    }
    int maxByteDistance =
        gap.maxLength() == Integer.MAX_VALUE
            ? curAnchorStart - minLimit
            : (int) Math.min(curAnchorStart - minLimit, (long) gap.maxLength() * 4);
    int minPossible = curAnchorStart - maxByteDistance;
    if (gap.guardBytes() != null) {
      int lastGuard = findLastGuardByte(gap.guardBytes(), scanner, minPossible, curAnchorStart - 1);
      if (lastGuard >= 0) {
        minPossible = Math.max(minPossible, lastGuard + 1);
      }
      if (gap.isPureComplement()) {
        return minPossible;
      }
    }
    if (gap.kind() == GapKind.BOUNDED_CLASS_REPEAT) {
      int cur = curAnchorStart;
      int count = 0;
      while (cur > minPossible && count < gap.maxLength()) {
        long decoded = scanner.decodeBackward(cur);
        int cp = InputScanner.codePoint(decoded);
        int prev = InputScanner.position(decoded);
        if (prev < minPossible) {
          break;
        }
        if (gap.scanInfo() != null) {
          if (!gap.scanInfo().contains(cp)) {
            return cur;
          }
        } else if (gap.charClass() != null) {
          if (!gap.charClass().contains(cp)) {
            return cur;
          }
        }
        count++;
        cur = prev;
      }
      return cur;
    }
    if (gap.kind() == GapKind.SINGLE_LINE_ANY_STAR) {
      int cur = curAnchorStart;
      int count = 0;
      while (cur > minPossible && count < gap.maxLength()) {
        long decoded = scanner.decodeBackward(cur);
        int cp = InputScanner.codePoint(decoded);
        int prev = InputScanner.position(decoded);
        if (prev < minPossible) {
          break;
        }
        if (isTerminator(gap, cp)) {
          return cur;
        }
        count++;
        cur = prev;
      }
      return cur;
    }
    return minPossible;
  }

  static int matchExecutorFixedForward(Gap gap, String text, int fromPos, int maxPos) {
    if (gap.equals(Gap.EMPTY)) {
      return fromPos;
    }
    if (!gap.isExecutorFixedGap()) {
      return -1;
    }
    if (gap.kind() == GapKind.COMPOUND_SEQUENCE) {
      CharClassScanInfo[] seq = gap.classSequence();
      if (seq == null) {
        return -1;
      }
      int cur = fromPos;
      for (int i = 0; i < seq.length; i++) {
        if (cur >= maxPos) {
          return -1;
        }
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        int cp = text.codePointAt(cur);
        if (seq[i] != null && !seq[i].contains(cp)) {
          return -1;
        }
        cur += Character.charCount(cp);
      }
      return cur <= maxPos ? cur : -1;
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
    if (gap.kind() == GapKind.COMPOUND_SEQUENCE) {
      CharClassScanInfo[] seq = gap.classSequence();
      if (seq == null) {
        return -1;
      }
      int cur = fromPos;
      for (int i = 0; i < seq.length; i++) {
        if (cur >= maxPos) {
          return -1;
        }
        long decoded = scanner.decodeForward(cur);
        int cp = InputScanner.codePoint(decoded);
        if (seq[i] != null && !seq[i].contains(cp)) {
          return -1;
        }
        cur = InputScanner.position(decoded);
      }
      return cur <= maxPos ? cur : -1;
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
          if (gap.guardBytes().length == 1 && gap.isPureComplement()) {
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
          if (isTerminator(gap, cp)) {
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
      case COMPOUND_SEQUENCE -> {
        CharClassScanInfo[] seq = gap.classSequence();
        if (seq == null) {
          yield false;
        }
        int cur = from;
        for (int i = 0; i < seq.length; i++) {
          if (cur >= to) {
            yield false;
          }
          int cp = text.codePointAt(cur);
          if (seq[i] != null && !seq[i].contains(cp)) {
            yield false;
          }
          cur += Character.charCount(cp);
        }
        yield cur == to;
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
          if (gap.guardBytes().length == 1 && gap.isPureComplement()) {
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
          if (isTerminator(gap, cp)) {
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
      case COMPOUND_SEQUENCE -> {
        CharClassScanInfo[] seq = gap.classSequence();
        if (seq == null) {
          yield false;
        }
        int cur = from;
        for (int i = 0; i < seq.length; i++) {
          if (cur >= to) {
            yield false;
          }
          long decoded = scanner.decodeForward(cur);
          int cp = InputScanner.codePoint(decoded);
          if (seq[i] != null && !seq[i].contains(cp)) {
            yield false;
          }
          cur = InputScanner.position(decoded);
        }
        yield cur == to;
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
      case COMPOUND_SEQUENCE -> {
        CharClassScanInfo[] seq = gap.classSequence();
        if (seq == null) {
          yield -1;
        }
        int cur = anchorPos;
        for (int i = seq.length - 1; i >= 0; i--) {
          if (cur <= minPos) {
            yield -1;
          }
          int cp = text.codePointBefore(cur);
          if (seq[i] != null && !seq[i].contains(cp)) {
            yield -1;
          }
          cur -= Character.charCount(cp);
        }
        yield cur >= minPos ? cur : -1;
      }
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
      case COMPOUND_SEQUENCE -> {
        CharClassScanInfo[] seq = gap.classSequence();
        if (seq == null) {
          yield -1;
        }
        int cur = anchorPos;
        for (int i = seq.length - 1; i >= 0; i--) {
          if (cur <= minPos) {
            yield -1;
          }
          long decoded = scanner.decodeBackward(cur);
          int cp = InputScanner.codePoint(decoded);
          if (seq[i] != null && !seq[i].contains(cp)) {
            yield -1;
          }
          cur = InputScanner.position(decoded);
        }
        yield cur >= minPos ? cur : -1;
      }
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
          if (!gap.isGreedy()) {
            int cur = fromPos;
            for (int count = 0; count < gap.minLength(); count++) {
              if (cur >= maxPos) {
                yield -1;
              }
              int cp = text.codePointAt(cur);
              if (gap.scanInfo() != null && !gap.scanInfo().contains(cp)) {
                yield -1;
              }
              cur += Character.charCount(cp);
            }
            yield cur <= maxPos ? cur : -1;
          }
          int limit = boundedCodePointEnd(gap, text, fromPos, maxPos);
          int g = findFirstGuardByte(gap.guardBytes(), text, fromPos, limit);
          int end = (g >= fromPos && g < limit) ? g : limit;
          if (gap.minLength() > 0) {
            int count = Character.codePointCount(text, fromPos, end);
            if (count < gap.minLength()) {
              yield -1;
            }
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
      case COMPOUND_SEQUENCE -> matchExecutorFixedForward(gap, text, fromPos, maxPos);
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
          if (!gap.isGreedy()) {
            int cur = fromPos;
            for (int count = 0; count < gap.minLength(); count++) {
              if (cur >= maxPos) {
                yield -1;
              }
              long decoded = scanner.decodeForward(cur);
              int cp = InputScanner.codePoint(decoded);
              if (gap.scanInfo() != null && !gap.scanInfo().contains(cp)) {
                yield -1;
              }
              cur = InputScanner.position(decoded);
            }
            yield cur <= maxPos ? cur : -1;
          }
          int limit = boundedCodePointEnd(gap, scanner, fromPos, maxPos);
          int g = findFirstGuardByte(gap.guardBytes(), scanner, fromPos, limit);
          int end = (g >= fromPos && g < limit) ? g : limit;
          if (gap.minLength() > 0) {
            int count = 0;
            for (int p = fromPos; p < end; ) {
              long decoded = scanner.decodeForward(p);
              p = InputScanner.position(decoded);
              count++;
            }
            if (count < gap.minLength()) {
              yield -1;
            }
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
      case COMPOUND_SEQUENCE -> matchExecutorFixedForward(gap, scanner, fromPos, maxPos);
    };
  }

  private static int expandLeadingWildcard(
      Gap gap, String text, int anchorPos, int minPos, boolean stopAtLineTerminator) {
    int count = 0;
    int cur = anchorPos;
    int minMatchPos = gap.minLength() == 0 ? cur : -1;
    while (count < gap.maxLength() && cur > minPos) {
      int cp = text.codePointBefore(cur);
      if (stopAtLineTerminator && isTerminator(gap, cp)) {
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
      if (stopAtLineTerminator && isTerminator(gap, cp)) {
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
      if (stopAtLineTerminator && isTerminator(gap, cp)) {
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
      if (stopAtLineTerminator && isTerminator(gap, cp)) {
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

  private static boolean isTerminator(Gap gap, int cp) {
    return (gap.guardBytes() != null && gap.guardBytes().length == 1 && gap.isPureComplement())
        ? (cp == '\n')
        : Nfa.isLineTerminator(cp);
  }
}
