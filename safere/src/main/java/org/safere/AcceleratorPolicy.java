// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * Immutable tuning policy and diagnostic metadata for start-position and DFA accelerators.
 *
 * <p>Callers compare the work an accelerator saves with the cost of the path it replaces. DFA start
 * acceleration uses {@code minProfitableSkip} to credit only positions bypassed by each scan,
 * excluding positions already consumed by the DFA. Literal-search anchor verification instead uses
 * {@code minDensityStride} to compare candidate density with a direct substring search. Repeated
 * unprofitable calls consume the strike budget and trigger bounded quarantine windows.
 *
 * @param minProfitableSkip Minimum skip distance (in chars or bytes) required for this accelerator
 *     to be profitable over direct scalar DFA execution.
 * @param strikeBudget Unsuccessful-call allowance before declaring adaptive defeat. DFA callers
 *     scale this by {@code minProfitableSkip} and account for partial losses in input units.
 * @param minDensityStride Minimum candidate stride (in chars or bytes) for literal-search anchor
 *     verification to be profitable over direct substring search.
 * @param initialQuarantineWindow Span of input to skip acceleration over on the first defeat.
 * @param maxQuarantineWindow Ceiling on the quarantine window under exponential backoff.
 * @param isExactMatchCandidate Whether this accelerator identifies an exact candidate match start
 *     that can be validated directly.
 * @param strategy The diagnostic strategy associated with this accelerator, or {@code null} if
 *     none.
 */
record AcceleratorPolicy(
    int minProfitableSkip,
    int strikeBudget,
    int minDensityStride,
    int initialQuarantineWindow,
    int maxQuarantineWindow,
    boolean isExactMatchCandidate,
    MatchStrategy strategy) {

  // TODO: Conduct systematic empirical micro-benchmarking across diverse CPU architectures (x86
  // AVX-512/AVX2, ARM Neon) to precisely tune minimum profitable skip thresholds.

  /**
   * Candidate strikes tolerated before quarantining an accelerator.
   *
   * <p>Allow a short run of unprofitable calls before falling back to scalar DFA execution or
   * direct substring search. Profitable calls can repay strikes, so brief changes in input density
   * do not immediately disable an otherwise useful accelerator.
   */
  private static final int DEFAULT_STRIKE_BUDGET = 16;

  /**
   * Candidate stride below which a candidate counts as a strike.
   *
   * <p>Break-even for a literal scan: anchoring on a single character saves roughly 0.1 ns/byte
   * over {@link String#indexOf(String, int)} and spends one verification per anchor occurrence, so
   * it stops paying at roughly one candidate per 64-90 bytes.
   */
  private static final int DEFAULT_MIN_DENSITY_STRIDE = 64;

  /** Initial quarantine window (in bytes/chars) after repeated unprofitable calls. */
  private static final int DEFAULT_INITIAL_QUARANTINE_WINDOW = 2048;

  /** Maximum quarantine window (in bytes/chars) under exponential backoff. */
  private static final int DEFAULT_MAX_QUARANTINE_WINDOW = 65536;

  private static AcceleratorPolicy of(
      int minProfitableSkip, boolean isExactMatchCandidate, MatchStrategy strategy) {
    return new AcceleratorPolicy(
        minProfitableSkip,
        DEFAULT_STRIKE_BUDGET,
        DEFAULT_MIN_DENSITY_STRIDE,
        DEFAULT_INITIAL_QUARANTINE_WINDOW,
        DEFAULT_MAX_QUARANTINE_WINDOW,
        isExactMatchCandidate,
        strategy);
  }

  /** Policy for vectorized literal and fixed-offset substring searches (AVX2 / SWAR). */
  static final AcceleratorPolicy LITERAL = of(16, true, MatchStrategy.LITERAL);

  /** Policy for vectorized multi-literal and Teddy SIMD filters. */
  static final AcceleratorPolicy VECTOR_MULTI_LITERAL = of(64, true, MatchStrategy.LITERAL);

  /** Policy for character class bitmap and range table scanning. */
  static final AcceleratorPolicy CHAR_CLASS = of(24, false, MatchStrategy.CHARACTER_CLASS);

  /** Policy for line anchor ('^', '$') boundary acceleration. */
  static final AcceleratorPolicy LINE_ANCHOR = of(16, false, null);

  /**
   * Policy for leading-expansion wrappers, which delegate the actual scan to an inner accelerator
   * and carry that accelerator's diagnostic strategy.
   */
  static final AcceleratorPolicy LEADING_EXPANSION = of(16, false, null);

  /** Default fallback policy for generic or composite accelerators. */
  static final AcceleratorPolicy DEFAULT = of(32, false, null);

  /** Returns this policy carrying {@code strategy} for diagnostic attribution. */
  AcceleratorPolicy withStrategy(MatchStrategy strategy) {
    return new AcceleratorPolicy(
        minProfitableSkip,
        strikeBudget,
        minDensityStride,
        initialQuarantineWindow,
        maxQuarantineWindow,
        isExactMatchCandidate,
        strategy);
  }
}
