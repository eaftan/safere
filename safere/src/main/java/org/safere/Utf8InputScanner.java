// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.lang.invoke.MethodHandles.byteArrayViewVarHandle;
import static java.nio.ByteOrder.nativeOrder;
import static java.util.Objects.requireNonNull;

import java.lang.invoke.VarHandle;

final class Utf8InputScanner implements InputScanner {
  private static final int REPLACEMENT_CHARACTER = 0xFFFD;
  private static final long BYTE_ONES = 0x0101_0101_0101_0101L;
  private static final long BYTE_HIGH_BITS = 0x8080_8080_8080_8080L;
  private static final VarHandle LONG_VIEW = byteArrayViewVarHandle(long[].class, nativeOrder());

  private final byte[] bytes;
  private final int offset;
  private final int length;

  Utf8InputScanner(byte[] bytes) {
    this(bytes, 0, bytes.length);
  }

  Utf8InputScanner(byte[] bytes, int offset, int length) {
    this.bytes = requireNonNull(bytes, "bytes");
    if (offset < 0 || length < 0 || offset > bytes.length - length) {
      throw new IndexOutOfBoundsException(
          "offset=" + offset + ", length=" + length + ", arrayLength=" + bytes.length);
    }
    this.offset = offset;
    this.length = length;
  }

  static void validate(byte[] bytes, int offset, int length) {
    Utf8InputScanner scanner = new Utf8InputScanner(bytes, offset, length);
    int position = 0;
    while (position < length) {
      long decoded = scanner.decodeForward(position);
      int next = InputScanner.position(decoded);
      if (InputScanner.codePoint(decoded) == REPLACEMENT_CHARACTER
          && next == position + 1
          && scanner.unsignedByteAt(position) >= 0x80) {
        throw new IllegalArgumentException("Malformed UTF-8 at byte " + position);
      }
      position = next;
    }
  }

  @Override
  public int length() {
    return length;
  }

  @Override
  public int asciiAt(int pos) {
    int value = unsignedByteAt(pos);
    return value < 0x80 ? value : -1;
  }

  int indexOf(byte[] literal, int[] failure, int[] shifts) {
    return indexOf(literal, failure, shifts, 0);
  }

  int indexOf(byte[] literal, int[] failure, int[] shifts, int start) {
    if (literal.length == 0) {
      return start;
    }
    if (literal.length == 1 && !WorkCounterConfig.ENABLED) {
      return indexOfByte(literal[0], start);
    }
    if (!WorkCounterConfig.ENABLED && shifts != null) {
      int result = boundedBoyerMooreHorspool(literal, shifts, start);
      if (result >= -1) {
        return result;
      }
    }
    int matched = 0;
    for (int position = start; position < length; position++) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      byte current = bytes[offset + position];
      while (matched > 0 && current != literal[matched]) {
        matched = failure[matched - 1];
      }
      if (current == literal[matched]) {
        matched++;
        if (matched == literal.length) {
          return position - literal.length + 1;
        }
      }
    }
    return -1;
  }

  int indexOfAsciiClass(boolean[] asciiClass, int start) {
    int first = -1;
    int second = -1;
    for (int value = 0; value < asciiClass.length; value++) {
      if (asciiClass[value]) {
        if (first < 0) {
          first = value;
        } else if (second < 0) {
          second = value;
        } else {
          return indexOfAsciiClassScalar(asciiClass, start);
        }
      }
    }
    if (first < 0) {
      return -1;
    }
    if (WorkCounterConfig.ENABLED) {
      return indexOfAsciiClassScalar(asciiClass, start);
    }
    if (second < 0) {
      return indexOfByte((byte) first, start);
    }
    return indexOfBytePair((byte) first, (byte) second, start);
  }

  private int boundedBoyerMooreHorspool(byte[] literal, int[] shifts, int start) {
    int last = literal.length - 1;
    int position = start + last;
    int work = 0;
    int workLimit = Math.max(1, (length - start) * 2);
    while (position < length && work < workLimit) {
      int literalPosition = last;
      int inputPosition = position;
      while (literalPosition >= 0
          && bytes[offset + inputPosition] == literal[literalPosition]
          && work++ < workLimit) {
        literalPosition--;
        inputPosition--;
      }
      if (literalPosition < 0) {
        return inputPosition + 1;
      }
      position += shifts[bytes[offset + position] & 0xFF];
      work++;
    }
    return position >= length ? -1 : -2;
  }

  private int indexOfByte(byte target, int start) {
    int position = start;
    int wordEnd = length - Long.BYTES;
    long repeatedTarget = (target & 0xFFL) * BYTE_ONES;
    while (position <= wordEnd) {
      long difference = (long) LONG_VIEW.get(bytes, offset + position) ^ repeatedTarget;
      if (((difference - BYTE_ONES) & ~difference & BYTE_HIGH_BITS) != 0) {
        for (int index = 0; index < Long.BYTES; index++) {
          if (bytes[offset + position + index] == target) {
            return position + index;
          }
        }
      }
      position += Long.BYTES;
    }
    while (position < length) {
      if (bytes[offset + position] == target) {
        return position;
      }
      position++;
    }
    return -1;
  }

  private int indexOfBytePair(byte first, byte second, int start) {
    int position = start;
    int wordEnd = length - Long.BYTES;
    long repeatedFirst = (first & 0xFFL) * BYTE_ONES;
    long repeatedSecond = (second & 0xFFL) * BYTE_ONES;
    while (position <= wordEnd) {
      long word = (long) LONG_VIEW.get(bytes, offset + position);
      long firstDifference = word ^ repeatedFirst;
      long secondDifference = word ^ repeatedSecond;
      if (((((firstDifference - BYTE_ONES) & ~firstDifference)
                  | ((secondDifference - BYTE_ONES) & ~secondDifference))
              & BYTE_HIGH_BITS)
          != 0) {
        for (int index = 0; index < Long.BYTES; index++) {
          byte value = bytes[offset + position + index];
          if (value == first || value == second) {
            return position + index;
          }
        }
      }
      position += Long.BYTES;
    }
    while (position < length) {
      byte value = bytes[offset + position];
      if (value == first || value == second) {
        return position;
      }
      position++;
    }
    return -1;
  }

  private int indexOfAsciiClassScalar(boolean[] asciiClass, int start) {
    for (int position = start; position < length; position++) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      int value = bytes[offset + position] & 0xFF;
      if (value < asciiClass.length && asciiClass[value]) {
        return position;
      }
    }
    return -1;
  }

  @Override
  public long decodeForward(int pos) {
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.record();
    }
    if (pos >= length) {
      return InputScanner.decoded(END_OF_INPUT, length);
    }
    int b1 = unsignedByteAt(pos);
    if (b1 < 0x80) {
      return InputScanner.decoded(b1, pos + 1);
    }
    if (b1 >= 0xC2 && b1 <= 0xDF && continuation(pos + 1)) {
      int codePoint = ((b1 & 0x1F) << 6) | (unsignedByteAt(pos + 1) & 0x3F);
      return InputScanner.decoded(codePoint, pos + 2);
    }
    if (b1 >= 0xE0 && b1 <= 0xEF && validThreeByteSecond(b1, pos + 1) && continuation(pos + 2)) {
      int codePoint =
          ((b1 & 0x0F) << 12)
              | ((unsignedByteAt(pos + 1) & 0x3F) << 6)
              | (unsignedByteAt(pos + 2) & 0x3F);
      return InputScanner.decoded(codePoint, pos + 3);
    }
    if (b1 >= 0xF0
        && b1 <= 0xF4
        && validFourByteSecond(b1, pos + 1)
        && continuation(pos + 2)
        && continuation(pos + 3)) {
      int codePoint =
          ((b1 & 0x07) << 18)
              | ((unsignedByteAt(pos + 1) & 0x3F) << 12)
              | ((unsignedByteAt(pos + 2) & 0x3F) << 6)
              | (unsignedByteAt(pos + 3) & 0x3F);
      return InputScanner.decoded(codePoint, pos + 4);
    }
    return InputScanner.decoded(REPLACEMENT_CHARACTER, pos + 1);
  }

  @Override
  public long decodeBackward(int pos) {
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.record();
    }
    if (pos <= 0) {
      return InputScanner.decoded(END_OF_INPUT, 0);
    }
    int last = unsignedByteAt(pos - 1);
    if (last < 0x80) {
      return InputScanner.decoded(last, pos - 1);
    }
    int earliest = Math.max(0, pos - 4);
    for (int start = pos - 2; start >= earliest; start--) {
      long decoded = decodeForward(start);
      if (InputScanner.position(decoded) == pos) {
        return InputScanner.decoded(InputScanner.codePoint(decoded), start);
      }
    }
    return InputScanner.decoded(REPLACEMENT_CHARACTER, pos - 1);
  }

  @Override
  public boolean isCodePointBoundary(int pos) {
    if (pos < 0 || pos > length) {
      return false;
    }
    if (pos == 0 || pos == length) {
      return true;
    }
    int earliest = Math.max(0, pos - 3);
    for (int start = earliest; start < pos; start++) {
      long decoded = decodeForward(start);
      if (InputScanner.position(decoded) > pos) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int trailingLineTerminatorStart(boolean unixLines, int logicalEndPos) {
    if (logicalEndPos <= 0 || logicalEndPos > length) {
      return -1;
    }
    long decoded = decodeBackward(logicalEndPos);
    int codePoint = InputScanner.codePoint(decoded);
    int previous = InputScanner.position(decoded);
    if (unixLines) {
      return codePoint == '\n' ? previous : -1;
    }
    if (codePoint == '\n') {
      if (previous > 0) {
        long before = decodeBackward(previous);
        if (InputScanner.codePoint(before) == '\r') {
          return InputScanner.position(before);
        }
      }
      return previous;
    }
    if (codePoint == '\r'
        || codePoint == '\u0085'
        || codePoint == '\u2028'
        || codePoint == '\u2029') {
      return previous;
    }
    return -1;
  }

  @Override
  public int positionDependentThreshold(boolean dollarAnchorEnd, boolean unixLines) {
    if (!dollarAnchorEnd) {
      return Integer.MAX_VALUE;
    }
    int trailingTerminator = trailingLineTerminatorStart(unixLines, length);
    return trailingTerminator >= 0 ? trailingTerminator : length;
  }

  byte[] bytes() {
    return bytes;
  }

  int offset() {
    return offset;
  }

  private int unsignedByteAt(int pos) {
    return bytes[offset + pos] & 0xFF;
  }

  private boolean continuation(int pos) {
    return pos < length && (unsignedByteAt(pos) & 0xC0) == 0x80;
  }

  private boolean validThreeByteSecond(int first, int secondPos) {
    if (!continuation(secondPos)) {
      return false;
    }
    int second = unsignedByteAt(secondPos);
    if (first == 0xE0) {
      return second >= 0xA0;
    }
    if (first == 0xED) {
      return second <= 0x9F;
    }
    return true;
  }

  private boolean validFourByteSecond(int first, int secondPos) {
    if (!continuation(secondPos)) {
      return false;
    }
    int second = unsignedByteAt(secondPos);
    if (first == 0xF0) {
      return second >= 0x90;
    }
    if (first == 0xF4) {
      return second <= 0x8F;
    }
    return true;
  }
}
