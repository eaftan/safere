// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Complete uninstrumented adaptation cycles; requires explicit investigation selection. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class AdaptiveCaptureBenchmark {
  /** Explicit investigation-owned scenario. */
  @Param({})
  public String scenario;

  /** Native String or UTF-8 execution. */
  @Param({"string", "utf8"})
  public String representation;

  /** Experimental library policy selected before Matcher initialization in each fresh fork. */
  @Param({})
  public String policy;

  /** Reproducible Matcher-local exploration seed. */
  @Param({"1"})
  public long seed;

  protected AdaptiveCaptureCase.Task task;

  /** Validates against a separate JDK oracle without teaching the measurement Pattern. */
  @Setup
  public void setup() {
    configure(policy, seed);
    AdaptiveCaptureCase selected = AdaptiveCaptureCase.load(scenario, representation);
    selected.validate();
    verify(policy, seed);
    task = selected.task(false);
  }

  static void configure(String policy, long seed) {
    System.setProperty(
        "safere.experimental.capturePolicy", policy.equals("patchedMain") ? "main" : policy);
    System.setProperty("safere.experimental.captureSeed", Long.toString(seed));
  }

  static void verify(String policy, long seed) {
    String actual = System.getProperty("safere.experimental.captureActualMode");
    if (actual == null
        && (policy.equals("main")
            || policy.equals(System.getProperty("safere.experimental.bareControl")))) {
      return;
    }
    String expected = policy.equals("patchedMain") ? "main" : policy;
    if (!expected.equals(actual)
        || !Long.toString(seed)
            .equals(System.getProperty("safere.experimental.captureActualSeed"))) {
      throw new IllegalStateException(
          "Experimental policy/seed initialized before benchmark setup");
    }
  }

  /** Scans the declared phases and consumes capture checksums. */
  @Benchmark
  public long run() {
    return task.run(null);
  }
}
