// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.vector;

/**
 * Interface for vector scanning implementations.
 */
public interface VectorScannerBridge {
  /**
   * Scans the array for the target character between fromIndex and toIndex.
   * Returns the index of the first occurrence, or -1 if not found.
   */
  int scan(char[] array, char target, int fromIndex, int toIndex);

  /**
   * Scans the string directly using reflection-based backing array access if supported.
   * Returns the index of the first occurrence, -1 if not found, or -2 if direct scanning
   * is not supported or failed.
   */
  int scanString(String text, char target, int fromIndex, int toIndex);
}
