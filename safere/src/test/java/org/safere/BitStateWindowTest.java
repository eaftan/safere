// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

/** Priority, context, and work bounds for speculative BitState searches. */
@DisabledForCrosscheck("uses package-private engine paths and search windows")
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class BitStateWindowTest {
  @Test
  void higherPriorityPathAtWindowEdgePreventsReturningShorterAlternative() {
    for (String regex : List.of("a+|a", "a+z|a", "(a?)*z|a")) {
      Prog prog = Pattern.compile(regex).prog();
      BitState bs = window(prog, new StringInputScanner("a".repeat(1000)), 4);
      assertThat(bs.doSearchWindow(0, 0, true, null)).as(regex).isNull();
      assertThat(bs.windowExceeded()).as(regex).isTrue();
      assertThat(bs.budgetExceeded()).as(regex).isFalse();
    }
  }

  @Test
  void lowerPriorityLongPathDoesNotPreventReturningPreferredMatch() {
    Prog prog = Pattern.compile("a|a+").prog();
    BitState bs = window(prog, new StringInputScanner("a".repeat(1000)), 4);
    assertThat(bs.doSearchWindow(0, 0, true, null)).containsExactly(0, 1);
    assertThat(bs.windowExceeded()).isFalse();
  }

  @Test
  void assertionsAtWindowEdgeStillObserveFullInput() {
    String text = "abcd";
    for (String regex : List.of("ab\\b", "(?:ab$|x)", "(?:ab\\z|x)")) {
      Prog prog = Pattern.compile(regex).prog();
      BitState bs = window(prog, new StringInputScanner(text), 2);
      assertThat(bs.doSearchWindow(0, 0, true, null)).as(regex).isNull();
      assertThat(bs.windowExceeded()).as(regex).isFalse();
    }
    Prog prog = Pattern.compile("ab\\B").prog();
    BitState bs = window(prog, new StringInputScanner(text), 2);
    assertThat(bs.doSearchWindow(0, 0, true, null)).containsExactly(0, 2);
  }

  @Test
  void codePointStraddlingWindowEdgeRequiresFallback() {
    Prog prog = Pattern.compile("a😀|a").prog();
    for (InputScanner input : scanners("a😀x")) {
      BitState bs = window(prog, input, 2);
      assertThat(bs.doSearchWindow(0, 0, true, null)).isNull();
      assertThat(bs.windowExceeded()).isTrue();
    }
  }

  @Test
  void reusedWindowClearsAbortedSearchState() {
    Prog prog = Pattern.compile("a+|a").prog();
    BitState bs = window(prog, new StringInputScanner("a".repeat(1000)), 4);
    assertThat(bs.doSearchWindow(0, 0, true, null)).isNull();
    assertThat(bs.windowExceeded()).isTrue();

    bs = BitState.getOrCreate(bs, prog, new StringInputScanner("a"), 0, 1, 2, false, false);
    assertThat(bs.doSearch(0, 0, true)).containsExactly(0, 1);
    assertThat(bs.windowExceeded()).isFalse();
    assertThat(bs.budgetExceeded()).isFalse();
  }

  @Test
  void longPreferredMatchAndAnchoredOperationsAgreeWithExactEngine() {
    String regex = "(a?)*b|a";
    Pattern fast = Pattern.compile(regex);
    Pattern exact = Pattern.compile(regex, 0, EnginePathOptions.builder().bitState(false).build());
    String text = "a".repeat(10_000) + "b";
    Matcher actual = fast.matcher(text);
    Matcher expected = exact.matcher(text);
    assertThat(actual.find()).isEqualTo(expected.find());
    assertThat(actual.end()).isEqualTo(expected.end());
    assertThat(actual.group(1)).isEqualTo(expected.group(1));
    assertThat(actual.reset().lookingAt()).isEqualTo(expected.reset().lookingAt());
    assertThat(actual.end()).isEqualTo(expected.end());
    assertThat(actual.reset().matches()).isEqualTo(expected.reset().matches());
    assertThat(actual.end()).isEqualTo(expected.end());
    assertThat(fast.matcher(text).replaceAll("X")).isEqualTo(exact.matcher(text).replaceAll("X"));
  }

  @Test
  void successfulWindowsPreserveExactCapturesAcrossPatternsAndInputRepresentations() {
    List<String> patterns =
        List.of(
            "a|a+",
            "a+|a",
            "a+?",
            "a+z|a",
            "(a?)*",
            "(a|)*",
            "(?:\\B|a)*",
            "((a)?b)*",
            "(a*)(b?)",
            "a\\b|a\\B",
            "(.)*?b",
            "(😀|a)*",
            "[^()]*\\([^()]*\\)",
            "((?:<[^>]*>|[^()]*|\\([^()]*\\))*)\\)");
    List<String> texts = List.of("", "a", "aaa", "aaabx", "ababx", "a😀b", "(ab)x", "<a>(b))x");
    for (String regex : patterns) {
      Prog prog = Pattern.compile(regex).prog();
      for (String text : texts) {
        for (InputScanner input : scanners(text)) {
          for (boolean anchored : List.of(false, true)) {
            int[] expected =
                Nfa.search(
                    prog,
                    input,
                    0,
                    input.length(),
                    input.length(),
                    0,
                    anchored ? Nfa.Anchor.ANCHORED : Nfa.Anchor.UNANCHORED,
                    Nfa.MatchKind.FIRST_MATCH,
                    prog.numCaptures(),
                    null);
            for (int end = 0; end <= input.length(); end++) {
              BitState bs = window(prog, input, end);
              int[] result = bs.doSearchWindow(0, input.length(), anchored, null);
              if (result != null) {
                assertThat(bs.windowExceeded()).isFalse();
                assertThat(result)
                    .as("/%s/ on %s, anchored=%s, window=%s", regex, text, anchored, end)
                    .isEqualTo(expected);
              }
            }
          }
        }
      }
    }
  }

  @Test
  void largeInputFindSequencesAndReplacementsAgreeWithExactEngine() {
    String regex = "\\[(.*?)]\\(customProtocol://((?:<[^>]*>|[^()]*|\\([^()]*\\))*)\\)";
    Pattern fast = Pattern.compile(regex);
    Pattern exact = Pattern.compile(regex, 0, EnginePathOptions.builder().bitState(false).build());
    for (String label : List.of("short", "α😀", "a".repeat(300))) {
      String text = ("[" + label + "](customProtocol://<doc_1>) ").repeat(400);
      for (String replacement : List.of("X", "$1:$2", "$0")) {
        assertThat(fast.matcher(text).replaceAll(replacement))
            .isEqualTo(exact.matcher(text).replaceAll(replacement));
      }
      Matcher actual = fast.matcher(text);
      Matcher expected = exact.matcher(text);
      while (expected.find()) {
        assertThat(actual.find()).isTrue();
        for (int group = 0; group <= expected.groupCount(); group++) {
          assertThat(actual.start(group)).isEqualTo(expected.start(group));
          assertThat(actual.end(group)).isEqualTo(expected.end(group));
        }
      }
      assertThat(actual.find()).isFalse();
      Utf8Input bytes = Utf8Input.trusted(text.getBytes(UTF_8));
      Utf8Matcher actualUtf8 = fast.matcher(bytes);
      Utf8Matcher expectedUtf8 = exact.matcher(bytes);
      while (expectedUtf8.find()) {
        assertThat(actualUtf8.find()).isTrue();
        for (int group = 0; group <= expectedUtf8.groupCount(); group++) {
          assertThat(actualUtf8.start(group)).isEqualTo(expectedUtf8.start(group));
          assertThat(actualUtf8.end(group)).isEqualTo(expectedUtf8.end(group));
        }
      }
      assertThat(actualUtf8.find()).isFalse();
    }
  }

  @Test
  void shortNullableLoopMatchInLargeInputUsesBitState() {
    Pattern pattern = Pattern.compile("((a)?b?)*c");
    String text = "abbc" + "x".repeat(100_000);
    SafeReMatchDiagnostics previous = Pattern.diagnostics();
    List<OperationDiagnostics> operations = new ArrayList<>();
    Pattern.setDiagnostics(
        new SafeReMatchDiagnostics() {
          @Override
          public void onOperationCompleted(OperationDiagnostics event) {
            operations.add(event);
          }
        });
    try {
      assertThat(pattern.matcher(text).find()).isTrue();
      assertThat(pattern.matcher(Utf8Input.trusted(text.getBytes(UTF_8))).find()).isTrue();
      assertThat(operations).hasSize(2);
      for (OperationDiagnostics operation : operations) {
        assertThat(operation.boundaryStrategy()).isEqualTo(MatchStrategy.BIT_STATE);
      }
    } finally {
      Pattern.setDiagnostics(previous);
    }
  }

  @Test
  @Tag("work-counter")
  void repeatedShortMatchesUseLinearWorkAcrossInputSizes() {
    for (String atom : List.of("a", "α", "😀")) {
      Pattern pattern = Pattern.compile("S((?:" + atom + "?)*)E");
      for (boolean utf8 : List.of(false, true)) {
        long previous = -1;
        for (int size : List.of(4_000, 8_000, 16_000)) {
          String text = ("S" + atom + atom + "E ").repeat(size);
          Matcher stringMatcher = pattern.matcher(text);
          Utf8Matcher utf8Matcher = pattern.matcher(Utf8Input.trusted(text.getBytes(UTF_8)));
          long work =
              WorkCounter.countForTesting(
                  () -> {
                    int matches = 0;
                    while (utf8 ? utf8Matcher.find() : stringMatcher.find()) {
                      matches++;
                    }
                    assertThat(matches).isEqualTo(size);
                  });
          if (previous >= 0) {
            assertThat(work).as("%s, UTF-8=%s", atom, utf8).isLessThan(previous * 3);
          }
          previous = work;
        }
      }
    }
  }

  @Test
  @Tag("work-counter")
  void speculationWorkIsIndependentOfUnvisitedInputLength() {
    Prog prog = Pattern.compile("(a?)*z|a").prog();
    long previous = -1;
    for (int size : List.of(10_000, 100_000, 1_000_000)) {
      BitState bs =
          window(prog, new StringInputScanner("a".repeat(size)), BitState.SPECULATIVE_WINDOW_SIZE);
      long work =
          WorkCounter.countForTesting(
              () -> assertThat(bs.doSearchWindow(0, 0, true, null)).isNull());
      assertThat(bs.windowExceeded()).isTrue();
      if (previous >= 0) {
        assertThat(work).isEqualTo(previous);
      }
      previous = work;
    }
  }

  private static BitState window(Prog prog, InputScanner input, int end) {
    return BitState.getOrCreate(null, prog, input, 0, end, 2 * prog.numCaptures(), false, false);
  }

  private static List<InputScanner> scanners(String text) {
    return List.of(
        new StringInputScanner(text),
        ((ArrayUtf8Input) Utf8Input.trusted(text.getBytes(UTF_8))).scanner());
  }
}
