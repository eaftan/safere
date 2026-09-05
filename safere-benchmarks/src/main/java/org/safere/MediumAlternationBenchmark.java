// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
 * Consolidated benchmark across alternation acceleration tiers:
 *
 * <ul>
 *   <li>Tier 1: Direct SIMD Equality (K <= 4)
 *   <li>Tier 2: Single-Group Teddy SIMD (5 <= K <= 32)
 *   <li>Dense Fallback: Lazy DFA (K > 32, dense roots bypassing AC)
 *   <li>Tier 3: Vector-Accelerated Aho-Corasick (K > 32, sparse roots)
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Thread)
public class MediumAlternationBenchmark {

  @Param({"1024", "65536"})
  public int haystackLength;

  @Param({"TIER1_DIRECT_4", "TIER2_TEDDY_16", "DENSE_FALLBACK_36", "TIER3_AC_ROUTES_256"})
  public String workload;

  @Param({"ABSENT", "MATCH_LATE"})
  public String matchMode;

  private Pattern saferePattern;
  private java.util.regex.Pattern jdkPattern;
  private String stringInput;
  private Utf8Input utf8Input;

  private static final String[] HTTP_METHODS_4 = {"GET", "POST", "PUT", "DELETE"};

  private static final String[] HTTP_HEADERS = {
    "Accept",
    "Accept-Charset",
    "Accept-Encoding",
    "Accept-Language",
    "Accept-Ranges",
    "Age",
    "Allow",
    "Authorization",
    "Cache-Control",
    "Connection",
    "Content-Disposition",
    "Content-Encoding",
    "Content-Language",
    "Content-Length",
    "Content-Location",
    "Content-Range",
    "Content-Type",
    "Cookie",
    "Date",
    "ETag",
    "Expect",
    "Expires",
    "From",
    "Host",
    "If-Match",
    "If-Modified-Since",
    "If-None-Match",
    "If-Range",
    "If-Unmodified-Since",
    "Last-Modified",
    "Location",
    "Origin",
    "Pragma",
    "Proxy-Authenticate",
    "Proxy-Authorization",
    "Range",
    "Referer",
    "Retry-After",
    "Server",
    "Set-Cookie",
    "Trailer",
    "Transfer-Encoding",
    "Upgrade",
    "User-Agent",
    "Vary",
    "Via",
    "Warning",
    "WWW-Authenticate"
  };

  private static String[] generateUrlRoutes256() {
    String[] prefixes = {
      "/api/v1/users/", "/api/v1/accounts/", "/api/v2/orders/", "/api/v2/billing/",
      "/api/v3/reports/", "/api/v3/analytics/", "/auth/oauth2/", "/static/assets/"
    };
    String[] suffixes = {
      "/profile", "/settings", "/list", "/create", "/delete", "/update", "/status", "/export"
    };
    String[] routes = new String[256];
    for (int i = 0; i < 256; i++) {
      String p = prefixes[i % prefixes.length];
      String s = suffixes[(i / prefixes.length) % suffixes.length];
      routes[i] = p + "item" + i + s;
    }
    return routes;
  }

  @Setup
  public void setup() {
    String[] keywords =
        switch (workload) {
          case "TIER1_DIRECT_4", "DIRECT_SIMD_4" -> HTTP_METHODS_4;
          case "TIER2_TEDDY_16", "TEDDY_16" -> Arrays.copyOfRange(HTTP_HEADERS, 0, 16);
          case "DENSE_FALLBACK_36", "AC_HTTP_HEADERS_36", "HTTP_HEADERS_36" ->
              Arrays.copyOfRange(HTTP_HEADERS, 0, 36);
          case "TIER3_AC_ROUTES_256", "URL_ROUTES_256" -> generateUrlRoutes256();
          default -> throw new IllegalArgumentException("Unknown workload: " + workload);
        };

    StringBuilder regexBuilder = new StringBuilder();
    for (int i = 0; i < keywords.length; i++) {
      if (i > 0) {
        regexBuilder.append("|");
      }
      regexBuilder.append(java.util.regex.Pattern.quote(keywords[i]));
    }
    String regex = regexBuilder.toString();

    saferePattern = Pattern.compile(regex);
    jdkPattern = java.util.regex.Pattern.compile(regex);

    Random rng = new Random(42);
    byte[] chars = new byte[haystackLength];
    for (int i = 0; i < haystackLength; i++) {
      chars[i] = (byte) ('a' + rng.nextInt(26));
    }

    if (matchMode.equals("MATCH_LATE")) {
      String target = keywords[keywords.length / 2];
      byte[] targetBytes = target.getBytes(StandardCharsets.UTF_8);
      int insertPos =
          Math.max(0, haystackLength - targetBytes.length - (haystackLength > 64 ? 8 : 0));
      int copyLen = Math.min(targetBytes.length, haystackLength - insertPos);
      System.arraycopy(targetBytes, 0, chars, insertPos, copyLen);
    }

    stringInput = new String(chars, StandardCharsets.UTF_8);
    utf8Input = Utf8Input.trusted(chars);
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
