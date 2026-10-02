// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Complete, ordered literal alternatives for a bounded String search fast path. */
final class LiteralAlternation {
  static final long NO_MATCH = -1;
  static final long FALLBACK = -2;
  private static final int PROBE_LENGTH = 1_024;
  private static final int MAX_ALTERNATIVES = 8;
  private static final int WINDOW_LENGTH = 256;
  private static final int MAX_LITERAL_LENGTH = 12;

  private final String[] literals;
  private final boolean wholeInputRejection;

  private LiteralAlternation(String[] literals, boolean wholeInputRejection) {
    this.literals = literals;
    this.wholeInputRejection = wholeInputRejection;
  }

  /** Caller must have ruled out explicit captures. */
  static LiteralAlternation compile(Regexp ast, boolean wholeInputRejection) {
    while (ast.op == RegexpOp.NON_CAPTURE || (ast.op == RegexpOp.CAPTURE && ast.cap == 0)) {
      ast = ast.sub();
    }
    if (ast.op != RegexpOp.ALTERNATE || ast.nsub() < 2 || ast.nsub() > MAX_ALTERNATIVES) {
      return null;
    }
    String[] literals = new String[ast.nsub()];
    for (int i = 0; i < literals.length; i++) {
      literals[i] = MultiAnchorCompiler.extractExactAsciiLiteral(ast.subs.get(i));
      if (literals[i] == null) {
        return null;
      }
      // indexOf can repeatedly compare long near-matching prefixes. Even a whole-tail rejection
      // filter may stop after its first successful alternative, whereas exact bounds require us
      // to inspect later alternatives too. Keep the existing engine for longer needles.
      if (literals[i].length() > MAX_LITERAL_LENGTH) {
        return null;
      }
    }
    return new LiteralAlternation(literals, wholeInputRejection);
  }

  /**
   * Returns packed start/end bounds, NO_MATCH, or FALLBACK. Only the initial search uses this plan.
   * Later searches use the existing engine, avoiding probe overhead for dense matches.
   */
  long find(String text, int from) {
    if (from != 0) {
      return FALLBACK;
    }
    int length = text.length();
    if (!wholeInputRejection) {
      // Preserve locality for patterns that did not already scan the whole input for rejection.
      for (int start = 0; start < length; ) {
        int limit = start + Math.min(WINDOW_LENGTH, length - start);
        long match = search(text, start, limit);
        if (match >= 0) {
          return match;
        }
        start = limit;
      }
      return NO_MATCH;
    }
    if (length <= PROBE_LENGTH * 4) {
      return search(text, from, length);
    }
    long match = search(text, from, PROBE_LENGTH);
    return match >= 0 ? match : searchGrowingWindows(text, PROBE_LENGTH, PROBE_LENGTH * 2);
  }

  private long searchGrowingWindows(String text, int start, int windowLength) {
    int length = text.length();
    while (start < length) {
      int remaining = length - start;
      int width = Math.min(windowLength, remaining);
      // Combine a small final remainder with this window, avoiding another range search.
      int limit = remaining - width <= PROBE_LENGTH * 4 ? length : start + width;
      long match = search(text, start, limit);
      if (match >= 0) {
        return match;
      }
      start = limit;
      // Candidate-start ranges do not overlap. search() includes the literal-length overlap
      // needed for crossing matches; long arithmetic keeps growth safe at String's size limit.
      windowLength = (int) Math.min(2L * windowLength, length - start);
    }
    return NO_MATCH;
  }

  /** Search literal starts in [from, limit), including literals crossing the upper boundary. */
  private long search(String text, int from, int limit) {
    int bestStart = limit;
    int bestEnd = -1;
    int length = text.length();
    for (String literal : literals) {
      int end = bestStart + Math.min(literal.length() - 1, length - bestStart);
      int idx = end == length ? text.indexOf(literal, from) : text.indexOf(literal, from, end);
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record(idx >= 0 ? idx - from + literal.length() : end - from);
      }
      if (idx >= 0 && idx < bestStart) {
        bestStart = idx;
        bestEnd = idx + literal.length();
        // Alternatives are visited in priority order; no later alternative can improve this.
        if (idx == from) {
          break;
        }
      }
    }
    return bestEnd < 0 ? NO_MATCH : ((long) bestStart << 32) | bestEnd;
  }
}
