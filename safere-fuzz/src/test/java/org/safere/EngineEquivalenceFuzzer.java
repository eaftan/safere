// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Engine equivalence over a small grammar, Unicode subjects and bounded find sequences. */
public final class EngineEquivalenceFuzzer {
  private static final List<EnginePathOptions> PATHS =
      List.of(
          EnginePathOptions.allEnabled(),
          EnginePathOptions.builder().startAcceleration(false).build(),
          options(true, true, true),
          options(false, true, false),
          options(false, false, true),
          options(false, false, false));

  @FuzzTest(maxDuration = "30s")
  void engines(FuzzedDataProvider data) {
    fuzzerTestOneInput(data);
  }

  /** Shared local and standalone entry point. */
  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
    String atom = data.pickValue(List.of("a", ".", "[ab]", "[^a]", "é", "😀"));
    String boundaryAtom =
        data.pickValue(List.of(atom, "\\p{Cs}", "\\p{So}", "[\\uDC00-\\uDFFF]", "\\uDC4D"));
    String leadingRepeat =
        boundaryAtom + data.pickValue(List.of("*", "*?", "+", "+?", "?", "??", "{0,2}", "{1,2}?"));
    String suffix = data.pickValue(List.of("[ab]", "a", "(a|b)"));
    String regex =
        data.pickValue(
            List.of(
                atom,
                atom + "|",
                "(?:" + atom + ")+",
                "(" + atom + ")?b",
                "^" + atom + "+$",
                boundaryAtom + "\\b{g}",
                "(" + boundaryAtom + ")(\\b{g})",
                "\\b{g}(" + boundaryAtom + ")",
                leadingRepeat + suffix + "\\b{g}",
                "(" + leadingRepeat + ")(" + suffix + ")(\\b{g})",
                "(?:(" + boundaryAtom + ")\\b{g}|(a))"));
    StringBuilder input = new StringBuilder();
    int length = data.consumeInt(0, 32);
    for (int i = 0; i < length; i++) {
      input.append(
          data.pickValue(
              List.of(
                  "a", "b", "é", "中", "😀", "👍", "\ud800", "\udc00", "\uDC4D", "\n", "\u0301")));
    }
    check(regex, input.toString(), data.consumeInt(0, input.length()));
    int start = data.consumeInt(0, input.length());
    int end = data.consumeInt(start, input.length());
    checkRegion(regex, input.toString(), start, end, data.consumeBoolean(), data.consumeBoolean());
  }

  static void check(String regex, String input, int start) {
    List<String> expected = trace(Pattern.compile(regex, 0, PATHS.getLast()), input, start);
    List<String> expectedUtf8 =
        validScalars(input) ? utf8Trace(Pattern.compile(regex, 0, PATHS.getLast()), input) : null;
    for (EnginePathOptions path : PATHS) {
      Pattern pattern = Pattern.compile(regex, 0, path);
      assertThat(trace(pattern, input, start))
          .as("%s on %s at %s via %s", regex, escaped(input), start, path)
          .isEqualTo(expected);
      if (expectedUtf8 != null) {
        List<String> utf8 = utf8Trace(pattern, input);
        assertThat(utf8)
            .as("UTF-8 %s on %s via %s", regex, escaped(input), path)
            .isEqualTo(expectedUtf8);
        // Empty-match continuation advances by UTF-16 unit versus UTF-8 code point.
        // Fresh matches/lookingAt and the first find start at equivalent positions.
        assertThat(utf8.subList(0, 3)).isEqualTo(trace(pattern, input, 0).subList(0, 3));
      }
    }
  }

  static void checkRegion(
      String regex, String input, int start, int end, boolean transparent, boolean anchoring) {
    List<String> expected =
        regionTrace(
            Pattern.compile(regex, 0, PATHS.getLast()), input, start, end, transparent, anchoring);
    for (EnginePathOptions path : PATHS) {
      assertThat(
              regionTrace(
                  Pattern.compile(regex, 0, path), input, start, end, transparent, anchoring))
          .as(
              "%s on %s region [%s,%s), transparent=%s, anchoring=%s via %s",
              regex, escaped(input), start, end, transparent, anchoring, path)
          .isEqualTo(expected);
    }
  }

  private static EnginePathOptions options(boolean onePass, boolean dfa, boolean bitState) {
    return EnginePathOptions.builder()
        .literalFastPaths(false)
        .charClassMatchFastPaths(false)
        .keywordAlternationFastPath(false)
        .startAcceleration(false)
        .shiftDfa(false)
        .onePass(onePass)
        .dfa(dfa)
        .bitState(bitState)
        .build();
  }

  private static List<String> trace(Pattern pattern, String input, int start) {
    Matcher matcher = pattern.matcher(input);
    List<String> result = new ArrayList<>();
    result.add(snapshot(matcher.matches(), matcher));
    matcher.reset();
    result.add(snapshot(matcher.lookingAt(), matcher));
    matcher.reset();
    boolean found = matcher.find(start);
    for (int count = 0; count <= input.length() + 1; count++) {
      result.add(snapshot(found, matcher));
      if (!found) {
        return result;
      }
      found = matcher.find();
    }
    throw new AssertionError("unbounded find sequence");
  }

  private static String snapshot(boolean found, Matcher matcher) {
    StringBuilder value = new StringBuilder(Boolean.toString(found));
    if (found)
      for (int g = 0; g <= matcher.groupCount(); g++) {
        value
            .append('|')
            .append(matcher.start(g))
            .append(':')
            .append(matcher.end(g))
            .append(':')
            .append(escaped(matcher.group(g)));
      }
    return value.toString();
  }

  private static List<String> regionTrace(
      Pattern pattern, String input, int start, int end, boolean transparent, boolean anchoring) {
    Matcher matcher =
        pattern.matcher(input).useTransparentBounds(transparent).useAnchoringBounds(anchoring);
    List<String> result = new ArrayList<>();
    matcher.region(start, end);
    result.add(snapshot(matcher.matches(), matcher));
    matcher.region(start, end);
    result.add(snapshot(matcher.lookingAt(), matcher));
    matcher.region(start, end);
    for (int count = 0; count <= input.length() + 1; count++) {
      boolean found = matcher.find();
      result.add(snapshot(found, matcher));
      if (!found) {
        return result;
      }
    }
    throw new AssertionError("unbounded region find sequence");
  }

  private static List<String> utf8Trace(Pattern pattern, String input) {
    byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
    Utf8Matcher matcher = pattern.matcher(Utf8Input.validated(bytes));
    List<String> result = new ArrayList<>();
    result.add(utf8Snapshot(matcher.matches(), matcher, bytes));
    matcher.reset();
    result.add(utf8Snapshot(matcher.lookingAt(), matcher, bytes));
    matcher.reset();
    for (int count = 0; count <= bytes.length + 1; count++) {
      boolean found = matcher.find();
      result.add(utf8Snapshot(found, matcher, bytes));
      if (!found) return result;
    }
    throw new AssertionError("unbounded UTF-8 find sequence");
  }

  private static String utf8Snapshot(boolean found, Utf8Matcher matcher, byte[] bytes) {
    StringBuilder value = new StringBuilder(Boolean.toString(found));
    if (found)
      for (int g = 0; g <= matcher.groupCount(); g++) {
        int start = matcher.start(g), end = matcher.end(g);
        value
            .append('|')
            .append(utf16Offset(bytes, start))
            .append(':')
            .append(utf16Offset(bytes, end))
            .append(':')
            .append(
                start < 0
                    ? "null"
                    : escaped(new String(bytes, start, end - start, StandardCharsets.UTF_8)));
      }
    return value.toString();
  }

  private static int utf16Offset(byte[] bytes, int offset) {
    return offset < 0 ? -1 : new String(bytes, 0, offset, StandardCharsets.UTF_8).length();
  }

  private static boolean validScalars(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
      } else if (Character.isLowSurrogate(c)) return false;
    }
    return true;
  }

  private static String escaped(String text) {
    if (text == null) return "null";
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < text.length(); i++)
      result.append(String.format("\\u%04x", (int) text.charAt(i)));
    return result.toString();
  }
}
