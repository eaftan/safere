// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisabledForCrosscheck("Internal scanner coordinates and opaque storage bounds")
class StringRegionInputScannerTest {
  @Test
  void foldedVerificationFallbackHonorsBothStorageBounds() {
    String literal = "a".repeat(128) + "b";
    String text = "#" + "A".repeat(4096) + "b!";
    StringRegionInputScanner clipped = new StringRegionInputScanner(text, 1, text.length() - 2);
    StringRegionInputScanner complete = new StringRegionInputScanner(text, 1, text.length() - 1);
    // Deliberately use a dense anchor to exercise verification-budget exhaustion and KMP.
    assertThat(clipped.indexOfIgnoreCase(literal, 0, 'a', 'A', 0)).isEqualTo(-1);
    assertThat(complete.indexOfIgnoreCase(literal, 0, 'a', 'A', 0)).isEqualTo(4096 - 128);
    assertThat(complete.indexOfIgnoreCase(literal, 0, 'a', 'A', 4096)).isEqualTo(-1);
  }

  @Test
  void terminalSuffixFiltersAgreeWithTheCopiedRegion() {
    EnginePathOptions options = EnginePathOptions.allEnabled();
    for (String text : new String[] {"!ab!", "!ab\r\n!", "!ab\n!", "!ab\u0085!", "!Kk!"}) {
      for (int start = 0; start <= text.length(); start++) {
        for (int end = start; end <= text.length(); end++) {
          String copy = text.substring(start, end);
          StringRegionInputScanner view = new StringRegionInputScanner(text, start, end);
          for (String suffix : new String[] {"b", "ab", "k", "\r"}) {
            for (boolean dollar : new boolean[] {false, true}) {
              for (boolean unix : new boolean[] {false, true}) {
                for (boolean fold : new boolean[] {false, true}) {
                  RejectPrefilter filter =
                      new RejectPrefilter.EndAnchoredSuffix(
                          suffix, suffix.getBytes(UTF_8), dollar, unix, fold);
                  assertThat(filter.canReject(view, null, 0, options))
                      .isEqualTo(filter.canReject(new StringInputScanner(copy), copy, 0, options));
                }
              }
            }
          }
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"!abc!", "x😀y", "\r\n", "a\u0085b\u2028c\u2029", "\uD800a\uDC00"})
  void boundedDecodingAndSearchAgreeWithAnIndependentSubstring(String text) {
    for (int start = 0; start <= text.length(); start++) {
      for (int end = start; end <= text.length(); end++) {
        StringInputScanner copy = new StringInputScanner(text.substring(start, end));
        StringRegionInputScanner view = new StringRegionInputScanner(text, start, end);
        assertThat(view.length()).isEqualTo(copy.length());
        for (int pos = 0; pos <= view.length(); pos++) {
          assertThat(view.decodeForward(pos)).isEqualTo(copy.decodeForward(pos));
          assertThat(view.decodeBackward(pos)).isEqualTo(copy.decodeBackward(pos));
          for (int limit = pos; limit <= view.length(); limit++) {
            assertThat(view.decodeForward(pos, limit)).isEqualTo(copy.decodeForward(pos, limit));
          }
          for (int lower = 0; lower <= pos; lower++) {
            assertThat(view.decodeBackward(pos, lower)).isEqualTo(copy.decodeBackward(pos, lower));
          }
          assertThat(view.isCodePointBoundary(pos)).isEqualTo(copy.isCodePointBoundary(pos));
          if (pos < view.length()) {
            assertThat(view.asciiAt(pos)).isEqualTo(copy.asciiAt(pos));
            assertThat(view.singleUnitCodePointAt(pos)).isEqualTo(copy.singleUnitCodePointAt(pos));
          }
          if (pos > 0) {
            assertThat(view.singleUnitCodePointBefore(pos))
                .isEqualTo(copy.singleUnitCodePointBefore(pos));
          }
          for (int limit = pos; limit <= view.length(); limit++) {
            assertThat(view.indexOfAscii('a', pos, limit))
                .isEqualTo(copy.indexOfAscii('a', pos, limit));
            assertThat(view.indexOfAsciiPair('a', 'b', pos, limit))
                .isEqualTo(copy.indexOfAsciiPair('a', 'b', pos, limit));
            assertThat(view.indexOfAsciiTriple('a', 'b', 'c', pos, limit))
                .isEqualTo(copy.indexOfAsciiTriple('a', 'b', 'c', pos, limit));
            assertThat(view.indexOfAsciiOrNonAscii('a', pos, limit))
                .isEqualTo(copy.indexOfAsciiOrNonAscii('a', pos, limit));
            assertThat(view.indexOfAsciiPairOrNonAscii('a', 'b', pos, limit))
                .isEqualTo(copy.indexOfAsciiPairOrNonAscii('a', 'b', pos, limit));
            assertThat(view.indexOfAsciiTripleOrNonAscii('a', 'b', 'c', pos, limit))
                .isEqualTo(copy.indexOfAsciiTripleOrNonAscii('a', 'b', 'c', pos, limit));
          }
          for (boolean unix : new boolean[] {false, true}) {
            assertThat(view.trailingLineTerminatorStart(unix, pos))
                .isEqualTo(copy.trailingLineTerminatorStart(unix, pos));
          }
        }
      }
    }
  }
}
