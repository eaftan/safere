// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.MatchResult;

/** Strict comparisons of bounded state sequences; oracle defects affect observations only. */
final class StrictStateChecks {
  private StrictStateChecks() {}

  static boolean check(
      String regex, String input, int start, int end, boolean anchoring, boolean transparent) {
    var safe = org.safere.Pattern.compile(regex).matcher(input);
    // Execute the entire SafeRE sequence independently even if the oracle later becomes
    // unavailable.
    safe.find(start);
    safe.region(start, end).useAnchoringBounds(anchoring).useTransparentBounds(transparent);
    safe.matches();
    safe.reset().region(start, end).useAnchoringBounds(anchoring).useTransparentBounds(transparent);
    safe.lookingAt();
    safe.find();
    safe.reset();
    safe.replaceAll("-");
    exhausted(safe);
    safe.reset();
    if (safe.find(start)) {
      int oldStart = safe.start(), oldEnd = safe.end();
      String oldGroup = safe.group();
      safe.usePattern(org.safere.Pattern.compile("a"));
      assertThat(safe.hasMatch()).isTrue();
      assertThat(safe.start(0)).isEqualTo(oldStart);
      assertThat(safe.end(0)).isEqualTo(oldEnd);
      assertThat(safe.group()).isEqualTo(oldGroup);
    }
    safe.find();

    safe = org.safere.Pattern.compile(regex).matcher(input);
    var actual = safe;
    try {
      var jdk = java.util.regex.Pattern.compile(regex).matcher(StrictOracle.input(input));
      compare(
          regex,
          input,
          "find(" + start + ")",
          snapshot(actual.find(start), actual),
          snapshot(oracle(() -> jdk.find(start)), jdk));
      actual.region(start, end).useAnchoringBounds(anchoring).useTransparentBounds(transparent);
      jdk.region(start, end).useAnchoringBounds(anchoring).useTransparentBounds(transparent);
      String region =
          "region=" + start + ":" + end + " anchoring=" + anchoring + " transparent=" + transparent;
      compare(
          regex,
          input,
          region + ";matches",
          snapshot(actual.matches(), actual),
          snapshot(oracle(() -> jdk.matches()), jdk));
      actual
          .reset()
          .region(start, end)
          .useAnchoringBounds(anchoring)
          .useTransparentBounds(transparent);
      jdk.reset()
          .region(start, end)
          .useAnchoringBounds(anchoring)
          .useTransparentBounds(transparent);
      compare(
          regex,
          input,
          region + ";lookingAt",
          snapshot(actual.lookingAt(), actual),
          snapshot(oracle(() -> jdk.lookingAt()), jdk));
      compare(
          regex,
          input,
          region + ";lookingAt;find",
          snapshot(actual.find(), actual),
          snapshot(oracle(() -> jdk.find()), jdk));
      actual.reset();
      jdk.reset();
      compare(
          regex,
          input,
          "reset;replaceAll(-)",
          actual.replaceAll("-"),
          oracle(() -> jdk.replaceAll("-")));
      exhausted(actual);
      if (OracleDefects.exhaustedEmptyMatch(jdk)) {
        OracleDefects.record();
      } else {
        compare(regex, input, "replaceAll;hasMatch", actual.hasMatch(), jdk.hasMatch());
        compare(regex, input, "replaceAll;group", outcome(actual::group), outcome(jdk::group));
        compare(regex, input, "replaceAll;start", outcome(actual::start), outcome(jdk::start));
        compare(regex, input, "replaceAll;end", outcome(actual::end), outcome(jdk::end));
      }
      actual.reset();
      jdk.reset();
      boolean found = actual.find(start), expected = oracle(() -> jdk.find(start));
      compare(regex, input, "reset;find(start)", snapshot(found, actual), snapshot(expected, jdk));
      if (found) {
        actual.usePattern(org.safere.Pattern.compile("a"));
        jdk.usePattern(java.util.regex.Pattern.compile("a"));
        compare(regex, input, "usePattern;hasMatch", actual.hasMatch(), jdk.hasMatch());
        compare(regex, input, "usePattern;start", actual.start(), jdk.start());
        compare(regex, input, "usePattern;end", actual.end(), jdk.end());
        if (OracleDefects.clearedGroupsWithLiveMatch(jdk)) {
          OracleDefects.record();
        } else {
          compare(regex, input, "usePattern;groups", snapshot(true, actual), snapshot(true, jdk));
        }
      }
      compare(
          regex,
          input,
          "usePattern;find",
          snapshot(actual.find(), actual),
          snapshot(oracle(() -> jdk.find()), jdk));
      StrictOracle.completed();
      return true;
    } catch (StrictOracle.Unavailable unavailable) {
      // Only JDK resource failures are unavailable; SafeRE above already ran the complete sequence.
      StrictOracle.unavailable();
      return false;
    }
  }

  private static <T> T oracle(Supplier<T> action) {
    try {
      return action.get();
    } catch (StackOverflowError unavailable) {
      throw new StrictOracle.Unavailable();
    }
  }

  static void exhausted(org.safere.Matcher matcher) {
    assertThat(matcher.hasMatch()).isFalse();
    assertThatThrownBy(matcher::group).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(matcher::start).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(matcher::end).isInstanceOf(IllegalStateException.class);
    assertThat(matcher.toMatchResult().hasMatch()).isFalse();
  }

  static String snapshot(boolean found, MatchResult matcher) {
    StringBuilder result = new StringBuilder(Boolean.toString(found));
    if (found) {
      result.append(" groups=").append(matcher.groupCount());
      for (int g = 0; g <= matcher.groupCount(); g++) {
        result
            .append('|')
            .append(matcher.start(g))
            .append(':')
            .append(matcher.end(g))
            .append(':')
            .append(FuzzSupport.javaStringLiteral(Objects.toString(matcher.group(g))));
      }
    }
    return result.toString();
  }

  private static Object outcome(Supplier<?> operation) {
    try {
      return operation.get();
    } catch (IllegalStateException expected) {
      return IllegalStateException.class;
    }
  }

  static void compare(String regex, String input, String operation, Object safe, Object jdk) {
    if (!Objects.equals(safe, jdk)) {
      throw FuzzFinding.failure(
          regex,
          0,
          input,
          operation,
          operation,
          operation,
          Objects.toString(safe),
          Objects.toString(jdk));
    }
  }
}
