// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.vector;

/** Loads the vector scanner implementation via reflection if available on the classpath. */
public final class VectorScannerLoader {
  private static final VectorScannerBridge INSTANCE = loadInstance();

  private static VectorScannerBridge loadInstance() {
    try {
      Class<? extends VectorScannerBridge> implClass =
          Class.forName("org.safere.vector.VectorScannerImpl")
              .asSubclass(VectorScannerBridge.class);
      return implClass.getDeclaredConstructor().newInstance();
    } catch (Throwable t) {
      // Gracefully fall back to scalar matching (null) if:
      // - safere-vector jar is not on the classpath.
      // - JVM was run without --enable-preview or jdk.incubator.vector module.
      return null;
    }
  }

  private VectorScannerLoader() {}

  /** Returns the vector scanner instance, or {@code null} if not supported at runtime. */
  public static VectorScannerBridge getInstance() {
    return INSTANCE;
  }
}
