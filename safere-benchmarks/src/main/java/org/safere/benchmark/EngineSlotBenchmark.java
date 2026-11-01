// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.safere.Matcher;
import org.safere.Pattern;
import org.safere.Utf8Input;
import org.safere.Utf8Matcher;

/**
 * Matcher lifecycles that exercise how a Pattern hands its cached engines to matchers: a new
 * matcher per call, a retained matcher reset per call, and matcher-free searches. Patterns live in
 * a {@link Scope#Benchmark} state, so running with {@code -t 4} shares each Pattern across threads.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class EngineSlotBenchmark {

  /** {@code string} uses {@link Matcher}; {@code utf8} uses {@link Utf8Matcher}. */
  @Param({"string", "utf8"})
  public String input;

  private String logText;
  private int regionStart;
  private int regionEnd;

  Pattern lookingAtPattern;
  String lookingAtText;
  byte[] lookingAtBytes;

  Pattern regionPattern;
  String regionText;
  byte[] regionBytes;

  Pattern resetPattern;
  String resetText;
  byte[] resetBytes;

  /** Matches near the end of {@link #logText}, so a forward DFA scan runs. */
  Pattern findPattern;

  /** Never matches {@link #logText}. */
  Pattern missPattern;

  /** Many matches in {@link #logText}. */
  Pattern findAllPattern;

  Pattern matchesPattern;
  String matchesText;
  byte[] matchesBytes;

  byte[] logBytes;
  Predicate<String> findPredicate;

  @Setup
  public void setup() {
    BenchmarkData data = BenchmarkData.get();
    lookingAtPattern =
        Pattern.compile(data.getString("configuration.engineSlot.patterns.lookingAt"));
    lookingAtText =
        data.getInputString(data.getString("configuration.engineSlot.inputs.lookingAt"));
    regionPattern = Pattern.compile(data.getString("configuration.engineSlot.patterns.region"));
    regionText = data.getInputString(data.getString("configuration.engineSlot.inputs.region"));
    resetPattern = Pattern.compile(data.getString("configuration.engineSlot.patterns.reset"));
    resetText = data.getInputString(data.getString("configuration.engineSlot.inputs.reset"));
    findPattern = Pattern.compile(data.getString("configuration.engineSlot.patterns.find"));
    missPattern = Pattern.compile(data.getString("configuration.engineSlot.patterns.miss"));
    findAllPattern = Pattern.compile(data.getString("configuration.engineSlot.patterns.findAll"));
    matchesPattern = Pattern.compile(data.getString("configuration.engineSlot.patterns.matches"));
    matchesText = data.getInputString(data.getString("configuration.engineSlot.inputs.matches"));
    lookingAtBytes =
        data.getInputBytes(data.getString("configuration.engineSlot.inputs.lookingAt"));
    regionBytes = data.getInputBytes(data.getString("configuration.engineSlot.inputs.region"));
    resetBytes = data.getInputBytes(data.getString("configuration.engineSlot.inputs.reset"));
    matchesBytes = data.getInputBytes(data.getString("configuration.engineSlot.inputs.matches"));
    logText = data.getInputString(data.getString("configuration.engineSlot.inputs.log"));
    logBytes = data.getInputBytes(data.getString("configuration.engineSlot.inputs.log"));
    regionStart = Integer.parseInt(data.getString("configuration.engineSlot.regionStart"));
    regionEnd = Integer.parseInt(data.getString("configuration.engineSlot.regionEnd"));
    findPredicate = findPattern.asPredicate();
  }

  /** Per-thread retained matchers, as a find-all loop over a reused matcher keeps them. */
  @State(Scope.Thread)
  public static class Retained {
    Matcher regionMatcher;
    Utf8Matcher regionUtf8Matcher;
    Matcher resetMatcher;
    Utf8Matcher resetUtf8Matcher;

    @Setup
    public void setup(EngineSlotBenchmark b) {
      regionMatcher = b.regionPattern.matcher(b.regionText);
      regionUtf8Matcher = b.regionPattern.matcher(Utf8Input.trusted(b.regionBytes));
      resetMatcher = b.resetPattern.matcher(b.resetText);
      resetUtf8Matcher = b.resetPattern.matcher(Utf8Input.trusted(b.resetBytes));
    }
  }

  private boolean utf8() {
    return input.equals("utf8");
  }

  /** {@code pattern.matcher(s).lookingAt()} on a short prefix match. */
  @Benchmark
  public boolean lookingAt() {
    return utf8()
        ? lookingAtPattern.matcher(Utf8Input.trusted(lookingAtBytes)).lookingAt()
        : lookingAtPattern.matcher(lookingAtText).lookingAt();
  }

  /** A retained matcher: {@code region(7, 22).find()}. */
  @Benchmark
  public boolean regionFind(Retained r) {
    return utf8()
        ? r.regionUtf8Matcher.region(regionStart, regionEnd).find()
        : r.regionMatcher.region(regionStart, regionEnd).find();
  }

  /** A retained matcher: {@code reset()} then a find-all loop with three matches. */
  @Benchmark
  public int resetAndFind(Retained r) {
    int count = 0;
    if (utf8()) {
      Utf8Matcher m = r.resetUtf8Matcher.reset();
      while (m.find()) {
        count++;
      }
    } else {
      Matcher m = r.resetMatcher.reset();
      while (m.find()) {
        count++;
      }
    }
    return count;
  }

  /** {@code pattern.matcher(s).find()} that matches; the matcher is then abandoned. */
  @Benchmark
  public boolean newMatcherFindHit() {
    return utf8()
        ? findPattern.matcher(Utf8Input.trusted(logBytes)).find()
        : findPattern.matcher(logText).find();
  }

  /** {@code pattern.matcher(s).find()} that does not match. */
  @Benchmark
  public boolean newMatcherFindMiss() {
    return utf8()
        ? missPattern.matcher(Utf8Input.trusted(logBytes)).find()
        : missPattern.matcher(logText).find();
  }

  /** {@code m = pattern.matcher(s); m.find(); m.group(1)}: a capture read from an abandoned hit. */
  @Benchmark
  public int newMatcherFindGroup() {
    if (utf8()) {
      Utf8Matcher m = findPattern.matcher(Utf8Input.trusted(logBytes));
      return m.find() ? m.end(1) : -1;
    }
    Matcher m = findPattern.matcher(logText);
    return m.find() ? m.group(1).length() : -1;
  }

  /** A new matcher per call, then a find-all loop with five matches. */
  @Benchmark
  public int newMatcherFindAll() {
    int count = 0;
    if (utf8()) {
      Utf8Matcher m = findAllPattern.matcher(Utf8Input.trusted(logBytes));
      while (m.find()) {
        count += m.end(1);
      }
    } else {
      Matcher m = findAllPattern.matcher(logText);
      while (m.find()) {
        count += m.end(1);
      }
    }
    return count;
  }

  /** {@code pattern.matcher(s).matches()} followed by a capture read. */
  @Benchmark
  public int newMatcherMatchesGroup() {
    if (utf8()) {
      Utf8Matcher m = matchesPattern.matcher(Utf8Input.trusted(matchesBytes));
      return m.matches() ? m.end(2) : -1;
    }
    Matcher m = matchesPattern.matcher(matchesText);
    return m.matches() ? m.group(2).length() : -1;
  }

  /** Matcher-free search: {@code asPredicate()} for String, {@code Pattern.find} for UTF-8. */
  @Benchmark
  public boolean matcherFreeFind() {
    return utf8() ? findPattern.find(Utf8Input.trusted(logBytes)) : findPredicate.test(logText);
  }
}
