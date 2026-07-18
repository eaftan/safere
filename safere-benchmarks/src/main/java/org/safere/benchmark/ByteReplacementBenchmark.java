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
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Measures byte-native replacement while a synchronous sink consumes output ranges. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class ByteReplacementBenchmark {
  private ReplacementCase literal;
  private ReplacementCase numbered;
  private ReplacementCase named;
  private final CountingSink sink = new CountingSink();

  /** Loads the frozen UTF-8 replacement workloads. */
  @Setup
  public void setup() {
    BenchmarkData data = BenchmarkData.get();
    literal = replacementCase(data, "literal");
    numbered = replacementCase(data, "numbered");
    named = replacementCase(data, "named");
  }

  /** Replaces multibyte literals with an ASCII literal. */
  @Benchmark
  public int literal() {
    return replace(literal);
  }

  /** Expands numbered groups directly from the subject storage. */
  @Benchmark
  public int numbered() {
    return replace(numbered);
  }

  /** Expands named groups directly from the subject storage. */
  @Benchmark
  public int named() {
    return replace(named);
  }

  private int replace(ReplacementCase replacementCase) {
    sink.reset();
    org.safere.Utf8Matcher matcher = replacementCase.pattern().matcher(replacementCase.input());
    while (matcher.find()) {
      matcher.appendReplacement(sink, replacementCase.replacement());
    }
    matcher.appendTail(sink);
    return sink.length();
  }

  private static ReplacementCase replacementCase(BenchmarkData data, String name) {
    String prefix = "utf8Matching.replacement." + name + ".";
    return new ReplacementCase(
        org.safere.Pattern.compile(data.getString(prefix + "pattern")),
        org.safere.Utf8Input.trusted(bytes(data.getString(prefix + "text"))),
        org.safere.Utf8Input.validated(bytes(data.getString(prefix + "replacement"))));
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private record ReplacementCase(
      org.safere.Pattern pattern, org.safere.Utf8Input input, org.safere.Utf8Input replacement) {}

  private static final class CountingSink implements org.safere.Utf8Sink {
    private int length;

    @Override
    public void append(byte[] bytes, int offset, int rangeLength) {
      length += rangeLength;
    }

    void reset() {
      length = 0;
    }

    int length() {
      return length;
    }
  }
}
