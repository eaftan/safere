// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

/**
 * Benchmark for large-scale dictionary alternations (256 <= K <= 4096), representative of URL route
 * tables, security rule signatures, and dictionary wordlists. Targets Vectorized Aho-Corasick
 * acceleration.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Thread)
public class LargeDictionaryAlternationBenchmark {

  @Param({"1024", "65536"})
  public int haystackLength;

  @Param({"URL_ROUTES_256", "SECURITY_SIGNATURES_1024", "DICTIONARY_TOKENS_4096"})
  public String workload;

  private Pattern saferePattern;
  private java.util.regex.Pattern jdkPattern;
  private String stringInput;
  private Utf8Input utf8Input;

  @Setup
  public void setup() {
    List<String> keywords = generateKeywords(workload);

    StringBuilder regexBuilder = new StringBuilder();
    for (int i = 0; i < keywords.size(); i++) {
      if (i > 0) {
        regexBuilder.append("|");
      }
      regexBuilder.append(java.util.regex.Pattern.quote(keywords.get(i)));
    }
    String regex = regexBuilder.toString();

    saferePattern = Pattern.compile(regex);
    jdkPattern = java.util.regex.Pattern.compile(regex);

    Random rng = new Random(42);
    byte[] chars = new byte[haystackLength];
    for (int i = 0; i < haystackLength; i++) {
      chars[i] = (byte) ('a' + rng.nextInt(26));
    }
    stringInput = new String(chars, StandardCharsets.UTF_8);
    utf8Input = Utf8Input.trusted(chars);
  }

  private static List<String> generateKeywords(String workload) {
    List<String> list = new ArrayList<>();
    Random rng = new Random(12345);

    switch (workload) {
      case "URL_ROUTES_256" -> {
        String[] prefixes = {
          "/api/v1/users/", "/api/v1/accounts/", "/api/v2/orders/", "/api/v2/billing/",
          "/api/v3/reports/", "/api/v3/analytics/", "/auth/oauth2/", "/static/assets/"
        };
        String[] suffixes = {
          "/profile", "/settings", "/list", "/create", "/delete", "/update", "/status", "/export"
        };
        for (int i = 0; i < 256; i++) {
          String p = prefixes[i % prefixes.length];
          String s = suffixes[(i / prefixes.length) % suffixes.length];
          list.add(p + "item" + i + s);
        }
      }
      case "SECURITY_SIGNATURES_1024" -> {
        String[] bases = {
          "eval(",
          "union select",
          "document.cookie",
          "exec(",
          "system(",
          "<script",
          "../",
          "etc/passwd",
          "cmd.exe",
          "/bin/sh",
          "chmod ",
          "wget ",
          "curl "
        };
        for (int i = 0; i < 1024; i++) {
          String base = bases[i % bases.length];
          list.add(base + "_sig_" + i);
        }
      }
      case "DICTIONARY_TOKENS_4096" -> {
        for (int i = 0; i < 4096; i++) {
          StringBuilder sb = new StringBuilder();
          int len = 4 + (i % 6);
          for (int j = 0; j < len; j++) {
            sb.append((char) ('a' + rng.nextInt(26)));
          }
          list.add(sb.toString() + i);
        }
      }
      default -> throw new IllegalArgumentException("Unknown workload: " + workload);
    }
    return list;
  }

  @Benchmark
  public int safereString() {
    Matcher m = saferePattern.matcher(stringInput);
    return m.find() ? m.start() : -1;
  }

  @Benchmark
  public int safereUtf8() {
    Utf8Matcher m = saferePattern.matcher(utf8Input);
    return m.find() ? m.start() : -1;
  }

  @Benchmark
  public int jdkString() {
    java.util.regex.Matcher m = jdkPattern.matcher(stringInput);
    return m.find() ? m.start() : -1;
  }
}
