// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;

import java.util.ConcurrentModificationException;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests when a {@link Matcher} gives its engines back to the {@link Pattern}. The pattern runs on
 * BitState only, so an idle bundle shows as a BitState that {@link Pattern#borrowBitState()} can
 * take. Isolated because one test installs a process-wide diagnostics listener.
 */
@Isolated
@DisabledForCrosscheck("asserts the package-private idle engines of a pattern")
class MatcherEngineReleaseTest {

  private static Pattern bitStatePattern() {
    return Pattern.compile(
        "([a-z]+)([0-9]+)", 0, EnginePathOptions.builder().dfa(false).onePass(false).build());
  }

  static Stream<Named<Consumer<Pattern>>> operationsThatEndASearch() {
    return Stream.of(
        op("find() that matches", p -> p.matcher("abc123 xyz789").find()),
        op(
            "find() until it fails",
            p -> {
              Matcher m = p.matcher("abc123 xyz789");
              while (m.find()) {}
            }),
        op("find(int) that fails", p -> p.matcher("abc123 !!!!!!").find(7)),
        op("matches()", p -> p.matcher("abc123").matches()),
        op("lookingAt()", p -> p.matcher("abc123 xyz").lookingAt()),
        op("replaceAll(String)", p -> p.matcher("abc123 xyz789").replaceAll("$2")),
        op("replaceFirst(String)", p -> p.matcher("abc123 xyz789").replaceFirst("$2")),
        op("replaceAll(Function)", p -> p.matcher("abc123 xyz789").replaceAll(r -> r.group(2))),
        op("replaceFirst(Function)", p -> p.matcher("abc123 xyz789").replaceFirst(r -> r.group(2))),
        op("split", p -> p.split("abc123 xyz789 tail")),
        op(
            "asPredicate() that matches",
            p -> assertThat(p.asPredicate().test("abc123 xyz789")).isTrue()));
  }

  private static Named<Consumer<Pattern>> op(String name, Consumer<Pattern> operation) {
    return named(name, operation);
  }

  @ParameterizedTest
  @MethodSource("operationsThatEndASearch")
  void operationReturnsTheEnginesToThePattern(Consumer<Pattern> operation) {
    Pattern pattern = bitStatePattern();
    operation.accept(pattern);
    assertThat(pattern.borrowBitState()).isNotNull();
  }

  /**
   * With the DFA on, {@code matches()} defers the captures and never builds a BitState, so an idle
   * BitState afterwards comes from the bundle that {@code group(1)} took to resolve them.
   */
  @Test
  void groupReturnsTheEnginesItTakesToResolveCaptures() {
    Pattern pattern =
        Pattern.compile("([a-z]+)([0-9]+)", 0, EnginePathOptions.builder().onePass(false).build());
    Matcher m = pattern.matcher("abc123");
    assertThat(m.matches()).isTrue();
    assertThat(pattern.borrowBitState()).as("idle BitState before group(1)").isNull();
    assertThat(m.group(1)).isEqualTo("abc");
    assertThat(pattern.borrowBitState()).as("idle BitState after group(1)").isNotNull();
  }

  @Test
  void findReturnsTheEnginesWhenTheDiagnosticsListenerThrows() {
    Pattern pattern = bitStatePattern();
    Matcher m = pattern.matcher("abc123 xyz789");
    Pattern.setDiagnostics(
        new SafeReMatchDiagnostics() {
          @Override
          public void onOperationCompleted(OperationDiagnostics event) {
            throw new IllegalStateException("listener failed");
          }
        });
    try {
      assertThatThrownBy(m::find).hasMessage("listener failed");
    } finally {
      Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
    }
    assertThat(pattern.borrowBitState()).as("idle BitState after the failed find()").isNotNull();

    pattern.acquireEngines();
    assertThat(m.find()).isTrue();
    assertThat(pattern.borrowBitState()).as("idle BitState after the next find()").isNotNull();
  }

  @Test
  void replaceKeepsTheEnginesUntilItReturns() {
    Pattern pattern = bitStatePattern();
    String replaced =
        pattern
            .matcher("abc123 xyz789")
            .replaceAll(
                r -> {
                  assertThat(pattern.borrowBitState())
                      .as("idle BitState inside replaceAll")
                      .isNull();
                  return r.group(2);
                });
    assertThat(replaced).isEqualTo("123 789");
  }

  /**
   * A matcher-free search runs while a replace holds a bundle, so the pattern holds two bundles at
   * once. With a single slot the bundle released last would be dropped; the stripes keep both, and
   * a third taker gets an empty one.
   */
  @Test
  void overlappingSearchesLeaveBothBundlesIdle() {
    Pattern pattern = bitStatePattern();
    String replaced =
        pattern
            .matcher("abc123 xyz789")
            .replaceFirst(
                r -> {
                  assertThat(pattern.matcher("abc123").matches()).isTrue();
                  return r.group(2);
                });
    assertThat(replaced).isEqualTo("123 xyz789");

    Pattern.Engines taken1 = pattern.acquireEngines();
    Pattern.Engines taken2 = pattern.acquireEngines();
    Pattern.Engines taken3 = pattern.acquireEngines();
    assertThat(taken1.bitState).as("first bundle taken").isNotNull();
    assertThat(taken2.bitState).as("second bundle taken").isNotNull().isNotSameAs(taken1.bitState);
    assertThat(taken3.bitState).as("third bundle taken").isNull();
  }

  @Test
  void usePatternDuringAReplaceReturnsTheEnginesToThePreviousPattern() {
    Pattern pattern = bitStatePattern();
    Matcher m = pattern.matcher("abc123 xyz789");
    assertThatThrownBy(
            () ->
                m.replaceFirst(
                    r -> {
                      m.usePattern(Pattern.compile("[a-z]+"));
                      return "";
                    }))
        .isInstanceOf(ConcurrentModificationException.class);
    assertThat(pattern.borrowBitState()).isNotNull();
  }
}
