// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.nio.charset.StandardCharsets;
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
 * a {@link Scope#Benchmark} state, so running with {@code -t 4} shares each Pattern across
 * threads.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class EngineSlotBenchmark {

  /** {@code string} uses {@link Matcher}; {@code utf8} uses {@link Utf8Matcher}. */
  @Param({"string", "utf8"})
  public String input;

  private static final String LOG =
      "2026-10-31T12:00:00Z INFO  request id=42 path=/api/v1/items status=200 took=12ms "
          + "2026-10-31T12:00:01Z INFO  request id=43 path=/api/v1/users status=200 took=7ms "
          + "2026-10-31T12:00:02Z WARN  request id=44 path=/api/v1/items status=404 took=3ms "
          + "2026-10-31T12:00:03Z INFO  request id=45 path=/api/v1/orders status=200 took=19ms "
          + "2026-10-31T12:00:04Z ERROR request id=46 path=/api/v1/items status=500 took=250ms";

  Pattern lookingAtPattern;
  String lookingAtText;
  byte[] lookingAtBytes;

  Pattern regionPattern;
  String regionText;
  byte[] regionBytes;

  Pattern resetPattern;
  String resetText;
  byte[] resetBytes;

  /** Matches near the end of {@link #LOG}, so a forward DFA scan runs. */
  Pattern findPattern;

  /** Never matches {@link #LOG}. */
  Pattern missPattern;

  /** Many matches in {@link #LOG}. */
  Pattern findAllPattern;

  Pattern matchesPattern;
  String matchesText;
  byte[] matchesBytes;

  byte[] logBytes;
  Predicate<String> findPredicate;

  @Setup
  public void setup() {
    lookingAtPattern = Pattern.compile("[A-Za-z]+:\\s+\\d+");
    lookingAtText = "requests: 1234 completed";
    regionPattern = Pattern.compile("\\b[A-Za-z]+ing\\b");
    regionText = "IGNORE morning shining IGNORE";
    resetPattern = Pattern.compile("[A-Za-z]+\\d+");
    resetText = "item1 item22 item333";
    findPattern = Pattern.compile("status=5\\d\\d took=(\\d+)ms");
    missPattern = Pattern.compile("status=3\\d\\d took=(\\d+)ms");
    findAllPattern = Pattern.compile("id=(\\d+)");
    matchesPattern = Pattern.compile("([a-z0-9._%+-]+)@([a-z0-9.-]+)\\.([a-z]{2,})");
    matchesText = "user.name+tag" + "@" + "mail.example.com";
    lookingAtBytes = lookingAtText.getBytes(StandardCharsets.UTF_8);
    regionBytes = regionText.getBytes(StandardCharsets.UTF_8);
    resetBytes = resetText.getBytes(StandardCharsets.UTF_8);
    matchesBytes = matchesText.getBytes(StandardCharsets.UTF_8);
    logBytes = LOG.getBytes(StandardCharsets.UTF_8);
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
    return utf8() ? r.regionUtf8Matcher.region(7, 22).find() : r.regionMatcher.region(7, 22).find();
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
        : findPattern.matcher(LOG).find();
  }

  /** {@code pattern.matcher(s).find()} that does not match. */
  @Benchmark
  public boolean newMatcherFindMiss() {
    return utf8()
        ? missPattern.matcher(Utf8Input.trusted(logBytes)).find()
        : missPattern.matcher(LOG).find();
  }

  /** {@code m = pattern.matcher(s); m.find(); m.group(1)}: a capture read from an abandoned hit. */
  @Benchmark
  public int newMatcherFindGroup() {
    if (utf8()) {
      Utf8Matcher m = findPattern.matcher(Utf8Input.trusted(logBytes));
      return m.find() ? m.end(1) : -1;
    }
    Matcher m = findPattern.matcher(LOG);
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
      Matcher m = findAllPattern.matcher(LOG);
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
    return utf8() ? findPattern.find(Utf8Input.trusted(logBytes)) : findPredicate.test(LOG);
  }
}
