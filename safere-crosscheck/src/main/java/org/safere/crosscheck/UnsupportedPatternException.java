// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.crosscheck;

import java.util.regex.PatternSyntaxException;

/**
 * Thrown when SafeRE rejects a pattern that {@code java.util.regex} accepts. This is expected for
 * features that SafeRE intentionally does not support (backreferences, lookahead, lookbehind,
 * possessive quantifiers) because they violate linear-time guarantees.
 *
 * <p>This exception extends {@link PatternSyntaxException} so callers that catch
 * {@code PatternSyntaxException} will handle it naturally.
 */
public class UnsupportedPatternException extends PatternSyntaxException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a new unsupported pattern exception.
   *
   * @param description description of the unsupported feature
   * @param pattern the pattern that was rejected
   * @param index the index in the pattern where the error occurred
   */
  public UnsupportedPatternException(String description, String pattern, int index) {
    super(description, pattern, index);
  }

  /**
   * Creates a new unsupported pattern exception from a SafeRE {@link PatternSyntaxException}.
   *
   * @param cause the original exception thrown by SafeRE
   * @return a new {@code UnsupportedPatternException}
   */
  public static UnsupportedPatternException fromCause(PatternSyntaxException cause) {
    UnsupportedPatternException ex = new UnsupportedPatternException(
        "SafeRE does not support this pattern (linear-time constraint): " + cause.getDescription(),
        cause.getPattern(),
        cause.getIndex());
    ex.initCause(cause);
    return ex;
  }
}
