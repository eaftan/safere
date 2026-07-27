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

/** Specialized capture-materialization comparison retained outside the shared execution model. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class RegexBenchmark {

  private org.safere.Pattern saferePattern;
  private java.util.regex.Pattern jdkPattern;
  private com.google.re2j.Pattern re2jPattern;
  private org.safere.re2ffm.RE2FfmPattern re2ffmPattern;
  private String text;

  /** Compiles each engine's pattern and loads the capture input outside the timed operation. */
  @Setup
  public void setup() {
    BenchmarkData data = BenchmarkData.get();
    String pattern = data.getString("regex.captureGroups.pattern");
    text =
        data.getInputString("crossEngine." + data.getString("regex.captureGroups.id") + ".input");
    saferePattern = org.safere.Pattern.compile(pattern);
    jdkPattern = java.util.regex.Pattern.compile(pattern);
    re2jPattern = com.google.re2j.Pattern.compile(pattern);
    re2ffmPattern = org.safere.re2ffm.RE2FfmPattern.compile(pattern);
  }

  /** Measures SafeRE matching followed by capture String materialization and concatenation. */
  @Benchmark
  public String captureGroups_safere() {
    org.safere.Matcher matcher = saferePattern.matcher(text);
    matcher.matches();
    return matcher.group(1) + matcher.group(2) + matcher.group(3);
  }

  /** Measures JDK matching followed by capture String materialization and concatenation. */
  @Benchmark
  public String captureGroups_jdk() {
    java.util.regex.Matcher matcher = jdkPattern.matcher(text);
    matcher.matches();
    return matcher.group(1) + matcher.group(2) + matcher.group(3);
  }

  /** Measures RE2/J matching followed by capture String materialization and concatenation. */
  @Benchmark
  public String captureGroups_re2j() {
    com.google.re2j.Matcher matcher = re2jPattern.matcher(text);
    matcher.matches();
    return matcher.group(1) + matcher.group(2) + matcher.group(3);
  }

  /** Measures RE2-FFM matching followed by capture String materialization and concatenation. */
  @Benchmark
  public String captureGroups_re2ffm() {
    org.safere.re2ffm.RE2FfmMatcher matcher = re2ffmPattern.matcher(text);
    matcher.matches();
    return matcher.group(1) + matcher.group(2) + matcher.group(3);
  }
}
