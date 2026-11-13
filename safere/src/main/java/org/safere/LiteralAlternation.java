// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.util.Arrays;

/** Complete, ordered literal alternatives for a bounded String and UTF-8 search fast path. */
final class LiteralAlternation {
  static final long NO_MATCH = -1;
  static final long FALLBACK = -2;
  private static final int PROBE_LENGTH = 1_024;
  private static final int MAX_ALTERNATIVES = 8;
  private static final int WINDOW_LENGTH = 256;
  private static final int MAX_LITERAL_LENGTH = 12;
  // Searches verify first-byte candidates one at a time until they see this many within
  // DENSE_CANDIDATE_SPAN bytes, then switch to one bounded scan per literal.
  private static final int DENSE_CANDIDATES = 8;
  private static final int DENSE_CANDIDATE_SPAN = 256;

  private final String[] literals;
  private final boolean wholeInputRejection;
  private final char[] firstChars;
  private final int firstLow;
  private final int firstHigh;
  // The literals are ASCII, so their UTF-8 encodings are their chars as bytes.
  private final byte[][] utf8Literals;
  private final int[][] utf8Failures;
  // Reuse the ordinary first-byte scan when present. Otherwise default-provider searches use a
  // compact enclosing range or bounded per-literal scans; vector searches retain their own plan.
  private final Utf8StartAccelerator.CharClass utf8FirstByteScan;

  private LiteralAlternation(
      String[] literals,
      boolean wholeInputRejection,
      Utf8StartAccelerator.CharClass utf8FirstByteScan) {
    this.literals = literals;
    this.wholeInputRejection = wholeInputRejection;
    char[] initials = new char[literals.length];
    int count = 0;
    int low = 127;
    int high = 0;
    for (String literal : literals) {
      char first = literal.charAt(0);
      boolean seen = false;
      for (int i = 0; i < count; i++) {
        seen |= initials[i] == first;
      }
      if (!seen) {
        initials[count++] = first;
      }
      low = Math.min(low, first);
      high = Math.max(high, first);
    }
    this.firstChars = Arrays.copyOf(initials, count);
    this.firstLow = low;
    this.firstHigh = high;
    this.utf8Literals = new byte[literals.length][];
    this.utf8Failures = new int[literals.length][];
    for (int i = 0; i < literals.length; i++) {
      utf8Literals[i] = literals[i].getBytes(US_ASCII);
      utf8Failures[i] = Pattern.literalFailure(utf8Literals[i]);
    }
    this.utf8FirstByteScan = utf8FirstByteScan;
  }

  /**
   * Caller must have ruled out explicit captures. {@code utf8FirstByteScan} is the UTF-8 start
   * accelerator when it scans for the literals' first bytes, or null. Vector multi-literal plans
   * retain their existing search; the default provider can select the ordered literal search even
   * when there are too many first bytes for ordinary start acceleration.
   */
  static LiteralAlternation compile(
      Regexp ast, boolean wholeInputRejection, Utf8StartAccelerator.CharClass utf8FirstByteScan) {
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
      // to inspect later alternatives too. MAX_LITERAL_LENGTH * MAX_ALTERNATIVES bounds the
      // comparison work per input position, keeping indexOf's O(n * m) worst case linear in n.
      // WorkCounter charges distance, not character comparisons, so it relies on these caps.
      // Keep the existing engine for longer needles.
      if (literals[i].length() > MAX_LITERAL_LENGTH) {
        return null;
      }
    }
    return new LiteralAlternation(literals, wholeInputRejection, utf8FirstByteScan);
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
    if (firstChars.length > 3) {
      // Keep early hits on the existing short-window path. Beyond it, character searches use
      // the JVM's single-character intrinsics instead of one substring scan per alternative.
      int limit = Math.min(WINDOW_LENGTH, length);
      long match = search(text, 0, limit);
      return match >= 0 || limit == length ? match : searchCandidates(text, limit);
    }
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

  /** Candidate starts advance monotonically; only a bounded prefix is retried on dense input. */
  private long searchCandidates(String text, int start) {
    int spanStart = start;
    int candidates = 0;
    for (int position = start; ; ) {
      int candidate = StringInputScanner.indexOfSmallSet(text, firstChars, position);
      if (candidate < 0) {
        return NO_MATCH;
      }
      for (String literal : literals) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(literal.length());
        }
        if (text.startsWith(literal, candidate)) {
          return ((long) candidate << 32) | (candidate + literal.length());
        }
      }
      position = candidate + 1;
      if (++candidates == DENSE_CANDIDATES) {
        if (candidate - spanStart < DENSE_CANDIDATE_SPAN) {
          return searchGrowingWindows(text, position, PROBE_LENGTH);
        }
        candidates = 0;
        spanStart = candidate;
      }
    }
  }

  /** Searches an opaque String region without inspecting text beyond its bounds. */
  long find(StringRegionInputScanner text, int from) {
    if (from != 0) {
      return FALLBACK;
    }
    int length = text.length();
    for (int start = 0; start < length; ) {
      int limit = start + Math.min(WINDOW_LENGTH, length - start);
      int bestStart = limit;
      int bestEnd = -1;
      for (String literal : literals) {
        int end = bestStart + Math.min(literal.length() - 1, length - bestStart);
        int candidate = text.indexOf(literal, start, end);
        if (candidate >= 0 && candidate < bestStart) {
          bestStart = candidate;
          bestEnd = candidate + literal.length();
          if (candidate == start) {
            break;
          }
        }
      }
      if (bestEnd >= 0) {
        return ((long) bestStart << 32) | bestEnd;
      }
      start = limit;
    }
    return NO_MATCH;
  }

  /**
   * UTF-8 counterpart to {@link #find(String, int)}, returning byte offsets. ASCII literals only
   * match ASCII bytes, so a match never splits a code point, even in malformed input.
   *
   * <p>Every match starts with one of the literals' first bytes. Reuse the fallback's first-byte
   * scan when available; otherwise use a compact enclosing range after a substantial initial gap.
   * Dense early candidates retain the DFA. Check literals in priority order at every candidate.
   * When the first bytes are common, that stops at almost every word, so after {@link
   * #DENSE_CANDIDATES} candidates within {@link #DENSE_CANDIDATE_SPAN} bytes it switches to the
   * String path's strategy of one bounded scan per literal. Those scans start with {@link
   * #PROBE_LENGTH}-byte windows, long enough for the word-at-a-time literal filter.
   */
  long find(Utf8InputScanner text, int from) {
    if (from != 0) {
      return FALLBACK;
    }
    if (utf8FirstByteScan == null) {
      return VectorScanProviders.vectorProviderAvailable() ? FALLBACK : findSparseInitials(text);
    }
    byte[] bytes = text.bytes();
    int offset = text.offset();
    int length = text.length();
    int spanStart = 0;
    int candidates = 0;
    for (int position = 0; ; position++) {
      int candidate = utf8FirstByteScan.findCandidate(text, position);
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record((candidate < 0 ? length : candidate + 1) - position);
      }
      if (candidate < 0) {
        return NO_MATCH;
      }
      long match = matchAt(bytes, offset, length, candidate);
      if (match >= 0) {
        return match;
      }
      position = candidate;
      if (++candidates == DENSE_CANDIDATES) {
        if (position - spanStart < DENSE_CANDIDATE_SPAN) {
          return searchFrom(text, position + 1);
        }
        candidates = 0;
        spanStart = position;
      }
    }
  }

  /**
   * Uses a conservative first-byte range only after a substantial initial gap. Dense early
   * candidates retain the DFA, which can recognize nearby matches without per-literal probes. A
   * fallback repeats at most WINDOW_LENGTH input bytes; otherwise all candidate starts advance.
   */
  private long findSparseInitials(Utf8InputScanner text) {
    if (text.length() < WINDOW_LENGTH || firstHigh - firstLow > 31) {
      return FALLBACK;
    }
    byte[] bytes = text.bytes();
    int offset = text.offset();
    int length = text.length();
    // Membership at byte zero already proves an early candidate. Avoid launching the word scan
    // merely to discover that the dense-input fallback applies.
    int first = bytes[offset] & 0xFF;
    if (first >= firstLow && first <= firstHigh) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      return FALLBACK;
    }
    int candidate = ByteSwarScan.indexOfByteRange(bytes, offset, length, firstLow, firstHigh, 0);
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.record(candidate < 0 ? length : candidate + 1);
    }
    if (candidate < 0) {
      return NO_MATCH;
    }
    if (candidate < WINDOW_LENGTH) {
      return FALLBACK;
    }
    int spanStart = candidate;
    int candidates = 0;
    while (true) {
      long match = matchAt(bytes, offset, length, candidate);
      if (match >= 0) {
        return match;
      }
      int position = candidate + 1;
      if (++candidates == DENSE_CANDIDATES) {
        if (candidate - spanStart < DENSE_CANDIDATE_SPAN) {
          return searchFrom(text, position);
        }
        candidates = 0;
        spanStart = candidate;
      }
      candidate =
          ByteSwarScan.indexOfByteRange(bytes, offset, length, firstLow, firstHigh, position);
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record((candidate < 0 ? length : candidate + 1) - position);
      }
      if (candidate < 0) {
        return NO_MATCH;
      }
    }
  }

  /** Returns the bounds of the highest-priority literal starting at {@code position}, or -1. */
  private long matchAt(byte[] bytes, int offset, int length, int position) {
    for (byte[] literal : utf8Literals) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record(literal.length);
      }
      if (literal.length <= length - position
          && ByteSwarScan.matchesAt(bytes, offset, literal, position)) {
        return ((long) position << 32) | (position + literal.length);
      }
    }
    return NO_MATCH;
  }

  /** Searches [start, length), where no literal starts before start. */
  private long searchFrom(Utf8InputScanner text, int start) {
    int length = text.length();
    if (length - start <= PROBE_LENGTH * 4) {
      return search(text, start, length);
    }
    long match = search(text, start, start + PROBE_LENGTH);
    return match >= 0 ? match : searchGrowingWindows(text, start + PROBE_LENGTH, PROBE_LENGTH * 2);
  }

  private long searchGrowingWindows(Utf8InputScanner text, int start, int windowLength) {
    int length = text.length();
    while (start < length) {
      int remaining = length - start;
      int width = Math.min(windowLength, remaining);
      int limit = remaining - width <= PROBE_LENGTH * 4 ? length : start + width;
      long match = search(text, start, limit);
      if (match >= 0) {
        return match;
      }
      start = limit;
      windowLength = (int) Math.min(2L * windowLength, length - start);
    }
    return NO_MATCH;
  }

  /** Search literal starts in [from, limit), including literals crossing the upper boundary. */
  private long search(Utf8InputScanner text, int from, int limit) {
    int bestStart = limit;
    int bestEnd = -1;
    for (int i = 0; i < utf8Literals.length; i++) {
      byte[] literal = utf8Literals[i];
      // indexOfWithin charges WorkCounter for the bytes it scans.
      int idx = text.indexOfWithin(literal, utf8Failures[i], from, bestStart - 1);
      if (idx >= 0) {
        bestStart = idx;
        bestEnd = idx + literal.length;
        // Alternatives are visited in priority order; no later alternative can improve this.
        if (idx == from) {
          break;
        }
      }
    }
    return bestEnd < 0 ? NO_MATCH : ((long) bestStart << 32) | bestEnd;
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
