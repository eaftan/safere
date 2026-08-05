// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Constructs the optional incubator Vector API provider. */
final class VectorScanProviderFactory {
  private static final String VECTOR_MODULE_NAME = "jdk.incubator.vector";

  private VectorScanProviderFactory() {}

  static VectorScanProvider create() {
    Module safeReModule = VectorScanProviderFactory.class.getModule();
    if (safeReModule.isNamed()) {
      Module vectorModule =
          ModuleLayer.boot()
              .findModule(VECTOR_MODULE_NAME)
              .orElseThrow(
                  () ->
                      new IllegalStateException(VECTOR_MODULE_NAME + " is not in the boot layer"));
      safeReModule.addReads(vectorModule);
    }
    return new IncubatorVectorScanProvider();
  }
}
