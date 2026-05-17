// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import java.util.List;
import java.util.regex.PatternSyntaxException;

/** Shared regex comparison helpers for exhaustive sweeps. */
final class RegexSweep {
  private RegexSweep() {}

  static Outcome jdkTraceOutcome(String regex, int flags, List<String> inputs, int findLimit) {
    try {
      java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex, flags);
      return new Outcome(true, operationTrace(pattern, inputs, findLimit), "");
    } catch (PatternSyntaxException e) {
      return new Outcome(false, "", e.getDescription());
    } catch (RuntimeException e) {
      return new Outcome(false, "", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  static Outcome safeReTraceOutcome(String regex, int flags, List<String> inputs, int findLimit) {
    try {
      org.safere.Pattern pattern = org.safere.Pattern.compile(regex, flags);
      return new Outcome(true, operationTrace(pattern, inputs, findLimit), "");
    } catch (PatternSyntaxException e) {
      return new Outcome(false, "", e.getDescription());
    } catch (RuntimeException e) {
      return new Outcome(false, "", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  static boolean semanticallyEqual(Outcome left, Outcome right) {
    if (left.accepted() != right.accepted()) {
      return false;
    }
    return !left.accepted() || left.trace().equals(right.trace());
  }

  private static String operationTrace(
      java.util.regex.Pattern pattern, List<String> inputs, int findLimit) {
    StringBuilder result = new StringBuilder();
    for (String input : inputs) {
      java.util.regex.Matcher matcher = pattern.matcher(input);
      appendTrace(result, input, "matches", matcher.matches(), matcher);
      matcher.reset();
      appendTrace(result, input, "lookingAt", matcher.lookingAt(), matcher);
      matcher.reset();
      appendFindTrace(result, input, matcher, findLimit);
    }
    return result.toString();
  }

  private static String operationTrace(
      org.safere.Pattern pattern, List<String> inputs, int findLimit) {
    StringBuilder result = new StringBuilder();
    for (String input : inputs) {
      org.safere.Matcher matcher = pattern.matcher(input);
      appendTrace(result, input, "matches", matcher.matches(), matcher);
      matcher.reset();
      appendTrace(result, input, "lookingAt", matcher.lookingAt(), matcher);
      matcher.reset();
      appendFindTrace(result, input, matcher, findLimit);
    }
    return result.toString();
  }

  private static void appendTrace(
      StringBuilder result,
      String input,
      String operation,
      boolean matched,
      java.util.regex.Matcher matcher) {
    if (!matched) {
      return;
    }
    appendSeparator(result);
    result
        .append(escape(input))
        .append(':')
        .append(operation)
        .append('@')
        .append(matcher.start())
        .append('-')
        .append(matcher.end());
  }

  private static void appendTrace(
      StringBuilder result,
      String input,
      String operation,
      boolean matched,
      org.safere.Matcher matcher) {
    if (!matched) {
      return;
    }
    appendSeparator(result);
    result
        .append(escape(input))
        .append(':')
        .append(operation)
        .append('@')
        .append(matcher.start())
        .append('-')
        .append(matcher.end());
  }

  private static void appendFindTrace(
      StringBuilder result, String input, java.util.regex.Matcher matcher, int findLimit) {
    int count = 0;
    while (count < findLimit && matcher.find()) {
      appendFindMatch(result, input, count, matcher.start(), matcher.end());
      count++;
    }
  }

  private static void appendFindTrace(
      StringBuilder result, String input, org.safere.Matcher matcher, int findLimit) {
    int count = 0;
    while (count < findLimit && matcher.find()) {
      appendFindMatch(result, input, count, matcher.start(), matcher.end());
      count++;
    }
  }

  private static void appendFindMatch(
      StringBuilder result, String input, int count, int start, int end) {
    appendSeparator(result);
    result
        .append(escape(input))
        .append(":find")
        .append(count)
        .append('@')
        .append(start)
        .append('-')
        .append(end);
  }

  private static void appendSeparator(StringBuilder result) {
    if (result.length() > 0) {
      result.append(',');
    }
  }

  private static String escape(String value) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> result.append("\\\\");
        case '\n' -> result.append("\\n");
        case '\r' -> result.append("\\r");
        case '\t' -> result.append("\\t");
        case '"' -> result.append("\\\"");
        default -> {
          if (Character.isISOControl(c) || Character.isSurrogate(c)) {
            result.append(String.format("\\u%04X", (int) c));
          } else {
            result.append(c);
          }
        }
      }
    }
    return result.toString();
  }

  record Outcome(boolean accepted, String trace, String error) {}
}
