// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * One scan dispatch, as observed by {@link ScanAudit}.
 *
 * @param kind the kernel family the site dispatched on
 * @param direction the direction the site scanned in
 * @param windowLength the length of the region the site was about to scan, which is the value it
 *     passed to {@link VectorScanProviders#providerFor}. Sizing this by the whole input rather than
 *     by the search window is the defect class this audit exists to catch; see Invariant 4.41.
 * @param path the implementation tier that actually ran
 */
record ScanEvent(ScanKind kind, ScanDirection direction, int windowLength, ScanPath path) {}
