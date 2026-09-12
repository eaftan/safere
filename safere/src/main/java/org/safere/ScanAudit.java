// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayList;
import java.util.List;

/**
 * Package-private record of which scan kernel each dispatch site chose, for tests.
 *
 * <p>Every vector dispatch ladder sizes its crossover decision by the length of the region it is
 * about to scan. Passing the length of the whole input instead defeats the thresholds, because one
 * long input then selects a provider that serves every narrow probe issued against it. That defect
 * is invisible to result-based tests: the answer stays correct and only the cost changes. This
 * class makes the choice observable so a test can assert on it directly.
 *
 * <p>Sites record the tier that <em>ran</em>, not the provider's admission decision. {@link
 * VectorScanProviders#providerFor} returning {@code null} is often correct, and at least one past
 * defect was a caller mishandling a correct {@code null}, so an audit taken inside {@code
 * providerFor} could not have seen it.
 *
 * @see ScanEvent
 */
final class ScanAudit {
  /** System property that turns dispatch recording on. */
  static final String PROPERTY = "org.safere.testing.scanAudit";

  /**
   * Whether dispatch recording is on.
   *
   * <p>Read once in {@code <clinit>} and held in a {@code static final boolean}, so C2 folds it to
   * a constant after class initialization and every {@link #record} call collapses to nothing. This
   * deliberately does not follow {@code WorkCounterConfig.ENABLED}, which is generated at build
   * time behind a Maven profile: that machinery exists because a work counter fires once per byte
   * scanned, where even a folded branch in the interpreter matters. A dispatch event fires once per
   * scan call, three orders of magnitude less often.
   */
  static final boolean ENABLED = Boolean.getBoolean(PROPERTY);

  private static final ThreadLocal<List<ScanEvent>> LOG = new ThreadLocal<>();

  private ScanAudit() {}

  /**
   * Runs {@code task} and returns the dispatch events it recorded, in the order they occurred.
   *
   * @throws IllegalStateException if recording is off, or if work counting is on
   */
  static List<ScanEvent> captureForTesting(Runnable task) {
    if (!ENABLED) {
      throw new IllegalStateException("ScanAudit is disabled; run tests with -D" + PROPERTY);
    }
    if (WorkCounterConfig.ENABLED) {
      // The work-counter branches replace each kernel with a scalar counting loop and return
      // before reaching the dispatch ladder, so a capture taken here would faithfully report that
      // nothing but scalar code ran. Throwing beats handing back a plausible-looking log.
      throw new IllegalStateException(
          "ScanAudit cannot observe dispatch under -Pwork-counters, which bypasses every ladder");
    }
    List<ScanEvent> previous = LOG.get();
    List<ScanEvent> current = new ArrayList<>();
    LOG.set(current);
    try {
      task.run();
      return List.copyOf(current);
    } finally {
      if (previous == null) {
        LOG.remove();
      } else {
        LOG.set(previous);
      }
    }
  }

  /**
   * Records that a dispatch site ran {@code path} over a window of {@code windowLength} bytes.
   *
   * <p>The guard is inside the method rather than at each call site so that a ladder reads as one
   * statement per exit. Inlining folds the whole call away once {@link #ENABLED} is known false.
   */
  static void record(ScanKind kind, ScanDirection direction, int windowLength, ScanPath path) {
    if (!ENABLED) {
      return;
    }
    List<ScanEvent> log = LOG.get();
    if (log != null) {
      log.add(new ScanEvent(kind, direction, windowLength, path));
    }
  }

  /**
   * Records that {@link VectorScanProviders#providerFor} was asked about a window.
   *
   * <p>This is the one event no call site has to opt into, which is what makes the audit resistant
   * to rot: a dispatch ladder added later and never instrumented still shows up in a capture, as a
   * consultation with no path recorded against it.
   */
  static void recordConsultation(ScanKind kind, int windowLength) {
    record(kind, ScanDirection.UNSPECIFIED, windowLength, ScanPath.CONSULTED);
  }
}
