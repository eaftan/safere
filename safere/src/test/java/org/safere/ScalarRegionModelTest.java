// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks scalar regions against the corresponding bounded text, including capture traces. */
@DisabledForCrosscheck("Intentional region-local surrogate decoding, see #867")
class ScalarRegionModelTest {
  @Test
  void consumingAtomsIgnoreHiddenPrefixAndSuffix() {
    checkRegions(
        List.of(
            ".",
            ".*",
            ".+?",
            "(.)*",
            "(.+)",
            "(a|.)*",
            "([^a]+)",
            "[\\s\\S]*",
            "\\p{Cs}+",
            "\\P{Cs}+",
            "[\\p{L}\\p{Cs}]+",
            ".{0,2}",
            "(a+?)?"),
        true);
  }

  @Test
  void opaqueWordAssertionsUseTheBoundedCharacterCategories() {
    checkRegions(List.of("\\b.", "\\B.", "(?U)\\b.", "(?U)\\B.", "(?U).\\b", "(?U).\\B"), false);
  }

  @Test
  void unreachableGraphemeAlternativeDoesNotChangeOrdinaryWordAssertions() {
    for (String prefix : List.of("", "a", " ", "aa")) {
      for (String boundary : List.of("\\b", "\\B", "(?U:\\b)", "(?U:\\B)")) {
        String regex = Pattern.quote(prefix) + boundary + ".";
        Pattern ordinary = Pattern.compile(regex);
        Pattern mixed = Pattern.compile("(?:" + regex + ")|(?:\\Xz)");
        String input = prefix + "\uD801\uDC00x";
        int end = prefix.length() + 1;
        for (boolean transparent : new boolean[] {false, true}) {
          for (int operation = 0; operation < 3; operation++) {
            Matcher expected = ordinary.matcher(input).region(0, end);
            Matcher actual = mixed.matcher(input).region(0, end);
            expected.useTransparentBounds(transparent);
            actual.useTransparentBounds(transparent);
            assertThat(trace(actual, operation, 0))
                .as(
                    "%s prefix=%s transparent=%s operation=%s",
                    regex, prefix, transparent, operation)
                .isEqualTo(trace(expected, operation, 0));
          }
        }
      }
    }
  }

  @Test
  void unreachableGraphemeAlternativeDoesNotChangeScalarGraphemeAssertions() {
    for (String prefix : List.of("", "a", "a\u0301", "\uD83C\uDDE6\uD83C\uDDE7")) {
      for (String scalar : List.of("(.)\\b{g}", "(.)\\b{g}$", "\\b{g}(.)\\b{g}")) {
        String regex = Pattern.quote(prefix) + scalar;
        Pattern ordinary = Pattern.compile(regex);
        for (String pair : List.of("\uD83D\uDC4D", "\uD801\uDC00", "\uD83C\uDDE6")) {
          String input = "q" + prefix + pair + "x";
          int end = 1 + prefix.length() + 1;
          for (String mixedRegex :
              List.of("(?:" + regex + ")|(?:\\Xz)", "(?:\\Xz)|(?:" + regex + ")")) {
            Pattern mixed = Pattern.compile(mixedRegex);
            for (boolean transparent : new boolean[] {false, true}) {
              for (boolean anchoring : new boolean[] {false, true}) {
                for (int operation = 0; operation < 3; operation++) {
                  Matcher expected = ordinary.matcher(input).region(1, end);
                  Matcher actual = mixed.matcher(input).region(1, end);
                  expected.useTransparentBounds(transparent).useAnchoringBounds(anchoring);
                  actual.useTransparentBounds(transparent).useAnchoringBounds(anchoring);
                  assertThat(trace(actual, operation, 0))
                      .as(
                          "%s input=%s transparent=%s anchoring=%s operation=%s",
                          mixedRegex, input, transparent, anchoring, operation)
                      .isEqualTo(trace(expected, operation, 0));
                }
              }
            }
          }
        }
      }
    }
  }

  @Test
  void unreachableGraphemeAlternativeDoesNotChangeInteriorAssertions() {
    for (String pair : List.of("\uD83C\uDDE6", "\uD83D\uDC4D", "\uD801\uDC00")) {
      String input = pair.repeat(3);
      for (String regex : List.of(".\\b{g}", ".\\b{g}.", "(.)\\b{g}(.)", "[^a]\\b{g}[\\s\\S]")) {
        Pattern ordinary = Pattern.compile(regex);
        for (String mixedRegex :
            List.of("(?:" + regex + ")|(?:\\Xz)", "(?:\\Xz)|(?:" + regex + ")")) {
          Pattern mixed = Pattern.compile(mixedRegex);
          for (boolean transparent : new boolean[] {false, true}) {
            for (boolean anchoring : new boolean[] {false, true}) {
              for (int operation = 0; operation < 3; operation++) {
                Matcher expected = ordinary.matcher(input).region(1, 3);
                Matcher actual = mixed.matcher(input).region(1, 3);
                expected.useTransparentBounds(transparent).useAnchoringBounds(anchoring);
                actual.useTransparentBounds(transparent).useAnchoringBounds(anchoring);
                assertThat(trace(actual, operation, 0))
                    .as(
                        "%s input=%s transparent=%s anchoring=%s operation=%s",
                        mixedRegex, input, transparent, anchoring, operation)
                    .isEqualTo(trace(expected, operation, 0));
              }
            }
          }
        }
      }
    }
  }

  @Test
  void interiorRegionalIndicatorAssertionUsesCompleteCharacterContext() {
    String input = "\uD83C\uDDE6".repeat(2);
    for (String regex : List.of(".\\b{g}", ".\\b{g}.", "\\X\\b{g}")) {
      assertThat(Pattern.compile(regex).matcher(input).region(1, 3).lookingAt()).isFalse();
      assertThat(java.util.regex.Pattern.compile(regex).matcher(input).region(1, 3).lookingAt())
          .isFalse();
    }
  }

  @Test
  void terminalClosureFailureStillAllowsAnEmptyMatchAtTheRegionEnd() {
    Matcher matcher = Pattern.compile("a\\Xb|$").matcher("a\uD83D\uDC4D").region(0, 2);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(2);
    assertThat(matcher.end()).isEqualTo(2);
    assertThat(matcher.find()).isFalse();
  }

  private static void checkRegions(List<String> patterns, boolean includeTransparent) {
    for (String regex : patterns) {
      Pattern pattern = Pattern.compile(regex);
      for (String input :
          List.of("a\uD83D\uDC4Db", "x\uD801\uDC00a", "\uD83Dx\uDC4D", "a\u0301", "")) {
        Matcher region = pattern.matcher(input);
        for (int start = 0; start <= input.length(); start++) {
          for (int end = start; end <= input.length(); end++) {
            for (boolean transparent : new boolean[] {false, true}) {
              if (transparent && !includeTransparent) {
                continue;
              }
              for (boolean anchoring : new boolean[] {false, true}) {
                for (int operation = 0; operation < 3; operation++) {
                  region
                      .region(start, end)
                      .useTransparentBounds(transparent)
                      .useAnchoringBounds(anchoring);
                  Matcher bounded = pattern.matcher(input.substring(start, end));
                  assertThat(trace(region, operation, start))
                      .as(
                          "%s input=%s region=%s:%s transparent=%s anchoring=%s operation=%s",
                          regex, input, start, end, transparent, anchoring, operation)
                      .isEqualTo(trace(bounded, operation, 0));
                }
              }
            }
          }
        }
      }
    }
  }

  private static List<String> trace(Matcher matcher, int operation, int offset) {
    List<String> result = new ArrayList<>();
    while (true) {
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
  }
}
