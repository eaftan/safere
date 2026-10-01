// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.Arrays;
import org.safere.Pattern;
import org.safere.Utf8Input;

/** Checks that the requested UTF-8 scan provider can run from the benchmark artifact. */
public final class BenchmarkProviderCheck {
  private BenchmarkProviderCheck() {}

  /** Exercises a long absent scan before measurement to fail clearly on provider loading errors. */
  public static void main(String[] args) {
    String provider = System.getProperty("org.safere.experimental.vectorScanProvider", "default");
    if (provider.equals("vector")
        && ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty()) {
      throw new IllegalStateException(
          "Could not enable the experimental Vector UTF-8 scanner; add "
              + "--add-modules=jdk.incubator.vector");
    }
    byte[] bytes = new byte[2048];
    Arrays.fill(bytes, (byte) 'b');
    Pattern pattern = Pattern.compile("(?:xyz|uvw)[0-9]+");
    if (pattern.find(Utf8Input.trusted(bytes))) {
      throw new AssertionError("Unexpected match during provider startup check");
    }
    byte[] suffix = {'x', 'y', 'z', '7'};
    System.arraycopy(suffix, 0, bytes, bytes.length - suffix.length, suffix.length);
    if (!pattern.find(Utf8Input.trusted(bytes))) {
      throw new AssertionError("Missing late match during provider startup check");
    }
    System.out.println("UTF-8 scan provider startup check passed: " + provider);
  }
}
