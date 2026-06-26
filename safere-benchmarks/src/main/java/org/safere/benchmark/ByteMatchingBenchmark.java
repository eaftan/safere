// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class ByteMatchingBenchmark {

  private org.safere.Pattern safeEmail;
  private java.util.regex.Pattern jdkEmail;

  private String emailText;
  private byte[] emailBytes;

  private org.safere.Pattern safeFindIng;
  private java.util.regex.Pattern jdkFindIng;

  private String proseText;
  private byte[] proseBytes;

  @Setup
  public void setup() {
    BenchmarkData data = BenchmarkData.get();

    // Email matching (short-to-medium text search)
    String emailPattern = data.getString("regex.emailFind.pattern");
    emailText = data.getString("regex.emailFind.text");
    emailBytes = emailText.getBytes(StandardCharsets.UTF_8);
    safeEmail = org.safere.Pattern.compile(emailPattern);
    jdkEmail = java.util.regex.Pattern.compile(emailPattern);

    // Finding all -ing words in long prose text (heavy search loop)
    String ingPattern = data.getString("regex.findInText.pattern");
    proseText = data.getString("regex.findInText.text");
    proseBytes = proseText.getBytes(StandardCharsets.UTF_8);
    safeFindIng = org.safere.Pattern.compile(ingPattern);
    jdkFindIng = java.util.regex.Pattern.compile(ingPattern);
  }

  // ===== Email Find (Single match check) =====

  @Benchmark
  public boolean emailFind_safere_bytes() {
    return safeEmail.matcher(emailBytes).find();
  }

  @Benchmark
  public boolean emailFind_safere_string() {
    return safeEmail.matcher(emailText).find();
  }

  @Benchmark
  public boolean emailFind_jdk_string() {
    return jdkEmail.matcher(emailText).find();
  }

  @Benchmark
  public boolean emailFind_jdk_bytes_decode() {
    String decoded = new String(emailBytes, StandardCharsets.UTF_8);
    return jdkEmail.matcher(decoded).find();
  }

  // ===== Find In Text (Sequential find loop on long text) =====

  @Benchmark
  public int findInText_safere_bytes() {
    org.safere.Matcher m = safeFindIng.matcher(proseBytes);
    int count = 0;
    while (m.find()) {
      count++;
    }
    return count;
  }

  @Benchmark
  public int findInText_safere_string() {
    org.safere.Matcher m = safeFindIng.matcher(proseText);
    int count = 0;
    while (m.find()) {
      count++;
    }
    return count;
  }

  @Benchmark
  public int findInText_jdk_string() {
    java.util.regex.Matcher m = jdkFindIng.matcher(proseText);
    int count = 0;
    while (m.find()) {
      count++;
    }
    return count;
  }

  @Benchmark
  public int findInText_jdk_bytes_decode() {
    String decoded = new String(proseBytes, StandardCharsets.UTF_8);
    java.util.regex.Matcher m = jdkFindIng.matcher(decoded);
    int count = 0;
    while (m.find()) {
      count++;
    }
    return count;
  }
}
