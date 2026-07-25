// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.IntConsumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Work-bound tests for DFA transition caching in anchored patterns. */
@DisabledForCrosscheck("WorkCounter and the UTF-8 input API are SafeRE-specific")
@Tag("work-counter")
class AnchoredDfaCachingTest {
  private static final int FLAGS =
      ParseFlags.PERL_X | ParseFlags.PERL_CLASSES | ParseFlags.PERL_B | ParseFlags.UNICODE_GROUPS;
  private static final String FRUIT_MARKUP_PATTERN =
      "^\\s*<(\\QApple\\E|\\QBanana\\E|\\QCherry\\E)>\\s*$";
  private static final String MULTILINE_LINE_PATTERN = "(?m)(?:^|,)(?:\"(target)\"|(target))";

  @Test
  void stringAnchorsKeepOrdinaryDfaTransitionsCached() {
    Pattern pattern = Pattern.compile(FRUIT_MARKUP_PATTERN);
    assertCachedTransitionWork(
        size -> {
          String input = nonMatchingInput(size);
          assertThat(pattern.matcher(input).replaceAll("$1")).isEqualTo(input);
        });
  }

  @Test
  void utf8AnchorsKeepOrdinaryDfaTransitionsCached() {
    Prog prog = Compiler.compile(Parser.parse(FRUIT_MARKUP_PATTERN, FLAGS));
    Dfa dfa = new Dfa(prog, 10_000, Dfa.buildSetup(prog), false);
    assertCachedTransitionWork(
        size -> {
          byte[] input = nonMatchingInput(size).getBytes(UTF_8);
          Dfa.SearchResult result = dfa.doSearch(new Utf8InputScanner(input), true, false);
          assertThat(result).isNotNull();
          assertThat(result.matched()).isFalse();
        });
  }

  @Test
  void multilineBeginningAnchorKeepsStringTransitionsCached() {
    Pattern pattern = Pattern.compile(MULTILINE_LINE_PATTERN);
    assertCachedTransitionWork(
        size -> assertThat(pattern.matcher(multilineNonMatchingInput(size)).find()).isFalse());
  }

  @Test
  void multilineBeginningAnchorKeepsUtf8TransitionsCached() {
    Prog prog = Compiler.compile(Parser.parse(MULTILINE_LINE_PATTERN, FLAGS));
    Dfa dfa = new Dfa(prog, 10_000, Dfa.buildSetup(prog), false);
    assertCachedTransitionWork(
        size -> {
          byte[] input = multilineNonMatchingInput(size).getBytes(UTF_8);
          Dfa.SearchResult result = dfa.doSearch(new Utf8InputScanner(input), false, false);
          assertThat(result).isNotNull();
          assertThat(result.matched()).isFalse();
        });
  }

  private static void assertCachedTransitionWork(IntConsumer operation) {
    operation.accept(1_000);
    operation.accept(10_000);

    long smallerWork = WorkCounter.countForTesting(() -> operation.accept(1_000));
    long largerWork = WorkCounter.countForTesting(() -> operation.accept(10_000));

    assertThat(largerWork)
        .as("ordinary anchored transitions should use the DFA cache independent of input length")
        .isLessThanOrEqualTo(smallerWork * 2 + 10);
  }

  private static String nonMatchingInput(int size) {
    String tag = "<Orange>";
    int leadingSpaces = (size - tag.length()) / 2;
    return " ".repeat(leadingSpaces) + tag + " ".repeat(size - tag.length() - leadingSpaces);
  }

  private static String multilineNonMatchingInput(int size) {
    return "value,other\n".repeat((size + 11) / 12).substring(0, size);
  }
}
