// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import org.safere.PatternSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmarks for {@link PatternSet} multi-pattern matching.
 *
 * <p>Tests matching text against multiple compiled patterns simultaneously, which is a key use case
 * for content filtering, log classification, and routing.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class PatternSetBenchmark {

  @Param({"4", "16", "64"})
  private int patternCount;

  private PatternSet unanchoredSet;
  private PatternSet anchoredSet;

  private static final BenchmarkData DATA = BenchmarkData.get();
  private static final String MATCH_TEXT = DATA.getString("patternSet.matchText");
  private static final String NO_MATCH_TEXT = DATA.getString("patternSet.noMatchText");
  private static final List<String> BASE_PATTERNS =
      DATA.getStringList("patternSet.basePatterns");

  @Setup
  public void setup() {
    PatternSet.Builder unanchoredBuilder =
        new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
    PatternSet.Builder anchoredBuilder =
        new PatternSet.Builder(PatternSet.Anchor.ANCHOR_START);

    for (int i = 0; i < patternCount; i++) {
      String pat = BASE_PATTERNS.get(i % BASE_PATTERNS.size());
      unanchoredBuilder.add(pat);
      anchoredBuilder.add(pat);
    }

    unanchoredSet = unanchoredBuilder.compile();
    anchoredSet = anchoredBuilder.compile();
  }

  // ===== Unanchored: text with matches =====

  @Benchmark
  public List<Integer> unanchoredMatch() {
    return unanchoredSet.match(MATCH_TEXT);
  }

  // ===== Unanchored: text with no matches =====

  @Benchmark
  public List<Integer> unanchoredNoMatch() {
    return unanchoredSet.match(NO_MATCH_TEXT);
  }

  // ===== Anchored: text with matches =====

  @Benchmark
  public List<Integer> anchoredMatch() {
    return anchoredSet.match(MATCH_TEXT);
  }

  // ===== Anchored: text with no matches =====

  @Benchmark
  public List<Integer> anchoredNoMatch() {
    return anchoredSet.match(NO_MATCH_TEXT);
  }
}
