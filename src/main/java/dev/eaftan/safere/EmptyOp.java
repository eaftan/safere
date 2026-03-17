// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

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

  /** All flags combined. */
  public static final int ALL_FLAGS = (1 << 6) - 1;

  private EmptyOp() {} // Non-instantiable.
}
