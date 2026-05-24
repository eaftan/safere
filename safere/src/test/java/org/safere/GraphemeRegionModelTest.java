// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Tests for SafeRE's documented grapheme-region model where it intentionally diverges from JDK. */
@DisabledForCrosscheck(
    "SafeRE's documented grapheme-region model intentionally diverges from selected JDK traces")
class GraphemeRegionModelTest {

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("regionLocalCases")
  @DisplayName("opaque region-local grapheme behavior is compositional")
  void opaqueRegionLocalGraphemeBehaviorIsCompositional(Case testCase) {
    Matcher matcher =
        Pattern.compile(testCase.regex())
            .matcher(testCase.input())
            .region(testCase.start(), testCase.end());

    assertThat(findTrace(matcher)).containsExactlyElementsOf(testCase.expectedFinds());
  }

  private static Stream<Arguments> regionLocalCases() {
    return Stream.of(
        Arguments.of(
            new Case(
                "explicit boundaries compose across ordinary characters",
                "\\b{g}\\X\\b{g}",
                "ab",
                0,
                2,
                List.of("0-1;g0=U+0061", "1-2;g0=U+0062"))),
        Arguments.of(
            new Case(
                "standalone ZWJ at opaque region start has a trailing boundary",
                "\\X\\b{g}",
                "\uD83D\uDC69\u200D\uD83D\uDC69",
                2,
                5,
                List.of("2-3;g0=U+200D", "3-5;g0=U+1F469"))),
        Arguments.of(
            new Case(
                "explicit leading boundary composes at opaque low-surrogate region start",
                "\\b{g}\\X",
                "\uD83D\uDC4D\uD83C\uDFFB",
                1,
                3,
                List.of("1-2;g0=U+DC4D", "2-4;g0=U+1F3FB"))),
        Arguments.of(
            new Case(
                "explicit leading boundary composes at opaque ZWJ region start",
                "\\b{g}\\X",
                "\uD83D\uDC69\u200D\uD83D\uDC69",
                2,
                5,
                List.of("2-3;g0=U+200D", "3-5;g0=U+1F469"))),
        Arguments.of(
            new Case(
                "explicit boundaries compose around standalone ZWJ at opaque region start",
                "\\b{g}\\X\\b{g}",
                "\uD83D\uDC69\u200D\uD83D\uDC69",
                2,
                5,
                List.of("2-3;g0=U+200D", "3-5;g0=U+1F469"))),
        Arguments.of(
            new Case(
                "explicit boundaries compose after a control before an extender",
                "\\b{g}\\X\\b{g}",
                "a\u0000\u0301",
                1,
                3,
                List.of("1-2;g0=U+0000", "2-3;g0=U+0301"))),
        Arguments.of(
            new Case(
                "explicit boundaries compose around an Indic suffix region",
                "\\b{g}\\X\\b{g}",
                "\u0915\u094D\u0937",
                1,
                3,
                List.of("1-2;g0=U+094D", "2-3;g0=U+0937"))),
        Arguments.of(
            new Case(
                "unanchored chained clusters can start at an Indic suffix",
                "\\X\\X",
                "\u0915\u094D\u0937",
                0,
                3,
                List.of("1-3;g0=U+094D U+0937"))),
        Arguments.of(
            new Case(
                "quantified clusters can start at an Indic suffix",
                "\\X{2}",
                "\u0915\u094D\u0937",
                0,
                3,
                List.of("1-3;g0=U+094D U+0937"))),
        Arguments.of(
            new Case(
                "captured chained clusters preserve suffix-local captures",
                "(\\X)(\\X)",
                "\u0915\u094D\u0937",
                0,
                3,
                List.of("1-3;g0=U+094D U+0937;g1=U+094D;g2=U+0937"))));
  }

  private static List<String> findTrace(Matcher matcher) {
    List<String> trace = new ArrayList<>();
    while (matcher.find()) {
      StringBuilder builder = new StringBuilder();
      builder.append(matcher.start()).append('-').append(matcher.end());
      for (int group = 0; group <= matcher.groupCount(); group++) {
        builder.append(";g").append(group).append('=').append(codePoints(matcher.group(group)));
      }
      trace.add(builder.toString());
    }
    return trace;
  }

  private static String codePoints(String value) {
    if (value == null) {
      return "<null>";
    }
    StringBuilder builder = new StringBuilder();
    value
        .codePoints()
        .forEach(
            cp -> {
              if (!builder.isEmpty()) {
                builder.append(' ');
              }
              builder.append(String.format("U+%04X", cp));
            });
    return builder.toString();
  }

  private record Case(
      String name, String regex, String input, int start, int end, List<String> expectedFinds) {
    @Override
    public String toString() {
      return name;
    }
  }
}
