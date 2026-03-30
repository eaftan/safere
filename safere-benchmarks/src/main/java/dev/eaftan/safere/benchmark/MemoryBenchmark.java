// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere.benchmark;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * Measures the retained heap size of compiled regex patterns across engines (SafeRE, JDK, RE2/J).
 *
 * <p>Uses the heap-delta technique: allocate N instances of a compiled pattern, measure heap growth
 * before and after (with forced GC), and divide by N. Multiple trials are run and the median is
 * reported to reduce noise.
 *
 * <p>This is a standalone measurement tool, not a JMH benchmark, because we are measuring one-shot
 * object sizes rather than per-operation throughput.
 *
 * <p>Run with:
 *
 * <pre>{@code
 * java -Xms256m -Xmx256m -cp safere-benchmarks/target/benchmarks.jar \
 *   dev.eaftan.safere.benchmark.MemoryBenchmark
 * }</pre>
 */
public final class MemoryBenchmark {

  /** Number of pattern instances to allocate per measurement (amortizes GC noise). */
  private static final int INSTANCES = 500;

  /** Number of independent measurement trials (take the median). */
  private static final int TRIALS = 7;

  private MemoryBenchmark() {}

  public static void main(String[] args) throws Exception {
    BenchmarkData data = BenchmarkData.get();

    System.out.println("=== Compiled Pattern Size (bytes, retained heap) ===");
    System.out.println();
    System.out.printf("%-18s %10s %10s %10s%n", "Pattern", "SafeRE", "JDK", "RE2/J");
    System.out.println("─".repeat(52));

    String[] patternNames = {"simple", "medium", "complex", "alternation"};

    for (String name : patternNames) {
      String pattern = data.getString("compile." + name + ".pattern");

      long safereSize = measureRetainedSize(
          () -> dev.eaftan.safere.Pattern.compile(pattern));
      long jdkSize = measureRetainedSize(
          () -> java.util.regex.Pattern.compile(pattern));
      long re2jSize = measureRetainedSize(
          () -> com.google.re2j.Pattern.compile(pattern));

      System.out.printf("%-18s %,10d %,10d %,10d%n", name, safereSize, jdkSize, re2jSize);
    }

    // Also measure the core regex benchmark patterns (match-time patterns).
    System.out.println();
    System.out.println("=== Match-Time Pattern Size (bytes, retained heap) ===");
    System.out.println();
    System.out.printf("%-18s %10s %10s %10s%n", "Pattern", "SafeRE", "JDK", "RE2/J");
    System.out.println("─".repeat(52));

    String[][] regexPatterns = {
        {"literalMatch", "regex.literalMatch.pattern"},
        {"charClassMatch", "regex.charClassMatch.pattern"},
        {"alternationFind", "regex.alternationFind.pattern"},
        {"captureGroups", "regex.captureGroups.pattern"},
        {"findInText", "regex.findInText.pattern"},
        {"emailFind", "regex.emailFind.pattern"},
    };

    for (String[] entry : regexPatterns) {
      String name = entry[0];
      String pattern = data.getString(entry[1]);

      long safereSize = measureRetainedSize(
          () -> dev.eaftan.safere.Pattern.compile(pattern));
      long jdkSize = measureRetainedSize(
          () -> java.util.regex.Pattern.compile(pattern));
      long re2jSize = measureRetainedSize(
          () -> com.google.re2j.Pattern.compile(pattern));

      System.out.printf("%-18s %,10d %,10d %,10d%n", name, safereSize, jdkSize, re2jSize);
    }

    // DFA cache growth: compile a single SafeRE pattern, measure heap before/after matching
    // against a large text to populate the DFA state cache.
    System.out.println();
    System.out.println("=== DFA Cache Growth (SafeRE only, bytes) ===");
    System.out.println();
    System.out.printf("%-18s %12s%n", "Pattern", "Cache growth");
    System.out.println("─".repeat(32));

    // Build a large text to exercise DFA cache creation across many character classes.
    String largeText = getSampleText(data, "complex").repeat(200);

    for (String name : patternNames) {
      String pattern = data.getString("compile." + name + ".pattern");
      long growth = measureDfaCacheGrowth(pattern, largeText);
      System.out.printf("%-18s %,12d%n", name, growth);
    }
  }

  /**
   * Measures the retained heap size of an object produced by the given factory, using the
   * heap-delta technique. Creates {@link #INSTANCES} copies, measures heap growth, divides by
   * instance count. Runs {@link #TRIALS} trials and returns the median.
   */
  private static long measureRetainedSize(Supplier<Object> factory) throws Exception {
    long[] results = new long[TRIALS];

    for (int trial = 0; trial < TRIALS; trial++) {
      // Warm up the factory (JIT compile, class loading).
      for (int i = 0; i < 20; i++) {
        factory.get();
      }

      forceGc();
      long before = usedMemory();

      // Allocate many instances to amortize per-object GC noise.
      Object[] holders = new Object[INSTANCES];
      for (int i = 0; i < INSTANCES; i++) {
        holders[i] = factory.get();
      }

      forceGc();
      long after = usedMemory();

      results[trial] = Math.max(0, (after - before) / INSTANCES);

      // Keep holders alive past the measurement point.
      if (holders[INSTANCES - 1] == null) {
        throw new AssertionError("holder should not be null");
      }
    }

    Arrays.sort(results);
    return results[TRIALS / 2]; // median
  }

  /** Forces garbage collection as thoroughly as possible. */
  private static void forceGc() throws InterruptedException {
    for (int i = 0; i < 5; i++) {
      System.gc();
      Thread.sleep(50);
    }
  }

  /** Returns the currently used heap memory in bytes. */
  private static long usedMemory() {
    Runtime rt = Runtime.getRuntime();
    return rt.totalMemory() - rt.freeMemory();
  }

  /**
   * Measures the DFA cache growth for a single compiled SafeRE pattern by measuring heap before and
   * after running the pattern against text. Uses multiple trials and returns the median.
   */
  private static long measureDfaCacheGrowth(String pattern, String text) throws Exception {
    long[] results = new long[TRIALS];

    for (int trial = 0; trial < TRIALS; trial++) {
      // Compile the pattern (DFA cache starts empty).
      dev.eaftan.safere.Pattern p = dev.eaftan.safere.Pattern.compile(pattern);

      // Warm up: run a short match so JIT compiles the matching path.
      p.matcher("warmup").find();

      forceGc();
      long before = usedMemory();

      // Run matching against large text to populate the DFA state cache.
      dev.eaftan.safere.Matcher m = p.matcher(text);
      while (m.find()) {
        // DFA states are lazily created during matching.
      }

      forceGc();
      long after = usedMemory();

      results[trial] = after - before;

      // Keep pattern alive past the measurement.
      if (p.pattern() == null) {
        throw new AssertionError("pattern should not be null");
      }
    }

    Arrays.sort(results);
    return results[TRIALS / 2]; // median
  }

  /** Returns a sample text appropriate for the given compile pattern name. */
  private static String getSampleText(BenchmarkData data, String patternName) {
    return switch (patternName) {
      case "simple" -> "hello world hello world hello";
      case "medium" -> "2025-12-25T10:30:00 and 2024-01-15T08:00:00";
      case "complex" -> "contact user.name+tag@example.co.uk for info";
      case "alternation" ->
          "the garply went to the baz and met a quux and fred and xyzzy and plugh";
      default -> "the quick brown fox jumps over the lazy dog";
    };
  }
}
