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
 * Measures overhead added by the crosscheck facade for replacement loops that call
 * {@code appendReplacement} once per match.
 *
 * <p>This is a diagnostic benchmark for crosscheck optimization work. The Java benchmark wrapper
 * excludes it from default no-argument runs; run this class explicitly when needed.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class CrosscheckOverheadBenchmark {

  @Param({"16", "128", "1024"})
  public int matches;

  private String text;
  private String replacement;
  private org.safere.Pattern saferePattern;
  private java.util.regex.Pattern jdkPattern;
  private org.safere.crosscheck.Pattern crosscheckPattern;

  @Setup
  public void setup() {
    StringBuilder sb = new StringBuilder(matches * 12);
    for (int i = 0; i < matches; i++) {
      sb.append("item").append(i).append(';');
    }
    text = sb.toString();
    replacement = "$2:$1";

    String regex = "([a-z]+)(\\d+)";
    saferePattern = org.safere.Pattern.compile(regex);
    jdkPattern = java.util.regex.Pattern.compile(regex);
    crosscheckPattern = org.safere.crosscheck.Pattern.compile(regex);
  }

  @Benchmark
  public String appendReplacement_safere() {
    org.safere.Matcher matcher = saferePattern.matcher(text);
    StringBuilder sb = new StringBuilder(text.length());
    while (matcher.find()) {
      matcher.appendReplacement(sb, replacement);
    }
    matcher.appendTail(sb);
    return sb.toString();
  }

  @Benchmark
  public String appendReplacement_jdk() {
    java.util.regex.Matcher matcher = jdkPattern.matcher(text);
    StringBuilder sb = new StringBuilder(text.length());
    while (matcher.find()) {
      matcher.appendReplacement(sb, replacement);
    }
    matcher.appendTail(sb);
    return sb.toString();
  }

  @Benchmark
  public String appendReplacement_crosscheck() {
    org.safere.crosscheck.Matcher matcher = crosscheckPattern.matcher(text);
    StringBuilder sb = new StringBuilder(text.length());
    while (matcher.find()) {
      matcher.appendReplacement(sb, replacement);
    }
    matcher.appendTail(sb);
    return sb.toString();
  }
}
