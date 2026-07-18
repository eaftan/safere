// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.crosscheck;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.MatchResult;

/** Crosscheck matcher over UTF-8 input with relative byte coordinates. */
public final class Utf8Matcher {
  private final Pattern pattern;
  private final Utf8Input input;
  private final org.safere.Utf8Matcher delegate;
  private final TraceRecorder trace = new TraceRecorder();
  private Oracles oracles;

  Utf8Matcher(Pattern pattern, Utf8Input input) {
    this.pattern = requireNonNull(pattern, "pattern");
    this.input = requireNonNull(input, "input");
    delegate = pattern.saferePattern().matcher(input.delegate());
    if (input.validated()) {
      String decoded = input.decoded();
      Utf8Coordinates coordinates = Utf8Coordinates.create(decoded);
      if (coordinates == null) {
        throw new IllegalStateException("validated UTF-8 decoded to malformed UTF-16");
      }
      oracles =
          new Oracles(
              pattern.saferePattern().matcher(decoded),
              pattern.jdkPattern().matcher(decoded),
              coordinates);
    }
  }

  /** Attempts to find the next matching subsequence. */
  public boolean find() {
    boolean actual = delegate.find();
    if (oracles == null) {
      return actual;
    }
    boolean stringResult = findNextScalarAligned(oracles.safereString(), oracles.coordinates());
    boolean jdkResult = findNextScalarAligned(oracles.jdk(), oracles.coordinates());
    compare("find", "", "SafeRE UTF-8", actual, "SafeRE String", stringResult);
    compare("find", "", "SafeRE String", stringResult, "JDK", jdkResult);
    if (actual) {
      checkMatchState("find");
    }
    trace.recordMatch("find", "", actual);
    return actual;
  }

  /** Returns the previous full match's relative byte start. */
  public int start() {
    return observeBoundary("start", "", 0, true);
  }

  /** Returns a numbered group's relative byte start. */
  public int start(int group) {
    return observeBoundary("start", String.valueOf(group), group, true);
  }

  /** Returns the previous full match's relative byte end. */
  public int end() {
    return observeBoundary("end", "", 0, false);
  }

  /** Returns a numbered group's relative byte end. */
  public int end(int group) {
    return observeBoundary("end", String.valueOf(group), group, false);
  }

  /** Returns the pattern's capturing-group count. */
  public int groupCount() {
    int actual = delegate.groupCount();
    if (oracles != null) {
      int stringResult = oracles.safereString().groupCount();
      int jdkResult = oracles.jdk().groupCount();
      compare("groupCount", "", "SafeRE UTF-8", actual, "SafeRE String", stringResult);
      compare("groupCount", "", "SafeRE String", stringResult, "JDK", jdkResult);
      trace.recordMatch("groupCount", "", actual);
    }
    return actual;
  }

  /** Appends the unmatched prefix and expanded replacement for the current match. */
  public Utf8Matcher appendReplacement(Utf8Sink sink, Utf8Input replacement) {
    requireNonNull(sink, "sink");
    requireNonNull(replacement, "replacement");
    ByteArrayOutputStream actualOutput = new ByteArrayOutputStream();
    delegate.appendReplacement(
        (bytes, offset, length) -> {
          actualOutput.write(bytes, offset, length);
          sink.append(bytes, offset, length);
        },
        replacement.delegate());
    if (oracles == null) {
      return this;
    }
    if (!replacement.validated()) {
      oracles = null;
      return this;
    }

    StringBuilder stringOutput = new StringBuilder();
    StringBuilder jdkOutput = new StringBuilder();
    String decodedReplacement = replacement.decoded();
    oracles.safereString().appendReplacement(stringOutput, decodedReplacement);
    oracles.jdk().appendReplacement(jdkOutput, decodedReplacement);
    String actual = actualOutput.toString(UTF_8);
    compare(
        "appendReplacement",
        quote(decodedReplacement),
        "SafeRE UTF-8",
        actual,
        "SafeRE String",
        stringOutput);
    compare(
        "appendReplacement",
        quote(decodedReplacement),
        "SafeRE String",
        stringOutput,
        "JDK",
        jdkOutput);
    trace.recordMatch("appendReplacement", quote(decodedReplacement), actual);
    return this;
  }

  /** Appends the subject tail following the last replacement. */
  public void appendTail(Utf8Sink sink) {
    requireNonNull(sink, "sink");
    ByteArrayOutputStream actualOutput = new ByteArrayOutputStream();
    delegate.appendTail(
        (bytes, offset, length) -> {
          actualOutput.write(bytes, offset, length);
          sink.append(bytes, offset, length);
        });
    if (oracles == null) {
      return;
    }
    StringBuilder stringOutput = new StringBuilder();
    StringBuilder jdkOutput = new StringBuilder();
    oracles.safereString().appendTail(stringOutput);
    oracles.jdk().appendTail(jdkOutput);
    String actual = actualOutput.toString(UTF_8);
    compare("appendTail", "", "SafeRE UTF-8", actual, "SafeRE String", stringOutput);
    compare("appendTail", "", "SafeRE String", stringOutput, "JDK", jdkOutput);
    trace.recordMatch("appendTail", "", actual);
  }

  static void crosscheckFind(Pattern pattern, Utf8Input input, boolean actual) {
    if (!input.validated()) {
      return;
    }
    String decoded = input.decoded();
    Utf8Coordinates coordinates = Utf8Coordinates.create(decoded);
    if (coordinates == null) {
      throw new IllegalStateException("validated UTF-8 decoded to malformed UTF-16");
    }
    boolean stringResult =
        findNextScalarAligned(pattern.saferePattern().matcher(decoded), coordinates);
    boolean jdkResult = findNextScalarAligned(pattern.jdkPattern().matcher(decoded), coordinates);
    if (actual != stringResult) {
      throw directDivergence(
          pattern,
          input,
          coordinates,
          "Pattern.find",
          "SafeRE UTF-8",
          actual,
          "SafeRE String",
          stringResult);
    }
    if (stringResult != jdkResult) {
      throw directDivergence(
          pattern,
          input,
          coordinates,
          "Pattern.find",
          "SafeRE String",
          stringResult,
          "JDK",
          jdkResult);
    }
  }

  private int observeBoundary(String method, String args, int group, boolean start) {
    if (oracles == null) {
      return start ? delegate.start(group) : delegate.end(group);
    }
    Observation actual = observe(() -> start ? delegate.start(group) : delegate.end(group));
    Observation stringResult =
        observe(
            () -> start ? oracles.safereString().start(group) : oracles.safereString().end(group));
    Observation jdkResult =
        observe(() -> start ? oracles.jdk().start(group) : oracles.jdk().end(group));
    compareObservations(method, args, "SafeRE UTF-8", actual, "SafeRE String", stringResult);
    compareObservations(method, args, "SafeRE String", stringResult, "JDK", jdkResult);
    if (actual.failure() != null) {
      throw actual.failure();
    }

    int actualOffset = actual.value();
    if (actualOffset >= 0) {
      oracles.coordinates().toUtf16(actualOffset);
    }
    compareOracleBoundary(method, args, actualOffset, "SafeRE String", stringResult.value());
    compareOracleBoundary(method, args, actualOffset, "JDK", jdkResult.value());
    trace.recordMatch(method, args, actualOffset);
    return actualOffset;
  }

  private void checkMatchState(String context) {
    int actualGroupCount = delegate.groupCount();
    compare(
        context + " -> groupCount",
        "",
        "SafeRE UTF-8",
        actualGroupCount,
        "SafeRE String",
        oracles.safereString().groupCount());
    compare(
        context + " -> groupCount",
        "",
        "SafeRE String",
        oracles.safereString().groupCount(),
        "JDK",
        oracles.jdk().groupCount());
    for (int group = 0; group <= actualGroupCount; group++) {
      compareCurrentBoundary(context, group, true);
      compareCurrentBoundary(context, group, false);
    }
  }

  private void compareCurrentBoundary(String context, int group, boolean start) {
    int actual = start ? delegate.start(group) : delegate.end(group);
    if (actual >= 0) {
      oracles.coordinates().toUtf16(actual);
    }
    int stringResult =
        start ? oracles.safereString().start(group) : oracles.safereString().end(group);
    int jdkResult = start ? oracles.jdk().start(group) : oracles.jdk().end(group);
    String method = context + " -> " + (start ? "start" : "end") + "(" + group + ")";
    compareOracleBoundary(method, "", actual, "SafeRE String", stringResult);
    compareOracleBoundary(method, "", actual, "JDK", jdkResult);
  }

  private void compareOracleBoundary(
      String method, String args, int actual, String oracleLabel, int utf16Offset) {
    if (utf16Offset < 0) {
      compare(method, args, "SafeRE UTF-8", actual, oracleLabel, -1);
    } else if (oracles.coordinates().isUtf16Boundary(utf16Offset)) {
      compare(
          method,
          args,
          "SafeRE UTF-8",
          actual,
          oracleLabel,
          oracles.coordinates().toUtf8(utf16Offset));
    }
  }

  private void compareObservations(
      String method,
      String args,
      String firstLabel,
      Observation first,
      String secondLabel,
      Observation second) {
    if (first.failure() == null && second.failure() == null) {
      return;
    }
    Object firstResult =
        first.failure() == null ? first.value() : "throws " + first.failure().getClass().getName();
    Object secondResult =
        second.failure() == null
            ? second.value()
            : "throws " + second.failure().getClass().getName();
    compare(method, args, firstLabel, firstResult, secondLabel, secondResult);
  }

  private void compare(
      String method,
      String args,
      String firstLabel,
      Object first,
      String secondLabel,
      Object second) {
    if (Objects.equals(Objects.toString(first), Objects.toString(second))) {
      return;
    }
    trace.recordDivergence(method, args, first, second);
    throw CrosscheckException.comparison(
        method,
        args,
        firstLabel,
        Objects.toString(first),
        secondLabel,
        Objects.toString(second),
        trace.format() + "\n" + context());
  }

  private String context() {
    int shownLength = Math.min(input.length(), 256);
    byte[] shown = Arrays.copyOfRange(input.bytes(), input.offset(), input.offset() + shownLength);
    String suffix = shownLength == input.length() ? "" : " ...";
    return "pattern: /"
        + pattern.pattern()
        + "/\n"
        + "decoded input: "
        + quote(input.decoded())
        + "\n"
        + "logical byte window: offset="
        + input.offset()
        + ", length="
        + input.length()
        + "\n"
        + "logical bytes: "
        + Arrays.toString(shown)
        + suffix
        + "\n"
        + "UTF-16->UTF-8 boundaries: "
        + oracles.coordinates().describe();
  }

  private static CrosscheckException directDivergence(
      Pattern pattern,
      Utf8Input input,
      Utf8Coordinates coordinates,
      String method,
      String firstLabel,
      Object first,
      String secondLabel,
      Object second) {
    return CrosscheckException.comparison(
        method,
        "",
        firstLabel,
        Objects.toString(first),
        secondLabel,
        Objects.toString(second),
        "pattern: /"
            + pattern.pattern()
            + "/\n"
            + "decoded input: "
            + quote(input.decoded())
            + "\nlogical byte window: offset="
            + input.offset()
            + ", length="
            + input.length()
            + "\nUTF-16->UTF-8 boundaries: "
            + coordinates.describe());
  }

  private static boolean findNextScalarAligned(MatchResult matcher, Utf8Coordinates coordinates) {
    while (find(matcher)) {
      if (coordinates.isUtf16Boundary(matcher.start())
          && coordinates.isUtf16Boundary(matcher.end())) {
        return true;
      }
    }
    return false;
  }

  private static boolean find(MatchResult matcher) {
    if (matcher instanceof org.safere.Matcher safereMatcher) {
      return safereMatcher.find();
    }
    return ((java.util.regex.Matcher) matcher).find();
  }

  private static Observation observe(IntOperation operation) {
    try {
      return new Observation(operation.get(), null);
    } catch (RuntimeException failure) {
      return new Observation(0, failure);
    }
  }

  private static String quote(String text) {
    return "\"" + text + "\"";
  }

  private record Oracles(
      org.safere.Matcher safereString, java.util.regex.Matcher jdk, Utf8Coordinates coordinates) {}

  private record Observation(int value, RuntimeException failure) {}

  @FunctionalInterface
  private interface IntOperation {
    int get();
  }
}
