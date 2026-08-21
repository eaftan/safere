// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Metadata for small multi-literal alternation acceleration (2 &le; N &le; 4) using multi-vector
 * SIMD matching on distinct initial prefix characters.
 *
 * <p>All literals anchor on their first character (offset 0) to strictly preserve leftmost-first
 * matching semantics during left-to-right vector scanning.
 *
 * <p>The upper bound of 4 literals and requirement for distinct prefix characters is chosen based
 * on empirical vector performance and hardware constraints:
 *
 * <ul>
 *   <li><b>Register allocation &amp; unrolling:</b> AVX2 provides 16 YMM vector registers. 2–4
 *       literals require 2–4 broadcast registers plus input and comparison masks (6 registers
 *       total), leaving 10+ free YMM registers for HotSpot C2 to unroll the vector loop across
 *       64–128 byte strides without stack spilling.
 *   <li><b>Candidate false-positive density:</b> For 2–4 literals with distinct prefixes, the
 *       probability of false-positive candidate hits remains low (&lt; 5% in natural text),
 *       allowing the scanner to stay on the fast SIMD path &gt; 95% of the time. When literals
 *       share common prefixes or exceed 4 keywords, dedicated multi-pattern algorithms (such as
 *       Teddy with 4-bit nibble bucket hashing) are used instead.
 * </ul>
 */
@SuppressWarnings("ArrayRecordComponent")
record MultiLiteralInfo(String[] literals, char[] anchorChars, int[] anchorOffsets, int minLength)
    implements Serializable {

  static MultiLiteralInfo create(String[] literals) {
    if (literals == null || literals.length < 2 || literals.length > 4) {
      return null;
    }
    int minLen = Integer.MAX_VALUE;
    char[] anchorChars = new char[literals.length];
    int[] anchorOffsets = new int[literals.length];
    long seen0 = 0L;
    long seen1 = 0L;

    for (int i = 0; i < literals.length; i++) {
      String lit = literals[i];
      if (lit == null || lit.isEmpty()) {
        return null;
      }
      for (int j = 0; j < lit.length(); j++) {
        if (lit.charAt(j) > 127) {
          return null;
        }
      }
      char firstChar = lit.charAt(0);
      long bit = 1L << (firstChar & 63);
      if (firstChar < 64) {
        if ((seen0 & bit) != 0) {
          return null; // Duplicate anchor character; defer to Teddy
        }
        seen0 |= bit;
      } else {
        if ((seen1 & bit) != 0) {
          return null; // Duplicate anchor character; defer to Teddy
        }
        seen1 |= bit;
      }
      anchorOffsets[i] = 0;
      anchorChars[i] = firstChar;
      minLen = Math.min(minLen, lit.length());
    }

    return new MultiLiteralInfo(
        Arrays.copyOf(literals, literals.length), anchorChars, anchorOffsets, minLen);
  }
}
