// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.EnumSet;
import java.util.Set;

/** Bounded per-thread diagnostic buckets for exploratory fuzzing; never suppress comparisons. */
public final class FuzzDivergenceEvidence {
  private static final ThreadLocal<Set<IntentionalDivergence>> EVENTS =
      ThreadLocal.withInitial(() -> EnumSet.noneOf(IntentionalDivergence.class));
  private static final SafeReMatchDiagnostics LISTENER =
      new SafeReMatchDiagnostics() {
        @Override
        public void onIntentionalDivergence(IntentionalDivergence divergence) {
          EVENTS.get().add(divergence);
        }
      };

  private FuzzDivergenceEvidence() {}

  /** Starts an exploratory case, installing evidence collection when diagnostics are unclaimed. */
  public static void begin() {
    EVENTS.get().clear();
    if (!SafeReMatchDiagnostics.isEnabled(Pattern.diagnostics())) Pattern.setDiagnostics(LISTENER);
  }

  /** Returns execution evidence; an empty set means no instrumented signal was observed. */
  public static String observed() {
    return EVENTS.get().toString();
  }
}
