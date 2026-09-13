// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Preserves SafeRE's interpretation that ignored comment text cannot activate quoting.
 *
 * <p>See issue #858 and INTENTIONAL_DIVERGENCES.md. JDK 26 activates quotes inside comments; these
 * tests intentionally assert SafeRE's chosen comment semantics instead. The JDK documentation does
 * not explicitly specify precedence between quoting and comment removal.
 */
@DisabledForCrosscheck(
    "Quoting constructs inside ignored comments must not affect subsequent lines")
class CommentQuotingModelTest {

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("commentContexts")
  void commentQuotesDoNotChangeFollowingSyntax(
      String description, String prefix, String suffix, int flags, String newline) {
    String comment = prefix + "#\\Q" + newline;

    Pattern wildcard = Pattern.compile(comment + "." + suffix, flags);
    assertThat(wildcard.matcher("x").matches()).isTrue();
    assertThat(wildcard.matcher(".").matches()).isTrue();

    Matcher capture = Pattern.compile(comment + "(a+)" + suffix, flags).matcher("aaa");
    assertThat(capture.matches()).isTrue();
    assertThat(capture.groupCount()).isEqualTo(1);
    assertThat(capture.group(1)).isEqualTo("aaa");

    Pattern range = Pattern.compile(prefix + "[a#\\Q" + newline + "-z]" + suffix, flags);
    assertThat(range.matcher("m").matches()).isTrue();
    assertThat(range.matcher("-").matches()).isFalse();

    Pattern quote = Pattern.compile(comment + "\\Q(\\E" + suffix, flags);
    assertThat(quote.matcher("(").matches()).isTrue();
    assertThat(quote.matcher("Q(").matches()).isFalse();
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("commentContexts")
  void commentQuotesDoNotHideSyntaxErrors(
      String description, String prefix, String suffix, int flags, String newline) {
    for (String invalid : new String[] {"(", "*", ".\\E"}) {
      String regex = prefix + "#\\Q" + newline + invalid + suffix;
      assertThatThrownBy(() -> Pattern.compile(regex, flags))
          .as("%s: syntax after ignored comment: %s", description, invalid)
          .isInstanceOf(PatternSyntaxException.class);
    }
  }

  private static Stream<Arguments> commentContexts() {
    return Stream.of("\n", "\r", "\r\n")
        .flatMap(
            newline -> {
              String label = newline.replace("\r", "CR").replace("\n", "LF");
              return Stream.of(
                  Arguments.of("compile flag / " + label, "", "", Pattern.COMMENTS, newline),
                  Arguments.of("inline flag / " + label, "(?x)", "", 0, newline),
                  Arguments.of("scoped flag / " + label, "(?x:", ")", 0, newline));
            });
  }
}
