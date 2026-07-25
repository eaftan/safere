// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable summary of one normally completed public matching operation.
 *
 * <p>DFA search counts include every attempted search, including attempts that exceed the DFA state
 * budget. Replacement operations aggregate searches across all matches into their single event.
 */
public record OperationDiagnostics(
    PatternDescriptor pattern,
    MatchOperation operation,
    MatchOutcome outcome,
    MatchStrategy boundaryStrategy,
    MatchStrategy captureStrategy,
    List<StrategyParticipation> auxiliaryStrategies,
    Set<StrategyDecision> strategyDecisions,
    int forwardDfaSearchCount,
    int reverseDfaSearchCount,
    CaptureMode captureMode,
    int inputLength,
    int matchCount) {
  /** Creates an immutable operation summary. */
  public OperationDiagnostics {
    Objects.requireNonNull(pattern, "pattern");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(boundaryStrategy, "boundaryStrategy");
    Objects.requireNonNull(captureStrategy, "captureStrategy");
    auxiliaryStrategies = List.copyOf(auxiliaryStrategies);
    strategyDecisions = Set.copyOf(strategyDecisions);
    Objects.requireNonNull(captureMode, "captureMode");
    if (forwardDfaSearchCount < 0
        || reverseDfaSearchCount < 0
        || inputLength < 0
        || matchCount < 0) {
      throw new IllegalArgumentException("counts and lengths must be non-negative");
    }
  }
}
