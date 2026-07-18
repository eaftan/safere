// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Shared-data comparison of String, decode-inclusive, and direct UTF-8 matching. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class Utf8MatchingBenchmark extends ByteMatchingBenchmark {
  /** Measures direct capture-free search. */
  @Benchmark
  public boolean captureFreeBytes(CaptureFreeState state) {
    return state.pattern.find(state.input);
  }

  /** Measures search after decoding the same storage. */
  @Benchmark
  public boolean captureFreeDecode(CaptureFreeState state) {
    return state.pattern.matcher(new String(state.bytes, StandardCharsets.UTF_8)).find();
  }

  /** Measures search over a predecoded String. */
  @Benchmark
  public boolean captureFreeString(CaptureFreeState state) {
    return state.pattern.matcher(state.text).find();
  }

  /** Measures repeated direct UTF-8 find. */
  @Benchmark
  public int repeatedFind(RepeatedFindState state) {
    org.safere.Utf8Matcher matcher = state.pattern.matcher(state.input);
    int result = 0;
    while (matcher.find()) {
      result += matcher.start() + matcher.end();
    }
    return result;
  }

  /** Measures capture-bound extraction without group materialization. */
  @Benchmark
  public int captureBounds(CaptureBoundsState state) {
    org.safere.Utf8Matcher matcher = state.pattern.matcher(state.input);
    int result = 0;
    while (matcher.find()) {
      for (int group = 0; group <= matcher.groupCount(); group++) {
        result += matcher.start(group) + matcher.end(group);
      }
    }
    return result;
  }

  /** Measures scalar-safe empty-match iteration. */
  @Benchmark
  public int emptyMatchIteration(EmptyMatchState state) {
    org.safere.Utf8Matcher matcher = state.pattern.matcher(state.input);
    int result = 0;
    while (matcher.find()) {
      result += matcher.start();
    }
    return result;
  }

  /** Measures a borrowed nonzero-offset window. */
  @Benchmark
  public boolean window(WindowState state) {
    return state.pattern.find(state.input);
  }

  /** Measures trusted or validated view construction separately from matching. */
  @Benchmark
  public org.safere.Utf8Input construction(ConstructionState state) {
    return state.validated
        ? org.safere.Utf8Input.validated(state.bytes)
        : org.safere.Utf8Input.trusted(state.bytes);
  }

  /** Measures hard-failure scaling. */
  @Benchmark
  public boolean hardFailure(HardFailureState state) {
    return state.pattern.find(state.input);
  }

  @State(Scope.Thread)
  public static class CaptureFreeState {
    @Param({
      "asciiEarly", "asciiLate", "asciiNoMatch",
      "multibyteEarly", "multibyteLate", "multibyteNoMatch"
    })
    public String name;

    private org.safere.Pattern pattern;
    private String text;
    private byte[] bytes;
    private org.safere.Utf8Input input;

    /** Loads one frozen capture-free case. */
    @Setup
    public void setup() {
      BenchmarkData data = BenchmarkData.get();
      String prefix = "utf8Matching.captureFreeFind." + name + ".";
      pattern = org.safere.Pattern.compile(data.getString(prefix + "pattern"));
      text = data.getString(prefix + "text");
      bytes = text.getBytes(StandardCharsets.UTF_8);
      input = org.safere.Utf8Input.trusted(bytes);
    }
  }

  @State(Scope.Thread)
  public static class RepeatedFindState extends CaseState {
    @Param({"ascii", "multibyte"})
    public String name;

    /** Loads one frozen repeated-find case. */
    @Setup
    public void setup() {
      setup("utf8Matching.repeatedFind." + name + ".");
    }
  }

  @State(Scope.Thread)
  public static class CaptureBoundsState extends CaseState {
    @Param({"numbered", "named", "nonparticipating"})
    public String name;

    /** Loads one frozen capture-bounds case. */
    @Setup
    public void setup() {
      setup("utf8Matching.captureBounds." + name + ".");
    }
  }

  @State(Scope.Thread)
  public static class EmptyMatchState extends CaseState {
    /** Loads the frozen empty-match case. */
    @Setup
    public void setup() {
      setup("utf8Matching.emptyMatchIteration.");
    }
  }

  @State(Scope.Thread)
  public static class WindowState extends CaseState {
    /** Builds the frozen nonzero-offset view. */
    @Setup
    public void setup() {
      BenchmarkData data = BenchmarkData.get();
      String prefix = data.getString("utf8Matching.window.prefix");
      String text = data.getString("utf8Matching.window.text");
      String suffix = data.getString("utf8Matching.window.suffix");
      byte[] storage = (prefix + text + suffix).getBytes(StandardCharsets.UTF_8);
      int offset = prefix.getBytes(StandardCharsets.UTF_8).length;
      int length = text.getBytes(StandardCharsets.UTF_8).length;
      pattern = org.safere.Pattern.compile(data.getString("utf8Matching.window.pattern"));
      input = org.safere.Utf8Input.trusted(storage, offset, length);
    }
  }

  @State(Scope.Thread)
  public static class ConstructionState {
    @Param({"false", "true"})
    public boolean validated;

    private byte[] bytes;

    /** Loads representative multibyte storage. */
    @Setup
    public void setup() {
      bytes =
          BenchmarkData.get()
              .getString("utf8Matching.repeatedFind.multibyte.text")
              .getBytes(StandardCharsets.UTF_8);
    }
  }

  @State(Scope.Thread)
  public static class HardFailureState {
    @Param({"1024", "10240", "102400", "1048576"})
    public int size;

    private org.safere.Pattern pattern;
    private org.safere.Utf8Input input;

    /** Builds a frozen-size hard-failure input. */
    @Setup
    public void setup() {
      BenchmarkData data = BenchmarkData.get();
      pattern = org.safere.Pattern.compile(data.getString("utf8Matching.hardFailure.pattern"));
      String unit = data.getString("utf8Matching.hardFailure.unit");
      String text = unit.repeat((size + unit.length() - 1) / unit.length()).substring(0, size);
      input = org.safere.Utf8Input.trusted(text.getBytes(StandardCharsets.UTF_8));
    }
  }

  private abstract static class CaseState {
    org.safere.Pattern pattern;
    org.safere.Utf8Input input;

    void setup(String prefix) {
      BenchmarkData data = BenchmarkData.get();
      pattern = org.safere.Pattern.compile(data.getString(prefix + "pattern"));
      input =
          org.safere.Utf8Input.trusted(
              data.getString(prefix + "text").getBytes(StandardCharsets.UTF_8));
    }
  }
}
