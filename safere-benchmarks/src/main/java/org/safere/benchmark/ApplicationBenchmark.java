// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.HashMap;
import java.util.Map;
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

  private Map<String, ApplicationCase> cases;
  private Map<String, org.safere.Pattern> saferePatterns;
  private Map<String, java.util.regex.Pattern> jdkPatterns;
  private Map<String, com.google.re2j.Pattern> re2jPatterns;
  private Map<String, org.safere.re2ffm.RE2FfmPattern> re2ffmPatterns;

  @Setup
  public void setup() {
    cases = BenchmarkData.get().getApplicationCases();
    saferePatterns = new HashMap<>();
    jdkPatterns = new HashMap<>();
    re2jPatterns = new HashMap<>();
    re2ffmPatterns = new HashMap<>();

    for (ApplicationCase appCase : cases.values()) {
      org.safere.Pattern saferePattern = org.safere.Pattern.compile(appCase.pattern);
      saferePatterns.put(appCase.name, saferePattern);
      jdkPatterns.put(appCase.name, java.util.regex.Pattern.compile(appCase.pattern));
      re2jPatterns.put(appCase.name, com.google.re2j.Pattern.compile(appCase.pattern));
      re2ffmPatterns.put(appCase.name, org.safere.re2ffm.RE2FfmPattern.compile(appCase.pattern));
      validateExpected(appCase);
    }
  }

  @Benchmark
  public int uuidValidation_safere() {
    return runSafereInt("uuidValidation");
  }

  @Benchmark
  public int uuidValidation_jdk() {
    return runJdkInt("uuidValidation");
  }

  @Benchmark
  public int uuidValidation_re2j() {
    return runRe2jInt("uuidValidation");
  }

  @Benchmark
  public int uuidValidation_re2ffm() {
    return runRe2ffmInt("uuidValidation");
  }

  @Benchmark
  public int logLineParse_safere() {
    return runSafereInt("logLineParse");
  }

  @Benchmark
  public int logLineParse_jdk() {
    return runJdkInt("logLineParse");
  }

  @Benchmark
  public int logLineParse_re2j() {
    return runRe2jInt("logLineParse");
  }

  @Benchmark
  public int logLineParse_re2ffm() {
    return runRe2ffmInt("logLineParse");
  }

  @Benchmark
  public int apiRouteMatch_safere() {
    return runSafereInt("apiRouteMatch");
  }

  @Benchmark
  public int apiRouteMatch_jdk() {
    return runJdkInt("apiRouteMatch");
  }

  @Benchmark
  public int apiRouteMatch_re2j() {
    return runRe2jInt("apiRouteMatch");
  }

  @Benchmark
  public int apiRouteMatch_re2ffm() {
    return runRe2ffmInt("apiRouteMatch");
  }

  @Benchmark
  public int stackTraceExtract_safere() {
    return runSafereInt("stackTraceExtract");
  }

  @Benchmark
  public int stackTraceExtract_jdk() {
    return runJdkInt("stackTraceExtract");
  }

  @Benchmark
  public int stackTraceExtract_re2j() {
    return runRe2jInt("stackTraceExtract");
  }

  @Benchmark
  public int stackTraceExtract_re2ffm() {
    return runRe2ffmInt("stackTraceExtract");
  }

  @Benchmark
  public int caseInsensitiveKeywords_safere() {
    return runSafereInt("caseInsensitiveKeywords");
  }

  @Benchmark
  public int caseInsensitiveKeywords_jdk() {
    return runJdkInt("caseInsensitiveKeywords");
  }

  @Benchmark
  public int caseInsensitiveKeywords_re2j() {
    return runRe2jInt("caseInsensitiveKeywords");
  }

  @Benchmark
  public int caseInsensitiveKeywords_re2ffm() {
    return runRe2ffmInt("caseInsensitiveKeywords");
  }

  @Benchmark
  public int urlExtraction_safere() {
    return runSafereInt("urlExtraction");
  }

  @Benchmark
  public int urlExtraction_jdk() {
    return runJdkInt("urlExtraction");
  }

  @Benchmark
  public int urlExtraction_re2j() {
    return runRe2jInt("urlExtraction");
  }

  @Benchmark
  public int urlExtraction_re2ffm() {
    return runRe2ffmInt("urlExtraction");
  }

  @Benchmark
  public int csvFieldScan_safere() {
    return runSafereInt("csvFieldScan");
  }

  @Benchmark
  public int csvFieldScan_jdk() {
    return runJdkInt("csvFieldScan");
  }

  @Benchmark
  public int csvFieldScan_re2j() {
    return runRe2jInt("csvFieldScan");
  }

  @Benchmark
  public int csvFieldScan_re2ffm() {
    return runRe2ffmInt("csvFieldScan");
  }

  @Benchmark
  public String secretRedaction_safere() {
    return runSafereString("secretRedaction");
  }

  @Benchmark
  public String secretRedaction_jdk() {
    return runJdkString("secretRedaction");
  }

  @Benchmark
  public String secretRedaction_re2j() {
    return runRe2jString("secretRedaction");
  }

  @Benchmark
  public String secretRedaction_re2ffm() {
    return runRe2ffmString("secretRedaction");
  }

  private int runSafereInt(String name) {
    ApplicationCase appCase = cases.get(name);
    return runSafereInt(appCase, saferePatterns.get(name));
  }

  private int runJdkInt(String name) {
    ApplicationCase appCase = cases.get(name);
    return runJdkInt(appCase, jdkPatterns.get(name));
  }

  private int runRe2jInt(String name) {
    ApplicationCase appCase = cases.get(name);
    return runRe2jInt(appCase, re2jPatterns.get(name));
  }

  private int runRe2ffmInt(String name) {
    ApplicationCase appCase = cases.get(name);
    return runRe2ffmInt(appCase, re2ffmPatterns.get(name));
  }

  private String runSafereString(String name) {
    ApplicationCase appCase = cases.get(name);
    return saferePatterns.get(name).matcher(appCase.text).replaceAll(appCase.replacement);
  }

  private String runJdkString(String name) {
    ApplicationCase appCase = cases.get(name);
    return jdkPatterns.get(name).matcher(appCase.text).replaceAll(appCase.replacement);
  }

  private String runRe2jString(String name) {
    ApplicationCase appCase = cases.get(name);
    return re2jPatterns.get(name).matcher(appCase.text).replaceAll(appCase.replacement);
  }

  private String runRe2ffmString(String name) {
    ApplicationCase appCase = cases.get(name);
    return re2ffmPatterns.get(name).matcher(appCase.text).replaceAll(appCase.replacement);
  }

  private static int runSafereInt(ApplicationCase appCase, org.safere.Pattern pattern) {
    return switch (appCase.op) {
      case "matchesCorpus" -> {
        int count = 0;
        for (String text : appCase.texts) {
          if (pattern.matcher(text).matches()) {
            count++;
          }
        }
        yield count;
      }
      case "matchesGroupLengthSum" -> {
        int count = 0;
        for (String text : appCase.texts) {
          org.safere.Matcher matcher = pattern.matcher(text);
          if (matcher.matches()) {
            count += groupLengthSum(matcher::group, appCase.groups);
          }
        }
        yield count;
      }
      case "findAllCount" -> {
        org.safere.Matcher matcher = pattern.matcher(appCase.text);
        int count = 0;
        while (matcher.find()) {
          count++;
        }
        yield count;
      }
      case "findAllLengthSum" -> {
        org.safere.Matcher matcher = pattern.matcher(appCase.text);
        int count = 0;
        while (matcher.find()) {
          count += matcher.group().length();
        }
        yield count;
      }
      case "findAllGroupLengthSum" -> {
        org.safere.Matcher matcher = pattern.matcher(appCase.text);
        int count = 0;
        while (matcher.find()) {
          count += groupLengthSum(matcher::group, appCase.groups);
        }
        yield count;
      }
      default -> throw new IllegalArgumentException("String op used as int op: " + appCase.op);
    };
  }

  private static int runJdkInt(ApplicationCase appCase, java.util.regex.Pattern pattern) {
    return switch (appCase.op) {
      case "matchesCorpus" -> {
        int count = 0;
        for (String text : appCase.texts) {
          if (pattern.matcher(text).matches()) {
            count++;
          }
        }
        yield count;
      }
      case "matchesGroupLengthSum" -> {
        int count = 0;
        for (String text : appCase.texts) {
          java.util.regex.Matcher matcher = pattern.matcher(text);
          if (matcher.matches()) {
            count += groupLengthSum(matcher::group, appCase.groups);
          }
        }
        yield count;
      }
      case "findAllCount" -> {
        java.util.regex.Matcher matcher = pattern.matcher(appCase.text);
        int count = 0;
        while (matcher.find()) {
          count++;
        }
        yield count;
      }
      case "findAllLengthSum" -> {
        java.util.regex.Matcher matcher = pattern.matcher(appCase.text);
        int count = 0;
        while (matcher.find()) {
          count += matcher.group().length();
        }
        yield count;
      }
      case "findAllGroupLengthSum" -> {
        java.util.regex.Matcher matcher = pattern.matcher(appCase.text);
        int count = 0;
        while (matcher.find()) {
          count += groupLengthSum(matcher::group, appCase.groups);
        }
        yield count;
      }
      default -> throw new IllegalArgumentException("String op used as int op: " + appCase.op);
    };
  }

  private static int runRe2jInt(ApplicationCase appCase, com.google.re2j.Pattern pattern) {
    return switch (appCase.op) {
      case "matchesCorpus" -> {
        int count = 0;
        for (String text : appCase.texts) {
          if (pattern.matcher(text).matches()) {
            count++;
          }
        }
        yield count;
      }
      case "matchesGroupLengthSum" -> {
        int count = 0;
        for (String text : appCase.texts) {
          com.google.re2j.Matcher matcher = pattern.matcher(text);
          if (matcher.matches()) {
            count += groupLengthSum(matcher::group, appCase.groups);
          }
        }
        yield count;
      }
      case "findAllCount" -> {
        com.google.re2j.Matcher matcher = pattern.matcher(appCase.text);
        int count = 0;
        while (matcher.find()) {
          count++;
        }
        yield count;
      }
      case "findAllLengthSum" -> {
        com.google.re2j.Matcher matcher = pattern.matcher(appCase.text);
        int count = 0;
        while (matcher.find()) {
          count += matcher.group().length();
        }
        yield count;
      }
      case "findAllGroupLengthSum" -> {
        com.google.re2j.Matcher matcher = pattern.matcher(appCase.text);
        int count = 0;
        while (matcher.find()) {
          count += groupLengthSum(matcher::group, appCase.groups);
        }
        yield count;
      }
      default -> throw new IllegalArgumentException("String op used as int op: " + appCase.op);
    };
  }

  private static int runRe2ffmInt(
      ApplicationCase appCase, org.safere.re2ffm.RE2FfmPattern pattern) {
    return switch (appCase.op) {
      case "matchesCorpus" -> {
        int count = 0;
        for (String text : appCase.texts) {
          if (pattern.matcher(text).matches()) {
            count++;
          }
        }
        yield count;
      }
      case "matchesGroupLengthSum" -> {
        int count = 0;
        for (String text : appCase.texts) {
          org.safere.re2ffm.RE2FfmMatcher matcher = pattern.matcher(text);
          if (matcher.matches()) {
            count += groupLengthSum(matcher::group, appCase.groups);
          }
        }
        yield count;
      }
      case "findAllCount" -> {
        org.safere.re2ffm.RE2FfmMatcher matcher = pattern.matcher(appCase.text);
        int count = 0;
        while (matcher.find()) {
          count++;
        }
        yield count;
      }
      case "findAllLengthSum" -> {
        org.safere.re2ffm.RE2FfmMatcher matcher = pattern.matcher(appCase.text);
        int count = 0;
        while (matcher.find()) {
          count += matcher.group().length();
        }
        yield count;
      }
      case "findAllGroupLengthSum" -> {
        org.safere.re2ffm.RE2FfmMatcher matcher = pattern.matcher(appCase.text);
        int count = 0;
        while (matcher.find()) {
          count += groupLengthSum(matcher::group, appCase.groups);
        }
        yield count;
      }
      default -> throw new IllegalArgumentException("String op used as int op: " + appCase.op);
    };
  }

  private static int groupLengthSum(GroupReader groupReader, int[] groups) {
    int sum = 0;
    for (int group : groups) {
      String value = groupReader.group(group);
      if (value != null) {
        sum += value.length();
      }
    }
    return sum;
  }

  private void validateExpected(ApplicationCase appCase) {
    org.safere.Pattern saferePattern = saferePatterns.get(appCase.name);
    if (appCase.op.startsWith("findAll") && saferePattern.matcher("").find()) {
      throw new IllegalArgumentException(
          appCase.name + " uses an empty-width pattern with find-all op " + appCase.op);
    }
    if (appCase.expectsString()) {
      validateString(
          appCase.name, "safere", runSafereString(appCase.name), appCase.expectedString());
      validateString(appCase.name, "jdk", runJdkString(appCase.name), appCase.expectedString());
      validateString(appCase.name, "re2j", runRe2jString(appCase.name), appCase.expectedString());
      validateString(
          appCase.name, "re2ffm", runRe2ffmString(appCase.name), appCase.expectedString());
      return;
    }
    validateInt(appCase.name, "safere", runSafereInt(appCase.name), appCase.expectedInt());
    validateInt(appCase.name, "jdk", runJdkInt(appCase.name), appCase.expectedInt());
    validateInt(appCase.name, "re2j", runRe2jInt(appCase.name), appCase.expectedInt());
    validateInt(appCase.name, "re2ffm", runRe2ffmInt(appCase.name), appCase.expectedInt());
  }

  private static void validateInt(String caseName, String engine, int actual, int expected) {
    if (actual != expected) {
      throw new IllegalArgumentException(
          caseName + " " + engine + " expected " + expected + " but was " + actual);
    }
  }

  private static void validateString(
      String caseName, String engine, String actual, String expected) {
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException(
          caseName + " " + engine + " expected " + expected + " but was " + actual);
    }
  }

  private interface GroupReader {
    String group(int group);
  }
}
