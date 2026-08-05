// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.safere.Pattern;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(value = 1)
@Warmup(iterations = 2, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class StringVectorScanBenchmark {

  @Param({"32", "2048"})
  public int length;

  private String textDigit;
  private String textWord;
  private String textAlpha;
  private String textNegated;
  private String textPrefix;

  private Pattern patternSingle;
  private Pattern patternMulti;
  private Pattern patternWord;
  private Pattern patternAlpha;
  private Pattern patternNegated;
  private Pattern patternPrefix;

  @Setup
  public void setup() {
    // 1. For [0-9] and [0-9a-c]: fill with 'x', end with '9'
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < length - 1; i++) {
      sb.append('x');
    }
    sb.append('9');
    textDigit = sb.toString();

    // 2. For \w: fill with '-', end with 'a'
    sb = new StringBuilder();
    for (int i = 0; i < length - 1; i++) {
      sb.append('-');
    }
    sb.append('a');
    textWord = sb.toString();

    // 3. For [A-Za-z]: fill with '0', end with 'a'
    sb = new StringBuilder();
    for (int i = 0; i < length - 1; i++) {
      sb.append('0');
    }
    sb.append('a');
    textAlpha = sb.toString();

    // 4. For [^A-Za-z0-9]: fill with 'a', end with '-'
    sb = new StringBuilder();
    for (int i = 0; i < length - 1; i++) {
      sb.append('a');
    }
    sb.append('-');
    textNegated = sb.toString();

    // 5. For (?i)hello: fill with 'x', end with 'Hello'
    sb = new StringBuilder();
    for (int i = 0; i < length - 5; i++) {
      sb.append('x');
    }
    sb.append("Hello");
    textPrefix = sb.toString();

    patternSingle = Pattern.compile("[0-9]");
    patternMulti = Pattern.compile("[0-9a-c]");
    patternWord = Pattern.compile("\\w");
    patternAlpha = Pattern.compile("[A-Za-z]");
    patternNegated = Pattern.compile("[^A-Za-z0-9]");
    patternPrefix = Pattern.compile("(?i)hello");
  }

  @Benchmark
  public boolean scanSingle() {
    return patternSingle.matcher(textDigit).find();
  }

  @Benchmark
  public boolean scanMulti() {
    return patternMulti.matcher(textDigit).find();
  }

  @Benchmark
  public boolean scanWord() {
    return patternWord.matcher(textWord).find();
  }

  @Benchmark
  public boolean scanAlpha() {
    return patternAlpha.matcher(textAlpha).find();
  }

  @Benchmark
  public boolean scanNegated() {
    return patternNegated.matcher(textNegated).find();
  }

  @Benchmark
  public boolean scanPrefixIgnoreCase() {
    return patternPrefix.matcher(textPrefix).find();
  }
}
