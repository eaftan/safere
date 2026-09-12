// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class Utf8VectorPairTripleTest {

  @Test
  void tripleScannerRequiresThreeDisjointCharacters() {
    assertThat(Utf8InputScanner.isAsciiTriple(new int[] {'X', 'X', 'Z', 'Z', '_', '_'})).isTrue();
    assertThat(Utf8InputScanner.isAsciiTriple(new int[] {'X', 'Z'})).isFalse();
    assertThat(Utf8InputScanner.isAsciiTriple(new int[] {'X', 'X', 'Z', 'Z'})).isFalse();
  }

  @Test
  void disjointTripleDispatchHonorsVectorCrossover() {
    int[] ranges = {'X', 'X', 'Z', 'Z', '_', '_'};
    if (VectorScanProviders.providerFor(ScanKind.TRIPLE, 64) != null) {
      assertThat(Utf8InputScanner.useSpecializedAsciiTriple(ranges, 64)).isTrue();
      assertThat(Utf8InputScanner.useSpecializedAsciiTriple(ranges, 10_240)).isTrue();
    }
    if (VectorScanProviders.providerFor(ScanKind.CLASS, 10_241) != null) {
      assertThat(Utf8InputScanner.useSpecializedAsciiTriple(ranges, 10_241)).isFalse();
    }
  }

  @Test
  void unknownProviderDiagnosticIncludesRequestedValue() {
    assertThat(VectorScanProviders.unknownProviderMessage("typo")).contains("typo");
  }

  @Test
  void pairAndTripleScansHaveIndependentVectorCutoffs() {
    if (VectorScanProviders.providerFor(ScanKind.PAIR, 64) != null) {
      assertThat(VectorScanProviders.providerFor(ScanKind.CLASS, 64)).isNull();
      assertThat(VectorScanProviders.providerFor(ScanKind.TRIPLE, 64)).isNotNull();
      assertThat(VectorScanProviders.providerFor(ScanKind.TRIPLE, 10_240)).isNotNull();
      assertThat(VectorScanProviders.providerFor(ScanKind.TRIPLE, 10_241)).isNull();
      assertThat(VectorScanProviders.providerFor(ScanKind.CLASS, 1024)).isNotNull();
    }
  }

  @Test
  void everyScanKindHasACoherentWindowRange() {
    VectorScanProvider provider = VectorScanProviders.providerFor(ScanKind.PAIR, 64);
    if (provider == null) {
      return; // No Vector provider is installed, so no thresholds are observable.
    }
    for (ScanKind kind : ScanKind.values()) {
      int minimum = provider.minimumWindowLength(kind);
      int maximum = provider.maximumWindowLength(kind);
      assertThat(minimum).isGreaterThan(0);
      assertThat(maximum).isGreaterThanOrEqualTo(minimum);
      assertThat(VectorScanProviders.providerFor(kind, minimum)).isNotNull();
      assertThat(VectorScanProviders.providerFor(kind, minimum - 1)).isNull();
      if (maximum != Integer.MAX_VALUE) {
        assertThat(VectorScanProviders.providerFor(kind, maximum)).isNotNull();
        assertThat(VectorScanProviders.providerFor(kind, maximum + 1)).isNull();
      }
    }
  }

  private static boolean isVectorApiAvailable() {
    try {
      Class.forName("jdk.incubator.vector.ByteVector");
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 15, 16, 31, 32, 63, 64, 100, 128, 256, 500})
  void testPairEquivalenceWithSwar(int length) {
    assumeTrue(isVectorApiAvailable(), "Vector API not available on module path");

    byte b0 = 'x';
    byte b1 = 'y';
    Random rnd = new Random(1000 + length);

    for (int trial = 0; trial < 50; trial++) {
      byte[] bytes = new byte[length];
      for (int i = 0; i < length; i++) {
        bytes[i] = (byte) ('a' + rnd.nextInt(20)); // 'a'..'t' (no 'x' or 'y')
      }
      int start = length == 0 ? 0 : rnd.nextInt(length);

      // Absent check
      int swarAbsent = ByteSwarScan.indexOfBytePair(bytes, 0, length, b0, b1, start);
      int vectorAbsent = ByteVectorScan.indexOfAsciiPair(bytes, 0, length, b0, b1, start);
      assertThat(vectorAbsent).as("absent trial %d len %d", trial, length).isEqualTo(swarAbsent);

      // Present check
      if (length > start) {
        int pos = start + rnd.nextInt(length - start);
        bytes[pos] = rnd.nextBoolean() ? b0 : b1;

        int swarHit = ByteSwarScan.indexOfBytePair(bytes, 0, length, b0, b1, start);
        int vectorHit = ByteVectorScan.indexOfAsciiPair(bytes, 0, length, b0, b1, start);
        assertThat(vectorHit).as("hit trial %d len %d", trial, length).isEqualTo(swarHit);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 15, 16, 31, 32, 63, 64, 100, 128, 256, 500})
  void testReversePairEquivalenceWithSwar(int length) {
    assumeTrue(isVectorApiAvailable(), "Vector API not available on module path");

    byte b0 = 'x';
    byte b1 = 'y';
    Random rnd = new Random(3000 + length);

    for (int trial = 0; trial < 50; trial++) {
      byte[] bytes = new byte[length];
      for (int i = 0; i < length; i++) {
        bytes[i] = (byte) ('a' + rnd.nextInt(20)); // 'a'..'t' (no 'x' or 'y')
      }
      int fromIndex = length == 0 ? 0 : rnd.nextInt(length);
      int toIndex = length == 0 ? 0 : rnd.nextInt(fromIndex + 1);

      // Absent check
      int swarAbsent =
          ByteSwarScan.lastIndexOfBytePair(bytes, 0, length, b0, b1, fromIndex, toIndex);
      int vectorAbsent =
          ByteVectorScan.lastIndexOfAsciiPair(bytes, 0, length, b0, b1, fromIndex, toIndex);
      assertThat(vectorAbsent).as("absent trial %d len %d", trial, length).isEqualTo(swarAbsent);

      // Present check
      if (length > 0 && fromIndex >= toIndex) {
        int pos = toIndex + rnd.nextInt(fromIndex - toIndex + 1);
        bytes[pos] = rnd.nextBoolean() ? b0 : b1;

        int swarHit =
            ByteSwarScan.lastIndexOfBytePair(bytes, 0, length, b0, b1, fromIndex, toIndex);
        int vectorHit =
            ByteVectorScan.lastIndexOfAsciiPair(bytes, 0, length, b0, b1, fromIndex, toIndex);
        assertThat(vectorHit).as("hit trial %d len %d", trial, length).isEqualTo(swarHit);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 15, 16, 31, 32, 63, 64, 100, 128, 256, 500})
  void testTripleEquivalenceWithSwar(int length) {
    assumeTrue(isVectorApiAvailable(), "Vector API not available on module path");

    byte b0 = 'x';
    byte b1 = 'y';
    byte b2 = 'z';
    Random rnd = new Random(2000 + length);

    for (int trial = 0; trial < 50; trial++) {
      byte[] bytes = new byte[length];
      for (int i = 0; i < length; i++) {
        bytes[i] = (byte) ('a' + rnd.nextInt(20)); // 'a'..'t' (no 'x', 'y', 'z')
      }
      int start = length == 0 ? 0 : rnd.nextInt(length);

      // Absent check
      int swarAbsent = ByteSwarScan.indexOfByteTriple(bytes, 0, length, b0, b1, b2, start);
      int vectorAbsent = ByteVectorScan.indexOfAsciiTriple(bytes, 0, length, b0, b1, b2, start);
      assertThat(vectorAbsent).as("absent trial %d len %d", trial, length).isEqualTo(swarAbsent);

      // Present check
      if (length > start) {
        int pos = start + rnd.nextInt(length - start);
        int choice = rnd.nextInt(3);
        bytes[pos] = choice == 0 ? b0 : choice == 1 ? b1 : b2;

        int swarHit = ByteSwarScan.indexOfByteTriple(bytes, 0, length, b0, b1, b2, start);
        int vectorHit = ByteVectorScan.indexOfAsciiTriple(bytes, 0, length, b0, b1, b2, start);
        assertThat(vectorHit).as("hit trial %d len %d", trial, length).isEqualTo(swarHit);
      }
    }
  }

  @Test
  void testScannerPairAndTripleWithLimit() {
    byte[] bytes = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(UTF_8);
    Utf8InputScanner scanner = new Utf8InputScanner(bytes);

    // 'a' is at 10, 'A' is at 36
    assertThat(scanner.indexOfAsciiPair('a', 'A', 0, 10)).isEqualTo(-1);
    assertThat(scanner.indexOfAsciiPair('a', 'A', 0, 11)).isEqualTo(10);
    assertThat(scanner.indexOfAsciiPair('a', 'A', 11, 36)).isEqualTo(-1);
    assertThat(scanner.indexOfAsciiPair('a', 'A', 11, 37)).isEqualTo(36);

    // 'b' at 11, 'm' at 22, 'Z' at 61
    assertThat(scanner.indexOfAsciiTriple('b', 'm', 'Z', 0, 11)).isEqualTo(-1);
    assertThat(scanner.indexOfAsciiTriple('b', 'm', 'Z', 0, 12)).isEqualTo(11);
    assertThat(scanner.indexOfAsciiTriple('b', 'm', 'Z', 12, 22)).isEqualTo(-1);
    assertThat(scanner.indexOfAsciiTriple('b', 'm', 'Z', 12, 23)).isEqualTo(22);
    assertThat(scanner.indexOfAsciiTriple('b', 'm', 'Z', 23, 61)).isEqualTo(-1);
    assertThat(scanner.indexOfAsciiTriple('b', 'm', 'Z', 23, 62)).isEqualTo(61);
  }

  @Test
  void testScannerReversePairWithLimit() {
    byte[] bytes = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(UTF_8);
    Utf8InputScanner scanner = new Utf8InputScanner(bytes);

    // 'a' is at 10, 'A' is at 36
    assertThat(scanner.lastIndexOfAsciiPair((byte) 'a', (byte) 'A', 61, 37)).isEqualTo(-1);
    assertThat(scanner.lastIndexOfAsciiPair((byte) 'a', (byte) 'A', 61, 36)).isEqualTo(36);
    assertThat(scanner.lastIndexOfAsciiPair((byte) 'a', (byte) 'A', 35, 11)).isEqualTo(-1);
    assertThat(scanner.lastIndexOfAsciiPair((byte) 'a', (byte) 'A', 35, 10)).isEqualTo(10);
    assertThat(scanner.lastIndexOfAsciiPair((byte) 'a', (byte) 'A', 9, 0)).isEqualTo(-1);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 15, 16, 31, 32, 63, 64, 100, 128, 256, 500})
  void testReverseTripleEquivalenceWithSwar(int length) {
    assumeTrue(isVectorApiAvailable(), "Vector API not available on module path");

    byte b0 = 'x';
    byte b1 = 'y';
    byte b2 = 'z';
    Random rnd = new Random(5000 + length);

    for (int trial = 0; trial < 50; trial++) {
      byte[] bytes = new byte[length];
      for (int i = 0; i < length; i++) {
        bytes[i] = (byte) ('a' + rnd.nextInt(20)); // 'a'..'t' (no 'x', 'y', 'z')
      }
      int fromIndex = length == 0 ? 0 : rnd.nextInt(length);
      int toIndex = length == 0 ? 0 : rnd.nextInt(fromIndex + 1);

      // Absent check
      int swarAbsent =
          ByteSwarScan.lastIndexOfByteTriple(bytes, 0, length, b0, b1, b2, fromIndex, toIndex);
      int vectorAbsent =
          ByteVectorScan.lastIndexOfAsciiTriple(bytes, 0, length, b0, b1, b2, fromIndex, toIndex);
      assertThat(vectorAbsent).as("absent trial %d len %d", trial, length).isEqualTo(swarAbsent);

      // Present check
      if (length > 0 && fromIndex >= toIndex) {
        int pos = toIndex + rnd.nextInt(fromIndex - toIndex + 1);
        int choice = rnd.nextInt(3);
        bytes[pos] = choice == 0 ? b0 : choice == 1 ? b1 : b2;

        int swarHit =
            ByteSwarScan.lastIndexOfByteTriple(bytes, 0, length, b0, b1, b2, fromIndex, toIndex);
        int vectorHit =
            ByteVectorScan.lastIndexOfAsciiTriple(bytes, 0, length, b0, b1, b2, fromIndex, toIndex);
        assertThat(vectorHit).as("hit trial %d len %d", trial, length).isEqualTo(swarHit);
      }
    }
  }

  @Test
  void testReverseTripleCollapsesDuplicateNeedles() {
    // Guard byte sets come from case-fold expansion and routinely repeat a byte. Every duplicate
    // arrangement must agree with the distinct-needle reference.
    byte[] bytes = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(UTF_8);
    int length = bytes.length;
    byte x = 'x'; // index 33
    byte m = 'm'; // index 22

    for (byte[] needles :
        new byte[][] {{x, x, x}, {x, x, m}, {x, m, x}, {m, x, x}, {x, m, m}, {m, m, x}}) {
      int expected = -1;
      for (int i = length - 1; i >= 0; i--) {
        if (bytes[i] == needles[0] || bytes[i] == needles[1] || bytes[i] == needles[2]) {
          expected = i;
          break;
        }
      }
      assertThat(
              ByteSwarScan.lastIndexOfByteTriple(
                  bytes, 0, length, needles[0], needles[1], needles[2], length - 1, 0))
          .as("swar %s", Arrays.toString(needles))
          .isEqualTo(expected);
      if (isVectorApiAvailable()) {
        assertThat(
                ByteVectorScan.lastIndexOfAsciiTriple(
                    bytes, 0, length, needles[0], needles[1], needles[2], length - 1, 0))
            .as("vector %s", Arrays.toString(needles))
            .isEqualTo(expected);
      }
    }
  }

  @Test
  void testScannerReverseTripleWithLimit() {
    byte[] bytes = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(UTF_8);
    Utf8InputScanner scanner = new Utf8InputScanner(bytes);

    // 'b' at 11, 'm' at 22, 'Z' at 61
    assertThat(scanner.lastIndexOfAsciiTriple((byte) 'b', (byte) 'm', (byte) 'Z', 61, 23))
        .isEqualTo(61);
    assertThat(scanner.lastIndexOfAsciiTriple((byte) 'b', (byte) 'm', (byte) 'Z', 60, 23))
        .isEqualTo(-1);
    assertThat(scanner.lastIndexOfAsciiTriple((byte) 'b', (byte) 'm', (byte) 'Z', 60, 22))
        .isEqualTo(22);
    assertThat(scanner.lastIndexOfAsciiTriple((byte) 'b', (byte) 'm', (byte) 'Z', 21, 12))
        .isEqualTo(-1);
    assertThat(scanner.lastIndexOfAsciiTriple((byte) 'b', (byte) 'm', (byte) 'Z', 21, 11))
        .isEqualTo(11);
  }

  @Test
  void testScannerReverseTripleHonorsWindowCrossover() {
    // Same obligation as the reverse byte scan: a long input must not push a narrow reverse window
    // onto a vector kernel.
    int length = 4096;
    byte[] bytes = new byte[length];
    Random rnd = new Random(4243);
    for (int i = 0; i < length; i++) {
      bytes[i] = (byte) ('a' + rnd.nextInt(20)); // 'a'..'t' (no 'x', 'y', 'z')
    }
    Utf8InputScanner scanner = new Utf8InputScanner(bytes);

    for (int window : new int[] {1, 2, 7, 8, 15, 16, 31, 32, 63, 64, 65, 127, 128, 1024}) {
      for (int trial = 0; trial < 20; trial++) {
        int limit = rnd.nextInt(length - window);
        int fromIndex = limit + window - 1;
        int pos = limit + rnd.nextInt(window);
        bytes[pos] = (byte) "xyz".charAt(rnd.nextInt(3));

        assertThat(
                scanner.lastIndexOfAsciiTriple(
                    (byte) 'x', (byte) 'y', (byte) 'z', fromIndex, limit))
            .as("window %d trial %d", window, trial)
            .isEqualTo(lastIndexOfTripleReference(bytes, fromIndex, limit));

        Arrays.fill(bytes, limit, limit + window, (byte) 'a');
        assertThat(
                scanner.lastIndexOfAsciiTriple(
                    (byte) 'x', (byte) 'y', (byte) 'z', fromIndex, limit))
            .as("absent window %d trial %d", window, trial)
            .isEqualTo(-1);
      }
    }
  }

  private static int lastIndexOfTripleReference(byte[] bytes, int fromIndex, int limit) {
    for (int i = fromIndex; i >= limit; i--) {
      if (bytes[i] == 'x' || bytes[i] == 'y' || bytes[i] == 'z') {
        return i;
      }
    }
    return -1;
  }

  @Test
  void testScannerReverseByteHonorsWindowCrossover() {
    // A long input selects a vector provider, but individual reverse scans may span a window far
    // below the vector crossover; dispatch must be sized by the window, not by the input.
    int length = 4096;
    byte[] bytes = new byte[length];
    Random rnd = new Random(4242);
    for (int i = 0; i < length; i++) {
      bytes[i] = (byte) ('a' + rnd.nextInt(20)); // 'a'..'t' (no 'x')
    }
    Utf8InputScanner scanner = new Utf8InputScanner(bytes);

    for (int window : new int[] {1, 2, 7, 8, 15, 16, 31, 32, 63, 64, 65, 127, 128, 1024}) {
      for (int trial = 0; trial < 20; trial++) {
        int limit = rnd.nextInt(length - window);
        int fromIndex = limit + window - 1;
        bytes[limit + rnd.nextInt(window)] = 'x';

        assertThat(scanner.lastIndexOfAscii('x', fromIndex, limit))
            .as("window %d trial %d", window, trial)
            .isEqualTo(lastIndexOfReference(bytes, (byte) 'x', fromIndex, limit));

        Arrays.fill(bytes, limit, limit + window, (byte) 'a');
        assertThat(scanner.lastIndexOfAscii('x', fromIndex, limit))
            .as("absent window %d trial %d", window, trial)
            .isEqualTo(-1);
      }
    }
  }

  private static int lastIndexOfReference(byte[] bytes, byte needle, int fromIndex, int limit) {
    for (int i = fromIndex; i >= limit; i--) {
      if (bytes[i] == needle) {
        return i;
      }
    }
    return -1;
  }

  @Test
  void testStartAcceleratorSelection() {
    Pattern pPairClass = Pattern.compile("[YZ]");
    assertThat(pPairClass.utf8StartAccelerator())
        .isInstanceOf(Utf8StartAccelerator.CharClass.class);

    Pattern pPairAlt = Pattern.compile("Y|Z");
    Class<?> expectedPairAltClass =
        VectorScanProviders.vectorProviderAvailable()
            ? Utf8StartAccelerator.MultiLiteral.class
            : (VectorScanProviders.vectorProviderAvailable()
                ? Utf8StartAccelerator.Teddy.class
                : Utf8StartAccelerator.CharClass.class);
    assertThat(pPairAlt.utf8StartAccelerator()).isInstanceOf(expectedPairAltClass);

    Pattern pConsecutivePair = Pattern.compile("[ab]");
    assertThat(pConsecutivePair.utf8StartAccelerator())
        .isInstanceOf(Utf8StartAccelerator.CharClass.class);

    Pattern pTripleClass = Pattern.compile("[XYZ]");
    assertThat(pTripleClass.utf8StartAccelerator())
        .isInstanceOf(Utf8StartAccelerator.CharClass.class);

    Pattern pTripleAlt = Pattern.compile("X|Y|Z");
    Class<?> expectedTripleAltClass =
        VectorScanProviders.vectorProviderAvailable()
            ? Utf8StartAccelerator.MultiLiteral.class
            : (VectorScanProviders.vectorProviderAvailable()
                ? Utf8StartAccelerator.Teddy.class
                : Utf8StartAccelerator.CharClass.class);
    assertThat(pTripleAlt.utf8StartAccelerator()).isInstanceOf(expectedTripleAltClass);
  }

  @Test
  void testPatternMatchingWithPairAndTriple() {
    byte[] input = "the quick brown fox jumps over the lazy dog".getBytes(UTF_8);
    Utf8Input utf8 = Utf8Input.trusted(input);

    Pattern pPair = Pattern.compile("[jx]");
    Utf8Matcher mPair = pPair.matcher(utf8);
    assertThat(mPair.find()).isTrue();
    assertThat(mPair.start()).isEqualTo(18); // 'j' in jumps

    Pattern pTriple = Pattern.compile("[xyz]");
    Utf8Matcher mTriple = pTriple.matcher(utf8);
    assertThat(mTriple.find()).isTrue();
    assertThat(mTriple.start()).isEqualTo(18); // 'x' in fox is at 18? No, 'x' is at 18
  }

  @Test
  void testDfaSelfLoopPairAndTriple() {
    byte[] input = "START some arbitrary payload with delimiters Y and Z".getBytes(UTF_8);
    Utf8Input utf8 = Utf8Input.trusted(input);

    Pattern pPair = Pattern.compile("(?s)START.*?[YZ]");
    Utf8Matcher mPair = pPair.matcher(utf8);
    assertThat(mPair.find()).isTrue();
    assertThat(mPair.end()).isEqualTo(46); // index after 'Y'

    Pattern pTriple = Pattern.compile("(?s)START.*?[XYZ]");
    Utf8Matcher mTriple = pTriple.matcher(utf8);
    assertThat(mTriple.find()).isTrue();
    assertThat(mTriple.end()).isEqualTo(46);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 15, 16, 31, 32, 63, 64, 100, 128, 256, 500})
  void testPairIgnoreCaseEquivalenceWithSwar(int length) {
    assumeTrue(isVectorApiAvailable(), "Vector API not available on module path");

    String prefix = "content-type:";
    int prefixLen = prefix.length();
    RarityOracle.AsciiPair pair = RarityOracle.rarestAsciiPairIgnoreCase(prefix, prefixLen);
    assertThat(pair).isNotNull();
    Random rnd = new Random(3000 + length);

    for (int trial = 0; trial < 50; trial++) {
      byte[] bytes = new byte[length];
      for (int i = 0; i < length; i++) {
        bytes[i] = (byte) ('a' + rnd.nextInt(20));
      }
      int start = length == 0 ? 0 : rnd.nextInt(length);

      // Absent check
      int swarAbsent =
          ByteSwarScan.indexOfPairIgnoreCase(
              bytes,
              0,
              length,
              prefix,
              prefixLen,
              pair.offset1(),
              pair.low1(),
              pair.high1(),
              pair.offset2(),
              pair.low2(),
              pair.high2(),
              start);
      int vectorAbsent =
          ByteVectorScan.indexOfPairIgnoreCase(
              bytes,
              0,
              length,
              prefix,
              prefixLen,
              pair.offset1(),
              pair.low1(),
              pair.high1(),
              pair.offset2(),
              pair.low2(),
              pair.high2(),
              start);
      assertThat(vectorAbsent)
          .as("pair ignore case absent trial %d len %d", trial, length)
          .isEqualTo(swarAbsent);

      // Present check
      if (length >= start + prefixLen) {
        int pos = start + rnd.nextInt(length - start - prefixLen + 1);
        for (int i = 0; i < prefixLen; i++) {
          char c = prefix.charAt(i);
          bytes[pos + i] = (byte) (rnd.nextBoolean() ? Ascii.toLowerCase(c) : Ascii.toUpperCase(c));
        }

        int swarHit =
            ByteSwarScan.indexOfPairIgnoreCase(
                bytes,
                0,
                length,
                prefix,
                prefixLen,
                pair.offset1(),
                pair.low1(),
                pair.high1(),
                pair.offset2(),
                pair.low2(),
                pair.high2(),
                start);
        int vectorHit =
            ByteVectorScan.indexOfPairIgnoreCase(
                bytes,
                0,
                length,
                prefix,
                prefixLen,
                pair.offset1(),
                pair.low1(),
                pair.high1(),
                pair.offset2(),
                pair.low2(),
                pair.high2(),
                start);
        assertThat(vectorHit)
            .as("pair ignore case hit trial %d len %d", trial, length)
            .isEqualTo(swarHit);
      }
    }
  }

  @Test
  void testPatternMatchingWithCaseInsensitivePair() {
    byte[] input = "header: some-value\r\nCoNtEnT-TyPe: application/json\r\n\r\n".getBytes(UTF_8);
    Utf8Input utf8 = Utf8Input.trusted(input);

    Pattern p = Pattern.compile("(?i)content-type:");
    assertThat(p.utf8StartAccelerator())
        .isInstanceOf(Utf8StartAccelerator.CaseInsensitiveLiteral.class);

    Utf8Matcher m = p.matcher(utf8);
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(20);
    assertThat(m.end()).isEqualTo(33);
  }
}
