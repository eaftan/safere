// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Java 21 fallback replaced by the JDK 26 implementation in the multi-release JAR. */
final class VectorScanProviderFactory {
  private VectorScanProviderFactory() {}

  static VectorScanProvider create() {
    return null;
  }
}
