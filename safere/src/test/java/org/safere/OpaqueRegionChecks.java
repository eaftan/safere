// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

/** Shared substring oracle for opaque matching, captures, and find sequences. */
final class OpaqueRegionChecks {
  private OpaqueRegionChecks() {}

  static void assertMatchesSubstring(String regex, String input, int start, int end) {
    Pattern pattern = Pattern.compile(regex);
    for (int operation = 0; operation < 3; operation++) {
      assertThat(trace(pattern.matcher(input).region(start, end), operation, start))
          .as("/%s/ region [%s,%s] operation=%s", regex, start, end, operation)
          .isEqualTo(trace(pattern.matcher(input.substring(start, end)), operation, 0));
    }
  }

  static List<String> trace(Matcher matcher, int operation, int offset) {
    List<String> result = new ArrayList<>();
    int maximumSteps = matcher.regionEnd() - matcher.regionStart() + 2;
    for (int step = 0; step < maximumSteps; step++) {
      boolean matched =
          switch (operation) {
            case 0 -> matcher.matches();
            case 1 -> matcher.lookingAt();
            default -> matcher.find();
          };
      result.add(Boolean.toString(matched));
      if (matched) {
        for (int group = 0; group <= matcher.groupCount(); group++) {
          int start = matcher.start(group);
          int end = matcher.end(group);
          result.add((start < 0 ? start : start - offset) + ":" + (end < 0 ? end : end - offset));
          result.add(matcher.group(group));
        }
      }
      if (operation != 2 || !matched) {
        return result;
      }
    }
    throw new AssertionError("Opaque find sequence failed to make progress: " + matcher.pattern());
  }
}
