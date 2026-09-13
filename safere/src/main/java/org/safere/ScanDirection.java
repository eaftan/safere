// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * The direction a dispatch site scanned in.
 *
 * <p>A {@link ScanKind} covers every direction a needle shape is scanned in, because those kernels
 * share a crossover threshold. Direction is therefore known only at the call site, and exists here
 * so that an audited event identifies the ladder it came from.
 *
 * @see ScanAudit
 */
enum ScanDirection {
  /** Increasing positions, as in {@code indexOf}. */
  FORWARD,
  /**
   * Not known. Carried by the automatic {@link ScanPath#CONSULTED} event, which is recorded inside
   * {@code providerFor} where only the kind and the window are in scope.
   */
  UNSPECIFIED,
}
