// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

/** JDK 26 Vector API acceleration for SafeRE UTF-8 scans. */
module org.safere.vector.jdk26 {
  requires jdk.incubator.vector;
  requires org.safere;

  provides org.safere.spi.Utf8ScanProvider with
      org.safere.vector.jdk26.Jdk26VectorUtf8ScanProvider;
}
