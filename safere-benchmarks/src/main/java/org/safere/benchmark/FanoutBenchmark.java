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
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Pathological fanout benchmark, ported from RE2 C++ {@code regexp_benchmark.cc}.
 *
 * <p>The pattern {@code (?:[\x{80}-\x{10FFFF}]?){100}[\x{80}-\x{10FFFF}]} creates high NFA fanout
 * because each of the 100 optional Unicode ranges can match or skip, creating 2^100 possible paths.
 * This stresses DFA state generation and NFA thread management.
 *
 * <p>Also includes a simpler nested-quantifier fanout pattern to measure the impact of quantifier
 * nesting depth.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class FanoutBenchmark {

  @Param({"1024", "10240", "102400"})
  private int textSize;

  // Unicode fanout: RE2's Search_Fanout pattern (adapted for Java).
  // [\x{80}-\x{10FFFF}] matches any non-ASCII Unicode code point.
  private org.safere.Pattern safeFanout;
  private java.util.regex.Pattern jdkFanout;
  private com.google.re2j.Pattern re2jFanout;
  private org.safere.re2ffm.RE2FfmPattern re2ffmFanout;

  // Nested quantifier: a?{n}a{n} but with higher n and varying text.
  private org.safere.Pattern safeNested;
  private java.util.regex.Pattern jdkNested;
  private com.google.re2j.Pattern re2jNested;
  private org.safere.re2ffm.RE2FfmPattern re2ffmNested;

  private String unicodeText;
  private String asciiText;

  private String fanoutPattern;
  private String nestedPattern;

  @Setup
  public void setup() {
    BenchmarkData data = BenchmarkData.get();
    fanoutPattern = data.getString("fanout.unicodeFanout.pattern");
    nestedPattern = data.getString("fanout.nestedQuantifier.pattern");
    safeFanout = org.safere.Pattern.compile(fanoutPattern);
    jdkFanout = java.util.regex.Pattern.compile(fanoutPattern);

    safeNested = org.safere.Pattern.compile(nestedPattern);
    jdkNested = java.util.regex.Pattern.compile(nestedPattern);

    re2jFanout = com.google.re2j.Pattern.compile(fanoutPattern);
    re2jNested = com.google.re2j.Pattern.compile(nestedPattern);

    re2ffmFanout = org.safere.re2ffm.RE2FfmPattern.compile(fanoutPattern);
    re2ffmNested = org.safere.re2ffm.RE2FfmPattern.compile(nestedPattern);

    unicodeText = data.getInputString("fanout.unicode." + textSize);
    asciiText = data.getInputString("fanout.ascii." + textSize);
  }

  // ===== Unicode fanout: find in Unicode text =====

  @Benchmark
  public boolean fanoutUnicode_safere() {
    return safeFanout.matcher(unicodeText).find();
  }

  @Benchmark
  public boolean fanoutUnicode_jdk() {
    return jdkFanout.matcher(unicodeText).find();
  }

  @Benchmark
  public boolean fanoutUnicode_re2j() {
    return re2jFanout.matcher(unicodeText).find();
  }

  @Benchmark
  public boolean fanoutUnicode_re2ffm() {
    return re2ffmFanout.matcher(unicodeText).find();
  }

  // ===== Nested quantifier: find in ASCII text =====

  @Benchmark
  public boolean nestedQuantifier_safere() {
    return safeNested.matcher(asciiText).find();
  }

  @Benchmark
  public boolean nestedQuantifier_jdk() {
    return jdkNested.matcher(asciiText).find();
  }

  @Benchmark
  public boolean nestedQuantifier_re2j() {
    return re2jNested.matcher(asciiText).find();
  }

  @Benchmark
  public boolean nestedQuantifier_re2ffm() {
    return re2ffmNested.matcher(asciiText).find();
  }
}
