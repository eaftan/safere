// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.util.List;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("package-private UTF-8 scanner tests exercise SafeRE internals")
class Utf8InputScannerTest {
  @Test
  void decodesEveryUnicodeScalarInBothDirections() {
    for (int expected = 0; expected <= Character.MAX_CODE_POINT; expected++) {
      if (expected >= Character.MIN_SURROGATE && expected <= Character.MAX_SURROGATE) {
        continue;
      }
      byte[] bytes = new String(Character.toChars(expected)).getBytes(UTF_8);
      Utf8InputScanner scanner = new Utf8InputScanner(bytes, 0, bytes.length);

      long forward = scanner.decodeForward(0);
      long backward = scanner.decodeBackward(bytes.length);
      if (InputScanner.codePoint(forward) != expected
          || InputScanner.position(forward) != bytes.length
          || InputScanner.codePoint(backward) != expected
          || InputScanner.position(backward) != 0) {
        fail("Incorrect UTF-8 decode for U+%04X", expected);
      }
    }
  }

  @Test
  void malformedBytesRecoverOneByteInBothDirections() {
    for (byte[] malformed : malformedSequences()) {
      Utf8InputScanner scanner = new Utf8InputScanner(malformed, 0, malformed.length);
      int position = 0;
      while (position < malformed.length) {
        long decoded = scanner.decodeForward(position);
        assertThat(InputScanner.codePoint(decoded)).isEqualTo(0xFFFD);
        assertThat(InputScanner.position(decoded)).isEqualTo(position + 1);
        position++;
      }

      position = malformed.length;
      while (position > 0) {
        long decoded = scanner.decodeBackward(position);
        assertThat(InputScanner.codePoint(decoded)).isEqualTo(0xFFFD);
        assertThat(InputScanner.position(decoded)).isEqualTo(position - 1);
        position--;
      }
    }
  }

  @Test
  void validationRejectsFirstMalformedRelativeOffset() {
    byte[] bytes = {'x', (byte) 0xF0, (byte) 0x80, (byte) 0x80, (byte) 0x80, 'y'};

    assertThatThrownBy(() -> Utf8InputScanner.validate(bytes, 0, bytes.length))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("byte 1");
  }

  @Test
  void validationAcceptsEveryBoundaryScalar() {
    byte[] bytes =
        "\u0000\u007F\u0080\u07FF\u0800\uD7FF\uE000\uFFFF"
            .concat(new String(Character.toChars(0x10000)))
            .concat(new String(Character.toChars(0x10FFFF)))
            .getBytes(UTF_8);

    Utf8InputScanner.validate(bytes, 0, bytes.length);
  }

  @Test
  void windowCoordinatesAreRelativeAndCannotReadSentinels() {
    byte[] logical = "aé😀z".getBytes(UTF_8);
    byte[] storage = new byte[logical.length + 4];
    storage[0] = (byte) 0x80;
    storage[1] = (byte) 0x80;
    System.arraycopy(logical, 0, storage, 2, logical.length);
    storage[storage.length - 2] = (byte) 0x80;
    storage[storage.length - 1] = (byte) 0x80;
    Utf8InputScanner scanner = new Utf8InputScanner(storage, 2, logical.length);

    assertThat(scanner.length()).isEqualTo(logical.length);
    assertThat(traceForward(scanner)).containsExactly((int) 'a', (int) 'é', 0x1F600, (int) 'z');
    assertThat(traceBackward(scanner)).containsExactly((int) 'z', 0x1F600, (int) 'é', (int) 'a');
    assertThat(scanner.decodeForward(scanner.length()))
        .isEqualTo(InputScanner.decoded(-1, scanner.length()));
    assertThat(scanner.decodeBackward(0)).isEqualTo(InputScanner.decoded(-1, 0));
  }

  @Test
  void emptySingleByteAndFourByteWindowsStayBounded() {
    Utf8InputScanner empty = new Utf8InputScanner(new byte[] {'x'}, 1, 0);
    assertThat(empty.decodeForward(0)).isEqualTo(InputScanner.decoded(-1, 0));
    assertThat(empty.decodeBackward(0)).isEqualTo(InputScanner.decoded(-1, 0));

    Utf8InputScanner single = new Utf8InputScanner(new byte[] {'x', 'a', 'y'}, 1, 1);
    assertThat(single.decodeForward(0)).isEqualTo(InputScanner.decoded('a', 1));
    assertThat(single.decodeBackward(1)).isEqualTo(InputScanner.decoded('a', 0));

    byte[] scalar = "😀".getBytes(UTF_8);
    byte[] storage = new byte[scalar.length + 2];
    System.arraycopy(scalar, 0, storage, 1, scalar.length);
    Utf8InputScanner fourByte = new Utf8InputScanner(storage, 1, scalar.length);
    assertThat(fourByte.decodeForward(0)).isEqualTo(InputScanner.decoded(0x1F600, 4));
    assertThat(fourByte.decodeBackward(4)).isEqualTo(InputScanner.decoded(0x1F600, 0));
  }

  @Test
  void windowBoundsRejectOverflowAndPartialScalars() {
    byte[] bytes = "xéy".getBytes(UTF_8);

    assertThatThrownBy(() -> new Utf8InputScanner(bytes, -1, 1))
        .isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> new Utf8InputScanner(bytes, 0, Integer.MAX_VALUE))
        .isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> Utf8InputScanner.validate(bytes, 2, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("byte 0");
  }

  @Test
  void codePointBoundariesRecognizeOnlyScalarEdges() {
    byte[] bytes = "aé😀z".getBytes(UTF_8);
    Utf8InputScanner scanner = new Utf8InputScanner(bytes, 0, bytes.length);

    assertThat(scanner.isCodePointBoundary(0)).isTrue();
    assertThat(scanner.isCodePointBoundary(1)).isTrue();
    assertThat(scanner.isCodePointBoundary(2)).isFalse();
    assertThat(scanner.isCodePointBoundary(3)).isTrue();
    assertThat(scanner.isCodePointBoundary(4)).isFalse();
    assertThat(scanner.isCodePointBoundary(5)).isFalse();
    assertThat(scanner.isCodePointBoundary(6)).isFalse();
    assertThat(scanner.isCodePointBoundary(7)).isTrue();
    assertThat(scanner.isCodePointBoundary(8)).isTrue();
  }

  @Test
  void malformedRecoveryMakesEveryRecoveredByteABoundary() {
    byte[] bytes = {(byte) 0xF0, (byte) 0x80, (byte) 0x80, (byte) 0x80};
    Utf8InputScanner scanner = new Utf8InputScanner(bytes);

    for (int position = 0; position <= bytes.length; position++) {
      assertThat(scanner.isCodePointBoundary(position)).as("position %s", position).isTrue();
    }
  }

  private static List<Integer> traceForward(Utf8InputScanner scanner) {
    java.util.ArrayList<Integer> result = new java.util.ArrayList<>();
    int position = 0;
    while (position < scanner.length()) {
      long decoded = scanner.decodeForward(position);
      result.add(InputScanner.codePoint(decoded));
      position = InputScanner.position(decoded);
    }
    return result;
  }

  private static List<Integer> traceBackward(Utf8InputScanner scanner) {
    java.util.ArrayList<Integer> result = new java.util.ArrayList<>();
    int position = scanner.length();
    while (position > 0) {
      long decoded = scanner.decodeBackward(position);
      result.add(InputScanner.codePoint(decoded));
      position = InputScanner.position(decoded);
    }
    return result;
  }

  private static List<byte[]> malformedSequences() {
    return List.of(
        new byte[] {(byte) 0x80},
        new byte[] {(byte) 0xC0},
        new byte[] {(byte) 0xC1},
        new byte[] {(byte) 0xC2},
        new byte[] {(byte) 0xE0},
        new byte[] {(byte) 0xF0},
        new byte[] {(byte) 0xF5},
        new byte[] {(byte) 0xFF},
        new byte[] {(byte) 0xC0, (byte) 0x80},
        new byte[] {(byte) 0xE0, (byte) 0x80, (byte) 0x80},
        new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80},
        new byte[] {(byte) 0xF0, (byte) 0x80, (byte) 0x80, (byte) 0x80},
        new byte[] {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80});
  }
}
