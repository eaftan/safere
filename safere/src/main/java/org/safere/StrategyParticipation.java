// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Objects;

/** A strategy performing an auxiliary role during an operation. */
public record StrategyParticipation(MatchStrategy strategy, StrategyRole role) {
  /** Creates a participation pair. */
  public StrategyParticipation {
    Objects.requireNonNull(strategy, "strategy");
    Objects.requireNonNull(role, "role");
  }
}
