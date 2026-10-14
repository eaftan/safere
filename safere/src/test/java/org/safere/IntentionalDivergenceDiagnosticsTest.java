// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.function.Consumer;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@DisabledForCrosscheck("intentional-divergence diagnostics are SafeRE-specific")
class IntentionalDivergenceDiagnosticsTest {
  @AfterEach
  void disableDiagnostics() {
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
  }

  @Test
  void exhaustedFindNotificationSeesFinalStateAndPropagatesFailure() {
    Matcher matcher = Pattern.compile("").matcher("");
    assertThat(matcher.find()).isTrue();
    RuntimeException failure = new IllegalStateException("listener failed");
    Pattern.setDiagnostics(
        listener(
            divergence -> {
              assertThat(divergence).isEqualTo(IntentionalDivergence.EXHAUSTED_EMPTY_MATCH);
              assertThat(matcher.hasMatch()).isFalse();
              assertThatThrownBy(matcher::group).isInstanceOf(IllegalStateException.class);
              throw failure;
            }));

    assertThatThrownBy(matcher::find).isSameAs(failure);
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
    assertThat(matcher.find()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("replacementCases")
  void replacementNotificationSeesFinalStateAcrossEngineAndApiPaths(
      String regex, String input, EnginePathOptions options, boolean functional, boolean first) {
    Pattern pattern = Pattern.compile(regex, 0, options);
    Matcher expected = pattern.matcher(input);
    replace(expected, functional, first);
    Matcher actual = pattern.matcher(input);
    RuntimeException failure = new IllegalStateException("listener failed");
    Pattern.setDiagnostics(
        listener(
            divergence -> {
              assertThat(divergence).isEqualTo(IntentionalDivergence.EXHAUSTED_EMPTY_MATCH);
              assertSameState(actual, expected);
              throw failure;
            }));

    assertThatThrownBy(() -> replace(actual, functional, first)).isSameAs(failure);
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
    assertSameState(actual, expected);
    assertThat(actual.find()).isEqualTo(expected.find());
  }

  private static Stream<Arguments> replacementCases() {
    EnginePathOptions defaults = EnginePathOptions.builder().build();
    EnginePathOptions dfa =
        EnginePathOptions.builder()
            .literalFastPaths(false)
            .charClassMatchFastPaths(false)
            .shiftDfa(false)
            .onePass(false)
            .build();
    return Stream.of(
        Arguments.of("^$", "", defaults, false, false),
        Arguments.of("^$", "", defaults, false, true),
        Arguments.of("[ab]*", "ab", defaults, false, false),
        Arguments.of("[ab]*", "ab", dfa, false, false),
        Arguments.of("[ab]*", "ab", defaults, true, false));
  }

  @Test
  void usePatternNotificationSeesInstalledPatternAndPreservedGroupZero() {
    Matcher matcher = Pattern.compile("a+").matcher("ab");
    assertThat(matcher.find()).isTrue();
    Pattern replacement = Pattern.compile("(b)");
    RuntimeException failure = new IllegalStateException("listener failed");
    Pattern.setDiagnostics(
        listener(
            divergence -> {
              assertThat(divergence).isEqualTo(IntentionalDivergence.USE_PATTERN_GROUP_ZERO);
              assertThat(matcher.pattern()).isSameAs(replacement);
              assertThat(matcher.groupCount()).isEqualTo(1);
              assertThat(matcher.group()).isEqualTo("a");
              assertThat(matcher.group(1)).isNull();
              throw failure;
            }));

    assertThatThrownBy(() -> matcher.usePattern(replacement)).isSameAs(failure);
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(1)).isEqualTo("b");
  }

  @ParameterizedTest
  @MethodSource("syntaxFailures")
  void failedParseKeepsSyntaxErrorAndSuppressesNotificationFailure(
      String regex, Throwable failure) {
    PatternSyntaxException expected =
        catchThrowableOfType(PatternSyntaxException.class, () -> Pattern.compile(regex));
    assertThat(expected).isNotNull();
    Pattern.setDiagnostics(
        listener(
            divergence -> {
              if (failure instanceof RuntimeException runtime) {
                throw runtime;
              }
              throw (Error) failure;
            }));

    assertThatThrownBy(() -> Pattern.compile(regex))
        .isInstanceOfSatisfying(
            PatternSyntaxException.class,
            actual -> {
              assertThat(actual.getPattern()).isEqualTo(expected.getPattern());
              assertThat(actual.getIndex()).isEqualTo(expected.getIndex());
              assertThat(actual.getDescription()).isEqualTo(expected.getDescription());
              assertThat(actual.getSuppressed()).containsExactly(failure);
            });
  }

  private static Stream<Arguments> syntaxFailures() {
    return Stream.of("[a&&&b]", "(?x)#\\Q\n[", "(?x)#\\Q\n[a&&&b]")
        .flatMap(
            regex ->
                Stream.of(
                    Arguments.of(regex, new IllegalStateException("listener failed")),
                    Arguments.of(regex, new AssertionError("listener failed"))));
  }

  @Test
  void successfulParsePropagatesNotificationFailure() {
    RuntimeException failure = new IllegalStateException("listener failed");
    Pattern.setDiagnostics(
        listener(
            divergence -> {
              assertThat(divergence).isEqualTo(IntentionalDivergence.COMMENT_QUOTING);
              throw failure;
            }));

    assertThatThrownBy(() -> Pattern.compile("(?x)#\\Q\n.")).isSameAs(failure);
  }

  private static SafeReMatchDiagnostics listener(Consumer<IntentionalDivergence> callback) {
    return new SafeReMatchDiagnostics() {
      @Override
      void onIntentionalDivergence(IntentionalDivergence divergence) {
        callback.accept(divergence);
      }
    };
  }

  private static void replace(Matcher matcher, boolean functional, boolean first) {
    if (functional) {
      matcher.replaceAll(match -> "-");
    } else if (first) {
      matcher.replaceFirst("-");
    } else {
      matcher.replaceAll("-");
    }
  }

  private static void assertSameState(Matcher actual, Matcher expected) {
    assertThat(actual.hasMatch()).isEqualTo(expected.hasMatch());
    if (expected.hasMatch()) {
      for (int group = 0; group <= expected.groupCount(); group++) {
        assertThat(actual.group(group)).isEqualTo(expected.group(group));
        assertThat(actual.start(group)).isEqualTo(expected.start(group));
        assertThat(actual.end(group)).isEqualTo(expected.end(group));
      }
    } else {
      assertThatThrownBy(actual::group).isInstanceOf(IllegalStateException.class);
    }
    assertThat(actual.appendTail(new StringBuilder()).toString())
        .isEqualTo(expected.appendTail(new StringBuilder()).toString());
  }
}
