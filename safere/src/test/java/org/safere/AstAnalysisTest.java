// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class AstAnalysisTest {

  @Test
  void nullRegexpYieldsSafeDefaults() {
    AstAnalysis analysis = AstAnalysis.analyze(null);
    assertThat(analysis.hasLazy()).isFalse();
    assertThat(analysis.hasAlt()).isFalse();
    assertThat(analysis.hasNullableAlt()).isFalse();
    assertThat(analysis.canMatchEmpty()).isTrue();
    assertThat(analysis.hasUserCaptures()).isFalse();
    assertThat(analysis.minMatchLength()).isEqualTo(0);
    assertThat(analysis.namedGroups()).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({
    "'a+b', false, false, false, false, 2",
    "'a+?b', true, false, false, false, 2",
    "'a|b', false, true, false, false, 1",
    "'a|', false, true, true, true, 0",
    "'(a|b)?', false, true, false, true, 0",
    "'(?:get|post)', false, true, false, false, 3",
    "'(?:get|post|)', false, true, true, true, 0",
    "'a{3,5}', false, false, false, false, 3",
    "'a{3,5}?', true, false, false, false, 3",
    "'.*', false, false, false, true, 0",
    "'.+', false, false, false, false, 1",
    "'^$', false, false, false, true, 0",
    "'\\b', false, false, false, true, 0",
  })
  void structuralAnalysisFlags(
      String regex,
      boolean hasLazy,
      boolean hasAlt,
      boolean hasNullableAlt,
      boolean canMatchEmpty,
      int minMatchLength) {
    Regexp re = Parser.parse(regex, Pattern.toParseFlags(0));
    AstAnalysis analysis = AstAnalysis.analyze(re);

    assertThat(analysis.hasLazy()).isEqualTo(hasLazy);
    assertThat(analysis.hasAlt()).isEqualTo(hasAlt);
    assertThat(analysis.hasNullableAlt()).isEqualTo(hasNullableAlt);
    assertThat(analysis.canMatchEmpty()).isEqualTo(canMatchEmpty);
    assertThat(analysis.minMatchLength()).isEqualTo(minMatchLength);
  }

  @Test
  void namedGroupsAndUserCaptures() {
    Regexp re =
        Parser.parse("(?<year>\\d{4})-(?<month>\\d{2})-(?:\\d{2})", Pattern.toParseFlags(0));
    AstAnalysis analysis = AstAnalysis.analyze(re);

    assertThat(analysis.hasUserCaptures()).isTrue();
    assertThat(analysis.namedGroups()).isEqualTo(Map.of("year", 1, "month", 2));
  }

  @Test
  void nulLiteralConsumesOneCodePoint() {
    Regexp re = Parser.parse("\\x00", Pattern.toParseFlags(0));

    AstAnalysis analysis = AstAnalysis.analyze(re);

    assertThat(analysis.canMatchEmpty()).isFalse();
    assertThat(analysis.minMatchLength()).isEqualTo(1);

    PatternAnalysis publicAnalysis = Pattern.compile("\\x00|a").analysis();
    assertThat(publicAnalysis.features())
        .doesNotContain(PatternFeature.NULLABLE, PatternFeature.NULLABLE_ALTERNATION);
    assertThat(publicAnalysis.capabilities()).contains(PatternCapability.ONE_PASS_PRIMARY);
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc", "(?:abc)", "a[0-9]c", "^abc$"})
  void patternsWithoutUserCaptures(String regex) {
    Regexp re = Parser.parse(regex, Pattern.toParseFlags(0));
    AstAnalysis analysis = AstAnalysis.analyze(re);

    assertThat(analysis.hasUserCaptures()).isFalse();
    assertThat(analysis.namedGroups()).isEmpty();
  }
}
