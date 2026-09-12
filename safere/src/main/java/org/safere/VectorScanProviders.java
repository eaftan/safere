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

  /**
   * Returns the installed Vector provider when its crossover thresholds admit a {@code kind} scan
   * over a window of {@code windowLength} bytes, and {@code null} otherwise.
   *
   * <p>{@code windowLength} must be the length of the region the caller is about to scan, not the
   * length of the whole input. The thresholds describe the work of a single kernel invocation, so
   * sizing them by the input defeats them: one long input selects a provider that then serves every
   * scan against it, including the narrow probes a candidate verification loop issues thousands of
   * times, each paying vector setup for a window far below break-even.
   */
  static VectorScanProvider providerFor(ScanKind kind, int windowLength) {
    ScanAudit.recordConsultation(kind, windowLength);
    VectorScanProvider selected = SELECTED;
    if (selected == null) {
      return null;
    }
    return windowLength >= selected.minimumWindowLength(kind)
            && windowLength <= selected.maximumWindowLength(kind)
        ? selected
        : null;
  }

  /**
   * Returns whether a Vector provider is installed at all, ignoring crossover thresholds. Use this
   * only for compile-time decisions, where no search window exists yet.
   */
  static boolean vectorProviderAvailable() {
    return SELECTED != null;
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
