// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Start-acceleration backoff for a leading-expansion accelerator over a two-member small set.
 *
 * <p>For the citation pattern below, the accelerator finds the next {@code [} or {@code \uFF3B} and
 * expands the optional leading space backward from it. On dense citations its skips are too short
 * to repay a call, so it must quarantine; on sparse citations every call skips a long gap, so it
 * must keep accelerating.
 */
@DisabledForCrosscheck("WorkCounter and acceleration policies are SafeRE implementation details")
@Tag("work-counter")
class LeadingExpansionBackoffTest {
  private static final String REGEX = " ?[\\[\uFF3B](?:(?:\\d+\\.){2,}\\d+(?:, )?)+[\\]\uFF3D]";
  private static final int LENGTH = 65_536;

  /** A citation every ~30 characters, with skips that alternate between about 4 and 29. */
  private static final String DENSE_UNIT =
      "See \uFF3B1.2.3\uFF3D and \uFF3B4.5.6, 7.8.9\uFF3D for version 2.4.1 details. ";

  /** One citation per 64 sentences, about 1000 characters apart. */
  private static final String SPARSE_HIT = "这是一个测试句子，参见附录。\uFF3B1.2.3\uFF3D";

  private static final String SPARSE_MISS = "这是一个测试句子，参见附录。";

  @Test
  void patternUsesALeadingExpansionAccelerator() {
    Pattern pattern = Pattern.compile(REGEX);
    assertThat(pattern.stringStartAccelerator())
        .isInstanceOf(StringStartAccelerator.LeadingExpansion.class);
  }

  @Test
  void denseCandidatesQuarantine() {
    String input = repeatToLength(DENSE_UNIT, LENGTH);
    List<Integer> starts = new ArrayList<>();

    WorkCounter.StartAccelerationWork work =
        WorkCounter.countStartAccelerationForTesting(() -> findAll(input, starts));

    assertThat(starts).isEqualTo(jdkStarts(input));
    assertThat(starts).hasSizeGreaterThan(2000);
    assertThat(work.quarantines()).as("%s", work).isPositive();
    // One call per match would mean the accelerator never backed off.
    assertThat(work.calls()).as("%s", work).isLessThan(starts.size() / 8L);
  }

  @Test
  void sparseCandidatesKeepAccelerating() {
    String input = sparse(LENGTH);
    List<Integer> starts = new ArrayList<>();

    WorkCounter.StartAccelerationWork work =
        WorkCounter.countStartAccelerationForTesting(() -> findAll(input, starts));

    assertThat(starts).isEqualTo(jdkStarts(input));
    assertThat(starts).hasSizeGreaterThan(50);
    assertThat(work.quarantines()).as("%s", work).isZero();
    // Every gap between citations is skipped by the accelerator, not stepped by the DFA.
    assertThat(work.calls()).as("%s", work).isEqualTo(starts.size());
    assertThat(work.skippedUnits()).as("%s", work).isGreaterThan(LENGTH * 9L / 10);
  }

  private static void findAll(String input, List<Integer> starts) {
    Matcher matcher = Pattern.compile(REGEX).matcher(input);
    while (matcher.find()) {
      starts.add(matcher.start());
    }
  }

  private static List<Integer> jdkStarts(String input) {
    List<Integer> starts = new ArrayList<>();
    var matcher = java.util.regex.Pattern.compile(REGEX).matcher(input);
    while (matcher.find()) {
      starts.add(matcher.start());
    }
    return starts;
  }

  private static String repeatToLength(String unit, int length) {
    return unit.repeat(length / unit.length() + 1).substring(0, length);
  }

  private static String sparse(int length) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; sb.length() < length; i++) {
      sb.append(i % 64 == 0 ? SPARSE_HIT : SPARSE_MISS);
    }
    return sb.substring(0, length);
  }
}
