// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

/**
 * Opcodes for compiled regular expression instructions. Each instruction in a compiled {@link Prog}
 * has an opcode that determines how it operates.
 *
 * <p>These correspond to RE2's InstOp enum in prog.h.
 */
enum InstOp {
  /** Branch: choose between two successors ({@code out} and {@code out1}). */
  ALT,

  /**
   * Optimized Alt: one branch is a byte range and loops back, the other is a match. Used to
   * optimize patterns like {@code .*} before a match.
   */
  ALT_MATCH,

  /**
   * Match a character in the range {@code [lo, hi]}. In the C++ version this is called ByteRange;
   * in Java we match Unicode code points, so this is a character range.
   */
  CHAR_RANGE,

  /** Capturing parenthesis. Records the current position as submatch boundary {@code cap}. */
  CAPTURE,

  /**
   * Empty-width assertion. Matches if the current position satisfies the conditions given by the
   * {@link EmptyOp} flags (e.g., beginning of line, word boundary).
   */
  EMPTY_WIDTH,

  /** Found a match. Halts the machine with a successful match. */
  MATCH,

  /** No-op. Passes through to the next instruction. Occasionally unavoidable. */
  NOP,

  /** Always fails. Never matches. Occasionally unavoidable. */
  FAIL;
}
