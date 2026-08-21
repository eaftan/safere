// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Comprehensive differential equivalence tests comparing 16-bit {@link ShortVectorScan} and {@link
 * ShortSwarScan} kernels against 8-bit kernels and scalar reference models.
 */
@DisabledForCrosscheck("Internal SIMD/SWAR kernel test, not part of public regex API")
class ShortScanEquivalenceTest {

  private static final List<int[]> ASCII_CHAR_CLASS_RANGES =
      List.of(
          new int[] {'0', '9'},
          new int[] {'a', 'z'},
          new int[] {'a', 'z', 'A', 'Z'},
          new int[] {'0', '9', 'a', 'z', 'A', 'Z'},
          new int[] {'0', '9', 'a', 'z', 'A', 'Z', '_', '_'});

  private static final List<int[]> UTF16_CHAR_CLASS_RANGES =
      List.of(
          new int[] {'0', '9'},
          new int[] {'a', 'z', 'A', 'Z'},
          new int[] {0x0400, 0x04FF}, // Cyrillic
          new int[] {0x3040, 0x309F}, // Hiragana
          new int[] {'0', '9', 0x0400, 0x04FF});

  private static boolean isVectorApiAvailable() {
    try {
      Class.forName("jdk.incubator.vector.ByteVector");
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  @Test
  void denseIgnoreCaseCandidatesFallBackBeforeReplayingLongPrefixes() {
    if (!isVectorApiAvailable()) {
      return;
    }
    String prefix = "z".repeat(64) + "y";
    byte[] input = "z".repeat(4_096).getBytes(UTF_8);

    assertThat(
            ByteVectorScan.indexOfIgnoreCase(
                input, 0, input.length, prefix, prefix.length(), 0, (byte) 'z', (byte) 'Z', 0))
        .isEqualTo(VectorScanProvider.UNSUPPORTED);
  }

  @Test
  void vectorCandidateBoundsDoNotOverflow() {
    assertThat(WorkLimit.candidateInBounds(Integer.MAX_VALUE - 4, 0, Integer.MAX_VALUE, 8))
        .isFalse();
    assertThat(WorkLimit.candidateInBounds(Integer.MAX_VALUE - 8, 0, Integer.MAX_VALUE, 8))
        .isTrue();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 3, 7, 15, 16, 31, 32, 63, 64, 127, 128, 512, 1024, 2048})
  @DisplayName("1-byte Latin-1 char class scan equivalence across ByteSwarScan and scalar")
  void testLatin1CharClassEquivalence(int length) {
    Random random = new Random(42 + length);
    byte[] input = new byte[length];
    for (int i = 0; i < length; i++) {
      input[i] = (byte) (random.nextInt(26) + 'a'); // random lowercase
    }

    for (int[] ranges : ASCII_CHAR_CLASS_RANGES) {
      for (int start : new int[] {0, 1, 7, 15, 31, Math.max(0, length - 5)}) {
        if (start > length) continue;

        int expected = scalarIndexOfAsciiClass(input, ranges, start);

        int byteSwar =
            (ranges.length <= 4)
                ? ByteSwarScan.indexOfAsciiClass(input, 0, length, ranges, start)
                : expected;

        assertThat(byteSwar)
            .as("ByteSwarScan at start=%d, len=%d", start, length)
            .isEqualTo(expected);

        if (isVectorApiAvailable()) {
          int byteVector = ByteVectorScan.indexOfAsciiClass(input, 0, length, ranges, start);
          assertThat(byteVector)
              .as("ByteVectorScan at start=%d, len=%d", start, length)
              .isEqualTo(expected);
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 3, 7, 15, 16, 31, 32, 63, 64, 127, 128, 512, 1024, 2048})
  @DisplayName("2-byte UTF-16 char class scan equivalence across char[], byte[], SWAR, and Vector")
  void testUtf16CharClassEquivalence(int charLength) {
    Random random = new Random(100 + charLength);
    char[] chars = new char[charLength];
    for (int i = 0; i < charLength; i++) {
      chars[i] = (char) (random.nextInt(26) + 'a');
    }
    // Inject some Cyrillic chars
    if (charLength > 10) {
      chars[charLength / 2] = '\u0416';
    }

    String str = new String(chars);
    byte[] utf16Bytes = str.getBytes(UTF_16LE);

    for (int[] ranges : UTF16_CHAR_CLASS_RANGES) {
      for (int start : new int[] {0, 1, 7, 15, 31, Math.max(0, charLength - 5)}) {
        if (start > charLength) continue;

        int expected = scalarIndexOfCharClass(chars, ranges, start);

        // SWAR over byte[] (UTF-16) and char[]
        int shortSwarByte =
            (ranges.length <= 4)
                ? ShortSwarScan.indexOfCharClassUtf16(utf16Bytes, 0, charLength, ranges, start)
                : expected;
        int shortSwarChar =
            (ranges.length <= 4)
                ? ShortSwarScan.indexOfCharClass(chars, 0, charLength, ranges, start)
                : expected;

        assertThat(shortSwarByte)
            .as("ShortSwarScan (byte[]) at start=%d, len=%d", start, charLength)
            .isEqualTo(expected);
        assertThat(shortSwarChar)
            .as("ShortSwarScan (char[]) at start=%d, len=%d", start, charLength)
            .isEqualTo(expected);

        // Vector API Kernels (When enabled on runtime)
        if (isVectorApiAvailable()) {
          int shortVectorByte =
              ShortVectorScan.indexOfCharClassUtf16(utf16Bytes, 0, charLength, ranges, start);
          int shortVectorChar =
              ShortVectorScan.indexOfCharClass(chars, 0, charLength, ranges, start);

          assertThat(shortVectorByte)
              .as("ShortVectorScan (byte[]) at start=%d, len=%d", start, charLength)
              .isEqualTo(expected);
          assertThat(shortVectorChar)
              .as("ShortVectorScan (char[]) at start=%d, len=%d", start, charLength)
              .isEqualTo(expected);
        }
      }
    }
  }

  @Test
  @DisplayName(
      "Case-insensitive prefix scan equivalence for 16-bit ShortVectorScan and ShortSwarScan")
  void testIgnoreCaseEquivalence() {
    String prefix = "hElLo";
    String haystackUtf16 =
        "x".repeat(500)
            + "\u0416\u0435\u043B\u043B\u043E"
            + "x".repeat(200)
            + "HELLO"
            + "x".repeat(300);

    char[] chars = haystackUtf16.toCharArray();
    byte[] utf16Bytes = haystackUtf16.getBytes(UTF_16LE);

    // SWAR
    int utf16SwarByte =
        ShortSwarScan.indexOfIgnoreCaseUtf16(utf16Bytes, 0, haystackUtf16.length(), prefix, 0);
    int utf16SwarChar =
        ShortSwarScan.indexOfIgnoreCase(chars, 0, haystackUtf16.length(), prefix, 0);

    assertThat(utf16SwarByte).isEqualTo(705);
    assertThat(utf16SwarChar).isEqualTo(705);

    // Vector
    if (isVectorApiAvailable()) {
      int utf16VecByte =
          ShortVectorScan.indexOfIgnoreCaseUtf16(utf16Bytes, 0, haystackUtf16.length(), prefix, 0);
      int utf16VecChar =
          ShortVectorScan.indexOfIgnoreCase(chars, 0, haystackUtf16.length(), prefix, 0);

      assertThat(utf16VecByte).isEqualTo(705);
      assertThat(utf16VecChar).isEqualTo(705);
    }
  }

  @Test
  @DisplayName("UTF-16 kernels handle unsigned ranges and reject non-BMP ranges")
  void utf16RangeBoundaries() {
    char[] chars = new char[64];
    Arrays.fill(chars, '\uA000');
    chars[10] = '\u7500';
    byte[] utf16Bytes = new String(chars).getBytes(UTF_16LE);
    int[] crossingSignedBoundary = {'\u7000', '\u9000'};

    assertThat(ShortSwarScan.indexOfCharClass(chars, 0, chars.length, crossingSignedBoundary, 0))
        .isEqualTo(10);
    assertThat(
            ShortSwarScan.indexOfCharClassUtf16(
                utf16Bytes, 0, chars.length, crossingSignedBoundary, 0))
        .isEqualTo(10);
    if (isVectorApiAvailable()) {
      assertThat(
              ShortVectorScan.indexOfCharClass(chars, 0, chars.length, crossingSignedBoundary, 0))
          .isEqualTo(10);
      assertThat(
              ShortVectorScan.indexOfCharClassUtf16(
                  utf16Bytes, 0, chars.length, crossingSignedBoundary, 0))
          .isEqualTo(10);
    }

    int[] supplementaryRange = {0x1F600, 0x1F64F};
    assertThat(ShortSwarScan.indexOfCharClass(chars, 0, chars.length, supplementaryRange, 0))
        .isEqualTo(VectorScanProvider.UNSUPPORTED);
    assertThat(
            ShortSwarScan.indexOfCharClassUtf16(utf16Bytes, 0, chars.length, supplementaryRange, 0))
        .isEqualTo(VectorScanProvider.UNSUPPORTED);
    if (isVectorApiAvailable()) {
      assertThat(ShortVectorScan.indexOfCharClass(chars, 0, chars.length, supplementaryRange, 0))
          .isEqualTo(VectorScanProvider.UNSUPPORTED);
      assertThat(
              ShortVectorScan.indexOfCharClassUtf16(
                  utf16Bytes, 0, chars.length, supplementaryRange, 0))
          .isEqualTo(VectorScanProvider.UNSUPPORTED);
    }
  }

  @Test
  @DisplayName("UTF-16 vector kernels compare ranges within each signed half directly")
  void utf16RangesWithinSignedHalves() {
    if (!isVectorApiAvailable()) {
      return;
    }
    char[] chars = new char[64];
    Arrays.fill(chars, '\uA000');
    chars[10] = '\u7500';
    chars[20] = '\u8500';
    byte[] utf16Bytes = new String(chars).getBytes(UTF_16LE);

    for (int[] ranges : new int[][] {{0x7000, 0x7FFF}, {0x8000, 0x9000}}) {
      int expected = scalarIndexOfCharClass(chars, ranges, 0);
      assertThat(ShortVectorScan.indexOfCharClass(chars, 0, chars.length, ranges, 0))
          .isEqualTo(expected);
      assertThat(ShortVectorScan.indexOfCharClassUtf16(utf16Bytes, 0, chars.length, ranges, 0))
          .isEqualTo(expected);
    }
  }

  @Test
  @DisplayName("Public array kernels handle empty prefixes and unsupported byte ranges")
  void publicKernelEdgeInputs() {
    char[] chars = "abc".toCharArray();
    byte[] utf16 = "abc".getBytes(UTF_16LE);

    assertThat(ShortSwarScan.indexOfIgnoreCase(chars, 0, chars.length, "", 1)).isEqualTo(1);
    assertThat(ShortSwarScan.indexOfIgnoreCaseUtf16(utf16, 0, chars.length, "", 10))
        .isEqualTo(chars.length);
    if (isVectorApiAvailable()) {
      assertThat(ShortVectorScan.indexOfIgnoreCase(chars, 0, chars.length, "", -1)).isZero();
      assertThat(ShortVectorScan.indexOfIgnoreCaseUtf16(utf16, 0, chars.length, "", 1))
          .isEqualTo(1);
    }

    byte[] bytes = new byte[64];
    bytes[10] = (byte) 0xE9;
    assertThat(ByteSwarScan.indexOfAsciiClass(bytes, 0, bytes.length, new int[] {233, 233}, 0))
        .isEqualTo(VectorScanProvider.UNSUPPORTED);
    assertThat(ByteSwarScan.indexOfAsciiClass(bytes, 0, bytes.length, new int[] {'z', 'a'}, 0))
        .isEqualTo(VectorScanProvider.UNSUPPORTED);
  }

  @Test
  @DisplayName("Ignore-case prefix scans use a linear failure function")
  void ignoreCasePrefixFailureFunction() {
    assertThat(Ascii.ignoreCaseFailure("aaaaab")).containsExactly(0, 1, 2, 3, 4, 0);
  }

  @Test
  @DisplayName("StringSupport reflects coder and array when java.base is accessible")
  void stringSupportAccess() {
    if (StringSupport.hasAccess()) {
      String latin1 = "hello world";
      String utf16 = "hello \u0410\u0411\u0412 world";

      assertThat(StringSupport.compatibleWith(latin1, ISO_8859_1)).isTrue();
      assertThat(StringSupport.compatibleWith(latin1, UTF_16)).isFalse();
      assertThat(StringSupport.compatibleWith(utf16, ISO_8859_1)).isFalse();
      assertThat(StringSupport.compatibleWith(utf16, UTF_16)).isTrue();
    }
  }

  private static int scalarIndexOfAsciiClass(byte[] input, int[] ranges, int start) {
    for (int i = start; i < input.length; i++) {
      int b = input[i] & 0xFF;
      for (int r = 0; r < ranges.length; r += 2) {
        if (b >= ranges[r] && b <= ranges[r + 1]) {
          return i;
        }
      }
    }
    return -1;
  }

  private static int scalarIndexOfCharClass(char[] input, int[] ranges, int start) {
    for (int i = start; i < input.length; i++) {
      char c = input[i];
      for (int r = 0; r < ranges.length; r += 2) {
        if (c >= ranges[r] && c <= ranges[r + 1]) {
          return i;
        }
      }
    }
    return -1;
  }
}
