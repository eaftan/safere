// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import org.safere.spi.Utf8ScanProvider;

/**
 * Loads the optional UTF-8 scan provider once, without linking core SafeRE to a JDK-specific API.
 */
final class Utf8ScanProviders {
  static final String PROVIDER_PROPERTY = "org.safere.utf8ScanProvider";
  private static final Utf8ScanProvider SELECTED = loadSelected();

  private Utf8ScanProviders() {}

  static Utf8ScanProvider providerForLength(int length) {
    return SELECTED != null && length >= SELECTED.minimumInputLength() ? SELECTED : null;
  }

  private static Utf8ScanProvider loadSelected() {
    String requested = System.getProperty(PROVIDER_PROPERTY, "").trim();
    if (requested.isEmpty() || requested.equals("swar")) {
      return null;
    }
    try {
      for (Utf8ScanProvider provider : ServiceLoader.load(Utf8ScanProvider.class)) {
        if (provider.name().equals(requested)) {
          if (provider.minimumInputLength() < 0) {
            throw new IllegalStateException(
                "UTF-8 scan provider '" + requested + "' returned a negative minimum length");
          }
          return provider;
        }
      }
    } catch (RuntimeException | LinkageError | ServiceConfigurationError e) {
      throw new IllegalStateException(
          "Could not load UTF-8 scan provider '"
              + requested
              + "'; check its JDK version and required JVM flags",
          e);
    }
    throw new IllegalStateException(
        "UTF-8 scan provider '"
            + requested
            + "' was requested but no matching provider is available");
  }
}
