// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisabledForCrosscheck("package-private input scanner tests exercise SafeRE internals")
class InputScannerTest {
  @ParameterizedTest
  @ValueSource(strings = {"Y", "YZ", "XZ_", "\u0000?\u007F"})
  void smallAsciiClassSearchReturnsEarliestMemberAcrossStringEncodings(String members) {
    AsciiBitmap.Builder bitmap = new AsciiBitmap.Builder();
    for (int i = 0; i < members.length(); i++) {
      bitmap.add(members.charAt(i));
    }
    CharClassScanInfo info = CharClassScanInfo.fromAsciiBitmap(bitmap.build());
    Random random = new Random(20261001);
    for (boolean wide : new boolean[] {false, true}) {
      char[] chars = new char[8197];
      for (int i = 0; i < chars.length; i++) {
        chars[i] = (char) random.nextInt(wide ? 65536 : 256);
      }
      for (int position : new int[] {0, 15, 16, 31, 127, 4095, 4096, 8192, 8196}) {
        chars[position] = members.charAt(random.nextInt(members.length()));
      }
      String text = new String(chars);
      StringInputScanner scanner = new StringInputScanner(text);
      for (int start : new int[] {-1, 0, 1, 15, 16, 32, 128, 4095, 4096, 8193, 8197, 8198}) {
        int expected = -1;
        for (int i = Math.max(0, start); i < text.length(); i++) {
          if (members.indexOf(text.charAt(i)) >= 0) {
            expected = i;
            break;
          }
        }
        assertThat(scanner.indexOfCharClass(info, start))
            .as("members %s, wide %s, start %s", members, wide, start)
            .isEqualTo(expected);
      }
    }
  }

  @Test
  void smallAsciiClassSearchSkipsUnicodeAndUnpairedSurrogates() {
    AsciiBitmap.Builder bitmap = new AsciiBitmap.Builder();
    bitmap.add('X');
    bitmap.add('Z');
    bitmap.add('_');
    CharClassScanInfo info = CharClassScanInfo.fromAsciiBitmap(bitmap.build());
    String noise = "é中😀\uD800a\uDC00".repeat(1500);
    StringInputScanner scanner = new StringInputScanner(noise + "_ZX");

    assertThat(scanner.indexOfCharClass(info, 0)).isEqualTo(noise.length());
    assertThat(scanner.indexOfCharClass(info, noise.length() + 1)).isEqualTo(noise.length() + 1);
    assertThat(new StringInputScanner(noise).indexOfCharClass(info, 0)).isEqualTo(-1);
  }

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

    assertThat(stringScanner.indexOfCodePointClass(cjk, 0, 0, 0, stringScanner.length()))
        .isEqualTo(4);
    assertThat(stringScanner.indexOfCodePointClass(supplementary, 0, 0, 0, stringScanner.length()))
        .isEqualTo(2);
    assertThat(stringScanner.indexOfCodePointClass(cjk, 0, 0, 5, stringScanner.length()))
        .isEqualTo(-1);
    assertThat(utf8Scanner.indexOfCodePointClass(cjk, 0, 0, 0, utf8Scanner.length())).isEqualTo(7);
    assertThat(utf8Scanner.indexOfCodePointClass(supplementary, 0, 0, 0, utf8Scanner.length()))
        .isEqualTo(3);
    assertThat(utf8Scanner.indexOfCodePointClass(cjk, 0, 0, 10, utf8Scanner.length()))
        .isEqualTo(-1);

    Utf8InputScanner paddedUtf8Scanner =
        new Utf8InputScanner(("a".repeat(24) + "中").getBytes(UTF_8));
    assertThat(paddedUtf8Scanner.indexOfCodePointClass(cjk, 0, 0, 0, paddedUtf8Scanner.length()))
        .isEqualTo(24);
  }
}
