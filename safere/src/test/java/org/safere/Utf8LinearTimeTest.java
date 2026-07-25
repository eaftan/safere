// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Work-bound tests for forward and reverse UTF-8 decoding. */
@DisabledForCrosscheck("package-private UTF-8 decoder work accounting is SafeRE-internal")
@Tag("work-counter")
class Utf8LinearTimeTest {
  @Test
  void forwardAndReverseDecodingScaleLinearlyForValidAndMalformedInput() {
    assertFourXInputStaysNearLinear(size -> scanBothDirections(validInput(size)));
    assertFourXInputStaysNearLinear(size -> scanBothDirections(malformedInput(size)));
  }

  @Test
  void matchingBehaviorsScaleLinearlyOverUtf8Input() {
    assertLargeFourXInputStaysNearLinear(
        size -> findAll("[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$", "a".repeat(size).getBytes(UTF_8)));
    String overlappingLiteral = "a".repeat(1_000) + "b";
    assertLargeFourXInputStaysNearLinear(
        size -> findAll(overlappingLiteral, "a".repeat(size).getBytes(UTF_8)));
    assertLargeFourXInputStaysNearLinear(size -> findAll("", "😀".repeat(size).getBytes(UTF_8)));
    assertLargeFourXInputStaysNearLinear(
        size -> findAll("([a-z]+)([0-9]+)", "a1".repeat(size).getBytes(UTF_8)));
    assertLargeFourXInputStaysNearLinear(
        size -> findAll("\\bword\\b", "word ".repeat(size).getBytes(UTF_8)));
    assertLargeFourXInputStaysNearLinear(size -> findAll("\\X", "á".repeat(size).getBytes(UTF_8)));
    assertLargeFourXInputStaysNearLinear(size -> findAll(".", malformedInput(size)));
  }

  @Test
  void singleValueAsciiPrefixScanAccountsForEveryExaminedByte() {
    byte[] input = "a".repeat(10_000).getBytes(UTF_8);
    Utf8InputScanner scanner = new Utf8InputScanner(input);
    boolean[] asciiClass = new boolean[128];
    asciiClass['z'] = true;

    long work =
        WorkCounter.countForTesting(
            () -> assertThat(scanner.indexOfAsciiClass(asciiClass, 0)).isEqualTo(-1));

    assertThat(work).isEqualTo(input.length);
  }

  private static void assertFourXInputStaysNearLinear(Consumer<Integer> task) {
    task.accept(10_000);
    task.accept(40_000);
    long smallerWork = WorkCounter.countForTesting(() -> task.accept(10_000));
    long largerWork = WorkCounter.countForTesting(() -> task.accept(40_000));

    assertThat(smallerWork).isPositive();
    assertThat(largerWork).isLessThan(smallerWork * 5);
  }

  private static void assertLargeFourXInputStaysNearLinear(Consumer<Integer> task) {
    task.accept(40_000);
    task.accept(160_000);
    long smallerWork = WorkCounter.countForTesting(() -> task.accept(40_000));
    long largerWork = WorkCounter.countForTesting(() -> task.accept(160_000));

    assertThat(smallerWork).isPositive();
    assertThat(largerWork).isLessThan(smallerWork * 5);
  }

  private static void scanBothDirections(byte[] bytes) {
    Utf8InputScanner scanner = new Utf8InputScanner(bytes);
    int position = 0;
    while (position < scanner.length()) {
      position = InputScanner.position(scanner.decodeForward(position));
    }
    assertThat(position).isEqualTo(scanner.length());

    while (position > 0) {
      position = InputScanner.position(scanner.decodeBackward(position));
    }
    assertThat(position).isZero();
  }

  private static void findAll(String regex, byte[] bytes) {
    Utf8Matcher matcher = Pattern.compile(regex).matcher(Utf8Input.trusted(bytes));
    while (matcher.find()) {
      matcher.start();
      matcher.end();
    }
  }

  private static byte[] validInput(int scalars) {
    return "aé😀".repeat(scalars / 3).getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  private static byte[] malformedInput(int bytes) {
    byte[] input = new byte[bytes];
    java.util.Arrays.fill(input, (byte) 0x80);
    return input;
  }
}
