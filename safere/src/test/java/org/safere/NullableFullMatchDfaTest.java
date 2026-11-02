// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

/** Full-match recognition can defer the ordered captures of progress-bearing programs. */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@DisabledForCrosscheck("exercises SafeRE engine authority and the approved repetition policy")
class NullableFullMatchDfaTest {
  @AfterEach
  void disableDiagnostics() {
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
  }

  @Test
  void successfulFullMatchesUseDfaBeforeAnyCaptureDemand() {
    for (String regex :
        List.of(
            "(?:a?){0,32}b",
            "(?:a?){0,1000}b",
            "(?:(?<empty>)|a){0,32}b",
            "(a?)*b",
            "(?:(?<empty>)|a){2,3}b")) {
      String input =
          regex.contains("{2,3}") ? "aab" : "a".repeat(regex.contains("1000") ? 512 : 16) + "b";
      for (boolean utf8 : List.of(false, true)) {
        Pattern pattern = Pattern.compile(regex);
        assertThat(pattern.prog().numLoopRegs()).isPositive();
        List<OperationDiagnostics> events = new ArrayList<>();
        Pattern.setDiagnostics(
            new SafeReMatchDiagnostics() {
              @Override
              public void onOperationCompleted(OperationDiagnostics event) {
                events.add(event);
              }
            });
        boolean matched =
            utf8
                ? pattern.matcher(Utf8Input.trusted(input.getBytes(UTF_8))).matches()
                : pattern.matcher(input).matches();
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
        assertThat(matched).isTrue();
        assertThat(events)
            .singleElement()
            .satisfies(
                event -> {
                  assertThat(event.boundaryStrategy()).as(regex).isEqualTo(MatchStrategy.DFA);
                  assertThat(event.captureStrategy()).isEqualTo(MatchStrategy.NONE);
                  assertThat(event.forwardDfaSearchCount()).isEqualTo(1);
                  assertThat(event.strategyDecisions())
                      .doesNotContain(
                          new StrategyDecision(
                              MatchStrategy.DFA,
                              StrategyDisposition.BYPASSED,
                              StrategyReason.EXACT_NULLABLE_LOOP_SEMANTICS_REQUIRED));
                });
      }
    }
  }

  @Test
  void fullMatchRecognitionAndDeferredCapturesAgreeWithTheExactEngine() {
    List<String> inputs =
        List.of("", "a", "b", "aa", "ab", "aaa", "aab", "abb", "a\n", "é", "éb", "😀", "😀b");
    for (String operand :
        List.of("a?", "()|a", "b?|a", "\\A|a", "a|\\z", "\\b|a", "((a)?)", "a??", "é?", "😀|")) {
      for (int minimum : List.of(0, 1, 2)) {
        for (int maximum : List.of(minimum, minimum + 1, minimum + 3, -1)) {
          for (String mode : List.of("", "?")) {
            for (String suffix : List.of("b", "\\z")) {
              String regex =
                  "(?:"
                      + operand
                      + "){"
                      + minimum
                      + (maximum == minimum ? "" : "," + (maximum < 0 ? "" : maximum))
                      + "}"
                      + mode
                      + suffix;
              Pattern optimized = Pattern.compile(regex, 0, engineOptions(true));
              Pattern exact = Pattern.compile(regex, 0, engineOptions(false));
              for (String input : inputs) {
                Matcher actual = optimized.matcher(input);
                Matcher expected = exact.matcher(input);
                boolean matches = expected.matches();
                assertThat(actual.matches()).as("/%s/ on %s", regex, input).isEqualTo(matches);
                Utf8Input bytes = Utf8Input.trusted(input.getBytes(UTF_8));
                Utf8Matcher actualBytes = optimized.matcher(bytes);
                Utf8Matcher expectedBytes = exact.matcher(bytes);
                assertThat(expectedBytes.matches()).isEqualTo(matches);
                assertThat(actualBytes.matches())
                    .as("UTF-8 /%s/ on %s", regex, input)
                    .isEqualTo(matches);
                if (matches) {
                  assertGroups(actual, expected);
                  for (int group = 0; group <= actualBytes.groupCount(); group++) {
                    assertThat(actualBytes.start(group)).isEqualTo(expectedBytes.start(group));
                    assertThat(actualBytes.end(group)).isEqualTo(expectedBytes.end(group));
                  }
                }
                assertThat(actual.find()).isEqualTo(expected.find());
                if (actual.hasMatch()) {
                  assertGroups(actual, expected);
                }
              }
            }
          }
        }
      }
    }
  }

  @Test
  void deferredFullMatchCapturesKeepTheirPolicyAcrossRegionsAndSnapshots() {
    for (String regex : List.of("(?:()|a){0,3}b", "(?:()|a){2,3}b", "(a?)*b")) {
      for (boolean region : List.of(false, true)) {
        Pattern optimized = Pattern.compile(regex, 0, engineOptions(true));
        Pattern exact = Pattern.compile(regex, 0, engineOptions(false));
        String prefix = region ? "😀#" : "";
        String input = prefix + "aab" + (region ? "!" : "");
        Matcher actual = optimized.matcher(input);
        Matcher expected = exact.matcher(input);
        Utf8Matcher actualBytes = optimized.matcher(Utf8Input.trusted(input.getBytes(UTF_8)));
        Utf8Matcher expectedBytes = exact.matcher(Utf8Input.trusted(input.getBytes(UTF_8)));
        if (region) {
          actual.region(prefix.length(), prefix.length() + 3);
          expected.region(prefix.length(), prefix.length() + 3);
          actualBytes.region(prefix.getBytes(UTF_8).length, prefix.getBytes(UTF_8).length + 3);
          expectedBytes.region(prefix.getBytes(UTF_8).length, prefix.getBytes(UTF_8).length + 3);
        }
        assertThat(actual.matches()).isTrue();
        assertThat(expected.matches()).isTrue();
        assertThat(actualBytes.matches()).isTrue();
        assertThat(expectedBytes.matches()).isTrue();
        MatchResult snapshot = actual.toMatchResult();
        assertGroups(actual, expected);
        for (int group = 0; group <= actualBytes.groupCount(); group++) {
          assertThat(actualBytes.start(group)).isEqualTo(expectedBytes.start(group));
          assertThat(actualBytes.end(group)).isEqualTo(expectedBytes.end(group));
        }
        actual.reset("reject");
        assertThat(actual.matches()).isFalse();
        assertThatThrownBy(() -> actual.group(1)).isInstanceOf(IllegalStateException.class);
        assertThat(snapshot.group()).isEqualTo("aab");
        assertThat(snapshot.start(1)).isEqualTo(expected.start(1));
        assertThat(snapshot.end(1)).isEqualTo(expected.end(1));
      }
    }
  }

  @Test
  void deferredCapturesCannotShortenFullMatchesBeforeFinalTerminators() {
    for (String terminator : List.of("\n", "\r\n", "\r", "\u0085", "\u2028", "\u2029")) {
      for (int flags : List.of(0, Pattern.UNIX_LINES)) {
        String regex = "^(a?)*((?:" + Pattern.quote(terminator) + ")??)$";
        for (boolean region : List.of(false, true)) {
          String prefix = region ? "😀#" : "";
          String input = prefix + "aa" + terminator + (region ? "!" : "");
          Pattern pattern = Pattern.compile(regex, flags);
          Matcher matcher = pattern.matcher(input);
          Utf8Matcher bytes =
              Pattern.compile(regex, flags).matcher(Utf8Input.trusted(input.getBytes(UTF_8)));
          if (region) {
            matcher.region(prefix.length(), prefix.length() + 2 + terminator.length());
            bytes.region(
                prefix.getBytes(UTF_8).length,
                prefix.getBytes(UTF_8).length + 2 + terminator.getBytes(UTF_8).length);
          }
          assertThat(matcher.matches()).as(regex).isTrue();
          int end = matcher.end();
          assertThat(matcher.group(2)).isEqualTo(terminator);
          assertThat(matcher.end()).isEqualTo(end);
          assertThat(matcher.toMatchResult().group()).isEqualTo("aa" + terminator);
          assertThat(bytes.matches()).isTrue();
          int byteEnd = bytes.end();
          assertThat(bytes.start(2)).isEqualTo(prefix.getBytes(UTF_8).length + 2);
          assertThat(bytes.end(2)).isEqualTo(byteEnd);
          assertThat(bytes.end()).isEqualTo(byteEnd);
        }
      }
    }
  }

  private static EnginePathOptions engineOptions(boolean dfa) {
    return EnginePathOptions.builder()
        .literalFastPaths(false)
        .charClassMatchFastPaths(false)
        .keywordAlternationFastPath(false)
        .startAcceleration(false)
        .shiftDfa(false)
        .onePass(false)
        .dfa(dfa)
        .reverseDfa(false)
        .bitState(false)
        .lazyCaptureExtraction(false)
        .build();
  }

  private static void assertGroups(Matcher actual, Matcher expected) {
    for (int group = 0; group <= expected.groupCount(); group++) {
      assertThat(actual.start(group)).isEqualTo(expected.start(group));
      assertThat(actual.end(group)).isEqualTo(expected.end(group));
      assertThat(actual.group(group)).isEqualTo(expected.group(group));
    }
  }
}
