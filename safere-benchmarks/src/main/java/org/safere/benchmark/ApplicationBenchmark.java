// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Benchmarks for ordinary application regex workloads over small corpora. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class ApplicationBenchmark {

  private org.safere.Pattern safeUuid;
  private java.util.regex.Pattern jdkUuid;
  private com.google.re2j.Pattern re2jUuid;
  private org.safere.re2ffm.RE2FfmPattern re2ffmUuid;
  private List<String> uuidTexts;

  private org.safere.Pattern safeLogLine;
  private java.util.regex.Pattern jdkLogLine;
  private com.google.re2j.Pattern re2jLogLine;
  private org.safere.re2ffm.RE2FfmPattern re2ffmLogLine;
  private List<String> logLineTexts;

  private org.safere.Pattern safeApiRoute;
  private java.util.regex.Pattern jdkApiRoute;
  private com.google.re2j.Pattern re2jApiRoute;
  private org.safere.re2ffm.RE2FfmPattern re2ffmApiRoute;
  private List<String> apiRouteTexts;

  private org.safere.Pattern safeStackTrace;
  private java.util.regex.Pattern jdkStackTrace;
  private com.google.re2j.Pattern re2jStackTrace;
  private org.safere.re2ffm.RE2FfmPattern re2ffmStackTrace;
  private String stackTraceText;

  private org.safere.Pattern safeKeywords;
  private java.util.regex.Pattern jdkKeywords;
  private com.google.re2j.Pattern re2jKeywords;
  private org.safere.re2ffm.RE2FfmPattern re2ffmKeywords;
  private String keywordText;

  private org.safere.Pattern safeUrl;
  private java.util.regex.Pattern jdkUrl;
  private com.google.re2j.Pattern re2jUrl;
  private org.safere.re2ffm.RE2FfmPattern re2ffmUrl;
  private String urlText;

  private org.safere.Pattern safeCsv;
  private java.util.regex.Pattern jdkCsv;
  private com.google.re2j.Pattern re2jCsv;
  private org.safere.re2ffm.RE2FfmPattern re2ffmCsv;
  private String csvText;

  private org.safere.Pattern safeSecret;
  private java.util.regex.Pattern jdkSecret;
  private com.google.re2j.Pattern re2jSecret;
  private org.safere.re2ffm.RE2FfmPattern re2ffmSecret;
  private String secretText;
  private String secretReplacement;

  @Setup
  public void setup() {
    BenchmarkData data = BenchmarkData.get();

    String uuidPattern = data.getString("application.uuidValidation.pattern");
    uuidTexts = data.getStringList("application.uuidValidation.texts");
    safeUuid = org.safere.Pattern.compile(uuidPattern);
    jdkUuid = java.util.regex.Pattern.compile(uuidPattern);
    re2jUuid = com.google.re2j.Pattern.compile(uuidPattern);
    re2ffmUuid = org.safere.re2ffm.RE2FfmPattern.compile(uuidPattern);

    String logLinePattern = data.getString("application.logLineParse.pattern");
    logLineTexts = data.getStringList("application.logLineParse.texts");
    safeLogLine = org.safere.Pattern.compile(logLinePattern);
    jdkLogLine = java.util.regex.Pattern.compile(logLinePattern);
    re2jLogLine = com.google.re2j.Pattern.compile(logLinePattern);
    re2ffmLogLine = org.safere.re2ffm.RE2FfmPattern.compile(logLinePattern);

    String apiRoutePattern = data.getString("application.apiRouteMatch.pattern");
    apiRouteTexts = data.getStringList("application.apiRouteMatch.texts");
    safeApiRoute = org.safere.Pattern.compile(apiRoutePattern);
    jdkApiRoute = java.util.regex.Pattern.compile(apiRoutePattern);
    re2jApiRoute = com.google.re2j.Pattern.compile(apiRoutePattern);
    re2ffmApiRoute = org.safere.re2ffm.RE2FfmPattern.compile(apiRoutePattern);

    String stackTracePattern = data.getString("application.stackTraceExtract.pattern");
    stackTraceText = data.getString("application.stackTraceExtract.text");
    safeStackTrace = org.safere.Pattern.compile(stackTracePattern);
    jdkStackTrace = java.util.regex.Pattern.compile(stackTracePattern);
    re2jStackTrace = com.google.re2j.Pattern.compile(stackTracePattern);
    re2ffmStackTrace = org.safere.re2ffm.RE2FfmPattern.compile(stackTracePattern);

    String keywordPattern = data.getString("application.caseInsensitiveKeywords.pattern");
    keywordText = data.getString("application.caseInsensitiveKeywords.text");
    safeKeywords = org.safere.Pattern.compile(keywordPattern);
    jdkKeywords = java.util.regex.Pattern.compile(keywordPattern);
    re2jKeywords = com.google.re2j.Pattern.compile(keywordPattern);
    re2ffmKeywords = org.safere.re2ffm.RE2FfmPattern.compile(keywordPattern);

    String urlPattern = data.getString("application.urlExtraction.pattern");
    urlText = data.getString("application.urlExtraction.text");
    safeUrl = org.safere.Pattern.compile(urlPattern);
    jdkUrl = java.util.regex.Pattern.compile(urlPattern);
    re2jUrl = com.google.re2j.Pattern.compile(urlPattern);
    re2ffmUrl = org.safere.re2ffm.RE2FfmPattern.compile(urlPattern);

    String csvPattern = data.getString("application.csvFieldScan.pattern");
    csvText = data.getString("application.csvFieldScan.text");
    safeCsv = org.safere.Pattern.compile(csvPattern);
    jdkCsv = java.util.regex.Pattern.compile(csvPattern);
    re2jCsv = com.google.re2j.Pattern.compile(csvPattern);
    re2ffmCsv = org.safere.re2ffm.RE2FfmPattern.compile(csvPattern);

    String secretPattern = data.getString("application.secretRedaction.pattern");
    secretText = data.getString("application.secretRedaction.text");
    secretReplacement = data.getString("application.secretRedaction.replacement");
    safeSecret = org.safere.Pattern.compile(secretPattern);
    jdkSecret = java.util.regex.Pattern.compile(secretPattern);
    re2jSecret = com.google.re2j.Pattern.compile(secretPattern);
    re2ffmSecret = org.safere.re2ffm.RE2FfmPattern.compile(secretPattern);
  }

  @Benchmark
  public int uuidValidation_safere() {
    int count = 0;
    for (String text : uuidTexts) {
      if (safeUuid.matcher(text).matches()) {
        count++;
      }
    }
    return count;
  }

  @Benchmark
  public int uuidValidation_jdk() {
    int count = 0;
    for (String text : uuidTexts) {
      if (jdkUuid.matcher(text).matches()) {
        count++;
      }
    }
    return count;
  }

  @Benchmark
  public int uuidValidation_re2j() {
    int count = 0;
    for (String text : uuidTexts) {
      if (re2jUuid.matcher(text).matches()) {
        count++;
      }
    }
    return count;
  }

  @Benchmark
  public int uuidValidation_re2ffm() {
    int count = 0;
    for (String text : uuidTexts) {
      if (re2ffmUuid.matcher(text).matches()) {
        count++;
      }
    }
    return count;
  }

  @Benchmark
  public int logLineParse_safere() {
    int count = 0;
    for (String text : logLineTexts) {
      org.safere.Matcher matcher = safeLogLine.matcher(text);
      if (matcher.matches()) {
        count += matcher.group(2).length() + matcher.group(3).length();
      }
    }
    return count;
  }

  @Benchmark
  public int logLineParse_jdk() {
    int count = 0;
    for (String text : logLineTexts) {
      java.util.regex.Matcher matcher = jdkLogLine.matcher(text);
      if (matcher.matches()) {
        count += matcher.group(2).length() + matcher.group(3).length();
      }
    }
    return count;
  }

  @Benchmark
  public int logLineParse_re2j() {
    int count = 0;
    for (String text : logLineTexts) {
      com.google.re2j.Matcher matcher = re2jLogLine.matcher(text);
      if (matcher.matches()) {
        count += matcher.group(2).length() + matcher.group(3).length();
      }
    }
    return count;
  }

  @Benchmark
  public int logLineParse_re2ffm() {
    int count = 0;
    for (String text : logLineTexts) {
      org.safere.re2ffm.RE2FfmMatcher matcher = re2ffmLogLine.matcher(text);
      if (matcher.matches()) {
        count += matcher.group(2).length() + matcher.group(3).length();
      }
    }
    return count;
  }

  @Benchmark
  public int apiRouteMatch_safere() {
    int count = 0;
    for (String text : apiRouteTexts) {
      org.safere.Matcher matcher = safeApiRoute.matcher(text);
      if (matcher.matches()) {
        count += matcher.group(1).length() + matcher.group(2).length();
      }
    }
    return count;
  }

  @Benchmark
  public int apiRouteMatch_jdk() {
    int count = 0;
    for (String text : apiRouteTexts) {
      java.util.regex.Matcher matcher = jdkApiRoute.matcher(text);
      if (matcher.matches()) {
        count += matcher.group(1).length() + matcher.group(2).length();
      }
    }
    return count;
  }

  @Benchmark
  public int apiRouteMatch_re2j() {
    int count = 0;
    for (String text : apiRouteTexts) {
      com.google.re2j.Matcher matcher = re2jApiRoute.matcher(text);
      if (matcher.matches()) {
        count += matcher.group(1).length() + matcher.group(2).length();
      }
    }
    return count;
  }

  @Benchmark
  public int apiRouteMatch_re2ffm() {
    int count = 0;
    for (String text : apiRouteTexts) {
      org.safere.re2ffm.RE2FfmMatcher matcher = re2ffmApiRoute.matcher(text);
      if (matcher.matches()) {
        count += matcher.group(1).length() + matcher.group(2).length();
      }
    }
    return count;
  }

  @Benchmark
  public int stackTraceExtract_safere() {
    org.safere.Matcher matcher = safeStackTrace.matcher(stackTraceText);
    int count = 0;
    while (matcher.find()) {
      count += matcher.group(1).length() + matcher.group(4).length();
    }
    return count;
  }

  @Benchmark
  public int stackTraceExtract_jdk() {
    java.util.regex.Matcher matcher = jdkStackTrace.matcher(stackTraceText);
    int count = 0;
    while (matcher.find()) {
      count += matcher.group(1).length() + matcher.group(4).length();
    }
    return count;
  }

  @Benchmark
  public int stackTraceExtract_re2j() {
    com.google.re2j.Matcher matcher = re2jStackTrace.matcher(stackTraceText);
    int count = 0;
    while (matcher.find()) {
      count += matcher.group(1).length() + matcher.group(4).length();
    }
    return count;
  }

  @Benchmark
  public int stackTraceExtract_re2ffm() {
    org.safere.re2ffm.RE2FfmMatcher matcher = re2ffmStackTrace.matcher(stackTraceText);
    int count = 0;
    while (matcher.find()) {
      count += matcher.group(1).length() + matcher.group(4).length();
    }
    return count;
  }

  @Benchmark
  public int caseInsensitiveKeywords_safere() {
    org.safere.Matcher matcher = safeKeywords.matcher(keywordText);
    int count = 0;
    while (matcher.find()) {
      count++;
    }
    return count;
  }

  @Benchmark
  public int caseInsensitiveKeywords_jdk() {
    java.util.regex.Matcher matcher = jdkKeywords.matcher(keywordText);
    int count = 0;
    while (matcher.find()) {
      count++;
    }
    return count;
  }

  @Benchmark
  public int caseInsensitiveKeywords_re2j() {
    com.google.re2j.Matcher matcher = re2jKeywords.matcher(keywordText);
    int count = 0;
    while (matcher.find()) {
      count++;
    }
    return count;
  }

  @Benchmark
  public int caseInsensitiveKeywords_re2ffm() {
    org.safere.re2ffm.RE2FfmMatcher matcher = re2ffmKeywords.matcher(keywordText);
    int count = 0;
    while (matcher.find()) {
      count++;
    }
    return count;
  }

  @Benchmark
  public int urlExtraction_safere() {
    org.safere.Matcher matcher = safeUrl.matcher(urlText);
    int count = 0;
    while (matcher.find()) {
      count += matcher.group().length();
    }
    return count;
  }

  @Benchmark
  public int urlExtraction_jdk() {
    java.util.regex.Matcher matcher = jdkUrl.matcher(urlText);
    int count = 0;
    while (matcher.find()) {
      count += matcher.group().length();
    }
    return count;
  }

  @Benchmark
  public int urlExtraction_re2j() {
    com.google.re2j.Matcher matcher = re2jUrl.matcher(urlText);
    int count = 0;
    while (matcher.find()) {
      count += matcher.group().length();
    }
    return count;
  }

  @Benchmark
  public int urlExtraction_re2ffm() {
    org.safere.re2ffm.RE2FfmMatcher matcher = re2ffmUrl.matcher(urlText);
    int count = 0;
    while (matcher.find()) {
      count += matcher.group().length();
    }
    return count;
  }

  @Benchmark
  public int csvFieldScan_safere() {
    org.safere.Matcher matcher = safeCsv.matcher(csvText);
    int count = 0;
    while (matcher.find()) {
      count += matcher.group().length();
    }
    return count;
  }

  @Benchmark
  public int csvFieldScan_jdk() {
    java.util.regex.Matcher matcher = jdkCsv.matcher(csvText);
    int count = 0;
    while (matcher.find()) {
      count += matcher.group().length();
    }
    return count;
  }

  @Benchmark
  public int csvFieldScan_re2j() {
    com.google.re2j.Matcher matcher = re2jCsv.matcher(csvText);
    int count = 0;
    while (matcher.find()) {
      count += matcher.group().length();
    }
    return count;
  }

  @Benchmark
  public int csvFieldScan_re2ffm() {
    org.safere.re2ffm.RE2FfmMatcher matcher = re2ffmCsv.matcher(csvText);
    int count = 0;
    while (matcher.find()) {
      count += matcher.group().length();
    }
    return count;
  }

  @Benchmark
  public String secretRedaction_safere() {
    return safeSecret.matcher(secretText).replaceAll(secretReplacement);
  }

  @Benchmark
  public String secretRedaction_jdk() {
    return jdkSecret.matcher(secretText).replaceAll(secretReplacement);
  }

  @Benchmark
  public String secretRedaction_re2j() {
    return re2jSecret.matcher(secretText).replaceAll(secretReplacement);
  }

  @Benchmark
  public String secretRedaction_re2ffm() {
    return re2ffmSecret.matcher(secretText).replaceAll(secretReplacement);
  }
}
