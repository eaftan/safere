// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Objects;
import java.util.Set;

/** Immutable static analysis of a compiled pattern. */
public record PatternAnalysis(
    Set<PatternFeature> features,
    Set<PatternCapability> capabilities,
    Set<PatternLimitation> limitations,
    int programSize,
    int captureCount) {
  /** Creates an immutable pattern analysis. */
  public PatternAnalysis {
    features = Set.copyOf(Objects.requireNonNull(features, "features"));
    capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
    limitations = Set.copyOf(Objects.requireNonNull(limitations, "limitations"));
    if (programSize < 0) {
      throw new IllegalArgumentException("programSize must be non-negative");
    }
    if (captureCount < 0) {
      throw new IllegalArgumentException("captureCount must be non-negative");
    }
  }
}
