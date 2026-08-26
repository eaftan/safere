// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisabledForCrosscheck("Internal StringChunkedVectorScan fallback testing")
class StringChunkedVectorFallbackTest {

  private static boolean isVectorApiAvailable() {
    try {
      Class.forName("jdk.incubator.vector.ByteVector");
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 64, 256, 511, 512, 513, 1023, 1024, 1500})
  @DisplayName("Chunked indexOfCharClass finds target at various offsets and chunk boundaries")
  void testIndexOfCharClassAcrossBoundaries(int targetOffset) {
    if (!isVectorApiAvailable()) {
      return;
    }
    int totalLength = 1600;
    char[] chars = new char[totalLength];
    Arrays.fill(chars, 'a');
    chars[targetOffset] = '9';
    String text = new String(chars);

    int[] ranges = new int[] {'0', '9'};
    int found = StringVectorScan.indexOfCharClass(text, ranges, 0, totalLength);
    assertThat(found).isEqualTo(targetOffset);

    // Test with start offset after 0
    if (targetOffset > 10) {
      int foundAfter = StringVectorScan.indexOfCharClass(text, ranges, 10, totalLength);
      assertThat(foundAfter).isEqualTo(targetOffset);
    }
  }

  @Test
  @DisplayName("Chunked indexOfCharClass returns -1 when target not found")
  void testIndexOfCharClassNotFound() {
    if (!isVectorApiAvailable()) {
      return;
    }
    char[] chars = new char[1500];
    Arrays.fill(chars, 'a');
    String text = new String(chars);

    int[] ranges = new int[] {'0', '9'};
    int found = StringVectorScan.indexOfCharClass(text, ranges, 0, text.length());
    assertThat(found).isEqualTo(-1);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 64, 256, 510, 511, 512, 1020, 1024, 1400})
  @DisplayName("Chunked indexOfIgnoreCase finds prefix crossing chunk boundaries")
  void testIndexOfIgnoreCaseAcrossBoundaries(int targetOffset) {
    if (!isVectorApiAvailable()) {
      return;
    }
    int totalLength = 1600;
    char[] chars = new char[totalLength];
    Arrays.fill(chars, 'x');

    String prefix = "HeLLo";
    for (int i = 0; i < prefix.length(); i++) {
      chars[targetOffset + i] = prefix.charAt(i);
    }
    String text = new String(chars);

    int found = Matcher.indexOfIgnoreCase(text, "hello", 0);
    assertThat(found).isEqualTo(targetOffset);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 64, 511, 512, 513, 1024, 1200})
  @DisplayName("Chunked indexOfAsciiPair and indexOfAsciiTriple find targets across chunks")
  void testIndexOfAsciiPairAndTripleAcrossBoundaries(int targetOffset) {
    if (!isVectorApiAvailable()) {
      return;
    }
    int totalLength = 1400;
    char[] chars = new char[totalLength];
    Arrays.fill(chars, 'a');
    chars[targetOffset] = 'Z';
    String text = new String(chars);

    int pairFound = StringVectorScan.indexOfAsciiPair(text, 'Y', 'Z', 0, totalLength);
    assertThat(pairFound).isEqualTo(targetOffset);

    int tripleFound = StringVectorScan.indexOfAsciiTriple(text, 'X', 'Y', 'Z', 0, totalLength);
    assertThat(tripleFound).isEqualTo(targetOffset);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 100, 508, 510, 511, 512, 1020, 1400})
  @DisplayName("Chunked indexOfMultiLiteral finds matching literal across chunk boundaries")
  void testIndexOfMultiLiteralAcrossBoundaries(int targetOffset) {
    if (!isVectorApiAvailable()) {
      return;
    }
    int totalLength = 1600;
    char[] chars = new char[totalLength];
    Arrays.fill(chars, 'x');

    String targetLit = "apple";
    for (int i = 0; i < targetLit.length(); i++) {
      chars[targetOffset + i] = targetLit.charAt(i);
    }
    String text = new String(chars);

    String[] literals = new String[] {"apple", "banana", "cherry"};
    char[] anchorChars = new char[] {'a', 'b', 'c'};
    int[] anchorOffsets = new int[] {0, 0, 0};
    int minLength = 5;

    int found =
        StringVectorScan.indexOfMultiLiteral(
            text, literals, anchorChars, anchorOffsets, minLength, 0);
    assertThat(found).isEqualTo(targetOffset);
  }
}
