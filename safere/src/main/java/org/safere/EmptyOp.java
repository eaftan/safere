// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * Bit flags for zero-width (empty) assertions. These are used by {@link InstOp#EMPTY_WIDTH}
 * instructions to test conditions at the current position without consuming input.
 *
 * <p>These correspond to RE2's EmptyOp enum in prog.h.
 */
final class EmptyOp {

  /** {@code ^} — beginning of line. */
  public static final int BEGIN_LINE = 1 << 0;

  /** {@code $} — end of line. */
  public static final int END_LINE = 1 << 1;

  /** {@code \A} — beginning of text. */
  public static final int BEGIN_TEXT = 1 << 2;

  /** {@code \z} — end of text. */
  public static final int END_TEXT = 1 << 3;

  /** {@code \b} — word boundary. */
  public static final int WORD_BOUNDARY = 1 << 4;

  /** {@code \B} — not a word boundary. */
  public static final int NON_WORD_BOUNDARY = 1 << 5;

  /**
   * {@code $} without {@code MULTILINE} — end of text or before a trailing {@code \n}. JDK's
   * {@code $} matches at end-of-input and also just before a final newline at the end of input.
   * This flag is set at both positions. Distinct from {@link #END_TEXT} ({@code \z}), which
   * matches only at the absolute end.
   */
  public static final int DOLLAR_END = 1 << 6;

  /** {@code \b} — Unicode word boundary (when UNICODE_CHARACTER_CLASS is set). */
  public static final int UNICODE_WORD_BOUNDARY = 1 << 7;

  /** {@code \B} — Unicode not a word boundary (when UNICODE_CHARACTER_CLASS is set). */
  public static final int UNICODE_NON_WORD_BOUNDARY = 1 << 8;

  /** All flags combined. */
  public static final int ALL_FLAGS = (1 << 9) - 1;

  private EmptyOp() {} // Non-instantiable.
}
