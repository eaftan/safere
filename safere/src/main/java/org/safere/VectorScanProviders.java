// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * Selects the optional Vector scan provider once, without linking Java 21 classes to a JDK-specific
 * API.
 */
final class VectorScanProviders {
  static final String PROVIDER_PROPERTY = "org.safere.experimental.vectorScanProvider";
  private static final VectorScanProvider SELECTED = loadSelected();

  private VectorScanProviders() {}

  static VectorScanProvider providerForLength(int length) {
    return SELECTED != null && length >= SELECTED.minimumInputLength() ? SELECTED : null;
  }

  private static VectorScanProvider loadSelected() {
    String requested = System.getProperty(PROVIDER_PROPERTY, "").trim();
    if (requested.isEmpty() || requested.equals("swar")) {
      return null;
    }
    if (!requested.equals("vector")) {
      throw new IllegalStateException("Unknown Vector scan provider '" + requested + "'");
    }
    try {
      VectorScanProvider provider = VectorScanProviderFactory.create();
      if (provider == null) {
        throw new IllegalStateException(
            "The SafeRE JAR does not contain a Vector scanner for this JDK");
      }
      return provider;
    } catch (RuntimeException | LinkageError e) {
      throw new IllegalStateException(
          "Could not enable the experimental Vector UTF-8 scanner; use JDK 26 and add "
              + "--add-modules=jdk.incubator.vector",
          e);
    }
  }
}
