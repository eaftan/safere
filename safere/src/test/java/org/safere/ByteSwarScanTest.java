// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisabledForCrosscheck("package-private byte scanner tests exercise SafeRE internals")
class ByteSwarScanTest {
  @ParameterizedTest
  @CsvSource({"0, false", "3, false", "0, true", "3, true"})
  void caseInsensitivePairSearchRespectsFullPrefixBounds(int offset, boolean padded) {
    // Exercise physical array ends and logical slice ends with matching bytes beyond the slice.
    for (int prefixLength : new int[] {8, 9, 10, 16, 24}) {
      String prefix = "abcdefghijklmnopqrstuvwx".substring(0, prefixLength);
      byte[] upper = prefix.toUpperCase(Locale.ROOT).getBytes(UTF_8);
      for (int anchorOffset : new int[] {1, prefixLength / 2, prefixLength - 1}) {
        for (int position = 0; position < 24; position++) {
          for (int available = 0; available <= prefixLength + 1; available++) {
            int length = position + available;
            byte[] bytes = new byte[offset + length + (padded ? prefixLength : 0)];
            Arrays.fill(bytes, (byte) '.');
            System.arraycopy(
                upper,
                0,
                bytes,
                offset + position,
                Math.min(prefixLength, bytes.length - offset - position));
            for (int start : new int[] {0, position, position + 1}) {
              int expected = available >= prefixLength && start <= position ? position : -1;
              assertThat(
                      ByteSwarScan.indexOfPairIgnoreCase(
                          bytes,
                          offset,
                          length,
                          prefix,
                          prefixLength,
                          0,
                          (byte) 'a',
                          (byte) 'A',
                          anchorOffset,
                          (byte) prefix.charAt(anchorOffset),
                          upper[anchorOffset],
                          start))
                  .as(
                      "prefix %s, anchor %s, position %s, available %s, start %s",
                      prefixLength, anchorOffset, position, available, start)
                  .isEqualTo(expected);
            }
          }
        }
      }
    }
  }
}
