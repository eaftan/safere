// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Surrogate consumption at region boundaries")
class SurrogateRegionConsumptionTest {
  record Case(String regex, String input, int start, int end, boolean expected) {
    @Override
    public String toString() {
      return regex + " on " + input.length() + " code units, region [" + start + "," + end + ")";
    }
  }

  static Stream<Case> ordinaryAtomsDecodeWithinTheRegion() {
    String pair = "\uD83D\uDC4D";
    String[] loneSurrogatePatterns = {
      ".", ".*", ".+", "[^a]", "[\\s\\S]", "[\\s\\S]*", "\\D", "\\p{Cs}"
    };
    Stream.Builder<Case> cases = Stream.builder();
    for (String regex : loneSurrogatePatterns) {
      cases.add(new Case(regex, "\uD83D", 0, 1, true));
      cases.add(new Case(regex, "\uD83Dx", 0, 1, true));
      cases.add(new Case(regex, pair, 0, 1, true));
      cases.add(new Case(regex, pair, 1, 2, true));
    }
    for (String input : new String[] {"\uD83D", "\uD83Dx", pair}) {
      cases.add(new Case("\\P{Cs}", input, 0, 1, false));
      cases.add(new Case("[^\\p{Cs}]", input, 0, 1, false));
    }
    cases.add(new Case("\\P{Cs}", pair, 1, 2, false));
    cases.add(new Case("\\p{Cs}", pair, 0, 2, false));
    cases.add(new Case("\\P{Cs}", pair, 0, 2, true));
    cases.add(new Case(".", pair, 0, 2, true));
    return cases.build();
  }

  @ParameterizedTest
  @MethodSource
  @DisabledForCrosscheck("Region-local scalar decoding intentionally differs from JDK")
  void ordinaryAtomsDecodeWithinTheRegion(Case c) {
    Pattern pattern = Pattern.compile(c.regex());
    for (boolean transparent : new boolean[] {false, true}) {
      assertThat(
              pattern
                  .matcher(c.input())
                  .region(c.start(), c.end())
                  .useTransparentBounds(transparent)
                  .matches())
          .as("matches: %s, transparent=%s", c, transparent)
          .isEqualTo(c.expected());

      if (c.expected()) {
        Matcher lookingAt =
            pattern.matcher(c.input()).region(c.start(), c.end()).useTransparentBounds(transparent);
        assertThat(lookingAt.lookingAt()).as("lookingAt: %s", c).isTrue();
        assertThat(lookingAt.start()).isEqualTo(c.start());
        assertThat(lookingAt.end()).isEqualTo(c.end());

        Matcher find =
            pattern.matcher(c.input()).region(c.start(), c.end()).useTransparentBounds(transparent);
        assertThat(find.find()).as("find: %s", c).isTrue();
        assertThat(find.start()).isEqualTo(c.start());
        assertThat(find.end()).isEqualTo(c.end());
      }
    }
  }

  @Test
  @DisabledForCrosscheck("Opaque region decoding intentionally differs from JDK")
  void unicodeWordBoundariesUseTheExposedSurrogateCategory() {
    String pair = "\uD801\uDC00"; // A supplementary letter in the full input.
    for (String input : new String[] {pair, "x" + pair}) {
      int start = input.length() - 2;
      assertThat(Pattern.compile("(?U)\\b.").matcher(input).region(start, start + 1).matches())
          .isFalse();
      assertThat(Pattern.compile("(?U)\\B.").matcher(input).region(start, start + 1).matches())
          .isTrue();
      assertThat(Pattern.compile("(?U)\\b.").matcher("\uD801").matches()).isFalse();
      assertThat(Pattern.compile("(?U)\\B.").matcher("\uD801").matches()).isTrue();
    }
  }

  @Test
  @DisabledForCrosscheck("Region-local ordinary atoms intentionally differ from JDK")
  void ordinaryAlternativesRemainRegionLocalWithGraphemeConstructs() {
    String pair = "\uD83D\uDC4D";
    for (String regex :
        new String[] {"(?:\\X|.)", "(?:.|\\X)", "(?:\\X|[\\s\\S])", "(?:\\b{g}|.)"}) {
      assertThat(Pattern.compile(regex).matcher(pair).region(0, 1).matches())
          .as("%s on the split region", regex)
          .isTrue();
      assertThat(Pattern.compile(regex).matcher("\uD83D").matches())
          .as("%s on the corresponding substring", regex)
          .isTrue();
    }
  }

  @Test
  @DisabledForCrosscheck("Region-local ordinary atoms intentionally differ from JDK")
  void firstOrdinaryAlternativePrecedesCompletedGraphemeMatch() {
    Pattern pattern = Pattern.compile("(.|(\\X))");
    String pair = "\uD83D\uDC4D";
    for (boolean find : new boolean[] {false, true}) {
      Matcher matcher = pattern.matcher(pair).region(0, 1);
      assertThat(find ? matcher.find() : matcher.lookingAt()).isTrue();
      assertThat(matcher.start()).isZero();
      assertThat(matcher.end()).isEqualTo(1);
      assertThat(matcher.group(1)).isEqualTo("\uD83D");
      assertThat(matcher.group(2)).isNull();
    }
  }
}
