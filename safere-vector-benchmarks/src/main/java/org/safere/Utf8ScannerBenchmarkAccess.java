// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Benchmark-only access to the current UTF-8 scanner implementation. */
public final class Utf8ScannerBenchmarkAccess {
  private final Utf8InputScanner scanner;
  private final int[] ranges;
  private final long bitmap0;
  private final long bitmap1;

  /** Creates a scanner over the requested borrowed-array window and code-point ranges. */
  public Utf8ScannerBenchmarkAccess(byte[] bytes, int offset, int length, int[] ranges) {
    scanner = new Utf8InputScanner(bytes, offset, length);
    this.ranges = ranges;
    bitmap0 = asciiBitmap(ranges, 0, 63);
    bitmap1 = asciiBitmap(ranges, 64, 127);
  }

  /** Returns the first byte position in the configured code-point ranges. */
  public int indexOfCodePointClass(int start) {
    return scanner.indexOfCodePointClass(ranges, bitmap0, bitmap1, start);
  }

  private static long asciiBitmap(int[] ranges, int first, int last) {
    long bitmap = 0;
    for (int index = 0; index < ranges.length; index += 2) {
      int low = Math.max(first, ranges[index]);
      int high = Math.min(last, ranges[index + 1]);
      for (int value = low; value <= high; value++) {
        bitmap |= 1L << (value - first);
      }
    }
    return bitmap;
  }
}
