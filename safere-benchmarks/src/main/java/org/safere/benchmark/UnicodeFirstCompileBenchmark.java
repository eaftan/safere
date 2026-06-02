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

/** Single-shot first-compile benchmark for Unicode table initialization costs. */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class UnicodeFirstCompileBenchmark {

  @Param({
    "\\p{L}+",
    "\\p{Lu}+",
    "\\p{IsAlphabetic}+",
    "\\p{IsEmoji}+",
    "\\p{IsExtended_Pictographic}+",
    "\\p{script=Latin}+",
    "\\p{script=Han}+",
    "\\p{block=BasicLatin}+",
    "\\w+",
    "[a-z]+",
    "[h-j]+",
    "\\p{L}[\\p{L}\\p{Nd}_]*"
  })
  public String regex;

  @Param({"0", "CASE_INSENSITIVE_UNICODE_CASE", "UNICODE_CHARACTER_CLASS"})
  public String flagSet;

  private int flags;

  @Setup
  public void setup() {
    flags = BenchmarkFlags.parse(flagSet);
  }

  @Benchmark
  public org.safere.Pattern firstCompile_safere() {
    return org.safere.Pattern.compile(regex, flags);
  }

  @Benchmark
  public java.util.regex.Pattern firstCompile_jdk() {
    return java.util.regex.Pattern.compile(regex, flags);
  }
}
