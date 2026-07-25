// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.recording;

import java.io.ObjectStreamException;
import java.io.Serializable;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Recording facade for {@link org.safere.Pattern}.
 *
 * <p>Every operation is recorded and then delegated once to SafeRE.
 */
public final class Pattern implements Serializable {

  private static final long serialVersionUID = 1L;

  public static final int UNIX_LINES = org.safere.Pattern.UNIX_LINES;
  public static final int CASE_INSENSITIVE = org.safere.Pattern.CASE_INSENSITIVE;
  public static final int COMMENTS = org.safere.Pattern.COMMENTS;
  public static final int MULTILINE = org.safere.Pattern.MULTILINE;
  public static final int LITERAL = org.safere.Pattern.LITERAL;
  public static final int DOTALL = org.safere.Pattern.DOTALL;
  public static final int UNICODE_CASE = org.safere.Pattern.UNICODE_CASE;
  public static final int UNICODE_CHARACTER_CLASS = org.safere.Pattern.UNICODE_CHARACTER_CLASS;

  private final org.safere.Pattern delegate;
  private final long recordingId;
  private long sequence;

  private Pattern(org.safere.Pattern delegate, long recordingId) {
    this.delegate = delegate;
    this.recordingId = recordingId;
  }

  /** Compiles a regular expression and records the result. */
  public static Pattern compile(String regex) {
    return compile(regex, 0);
  }

  /** Compiles a regular expression with flags and records the result. */
  public static Pattern compile(String regex, int flags) {
    long id = RecordingRuntime.newObjectId();
    String arguments = RecordingRuntime.encodeArguments(regex, flags);
    try {
      Pattern result = new Pattern(org.safere.Pattern.compile(regex, flags), id);
      RecordingRuntime.record("P", id, 0, "compile", arguments, "compiled");
      return result;
    } catch (RuntimeException e) {
      RecordingRuntime.recordException("P", id, 0, "compile", arguments, e);
      throw e;
    }
  }

  /** Compiles a regular expression and tests whether it matches the complete input. */
  public static boolean matches(String regex, CharSequence input) {
    long id = RecordingRuntime.newObjectId();
    String arguments = RecordingRuntime.encodeArguments(regex, input);
    try {
      boolean result = org.safere.Pattern.matches(regex, input);
      RecordingRuntime.record("S", id, 0, "matches", arguments, result);
      return result;
    } catch (RuntimeException e) {
      RecordingRuntime.recordException("S", id, 0, "matches", arguments, e);
      throw e;
    }
  }

  /** Quotes a literal string for use in a pattern. */
  public static String quote(String input) {
    long id = RecordingRuntime.newObjectId();
    String arguments = RecordingRuntime.encodeArguments(input);
    try {
      String result = org.safere.Pattern.quote(input);
      RecordingRuntime.record("S", id, 0, "quote", arguments, result);
      return result;
    } catch (RuntimeException e) {
      RecordingRuntime.recordException("S", id, 0, "quote", arguments, e);
      throw e;
    }
  }

  /** Creates a recording matcher for the input. */
  public Matcher matcher(CharSequence input) {
    return new Matcher(this, delegate.matcher(input), input);
  }

  /** Creates a recording matcher over UTF-8 input. */
  public Utf8Matcher matcher(Utf8Input input) {
    return new Utf8Matcher(this, input, delegate.matcher(input.delegate()));
  }

  /** Runs a capture-free UTF-8 search. */
  public boolean find(Utf8Input input) {
    return record(
        "findUtf8",
        RecordingRuntime.encodeArguments(input.bytes(), input.offset(), input.length()),
        () -> delegate.find(input.delegate()));
  }

  /** Returns the compile flags. */
  public int flags() {
    return record("flags", RecordingRuntime.encodeArguments(), delegate::flags);
  }

  /** Returns the original pattern string. */
  public String pattern() {
    return record("pattern", RecordingRuntime.encodeArguments(), delegate::pattern);
  }

  /** Splits the input around matches. */
  public String[] split(CharSequence input) {
    return record("split", RecordingRuntime.encodeArguments(input), () -> delegate.split(input));
  }

  /** Splits the input around matches with a limit. */
  public String[] split(CharSequence input, int limit) {
    return record(
        "split",
        RecordingRuntime.encodeArguments(input, limit),
        () -> delegate.split(input, limit));
  }

  /** Splits the input around matches and includes delimiters. */
  public String[] splitWithDelimiters(CharSequence input) {
    return record(
        "splitWithDelimiters",
        RecordingRuntime.encodeArguments(input),
        () -> delegate.splitWithDelimiters(input));
  }

  /** Splits the input around matches with a limit and includes delimiters. */
  public String[] splitWithDelimiters(CharSequence input, int limit) {
    return record(
        "splitWithDelimiters",
        RecordingRuntime.encodeArguments(input, limit),
        () -> delegate.splitWithDelimiters(input, limit));
  }

  /** Returns a stream of split elements. */
  public Stream<String> splitAsStream(CharSequence input) {
    record("splitAsStream", RecordingRuntime.encodeArguments(input), () -> "stream");
    return delegate
        .splitAsStream(input)
        .peek(
            value -> record("splitAsStream.next", RecordingRuntime.encodeArguments(), () -> value));
  }

  /** Returns a predicate implemented through this recording facade. */
  public Predicate<String> asPredicate() {
    record("asPredicate", RecordingRuntime.encodeArguments(), () -> "predicate");
    return input -> matcher(input).find();
  }

  /** Returns a full-match predicate implemented through this recording facade. */
  public Predicate<String> asMatchPredicate() {
    record("asMatchPredicate", RecordingRuntime.encodeArguments(), () -> "predicate");
    return input -> matcher(input).matches();
  }

  /** Returns the named capturing groups. */
  public Map<String, Integer> namedGroups() {
    return record("namedGroups", RecordingRuntime.encodeArguments(), delegate::namedGroups);
  }

  @Override
  public String toString() {
    return record("toString", RecordingRuntime.encodeArguments(), delegate::toString);
  }

  org.safere.Pattern delegate() {
    return delegate;
  }

  long recordingId() {
    return recordingId;
  }

  private <T> T record(String method, String arguments, Operation<T> operation) {
    long callSequence = ++sequence;
    try {
      T result = operation.run();
      RecordingRuntime.record("P", recordingId, callSequence, method, arguments, result);
      return result;
    } catch (RuntimeException e) {
      RecordingRuntime.recordException("P", recordingId, callSequence, method, arguments, e);
      throw e;
    }
  }

  private Object writeReplace() throws ObjectStreamException {
    return new SerializedForm(delegate.pattern(), delegate.flags());
  }

  @FunctionalInterface
  private interface Operation<T> {
    T run();
  }

  private record SerializedForm(String regex, int flags) implements Serializable {

    private static final long serialVersionUID = 1L;

    private Object readResolve() throws ObjectStreamException {
      return Pattern.compile(regex, flags);
    }
  }
}
