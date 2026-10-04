// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shift-based Scalar DFA engine for small deterministic regular expressions (&le; 10 states).
 *
 * <p>Implements Per Vognsen's Shift DFA model with integrated {@link StateAccelerator} vector
 * acceleration for self-loop states. Instead of looking up transition destinations by indexing
 * array memory with {@code state}, transition tables are indexed by the incoming character {@code
 * c} to retrieve a 64-bit integer packing next-state destinations for all states:
 *
 * <pre>{@code
 * long row = table[c];
 * state = (int) ((row >>> state) & 0x3F);
 * }</pre>
 *
 * <p>This decouples memory load latency from the loop-carried state dependency, reducing the
 * critical-path state transition to a single 1-cycle ALU shift instruction ({@code shrx}) and
 * achieving ~1 byte / clock cycle (~4.5 GB/s) scalar throughput. When in a self-loop state, it
 * fast-forwards using vector SIMD intrinsics (~30 GB/s).
 */
final class ShiftDfa {

  static final int MAX_STATES = 10;
  static final int STATE_SHIFT_STEP = 6;
  static final int DEAD_STATE = MAX_STATES * STATE_SHIFT_STEP; // 60
  static final int STATE_MASK = 0x3F;

  private final long[] table;
  private final long acceptMask;
  private final int initialShiftState;
  private final int numStates;
  private final StateAccelerator[] accelerators;

  private ShiftDfa(
      long[] table,
      long acceptMask,
      int initialShiftState,
      int numStates,
      StateAccelerator[] accelerators) {
    this.table = table;
    this.acceptMask = acceptMask;
    this.initialShiftState = initialShiftState;
    this.numStates = numStates;
    this.accelerators = accelerators;
  }

  int numStates() {
    return numStates;
  }

  StateAccelerator[] accelerators() {
    return accelerators;
  }

  /**
   * Attempts to compile the compiled {@link Prog} into a 64-bit {@link ShiftDfa}.
   *
   * @param prog the compiled NFA program
   * @return a compiled {@link ShiftDfa} if the DFA determinizes to &le; 10 states, or {@code null}
   *     if it exceeds 10 states or contains unsupported constructs.
   */
  static ShiftDfa compile(Prog prog) {
    if (prog == null || prog.numCaptures() > 1) {
      return null; // Only group 0 supported
    }
    if (prog.hasGraphemeSemantics()
        || prog.hasWordBoundary()
        || prog.anchorEnd()
        || prog.dollarAnchorEnd()) {
      return null;
    }

    int startInst = prog.start();
    if (startInst == 0) {
      return null;
    }

    Builder builder = new Builder(prog);
    builder.stack[builder.stackTop++] = startInst;
    if (!builder.expand()) {
      return null;
    }
    List<int[]> dfaStates = new ArrayList<>(MAX_STATES + 1);
    dfaStates.add(builder.copyFrontier());
    int[] boundaries = asciiBoundaries(prog);

    int[][] transitions = new int[MAX_STATES][128];

    for (int s = 0; s < dfaStates.size(); s++) {
      int[] currentInsts = dfaStates.get(s);

      // Every consuming instruction has constant membership between adjacent boundaries.
      // Expanding one representative therefore gives the transition for the entire interval.
      for (int cls = 0; cls < boundaries.length - 1; cls++) {
        int lo = boundaries[cls];
        int hi = boundaries[cls + 1];
        if (!builder.step(currentInsts, lo)) {
          return null; // Unsupported instruction encountered
        }
        int target;
        if (builder.frontierSize == 0) {
          target = MAX_STATES; // DEAD_STATE index 10
        } else {
          target = builder.findState(dfaStates);
          if (target < 0) {
            if (dfaStates.size() >= MAX_STATES) {
              return null; // State budget exceeded (> 10 states)
            }
            target = dfaStates.size();
            dfaStates.add(builder.copyFrontier());
          }
        }
        Arrays.fill(transitions[s], lo, hi, target);
      }
    }

    int numDfaStates = dfaStates.size();
    long[] table = new long[256];

    for (int c = 0; c < 128; c++) {
      long row = 0L;
      for (int s = 0; s < numDfaStates; s++) {
        int targetState = transitions[s][c] * STATE_SHIFT_STEP;
        row |= ((long) targetState) << (s * STATE_SHIFT_STEP);
      }
      // Dead state slot (slot 10 at bit offset 60) self-loops to dead state (60)
      row |= ((long) DEAD_STATE) << DEAD_STATE;
      table[c] = row;
    }

    // For non-ASCII bytes (128..255), all states transition to DEAD_STATE
    long nonAsciiDeadRow = 0L;
    for (int s = 0; s <= MAX_STATES; s++) {
      nonAsciiDeadRow |= ((long) DEAD_STATE) << (s * STATE_SHIFT_STEP);
    }
    for (int c = 128; c < 256; c++) {
      table[c] = nonAsciiDeadRow;
    }

    long acceptMask = 0L;
    for (int s = 0; s < numDfaStates; s++) {
      if (hasMatch(prog, dfaStates.get(s))) {
        acceptMask |= (1L << (s * STATE_SHIFT_STEP));
      }
    }

    // Analyze StateAccelerator for self-loop states
    StateAccelerator[] accelerators = null;
    for (int s = 0; s < numDfaStates; s++) {
      int selfLoopCount = 0;
      int escapeCount = 0;
      int[] escapes = new int[4];

      for (int c = 0; c < 128; c++) {
        if (transitions[s][c] == s) {
          selfLoopCount++;
        } else {
          if (escapeCount < 4) {
            escapes[escapeCount] = c;
          }
          escapeCount++;
        }
      }

      // Only accelerate if this is genuinely a dominant self-loop state (>= 120 self-loops)
      StateAccelerator acc = null;
      if (selfLoopCount >= 120 && escapeCount >= 1 && escapeCount <= 3) {
        if (escapeCount == 1) {
          acc = new StateAccelerator.SingleAsciiEscape(escapes[0]);
        } else if (escapeCount == 2) {
          acc = new StateAccelerator.AsciiPairEscape(escapes[0], escapes[1]);
        } else {
          acc = new StateAccelerator.AsciiTripleEscape(escapes[0], escapes[1], escapes[2]);
        }
      }

      if (acc != null) {
        if (accelerators == null) {
          accelerators = new StateAccelerator[numDfaStates];
        }
        accelerators[s] = acc;
      }
    }

    return new ShiftDfa(table, acceptMask, 0, numDfaStates, accelerators);
  }

  private static int[] asciiBoundaries(Prog prog) {
    boolean[] boundary = new boolean[129];
    boundary[0] = true;
    boundary[128] = true;
    for (int id = 1; id < prog.size(); id++) {
      Inst ip = prog.inst(id);
      if (ip.opCode == InstOp.OP_CHAR_RANGE) {
        addAsciiBoundaries(boundary, ip.lo, ip.hi);
      } else if (ip.opCode == InstOp.OP_CHAR_CLASS && ip.ranges != null) {
        for (int i = 0; i < ip.ranges.length && ip.ranges[i] < 128; i += 2) {
          addAsciiBoundaries(boundary, ip.ranges[i], ip.ranges[i + 1]);
        }
      }
    }
    int[] boundaries = new int[129];
    int count = 0;
    for (int c = 0; c <= 128; c++) {
      if (boundary[c]) {
        boundaries[count++] = c;
      }
    }
    return Arrays.copyOf(boundaries, count);
  }

  private static void addAsciiBoundaries(boolean[] boundary, int lo, int hi) {
    if (lo < 128 && hi >= 0) {
      boundary[Math.max(0, lo)] = true;
      boundary[Math.min(127, hi) + 1] = true;
    }
  }

  private static boolean hasMatch(Prog prog, int[] insts) {
    for (int id : insts) {
      if (prog.inst(id).opCode == InstOp.OP_MATCH) {
        return true;
      }
    }
    return false;
  }

  /** Compilation-local scratch; only interned DFA frontiers receive an owned array. */
  private static final class Builder {
    private final Prog prog;
    private final int[] stack;
    private final int[] frontier;
    private final int[] visited;
    private int stackTop;
    private int frontierSize;
    private int generation;

    Builder(Prog prog) {
      this.prog = prog;
      stack = new int[prog.size() * 2 + 16];
      frontier = new int[prog.size()];
      visited = new int[prog.size()];
    }

    int[] copyFrontier() {
      return Arrays.copyOf(frontier, frontierSize);
    }

    int findState(List<int[]> states) {
      for (int i = 0; i < states.size(); i++) {
        int[] state = states.get(i);
        if (Arrays.equals(state, 0, state.length, frontier, 0, frontierSize)) {
          return i;
        }
      }
      return -1;
    }

    boolean expand() {
      frontierSize = 0;
      // There are at most 1 + MAX_STATES * 128 expansions, so the stamp cannot overflow.
      generation++;
      while (stackTop > 0) {
        int id = stack[--stackTop];
        if (id == 0 || id >= prog.size() || visited[id] == generation) {
          continue;
        }
        visited[id] = generation;

        Inst ip = prog.inst(id);
        switch (ip.opCode) {
          case InstOp.OP_FAIL -> {}
          case InstOp.OP_ALT, InstOp.OP_ALT_MATCH, InstOp.OP_PROGRESS_CHECK -> {
            stack[stackTop++] = ip.out1;
            stack[stackTop++] = ip.out;
          }
          case InstOp.OP_NOP, InstOp.OP_CAPTURE -> stack[stackTop++] = ip.out;
          case InstOp.OP_CHAR_RANGE -> {
            if (ip.hi >= 128) {
              return false; // Non-ASCII character range not supported in ShiftDfa
            }
            frontier[frontierSize++] = id;
          }
          case InstOp.OP_CHAR_CLASS -> {
            if (ip.ranges != null
                && ip.ranges.length > 0
                && ip.ranges[ip.ranges.length - 1] >= 128) {
              return false; // Non-ASCII character class not supported in ShiftDfa
            }
            frontier[frontierSize++] = id;
          }
          case InstOp.OP_MATCH -> frontier[frontierSize++] = id;
          default -> {
            return false; // Unsupported instruction (e.g. EMPTY_WIDTH, GRAPHEME_CLUSTER)
          }
        }
      }
      Arrays.sort(frontier, 0, frontierSize);
      return true;
    }

    boolean step(int[] currentInsts, int c) {
      stackTop = 0;
      for (int id : currentInsts) {
        Inst ip = prog.inst(id);
        if (ip.opCode == InstOp.OP_CHAR_RANGE) {
          if (ip.lo <= c && c <= ip.hi) {
            stack[stackTop++] = ip.out;
          }
        } else if (ip.opCode == InstOp.OP_CHAR_CLASS) {
          long bitmap = c < 64 ? ip.bitmap0 : ip.bitmap1;
          if ((bitmap & (1L << (c & 63))) != 0) {
            stack[stackTop++] = ip.out;
          }
        }
      }
      return expand();
    }
  }

  /** Matches full input string from {@code start} to {@code end}. */
  boolean matches(String text, int start, int end) {
    int state = initialShiftState;
    final long[] tab = this.table;
    final StateAccelerator[] accs = this.accelerators;
    StringInputScanner scanner = accs != null ? new StringInputScanner(text) : null;
    int i = start;
    while (i < end) {
      if (accs != null && end - i >= 16) {
        StateAccelerator acc = accs[state / STATE_SHIFT_STEP];
        if (acc != null) {
          int nextPos = StateAccelerator.findNextAsciiOrNonAsciiEscape(acc, scanner, i, end);
          if (nextPos == -1) {
            break;
          }
          if (nextPos > i) {
            i = nextPos;
            if (i >= end) {
              break;
            }
          }
        }
      }
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      char c = text.charAt(i++);
      if (c >= 128) {
        return false;
      }
      state = (int) ((tab[c] >>> state) & STATE_MASK);
      if (state == DEAD_STATE) {
        return false;
      }
    }
    return (acceptMask & (1L << state)) != 0;
  }

  /** Matches full input UTF-8 byte scanner from {@code start} to {@code end}. */
  boolean matches(Utf8InputScanner scanner, int start, int end) {
    int state = initialShiftState;
    final long[] tab = this.table;
    final StateAccelerator[] accs = this.accelerators;
    byte[] bytes = scanner.bytes();
    int i = start;
    while (i < end) {
      if (accs != null && end - i >= 16) {
        StateAccelerator acc = accs[state / STATE_SHIFT_STEP];
        if (acc != null) {
          int nextPos = StateAccelerator.findNextAsciiOrNonAsciiEscape(acc, scanner, i, end);
          if (nextPos == -1) {
            break;
          }
          if (nextPos > i) {
            i = nextPos;
            if (i >= end) {
              break;
            }
          }
        }
      }
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      int b = bytes[scanner.offset() + i++] & 0xFF;
      state = (int) ((tab[b] >>> state) & STATE_MASK);
      if (state == DEAD_STATE) {
        return false;
      }
    }
    return (acceptMask & (1L << state)) != 0;
  }
}
