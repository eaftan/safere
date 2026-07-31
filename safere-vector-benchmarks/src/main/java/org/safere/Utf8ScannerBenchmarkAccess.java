// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Benchmark-only access to the current UTF-8 scanner implementation. */
public final class Utf8ScannerBenchmarkAccess {
  private final Utf8InputScanner scanner;

  /** Creates a scanner over the requested borrowed-array window. */
  public Utf8ScannerBenchmarkAccess(byte[] bytes, int offset, int length) {
    scanner = new Utf8InputScanner(bytes, offset, length);
  }

  /** Returns the first byte position in the supplied code-point ranges. */
  public int indexOfCodePointClass(int[] ranges, int start) {
    return scanner.indexOfCodePointClass(ranges, 0, 0, start);
  }
}
