// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.List;

/** Benchmark-only adapter for measuring package-private alternation anchor searches directly. */
public final class AlternationFindNextBenchmarkRunner {
  private final MultiAnchorDescriptor.Anchor.Alternation alternation;
  private final String[] inputs;
  private int nextInput;

  /** Creates a runner over fixed-width slices of a materialized input pool. */
  public AlternationFindNextBenchmarkRunner(
      List<String> literals, String inputPool, int inputLength) {
    if (inputLength <= 0 || inputPool.length() % inputLength != 0) {
      throw new IllegalArgumentException("Input pool must contain equal non-empty slices");
    }
    this.alternation =
        MultiAnchorDescriptor.Anchor.Alternation.create(literals.toArray(String[]::new), true);
    this.inputs = new String[inputPool.length() / inputLength];
    for (int i = 0; i < inputs.length; i++) {
      inputs[i] = inputPool.substring(i * inputLength, (i + 1) * inputLength);
      if (alternation.findNext(inputs[i], 0) >= 0) {
        throw new IllegalArgumentException("Alternation benchmark input pool contains a match");
      }
    }
  }

  /** Searches the next input in the rotating pool and returns the match position. */
  public int findNext() {
    String input = inputs[nextInput];
    nextInput++;
    if (nextInput == inputs.length) {
      nextInput = 0;
    }
    return alternation.findNext(input, 0);
  }
}
