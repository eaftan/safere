// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("package-private input scanner tests exercise SafeRE internals")
class InputScannerTest {
  @Test
  void stringScannerReadsSingleCharCodePointsWithoutFullDecoding() {
    StringInputScanner scanner = new StringInputScanner("Aé😀\uD800Z");

    assertThat(scanner.singleUnitCodePointAt(0)).isEqualTo('A');
    assertThat(scanner.singleUnitCodePointAt(1)).isEqualTo('é');
    assertThat(scanner.singleUnitCodePointAt(2)).isEqualTo(-1);
    assertThat(scanner.singleUnitCodePointAt(4)).isEqualTo('\uD800');
    assertThat(scanner.singleUnitCodePointBefore(6)).isEqualTo('Z');
    assertThat(scanner.singleUnitCodePointBefore(5)).isEqualTo('\uD800');
    assertThat(scanner.singleUnitCodePointBefore(4)).isEqualTo(-1);
  }

  @Test
  void utf8ScannerReadsOnlyAsciiWithoutFullDecoding() {
    Utf8InputScanner scanner = new Utf8InputScanner("Aé😀Z".getBytes(UTF_8));

    assertThat(scanner.singleUnitCodePointAt(0)).isEqualTo('A');
    assertThat(scanner.singleUnitCodePointAt(1)).isEqualTo(-1);
    assertThat(scanner.singleUnitCodePointAt(3)).isEqualTo(-1);
    assertThat(scanner.singleUnitCodePointBefore(scanner.length())).isEqualTo('Z');
    assertThat(scanner.singleUnitCodePointBefore(scanner.length() - 1)).isEqualTo(-1);
  }

  @Test
  void codePointClassSearchUsesRepresentationCoordinates() {
    int[] cjk = {'中', '中'};
    int[] supplementary = {0x1F600, 0x1F600};
    String text = "aé😀中z";
    StringInputScanner stringScanner = new StringInputScanner(text);
    Utf8InputScanner utf8Scanner = new Utf8InputScanner(text.getBytes(UTF_8));

    assertThat(stringScanner.indexOfCodePointClass(cjk, 0, 0, 0)).isEqualTo(4);
    assertThat(stringScanner.indexOfCodePointClass(supplementary, 0, 0, 0)).isEqualTo(2);
    assertThat(stringScanner.indexOfCodePointClass(cjk, 0, 0, 5)).isEqualTo(-1);
    assertThat(utf8Scanner.indexOfCodePointClass(cjk, 0, 0, 0)).isEqualTo(7);
    assertThat(utf8Scanner.indexOfCodePointClass(supplementary, 0, 0, 0)).isEqualTo(3);
    assertThat(utf8Scanner.indexOfCodePointClass(cjk, 0, 0, 10)).isEqualTo(-1);

    Utf8InputScanner paddedUtf8Scanner =
        new Utf8InputScanner(("a".repeat(24) + "中").getBytes(UTF_8));
    assertThat(paddedUtf8Scanner.indexOfCodePointClass(cjk, 0, 0, 0)).isEqualTo(24);
  }
}
