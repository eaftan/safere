// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.lang.invoke.MethodHandles.byteArrayViewVarHandle;
import static java.nio.ByteOrder.nativeOrder;

import java.lang.invoke.VarHandle;
import java.util.Arrays;

final class Utf8InputScanner extends ByteSwarScan implements InputScanner {
  private static final int REPLACEMENT_CHARACTER = 0xFFFD;
  private static final long BYTE_HIGH_BITS = 0x8080_8080_8080_8080L;
  private static final int BOYER_MOORE_HORSPOOL_BATCH_SIZE = 2;
  private static final int MULTI_RANGE_SWAR_MINIMUM_LENGTH = 64;
  private static final int MULTI_RANGE_SWAR_SCALAR_PROLOGUE_LENGTH = Long.BYTES;
  private static final int VECTOR_SCALAR_PROLOGUE_LENGTH = Integer.BYTES;

  private static final VarHandle LONG_VIEW = byteArrayViewVarHandle(long[].class, nativeOrder());

  private final VectorScanProvider scanProvider;

  Utf8InputScanner(byte[] bytes) {
    this(bytes, 0, bytes.length);
  }

  Utf8InputScanner(byte[] bytes, int offset, int length) {
    super(bytes, offset, length);
    this.scanProvider = VectorScanProviders.providerForLength(length);
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
  public int indexOfAscii(int ascii, int fromIndex, int limit) {
    int start = Math.max(0, fromIndex);
    int scanLen = Math.min(length, limit);
    if (start >= scanLen) {
      return -1;
    }
    if (WorkCounterConfig.ENABLED) {
      for (int i = start; i < scanLen; i++) {
        WorkCounter.record();
        if (unsignedByteAt(i) == ascii) {
          return i;
        }
      }
      return -1;
    }
    int res = indexOfByte((byte) ascii, start);
    return (res >= 0 && res < limit) ? res : -1;
  }

  @Override
  public int indexOfAsciiPair(int c1, int c2, int fromIndex, int limit) {
    int start = Math.max(0, fromIndex);
    int scanLen = Math.min(length, limit);
    if (start >= scanLen) {
      return -1;
    }
    if (WorkCounterConfig.ENABLED) {
      for (int i = start; i < scanLen; i++) {
        WorkCounter.record();
        int value = unsignedByteAt(i);
        if (value == c1 || value == c2) {
          return i;
        }
      }
      return -1;
    }
    int res = scanBytePair(scanLen, (byte) c1, (byte) c2, start);
    return (res >= 0 && res < limit) ? res : -1;
  }

  @Override
  public int indexOfAsciiTriple(int c1, int c2, int c3, int fromIndex, int limit) {
    int start = Math.max(0, fromIndex);
    int scanLen = Math.min(length, limit);
    if (start >= scanLen) {
      return -1;
    }
    if (WorkCounterConfig.ENABLED) {
      for (int i = start; i < scanLen; i++) {
        WorkCounter.record();
        int value = unsignedByteAt(i);
        if (value == c1 || value == c2 || value == c3) {
          return i;
        }
      }
      return -1;
    }
    int res = scanByteTriple(scanLen, (byte) c1, (byte) c2, (byte) c3, start);
    return (res >= 0 && res < limit) ? res : -1;
  }

  private int scanBytePair(int scanLen, byte b0, byte b1, int start) {
    VectorScanProvider pairProvider = VectorScanProviders.providerForPairLength(scanLen - start);
    if (pairProvider != null) {
      int idx = pairProvider.indexOfAsciiPair(bytes, offset, scanLen, b0, b1, start);
      if (idx != VectorScanProvider.UNSUPPORTED) {
        return idx;
      }
    }
    return ByteSwarScan.indexOfBytePair(bytes, offset, scanLen, b0, b1, start);
  }

  private int scanByteTriple(int scanLen, byte b0, byte b1, byte b2, int start) {
    VectorScanProvider tripleProvider =
        VectorScanProviders.providerForTripleLength(scanLen - start);
    if (tripleProvider != null) {
      int idx = tripleProvider.indexOfAsciiTriple(bytes, offset, scanLen, b0, b1, b2, start);
      if (idx != VectorScanProvider.UNSUPPORTED) {
        return idx;
      }
    }
    return ByteSwarScan.indexOfByteTriple(bytes, offset, scanLen, b0, b1, b2, start);
  }

  Utf8InputScanner slice(int start, int end) {
    return new Utf8InputScanner(bytes, offset + start, end - start);
  }

  boolean startsWith(byte[] prefix, int startPos) {
    int prefixLen = prefix.length;
    if (startPos >= 0 && length - startPos >= prefixLen) {
      int start = offset + startPos;
      if (Arrays.equals(bytes, start, start + prefixLen, prefix, 0, prefixLen)) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(prefixLen);
        }
        return true;
      }
    }
    return false;
  }

  boolean endsWith(byte[] suffix, boolean wasDollar, boolean unixLines, boolean foldCase) {
    int suffixLen = suffix.length;
    if (length >= suffixLen) {
      int start = offset + length - suffixLen;
      if (foldCase
          ? equalsFoldCase(bytes, start, suffix, 0, suffixLen)
          : Arrays.equals(bytes, start, start + suffixLen, suffix, 0, suffixLen)) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(suffixLen);
        }
        return true;
      }
    }
    if (!wasDollar || length == 0) {
      return false;
    }
    int effectiveLen = trailingLineTerminatorStart(unixLines, length);
    if (effectiveLen >= suffixLen) {
      int start = offset + effectiveLen - suffixLen;
      if (foldCase
          ? equalsFoldCase(bytes, start, suffix, 0, suffixLen)
          : Arrays.equals(bytes, start, start + suffixLen, suffix, 0, suffixLen)) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(suffixLen);
        }
        return true;
      }
    }
    return false;
  }

  private static boolean equalsFoldCase(
      byte[] input, int inputStart, byte[] suffix, int suffixStart, int len) {
    for (int i = 0; i < len; i++) {
      byte b1 = input[inputStart + i];
      byte b2 = suffix[suffixStart + i];
      if (b1 != b2) {
        int c1 = b1 >= 'A' && b1 <= 'Z' ? b1 + ('a' - 'A') : b1;
        int c2 = b2 >= 'A' && b2 <= 'Z' ? b2 + ('a' - 'A') : b2;
        if (c1 != c2) {
          return false;
        }
      }
    }
    return true;
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
  public int indexOfCodePointClass(int[] ranges, long bitmap0, long bitmap1, int start, int limit) {
    int position = Math.max(0, start);
    int scanLen = Math.min(length, limit);
    if (position >= scanLen) {
      return -1;
    }
    if (!WorkCounterConfig.ENABLED) {
      if (scanProvider == null
          && ranges.length >= 4
          && ranges.length <= 8
          && ranges[0] >= 0
          && ranges[ranges.length - 1] < 0x80
          && scanLen - position >= MULTI_RANGE_SWAR_MINIMUM_LENGTH
          && (ranges.length != 4 || ranges[0] != ranges[1] || ranges[2] != ranges[3])) {
        int scalarLimit = Math.min(scanLen, position + MULTI_RANGE_SWAR_SCALAR_PROLOGUE_LENGTH);
        for (; position < scalarLimit; position++) {
          int value = unsignedByteAt(position);
          if ((value < Long.SIZE && (bitmap0 & (1L << value)) != 0)
              || (value >= Long.SIZE
                  && value < 128
                  && (bitmap1 & (1L << (value - Long.SIZE))) != 0)) {
            return position;
          }
        }
        if (position >= scanLen) {
          return -1;
        }
        return ByteSwarScan.indexOfMultipleByteRanges(
            bytes, offset, scanLen, ranges, bitmap0, bitmap1, position);
      }
      int asciiResult = indexOfAsciiRanges(ranges, bitmap0, bitmap1, position, scanLen);
      if (asciiResult >= -1) {
        return asciiResult;
      }
      if (bitmap0 == 0 && bitmap1 == 0) {
        return indexOfNonAsciiCodePointClass(ranges, position, scanLen);
      }
    }
    while (position < scanLen) {
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
        return codePointPosition < scanLen ? codePointPosition : -1;
      }
    }
    return -1;
  }

  /**
   * Searches classes that can be represented by the specialized ASCII scanners.
   *
   * @return the match position, {@code -1} when the class is absent, or {@code -2} when the class
   *     requires the general code-point scan
   */
  private int indexOfAsciiRanges(int[] ranges, long bitmap0, long bitmap1, int start, int scanLen) {
    if (ranges.length < 2 || ranges[0] < 0 || ranges[ranges.length - 1] >= 0x80) {
      return -2;
    }
    switch (ranges.length) {
      case 2 -> {
        int low = ranges[0];
        int high = ranges[1];
        if (low == high) {
          int res = indexOfByte((byte) low, start);
          return (res >= 0 && res < scanLen) ? res : -1;
        }
        if (high == low + 1) {
          return scanBytePair(scanLen, (byte) low, (byte) high, start);
        }
      }
      case 4 -> {
        if (ranges[0] == ranges[1] && ranges[2] == ranges[3]) {
          return scanBytePair(scanLen, (byte) ranges[0], (byte) ranges[2], start);
        }
      }
      case 6 -> {
        if (useSpecializedAsciiTriple(ranges, scanLen - start)) {
          return scanByteTriple(
              scanLen, (byte) ranges[0], (byte) ranges[2], (byte) ranges[4], start);
        }
      }
      default -> {}
    }
    if (scanProvider != null && scanLen - start >= scanProvider.minimumInputLength()) {
      int position = start;
      int scalarLimit = Math.min(scanLen, position + VECTOR_SCALAR_PROLOGUE_LENGTH);
      for (; position < scalarLimit; position++) {
        if (InputScanner.classContains(ranges, bitmap0, bitmap1, unsignedByteAt(position))) {
          return position;
        }
      }
      int result = scanProvider.indexOfAsciiClass(bytes, offset, scanLen, ranges, position);
      if (result != VectorScanProvider.UNSUPPORTED) {
        return result;
      }
    }
    if (ranges.length == 2) {
      return ByteSwarScan.indexOfByteRange(bytes, offset, scanLen, ranges[0], ranges[1], start);
    }
    return -2;
  }

  static boolean isAsciiTriple(int[] ranges) {
    return ranges.length == 6
        && ranges[0] == ranges[1]
        && ranges[2] == ranges[3]
        && ranges[4] == ranges[5];
  }

  static boolean useSpecializedAsciiTriple(int[] ranges, int remaining) {
    return isAsciiTriple(ranges)
        && (VectorScanProviders.providerForTripleLength(remaining) != null
            || VectorScanProviders.providerForLength(remaining) == null);
  }

  private int indexOfNonAsciiCodePointClass(int[] ranges, int start, int scanLen) {
    int position = start;
    int wordEnd = scanLen - Long.BYTES;
    while (position < scanLen) {
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
        return codePointPosition < scanLen ? codePointPosition : -1;
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
            remaining(start) >= ByteSwarScan.filterThreshold(literal.length)
                ? ByteSwarScan.indexOfFiltered(bytes, offset, length, literal, failure, start)
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

  /** Knuth-Morris-Pratt scan, linear in the input length regardless of the literal. */
  private int indexOfLinear(byte[] literal, int[] failure, int start) {
    return indexOfLinear(bytes, offset, length, literal, failure, start);
  }

  static int indexOfLinear(
      byte[] bytes, int offset, int length, byte[] literal, int[] failure, int start) {
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

  int indexOfIgnoreCase(
      String prefix, int[] failure, int anchorOffset, byte anchorLow, byte anchorHigh, int start) {
    int prefixLen = prefix.length();
    if (prefixLen == 0) {
      return start;
    }
    if (!WorkCounterConfig.ENABLED) {
      if (scanProvider != null && length - start >= scanProvider.minimumInputLength()) {
        int result =
            ByteVectorScan.indexOfIgnoreCase(
                bytes,
                offset,
                length,
                prefix,
                prefixLen,
                anchorOffset,
                anchorLow,
                anchorHigh,
                start);
        if (result != VectorScanProvider.UNSUPPORTED) {
          return result;
        }
      }
      int swarResult =
          ByteSwarScan.indexOfIgnoreCase(
              bytes, offset, length, prefix, prefixLen, anchorOffset, anchorLow, anchorHigh, start);
      if (swarResult != VectorScanProvider.UNSUPPORTED) {
        return swarResult;
      }
    }
    return indexOfLinearIgnoreCase(bytes, offset, length, prefix, failure, start);
  }

  static int indexOfLinearIgnoreCase(
      byte[] bytes, int offset, int length, String prefix, int[] failure, int start) {
    int prefixLen = prefix.length();
    int matched = 0;
    for (int position = start; position < length; position++) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      int current = Ascii.toLowerCase(bytes[offset + position] & 0xFF);
      while (matched > 0 && current != prefix.charAt(matched)) {
        matched = failure[matched - 1];
      }
      if (current == prefix.charAt(matched)) {
        matched++;
        if (matched == prefixLen) {
          return position - prefixLen + 1;
        }
      }
    }
    return -1;
  }

  int indexOfAsciiClass(AsciiBitmap asciiClass, int start) {
    if (asciiClass == null || asciiClass.isEmpty()) {
      return -1;
    }
    int first = -1;
    int second = -1;
    int last = -1;
    boolean contiguous = true;
    for (int value = 0; value < 128; value++) {
      if (asciiClass.contains(value)) {
        if (first < 0) {
          first = value;
        } else if (second < 0) {
          second = value;
        }
        contiguous &= last < 0 || value == last + 1;
        last = value;
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
    if (last == second) {
      return scanBytePair(length, (byte) first, (byte) second, start);
    }
    return contiguous
        ? ByteSwarScan.indexOfByteRange(bytes, offset, length, first, last, start)
        : indexOfAsciiClassScalar(asciiClass, start);
  }

  /**
   * Boyer-Moore-Horspool with a bad-character skip table, used for inputs too short to amortize the
   * candidate filter's setup.
   *
   * @return the index of the first match, {@code -1} if the literal is absent, or {@code -2} if the
   *     work budget was exhausted before either could be established
   */
  int boundedBoyerMooreHorspool(byte[] literal, int[] shifts, int start) {
    int last = literal.length - 1;
    int position = start + last;
    long work = 0;
    long workLimit = WorkLimit.forRemaining(remaining(start));
    while (position < length) {
      // Keep two dependent skip steps under one outer backedge. C2 otherwise leaves this loop
      // scalar when it is compiled alongside the candidate-filter path, adding a backedge and
      // safepoint poll to every skip.
      for (int step = 0; step < BOYER_MOORE_HORSPOOL_BATCH_SIZE && position < length; step++) {
        int literalPosition = last;
        int inputPosition = position;
        while (literalPosition >= 0 && bytes[offset + inputPosition] == literal[literalPosition]) {
          literalPosition--;
          inputPosition--;
        }
        if (literalPosition < 0) {
          return inputPosition + 1;
        }
        work += (last - literalPosition + 1);
        if (WorkLimit.isExhausted(work, workLimit)) {
          return -2;
        }
        position += shifts[bytes[offset + position] & 0xFF];
      }
    }
    return -1;
  }

  private int indexOfAsciiClassScalar(AsciiBitmap asciiClass, int start) {
    for (int position = start; position < length; position++) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      int value = bytes[offset + position] & 0xFF;
      if (asciiClass.contains(value)) {
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

  @Override
  public int indexOfCharClass(CharClassScanInfo scanInfo, int start) {
    return indexOfCodePointClass(
        scanInfo.ranges(), scanInfo.bitmap0(), scanInfo.bitmap1(), start, length);
  }
}
