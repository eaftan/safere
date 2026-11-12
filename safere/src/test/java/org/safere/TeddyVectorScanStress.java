// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Standalone C2 stress driver; see the Vector compiler stress protocol in docs/TESTING.md. */
public final class TeddyVectorScanStress {
  private TeddyVectorScanStress() {}

  /** Warms the three-byte model before introducing models with disabled vector stages. */
  public static void main(String[] args) {
    TeddyModel[] models = {
      TeddyModel.compile(new String[] {"apple", "banana", "cherry"}, 64),
      TeddyModel.compile(new String[] {"ab", "cherry", "abc"}, 64),
      TeddyModel.compile(new String[] {"abc", "cherry", "ab"}, 64),
      TeddyModel.compile(new String[] {"abc", "bcd", "cde"}, 64)
    };
    byte[] data = new byte[4096];
    Arrays.fill(data, (byte) 'x');
    byte[] prefix = "a b c ".repeat(32).getBytes(StandardCharsets.UTF_8);
    System.arraycopy(prefix, 0, data, 0, prefix.length);
    byte[] suffix = "abcde cherry".getBytes(StandardCharsets.UTF_8);
    System.arraycopy(suffix, 0, data, data.length - suffix.length, suffix.length);
    long sum = 0;
    for (int phase = 0; phase < 4; phase++) {
      System.out.println("phase=" + phase);
      for (int i = 0; i < 3000; i++) {
        TeddyModel model = models[phase == 0 ? 0 : i % models.length];
        int found = TeddyVectorScan.indexOfTeddyUtf8(data, 0, data.length, model, i % 128);
        int expected = data.length - (model == models[0] ? 6 : 12);
        if (found != expected) {
          throw new AssertionError("expected=" + expected + " actual=" + found);
        }
        sum += found;
      }
    }
    System.out.println("sum=" + sum);
  }
}
