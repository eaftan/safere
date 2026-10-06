// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * Static helpers for the start-acceleration give-up rule, over state packed into one {@code long}.
 *
 * <p>The state records three values, all in input units (chars or bytes):
 *
 * <ul>
 *   <li>the <em>deficit</em> (bits 0-23): how much DFA work recent accelerator calls cost beyond
 *       what they saved;
 *   <li>the <em>backoff shift</em> (bits 24-31): how many consecutive defeats have doubled the
 *       quarantine window since the deficit was last repaid;
 *   <li>the <em>resume position</em> (bits 32-63): the position before which acceleration is
 *       quarantined.
 * </ul>
 *
 * <p>{@link #NEUTRAL} (zero) is the state of an accelerator with no history. Every state is safe to
 * pass to any search: the state only decides whether to call the accelerator, never what a search
 * matches. A {@link Matcher} carries the state across the searches of one sequence of {@code
 * find()} calls, reads it once per search into a local, and clears it when the input, region or
 * pattern changes. Tuning constants come from {@link AcceleratorPolicy}.
 *
 * <p>The state is a primitive rather than a holder object so that the DFA loop can keep it in a
 * local, and the helpers are small static methods so that the hot call sites stay small.
 */
final class AdaptiveBackoff {

  /** The state of an accelerator with no deficit, no backoff and no quarantine. */
  static final long NEUTRAL = 0L;

  /** Largest deficit the state can represent. */
  static final int MAX_DEFICIT = (1 << 24) - 1;

  private static final long DEFICIT_MASK = MAX_DEFICIT;
  private static final int SHIFT_OFFSET = 24;
  private static final int RESUME_OFFSET = 32;

  /** Caps the shift so that it always fits its eight bits, whatever the policy's windows are. */
  private static final int MAX_SHIFT = 30;

  private AdaptiveBackoff() {}

  static int deficit(long state) {
    return (int) (state & DEFICIT_MASK);
  }

  static int shift(long state) {
    return ((int) state) >>> SHIFT_OFFSET;
  }

  /** Returns the first position at which the accelerator may be called again. */
  static int resumePos(long state) {
    return (int) (state >>> RESUME_OFFSET);
  }

  static long pack(int deficit, int shift, int resumePos) {
    return ((long) resumePos << RESUME_OFFSET) | ((long) shift << SHIFT_OFFSET) | deficit;
  }

  /** Returns the quarantine window for the given backoff shift. */
  static int quarantineWindow(int shift, int initialWindow, int maxWindow) {
    return (int) Math.min((long) initialWindow << shift, maxWindow);
  }

  /**
   * Charges one accelerator call that moved the search from {@code pos} to {@code nextPos}.
   *
   * @see #recordSkip(long, int, int, int, int, int, int, int)
   */
  static long recordSkip(long state, int pos, int nextPos, int textLen, AcceleratorPolicy policy) {
    int minSkip = policy.minProfitableSkip();
    return recordSkip(
        state,
        nextPos - pos,
        nextPos,
        textLen,
        minSkip,
        policy.strikeBudget() * minSkip,
        policy.initialQuarantineWindow(),
        policy.maxQuarantineWindow());
  }

  /**
   * Charges one accelerator call that skipped {@code skipped} units and returned {@code nextPos}.
   *
   * <p>A skip shorter than {@code minSkip} adds the shortfall to the deficit. When the deficit
   * reaches {@code lossLimit}, acceleration is quarantined for the current window starting at
   * {@code nextPos}, the window doubles (up to {@code maxWindow}), and the deficit restarts at half
   * the limit. A longer skip repays the deficit by its excess over {@code minSkip}; repaying it in
   * full resets the window to {@code initialWindow}.
   */
  static long recordSkip(
      long state,
      int skipped,
      int nextPos,
      int textLen,
      int minSkip,
      int lossLimit,
      int initialWindow,
      int maxWindow) {
    int deficit = deficit(state);
    if (skipped < minSkip) {
      deficit += minSkip - skipped;
      if (deficit < lossLimit) {
        return (state & ~DEFICIT_MASK) | deficit;
      }
      return quarantine(state, nextPos, textLen, lossLimit, initialWindow, maxWindow);
    }
    if (deficit == 0) {
      return state;
    }
    deficit = Math.max(0, deficit + minSkip - skipped);
    // Callers only charge calls made at or after the resume position, so a repaid deficit leaves
    // nothing to remember.
    return deficit == 0 ? NEUTRAL : (state & ~DEFICIT_MASK) | deficit;
  }

  private static long quarantine(
      long state, int nextPos, int textLen, int lossLimit, int initialWindow, int maxWindow) {
    int shift = shift(state);
    int window = quarantineWindow(shift, initialWindow, maxWindow);
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.recordStartQuarantine(window);
    }
    int resumePos = nextPos + Math.min(window, textLen - nextPos);
    int nextShift = window < maxWindow && shift < MAX_SHIFT ? shift + 1 : shift;
    return pack(lossLimit >>> 1, nextShift, resumePos);
  }
}
