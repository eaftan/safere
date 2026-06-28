package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("work-counter")
class SearchScalingRegressionTest {

  @Test
  void testReverseDfaSuffixScanScalingFailsInConstantTime() {
    Pattern pattern = Pattern.compile("[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$");

    // Generate failing text (all lowercase) of two different sizes
    String text2000 = "a".repeat(2000);
    String text10000 = "a".repeat(10000);

    // Measure work done using WorkCounter
    long work2000 = WorkCounter.countForTesting(() -> {
      boolean matched = pattern.matcher(text2000).find();
      assertThat(matched).isFalse();
    });

    long work10000 = WorkCounter.countForTesting(() -> {
      boolean matched = pattern.matcher(text10000).find();
      assertThat(matched).isFalse();
    });

    // If the containsRequiredMatchClass pre-filter loop runs, it would scan the entire string,
    // resulting in at least 2,000 and 10,000 operations respectively.
    //
    // Under reverse DFA suffix acceleration, it rejects after inspecting only a few characters
    // from the end of the text, executing in constant time independent of text size.
    assertThat(work2000)
        .as("Short text failing scan should run in constant-time reverse DFA setup")
        .isLessThan(200);

    assertThat(work10000)
        .as("Long text failing scan should also run in constant-time reverse DFA setup")
        .isLessThan(200);

    // Assert that scaling is sub-linear (effectively constant)
    assertThat(work10000)
        .as("Work scaling should be flat, not linear with input size increase")
        .isLessThan(work2000 * 2);
  }
}
