// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.MatchResult;

/** Exact comparison of valid patterns: no compile skips or semantic waivers. */
final class StrictDifferential {
  record Step(String operation, boolean matched, List<Group> groups) {}

  record Group(int start, int end, String text) {}

  private StrictDifferential() {}

  static boolean check(String regex, String input) {
    // Always execute SafeRE first, including all observations, even if the JDK is unavailable.
    var actual = org.safere.Pattern.compile(regex).matcher(input);
    List<Step> safe =
        trace(
            actual,
            actual::matches,
            actual::lookingAt,
            actual::find,
            () -> actual.reset(),
            input.length());
    List<Step> jdk;
    List<Step> observed = new ArrayList<>();
    try {
      var expected = java.util.regex.Pattern.compile(regex).matcher(StrictOracle.input(input));
      jdk =
          trace(
              expected,
              expected::matches,
              expected::lookingAt,
              expected::find,
              () -> expected.reset(),
              input.length(),
              step -> {
                observed.add(step);
                int length = observed.size();
                if (length > safe.size() || !safe.get(length - 1).equals(step)) {
                  assertSame(regex, input, safe, observed);
                }
              });
    } catch (StrictOracle.Unavailable | StackOverflowError unavailable) {
      StrictOracle.unavailable();
      return false;
    }
    assertSame(regex, input, safe, jdk);
    StrictOracle.completed();
    return true;
  }

  static List<Step> trace(
      MatchResult matcher,
      BooleanSupplier matches,
      BooleanSupplier lookingAt,
      BooleanSupplier find,
      Runnable reset,
      int length) {
    return trace(matcher, matches, lookingAt, find, reset, length, ignored -> {});
  }

  private static List<Step> trace(
      MatchResult matcher,
      BooleanSupplier matches,
      BooleanSupplier lookingAt,
      BooleanSupplier find,
      Runnable reset,
      int length,
      Consumer<Step> observer) {
    List<Step> result = new ArrayList<>();
    Consumer<Step> append =
        value -> {
          result.add(value);
          observer.accept(value);
        };
    append.accept(step("matches", matches.getAsBoolean(), matcher));
    reset.run();
    append.accept(step("lookingAt", lookingAt.getAsBoolean(), matcher));
    reset.run();
    for (int attempt = 0; attempt <= length + 1; attempt++) {
      boolean found = find.getAsBoolean();
      append.accept(step("find", found, matcher));
      if (!found) {
        return List.copyOf(result);
      }
    }
    throw new AssertionError("find did not make bounded progress");
  }

  private static Step step(String operation, boolean matched, MatchResult matcher) {
    List<Group> groups = new ArrayList<>();
    if (matched) {
      for (int group = 0; group <= matcher.groupCount(); group++) {
        groups.add(new Group(matcher.start(group), matcher.end(group), matcher.group(group)));
      }
    }
    return new Step(operation, matched, List.copyOf(groups));
  }

  static void assertSame(String regex, String input, List<Step> safe, List<Step> jdk) {
    if (!Objects.equals(safe, jdk)) {
      throw FuzzFinding.failure(
          regex,
          0,
          input,
          "strict differential",
          "default bounds",
          "matches;reset;lookingAt;reset;find until exhausted",
          safe.toString(),
          jdk.toString());
    }
  }
}
