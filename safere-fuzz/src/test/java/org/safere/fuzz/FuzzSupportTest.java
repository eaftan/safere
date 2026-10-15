// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class FuzzSupportTest {
  @ParameterizedTest
  @MethodSource("unsupportedPatterns")
  void unsupportedSyntaxRemainsAnExploratoryFinding(String regex, int flags) {
    // These documented SafeRE dialect boundaries are accepted syntax in the JDK 26 Pattern API.
    assertThatThrownBy(() -> org.safere.Pattern.compile(regex, flags))
        .isInstanceOf(PatternSyntaxException.class);
    assertThatNoException().isThrownBy(() -> Pattern.compile(regex, flags));
    assertThatThrownBy(() -> FuzzSupport.compileCompatibleOrSkip(regex, flags))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("compile divergence");
  }

  private static Stream<Arguments> unsupportedPatterns() {
    return Stream.of(
        Arguments.of("(?=a)a", 0),
        Arguments.of("(?!b)a", 0),
        Arguments.of("(?<=a)b", 0),
        Arguments.of("a(?<!b)", 0),
        Arguments.of("(a)\\1", 0),
        // Fixed probes formerly run by EscapeSyntaxFuzzer; the JDK accepts these as references.
        Arguments.of("\\400", 0),
        Arguments.of("\\777", 0),
        Arguments.of("\\123", 0),
        Arguments.of("\\12*", 0),
        Arguments.of("\\2222*", 0),
        Arguments.of("3*\\2222*", 0),
        Arguments.of("(a)\\12", 0),
        Arguments.of("(?<item>a)\\k<item>", 0),
        Arguments.of("a++", 0),
        Arguments.of("a*+", 0),
        Arguments.of("a?+", 0),
        Arguments.of("a{1,2}+", 0),
        Arguments.of("a+\n+", Pattern.COMMENTS),
        Arguments.of("a+ #comment\n +", Pattern.COMMENTS),
        Arguments.of("(?x)a+ #comment\n +", 0),
        Arguments.of("(?x:a+ #comment\n +)", 0),
        Arguments.of("(?>a+)", 0),
        Arguments.of("\\G", 0),
        Arguments.of("\\G\\w+", 0),
        Arguments.of("a+\\GGGG", Pattern.COMMENTS | Pattern.CASE_INSENSITIVE | Pattern.UNIX_LINES));
  }

  @ParameterizedTest
  @ValueSource(strings = {"(?=a)", "\\1", "a++", "(?>a)", "\\G"})
  void syntaxLikeLiteralTextIsStillCompared(String literal) {
    assertLiteralMatch(Pattern.quote(literal), 0, literal);
    assertLiteralMatch(literal, Pattern.LITERAL, literal);
  }

  private static void assertLiteralMatch(String regex, int flags, String input) {
    var compiled = FuzzSupport.compileCompatibleOrSkip(regex, flags);
    assertThat(compiled).isNotNull();
    assertThat(compiled.matcher(input).matches()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"[", "(", "\\"})
  void rejectionByBothEnginesEndsTheComparison(String regex) {
    assertThatThrownBy(() -> org.safere.Pattern.compile(regex))
        .isInstanceOf(PatternSyntaxException.class);
    assertThatThrownBy(() -> Pattern.compile(regex)).isInstanceOf(PatternSyntaxException.class);
    assertThat(FuzzSupport.compileCompatibleOrSkip(regex, 0)).isNull();
  }
}
