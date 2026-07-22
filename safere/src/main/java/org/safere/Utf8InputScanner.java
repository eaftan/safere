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

  /**
   * Input sizes at which the SWAR candidate filter overtakes the skip loop. The filter always
   * advances eight positions per step, while the skip loop can advance as far as the literal
   * length, so the filter needs an input large enough to outweigh that per-step advantage. Both
   * bounds were measured; see {@link #indexOfFiltered}.
   */
  private static final int MIN_FILTER_LENGTH = 64;

  private static final int FILTER_LENGTH_FACTOR = 40;
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

  @Override
  public int singleUnitCodePointAt(int pos) {
    return asciiAt(pos);
  }

  @Override
  public int singleUnitCodePointBefore(int pos) {
    return asciiAt(pos - 1);
  }

  @Override
  public int indexOfCodePointClass(int[] ranges, long bitmap0, long bitmap1, int start) {
    int position = Math.max(0, start);
    if (!WorkCounterConfig.ENABLED && bitmap0 == 0 && bitmap1 == 0) {
      return indexOfNonAsciiCodePointClass(ranges, position);
    }
    while (position < length) {
      int codePointPosition = position;
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      int codePoint = asciiAt(position);
      if (codePoint >= 0) {
        position++;
      } else {
        long decoded = decodeForward(position);
        codePoint = InputScanner.codePoint(decoded);
        position = InputScanner.position(decoded);
      }
      if (InputScanner.classContains(ranges, bitmap0, bitmap1, codePoint)) {
        return codePointPosition;
      }
    }
    return -1;
  }

  private int indexOfNonAsciiCodePointClass(int[] ranges, int start) {
    int position = start;
    int wordEnd = length - Long.BYTES;
    while (position < length) {
      if (position <= wordEnd) {
        long word = (long) LONG_VIEW.get(bytes, offset + position);
        if ((word & BYTE_HIGH_BITS) == 0) {
          position += Long.BYTES;
          continue;
        }
      }
      int value = unsignedByteAt(position);
      if (value < 0x80) {
        position++;
        continue;
      }
      int codePointPosition = position;
      long decoded = decodeForward(position);
      int codePoint = InputScanner.codePoint(decoded);
      position = InputScanner.position(decoded);
      if (InputScanner.classContains(ranges, 0, 0, codePoint)) {
        return codePointPosition;
      }
    }
    return -1;
  }

  int indexOf(byte[] literal, int[] failure, int[] shifts) {
    return indexOf(literal, failure, shifts, 0);
  }

  int indexOf(byte[] literal, int[] failure, int[] shifts, int start) {
    if (literal.length == 0) {
      return start;
    }
    if (!WorkCounterConfig.ENABLED) {
      if (literal.length == 1) {
        return indexOfByte(literal[0], start);
      }
      if (shifts != null) {
        // Both searches beat the linear scan, but they win over different ranges. The skip loop
        // reaches the end of a short input in a handful of steps, while the candidate filter has
        // to pay for its wider setup and finish with a scalar tail. Once the input is long enough
        // for the filter's eight-positions-per-step throughput to dominate that fixed cost, it
        // wins by a growing margin. The crossover scales with the literal length, since a longer
        // literal lets the skip loop advance further per step.
        int result =
            remaining(start) >= filterThreshold(literal.length)
                ? indexOfFiltered(literal, failure, start)
                : boundedBoyerMooreHorspool(literal, shifts, start);
        // A match index or a trusted -1; only the -2 "work budget exhausted" sentinel falls
        // through to the linear-time scan below.
        if (result >= -1) {
          return result;
        }
      }
    }
    return indexOfLinear(literal, failure, start);
  }

  private int remaining(int start) {
    return length - start;
  }

  static long filterThreshold(int literalLength) {
    return Math.max(MIN_FILTER_LENGTH, (long) literalLength * FILTER_LENGTH_FACTOR);
  }

  static long workLimit(int remaining) {
    return Math.max(1L, (long) remaining * 2);
  }

  static long addCandidateWork(long work, int candidateCount, int literalLength) {
    return work + (long) candidateCount * literalLength + Long.BYTES;
  }

  /** Knuth-Morris-Pratt scan, linear in the input length regardless of the literal. */
  private int indexOfLinear(byte[] literal, int[] failure, int start) {
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

  /**
   * Boyer-Moore-Horspool with a bad-character skip table, used for inputs too short to amortize the
   * candidate filter's setup.
   *
   * @return the index of the first match, {@code -1} if the literal is absent, or {@code -2} if the
   *     work budget was exhausted before either could be established
   */
  private int boundedBoyerMooreHorspool(byte[] literal, int[] shifts, int start) {
    int last = literal.length - 1;
    int position = start + last;
    long work = 0;
    long workLimit = workLimit(remaining(start));
    while (position < length) {
      int literalPosition = last;
      int inputPosition = position;
      while (literalPosition >= 0 && bytes[offset + inputPosition] == literal[literalPosition]) {
        if (work >= workLimit) {
          return -2;
        }
        work++;
        literalPosition--;
        inputPosition--;
      }
      if (literalPosition < 0) {
        return inputPosition + 1;
      }
      if (work >= workLimit) {
        return -2;
      }
      position += shifts[bytes[offset + position] & 0xFF];
      work++;
    }
    return -1;
  }

  /**
   * Searches for a multi-byte {@code literal} by locating candidate positions with a SWAR filter on
   * the literal's first and last bytes, then verifying each candidate in full.
   *
   * <p>Two words are loaded per step, one aligned with the literal's first byte and one with its
   * last byte. XOR-ing each against the corresponding broadcast byte turns matching positions into
   * zero bytes, so the standard zero-byte test identifies positions where both the first and last
   * byte agree. Requiring both bytes makes candidates far rarer than a single-byte filter would.
   *
   * <p>This examines eight positions per step with no data-dependent branching. A skip loop such as
   * Boyer-Moore-Horspool can advance further per step, but each of its steps is a serialized load,
   * table lookup, and add, which costs more than the wider branch-free step here.
   *
   * <p>The zero-byte test never misses a matching position, but it can flag a position that does
   * not match, so every candidate is verified against the whole literal rather than trusting the
   * filter for the first and last byte.
   *
   * <p>Verification is O(literal length) per candidate, so an adversarial input can drive this to
   * O(input length * literal length). A work budget bounds that: on exhaustion this returns {@code
   * -2} and the caller falls back to linear-time KMP.
   *
   * @return the index of the first match, {@code -1} if the literal is absent, or {@code -2} if the
   *     work budget was exhausted before either could be established
   */
  private int indexOfFiltered(byte[] literal, int[] failure, int start) {
    int last = literal.length - 1;
    long repeatedFirst = (literal[0] & 0xFFL) * BYTE_ONES;
    long repeatedLast = (literal[last] & 0xFFL) * BYTE_ONES;
    int wordEnd = length - last - Long.BYTES;
    long work = 0;
    long workLimit = workLimit(remaining(start));
    int position = start;
    while (position <= wordEnd) {
      long firstDifference = (long) LONG_VIEW.get(bytes, offset + position) ^ repeatedFirst;
      long lastDifference = (long) LONG_VIEW.get(bytes, offset + position + last) ^ repeatedLast;
      long candidates =
          (firstDifference - BYTE_ONES)
              & ~firstDifference
              & (lastDifference - BYTE_ONES)
              & ~lastDifference
              & BYTE_HIGH_BITS;
      if (candidates != 0) {
        // Scanning the eight positions in address order keeps this independent of byte order and
        // returns the leftmost match within the word.
        int candidateCount = 0;
        for (int index = 0; index < Long.BYTES; index++) {
          int candidate = position + index;
          if (bytes[offset + candidate] == literal[0]) {
            if (matchesAt(literal, candidate)) {
              return candidate;
            }
            candidateCount++;
          }
        }
        work = addCandidateWork(work, candidateCount, literal.length);
      }
      position += Long.BYTES;
      work++;
      if (work >= workLimit) {
        return -2;
      }
    }
    // Fewer than literal.length + Long.BYTES positions remain. Finishing with the linear scan
    // keeps the tail linear rather than comparing the whole literal at each remaining position.
    return indexOfLinear(literal, failure, position);
  }

  private boolean matchesAt(byte[] literal, int position) {
    for (int index = 0; index < literal.length; index++) {
      if (bytes[offset + position + index] != literal[index]) {
        return false;
      }
    }
    return true;
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
