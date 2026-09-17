// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisabledForCrosscheck("package-private byte scanner tests exercise SafeRE internals")
class ByteScanTest {
  @ParameterizedTest
  @CsvSource({
    "false, 0, false", "false, 3, false", "false, 0, true", "false, 3, true",
    "true, 0, false", "true, 3, false", "true, 0, true", "true, 3, true"
  })
  void caseInsensitivePairSearchRespectsFullPrefixBounds(
      boolean vector, int offset, boolean padded) {
    if (vector) {
      assumeTrue(isVectorApiAvailable(), "Vector API not available on module path");
    }
    // Exercise physical array ends and logical slice ends with matching bytes beyond the slice.
    for (int prefixLength : new int[] {8, 9, 10, 16, 24, 65, 80}) {
      String prefix = "abcdefghijklmnopqrstuvwxyz".repeat(4).substring(0, prefixLength);
      byte[] upper = prefix.toUpperCase(Locale.ROOT).getBytes(UTF_8);
      for (int anchorOffset : new int[] {1, prefixLength / 2, prefixLength - 1}) {
        // Cross word and vector boundaries, including loads with incomplete prefix candidates.
        for (int position :
            IntStream.concat(
                    IntStream.range(0, 24), IntStream.of(31, 32, 63, 64, 65, 127, 128, 129))
                .toArray()) {
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
              int actual =
                  vector
                      ? ByteVectorScan.indexOfPairIgnoreCase(
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
                          start)
                      : ByteSwarScan.indexOfPairIgnoreCase(
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
                          start);
              assertThat(actual)
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

  private static boolean isVectorApiAvailable() {
    try {
      Class.forName("jdk.incubator.vector.ByteVector");
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }
}
