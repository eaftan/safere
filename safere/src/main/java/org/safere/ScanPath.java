// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * Which implementation tier a dispatch site actually ran.
 *
 * <p>This is the observed outcome, not the admission decision. {@link
 * VectorScanProviders#providerFor} returning {@code null} is frequently the correct answer, so a
 * record of what the provider was willing to do says nothing about whether the caller then did the
 * right thing; only the tier that ran does.
 *
 * @see ScanAudit
 */
enum ScanPath {
  /**
   * The site routed to the Vector API tier. Some kernels answer from a short scalar prologue before
   * the first vector load; that is still this tier, because the routing decision is what is under
   * audit.
   */
  VECTOR,
  /** The site routed to a SWAR kernel. */
  SWAR,
  /** The site routed to a scalar loop, because no wide kernel applied to this window. */
  SCALAR,
  /**
   * No kernel ran. The site consulted a provider, declined to accelerate, and returned control to
   * its caller without examining the input.
   */
  DECLINED,
  /**
   * Not a path at all: {@link VectorScanProviders#providerFor} was asked about a window. Recorded
   * automatically, so that a dispatch site which never records a path of its own shows up in a
   * capture as an unpaired consultation rather than going silently unaudited.
   */
  CONSULTED,
}
