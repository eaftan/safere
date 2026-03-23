// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere.benchmark;

import dev.eaftan.safere.PatternSet;
import java.util.List;
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
 * Benchmarks for {@link PatternSet} multi-pattern matching.
 *
 * <p>Tests matching text against multiple compiled patterns simultaneously, which is a key use case
 * for content filtering, log classification, and routing.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class PatternSetBenchmark {

  @Param({"4", "16", "64"})
  private int patternCount;

  private PatternSet unanchoredSet;
  private PatternSet anchoredSet;

  private static final String MATCH_TEXT =
      "ERROR: connection timeout after 30s to host db-primary.internal:5432";
  private static final String NO_MATCH_TEXT =
      "The quick brown fox jumps over the lazy dog near the riverbank";

  // Base patterns — mix of literals, char classes, and quantifiers.
  private static final String[] BASE_PATTERNS = {
      "ERROR",
      "WARNING",
      "INFO",
      "DEBUG",
      "FATAL",
      "connection\\s+timeout",
      "\\d+\\.\\d+\\.\\d+\\.\\d+",
      "host\\s+\\S+",
      "port\\s*:\\s*\\d+",
      "after\\s+\\d+s",
      "[A-Z]{2,}:",
      "\\w+\\.internal",
      "db-\\w+",
      "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}:\\d+",
      "timeout|refused|reset|closed",
      "\\[\\w+\\]",
      "status\\s*=\\s*\\d+",
      "retry\\s+\\d+/\\d+",
      "latency\\s*>\\s*\\d+ms",
      "queue\\s+full",
      "memory\\s+\\d+[kmg]b?",
      "cpu\\s+\\d+%",
      "disk\\s+(full|low)",
      "\\w+Exception",
      "at\\s+\\w+\\.\\w+\\(",
      "null\\s*pointer",
      "stack\\s*overflow",
      "out\\s+of\\s+memory",
      "permission\\s+denied",
      "not\\s+found",
      "already\\s+exists",
      "invalid\\s+\\w+",
      "expired",
      "unauthorized",
      "rate\\s+limit",
      "circuit\\s+breaker",
      "health\\s+check\\s+failed",
      "graceful\\s+shutdown",
      "leader\\s+election",
      "split\\s+brain",
      "rebalancing",
      "partition\\s+\\d+",
      "offset\\s+\\d+",
      "consumer\\s+group",
      "dead\\s+letter",
      "backpressure",
      "throttled",
      "degraded",
      "failover",
      "rollback",
      "checkpoint\\s+\\d+",
      "snapshot\\s+\\w+",
      "compaction",
      "gc\\s+pause\\s+\\d+ms",
      "safepoint",
      "jit\\s+compilation",
      "class\\s+loading",
      "thread\\s+pool\\s+\\w+",
      "blocked\\s+thread",
      "deadlock",
      "lock\\s+contention",
      "cache\\s+(hit|miss)",
      "eviction",
      "bloom\\s+filter",
  };

  @Setup
  public void setup() {
    PatternSet.Builder unanchoredBuilder =
        new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
    PatternSet.Builder anchoredBuilder =
        new PatternSet.Builder(PatternSet.Anchor.ANCHOR_START);

    for (int i = 0; i < patternCount; i++) {
      String pat = BASE_PATTERNS[i % BASE_PATTERNS.length];
      unanchoredBuilder.add(pat);
      anchoredBuilder.add(pat);
    }

    unanchoredSet = unanchoredBuilder.compile();
    anchoredSet = anchoredBuilder.compile();
  }

  // ===== Unanchored: text with matches =====

  @Benchmark
  public List<Integer> unanchoredMatch() {
    return unanchoredSet.match(MATCH_TEXT);
  }

  // ===== Unanchored: text with no matches =====

  @Benchmark
  public List<Integer> unanchoredNoMatch() {
    return unanchoredSet.match(NO_MATCH_TEXT);
  }

  // ===== Anchored: text with matches =====

  @Benchmark
  public List<Integer> anchoredMatch() {
    return anchoredSet.match(MATCH_TEXT);
  }

  // ===== Anchored: text with no matches =====

  @Benchmark
  public List<Integer> anchoredNoMatch() {
    return anchoredSet.match(NO_MATCH_TEXT);
  }
}
