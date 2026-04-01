// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package dev.eaftan.safere.benchmark;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmarks for searching through large text, ported from RE2 C++ {@code regexp_benchmark.cc}.
 *
 * <p>Tests both "failing search" (pattern not in text, must scan entire input) and "successful
 * search" scenarios at varying text sizes. These are the most important benchmarks for showing how
 * SafeRE scales with input size and how DFA/prefix acceleration helps.
 *
 * <p>RE2 C++ patterns:
 *
 * <ul>
 *   <li>Easy0: {@code ABCDEFGHIJKLMNOPQRSTUVWXYZ$} — has memchr-able literal prefix
 *   <li>Medium: {@code [XYZ]ABCDEFGHIJKLMNOPQRSTUVWXYZ$} — starts with char class, no memchr
 *   <li>Hard: {@code [ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$} — leading {@code [ -~]*} causes
 *       catastrophic backtracking in naive engines
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class SearchScalingBenchmark {

  @Param({"1024", "10240", "102400", "1048576"})
  private int textSize;

  // Random text that does NOT contain uppercase letters (so patterns always fail to match).
  private String randomText;

  // Text with a match planted at the end (successful search).
  private String textWithMatch;

  // Pre-compiled patterns.
  private dev.eaftan.safere.Pattern safeEasy;
  private java.util.regex.Pattern jdkEasy;
  private dev.eaftan.safere.Pattern safeMedium;
  private java.util.regex.Pattern jdkMedium;
  private dev.eaftan.safere.Pattern safeHard;
  private java.util.regex.Pattern jdkHard;

  private com.google.re2j.Pattern re2jEasy;
  private com.google.re2j.Pattern re2jMedium;
  private com.google.re2j.Pattern re2jHard;
  private com.google.re2j.Pattern re2jFindIng;

  private dev.eaftan.safere.re2ffm.RE2FfmPattern re2ffmEasy;
  private dev.eaftan.safere.re2ffm.RE2FfmPattern re2ffmMedium;
  private dev.eaftan.safere.re2ffm.RE2FfmPattern re2ffmHard;
  private dev.eaftan.safere.re2ffm.RE2FfmPattern re2ffmFindIng;

  // For the "find in text" scaling tests (existing patterns on larger text).
  private dev.eaftan.safere.Pattern safeFindIng;
  private java.util.regex.Pattern jdkFindIng;
  private String scaledProse;

  private String easyPattern;
  private String mediumPattern;
  private String hardPattern;
  private String matchSuffix;
  private String findIngPattern;
  private String proseUnit;

  @Setup
  public void setup() {
    BenchmarkData data = BenchmarkData.get();
    easyPattern = data.getString("searchScaling.patterns.easy");
    mediumPattern = data.getString("searchScaling.patterns.medium");
    hardPattern = data.getString("searchScaling.patterns.hard");
    matchSuffix = data.getString("searchScaling.matchSuffix");
    findIngPattern = data.getString("searchScaling.findIngPattern");
    proseUnit = data.getString("searchScaling.proseUnit");

    int seed = data.getInt("searchScaling.randomText.seed");
    String alphabet = data.getString("searchScaling.randomText.alphabet");
    alphabet = alphabet.replace("\\n", "\n");

    // Generate random lowercase + digits + space text (no uppercase).
    Random rng = new Random(seed);
    char[] chars = new char[textSize];
    for (int i = 0; i < textSize; i++) {
      chars[i] = alphabet.charAt(rng.nextInt(alphabet.length()));
    }
    randomText = new String(chars);
    textWithMatch = randomText + matchSuffix;

    // Compile search patterns.
    safeEasy = dev.eaftan.safere.Pattern.compile(easyPattern);
    jdkEasy = java.util.regex.Pattern.compile(easyPattern);
    safeMedium = dev.eaftan.safere.Pattern.compile(mediumPattern);
    jdkMedium = java.util.regex.Pattern.compile(mediumPattern);
    safeHard = dev.eaftan.safere.Pattern.compile(hardPattern);
    jdkHard = java.util.regex.Pattern.compile(hardPattern);

    // Build scaled prose by repeating the unit to reach textSize.
    StringBuilder sb = new StringBuilder(textSize + proseUnit.length());
    while (sb.length() < textSize) {
      sb.append(proseUnit);
    }
    scaledProse = sb.toString();
    safeFindIng = dev.eaftan.safere.Pattern.compile(findIngPattern);
    jdkFindIng = java.util.regex.Pattern.compile(findIngPattern);

    re2jEasy = com.google.re2j.Pattern.compile(easyPattern);
    re2jMedium = com.google.re2j.Pattern.compile(mediumPattern);
    re2jHard = com.google.re2j.Pattern.compile(hardPattern);
    re2jFindIng = com.google.re2j.Pattern.compile(findIngPattern);

    re2ffmEasy = dev.eaftan.safere.re2ffm.RE2FfmPattern.compile(easyPattern);
    re2ffmMedium = dev.eaftan.safere.re2ffm.RE2FfmPattern.compile(mediumPattern);
    re2ffmHard = dev.eaftan.safere.re2ffm.RE2FfmPattern.compile(hardPattern);
    re2ffmFindIng = dev.eaftan.safere.re2ffm.RE2FfmPattern.compile(findIngPattern);
  }

  // ===== Easy pattern: failing search =====

  @Benchmark
  public boolean searchEasyFail_safere() {
    return safeEasy.matcher(randomText).find();
  }

  @Benchmark
  public boolean searchEasyFail_jdk() {
    return jdkEasy.matcher(randomText).find();
  }

  @Benchmark
  public boolean searchEasyFail_re2j() {
    return re2jEasy.matcher(randomText).find();
  }

  @Benchmark
  public boolean searchEasyFail_re2ffm() {
    return re2ffmEasy.matcher(randomText).find();
  }

  // ===== Easy pattern: successful search (match at end) =====

  @Benchmark
  public boolean searchEasySuccess_safere() {
    return safeEasy.matcher(textWithMatch).find();
  }

  @Benchmark
  public boolean searchEasySuccess_jdk() {
    return jdkEasy.matcher(textWithMatch).find();
  }

  @Benchmark
  public boolean searchEasySuccess_re2j() {
    return re2jEasy.matcher(textWithMatch).find();
  }

  @Benchmark
  public boolean searchEasySuccess_re2ffm() {
    return re2ffmEasy.matcher(textWithMatch).find();
  }

  // ===== Medium pattern: failing search =====

  @Benchmark
  public boolean searchMediumFail_safere() {
    return safeMedium.matcher(randomText).find();
  }

  @Benchmark
  public boolean searchMediumFail_jdk() {
    return jdkMedium.matcher(randomText).find();
  }

  @Benchmark
  public boolean searchMediumFail_re2j() {
    return re2jMedium.matcher(randomText).find();
  }

  @Benchmark
  public boolean searchMediumFail_re2ffm() {
    return re2ffmMedium.matcher(randomText).find();
  }

  // ===== Hard pattern: failing search =====
  // Note: The hard pattern with [ -~]* can cause catastrophic backtracking in JDK.
  // At large sizes the JDK benchmark may be very slow.

  @Benchmark
  public boolean searchHardFail_safere() {
    return safeHard.matcher(randomText).find();
  }

  @Benchmark
  public boolean searchHardFail_jdk() {
    return jdkHard.matcher(randomText).find();
  }

  @Benchmark
  public boolean searchHardFail_re2j() {
    return re2jHard.matcher(randomText).find();
  }

  @Benchmark
  public boolean searchHardFail_re2ffm() {
    return re2ffmHard.matcher(randomText).find();
  }

  // ===== Find-all in scaled prose (text size scaling for existing pattern) =====

  @Benchmark
  public int findIngScaled_safere() {
    dev.eaftan.safere.Matcher m = safeFindIng.matcher(scaledProse);
    int count = 0;
    while (m.find()) {
      count++;
    }
    return count;
  }

  @Benchmark
  public int findIngScaled_jdk() {
    java.util.regex.Matcher m = jdkFindIng.matcher(scaledProse);
    int count = 0;
    while (m.find()) {
      count++;
    }
    return count;
  }

  @Benchmark
  public int findIngScaled_re2j() {
    com.google.re2j.Matcher m = re2jFindIng.matcher(scaledProse);
    int count = 0;
    while (m.find()) {
      count++;
    }
    return count;
  }

  @Benchmark
  public int findIngScaled_re2ffm() {
    dev.eaftan.safere.re2ffm.RE2FfmMatcher m = re2ffmFindIng.matcher(scaledProse);
    int count = 0;
    while (m.find()) {
      count++;
    }
    return count;
  }
}
