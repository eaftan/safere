// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Benchmarks String matcher API paths not covered by the core regex benchmarks. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class MatcherApiBenchmark {

  private org.safere.Pattern safeLookingAt;
  private java.util.regex.Pattern jdkLookingAt;
  private String lookingAtText;

  private org.safere.Matcher safeRegionMatcher;
  private java.util.regex.Matcher jdkRegionMatcher;
  private int regionStart;
  private int regionEnd;

  private org.safere.Matcher safeResetMatcher;
  private java.util.regex.Matcher jdkResetMatcher;

  @Setup
  public void setup() {
    BenchmarkData data = BenchmarkData.get();

    String lookingAtPattern = data.getString("matcherApi.lookingAt.pattern");
    lookingAtText = data.getString("matcherApi.lookingAt.text");
    safeLookingAt = org.safere.Pattern.compile(lookingAtPattern);
    jdkLookingAt = java.util.regex.Pattern.compile(lookingAtPattern);

    String regionPattern = data.getString("matcherApi.regionFind.pattern");
    String regionText = data.getString("matcherApi.regionFind.text");
    regionStart = data.getInt("matcherApi.regionFind.start");
    regionEnd = data.getInt("matcherApi.regionFind.end");
    safeRegionMatcher = org.safere.Pattern.compile(regionPattern).matcher(regionText);
    jdkRegionMatcher = java.util.regex.Pattern.compile(regionPattern).matcher(regionText);

    String resetPattern = data.getString("matcherApi.resetAndFind.pattern");
    String resetText = data.getString("matcherApi.resetAndFind.text");
    safeResetMatcher = org.safere.Pattern.compile(resetPattern).matcher(resetText);
    jdkResetMatcher = java.util.regex.Pattern.compile(resetPattern).matcher(resetText);

    if (!lookingAt_safere() || !lookingAt_jdk()) {
      throw new IllegalStateException("lookingAt benchmark input must match");
    }
    if (!regionFind_safere() || !regionFind_jdk()) {
      throw new IllegalStateException("region benchmark input must match");
    }
    if (resetAndFind_safere() != 3 || resetAndFind_jdk() != 3) {
      throw new IllegalStateException("reset benchmark input must contain three matches");
    }
  }

  @Benchmark
  public boolean lookingAt_safere() {
    return safeLookingAt.matcher(lookingAtText).lookingAt();
  }

  @Benchmark
  public boolean lookingAt_jdk() {
    return jdkLookingAt.matcher(lookingAtText).lookingAt();
  }

  @Benchmark
  public boolean regionFind_safere() {
    return safeRegionMatcher.region(regionStart, regionEnd).find();
  }

  @Benchmark
  public boolean regionFind_jdk() {
    return jdkRegionMatcher.region(regionStart, regionEnd).find();
  }

  @Benchmark
  public int resetAndFind_safere() {
    safeResetMatcher.reset();
    int count = 0;
    while (safeResetMatcher.find()) {
      count++;
    }
    return count;
  }

  @Benchmark
  public int resetAndFind_jdk() {
    jdkResetMatcher.reset();
    int count = 0;
    while (jdkResetMatcher.find()) {
      count++;
    }
    return count;
  }
}
