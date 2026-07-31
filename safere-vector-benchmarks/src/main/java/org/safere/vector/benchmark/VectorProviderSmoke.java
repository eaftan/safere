// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.vector.benchmark;

import java.util.Arrays;
import org.safere.Pattern;
import org.safere.Utf8Input;

/** Deterministic runtime smoke check for packaged UTF-8 scan-provider selection. */
public final class VectorProviderSmoke {
  private VectorProviderSmoke() {}

  /** Checks absent and late matches on an input large enough to invoke the selected provider. */
  public static void main(String[] args) {
    byte[] bytes = new byte[2048];
    Arrays.fill(bytes, (byte) 'b');
    Pattern pattern = Pattern.compile("x+");
    if (pattern.find(Utf8Input.trusted(bytes))) {
      throw new AssertionError("Unexpected match in absent input");
    }
    bytes[bytes.length - 1] = 'x';
    if (!pattern.find(Utf8Input.trusted(bytes))) {
      throw new AssertionError("Missing late match");
    }
  }
}
