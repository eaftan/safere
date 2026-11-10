// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Progress checks must not turn bounded-repeat fallback into input partition enumeration. */
@DisabledForCrosscheck("deterministic work accounting and package-private engine controls")
@Tag("work-counter")
class RepetitionPolicyWorkTest {
  @Test
  void nestedFiniteInvocationsKeepLinearWorkAcrossInputLengths() {
    for (boolean bitState : List.of(false, true)) {
      EnginePathOptions options =
          EnginePathOptions.builder()
              .literalFastPaths(false)
              .charClassMatchFastPaths(false)
              .keywordAlternationFastPath(false)
              .startAcceleration(false)
              .shiftDfa(false)
              .onePass(false)
              .dfa(false)
              .reverseDfa(false)
              .bitState(bitState)
              .lazyCaptureExtraction(false)
              .build();
      for (String regex : List.of("^(?:(?:()|a){0,3})*b$", "^(?:(a?){1,3})+b$")) {
        Pattern pattern = Pattern.compile(regex, 0, options);
        for (boolean rejects : List.of(false, true)) {
          for (boolean utf8 : List.of(false, true)) {
            long previous = 0;
            for (int length : List.of(32, 256, 2048)) {
              String input = "a".repeat(length) + (rejects ? "!b" : "b");
              Utf8Input bytes = Utf8Input.trusted(input.getBytes(UTF_8));
              long work =
                  WorkCounter.countForTesting(
                      () -> {
                        if (utf8) {
                          assertThat(pattern.matcher(bytes).matches()).isEqualTo(!rejects);
                        } else {
                          assertThat(pattern.matcher(input).matches()).isEqualTo(!rejects);
                        }
                      });
              assertThat(work).isPositive();
              if (previous > 0) {
                assertThat(work)
                    .as("/%s/ bitState=%s utf8=%s rejects=%s", regex, bitState, utf8, rejects)
                    .isLessThanOrEqualTo(previous * 8 * 3);
              }
              previous = work;
            }
          }
        }
      }
    }
  }
}
