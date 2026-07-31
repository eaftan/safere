// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.spi;

/**
 * A provider for specialized ASCII character-class scans over UTF-8 storage.
 *
 * <p>This service-provider interface is intended for optional, JDK-specific SafeRE artifacts. A
 * provider must be thread-safe and must not modify the supplied arrays.
 */
public interface Utf8ScanProvider {
  /** Returns the value used to select this provider with {@code org.safere.utf8ScanProvider}. */
  String name();

  /** Returns the minimum remaining input length for which this provider should be used. */
  int minimumInputLength();

  /**
   * Finds the first byte in the logical input that belongs to the supplied ASCII class.
   *
   * @param bytes backing UTF-8 storage
   * @param offset start of the logical input in {@code bytes}
   * @param length logical input length in bytes
   * @param ranges one inclusive range or two singleton ranges, all in ASCII
   * @param start first logical input position to inspect
   * @return the logical position of the first match, or {@code -1} if none exists
   */
  int indexOfAsciiClass(byte[] bytes, int offset, int length, int[] ranges, int start);
}
