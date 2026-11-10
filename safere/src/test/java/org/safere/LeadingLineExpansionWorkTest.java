// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Work bounds for leading expansions over a class that excludes only a few code points. */
@DisabledForCrosscheck("WorkCounter is an internal SafeRE API")
@Tag("work-counter")
class LeadingLineExpansionWorkTest {

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
