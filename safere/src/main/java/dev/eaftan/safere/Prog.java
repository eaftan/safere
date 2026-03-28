// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import java.util.ArrayDeque;
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
  private Inst[] instArray;
  private int start;
  private int startUnanchored;
  private int numCaptures;
  private boolean anchorStart;
  private boolean anchorEnd;
  private boolean reversed;

  /** Creates an empty program. */
  public Prog() {}

  /** Returns the instruction at the given index. Must be called after {@link #freeze()}. */
  public Inst inst(int index) {
    return instArray[index];
  }

  /**
   * Returns the instruction at the given index from the mutable instruction list. Used during
   * compilation before the program is frozen.
   */
  Inst mutableInst(int index) {
    return instructions.get(index);
  }

  /** Returns the total number of instructions. */
  public int size() {
    return instArray != null ? instArray.length : instructions.size();
  }

  /**
   * Allocates a new instruction at the end of the program and returns its index. Must be called
   * before {@link #freeze()}.
   *
   * @return the index of the newly allocated instruction
   */
  public int allocInst() {
    int index = instructions.size();
    instructions.add(new Inst());
    return index;
  }

  /**
   * Freezes the instruction list into a flat array for fast indexed access. Must be called after
   * all instructions have been allocated and initialized (typically at the end of compilation).
   * After freezing, {@link #inst(int)} reads directly from the array, avoiding ArrayList overhead.
   */
  public void freeze() {
    instArray = instructions.toArray(new Inst[0]);
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
   * Returns true if the program contains EMPTY_WIDTH instructions with {@link EmptyOp#WORD_BOUNDARY}
   * or {@link EmptyOp#NON_WORD_BOUNDARY} flags. The DFA cannot correctly cache transitions for these
   * assertions because the word-boundary context depends on both the previous and next characters,
   * but the DFA transition cache keys only on (state, character-class).
   *
   * <p>When this returns true, callers should bypass the DFA and use NFA/BitState instead.
   */
  public boolean hasWordBoundary() {
    int n = size();
    for (int i = 0; i < n; i++) {
      Inst ip = inst(i);
      if (ip.op == InstOp.EMPTY_WIDTH
          && (ip.arg & (EmptyOp.WORD_BOUNDARY | EmptyOp.NON_WORD_BOUNDARY)) != 0) {
        return true;
      }
    }
    return false;
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
    int n = size();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      if (i == start) {
        sb.append('>');
      } else {
        sb.append(' ');
      }
      sb.append(String.format("%d. %s\n", i, instArray != null ? instArray[i] : instructions.get(i)));
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return String.format(
        "Prog{size=%d, start=%d, startUnanchored=%d, captures=%d}",
        size(), start, startUnanchored, numCaptures);
  }

  // ---------------------------------------------------------------------------
  // Epsilon-cycle analysis for BitState
  // ---------------------------------------------------------------------------

  private boolean[] epsilonCycleAlts;

  /**
   * Returns a boolean array indexed by instruction ID. An entry is {@code true} if that instruction
   * is an ALT/ALT_MATCH that participates in an epsilon cycle — a path back to itself through only
   * epsilon transitions (ALT, NOP, CAPTURE, EMPTY_WIDTH). Only these ALT instructions need the
   * BitState visited bitmap to prevent infinite loops; other ALTs can be safely revisited from
   * different DFS paths.
   *
   * <p>The result is computed once and cached.
   */
  boolean[] epsilonCycleAlts() {
    if (epsilonCycleAlts == null) {
      epsilonCycleAlts = computeEpsilonCycleAlts();
    }
    return epsilonCycleAlts;
  }

  private boolean[] computeEpsilonCycleAlts() {
    int n = size();
    boolean[] inCycle = new boolean[n];

    for (int i = 0; i < n; i++) {
      Inst inst = inst(i);
      if (inst.op != InstOp.ALT && inst.op != InstOp.ALT_MATCH) {
        continue;
      }
      if (canReachSelfViaEpsilon(i)) {
        inCycle[i] = true;
      }
    }
    return inCycle;
  }

  /**
   * Returns true if instruction {@code target} can reach itself via a path that consists entirely of
   * epsilon transitions (ALT, ALT_MATCH, NOP, CAPTURE, EMPTY_WIDTH). CHAR_RANGE and MATCH consume
   * input or terminate, so they break any epsilon path.
   */
  private boolean canReachSelfViaEpsilon(int target) {
    int n = size();
    boolean[] visited = new boolean[n];
    ArrayDeque<Integer> stack = new ArrayDeque<>();

    // Seed with epsilon successors of target (don't count target itself as a starting node).
    addEpsilonSuccessors(target, visited, stack);

    while (!stack.isEmpty()) {
      int id = stack.pop();
      if (id == target) {
        return true;
      }
      addEpsilonSuccessors(id, visited, stack);
    }
    return false;
  }

  private void addEpsilonSuccessors(int id, boolean[] visited, ArrayDeque<Integer> stack) {
    Inst inst = inst(id);
    switch (inst.op) {
      case ALT, ALT_MATCH -> {
        if (inst.out > 0 && !visited[inst.out]) {
          visited[inst.out] = true;
          stack.push(inst.out);
        }
        if (inst.out1 > 0 && !visited[inst.out1]) {
          visited[inst.out1] = true;
          stack.push(inst.out1);
        }
      }
      case NOP, CAPTURE, EMPTY_WIDTH -> {
        if (inst.out > 0 && !visited[inst.out]) {
          visited[inst.out] = true;
          stack.push(inst.out);
        }
      }
      default -> {
        // CHAR_RANGE, MATCH, FAIL: not epsilon transitions.
      }
    }
  }
}
