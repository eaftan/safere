// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;
import org.safere.Matcher;
import org.safere.Pattern;

/**
 * Measures ordinary String searches after plain-only or mixed String-region warmup in one JVM.
 *
 * <p>Each warmup invocation performs the target search and an equivalent companion search. Only the
 * companion's region bounds differ between histories. Measurement performs the target search alone.
 * Run each history in fresh forks; use the experiment script to alternate their order.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class StringRegionProfileBenchmark {
  /** Declarative ordinary find workload, including matcher construction policy. */
  @Param({})
  public String plainTrial;

  /** Declarative region find workload exposing the same pattern and visible text. */
  @Param({})
  public String regionTrial;

  /** Whether warmup companions search independent text or a bounded region. */
  @Param({"plain", "mixed"})
  public String history;

  private Pattern pattern;
  private String plainInput;
  private String companionInput;
  private int regionStart;
  private int regionEnd;
  private boolean retained;
  private boolean mixed;
  private boolean warming;
  private Matcher plainMatcher;
  private Matcher companionMatcher;

  /** Loads and checks the paired workloads without exercising region matching in the control. */
  @Setup(Level.Trial)
  public void setup() {
    CrossEngineBenchmarkPlan plan = CrossEngineBenchmarkPlan.load();
    CrossEngineBenchmarkPlan.Trial plain = plan.resolve(plainTrial);
    CrossEngineBenchmarkPlan.Trial region = plan.resolve(regionTrial);
    if (plain.variant() != RegexEngineVariant.SAFERE_STRING
        || region.variant() != RegexEngineVariant.SAFERE_STRING
        || plain.workload().operation() != BenchmarkOperation.FIND
        || region.workload().operation() != BenchmarkOperation.MATCHER_REGION_FIND
        || !plain.workload().patterns().equals(region.workload().patterns())
        || plain.workload().patterns().size() != 1
        || !plain.workload().lifecycle().steps().isEmpty()
        || region.workload().lifecycle().steps().size() != 1) {
      throw new IllegalArgumentException("Expected equivalent SafeRE String find/region-find pair");
    }
    if (!history.equals("plain") && !history.equals("mixed")) {
      throw new IllegalArgumentException("Unknown warmup history: " + history);
    }
    DeclarativeBenchmarkPlan.LifecycleStep bounds =
        region.workload().lifecycle().steps().getFirst();
    if (bounds.kind() != DeclarativeBenchmarkPlan.LifecycleStepKind.REGION) {
      throw new IllegalArgumentException("Companion must declare region bounds");
    }
    plainInput = BenchmarkData.get().getInputString(plain.workload().inputKeys().getFirst());
    String regionInput =
        BenchmarkData.get().getInputString(region.workload().inputKeys().getFirst());
    regionStart = bounds.start();
    regionEnd = bounds.end();
    if (!regionInput.substring(regionStart, regionEnd).equals(plainInput)) {
      throw new IllegalArgumentException("Companion region must expose exactly the target text");
    }
    if (!java.util.Objects.equals(plain.workload().flagSet(), region.workload().flagSet())) {
      throw new IllegalArgumentException("Companion flags must match target flags");
    }
    pattern =
        Pattern.compile(
            plain.workload().patterns().getFirst(),
            plain.workload().flagSet() == null
                ? 0
                : BenchmarkFlags.parse(plain.workload().flagSet()));
    mixed = history.equals("mixed");
    retained =
        plain.workload().lifecycle().matcher() == DeclarativeBenchmarkPlan.MatcherReuse.RETAINED;
    companionInput = mixed ? regionInput : plainInput;
    plainMatcher = pattern.matcher(plainInput);
    companionMatcher = pattern.matcher(companionInput);
  }

  /** Uses JMH's phase boundary so region work ends before any measurement invocation. */
  @Setup(Level.Iteration)
  public void iteration(IterationParams parameters) {
    warming = parameters.getType() == IterationType.WARMUP;
  }

  /** Measures only the ordinary search; companion work runs exclusively during warmup. */
  @Benchmark
  public boolean find(Blackhole blackhole) {
    if (warming) {
      Matcher companion = retained ? companionMatcher.reset() : pattern.matcher(companionInput);
      if (mixed) {
        companion.region(regionStart, regionEnd);
      }
      blackhole.consume(companion.find());
    }
    Matcher target = retained ? plainMatcher.reset() : pattern.matcher(plainInput);
    return target.find();
  }
}
