// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class CapturePolicyExperimentTest {
  @Test
  void recordsEachRetiredSuccessOnceAfterRepeatedAndDelayedReads() {
    CapturePolicyExperiment policy = new CapturePolicyExperiment();
    policy.begin(128);
    policy.finish(true);
    policy.read(true);
    policy.read(false);
    assertThat(policy.snapshot().successes()).isZero();
    policy.retire();
    policy.retire();
    assertThat(policy.snapshot().successes()).isEqualTo(1);
    assertThat(policy.snapshot().reads()).isEqualTo(1);
    assertThat(policy.snapshot().replays()).isEqualTo(1);
  }

  @Test
  void terminalFailureDoesNotDiluteMeaningfulSearchHistory() {
    CapturePolicyExperiment policy = new CapturePolicyExperiment();
    policy.begin(128);
    policy.finish(true);
    policy.begin(0);
    policy.finish(false);
    assertThat(policy.snapshot().successes()).isEqualTo(1);
    assertThat(policy.snapshot().reads()).isZero();
    assertThat(policy.snapshot().attempts()).isEqualTo(1);
    assertThat(policy.snapshot().hits()).isEqualTo(1);
    assertThat(policy.snapshot().credit()).isEqualTo(-1);
    policy.begin(128);
    policy.finish(false);
    assertThat(policy.snapshot().attempts()).isEqualTo(2);
    assertThat(policy.snapshot().hits()).isEqualTo(1);
    assertThat(policy.snapshot().credit()).isEqualTo(-5);
  }

  @Test
  void anchoredPreferenceAndRepeatedDecisionsDoNotAddExtraAttempts() {
    CapturePolicyExperiment policy = new CapturePolicyExperiment();
    policy.begin(128);
    assertThat(policy.prefer(true, true)).isTrue();
    assertThat(policy.snapshot().choices()).isZero();
    boolean first = policy.prefer(false, false);
    assertThat(policy.prefer(true, false)).isEqualTo(first);
    assertThat(policy.snapshot().choices()).isEqualTo(1);
  }

  @Test
  void historiesMeansAndCreditsRemainBoundedUnderLongRuns() {
    CapturePolicyExperiment policy = new CapturePolicyExperiment();
    for (int i = 0; i < 100_000; i++) {
      policy.begin(128);
      policy.prefer(false, false);
      policy.work(Long.MAX_VALUE);
      policy.finish(true);
      if ((i & 3) == 0) {
        policy.read(true);
        policy.beginReplay();
        policy.work(Long.MAX_VALUE);
        policy.endReplay();
      }
    }
    policy.retire();
    CapturePolicyExperiment.Snapshot state = policy.snapshot();
    assertThat(state.successes()).isEqualTo(32);
    assertThat(state.reads()).isEqualTo(8);
    assertThat(state.attempts()).isEqualTo(32);
    assertThat(state.hits()).isEqualTo(32);
    assertThat(state.credit()).isBetween(-32, 32);
    assertThat(state.dfaMean()).isBetween(0, 1 << 20);
    assertThat(state.captureMean()).isBetween(0, 1 << 20);
    assertThat(state.dfaSamples()).isBetween(0, 4096);
    assertThat(state.captureSamples()).isBetween(0, 4096);
  }

  @Test
  void patternChangeDiscardsLearningAndUnrelatedReads() {
    CapturePolicyExperiment policy = new CapturePolicyExperiment();
    policy.begin(128);
    policy.finish(true);
    policy.read(true);
    policy.retire();
    policy.resetPattern();
    policy.read(true);
    policy.retire();
    assertThat(policy.snapshot().successes()).isZero();
    assertThat(policy.snapshot().reads()).isZero();
    assertThat(policy.snapshot().attempts()).isZero();
    assertThat(policy.snapshot().credit()).isZero();
  }

  @Test
  void localRandomStateMakesIdenticalHistoriesRepeatable() {
    CapturePolicyExperiment first = new CapturePolicyExperiment();
    CapturePolicyExperiment second = new CapturePolicyExperiment();
    for (int i = 0; i < 1024; i++) {
      first.begin(128);
      second.begin(128);
      assertThat(first.prefer(false, false)).isEqualTo(second.prefer(false, false));
      first.work(100 + i % 127);
      second.work(100 + i % 127);
      first.finish(true);
      second.finish(true);
      first.read(true);
      second.read(true);
    }
    assertThat(first.snapshot()).isEqualTo(second.snapshot());
  }

  @Test
  void consumptionFractionLearnsFrequentReadsAndForgetsUnusedSuccesses() {
    Assumptions.assumeTrue(
        CapturePolicyExperiment.MODE.equals("fraction")
            || CapturePolicyExperiment.MODE.equals("joint"));
    CapturePolicyExperiment policy = new CapturePolicyExperiment();
    for (int i = 0; i < 32; i++) {
      policy.begin(128);
      policy.finish(true);
      policy.read(false);
    }
    policy.begin(128);
    assertThat(policy.prefer(false, false)).isTrue();
    policy.finish(true);
    for (int i = 0; i < 32; i++) {
      policy.begin(128);
      policy.finish(true);
    }
    policy.begin(128);
    assertThat(policy.prefer(false, false)).isFalse();
    assertThat(policy.snapshot().reads()).isZero();
  }

  @Test
  void jointPolicyDistinguishesAllReadHitsFromFrequentRejections() {
    Assumptions.assumeTrue(CapturePolicyExperiment.MODE.equals("joint"));
    CapturePolicyExperiment policy = new CapturePolicyExperiment();
    for (int i = 0; i < 512; i++) {
      policy.begin(128);
      policy.finish((i & 15) == 0);
      if ((i & 15) == 0) {
        policy.read(true);
      }
    }
    policy.begin(128);
    assertThat(policy.snapshot().reads()).isEqualTo(32);
    assertThat(policy.snapshot().hits()).isEqualTo(2);
    assertThat(policy.prefer(false, false)).isFalse();
  }

  @Test
  void workLearningObservesBothProducersAndGreedyChoiceUsesTheLowerEstimate() {
    Assumptions.assumeTrue(CapturePolicyExperiment.MODE.equals("work"));
    CapturePolicyExperiment policy = new CapturePolicyExperiment();
    policy.begin(128);
    assertThat(policy.prefer(false, false)).isFalse();
    policy.work(100);
    policy.finish(true);
    policy.read(true);
    policy.begin(128);
    assertThat(policy.prefer(false, false)).isTrue();
    policy.work(10000);
    policy.finish(true);
    policy.read(false);
    policy.begin(128);
    assertThat(policy.prefer(false, false)).isFalse();
    assertThat(policy.snapshot().captureSamples()).isEqualTo(1);
    assertThat(policy.snapshot().dfaSamples()).isEqualTo(1);
  }
}
