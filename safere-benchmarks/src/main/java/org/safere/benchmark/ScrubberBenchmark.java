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

/**
 * Benchmarks a source-code scrubber workload with word-boundary directive patterns.
 *
 * <p>The workload is based on the minimized performance repro from issue #465: two independent
 * {@code replaceAll("")} operations over a repeated Java-like source file, one removing single-line
 * directives and one removing directive-delimited blocks.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class ScrubberBenchmark {

  private static final String REPLACEMENT = "";

  private org.safere.Pattern safeLineStrip;
  private org.safere.Pattern safeBlockStrip;
  private java.util.regex.Pattern jdkLineStrip;
  private java.util.regex.Pattern jdkBlockStrip;
  private com.google.re2j.Pattern re2jLineStrip;
  private com.google.re2j.Pattern re2jBlockStrip;
  private org.safere.re2ffm.RE2FfmPattern re2ffmLineStrip;
  private org.safere.re2ffm.RE2FfmPattern re2ffmBlockStrip;

  private String inputWithoutDirectives;
  private String inputWithDirectives;

  @Setup
  public void setup() {
    BenchmarkData data = BenchmarkData.get();
    int repeatCount = data.getInt("scrubber.repeatCount");
    String linePattern = data.getString("scrubber.linePattern");
    String blockPattern = data.getString("scrubber.blockPattern");

    inputWithoutDirectives = data.getString("scrubber.baseWithoutDirectives").repeat(repeatCount);
    inputWithDirectives = data.getString("scrubber.baseWithDirectives").repeat(repeatCount);

    safeLineStrip = org.safere.Pattern.compile(linePattern, org.safere.Pattern.COMMENTS);
    safeBlockStrip = org.safere.Pattern.compile(blockPattern, org.safere.Pattern.COMMENTS);
    jdkLineStrip = java.util.regex.Pattern.compile(linePattern, java.util.regex.Pattern.COMMENTS);
    jdkBlockStrip = java.util.regex.Pattern.compile(blockPattern, java.util.regex.Pattern.COMMENTS);
    re2jLineStrip = com.google.re2j.Pattern.compile(linePattern);
    re2jBlockStrip = com.google.re2j.Pattern.compile(blockPattern);
    re2ffmLineStrip = org.safere.re2ffm.RE2FfmPattern.compile(linePattern);
    re2ffmBlockStrip = org.safere.re2ffm.RE2FfmPattern.compile(blockPattern);

    validateOutputs(inputWithoutDirectives);
    validateOutputs(inputWithDirectives);
  }

  @Benchmark
  public int scrubNoDirectives_safere() {
    return scrub(safeLineStrip, safeBlockStrip, inputWithoutDirectives);
  }

  @Benchmark
  public int scrubNoDirectives_jdk() {
    return scrub(jdkLineStrip, jdkBlockStrip, inputWithoutDirectives);
  }

  @Benchmark
  public int scrubNoDirectives_re2j() {
    return scrub(re2jLineStrip, re2jBlockStrip, inputWithoutDirectives);
  }

  @Benchmark
  public int scrubNoDirectives_re2ffm() {
    return scrub(re2ffmLineStrip, re2ffmBlockStrip, inputWithoutDirectives);
  }

  @Benchmark
  public int scrubWithDirectives_safere() {
    return scrub(safeLineStrip, safeBlockStrip, inputWithDirectives);
  }

  @Benchmark
  public int scrubWithDirectives_jdk() {
    return scrub(jdkLineStrip, jdkBlockStrip, inputWithDirectives);
  }

  @Benchmark
  public int scrubWithDirectives_re2j() {
    return scrub(re2jLineStrip, re2jBlockStrip, inputWithDirectives);
  }

  @Benchmark
  public int scrubWithDirectives_re2ffm() {
    return scrub(re2ffmLineStrip, re2ffmBlockStrip, inputWithDirectives);
  }

  private static int scrub(
      org.safere.Pattern lineStrip, org.safere.Pattern blockStrip, String input) {
    return lineStrip.matcher(input).replaceAll(REPLACEMENT).length()
        + blockStrip.matcher(input).replaceAll(REPLACEMENT).length();
  }

  private static int scrub(
      java.util.regex.Pattern lineStrip, java.util.regex.Pattern blockStrip, String input) {
    return lineStrip.matcher(input).replaceAll(REPLACEMENT).length()
        + blockStrip.matcher(input).replaceAll(REPLACEMENT).length();
  }

  private static int scrub(
      com.google.re2j.Pattern lineStrip, com.google.re2j.Pattern blockStrip, String input) {
    return lineStrip.matcher(input).replaceAll(REPLACEMENT).length()
        + blockStrip.matcher(input).replaceAll(REPLACEMENT).length();
  }

  private static int scrub(
      org.safere.re2ffm.RE2FfmPattern lineStrip,
      org.safere.re2ffm.RE2FfmPattern blockStrip,
      String input) {
    return lineStrip.matcher(input).replaceAll(REPLACEMENT).length()
        + blockStrip.matcher(input).replaceAll(REPLACEMENT).length();
  }

  private void validateOutputs(String input) {
    String expectedLine = jdkLineStrip.matcher(input).replaceAll(REPLACEMENT);
    String expectedBlock = jdkBlockStrip.matcher(input).replaceAll(REPLACEMENT);

    validate("SafeRE line", safeLineStrip.matcher(input).replaceAll(REPLACEMENT), expectedLine);
    validate("SafeRE block", safeBlockStrip.matcher(input).replaceAll(REPLACEMENT), expectedBlock);
    validate("RE2/J line", re2jLineStrip.matcher(input).replaceAll(REPLACEMENT), expectedLine);
    validate("RE2/J block", re2jBlockStrip.matcher(input).replaceAll(REPLACEMENT), expectedBlock);
    validate("RE2-FFM line", re2ffmLineStrip.matcher(input).replaceAll(REPLACEMENT), expectedLine);
    validate(
        "RE2-FFM block", re2ffmBlockStrip.matcher(input).replaceAll(REPLACEMENT), expectedBlock);
  }

  private static void validate(String label, String actual, String expected) {
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException(
          label
              + " scrubber output length "
              + actual.length()
              + " did not match JDK output length "
              + expected.length());
    }
  }
}
