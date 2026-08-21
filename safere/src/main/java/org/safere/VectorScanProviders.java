// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Selects the optional Vector scan provider once without loading it unless requested. */
final class VectorScanProviders {
  static final String PROVIDER_PROPERTY = "org.safere.experimental.vectorScanProvider";
  private static final VectorScanProvider SELECTED = loadSelected();

  private VectorScanProviders() {}

  static VectorScanProvider providerForLength(int length) {
    return SELECTED != null && length >= SELECTED.minimumInputLength() ? SELECTED : null;
  }

  static VectorScanProvider providerForTeddyLength(int length) {
    return SELECTED != null && length >= SELECTED.minimumTeddyInputLength() ? SELECTED : null;
  }

  static boolean teddyProviderAvailable() {
    return SELECTED != null;
  }

  static VectorScanProvider providerForMultiLiteralLength(int length) {
    return SELECTED != null && length >= SELECTED.minimumMultiLiteralInputLength()
        ? SELECTED
        : null;
  }

  static boolean multiLiteralProviderAvailable() {
    return SELECTED != null;
  }

  static VectorScanProvider providerForPairLength(int length) {
    return SELECTED != null && length >= SELECTED.minimumPairInputLength() ? SELECTED : null;
  }

  static VectorScanProvider providerForTripleLength(int length) {
    return SELECTED != null
            && length >= SELECTED.minimumTripleInputLength()
            && length <= SELECTED.maximumTripleInputLength()
        ? SELECTED
        : null;
  }

  private static VectorScanProvider loadSelected() {
    String requested = System.getProperty(PROVIDER_PROPERTY, "").trim();
    if (requested.isEmpty() || requested.equals("swar")) {
      return null;
    }
    if (!requested.equals("vector")) {
      throw new IllegalStateException(unknownProviderMessage(requested));
    }
    try {
      return VectorScanProviderFactory.create();
    } catch (RuntimeException | LinkageError e) {
      throw new IllegalStateException(
          "Could not enable the experimental Vector UTF-8 scanner; use JDK 21 or later and add "
              + "--add-modules=jdk.incubator.vector",
          e);
    }
  }

  static String unknownProviderMessage(String requested) {
    return "Unknown Vector scan provider " + requested;
  }
}
