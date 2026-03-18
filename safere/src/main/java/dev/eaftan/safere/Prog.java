// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import java.util.ArrayList;
import java.util.List;

/**
 * A compiled regular expression program, consisting of an array of {@link Inst} instructions. This
 * is the output of the {@code Compiler} and the input to the execution engines (NFA, DFA, etc.).
 *
 * <p>A Prog is produced by compiling a {@link Regexp} AST via Thompson NFA construction. Each
 * instruction represents a state in the NFA.
 */
final class Prog {

  private final List<Inst> instructions = new ArrayList<>();
  private int start;
  private int startUnanchored;
  private int numCaptures;
  private boolean anchorStart;
  private boolean anchorEnd;
  private boolean reversed;

  /** Creates an empty program. */
  public Prog() {}

  /** Returns the instruction at the given index. */
  public Inst inst(int index) {
    return instructions.get(index);
  }

  /** Returns the total number of instructions. */
  public int size() {
    return instructions.size();
  }

  /**
   * Allocates a new instruction at the end of the program and returns its index.
   *
   * @return the index of the newly allocated instruction
   */
  public int allocInst() {
    int index = instructions.size();
    instructions.add(new Inst());
    return index;
  }

  /** Returns the start instruction index for anchored matching. */
  public int start() {
    return start;
  }

  /** Sets the start instruction index for anchored matching. */
  public void setStart(int start) {
    this.start = start;
  }

  /**
   * Returns the start instruction index for unanchored matching. This typically points to a {@code
   * .*?} loop that skips to the real start.
   */
  public int startUnanchored() {
    return startUnanchored;
  }

  /** Sets the start instruction index for unanchored matching. */
  public void setStartUnanchored(int startUnanchored) {
    this.startUnanchored = startUnanchored;
  }

  /** Returns the number of capturing groups (including the implicit group 0 for the full match). */
  public int numCaptures() {
    return numCaptures;
  }

  /** Sets the number of capturing groups. */
  public void setNumCaptures(int numCaptures) {
    this.numCaptures = numCaptures;
  }

  /** Returns true if the pattern is anchored at the start. */
  public boolean anchorStart() {
    return anchorStart;
  }

  /** Sets whether the pattern is anchored at the start. */
  public void setAnchorStart(boolean anchorStart) {
    this.anchorStart = anchorStart;
  }

  /** Returns true if the pattern is anchored at the end. */
  public boolean anchorEnd() {
    return anchorEnd;
  }

  /** Sets whether the pattern is anchored at the end. */
  public void setAnchorEnd(boolean anchorEnd) {
    this.anchorEnd = anchorEnd;
  }

  /** Returns true if this program runs in reverse (for finding match starts). */
  public boolean reversed() {
    return reversed;
  }

  /** Sets whether this program runs in reverse. */
  public void setReversed(boolean reversed) {
    this.reversed = reversed;
  }

  /**
   * Returns a human-readable dump of the program, useful for debugging.
   *
   * <p>Example output:
   *
   * <pre>
   *   0. char [0x61-0x61] -> 1
   *   1. match 0
   * </pre>
   */
  public String dump() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < instructions.size(); i++) {
      if (i == start) {
        sb.append('>');
      } else {
        sb.append(' ');
      }
      sb.append(String.format("%d. %s\n", i, instructions.get(i)));
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return String.format(
        "Prog{size=%d, start=%d, startUnanchored=%d, captures=%d}",
        size(), start, startUnanchored, numCaptures);
  }
}
