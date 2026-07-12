// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Objects;

/** A strategy-specific explanation of routing or fallback. */
public record StrategyDecision(
    MatchStrategy strategy, StrategyDisposition disposition, StrategyReason reason) {
  /** Creates a strategy decision. */
  public StrategyDecision {
    Objects.requireNonNull(strategy, "strategy");
    Objects.requireNonNull(disposition, "disposition");
    Objects.requireNonNull(reason, "reason");
  }
}
