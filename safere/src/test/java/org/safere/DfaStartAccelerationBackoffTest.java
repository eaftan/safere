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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Start-acceleration backoff carried across the searches of a {@code find()} sequence.
 *
 * <p>The accelerator for {@code [xz][0-9]+y} finds the next {@code x} or {@code z}. On input where
 * every {@code x} starts a match right where the previous match ended, every call skips nothing, so
 * the accelerator only adds overhead. A character class does not identify exact match starts, so
 * the matcher-level accelerator calls are charged too.
 */
@DisabledForCrosscheck("WorkCounter and acceleration policies are SafeRE implementation details")
@Tag("work-counter")
class DfaStartAccelerationBackoffTest {
  private static final String REGEX = "[xz][0-9]+y";
  private static final String DENSE_UNIT = "x1y";
  private static final int DENSE_COUNT = 30_000;
  private static final AcceleratorPolicy POLICY = AcceleratorPolicy.CHAR_CLASS;

  @Test
  void patternUsesACharacterClassAccelerator() {
    Pattern pattern = Pattern.compile(REGEX);
    assertThat(pattern.stringStartAccelerator().policy()).isEqualTo(POLICY);
    assertThat(pattern.utf8StartAccelerator().policy()).isEqualTo(POLICY);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void defeatStaysLinearAcrossFindCalls(boolean utf8) {
    String input = DENSE_UNIT.repeat(DENSE_COUNT);
    List<Integer> starts = new ArrayList<>();

    WorkCounter.StartAccelerationWork work =
        WorkCounter.countStartAccelerationForTesting(() -> findAll(input, utf8, starts));

    assertThat(starts).hasSize(DENSE_COUNT);
    assertThat(starts.get(DENSE_COUNT - 1)).isEqualTo((DENSE_COUNT - 1) * DENSE_UNIT.length());
    // Re-learning the defeat in every find() would make one call per match. Carrying the
    // backoff pays the loss limit once and then escalates the quarantine window.
    assertThat(work.calls()).as("%s", work).isLessThan(DENSE_COUNT / 100L);
    assertThat(work.largestQuarantine()).as("%s", work).isEqualTo(POLICY.maxQuarantineWindow());
  }

  @Test
  void defeatStaysLinearAcrossReplaceAllSearches() {
    // A character-class accelerator has no literal prefix, so replaceAll searches with the DFA's
    // own start acceleration on every match.
    Pattern pattern = Pattern.compile(REGEX);
    String input = DENSE_UNIT.repeat(DENSE_COUNT);
    String[] result = new String[1];

    WorkCounter.StartAccelerationWork work =
        WorkCounter.countStartAccelerationForTesting(
            () -> result[0] = pattern.matcher(input).replaceAll("-"));

    assertThat(result[0]).isEqualTo("-".repeat(DENSE_COUNT));
    assertThat(work.calls()).as("%s", work).isLessThan(DENSE_COUNT / 100L);
  }

  @Test
  void defeatStaysLinearAcrossSplitSearches() {
    Pattern pattern = Pattern.compile(REGEX);
    String input = (DENSE_UNIT + ",").repeat(DENSE_COUNT);
    String[][] result = new String[1][];

    WorkCounter.StartAccelerationWork work =
        WorkCounter.countStartAccelerationForTesting(() -> result[0] = pattern.split(input));

    assertThat(result[0]).hasSize(DENSE_COUNT + 1).containsOnly("", ",");
    assertThat(work.calls()).as("%s", work).isLessThan(DENSE_COUNT / 100L);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sparseInputRegainsAccelerationAfterDenseInput(boolean utf8) {
    String dense = DENSE_UNIT.repeat(DENSE_COUNT);
    String sparseUnit = "-".repeat(1_000) + DENSE_UNIT;
    int sparseCount = 1_024;
    String sparse = sparseUnit.repeat(sparseCount);
    Matcher matcher = Pattern.compile(REGEX).matcher(dense + sparse);
    Utf8Matcher utf8Matcher =
        Pattern.compile(REGEX).matcher(Utf8Input.validated((dense + sparse).getBytes(UTF_8)));

    WorkCounter.StartAccelerationWork denseWork =
        WorkCounter.countStartAccelerationForTesting(
            () -> {
              for (int i = 0; i < DENSE_COUNT; i++) {
                assertThat(utf8 ? utf8Matcher.find() : matcher.find()).isTrue();
              }
            });
    WorkCounter.StartAccelerationWork sparseWork =
        WorkCounter.countStartAccelerationForTesting(
            () -> {
              for (int i = 0; i < sparseCount; i++) {
                assertThat(utf8 ? utf8Matcher.find() : matcher.find()).isTrue();
              }
              assertThat(utf8 ? utf8Matcher.find() : matcher.find()).isFalse();
            });

    assertThat(denseWork.largestQuarantine()).isEqualTo(POLICY.maxQuarantineWindow());
    // At most one maximal quarantine window, carried over from the dense input, runs without
    // acceleration. After that, the first long skip repays the deficit and every later call skips
    // the whole gap.
    assertThat(sparseWork.skippedUnits())
        .as("%s", sparseWork)
        .isGreaterThan(sparse.length() - POLICY.maxQuarantineWindow() - sparseUnit.length());
    assertThat(sparseWork.quarantines()).isZero();
  }

  @Test
  void resetClearsBackoff() {
    Matcher matcher = quarantinedMatcher();

    matcher.reset();

    assertThat(findCalls(matcher)).isEqualTo(1);
  }

  @Test
  void regionClearsBackoff() {
    Matcher matcher = quarantinedMatcher();

    matcher.region(matcher.end(), matcher.regionEnd());

    assertThat(findCalls(matcher)).isEqualTo(1);
  }

  @Test
  void usePatternClearsBackoff() {
    Matcher matcher = quarantinedMatcher();

    matcher.usePattern(matcher.pattern());

    assertThat(findCalls(matcher)).isEqualTo(1);
  }

  /** Returns a matcher whose next find() is quarantined, so it makes no accelerator call. */
  private static Matcher quarantinedMatcher() {
    Matcher matcher = Pattern.compile(REGEX).matcher(DENSE_UNIT.repeat(2_000));
    for (int i = 0; i < 100; i++) {
      assertThat(matcher.find()).isTrue();
    }
    assertThat(findCalls(matcher)).as("the carried state quarantines the next find()").isZero();
    return matcher;
  }

  private static long findCalls(Matcher matcher) {
    return WorkCounter.countStartAccelerationForTesting(() -> assertThat(matcher.find()).isTrue())
        .calls();
  }

  private static void findAll(String input, boolean utf8, List<Integer> starts) {
    Pattern pattern = Pattern.compile(REGEX);
    if (utf8) {
      Utf8Matcher matcher = pattern.matcher(Utf8Input.validated(input.getBytes(UTF_8)));
      while (matcher.find()) {
        starts.add(matcher.start());
      }
    } else {
      Matcher matcher = pattern.matcher(input);
      while (matcher.find()) {
        starts.add(matcher.start());
      }
    }
  }
}
