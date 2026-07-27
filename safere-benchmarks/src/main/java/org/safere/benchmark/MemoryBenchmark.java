// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.Arrays;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
 *   org.safere.benchmark.MemoryBenchmark
 * }</pre>
 */
public final class MemoryBenchmark {

  /** Number of pattern instances to allocate per measurement (amortizes GC noise). */
  private static final int INSTANCES = 500;

  /** Number of independent measurement trials (take the median). */
  private static final int TRIALS = 7;

  private MemoryBenchmark() {}

  public static void main(String[] args) throws Exception {
    SpecializedBenchmarkPlan plan = SpecializedBenchmarkPlan.load();
    Set<String> selected = Arrays.stream(args).collect(Collectors.toSet());
    System.out.println("=== Declarative retained-memory measurements (bytes) ===");
    for (SpecializedBenchmarkPlan.Trial trial : plan.retainedMemoryTrials()) {
      if (!selected.isEmpty() && !selected.contains(trial.id())) {
        continue;
      }
      DeclarativeBenchmarkPlan.ExpandedWorkload workload = trial.workload();
      long bytes =
          switch (workload.operation()) {
            case COMPILE ->
                measureRetainedSize(
                    () ->
                        trial
                            .variant()
                            .compileForBenchmark(
                                workload.patterns().getFirst(), flagSet(workload)));
            case DFA_CACHE_GROWTH ->
                measureDfaCacheGrowth(
                    workload.patterns().getFirst(),
                    BenchmarkData.get().getInputString(workload.inputIds().getFirst()));
            default ->
                throw new IllegalArgumentException(
                    "Unsupported retained-memory operation: " + workload.operation());
          };
      System.out.printf("%s %,d%n", trial.id(), bytes);
    }
  }

  private static String flagSet(DeclarativeBenchmarkPlan.ExpandedWorkload workload) {
    DeclarativeBenchmarkPlan.RecipeValue value = workload.arguments().get("flagSet");
    return value == null ? null : ((DeclarativeBenchmarkPlan.RecipeString) value).value();
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
        var unused = factory.get();
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
      org.safere.Pattern p = org.safere.Pattern.compile(pattern);

      // Warm up: run a short match so JIT compiles the matching path.
      p.matcher("warmup").find();

      forceGc();
      long before = usedMemory();

      // Run matching against large text to populate the DFA state cache.
      org.safere.Matcher m = p.matcher(text);
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
}
