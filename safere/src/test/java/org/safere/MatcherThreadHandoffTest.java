// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests that a {@link Matcher} handed off to another thread stops using engines from the previous
 * thread's per-thread cache, since that thread may keep using the same {@link Pattern}.
 */
@DisabledForCrosscheck("uses package-private reflection to assert SafeRE matcher internals")
class MatcherThreadHandoffTest {

  @Test
  void handoffUsesExecutingThreadsForwardAndReverseDfas() throws Exception {
    Pattern pattern = Pattern.compile("[a-q][^u-z]{3}x");
    Matcher transferred = pattern.matcher("zz abcdx zz bcdex");
    assertThat(transferred.find()).isTrue();
    Dfa originalForward = (Dfa) field("cachedForwardFirstMatchDfa").get(transferred);
    Dfa originalReverse = (Dfa) field("cachedReverseDfa").get(transferred);
    assertThat(originalForward).isNotNull().isSameAs(pattern.forwardFirstMatchDfa());
    assertThat(originalReverse).isNotNull().isSameAs(pattern.reverseDfa());

    Dfa[] worker =
        onWorker(
            () -> {
              assertThat(transferred.find()).isTrue();
              assertThat(transferred.group()).isEqualTo("bcdex");
              return new Dfa[] {
                (Dfa) field("cachedForwardFirstMatchDfa").get(transferred),
                (Dfa) field("cachedReverseDfa").get(transferred),
                pattern.forwardFirstMatchDfa(),
                pattern.reverseDfa(),
              };
            });
    assertThat(worker[0]).isNotSameAs(originalForward).isSameAs(worker[2]);
    assertThat(worker[1]).isNotSameAs(originalReverse).isSameAs(worker[3]);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void handoffWithoutResetUsesExecutingThreadsEngine(boolean bitState) throws Exception {
    Pattern pattern =
        Pattern.compile(
            "([a-z]+)([0-9]+)",
            0,
            EnginePathOptions.builder().dfa(false).onePass(false).bitState(bitState).build());
    Field engineField = field(bitState ? "cachedBitState" : "cachedNfa");
    Matcher transferred = pattern.matcher("abc123 xyz789");
    assertThat(transferred.find()).isTrue();
    Object originalEngine = engineField.get(transferred);
    assertThat(originalEngine).isNotNull();

    Object workerEngine =
        onWorker(
            () -> {
              assertThat(transferred.find()).isTrue();
              // Read the engine before group(), which can rebuild it for capture resolution.
              Object engine = engineField.get(transferred);
              assertThat(transferred.group(2)).isEqualTo("789");
              return engine;
            });
    assertThat(workerEngine).isNotNull().isNotSameAs(originalEngine);

    // The original thread keeps using its own cache, not the worker's engine.
    Matcher retained = pattern.matcher("abc123 xyz789");
    assertThat(retained.find()).isTrue();
    assertThat(engineField.get(retained)).isNotNull().isNotSameAs(workerEngine);
  }

  private static <T> T onWorker(Callable<T> task) throws Exception {
    try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
      return worker.submit(task).get();
    }
  }

  private static Field field(String name) throws ReflectiveOperationException {
    Field field = Matcher.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }
}
