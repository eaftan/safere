// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * The direction a dispatch site scanned in.
 *
 * <p>A {@link ScanKind} deliberately covers both directions, because a forward and a reverse kernel
 * over the same needle shape share a crossover threshold. Direction is therefore known only at the
 * call site, and exists here so that an audited event identifies the ladder it came from.
 *
 * @see ScanAudit
 */
enum ScanDirection {
  /** Increasing positions, as in {@code indexOf}. */
  FORWARD,
  /** Decreasing positions, as in {@code lastIndexOf}. */
  REVERSE,
  /**
   * Not known. Carried by the automatic {@link ScanPath#CONSULTED} event, which is recorded inside
   * {@code providerFor} where only the kind and the window are in scope.
   */
  UNSPECIFIED,
}
