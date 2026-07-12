// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * Receives synchronous, low-cardinality diagnostics about pattern compilation and matching.
 *
 * <p>Callbacks run on the compiling or matching thread. Implementations are responsible for their
 * own thread safety, aggregation, storage, and cardinality policy. Callback exceptions propagate to
 * the caller; operation callbacks run only after matcher state has been finalized.
 *
 * <p>When {@link #NONE} is installed, matching allocates no diagnostics objects. Enabled listeners
 * receive one bounded event per supported public operation, irrespective of internal engine passes
 * or replacement match count.
 */
public class SafeReMatchDiagnostics {
  /** Listener used when match diagnostics are disabled. */
  public static final SafeReMatchDiagnostics NONE = new SafeReMatchDiagnostics(false);

  private final boolean enabled;

  /** Creates an enabled diagnostics listener. */
  protected SafeReMatchDiagnostics() {
    this(true);
  }

  private SafeReMatchDiagnostics(boolean enabled) {
    this.enabled = enabled;
  }

  /** Returns whether the given listener is enabled. */
  static boolean isEnabled(SafeReMatchDiagnostics listener) {
    return listener.enabled;
  }

  /**
   * Called after a pattern is compiled while this listener is installed.
   *
   * @param event immutable pattern compilation event
   */
  public void onPatternCompiled(PatternCompiledEvent event) {}

  /**
   * Called after a supported public operation completes normally.
   *
   * @param event immutable operation summary
   */
  public void onOperationCompleted(OperationDiagnostics event) {}
}
