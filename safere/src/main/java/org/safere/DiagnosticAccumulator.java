// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Enabled-only, bounded primitive diagnostics state for one public operation. */
final class DiagnosticAccumulator {
  private static final MatchStrategy[] STRATEGIES = {
    MatchStrategy.NONE,
    MatchStrategy.LITERAL,
    MatchStrategy.CHARACTER_CLASS,
    MatchStrategy.KEYWORD,
    MatchStrategy.ONE_PASS,
    MatchStrategy.DFA,
    MatchStrategy.BIT_STATE,
    MatchStrategy.NFA
  };
  private static final StrategyRole[] ROLES = {
    StrategyRole.START_ACCELERATION,
    StrategyRole.REJECT_PREFILTER,
    StrategyRole.CANDIDATE_VERIFICATION
  };
  private static final StrategyDisposition[] DISPOSITIONS = {
    StrategyDisposition.INAPPLICABLE, StrategyDisposition.BYPASSED, StrategyDisposition.FALLBACK
  };
  private static final StrategyReason[] REASONS = {
    StrategyReason.INPUT_TOO_SMALL,
    StrategyReason.INPUT_TOO_LARGE,
    StrategyReason.CAPTURES_REQUIRED,
    StrategyReason.AUTHORITATIVE_BOUNDS_REQUIRED,
    StrategyReason.EXACT_NULLABLE_LOOP_SEMANTICS_REQUIRED,
    StrategyReason.DFA_BUDGET_EXCEEDED,
    StrategyReason.WORK_BUDGET_EXCEEDED,
    StrategyReason.OPTIMIZED_PATH_DISABLED
  };
  private static final int STRATEGY_COUNT = STRATEGIES.length;
  private static final int ROLE_COUNT = ROLES.length;
  private static final int DISPOSITION_COUNT = DISPOSITIONS.length;
  private static final int REASON_COUNT = REASONS.length;
  private static final int PARTICIPATION_COUNT = STRATEGY_COUNT * ROLE_COUNT;
  private static final int DECISION_COUNT = STRATEGY_COUNT * DISPOSITION_COUNT * REASON_COUNT;

  private MatchStrategy boundaryStrategy = MatchStrategy.NONE;
  private MatchStrategy captureStrategy = MatchStrategy.NONE;
  private final int[] orderedParticipation = new int[PARTICIPATION_COUNT];
  private int participationSize;
  private final long[] seenParticipation = new long[wordCount(PARTICIPATION_COUNT)];
  private final long[] decisions = new long[wordCount(DECISION_COUNT)];
  private int matchCount;

  private static int wordCount(int bits) {
    return (bits + Long.SIZE - 1) / Long.SIZE;
  }

  void boundary(MatchStrategy strategy) {
    if (boundaryStrategy == MatchStrategy.NONE) {
      boundaryStrategy = strategy;
    }
  }

  void replaceBoundary(MatchStrategy strategy) {
    boundaryStrategy = strategy;
  }

  void capture(MatchStrategy strategy) {
    if (captureStrategy == MatchStrategy.NONE) {
      captureStrategy = strategy;
    }
  }

  void participate(MatchStrategy strategy, StrategyRole role) {
    int token = strategyIndex(strategy) * ROLE_COUNT + roleIndex(role);
    int word = token / Long.SIZE;
    long bit = 1L << (token % Long.SIZE);
    if ((seenParticipation[word] & bit) != 0) {
      return;
    }
    seenParticipation[word] |= bit;
    orderedParticipation[participationSize++] = token;
  }

  void decision(MatchStrategy strategy, StrategyDisposition disposition, StrategyReason reason) {
    int token =
        (strategyIndex(strategy) * DISPOSITION_COUNT + dispositionIndex(disposition)) * REASON_COUNT
            + reasonIndex(reason);
    decisions[token / Long.SIZE] |= 1L << (token % Long.SIZE);
  }

  void incrementMatchCount() {
    matchCount++;
  }

  void matchCount(int count) {
    matchCount = count;
  }

  int matchCount() {
    return matchCount;
  }

  boolean captured() {
    return captureStrategy != MatchStrategy.NONE;
  }

  OperationDiagnostics toEvent(
      PatternDescriptor pattern,
      MatchOperation operation,
      MatchOutcome outcome,
      CaptureMode captureMode,
      int inputLength) {
    List<StrategyParticipation> participation = new ArrayList<>(participationSize);
    for (int i = 0; i < participationSize; i++) {
      int token = orderedParticipation[i];
      participation.add(
          new StrategyParticipation(STRATEGIES[token / ROLE_COUNT], ROLES[token % ROLE_COUNT]));
    }
    Set<StrategyDecision> decisionSet = new LinkedHashSet<>();
    for (int token = 0; token < DECISION_COUNT; token++) {
      if ((decisions[token / Long.SIZE] & (1L << (token % Long.SIZE))) == 0) {
        continue;
      }
      int strategyOrdinal = token / (DISPOSITION_COUNT * REASON_COUNT);
      int remainder = token % (DISPOSITION_COUNT * REASON_COUNT);
      decisionSet.add(
          new StrategyDecision(
              STRATEGIES[strategyOrdinal],
              DISPOSITIONS[remainder / REASON_COUNT],
              REASONS[remainder % REASON_COUNT]));
    }
    return new OperationDiagnostics(
        pattern,
        operation,
        outcome,
        boundaryStrategy,
        captureStrategy,
        participation,
        decisionSet,
        captureMode,
        inputLength,
        matchCount);
  }

  private static int strategyIndex(MatchStrategy strategy) {
    return switch (strategy) {
      case NONE -> 0;
      case LITERAL -> 1;
      case CHARACTER_CLASS -> 2;
      case KEYWORD -> 3;
      case ONE_PASS -> 4;
      case DFA -> 5;
      case BIT_STATE -> 6;
      case NFA -> 7;
    };
  }

  private static int roleIndex(StrategyRole role) {
    return switch (role) {
      case START_ACCELERATION -> 0;
      case REJECT_PREFILTER -> 1;
      case CANDIDATE_VERIFICATION -> 2;
    };
  }

  private static int dispositionIndex(StrategyDisposition disposition) {
    return switch (disposition) {
      case INAPPLICABLE -> 0;
      case BYPASSED -> 1;
      case FALLBACK -> 2;
    };
  }

  private static int reasonIndex(StrategyReason reason) {
    return switch (reason) {
      case INPUT_TOO_SMALL -> 0;
      case INPUT_TOO_LARGE -> 1;
      case CAPTURES_REQUIRED -> 2;
      case AUTHORITATIVE_BOUNDS_REQUIRED -> 3;
      case EXACT_NULLABLE_LOOP_SEMANTICS_REQUIRED -> 4;
      case DFA_BUDGET_EXCEEDED -> 5;
      case WORK_BUDGET_EXCEEDED -> 6;
      case OPTIMIZED_PATH_DISABLED -> 7;
    };
  }
}
