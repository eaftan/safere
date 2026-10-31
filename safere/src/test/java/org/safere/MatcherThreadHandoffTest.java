// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests that a {@link Matcher} handed off to another thread keeps finding correct matches while
 * other Matchers of the same {@link Pattern} search on the first thread.
 */
@DisabledForCrosscheck("forces SafeRE engine paths through package-private options")
class MatcherThreadHandoffTest {

  private static final String INPUT = "ab12 cd345 efg6 hi78 jklm9 no0 pqr12345 st6";
  private static final List<String> EXPECTED =
      List.of("ab/12", "cd/345", "efg/6", "hi/78", "jklm/9", "no/0", "pqr/12345", "st/6");

  /**
   * The worker continues a find-all loop the main thread started, while the main thread runs
   * find-all loops on new matchers of the same pattern. Shared engines would corrupt the results.
   */
  @ParameterizedTest
  @ValueSource(strings = {"dfa", "bitState", "nfa"})
  void handedOffMatcherFindsCorrectlyWhileTheFirstThreadKeepsUsingThePattern(String engine)
      throws Exception {
    EnginePathOptions options =
        switch (engine) {
          case "dfa" -> EnginePathOptions.builder().onePass(false).build();
          case "bitState" -> EnginePathOptions.builder().dfa(false).onePass(false).build();
          default -> EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build();
        };
    Pattern pattern = Pattern.compile("([a-z]+)([0-9]+)", 0, options);
    Matcher transferred = pattern.matcher(INPUT);
    assertThat(transferred.find()).isTrue();
    assertThat(transferred.group(1) + "/" + transferred.group(2)).isEqualTo("ab/12");

    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
      Future<List<List<String>>> handedOff =
          worker.submit(
              () -> {
                start.await();
                List<List<String>> rounds = new ArrayList<>();
                for (int round = 0; round < 500; round++) {
                  List<String> found = new ArrayList<>();
                  while (transferred.find()) {
                    found.add(transferred.group(1) + "/" + transferred.group(2));
                  }
                  rounds.add(found);
                  transferred.reset();
                }
                return rounds;
              });
      start.countDown();
      for (int round = 0; round < 500; round++) {
        assertThat(findAll(pattern)).as("main thread, round %d", round).isEqualTo(EXPECTED);
      }
      List<List<String>> rounds = handedOff.get();
      assertThat(rounds.get(0)).as("worker, first round").isEqualTo(EXPECTED.subList(1, 8));
      assertThat(rounds.subList(1, rounds.size()))
          .as("worker, later rounds")
          .containsOnly(EXPECTED);
    }
  }

  private static List<String> findAll(Pattern pattern) {
    Matcher m = pattern.matcher(INPUT);
    List<String> found = new ArrayList<>();
    while (m.find()) {
      found.add(m.group(1) + "/" + m.group(2));
    }
    return found;
  }
}
