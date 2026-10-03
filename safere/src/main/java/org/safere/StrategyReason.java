// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Stable reason for a strategy decision. */
public enum StrategyReason {
  INPUT_TOO_SMALL,
  INPUT_TOO_LARGE,
  CAPTURES_REQUIRED,
  AUTHORITATIVE_BOUNDS_REQUIRED,
  EXACT_NULLABLE_LOOP_SEMANTICS_REQUIRED,
  /**
   * The lazy DFA exhausted its state cache, so the operation fell back to a slower engine. This
   * happens when the cache fills and the DFA declines to clear it, or when it clears the cache but
   * the fresh cache still cannot hold the states the search needs. The DFA clears and rebuilds a
   * full cache while doing so is productive; it declines only when recent cache generations covered
   * too little input per cached state.
   */
  DFA_BUDGET_EXCEEDED,
  WORK_BUDGET_EXCEEDED,
  OPTIMIZED_PATH_DISABLED,
  /** A speculative BitState search needed to search beyond its fixed window. */
  SPECULATIVE_WINDOW_EXCEEDED,
  /** A speculative BitState search was skipped after consecutive unsuccessful window attempts. */
  SPECULATIVE_BACKOFF
}
