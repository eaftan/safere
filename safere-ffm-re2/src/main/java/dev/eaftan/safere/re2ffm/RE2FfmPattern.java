// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package dev.eaftan.safere.re2ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.nio.charset.StandardCharsets;

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
