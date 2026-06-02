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

/** Benchmarks compile-time cost for Unicode-heavy regular expressions. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class UnicodeCompileBenchmark {

  @Param({
    "\\p{L}+",
    "\\p{Lu}+",
    "\\p{Ll}+",
    "\\p{Lt}+",
    "\\p{IsAlphabetic}+",
    "\\p{IsIdeographic}+",
    "\\p{IsEmoji}+",
    "\\p{IsExtended_Pictographic}+",
    "\\p{script=Latin}+",
    "\\p{script=Han}+",
    "\\p{block=BasicLatin}+",
    "\\p{block=CJK_Unified_Ideographs}+",
    "\\w+",
    "\\d+",
    "\\s+",
    "[a-z]+",
    "[h-j]+",
    "[\\p{L}&&[^\\p{Lu}]]+",
    "\\p{L}[\\p{L}\\p{Nd}_]*"
  })
  public String regex;

  @Param({
    "0",
    "CASE_INSENSITIVE",
    "CASE_INSENSITIVE_UNICODE_CASE",
    "CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS"
  })
  public String flagSet;

  private int flags;

  @Setup
  public void setup() {
    flags = BenchmarkFlags.parse(flagSet);
  }

  @Benchmark
  public org.safere.Pattern compile_safere() {
    return org.safere.Pattern.compile(regex, flags);
  }

  @Benchmark
  public java.util.regex.Pattern compile_jdk() {
    return java.util.regex.Pattern.compile(regex, flags);
  }
}
