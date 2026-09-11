// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * A family of vector scan kernels.
 *
 * <p>Each kind carries its own crossover thresholds because the kernels differ in setup cost and in
 * how much work they retire per vector lane. A kind covers both scan directions: a forward and a
 * reverse kernel over the same needle shape share a threshold.
 *
 * @see VectorScanProviders#providerFor(ScanKind, int)
 */
enum ScanKind {
  /** Single-byte search. */
  BYTE,
  /** Two-byte alternation search, as used for an ASCII case-folded pair. */
  PAIR,
  /** Three-byte alternation search. */
  TRIPLE,
  /** ASCII character-class search over a sorted range list. */
  CLASS,
  /** ASCII case-insensitive literal search. */
  IGNORE_CASE,
  /** Teddy multi-literal prefilter. */
  TEDDY,
  /** Multi-literal alternation search. */
  MULTI_LITERAL,
}
