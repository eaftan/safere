// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Tests for the optimized no-match path in {@code Matcher.replaceAll} and {@code replaceFirst}.
 *
 * <p>Pins the contract that when an input does not match, replace operations:
 *
 * <ul>
 *   <li>Return the exact same String instance, without constructing the replacement template.
 *   <li>Do not evaluate or throw on replacement templates containing references or syntax errors.
 *   <li>Leave the Matcher in the expected observable state (throwing {@link IllegalStateException}
 *       on {@code group()}, {@code start()}, etc.).
 *   <li>Emit exactly one operation event with start-acceleration diagnostics and no nested find
 *       events.
 *   <li>Produce results strictly identical to {@link java.util.regex.Matcher}.
 * </ul>
 */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class ReplaceNoMatchOverheadTest {

  private static final String PAREN_SOURCE =
      " ?\\([Ss]ource:\\s*\\d+(?:\\s*,\\s*(?:[Ss]ource:\\s*)?\\d+)*\\s*\\)";
  private static final String BRACKET_SOURCE =
      " ?\\[[Ss]ource:\\s*\\d+(?:\\s*,\\s*(?:[Ss]ource:\\s*)?\\d+)*\\s*\\]";
  private static final String BRACKET_TAG =
      "\\s?(?i)\\[(?:editor note|reviewer comment|margin note)\\]";

  private final RecordingDiagnostics diagnostics = new RecordingDiagnostics();

  @AfterEach
  void disableDiagnostics() {
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
  }

  @Test
  void noMatchReturnsSameStringInstance() {
    List<String> patterns =
        List.of(
            PAREN_SOURCE,
            BRACKET_SOURCE,
            BRACKET_TAG,
            "exactLiteralNeedle",
            "[0-9]+",
            "a+b",
            "^(?:foo|bar)$");
    String noMatchInput = "The quick brown fox jumps over the lazy dog in normal prose.";

    List<String> replacements = List.of("", "replacement", "$1", "${foo}", "\\n", "\\$", "\\\\");

    for (String patternStr : patterns) {
      Pattern pattern = Pattern.compile(patternStr);
      for (String rep : replacements) {
        Matcher matcherAll = pattern.matcher(noMatchInput);
        String resultAll = matcherAll.replaceAll(rep);
        assertThat(resultAll)
            .as("replaceAll on %s with %s", patternStr, rep)
            .isSameAs(noMatchInput);

        Matcher matcherFirst = pattern.matcher(noMatchInput);
        String resultFirst = matcherFirst.replaceFirst(rep);
        assertThat(resultFirst)
            .as("replaceFirst on %s with %s", patternStr, rep)
            .isSameAs(noMatchInput);
      }
    }
  }

  @Test
  void invalidReplacementTemplateDoesNotThrowOnNoMatch() {
    List<String> invalidReplacements = List.of("\\", "$", "$99", "${invalid", "$<unclosed>");
    String input = "No match here at all.";

    for (String regex : List.of(PAREN_SOURCE, BRACKET_SOURCE, BRACKET_TAG, "needle", "[0-9]+")) {
      Pattern safere = Pattern.compile(regex);
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);

      for (String rep : invalidReplacements) {
        // SafeRE must not throw and must return same instance
        assertThat(safere.matcher(input).replaceAll(rep)).isSameAs(input);
        assertThat(safere.matcher(input).replaceFirst(rep)).isSameAs(input);

        // JDK also does not throw on no-match
        assertThat(safere.matcher(input).replaceAll(rep))
            .isEqualTo(jdk.matcher(input).replaceAll(rep));
        assertThat(safere.matcher(input).replaceFirst(rep))
            .isEqualTo(jdk.matcher(input).replaceFirst(rep));
      }
    }
  }

  @Test
  void invalidReplacementTemplateThrowsOnMatchIdenticalToJdk() {
    String input = "prefix (Source: 42) suffix";
    Pattern safere = Pattern.compile(PAREN_SOURCE);
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(PAREN_SOURCE);

    // Trailing backslash
    assertThatThrownBy(() -> safere.matcher(input).replaceAll("\\"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> jdk.matcher(input).replaceAll("\\"))
        .isInstanceOf(IllegalArgumentException.class);

    // Lone dollar sign
    assertThatThrownBy(() -> safere.matcher(input).replaceAll("$"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> jdk.matcher(input).replaceAll("$"))
        .isInstanceOf(IllegalArgumentException.class);

    // Out-of-bounds group reference
    assertThatThrownBy(() -> safere.matcher(input).replaceAll("$99"))
        .isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> jdk.matcher(input).replaceAll("$99"))
        .isInstanceOf(IndexOutOfBoundsException.class);
  }

  @Test
  void nullReplacementThrowsNullPointerExceptionEvenOnNoMatch() {
    Pattern pattern = Pattern.compile(PAREN_SOURCE);
    Matcher matcher = pattern.matcher("no match");

    assertThatThrownBy(() -> matcher.replaceAll((String) null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> matcher.replaceFirst((String) null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void matcherStateAfterNoMatchThrowsIllegalStateException() {
    for (String regex : List.of(PAREN_SOURCE, "literalMatch", "[0-9]+")) {
      Pattern pattern = Pattern.compile(regex);
      Matcher matcher = pattern.matcher("no matching text here");

      matcher.replaceAll("X");
      assertThatThrownBy(matcher::group).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(matcher::start).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(matcher::end).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> matcher.group(0)).isInstanceOf(IllegalStateException.class);

      matcher.reset("no matching text here");
      matcher.replaceFirst("X");
      assertThatThrownBy(matcher::group).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(matcher::start).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(matcher::end).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> matcher.group(0)).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void matcherStateAfterMatchMatchesJdkContract() {
    Pattern pattern = Pattern.compile(PAREN_SOURCE);
    String input = "first (Source: 1) second (Source: 2) end";

    // replaceFirst leaves MATCHED
    Matcher matcherFirst = pattern.matcher(input);
    String resFirst = matcherFirst.replaceFirst("X");
    assertThat(resFirst).isEqualTo("firstX second (Source: 2) end");
    assertThat(matcherFirst.group()).isEqualTo(" (Source: 1)");
    assertThat(matcherFirst.start()).isEqualTo(5);
    assertThat(matcherFirst.end()).isEqualTo(17);

    // replaceAll leaves no match
    Matcher matcherAll = pattern.matcher(input);
    String resAll = matcherAll.replaceAll("X");
    assertThat(resAll).isEqualTo("firstX secondX end");
    assertThatThrownBy(matcherAll::group).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void differentialCheckAgainstJdk() {
    List<String> patterns =
        List.of(
            PAREN_SOURCE,
            BRACKET_SOURCE,
            BRACKET_TAG,
            "fox",
            "[0-9]+",
            "([a-z]+):([0-9]+)",
            "^(?:start|begin)",
            "(?m)^line");

    List<String> inputs =
        List.of(
            "",
            "The quick brown fox jumps over the lazy dog.",
            "test (Source: 123) and (Source: 456, 789) end",
            "[Source: 99] beginning only",
            " [editor note] here",
            "items: 123, 456, 789",
            "line1\nline2\nline3",
            "foo:10 bar:20 baz:30");

    List<String> replacements = List.of("", "REPLACED", "[$0]", "-");

    for (String pat : patterns) {
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pat);
      Pattern safere = Pattern.compile(pat);

      for (String in : inputs) {
        for (String rep : replacements) {
          assertThat(safere.matcher(in).replaceAll(rep))
              .as("replaceAll %s on '%s' with '%s'", pat, in, rep)
              .isEqualTo(jdk.matcher(in).replaceAll(rep));

          assertThat(safere.matcher(in).replaceFirst(rep))
              .as("replaceFirst %s on '%s' with '%s'", pat, in, rep)
              .isEqualTo(jdk.matcher(in).replaceFirst(rep));
        }
      }
    }
  }

  @Test
  void diagnosticsEmitsSingleEventWithStartAccelerationAndNoNestedFind() {
    Pattern.setDiagnostics(diagnostics);
    Pattern pattern = Pattern.compile(PAREN_SOURCE);

    // 1. No match replaceAll
    String noMatchInput = "The quick brown fox jumps over the lazy dog without any source.";
    String result = pattern.matcher(noMatchInput).replaceAll("");
    assertThat(result).isSameAs(noMatchInput);

    List<OperationDiagnostics> ops = operationsFor(pattern);
    assertThat(ops).hasSize(1);
    OperationDiagnostics op = ops.get(0);
    assertThat(op.operation()).isEqualTo(MatchOperation.REPLACE_ALL);
    assertThat(op.matchCount()).isEqualTo(0);
    assertThat(op.boundaryStrategy()).isEqualTo(MatchStrategy.LITERAL);
    assertThat(op.auxiliaryStrategies())
        .contains(
            new StrategyParticipation(MatchStrategy.LITERAL, StrategyRole.START_ACCELERATION));

    // 2. Matching replaceAll
    diagnostics.operations.clear();
    String matchInput = "citation (Source: 123) and more text";
    String matchResult = pattern.matcher(matchInput).replaceAll("");
    assertThat(matchResult).isEqualTo("citation and more text");

    ops = operationsFor(pattern);
    assertThat(ops).hasSize(1);
    op = ops.get(0);
    assertThat(op.operation()).isEqualTo(MatchOperation.REPLACE_ALL);
    assertThat(op.matchCount()).isEqualTo(1);
    // Boundary strategy was DFA
    assertThat(op.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
  }

  private List<OperationDiagnostics> operationsFor(Pattern pattern) {
    long patternId = pattern.descriptor().patternId();
    return diagnostics.operations.stream()
        .filter(event -> event.pattern().patternId() == patternId)
        .toList();
  }

  private static final class RecordingDiagnostics extends SafeReMatchDiagnostics {
    final List<PatternCompiledEvent> compilations = new ArrayList<>();
    final List<OperationDiagnostics> operations = new ArrayList<>();

    @Override
    public void onPatternCompiled(PatternCompiledEvent event) {
      compilations.add(event);
    }

    @Override
    public void onOperationCompleted(OperationDiagnostics event) {
      operations.add(event);
    }
  }
}
