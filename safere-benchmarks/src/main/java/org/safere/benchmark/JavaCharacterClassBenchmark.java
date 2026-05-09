// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Benchmarks for JDK-compatible {@code \p{java...}} character classes. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class JavaCharacterClassBenchmark {
  private static final String JAVA_LETTER = "\\p{javaLetter}";
  private static final int INPUT_COUNT = 4096;

  private String[] inputs;
  private int index;
  private org.safere.Pattern saferePattern;
  private java.util.regex.Pattern jdkPattern;

  @Setup
  public void setup() {
    inputs = new String[INPUT_COUNT];
    Random random = new Random(0x5AFE355L);
    for (int i = 0; i < inputs.length; i++) {
      inputs[i] = new String(new char[] {(char) random.nextInt()});
    }
    saferePattern = org.safere.Pattern.compile(JAVA_LETTER);
    jdkPattern = java.util.regex.Pattern.compile(JAVA_LETTER);
  }

  @Benchmark
  public boolean compileAndFindJavaLetter_safere() {
    org.safere.Pattern pattern = org.safere.Pattern.compile(JAVA_LETTER);
    return pattern.matcher(nextInput()).find();
  }

  @Benchmark
  public boolean compileAndFindJavaLetter_jdk() {
    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(JAVA_LETTER);
    return pattern.matcher(nextInput()).find();
  }

  @Benchmark
  public boolean findJavaLetter_safere() {
    return saferePattern.matcher(nextInput()).find();
  }

  @Benchmark
  public boolean findJavaLetter_jdk() {
    return jdkPattern.matcher(nextInput()).find();
  }

  private String nextInput() {
    String input = inputs[index];
    index = (index + 1) & (INPUT_COUNT - 1);
    return input;
  }
}
