// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Work bounds for leading expansions over a class that excludes only a few code points. */
@DisabledForCrosscheck("WorkCounter is an internal SafeRE API")
@Tag("work-counter")
class LeadingLineExpansionWorkTest {

  @ParameterizedTest
  @ValueSource(strings = {".*X![0-9]", ".*?X![0-9]", "[^;]*X![0-9]", "(.*)X![0-9]"})
  void tinyUtf8ExistenceDoesNotAddScanningToNativeVerification(String regex) {
    Pattern pattern = Pattern.compile(regex);
    for (boolean expected : new boolean[] {false, true}) {
      byte[] bytes = (expected ? "X!7" : "X!-7").getBytes(UTF_8);
      long nativeWork =
          WorkCounter.countWithForwardDfaStepsForTesting(
              () -> {
                Dfa.SearchResult result =
                    pattern
                        .forwardFirstMatchDfa()
                        .doSearch(new Utf8InputScanner(bytes), 0, false, false, true);
                assertThat(result).isNotNull();
                assertThat(result.matched()).isEqualTo(expected);
              });
      for (boolean diagnostics : new boolean[] {false, true}) {
        long work =
            WorkCounter.countWithForwardDfaStepsForTesting(
                () -> {
                  boolean found =
                      diagnostics
                          ? pattern.findWithDiagnostics(new Utf8InputScanner(bytes))
                          : pattern.find(Utf8Input.trusted(bytes));
                  assertThat(found).isEqualTo(expected);
                });
        assertThat(work)
            .as("%s expected=%s diagnostics=%s", regex, expected, diagnostics)
            .isLessThanOrEqualTo(nativeWork);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {".*error: \\w+", ".*?error: \\w+", "[^;]*error: \\w+", "(.*)error: \\w+"})
  void utf8ExistenceDoesNotTraverseTheLeadingRunInTheDfa(String regex) {
    Pattern pattern = Pattern.compile(regex);
    String suffix = " error: disk";
    for (int repetitions : new int[] {64, 512}) {
      byte[] bytes = ("abcdefghijklmnopqrstuvwxyz".repeat(repetitions) + suffix).getBytes(UTF_8);
      for (boolean diagnostics : new boolean[] {false, true}) {
        long steps =
            WorkCounter.countForwardDfaStepsForTesting(
                () -> {
                  boolean found =
                      diagnostics
                          ? pattern.findWithDiagnostics(new Utf8InputScanner(bytes))
                          : pattern.find(Utf8Input.trusted(bytes));
                  assertThat(found).isTrue();
                });
        // Only the literal and its tail need DFA verification for an existence-only search.
        assertThat(steps)
            .as("%s diagnostics=%s", regex, diagnostics)
            .isLessThan(4L * suffix.length());
      }
    }
  }

  @Test
  void matchOnEveryLineKeepsDfaStartStateFastForward() {
    // With a hit on every line, the expansion keeps returning the search start. That must not
    // quarantine the matcher's start backoff, which also gates the forward DFA's fast-forward
    // through its start state: without it the DFA steps through every byte of every line.
    String line =
        "request handled in 23 ms path=/api/v1/items/12345" + " ".repeat(60) + "error: disk\n";
    int lines = 2_000;
    String text = line.repeat(lines);
    Pattern pattern = Pattern.compile(".*error: \\w+");
    long stringSteps =
        WorkCounter.countForwardDfaStepsForTesting(
            () -> assertThat(count(pattern.matcher(text))).isEqualTo(lines));
    long utf8Steps =
        WorkCounter.countForwardDfaStepsForTesting(
            () ->
                assertThat(count(pattern.matcher(Utf8Input.trusted(text.getBytes(UTF_8)))))
                    .isEqualTo(lines));
    assertThat(stringSteps).isLessThan(text.length() / 2);
    assertThat(utf8Steps).isLessThan(text.length() / 2);
  }

  @ParameterizedTest
  @MethodSource("excludedSeparatorCases")
  void rejectedInnerHitsDoNotRescanThePrefix(String regex, String separator, boolean utf8) {
    Pattern pattern = Pattern.compile(regex);
    String rejected = "p".repeat(256) + separator + ("error" + separator).repeat(1024);
    for (String tail : new String[] {"", "perror"}) {
      String input = rejected + tail;
      int expected = tail.isEmpty() ? 0 : 1;
      long work =
          WorkCounter.countWithForwardDfaStepsForTesting(
              () -> {
                int matches =
                    utf8
                        ? count(pattern.matcher(Utf8Input.trusted(input.getBytes(UTF_8))))
                        : count(pattern.matcher(input));
                assertThat(matches).isEqualTo(expected);
              });
      assertThat(work).isLessThan(10L * input.length());
    }
  }

  static Stream<Arguments> excludedSeparatorCases() {
    return Stream.of(
        Arguments.of(".+error", "\r", false),
        Arguments.of(".+error", "\r", true),
        Arguments.of("\\S+error", " ", false),
        Arguments.of("\\S+error", " ", true));
  }

  private static int count(Matcher matcher) {
    int n = 0;
    while (matcher.find()) {
      n++;
    }
    return n;
  }

  private static int count(Utf8Matcher matcher) {
    int n = 0;
    while (matcher.find()) {
      n++;
    }
    return n;
  }
}
