// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A compiled regular expression program, consisting of an array of {@link Inst} instructions. This
 * is the output of the {@code Compiler} and the input to the execution engines (NFA, DFA, etc.).
 *
 * <p>A Prog is produced by compiling a {@link Regexp} AST via Thompson NFA construction. Each
 * instruction represents a state in the NFA.
 */
final class Prog {

  final List<Inst> instructions = new ArrayList<>();
  private boolean didFlatten;
  private Inst[] instArray;
  private int start;
  private int startUnanchored;
  private int numCaptures;
  private boolean anchorStart;
  private boolean anchorEnd;

  /**
   * True when the end anchor was {@code $} (not {@code \z}). When true, the match may end before a
   * trailing {@code \n} at the end of the text, not just at the absolute end. This mirrors JDK's
   * default (non-MULTILINE) {@code $} behavior.
   */
  private boolean dollarAnchorEnd;

  private boolean reversed;
  private boolean unixLines;
  private int numLoopRegs;
  private boolean requiresPikeNfaCaptureSemantics;
  private boolean hasGraphemeSemantics;
  private boolean hasGraphemeClusterInstruction;

  /** Creates an empty program. */
  public Prog() {}

  /** Creates a copy of another program. */
  public Prog(Prog other) {
    for (Inst inst : other.instructions) {
      this.instructions.add(new Inst(inst));
    }
    this.didFlatten = other.didFlatten;
    this.start = other.start;
    this.startUnanchored = other.startUnanchored;
    this.numCaptures = other.numCaptures;
    this.anchorStart = other.anchorStart;
    this.anchorEnd = other.anchorEnd;
    this.dollarAnchorEnd = other.dollarAnchorEnd;
    this.reversed = other.reversed;
    this.unixLines = other.unixLines;
    this.numLoopRegs = other.numLoopRegs;
    this.requiresPikeNfaCaptureSemantics = other.requiresPikeNfaCaptureSemantics;
    this.hasGraphemeSemantics = other.hasGraphemeSemantics;
    this.hasGraphemeClusterInstruction = other.hasGraphemeClusterInstruction;
  }

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

  /** Returns true if the program has been flattened. */
  public boolean didFlatten() {
    return didFlatten;
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
    hasGraphemeSemantics = computeHasGraphemeSemantics();
    hasGraphemeClusterInstruction = computeHasGraphemeClusterInstruction();
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

  /**
   * Returns true if the end anchor was {@code $} (not {@code \z}), meaning the match may end before
   * a trailing newline at the end of the text.
   */
  public boolean dollarAnchorEnd() {
    return dollarAnchorEnd;
  }

  /** Sets whether the end anchor allows a trailing newline. */
  public void setDollarAnchorEnd(boolean dollarAnchorEnd) {
    this.dollarAnchorEnd = dollarAnchorEnd;
  }

  /** Returns true if this program runs in reverse (for finding match starts). */
  public boolean reversed() {
    return reversed;
  }

  /** Sets whether this program runs in reverse. */
  public void setReversed(boolean reversed) {
    this.reversed = reversed;
  }

  /** Returns the number of loop progress-check registers allocated by the compiler. */
  public int numLoopRegs() {
    return numLoopRegs;
  }

  /** Sets the number of loop progress-check registers. */
  public void setNumLoopRegs(int numLoopRegs) {
    this.numLoopRegs = numLoopRegs;
  }

  boolean requiresPikeNfaCaptureSemantics() {
    return requiresPikeNfaCaptureSemantics;
  }

  void setRequiresPikeNfaCaptureSemantics(boolean requiresPikeNfaCaptureSemantics) {
    this.requiresPikeNfaCaptureSemantics = requiresPikeNfaCaptureSemantics;
  }

  /**
   * Returns true if Unix lines mode is active. When true, only {@code '\n'} is recognized as a line
   * terminator. When false (default), all JDK line terminators are recognized.
   */
  public boolean unixLines() {
    return unixLines;
  }

  /** Sets whether Unix lines mode is active. */
  public void setUnixLines(boolean unixLines) {
    this.unixLines = unixLines;
  }

  /**
   * Returns true if the program contains EMPTY_WIDTH instructions with {@link
   * EmptyOp#WORD_BOUNDARY}, {@link EmptyOp#NON_WORD_BOUNDARY}, {@link
   * EmptyOp#UNICODE_WORD_BOUNDARY}, or {@link EmptyOp#UNICODE_NON_WORD_BOUNDARY} flags. The DFA
   * handles these as deferred assertions that are re-expanded when the word-character context is
   * known.
   */
  public boolean hasWordBoundary() {
    int mask =
        EmptyOp.WORD_BOUNDARY
            | EmptyOp.NON_WORD_BOUNDARY
            | EmptyOp.UNICODE_WORD_BOUNDARY
            | EmptyOp.UNICODE_NON_WORD_BOUNDARY;
    int n = size();
    for (int i = 0; i < n; i++) {
      Inst ip = inst(i);
      if (ip.op == InstOp.EMPTY_WIDTH && (ip.arg & mask) != 0) {
        return true;
      }
    }
    return false;
  }

  boolean hasTextAnchor() {
    if (anchorStart || anchorEnd) {
      return true;
    }
    int mask =
        EmptyOp.BEGIN_LINE
            | EmptyOp.END_LINE
            | EmptyOp.BEGIN_TEXT
            | EmptyOp.END_TEXT
            | EmptyOp.DOLLAR_END;
    int n = size();
    for (int i = 0; i < n; i++) {
      Inst ip = inst(i);
      if (ip.op == InstOp.EMPTY_WIDTH && (ip.arg & mask) != 0) {
        return true;
      }
    }
    return false;
  }

  /** Returns true if this program contains grapheme-sensitive matching. */
  boolean hasGraphemeSemantics() {
    return hasGraphemeSemantics;
  }

  /** Returns true if this program contains a consuming {@code \X} instruction. */
  boolean hasGraphemeClusterInstruction() {
    return hasGraphemeClusterInstruction;
  }

  private boolean computeHasGraphemeSemantics() {
    int n = instArray.length;
    for (int i = 0; i < n; i++) {
      Inst ip = instArray[i];
      if (ip.op == InstOp.GRAPHEME_CLUSTER
          || (ip.op == InstOp.EMPTY_WIDTH
              && (ip.arg
                      & (EmptyOp.GRAPHEME_CLUSTER_BOUNDARY
                          | EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY))
                  != 0)) {
        return true;
      }
    }
    return false;
  }

  private boolean computeHasGraphemeClusterInstruction() {
    int n = instArray.length;
    for (int i = 0; i < n; i++) {
      if (instArray[i].op == InstOp.GRAPHEME_CLUSTER) {
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
      sb.append(
          String.format(
              Locale.ROOT, "%d. %s\n", i, instArray != null ? instArray[i] : instructions.get(i)));
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return String.format(
        Locale.ROOT,
        "Prog{size=%d, start=%d, startUnanchored=%d, captures=%d}",
        size(),
        start,
        startUnanchored,
        numCaptures);
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
    boolean[] reachable = computeReachableInstructions();

    for (int i = 0; i < n; i++) {
      Inst inst = inst(i);
      if (!reachable[i] || (inst.op != InstOp.ALT && inst.op != InstOp.ALT_MATCH)) {
        continue;
      }
      if (canReachSelfViaEpsilon(i, reachable)) {
        inCycle[i] = true;
      }
    }
    return inCycle;
  }

  private boolean[] computeReachableInstructions() {
    int n = size();
    boolean[] reachable = new boolean[n];
    ArrayDeque<Integer> stack = new ArrayDeque<>();
    addSuccessor(start, reachable, stack);
    addSuccessor(startUnanchored, reachable, stack);

    while (!stack.isEmpty()) {
      int id = stack.pop();
      Inst inst = inst(id);
      switch (inst.op) {
        case ALT, ALT_MATCH, PROGRESS_CHECK -> {
          addSuccessor(inst.out, reachable, stack);
          addSuccessor(inst.out1, reachable, stack);
        }
        case NOP, CAPTURE, EMPTY_WIDTH, CHAR_RANGE, CHAR_CLASS ->
            addSuccessor(inst.out, reachable, stack);
        default -> {
          // MATCH and FAIL terminate.
        }
      }
    }
    return reachable;
  }

  /**
   * Returns true if instruction {@code target} can reach itself via a path that consists entirely
   * of epsilon transitions (ALT, ALT_MATCH, NOP, CAPTURE, EMPTY_WIDTH). CHAR_RANGE and MATCH
   * consume input or terminate, so they break any epsilon path.
   */
  private boolean canReachSelfViaEpsilon(int target, boolean[] reachable) {
    int n = size();
    boolean[] visited = new boolean[n];
    ArrayDeque<Integer> stack = new ArrayDeque<>();

    // Seed with epsilon successors of target (don't count target itself as a starting node).
    addEpsilonSuccessors(target, reachable, visited, stack);

    while (!stack.isEmpty()) {
      int id = stack.pop();
      if (id == target) {
        return true;
      }
      addEpsilonSuccessors(id, reachable, visited, stack);
    }
    return false;
  }

  private void addEpsilonSuccessors(
      int id, boolean[] reachable, boolean[] visited, ArrayDeque<Integer> stack) {
    Inst inst = inst(id);
    switch (inst.op) {
      case ALT, ALT_MATCH -> {
        addEpsilonSuccessor(inst.out, reachable, visited, stack);
        addEpsilonSuccessor(inst.out1, reachable, visited, stack);
      }
      case NOP, CAPTURE, EMPTY_WIDTH -> {
        addEpsilonSuccessor(inst.out, reachable, visited, stack);
      }
      default -> {
        // CHAR_RANGE, MATCH, FAIL: not epsilon transitions.
      }
    }
  }

  private static void addEpsilonSuccessor(
      int id, boolean[] reachable, boolean[] visited, ArrayDeque<Integer> stack) {
    if (id > 0 && id < reachable.length && reachable[id] && !visited[id]) {
      visited[id] = true;
      stack.push(id);
    }
  }

  private static void addSuccessor(int id, boolean[] reachable, ArrayDeque<Integer> stack) {
    if (id > 0 && id < reachable.length && !reachable[id]) {
      reachable[id] = true;
      stack.push(id);
    }
  }

  public void flatten() {
    if (didFlatten) {
      return;
    }

    int ninst = instructions.size();
    int[] rootmap = new int[ninst];
    Arrays.fill(rootmap, -1);
    List<Integer> roots = new ArrayList<>();

    List<List<Integer>> predecessors = new ArrayList<>(ninst);
    for (int i = 0; i < ninst; i++) {
      predecessors.add(new ArrayList<>());
    }

    boolean[] reachable = new boolean[ninst];
    List<Integer> reachableList = new ArrayList<>();
    List<Integer> stack = new ArrayList<>();

    // Pass 1: Mark successor roots and predecessors.
    markSuccessors(rootmap, roots, predecessors, reachable, reachableList, stack);

    // Pass 2: Mark dominator roots by working backwards.
    List<Integer> sortedRoots = new ArrayList<>(roots);
    Collections.sort(sortedRoots);
    for (int i = sortedRoots.size() - 1; i >= 0; i--) {
      int root = sortedRoots.get(i);
      markDominator(root, rootmap, roots, predecessors, reachable, reachableList, stack);
    }

    // Pass 3: Emit lists. Remap outs to root-ids.
    int[] flatmap = new int[roots.size()];
    List<Inst> flat = new ArrayList<>(ninst);

    for (int r = 0; r < roots.size(); r++) {
      int root = roots.get(r);
      flatmap[r] = flat.size();
      emitList(root, rootmap, flat, reachable, stack);
      flat.get(flat.size() - 1).last = true;
    }

    // Pass 4: Remap outs from root-ids to flat-ids.
    for (Inst inst : flat) {
      if (inst.op == InstOp.ALT_MATCH) {
        // AltMatch's outs are already flat offsets (flat.len() and flat.len() + 1)
      } else if (inst.op == InstOp.PROGRESS_CHECK) {
        inst.out = flatmap[inst.out];
        inst.out1 = flatmap[inst.out1];
      } else {
        inst.out = flatmap[inst.out];
      }
    }

    // Remap startUnanchored and start.
    if (startUnanchored == 0) {
      // do nothing
    } else if (startUnanchored == start) {
      startUnanchored = flatmap[1];
      start = flatmap[1];
    } else {
      startUnanchored = flatmap[1];
      start = flatmap[2];
    }
    instructions.clear();
    instructions.addAll(flat);
    didFlatten = true;
  }

  private void markSuccessors(
      int[] rootmap,
      List<Integer> roots,
      List<List<Integer>> predecessors,
      boolean[] reachable,
      List<Integer> reachableList,
      List<Integer> stack) {

    // Mark the kInstFail instruction.
    rootmap[0] = roots.size();
    roots.add(0);

    // Mark the startUnanchored and start instructions.
    if (rootmap[startUnanchored] == -1) {
      rootmap[startUnanchored] = roots.size();
      roots.add(startUnanchored);
    }
    if (rootmap[start] == -1) {
      rootmap[start] = roots.size();
      roots.add(start);
    }

    Arrays.fill(reachable, false);
    reachableList.clear();
    stack.clear();
    stack.add(startUnanchored);

    int nextId = -1;
    while (!stack.isEmpty() || nextId != -1) {
      int id = nextId;
      if (id == -1) {
        // Index-based remove
        id = stack.remove(stack.size() - 1);
      } else {
        nextId = -1;
      }

      if (reachable[id]) {
        continue;
      }
      reachable[id] = true;
      reachableList.add(id);

      Inst inst = instructions.get(id);
      switch (inst.op) {
        case ALT, ALT_MATCH -> {
          int out = inst.out;
          int out1 = inst.out1;
          for (int o : new int[] {out, out1}) {
            predecessors.get(o).add(id);
          }
          stack.add(out1);
          nextId = out;
        }
        case CHAR_RANGE, CHAR_CLASS, CAPTURE, EMPTY_WIDTH, GRAPHEME_CLUSTER -> {
          int out = inst.out;
          if (rootmap[out] == -1) {
            rootmap[out] = roots.size();
            roots.add(out);
          }
          nextId = out;
        }
        case PROGRESS_CHECK -> {
          int out = inst.out;
          int out1 = inst.out1;
          if (rootmap[out] == -1) {
            rootmap[out] = roots.size();
            roots.add(out);
          }
          if (rootmap[out1] == -1) {
            rootmap[out1] = roots.size();
            roots.add(out1);
          }
          stack.add(out1);
          nextId = out;
        }
        case NOP -> {
          nextId = inst.out;
        }
        case MATCH, FAIL -> {
          // terminates
        }
      }
    }
  }

  private void markDominator(
      int root,
      int[] rootmap,
      List<Integer> roots,
      List<List<Integer>> predecessors,
      boolean[] reachable,
      List<Integer> reachableList,
      List<Integer> stack) {

    Arrays.fill(reachable, false);
    reachableList.clear();
    stack.clear();
    stack.add(root);

    int nextId = -1;
    while (!stack.isEmpty() || nextId != -1) {
      int id = nextId;
      if (id == -1) {
        // Index-based remove
        id = stack.remove(stack.size() - 1);
      } else {
        nextId = -1;
      }

      if (reachable[id]) {
        continue;
      }
      reachable[id] = true;
      reachableList.add(id);

      if (id != root && rootmap[id] != -1) {
        continue;
      }

      Inst inst = instructions.get(id);
      switch (inst.op) {
        case ALT, ALT_MATCH -> {
          stack.add(inst.out1);
          nextId = inst.out;
        }
        case NOP -> {
          nextId = inst.out;
        }
        case CHAR_RANGE,
            CHAR_CLASS,
            CAPTURE,
            EMPTY_WIDTH,
            GRAPHEME_CLUSTER,
            PROGRESS_CHECK,
            MATCH,
            FAIL -> {
          // Leaf/non-epsilon
        }
      }
    }

    for (int i : reachableList) {
      List<Integer> preds = predecessors.get(i);
      for (int pred : preds) {
        if (!reachable[pred]) {
          if (rootmap[i] == -1) {
            rootmap[i] = roots.size();
            roots.add(i);
          }
        }
      }
    }
  }

  private void emitList(
      int root, int[] rootmap, List<Inst> flat, boolean[] reachable, List<Integer> stack) {

    Arrays.fill(reachable, false);
    stack.clear();
    stack.add(root);

    int nextId = -1;
    while (!stack.isEmpty() || nextId != -1) {
      int id = nextId;
      if (id == -1) {
        // Index-based remove
        id = stack.remove(stack.size() - 1);
      } else {
        nextId = -1;
      }

      if (reachable[id]) {
        continue;
      }
      reachable[id] = true;

      if (id != root && rootmap[id] != -1) {
        Inst nop = new Inst();
        nop.initNop(rootmap[id]);
        flat.add(nop);
        continue;
      }

      Inst inst = instructions.get(id);
      switch (inst.op) {
        case ALT -> {
          stack.add(inst.out1);
          nextId = inst.out;
        }
        case ALT_MATCH -> {
          Inst flatAltMatch = new Inst();
          flatAltMatch.op = InstOp.ALT_MATCH;
          flatAltMatch.opCode = InstOp.OP_ALT_MATCH;
          flatAltMatch.out = flat.size();
          flatAltMatch.out1 = flat.size() + 1;
          flat.add(flatAltMatch);

          stack.add(inst.out1);
          nextId = inst.out;
        }
        case NOP -> {
          nextId = inst.out;
        }
        case PROGRESS_CHECK -> {
          Inst newInst = new Inst(inst);
          newInst.out = rootmap[inst.out];
          newInst.out1 = rootmap[inst.out1];
          flat.add(newInst);
        }
        case CAPTURE, EMPTY_WIDTH, CHAR_RANGE, CHAR_CLASS, GRAPHEME_CLUSTER -> {
          Inst newInst = new Inst(inst);
          newInst.out = rootmap[inst.out];
          flat.add(newInst);
        }
        case MATCH, FAIL -> {
          Inst newInst = new Inst(inst);
          flat.add(newInst);
        }
      }
    }
  }
}
