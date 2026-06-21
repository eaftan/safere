// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.re2ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A compiled regular expression backed by C++ RE2 via the FFM API. This is a benchmark-oriented
 * wrapper — it supports enough of the {@code java.util.regex.Pattern} API to run the SafeRE
 * benchmark suite.
 */
public final class RE2FfmPattern {

  /** Case-insensitive matching flag, compatible with {@code java.util.regex.Pattern}. */
  public static final int CASE_INSENSITIVE = 2;

  private static final Cleaner CLEANER = Cleaner.create();

  private final String patternString;
  private final int flags;
  private final MemorySegment nativeHandle;
  private final int numGroups;

  private RE2FfmPattern(String patternString, int flags, MemorySegment nativeHandle) {
    this.patternString = patternString;
    this.flags = flags;
    this.nativeHandle = nativeHandle;
    this.numGroups = Re2Shim.numCapturingGroups(nativeHandle);

    // Register cleanup so the native handle is freed when this object is GC'd.
    MemorySegment handle = nativeHandle;
    CLEANER.register(this, () -> Re2Shim.free(handle));
  }

  /**
   * Compiles a regular expression.
   *
   * @param regex the regular expression
   * @return a compiled pattern
   * @throws IllegalArgumentException if the pattern is invalid
   */
  public static RE2FfmPattern compile(String regex) {
    return compile(regex, 0);
  }

  /**
   * Compiles a regular expression with the given flags.
   *
   * @param regex the regular expression
   * @param flags compilation flags (e.g., {@link #CASE_INSENSITIVE})
   * @return a compiled pattern
   * @throws IllegalArgumentException if the pattern is invalid
   */
  public static RE2FfmPattern compile(String regex, int flags) {
    byte[] utf8 = regex.getBytes(StandardCharsets.UTF_8);
    MemorySegment handle;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment patternSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, utf8);
      if ((flags & CASE_INSENSITIVE) != 0) {
        handle = Re2Shim.compileCaseInsensitive(patternSeg, utf8.length);
      } else {
        handle = Re2Shim.compile(patternSeg, utf8.length);
      }
    }
    if (!Re2Shim.ok(handle)) {
      String error = Re2Shim.error(handle);
      Re2Shim.free(handle);
      throw new IllegalArgumentException("RE2 compilation failed: " + error);
    }
    return new RE2FfmPattern(regex, flags, handle);
  }

  /**
   * Creates a matcher for this pattern against the given input.
   *
   * @param input the input text
   * @return a new matcher
   */
  public RE2FfmMatcher matcher(String input) {
    return new RE2FfmMatcher(this, input);
  }

  /**
   * Splits the given input around matches of this pattern. Trailing empty strings are discarded.
   *
   * @param input the character sequence to be split
   * @return the array of strings computed by splitting the input around matches of this pattern
   */
  public String[] split(CharSequence input) {
    return split(input, 0);
  }

  /**
   * Splits the given input around matches of this pattern.
   *
   * @param input the character sequence to be split
   * @param limit the result threshold
   * @return the array of strings computed by splitting the input around matches of this pattern
   */
  public String[] split(CharSequence input, int limit) {
    String text = input.toString();
    RE2FfmMatcher matcher = matcher(text);
    List<String> parts = new ArrayList<>();
    int last = 0;

    while (matcher.find()) {
      if (limit > 0 && parts.size() >= limit - 1) {
        break;
      }
      if (last == 0 && matcher.start() == 0 && matcher.end() == 0) {
        continue;
      }
      parts.add(text.substring(last, matcher.start()));
      last = matcher.end();
    }
    if (last == 0) {
      return new String[] {text};
    }

    parts.add(text.substring(last));
    if (limit == 0) {
      int end = parts.size();
      while (end > 0 && parts.get(end - 1).isEmpty()) {
        end--;
      }
      parts = parts.subList(0, end);
    }
    return parts.toArray(new String[0]);
  }

  /** Returns the pattern string. */
  public String pattern() {
    return patternString;
  }

  /** Returns the compilation flags. */
  public int flags() {
    return flags;
  }

  /** Returns the native RE2 handle. Package-private for use by {@link RE2FfmMatcher}. */
  MemorySegment nativeHandle() {
    return nativeHandle;
  }

  /** Returns the number of capturing groups. */
  int numGroups() {
    return numGroups;
  }
}
