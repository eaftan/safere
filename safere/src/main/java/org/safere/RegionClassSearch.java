// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.IdentityHashMap;

/** Scalar-class existence checks for opaque grapheme searches, in original-input coordinates. */
final class RegionClassSearch {
  // At most one entry per required class in the compiled pattern. Region changes retain entries;
  // input and pattern changes discard this object. Ordinary String scanners carry no memo state.
  private IdentityHashMap<RejectPrefilter.CharClass, Scan> scans;

  private static final class Scan {
    int start;
    int stop;
    boolean hit;
  }

  boolean contains(RejectPrefilter.CharClass cc, InputScanner input, int start, int end) {
    if (start >= end) {
      return false;
    }
    long first = input.decodeForward(start, end);
    if (contains(cc, InputScanner.codePoint(first))) {
      return true;
    }
    // Existence does not require the leftmost occurrence. Probe the terminal code point before
    // scanning: an exposed high surrogate is a witness even when an early NFA match uses a low
    // half of a pair. This avoids delaying that first result with a scan of the entire region.
    long last = input.decodeBackward(end, start);
    if (contains(cc, InputScanner.codePoint(last))) {
      return true;
    }
    int from = InputScanner.position(first);
    int limit = InputScanner.position(last);
    if (from >= limit) {
      return false;
    }
    if (scans == null) {
      scans = new IdentityHashMap<>();
    }
    Scan scan = scans.get(cc);
    // The probed edge code points are excluded from the memo. Its interior scalar boundaries
    // remain valid when a later region exposes a different surrogate half at either edge.
    // A scan records an absent interval [start, stop), optionally followed by a hit at stop.
    int scanStart = from;
    if (scan != null && from >= scan.start && from <= scan.stop) {
      if (limit <= scan.stop) {
        return false;
      }
      if (scan.hit) {
        return true;
      }
      scanStart = scan.start;
      from = scan.stop;
    }
    if (scan == null) {
      scan = new Scan();
      scans.put(cc, scan);
    }
    scan.start = scanStart;
    scan.stop = limit;
    scan.hit = false;
    for (int pos = from; pos < limit; ) {
      long decoded = input.decodeForward(pos, limit);
      if (contains(cc, InputScanner.codePoint(decoded))) {
        scan.stop = pos;
        scan.hit = true;
        return true;
      }
      pos = InputScanner.position(decoded);
    }
    return false;
  }

  private static boolean contains(RejectPrefilter.CharClass cc, int codePoint) {
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.record();
    }
    return InputScanner.classContains(cc.ranges(), cc.bitmap0(), cc.bitmap1(), codePoint);
  }
}
