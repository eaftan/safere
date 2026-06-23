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

/** ReplaceAll workloads from issue 488 that stress NFA fallback under semantic guards. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class Issue488ReplaceAllBenchmark {

  @Param({"1000", "10000", "100000"})
  private int textSize;

  private String lazyAltInput;
  private String altCaptureInput;
  private String lazyAltReplacement;
  private String altCaptureReplacement;

  private org.safere.Pattern safereLazyAlt;
  private org.safere.Pattern safereAltCapture;

  private java.util.regex.Pattern jdkLazyAlt;
  private java.util.regex.Pattern jdkAltCapture;

  private com.google.re2j.Pattern re2jLazyAlt;
  private com.google.re2j.Pattern re2jAltCapture;

  private org.safere.re2ffm.RE2FfmPattern re2ffmLazyAlt;
  private org.safere.re2ffm.RE2FfmPattern re2ffmAltCapture;

  @Setup
  public void setup() {
    BenchmarkData data = BenchmarkData.get();

    String lazyAltPattern = data.getString("issue488ReplaceAll.lazyAlt.pattern");
    lazyAltReplacement = data.getString("issue488ReplaceAll.lazyAlt.replacement");
    lazyAltInput =
        generateLazyAltInput(
            data.getString("issue488ReplaceAll.lazyAlt.prefixUnit"),
            data.getString("issue488ReplaceAll.lazyAlt.match"),
            data.getString("issue488ReplaceAll.lazyAlt.suffixUnit"),
            textSize);

    String altCapturePattern = data.getString("issue488ReplaceAll.altCapture.pattern");
    altCaptureReplacement = data.getString("issue488ReplaceAll.altCapture.replacement");
    altCaptureInput =
        generateAltCaptureInput(
            data.getString("issue488ReplaceAll.altCapture.hitUnit"),
            data.getString("issue488ReplaceAll.altCapture.missUnit"),
            data.getInt("issue488ReplaceAll.altCapture.hitInterval"),
            textSize);

    safereLazyAlt = org.safere.Pattern.compile(lazyAltPattern);
    safereAltCapture = org.safere.Pattern.compile(altCapturePattern);

    jdkLazyAlt = java.util.regex.Pattern.compile(lazyAltPattern);
    jdkAltCapture = java.util.regex.Pattern.compile(altCapturePattern);

    re2jLazyAlt = com.google.re2j.Pattern.compile(lazyAltPattern);
    re2jAltCapture = com.google.re2j.Pattern.compile(altCapturePattern);

    re2ffmLazyAlt = org.safere.re2ffm.RE2FfmPattern.compile(lazyAltPattern);
    re2ffmAltCapture = org.safere.re2ffm.RE2FfmPattern.compile(altCapturePattern);
  }

  @Benchmark
  public String lazyAlt_safere() {
    return safereLazyAlt.matcher(lazyAltInput).replaceAll(lazyAltReplacement);
  }

  @Benchmark
  public String lazyAlt_jdk() {
    return jdkLazyAlt.matcher(lazyAltInput).replaceAll(lazyAltReplacement);
  }

  @Benchmark
  public String lazyAlt_re2j() {
    return re2jLazyAlt.matcher(lazyAltInput).replaceAll(lazyAltReplacement);
  }

  @Benchmark
  public String lazyAlt_re2ffm() {
    return re2ffmLazyAlt.matcher(lazyAltInput).replaceAll(lazyAltReplacement);
  }

  @Benchmark
  public String altCapture_safere() {
    return safereAltCapture.matcher(altCaptureInput).replaceAll(altCaptureReplacement);
  }

  @Benchmark
  public String altCapture_jdk() {
    return jdkAltCapture.matcher(altCaptureInput).replaceAll(altCaptureReplacement);
  }

  @Benchmark
  public String altCapture_re2j() {
    return re2jAltCapture.matcher(altCaptureInput).replaceAll(altCaptureReplacement);
  }

  @Benchmark
  public String altCapture_re2ffm() {
    return re2ffmAltCapture.matcher(altCaptureInput).replaceAll(altCaptureReplacement);
  }

  private static String generateLazyAltInput(
      String prefixUnit, String match, String suffixUnit, int size) {
    StringBuilder sb = new StringBuilder(size + match.length() + prefixUnit.length());
    int halfSize = size / 2;
    while (sb.length() < halfSize) {
      sb.append(prefixUnit);
    }
    sb.append(match);
    while (sb.length() < size) {
      sb.append(suffixUnit);
    }
    return sb.substring(0, size);
  }

  private static String generateAltCaptureInput(
      String hitUnit, String missUnit, int hitInterval, int size) {
    StringBuilder sb = new StringBuilder(size + Math.max(hitUnit.length(), missUnit.length()));
    int counter = 0;
    while (sb.length() < size) {
      sb.append(counter % hitInterval == 0 ? hitUnit : missUnit);
      counter++;
    }
    return sb.substring(0, size);
  }
}
