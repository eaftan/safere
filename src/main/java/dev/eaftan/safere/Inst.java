// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

/**
 * A single instruction in a compiled regular expression program. Each instruction has an {@link
 * InstOp opcode} and opcode-specific data.
 *
 * <p>Unlike the C++ RE2 implementation, this class does not use bit-packing. Fields are stored
 * directly for clarity.
 *
 * <p>The instruction set:
 *
 * <ul>
 *   <li>{@link InstOp#ALT}: Branch to {@code out} or {@code out1}
 *   <li>{@link InstOp#ALT_MATCH}: Optimized Alt where one branch is a match
 *   <li>{@link InstOp#CHAR_RANGE}: Match a code point in {@code [lo, hi]}, optionally
 *       case-insensitive
 *   <li>{@link InstOp#CAPTURE}: Record position as submatch boundary for capture {@code arg}
 *   <li>{@link InstOp#EMPTY_WIDTH}: Assert zero-width condition (see {@link EmptyOp})
 *   <li>{@link InstOp#MATCH}: Accept with match ID {@code arg}
 *   <li>{@link InstOp#NOP}: No-op, continue to {@code out}
 *   <li>{@link InstOp#FAIL}: Unconditional failure
 * </ul>
 */
public final class Inst {

  /** The opcode for this instruction. */
  public InstOp op;

  /** Primary successor instruction index. Used by all opcodes except MATCH and FAIL. */
  public int out;

  /**
   * Secondary successor instruction index. Only used by {@link InstOp#ALT} and {@link
   * InstOp#ALT_MATCH}.
   */
  public int out1;

  /**
   * Multipurpose argument:
   *
   * <ul>
   *   <li>For {@link InstOp#CAPTURE}: the capture register index (cap * 2 for start, cap * 2 + 1
   *       for end)
   *   <li>For {@link InstOp#MATCH}: the match ID
   *   <li>For {@link InstOp#EMPTY_WIDTH}: the {@link EmptyOp} flags
   * </ul>
   */
  public int arg;

  /** Low end of character range (inclusive). Only used by {@link InstOp#CHAR_RANGE}. */
  public int lo;

  /** High end of character range (inclusive). Only used by {@link InstOp#CHAR_RANGE}. */
  public int hi;

  /**
   * Whether this character range match is case-insensitive. Only used by {@link
   * InstOp#CHAR_RANGE}.
   */
  public boolean foldCase;

  /** Creates an uninitialized instruction (defaults to FAIL). */
  public Inst() {
    this.op = InstOp.FAIL;
  }

  /** Initializes as an ALT instruction branching to {@code out} or {@code out1}. */
  public void initAlt(int out, int out1) {
    this.op = InstOp.ALT;
    this.out = out;
    this.out1 = out1;
  }

  /** Initializes as a CHAR_RANGE instruction matching code points in {@code [lo, hi]}. */
  public void initCharRange(int lo, int hi, boolean foldCase, int out) {
    this.op = InstOp.CHAR_RANGE;
    this.lo = lo;
    this.hi = hi;
    this.foldCase = foldCase;
    this.out = out;
  }

  /** Initializes as a CAPTURE instruction for capture register {@code cap}. */
  public void initCapture(int cap, int out) {
    this.op = InstOp.CAPTURE;
    this.arg = cap;
    this.out = out;
  }

  /** Initializes as an EMPTY_WIDTH instruction with the given {@link EmptyOp} flags. */
  public void initEmptyWidth(int emptyFlags, int out) {
    this.op = InstOp.EMPTY_WIDTH;
    this.arg = emptyFlags;
    this.out = out;
  }

  /** Initializes as a MATCH instruction with the given match ID. */
  public void initMatch(int matchId) {
    this.op = InstOp.MATCH;
    this.arg = matchId;
  }

  /** Initializes as a NOP instruction continuing to {@code out}. */
  public void initNop(int out) {
    this.op = InstOp.NOP;
    this.out = out;
  }

  /** Initializes as a FAIL instruction. */
  public void initFail() {
    this.op = InstOp.FAIL;
  }

  /**
   * Returns true if the given code point matches this CHAR_RANGE instruction.
   *
   * @throws IllegalStateException if this instruction is not a CHAR_RANGE
   */
  public boolean matchesChar(int c) {
    if (op != InstOp.CHAR_RANGE) {
      throw new IllegalStateException("matchesChar called on " + op);
    }
    if (c >= lo && c <= hi) {
      return true;
    }
    if (foldCase) {
      // Try case-folded version.
      int folded = simpleFold(c);
      while (folded != c) {
        if (folded >= lo && folded <= hi) {
          return true;
        }
        folded = simpleFold(folded);
      }
    }
    return false;
  }

  /**
   * Returns the next code point in the case-fold orbit of {@code r}. For example, 'A' → 'a' → 'A'.
   * For characters with no case folding, returns {@code r}.
   */
  static int simpleFold(int r) {
    // Use Unicode case folding tables.
    for (int[] fold : UnicodeTables.CASE_FOLD) {
      if (r < fold[0]) {
        return r; // Past the relevant range.
      }
      if (r > fold[1]) {
        continue; // Before this range.
      }
      int delta = fold[2];
      if (delta == UnicodeTables.EVEN_ODD) {
        // Even code points add 1, odd subtract 1.
        return (r % 2 == 0) ? r + 1 : r - 1;
      } else if (delta == UnicodeTables.ODD_EVEN) {
        // Odd code points add 1 (wrapping), even subtract 1.
        return (r % 2 == 1) ? r + 1 : r - 1;
      } else if (delta == UnicodeTables.EVEN_ODD_SKIP || delta == UnicodeTables.ODD_EVEN_SKIP) {
        // These are more complex folding cases; for now treat as simple delta.
        return r;
      } else {
        return r + delta;
      }
    }
    return r;
  }

  @Override
  public String toString() {
    return switch (op) {
      case ALT -> String.format("alt -> %d | %d", out, out1);
      case ALT_MATCH -> String.format("altmatch -> %d | %d", out, out1);
      case CHAR_RANGE ->
          String.format(
              "char [0x%X-0x%X]%s -> %d", lo, hi, foldCase ? "/i" : "", out);
      case CAPTURE -> String.format("capture %d -> %d", arg, out);
      case EMPTY_WIDTH -> String.format("empty 0x%X -> %d", arg, out);
      case MATCH -> String.format("match %d", arg);
      case NOP -> String.format("nop -> %d", out);
      case FAIL -> "fail";
    };
  }
}
