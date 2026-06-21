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

/** Scaling benchmarks for the application-style workloads discussed in issue 481. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class Issue481ScalingBenchmark {

  @Param({"128", "1024", "10240", "102400", "1048576"})
  private int textSize;

  private String splitText;
  private String blockText;
  private String blockNegativeText;
  private String tagText;
  private String tagNegativeText;
  private String schemeText;
  private String schemeNegativeText;

  private org.safere.Pattern safereSplitW;
  private org.safere.Pattern safereBlock;
  private org.safere.Pattern safereTag;
  private org.safere.Pattern safereScheme;

  private java.util.regex.Pattern jdkSplitW;
  private java.util.regex.Pattern jdkBlock;
  private java.util.regex.Pattern jdkTag;
  private java.util.regex.Pattern jdkScheme;

  private com.google.re2j.Pattern re2jSplitW;
  private com.google.re2j.Pattern re2jBlock;
  private com.google.re2j.Pattern re2jTag;
  private com.google.re2j.Pattern re2jScheme;

  private org.safere.re2ffm.RE2FfmPattern re2ffmSplitW;
  private org.safere.re2ffm.RE2FfmPattern re2ffmBlock;
  private org.safere.re2ffm.RE2FfmPattern re2ffmTag;
  private org.safere.re2ffm.RE2FfmPattern re2ffmScheme;

  @Setup
  public void setup() {
    BenchmarkData data = BenchmarkData.get();

    String splitPattern = data.getString("issue481Scaling.splitW.pattern");
    String blockPattern = data.getString("issue481Scaling.block.pattern");
    String tagPattern = data.getString("issue481Scaling.tag.pattern");
    String schemePattern = data.getString("issue481Scaling.scheme.pattern");

    splitText = repeatToSize(data.getString("issue481Scaling.splitW.unit"), textSize);
    blockText =
        surroundToSize(
            data.getString("issue481Scaling.block.prefix"),
            data.getString("issue481Scaling.block.unit"),
            data.getString("issue481Scaling.block.suffix"),
            textSize);
    blockNegativeText =
        surroundToSize(
            data.getString("issue481Scaling.block.prefix"),
            data.getString("issue481Scaling.block.unit"),
            data.getString("issue481Scaling.block.negativeSuffix"),
            textSize);
    tagText =
        suffixMatchToSize(
            data.getString("issue481Scaling.tag.prefixUnit"),
            data.getString("issue481Scaling.tag.match"),
            textSize);
    tagNegativeText =
        suffixMatchToSize(
            data.getString("issue481Scaling.tag.prefixUnit"),
            data.getString("issue481Scaling.tag.negativeMatch"),
            textSize);
    schemeText =
        suffixMatchToSize(
            data.getString("issue481Scaling.scheme.prefixUnit"),
            data.getString("issue481Scaling.scheme.match"),
            textSize);
    schemeNegativeText =
        suffixMatchToSize(
            data.getString("issue481Scaling.scheme.prefixUnit"),
            data.getString("issue481Scaling.scheme.negativeMatch"),
            textSize);

    safereSplitW = org.safere.Pattern.compile(splitPattern);
    safereBlock = org.safere.Pattern.compile(blockPattern);
    safereTag = org.safere.Pattern.compile(tagPattern);
    safereScheme = org.safere.Pattern.compile(schemePattern);

    jdkSplitW = java.util.regex.Pattern.compile(splitPattern);
    jdkBlock = java.util.regex.Pattern.compile(blockPattern);
    jdkTag = java.util.regex.Pattern.compile(tagPattern);
    jdkScheme = java.util.regex.Pattern.compile(schemePattern);

    re2jSplitW = com.google.re2j.Pattern.compile(splitPattern);
    re2jBlock = com.google.re2j.Pattern.compile(blockPattern);
    re2jTag = com.google.re2j.Pattern.compile(tagPattern);
    re2jScheme = com.google.re2j.Pattern.compile(schemePattern);

    re2ffmSplitW = org.safere.re2ffm.RE2FfmPattern.compile(splitPattern);
    re2ffmBlock = org.safere.re2ffm.RE2FfmPattern.compile(blockPattern);
    re2ffmTag = org.safere.re2ffm.RE2FfmPattern.compile(tagPattern);
    re2ffmScheme = org.safere.re2ffm.RE2FfmPattern.compile(schemePattern);
  }

  @Benchmark
  public int splitWords_safere() {
    return splitLengthSum(safereSplitW.split(splitText));
  }

  @Benchmark
  public int splitWords_jdk() {
    return splitLengthSum(jdkSplitW.split(splitText));
  }

  @Benchmark
  public int splitWords_re2j() {
    return splitLengthSum(re2jSplitW.split(splitText));
  }

  @Benchmark
  public int splitWords_re2ffm() {
    return splitLengthSum(re2ffmSplitW.split(splitText));
  }

  @Benchmark
  public boolean blockFind_safere() {
    return safereBlock.matcher(blockText).find();
  }

  @Benchmark
  public boolean blockFind_jdk() {
    return jdkBlock.matcher(blockText).find();
  }

  @Benchmark
  public boolean blockFind_re2j() {
    return re2jBlock.matcher(blockText).find();
  }

  @Benchmark
  public boolean blockFind_re2ffm() {
    return re2ffmBlock.matcher(blockText).find();
  }

  @Benchmark
  public boolean blockFindNegative_safere() {
    return safereBlock.matcher(blockNegativeText).find();
  }

  @Benchmark
  public boolean blockFindNegative_jdk() {
    return jdkBlock.matcher(blockNegativeText).find();
  }

  @Benchmark
  public boolean blockFindNegative_re2j() {
    return re2jBlock.matcher(blockNegativeText).find();
  }

  @Benchmark
  public boolean blockFindNegative_re2ffm() {
    return re2ffmBlock.matcher(blockNegativeText).find();
  }

  @Benchmark
  public boolean tagFind_safere() {
    return safereTag.matcher(tagText).find();
  }

  @Benchmark
  public boolean tagFind_jdk() {
    return jdkTag.matcher(tagText).find();
  }

  @Benchmark
  public boolean tagFind_re2j() {
    return re2jTag.matcher(tagText).find();
  }

  @Benchmark
  public boolean tagFind_re2ffm() {
    return re2ffmTag.matcher(tagText).find();
  }

  @Benchmark
  public boolean tagFindNegative_safere() {
    return safereTag.matcher(tagNegativeText).find();
  }

  @Benchmark
  public boolean tagFindNegative_jdk() {
    return jdkTag.matcher(tagNegativeText).find();
  }

  @Benchmark
  public boolean tagFindNegative_re2j() {
    return re2jTag.matcher(tagNegativeText).find();
  }

  @Benchmark
  public boolean tagFindNegative_re2ffm() {
    return re2ffmTag.matcher(tagNegativeText).find();
  }

  @Benchmark
  public int schemeExtract_safere() {
    org.safere.Matcher matcher = safereScheme.matcher(schemeText);
    int sum = 0;
    while (matcher.find()) {
      sum += matcher.group(1).length();
      sum += matcher.group(2).length();
    }
    return sum;
  }

  @Benchmark
  public int schemeExtract_jdk() {
    java.util.regex.Matcher matcher = jdkScheme.matcher(schemeText);
    int sum = 0;
    while (matcher.find()) {
      sum += matcher.group(1).length();
      sum += matcher.group(2).length();
    }
    return sum;
  }

  @Benchmark
  public int schemeExtract_re2j() {
    com.google.re2j.Matcher matcher = re2jScheme.matcher(schemeText);
    int sum = 0;
    while (matcher.find()) {
      sum += matcher.group(1).length();
      sum += matcher.group(2).length();
    }
    return sum;
  }

  @Benchmark
  public int schemeExtract_re2ffm() {
    org.safere.re2ffm.RE2FfmMatcher matcher = re2ffmScheme.matcher(schemeText);
    int sum = 0;
    while (matcher.find()) {
      sum += matcher.group(1).length();
      sum += matcher.group(2).length();
    }
    return sum;
  }

  @Benchmark
  public boolean schemeFindNegative_safere() {
    return safereScheme.matcher(schemeNegativeText).find();
  }

  @Benchmark
  public boolean schemeFindNegative_jdk() {
    return jdkScheme.matcher(schemeNegativeText).find();
  }

  @Benchmark
  public boolean schemeFindNegative_re2j() {
    return re2jScheme.matcher(schemeNegativeText).find();
  }

  @Benchmark
  public boolean schemeFindNegative_re2ffm() {
    return re2ffmScheme.matcher(schemeNegativeText).find();
  }

  private static String repeatToSize(String unit, int size) {
    StringBuilder sb = new StringBuilder(size + unit.length());
    while (sb.length() < size) {
      sb.append(unit);
    }
    return sb.substring(0, size);
  }

  private static String surroundToSize(String prefix, String unit, String suffix, int size) {
    int targetBodySize = Math.max(0, size - prefix.length() - suffix.length());
    return prefix + repeatToSize(unit, targetBodySize) + suffix;
  }

  private static String suffixMatchToSize(String prefixUnit, String match, int size) {
    int targetPrefixSize = Math.max(0, size - match.length());
    return repeatToSize(prefixUnit, targetPrefixSize) + match;
  }

  private static int splitLengthSum(String[] parts) {
    int sum = parts.length;
    for (String part : parts) {
      sum += part.length();
    }
    return sum;
  }
}
