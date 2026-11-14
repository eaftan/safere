// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Tests for {@link Prog}. */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class ProgTest {

  @Test
  void greedyAndLazyLoopMetadataCoexistRegardlessOfFirstAccess() {
    for (boolean greedyFirst : List.of(false, true)) {
      Prog prog = Pattern.compile("([a-z]*)(.*?)(z)").prog();
      if (greedyFirst) {
        assertThat(prog.greedyRunes()).isNotEmpty();
      } else {
        assertThat(prog.lazyRuneLoops()).isNotEmpty();
      }
      Inst[] greedy = prog.greedyRunes();
      LazyRuneLoop[] lazy = prog.lazyRuneLoops();
      assertThat(greedy).isNotEmpty();
      assertThat(lazy).isNotEmpty();
      for (int id = 0; id < prog.size(); id++) {
        if (greedy[id] != null) {
          assertThat(lazy[id]).isNull();
        }
      }
      assertThat(prog.greedyRunes()).isSameAs(greedy);
      assertThat(prog.lazyRuneLoops()).isSameAs(lazy);
      Prog copy = new Prog(prog);
      copy.freeze();
      assertThat(copy.greedyRunes()).isNotSameAs(greedy).isNotEmpty();
      assertThat(copy.lazyRuneLoops()).isNotSameAs(lazy).isNotEmpty();
    }
  }

  @Test
  void greedyRuneMetadataRequiresAnImmediatePreferredConsumingCycle() {
    for (String regex : List.of("a+", "[a-z]*b", "[é😀]+")) {
      Prog prog = Pattern.compile(regex).prog();
      Inst[] runes = prog.greedyRunes();
      assertThat(runes).as(regex).isNotNull();
      assertThat(prog.greedyRunes()).isSameAs(runes);
    }
    for (String regex : List.of("a+?", "(a)+", "(a?)*", "abc")) {
      assertThat(Pattern.compile(regex).prog().greedyRunes()).as(regex).isNull();
    }
  }

  @Test
  void bitStateAnalysisIsSharedRegardlessOfFirstRequestedMetadata() {
    for (boolean fusionFirst : new boolean[] {false, true}) {
      Prog prog = Pattern.compile("(a)b(c)").prog();
      CaptureRune[] captures;
      boolean[] cycleAlts;
      if (fusionFirst) {
        captures = prog.captureRunes();
        cycleAlts = prog.epsilonCycleAlts();
      } else {
        cycleAlts = prog.epsilonCycleAlts();
        captures = prog.captureRunes();
      }

      assertThat(captures).isNotEmpty();
      assertThat(prog.captureRunes()).isSameAs(captures);
      assertThat(prog.epsilonCycleAlts()).isSameAs(cycleAlts);
    }
  }

  @Test
  void bitStateAnalysisCachesTheAbsenceOfFusion() {
    Prog prog = Pattern.compile("a+").prog();
    boolean[] cycleAlts = prog.epsilonCycleAlts();

    assertThat(prog.captureRunes()).isNull();
    assertThat(prog.captureRunes()).isNull();
    assertThat(prog.epsilonCycleAlts()).isSameAs(cycleAlts);
  }

  @Test
  void copiedProgramsComputeTheirOwnBitStateAnalysis() {
    Prog original = Pattern.compile("(a)b(c)").prog();
    CaptureRune[] originalCaptures = original.captureRunes();
    boolean[] originalCycleAlts = original.epsilonCycleAlts();
    Prog copy = new Prog(original);
    copy.freeze();

    assertThat(copy.captureRunes()).isNotNull().isNotSameAs(originalCaptures);
    assertThat(copy.epsilonCycleAlts()).isNotSameAs(originalCycleAlts);
  }

  @Test
  void concurrentFirstUsePublishesCompleteBitStateAnalysis() throws Exception {
    try (var executor = Executors.newFixedThreadPool(2)) {
      for (int iteration = 0; iteration < 8; iteration++) {
        Prog prog = Pattern.compile("(a)b(c)").prog();
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<int[]> search =
            () -> {
              start.await();
              return BitState.search(prog, "abc", true, false, true, prog.numCaptures());
            };
        List<Future<int[]>> results = executor.invokeAll(List.of(search, search));
        for (Future<int[]> result : results) {
          assertThat(result.get()).containsExactly(0, 3, 0, 1, 2, 3);
        }
      }
    }
  }

  @Test
  void epsilonCyclesAfterGraphemeConsumptionAreReachable() {
    Prog prog = new Prog();
    for (int i = 0; i < 5; i++) {
      prog.allocInst();
    }
    prog.mutableInst(0).initFail();
    prog.mutableInst(1).initGraphemeCluster(2);
    prog.mutableInst(2).initAlt(3, 4);
    prog.mutableInst(3).initNop(2);
    prog.mutableInst(4).initMatch(0);
    prog.setStart(1);
    prog.setStartUnanchored(1);
    prog.freeze();

    assertThat(prog.epsilonCycleAlts()).containsExactly(false, false, true, false, false);
  }

  @Test
  void graphemeConsumptionBreaksEpsilonCycles() {
    Prog prog = new Prog();
    for (int i = 0; i < 4; i++) {
      prog.allocInst();
    }
    prog.mutableInst(0).initFail();
    prog.mutableInst(1).initAlt(2, 3);
    prog.mutableInst(2).initGraphemeCluster(1);
    prog.mutableInst(3).initMatch(0);
    prog.setStart(1);
    prog.setStartUnanchored(1);
    prog.freeze();

    assertThat(prog.epsilonCycleAlts()).containsExactly(false, false, false, false);
  }

  @Test
  void emptyProgram() {
    Prog prog = new Prog();
    assertThat(prog.size()).isEqualTo(0);
  }

  @Test
  void allocInst_growsAndReturnsIndex() {
    Prog prog = new Prog();
    int i0 = prog.allocInst();
    int i1 = prog.allocInst();
    int i2 = prog.allocInst();
    assertThat(i0).isEqualTo(0);
    assertThat(i1).isEqualTo(1);
    assertThat(i2).isEqualTo(2);
    assertThat(prog.size()).isEqualTo(3);
  }

  @Test
  void instReturnsCorrectInstruction() {
    Prog prog = new Prog();
    int idx = prog.allocInst();
    prog.mutableInst(idx).initMatch(42);
    prog.freeze();
    assertThat(prog.inst(idx).op).isEqualTo(InstOp.MATCH);
    assertThat(prog.inst(idx).arg).isEqualTo(42);
  }

  @Test
  void startAndStartUnanchored() {
    Prog prog = new Prog();
    prog.allocInst();
    prog.allocInst();
    prog.setStart(0);
    prog.setStartUnanchored(1);
    assertThat(prog.start()).isEqualTo(0);
    assertThat(prog.startUnanchored()).isEqualTo(1);
  }

  @Test
  void numCaptures() {
    Prog prog = new Prog();
    prog.setNumCaptures(3);
    assertThat(prog.numCaptures()).isEqualTo(3);
  }

  @Test
  void anchorFlags() {
    Prog prog = new Prog();
    prog.setAnchorStart(true);
    prog.setAnchorEnd(true);
    assertThat(prog.anchorStart()).isTrue();
    assertThat(prog.anchorEnd()).isTrue();
  }

  @Test
  void dump_formatsInstructions() {
    Prog prog = new Prog();
    prog.setStart(0);
    int i0 = prog.allocInst();
    int i1 = prog.allocInst();
    prog.mutableInst(i0).initCharRange('a', 'a', false, 1);
    prog.mutableInst(i1).initMatch(0);
    prog.freeze();

    String dump = prog.dump();
    assertThat(dump).contains("char");
    assertThat(dump).contains("match");
  }

  @Test
  void toStringShowsSummary() {
    Prog prog = new Prog();
    prog.allocInst();
    prog.allocInst();
    prog.setStart(0);
    prog.setNumCaptures(1);
    String s = prog.toString();
    assertThat(s).contains("size=2");
    assertThat(s).contains("captures=1");
  }
}
