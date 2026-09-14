// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Compiles one pattern in a fresh JVM for the Unicode index initialization test. */
public final class UnicodeCaseIndexInitializationProbe {
  private UnicodeCaseIndexInitializationProbe() {}

  /** Compiles the pattern supplied as the first command-line argument. */
  public static void main(String[] args) {
    Pattern.compile(args[0]);
  }
}
