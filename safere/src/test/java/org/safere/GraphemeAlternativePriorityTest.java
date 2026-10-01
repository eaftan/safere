// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Checks ordered choice independently of the lengths consumed by its alternatives. */
@DisabledForCrosscheck("Includes intentional SafeRE region-local surrogate semantics")
class GraphemeAlternativePriorityTest {
  @Test
  void firstSuccessfulAlternativeDeterminesBoundsAndCaptures() {
    String[] atoms = {".", "\\X", "\\b{g}", ".?", "\\X?", ".+", "\\X+", ".*?", "\\X*?"};
    String[] inputs = {"ab", "a\u0301b", "\r\nx", "\uD83D\uDC4Dx", "a\uD83D\uDC4D"};
    for (String left : atoms) {
      for (String right : atoms) {
        Pattern alternatives = Pattern.compile("(" + left + ")|(" + right + ")");
        Pattern leftPattern = Pattern.compile(left);
        Pattern rightPattern = Pattern.compile(right);
        for (String input : inputs) {
          for (int start = 0; start <= input.length(); start++) {
            for (int end = start; end <= input.length(); end++) {
              for (boolean transparent : new boolean[] {false, true}) {
                for (boolean full : new boolean[] {false, true}) {
                  Matcher first = matcher(leftPattern, input, start, end, transparent);
                  Matcher second = matcher(rightPattern, input, start, end, transparent);
                  boolean firstMatches = full ? first.matches() : first.lookingAt();
                  boolean secondMatches = full ? second.matches() : second.lookingAt();
                  Matcher actual = matcher(alternatives, input, start, end, transparent);
                  boolean matched = full ? actual.matches() : actual.lookingAt();
                  String label =
                      alternatives
                          + " region="
                          + start
                          + ":"
                          + end
                          + " transparent="
                          + transparent
                          + " full="
                          + full
                          + " input="
                          + input;
                  assertThat(matched).as(label).isEqualTo(firstMatches || secondMatches);
                  if (matched) {
                    Matcher expected = firstMatches ? first : second;
                    assertThat(actual.start()).as(label).isEqualTo(expected.start());
                    assertThat(actual.end()).as(label).isEqualTo(expected.end());
                    assertThat(actual.group(firstMatches ? 1 : 2))
                        .as(label)
                        .isEqualTo(expected.group());
                    assertThat(actual.group(firstMatches ? 2 : 1)).as(label).isNull();
                    if (!full) {
                      Matcher find = matcher(alternatives, input, start, end, transparent);
                      assertThat(find.find()).as(label).isTrue();
                      assertThat(find.start()).as(label).isEqualTo(expected.start());
                      assertThat(find.end()).as(label).isEqualTo(expected.end());
                      assertThat(find.group(firstMatches ? 1 : 2))
                          .as(label)
                          .isEqualTo(expected.group());
                      assertThat(find.group(firstMatches ? 2 : 1)).as(label).isNull();
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  @Test
  void reusedMatcherPreservesPriorityAsConsumptionLimitsChange() {
    Matcher matcher = Pattern.compile("(.|(\\X))").matcher("\uD83D\uDC4D");
    for (int end : new int[] {2, 1, 2, 1}) {
      matcher.region(0, end);
      assertThat(matcher.matches()).isTrue();
      matcher.region(0, end);
      assertThat(matcher.lookingAt()).isTrue();
      assertThat(matcher.end()).isEqualTo(end);
      assertThat(matcher.group(2)).isNull();
      matcher.region(0, end);
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.end()).isEqualTo(end);
      assertThat(matcher.group(2)).isNull();
    }
  }

  @Test
  void earlierCandidateRetainsPriorityOverLaterCompletions() {
    Matcher matcher = Pattern.compile("(\\Xb)|([b\uDC4D])").matcher("z\uD83D\uDC4Db");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(1);
    assertThat(matcher.end()).isEqualTo(4);
    assertThat(matcher.group(1)).isEqualTo("\uD83D\uDC4Db");
    assertThat(matcher.group(2)).isNull();
    assertThat(matcher.find()).isFalse();
  }

  private static Matcher matcher(
      Pattern pattern, String input, int start, int end, boolean transparent) {
    return pattern.matcher(input).region(start, end).useTransparentBounds(transparent);
  }
}
