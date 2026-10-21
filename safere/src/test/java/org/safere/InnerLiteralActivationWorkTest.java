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

@DisabledForCrosscheck("Uses SafeRE's internal work instrumentation")
@Tag("work-counter")
class InnerLiteralActivationWorkTest {
  @Test
  void successfulFirstCandidatesNeverProbeTheSuffix() {
    String[] regexes = {
      "<[^>]*>",
      "\"([^\"]*)\"",
      "\\[\\d+\\]",
      "\"(.*?)\"",
      "\\{([^}]+)\\}",
      "record:[^;\\r\\n]*val=200",
      "record:[^;\\r\\n]*val=200",
      "clé:[^;]*fin"
    };
    String[] units = {
      "<p>value</p> ",
      "\"field\" \"value\" ",
      "[123] [4567] ",
      "\"field\" \"value\" ",
      "{key=1} {value=2} ",
      "record:target_val=200;",
      "record:" + "x".repeat(1_024) + "val=200;",
      "clé:é😀fin;"
    };
    for (int i = 0; i < regexes.length; i++) {
      Pattern pattern = Pattern.compile(regexes[i]);
      String input = units[i].repeat(64);
      for (Runnable operation : operations(pattern, input)) {
        assertThat(WorkCounter.countInnerLiteralProbesForTesting(operation))
            .as("successful /%s/", regexes[i])
            .isZero();
      }
    }
  }

  @Test
  void failedCandidatesActivateRegardlessOfGapLengthOrEncoding() {
    String[] regexes = {
      "record:[^;\\r\\n]*val=200",
      "line:[^\\r\\n]*done",
      "pre:[^;]*;done",
      "clé:[^;]*fin",
      "😀[^;]*終"
    };
    String[] prefixes = {"record:", "line:", "pre:", "clé:", "😀"};
    String[] failures = {";", "\n", ";bad;", ";", ";"};
    String[] tails = {
      "record:target_val=200", "line:target_done", "pre:target;done", "clé:é😀fin", "😀é終"
    };
    for (int i = 0; i < regexes.length; i++) {
      Pattern pattern = Pattern.compile(regexes[i]);
      for (int gap : new int[] {1, 128, 2_048}) {
        String input = (prefixes[i] + "x".repeat(gap) + failures[i]).repeat(128) + tails[i];
        for (Runnable operation : operations(pattern, input)) {
          assertThat(WorkCounter.countInnerLiteralProbesForTesting(operation))
              .as("failed /%s/, gap=%d", regexes[i], gap)
              .isEqualTo(1);
          assertThat(WorkCounter.countForwardDfaStepsForTesting(operation))
              .as("failed /%s/, gap=%d must avoid scanning later candidates", regexes[i], gap)
              .isLessThan(input.length() / 8L);
        }
      }
    }
  }

  @Test
  void isolatedShortFailuresDoNotActivateTheProbe() {
    Pattern pattern = Pattern.compile("record:[^;]*val=200");
    for (String input :
        List.of(
            "record:x;record:ok_val=200;".repeat(256),
            ("record:x;" + "noise;".repeat(1_024)).repeat(4) + "record:ok_val=200;")) {
      for (Runnable operation : operations(pattern, input)) {
        assertThat(WorkCounter.countInnerLiteralProbesForTesting(operation)).isZero();
      }
    }
  }

  @Test
  void isolatedFailuresDoNotAddCandidateScans() {
    String[] regexes = {"record:[^;]*val=200", "\\[\\d+\\]", "line:[^\\r\\n]*done"};
    String[] units = {"record:x;record:ok_val=200;", "[x][123]", "line:x\nline:ok_done\n"};
    for (int i = 0; i < regexes.length; i++) {
      String input = units[i].repeat(256);
      Pattern pattern = Pattern.compile(regexes[i]);
      Pattern ordinary =
          Pattern.compile(
              regexes[i], 0, EnginePathOptions.builder().literalFastPaths(false).build());
      List<Runnable> accelerated = operations(pattern, input);
      List<Runnable> control = operations(ordinary, input);
      for (int operation = 0; operation < accelerated.size(); operation++) {
        accelerated.get(operation).run();
        control.get(operation).run();
        long expected =
            WorkCounter.countStartAccelerationForTesting(control.get(operation)).calls();
        long actual =
            WorkCounter.countStartAccelerationForTesting(accelerated.get(operation)).calls();
        assertThat(actual)
            .as("/%s/, operation=%d", regexes[i], operation)
            .isLessThanOrEqualTo(expected + 2);
      }
    }
  }

  @Test
  void prefixQuarantineDoesNotSuppressProfitableInnerNarrowing() {
    Pattern pattern = Pattern.compile("record:[^;]*val=200");
    String input = "record:x;".repeat(1_024) + "record:target_val=200";
    long backoff = AdaptiveBackoff.NEUTRAL;
    for (int i = 0; i < AcceleratorPolicy.LITERAL.strikeBudget(); i++) {
      backoff =
          AdaptiveBackoff.recordSkip(backoff, 0, 0, input.length(), AcceleratorPolicy.LITERAL);
    }
    assertThat(AdaptiveBackoff.resumePos(backoff)).isGreaterThan(1_024);
    long initialBackoff = backoff;
    for (InputScanner scanner :
        List.of(new StringInputScanner(input), new Utf8InputScanner(input.getBytes(UTF_8)))) {
      Runnable operation =
          () -> {
            Dfa.SearchResult result =
                pattern
                    .forwardFirstMatchDfa()
                    .doSearch(scanner, 0, false, false, true, initialBackoff);
            assertThat(result).isNotNull();
            assertThat(result.matched()).isTrue();
          };
      assertThat(WorkCounter.countInnerLiteralProbesForTesting(operation)).isEqualTo(1);
      assertThat(WorkCounter.countForwardDfaStepsForTesting(operation))
          .isLessThan(input.length() / 16L);
    }
  }

  @Test
  void repeatedSearchesRearmOnlyVisitedFailureNotifications() {
    String prefix = "abcdefghijklmnopqrst:";
    Pattern pattern = Pattern.compile(prefix + "[^;]*Z");
    StringBuilder noise = new StringBuilder((prefix + "x;").repeat(4));
    for (int length = 1; length < prefix.length(); length++) {
      noise.append(prefix, 0, length).append('!');
    }
    String input = noise + prefix + "x".repeat(512) + "Z";
    long backoff = AdaptiveBackoff.NEUTRAL;
    for (int i = 0; i < AcceleratorPolicy.LITERAL.strikeBudget(); i++) {
      backoff =
          AdaptiveBackoff.recordSkip(backoff, 0, 0, input.length(), AcceleratorPolicy.LITERAL);
    }
    long initialBackoff = backoff;
    for (int pass = 0; pass < 8; pass++) {
      for (InputScanner scanner :
          List.of(new StringInputScanner(input), new Utf8InputScanner(input.getBytes(UTF_8)))) {
        Runnable operation =
            () -> {
              Dfa.SearchResult result =
                  pattern
                      .forwardFirstMatchDfa()
                      .doSearch(scanner, 0, false, false, true, initialBackoff);
              assertThat(result).isNotNull();
              assertThat(result.matched()).isTrue();
            };
        assertThat(WorkCounter.countInnerLiteralProbesForTesting(operation)).isEqualTo(1);
        assertThat(WorkCounter.countWithForwardDfaStepsForTesting(operation))
            .isLessThan(input.length() * 3L);
      }
    }
  }

  @Test
  void missingSuffixIsRejectedAfterBoundedNativeWork() {
    Pattern pattern = Pattern.compile("record:[^;]*val=200");
    String input = "record:x;".repeat(1_024);
    Utf8Input bytes = Utf8Input.validated(input.getBytes(UTF_8));
    for (Runnable operation :
        List.<Runnable>of(
            () -> assertThat(pattern.matcher(input).find()).isFalse(),
            () -> assertThat(pattern.matcher(bytes).find()).isFalse(),
            () -> assertThat(pattern.find(bytes)).isFalse())) {
      assertThat(WorkCounter.countInnerLiteralProbesForTesting(operation)).isEqualTo(1);
      assertThat(WorkCounter.countForwardDfaStepsForTesting(operation))
          .isLessThan(input.length() / 16L);
    }
  }

  @Test
  void repeatedFailedProbesRemainLinear() {
    Pattern pattern = Pattern.compile("record:[^;]*val=200");
    for (int size : new int[] {128, 256, 512}) {
      // The bounded backward probe cannot cross this successful gap. Each search must still
      // consume its own suffix rather than scanning the remaining input on every find().
      String input =
          ("record:x;".repeat(16) + "record:" + "x".repeat(512) + "val=200;").repeat(size);
      Utf8Input bytes = Utf8Input.validated(input.getBytes(UTF_8));
      for (Runnable operation :
          List.<Runnable>of(
              () -> findAll(pattern, input),
              () -> {
                Utf8Matcher matcher = pattern.matcher(bytes);
                while (matcher.find()) {
                  assertThat(matcher.end()).isGreaterThan(matcher.start());
                }
              })) {
        assertThat(WorkCounter.countInnerLiteralProbesForTesting(operation)).isEqualTo(size);
        assertThat(WorkCounter.countWithForwardDfaStepsForTesting(operation))
            .isLessThan(input.length() * 4L);
      }
    }
  }

  private static List<Runnable> operations(Pattern pattern, String input) {
    Utf8Input bytes = Utf8Input.validated(input.getBytes(UTF_8));
    return List.of(
        () -> assertThat(pattern.matcher(input).find()).isTrue(),
        () -> assertThat(pattern.matcher(bytes).find()).isTrue(),
        () -> assertThat(pattern.find(bytes)).isTrue(),
        () -> assertThat(pattern.asPredicate().test(input)).isTrue(),
        () -> findAll(pattern, input),
        () -> {
          Utf8Matcher matcher = pattern.matcher(bytes);
          while (matcher.find()) {
            assertThat(matcher.end()).isGreaterThan(matcher.start());
          }
        },
        () -> pattern.matcher(input).replaceAll("X"),
        () -> pattern.split(input, -1));
  }

  private static void findAll(Pattern pattern, String input) {
    Matcher matcher = pattern.matcher(input);
    while (matcher.find()) {
      assertThat(matcher.end()).isGreaterThan(matcher.start());
    }
  }
}
