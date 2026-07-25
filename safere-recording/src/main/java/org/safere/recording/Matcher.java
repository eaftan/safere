// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.recording;

import java.util.Map;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.stream.Stream;

/**
 * Recording facade for {@link org.safere.Matcher}.
 *
 * <p>Every operation is recorded and then delegated once to SafeRE.
 */
public final class Matcher implements MatchResult {

  private Pattern pattern;
  private final org.safere.Matcher delegate;
  private final long recordingId = RecordingRuntime.newObjectId();
  private long sequence;

  Matcher(Pattern pattern, org.safere.Matcher delegate, CharSequence input) {
    this.pattern = pattern;
    this.delegate = delegate;
    RecordingRuntime.record(
        "M",
        recordingId,
        sequence,
        "matcher",
        RecordingRuntime.encodeArguments(
            pattern.delegate().pattern(), pattern.delegate().flags(), input),
        "created");
  }

  /** Quotes a replacement string. */
  public static String quoteReplacement(String input) {
    long id = RecordingRuntime.newObjectId();
    String arguments = RecordingRuntime.encodeArguments(input);
    try {
      String result = org.safere.Matcher.quoteReplacement(input);
      RecordingRuntime.record("S", id, 0, "quoteReplacement", arguments, result);
      return result;
    } catch (RuntimeException e) {
      RecordingRuntime.recordException("S", id, 0, "quoteReplacement", arguments, e);
      throw e;
    }
  }

  /** Attempts to match the complete region. */
  public boolean matches() {
    return record("matches", delegate::matches);
  }

  /** Attempts to match at the beginning of the region. */
  public boolean lookingAt() {
    return record("lookingAt", delegate::lookingAt);
  }

  /** Finds the next match. */
  public boolean find() {
    return record("find", delegate::find);
  }

  /** Resets and finds from the given index. */
  public boolean find(int start) {
    return record("find", () -> delegate.find(start), start);
  }

  /** Returns a stream of match-result snapshots. */
  public Stream<MatchResult> results() {
    record("results", () -> "stream");
    return delegate
        .results()
        .map(
            result -> {
              record("results.next", () -> result);
              return new RecordedMatchResult(result);
            });
  }

  @Override
  public int groupCount() {
    return record("groupCount", delegate::groupCount);
  }

  @Override
  public String group() {
    return record("group", delegate::group);
  }

  @Override
  public String group(int group) {
    return record("group", () -> delegate.group(group), group);
  }

  @Override
  public String group(String name) {
    return record("group", () -> delegate.group(name), name);
  }

  @Override
  public int start() {
    return record("start", delegate::start);
  }

  @Override
  public int start(int group) {
    return record("start", () -> delegate.start(group), group);
  }

  @Override
  public int start(String name) {
    return record("start", () -> delegate.start(name), name);
  }

  @Override
  public int end() {
    return record("end", delegate::end);
  }

  @Override
  public int end(int group) {
    return record("end", () -> delegate.end(group), group);
  }

  @Override
  public int end(String name) {
    return record("end", () -> delegate.end(name), name);
  }

  /** Replaces the first match. */
  public String replaceFirst(String replacement) {
    return record("replaceFirst", () -> delegate.replaceFirst(replacement), replacement);
  }

  /** Replaces all matches. */
  public String replaceAll(String replacement) {
    return record("replaceAll", () -> delegate.replaceAll(replacement), replacement);
  }

  /** Replaces the first match with a function. */
  public String replaceFirst(Function<MatchResult, String> replacer) {
    return record(
        "replaceFirstFunction",
        () -> delegate.replaceFirst(result -> replacer.apply(new RecordedMatchResult(result))),
        replacer);
  }

  /** Replaces all matches with a function. */
  public String replaceAll(Function<MatchResult, String> replacer) {
    return record(
        "replaceAllFunction",
        () -> delegate.replaceAll(result -> replacer.apply(new RecordedMatchResult(result))),
        replacer);
  }

  /** Performs an append-and-replace step. */
  public Matcher appendReplacement(StringBuilder output, String replacement) {
    return recordThis(
        "appendReplacement",
        () -> delegate.appendReplacement(output, replacement),
        replacement,
        output);
  }

  /** Appends the unmatched tail. */
  public StringBuilder appendTail(StringBuilder output) {
    return record("appendTail", () -> delegate.appendTail(output), output);
  }

  /** Performs an append-and-replace step. */
  public Matcher appendReplacement(StringBuffer output, String replacement) {
    return recordThis(
        "appendReplacement",
        () -> delegate.appendReplacement(output, replacement),
        replacement,
        output);
  }

  /** Appends the unmatched tail. */
  public StringBuffer appendTail(StringBuffer output) {
    return record("appendTail", () -> delegate.appendTail(output), output);
  }

  /** Resets this matcher. */
  public Matcher reset() {
    return recordThis("reset", delegate::reset);
  }

  /** Resets this matcher with a new input. */
  public Matcher reset(CharSequence input) {
    return recordThis("reset", () -> delegate.reset(input), input);
  }

  /** Changes the active region. */
  public Matcher region(int start, int end) {
    return recordThis("region", () -> delegate.region(start, end), start, end);
  }

  /** Returns the region start. */
  public int regionStart() {
    return record("regionStart", delegate::regionStart);
  }

  /** Returns the region end. */
  public int regionEnd() {
    return record("regionEnd", delegate::regionEnd);
  }

  @Override
  public Map<String, Integer> namedGroups() {
    return record("namedGroups", delegate::namedGroups);
  }

  /** Returns the facade pattern associated with this matcher. */
  public Pattern pattern() {
    record("pattern", () -> pattern);
    return pattern;
  }

  /** Changes the pattern used by this matcher. */
  public Matcher usePattern(Pattern newPattern) {
    Matcher result =
        recordThis(
            "usePattern",
            () -> delegate.usePattern(newPattern == null ? null : newPattern.delegate()),
            newPattern);
    pattern = newPattern;
    return result;
  }

  /** Changes transparent-bounds behavior. */
  public Matcher useTransparentBounds(boolean enabled) {
    return recordThis(
        "useTransparentBounds", () -> delegate.useTransparentBounds(enabled), enabled);
  }

  /** Returns transparent-bounds behavior. */
  public boolean hasTransparentBounds() {
    return record("hasTransparentBounds", delegate::hasTransparentBounds);
  }

  /** Changes anchoring-bounds behavior. */
  public Matcher useAnchoringBounds(boolean enabled) {
    return recordThis("useAnchoringBounds", () -> delegate.useAnchoringBounds(enabled), enabled);
  }

  /** Returns anchoring-bounds behavior. */
  public boolean hasAnchoringBounds() {
    return record("hasAnchoringBounds", delegate::hasAnchoringBounds);
  }

  /** Returns an immutable snapshot of the current match result. */
  public MatchResult toMatchResult() {
    return new RecordedMatchResult(record("toMatchResult", delegate::toMatchResult));
  }

  @Override
  public String toString() {
    return record("toString", delegate::toString);
  }

  private Matcher recordThis(String method, Operation<?> operation, Object... arguments) {
    long callSequence = ++sequence;
    String encodedArguments = RecordingRuntime.encodeArguments(arguments);
    try {
      operation.run();
      RecordingRuntime.record("M", recordingId, callSequence, method, encodedArguments, "this");
      return this;
    } catch (RuntimeException e) {
      RecordingRuntime.recordException("M", recordingId, callSequence, method, encodedArguments, e);
      throw e;
    }
  }

  private <T> T record(String method, Operation<T> operation, Object... arguments) {
    long callSequence = ++sequence;
    String encodedArguments = RecordingRuntime.encodeArguments(arguments);
    try {
      T result = operation.run();
      RecordingRuntime.record("M", recordingId, callSequence, method, encodedArguments, result);
      return result;
    } catch (RuntimeException e) {
      RecordingRuntime.recordException("M", recordingId, callSequence, method, encodedArguments, e);
      throw e;
    }
  }

  @FunctionalInterface
  private interface Operation<T> {
    T run();
  }

  private final class RecordedMatchResult implements MatchResult {

    private final MatchResult result;

    RecordedMatchResult(MatchResult result) {
      this.result = result;
    }

    @Override
    public int start() {
      return record("matchResult.start", result::start);
    }

    @Override
    public int start(int group) {
      return record("matchResult.start", () -> result.start(group), group);
    }

    @Override
    public int start(String name) {
      return record("matchResult.start", () -> result.start(name), name);
    }

    @Override
    public int end() {
      return record("matchResult.end", result::end);
    }

    @Override
    public int end(int group) {
      return record("matchResult.end", () -> result.end(group), group);
    }

    @Override
    public int end(String name) {
      return record("matchResult.end", () -> result.end(name), name);
    }

    @Override
    public String group() {
      return record("matchResult.group", result::group);
    }

    @Override
    public String group(int group) {
      return record("matchResult.group", () -> result.group(group), group);
    }

    @Override
    public String group(String name) {
      return record("matchResult.group", () -> result.group(name), name);
    }

    @Override
    public int groupCount() {
      return record("matchResult.groupCount", result::groupCount);
    }

    @Override
    public Map<String, Integer> namedGroups() {
      return record("matchResult.namedGroups", result::namedGroups);
    }
  }
}
