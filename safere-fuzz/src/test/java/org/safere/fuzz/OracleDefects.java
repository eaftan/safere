// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;

/** Observed oracle defects only; no regex recognition or SafeRE-result matching. */
final class OracleDefects {
  static final LongAdder EXCLUDED_OBSERVATIONS = new LongAdder();

  private OracleDefects() {}

  // JDK-8390449: usePattern clears group zero without invalidating the whole match.
  // The exhausted-find variant is documented separately in SafeRE #931/#933.
  // Do not claim that the upstream ticket already covers that variant.
  static boolean clearedGroupsWithLiveMatch(Matcher matcher) {
    if (!matcher.hasMatch() || matcher.start() < 0 || matcher.end() < matcher.start()) {
      return false;
    }
    for (int group = 0; group <= matcher.groupCount(); group++) {
      if (matcher.start(group) != -1 || matcher.end(group) != -1 || matcher.group(group) != null) {
        return false;
      }
    }
    return matcher.group() == null;
  }

  static boolean exhaustedEmptyMatch(Matcher matcher) {
    return clearedGroupsWithLiveMatch(matcher)
        && matcher.start() == matcher.regionEnd()
        && matcher.end() == matcher.regionEnd();
  }

  static void record() {
    EXCLUDED_OBSERVATIONS.increment();
  }
}
