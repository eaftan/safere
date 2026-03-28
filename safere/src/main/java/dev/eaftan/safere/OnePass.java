// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * One-pass NFA execution engine. For patterns where the NFA is deterministic (at most one possible
 * path for any input), this engine extracts submatch boundaries in a single linear pass over the
 * input text — no backtracking, no thread management.
 *
 * <p>A pattern is "one-pass" if, after following all epsilon transitions from any state:
 *
 * <ol>
 *   <li>At most one CHAR_RANGE instruction matches any given input code point.
 *   <li>At most one MATCH instruction is reachable.
 *   <li>No instruction is reachable via two different epsilon paths.
 * </ol>
 *
 * <p>One-pass matching only works for <b>anchored</b> searches. For unanchored searches, use the
 * DFA to find the match region first, then run OnePass on that region.
 *
 * <p>This is a port of RE2's {@code onepass.cc}, adapted for Java's Unicode code point model.
 */
final class OnePass {

  /**
   * Maximum number of capture groups the one-pass engine supports (including group 0). This limit
   * exists because capture group tracking is encoded in a bitmask within each action integer.
   * Matches RE2's {@code kMaxCap}.
   */
  static final int MAX_CAPTURE_GROUPS = 16;

  private static final int MAX_CAP_REGS = 2 * MAX_CAPTURE_GROUPS;

  // -------------------------------------------------------------------------
  // Action encoding: each action is packed into a single long.
  //
  //   bits  0-7 : empty-width flags required for this transition
  //   bits  8-27: capture mask (which capture registers to set)
  //   bits 28-63: next state index
  //
  // Special value: NO_ACTION (-1L) means no valid transition.
  // -------------------------------------------------------------------------

  private static final int EMPTY_BITS = 8;
  private static final int CAP_SHIFT = EMPTY_BITS;
  private static final int INDEX_SHIFT = CAP_SHIFT + MAX_CAP_REGS;
  private static final long EMPTY_MASK = (1L << EMPTY_BITS) - 1;
  private static final long CAP_REG_MASK = (1L << MAX_CAP_REGS) - 1;
  private static final long NO_ACTION = -1L;

  private static long encodeAction(int nextState, int capMask, int emptyFlags) {
    return ((long) nextState << INDEX_SHIFT) | ((capMask & CAP_REG_MASK) << CAP_SHIFT)
        | (emptyFlags & EMPTY_MASK);
  }

  private static int actionNextState(long action) {
    return (int) (action >>> INDEX_SHIFT);
  }

  private static int actionCapMask(long action) {
    return (int) ((action >>> CAP_SHIFT) & CAP_REG_MASK);
  }

  private static int actionEmptyFlags(long action) {
    return (int) (action & EMPTY_MASK);
  }

  // -------------------------------------------------------------------------
  // Instance fields
  // -------------------------------------------------------------------------

  /** Transition table: {@code actions[state][eqClass]} = encoded action. */
  private final long[][] actions;

  /** Match actions: {@code matchAction[state]} = encoded action when at a match state. */
  private final long[] matchAction;

  /** Sorted code point boundaries defining equivalence classes. */
  private final int[] boundaries;

  /** Whether the program requires end-of-text matching (stripped trailing {@code $}). */
  private final boolean anchorEnd;

  private OnePass(long[][] actions, long[] matchAction, int[] boundaries, boolean anchorEnd) {
    this.actions = actions;
    this.matchAction = matchAction;
    this.boundaries = boundaries;
    this.anchorEnd = anchorEnd;
  }

  // -------------------------------------------------------------------------
  // Building the one-pass automaton
  // -------------------------------------------------------------------------

  /**
   * Attempts to build a one-pass automaton from the compiled program. Returns {@code null} if the
   * pattern is not one-pass or exceeds the capture group limit.
   */
  static OnePass build(Prog prog) {
    if (prog.start() == 0) {
      return null;
    }
    if (prog.numCaptures() > MAX_CAPTURE_GROUPS) {
      return null;
    }
    // Reject patterns with case-folding CHAR_RANGE instructions; the equivalence class
    // overlap check doesn't account for fold-case semantics.
    for (int i = 0; i < prog.size(); i++) {
      Inst inst = prog.inst(i);
      if (inst.op == InstOp.CHAR_RANGE && inst.foldCase) {
        return null;
      }
    }

    int[] boundaries = buildBoundaries(prog);
    int numClasses = boundaries.length;

    // State table. States are identified by instruction IDs.
    // nodeMap: instruction ID -> state index.
    Map<Integer, Integer> nodeMap = new HashMap<>();
    int stateCount = 0;

    // Pre-allocate generously; we'll trim later.
    int maxStates = prog.size();
    long[][] actions = new long[maxStates][numClasses];
    long[] matchActions = new long[maxStates];
    for (long[] row : actions) {
      Arrays.fill(row, NO_ACTION);
    }
    Arrays.fill(matchActions, NO_ACTION);

    // BFS: process each state (instruction ID).
    Deque<Integer> worklist = new ArrayDeque<>();
    int startInst = prog.start();
    nodeMap.put(startInst, stateCount++);
    worklist.add(startInst);

    while (!worklist.isEmpty()) {
      int instId = worklist.poll();
      int stateIndex = nodeMap.get(instId);

      // Compute epsilon closure from instId.
      // Each entry is a frontier instruction (CHAR_RANGE or MATCH) plus accumulated conditions.
      boolean[] visited = new boolean[prog.size()];
      Deque<int[]> stack = new ArrayDeque<>();
      // stack entries: [instId, capMask, emptyFlags]
      stack.push(new int[] {instId, 0, 0});

      while (!stack.isEmpty()) {
        int[] entry = stack.pop();
        int id = entry[0];
        int capMask = entry[1];
        int emptyFlags = entry[2];

        if (id == 0 || id >= prog.size()) {
          continue;
        }
        if (visited[id]) {
          // Same instruction reachable via two epsilon paths -> not one-pass.
          return null;
        }
        visited[id] = true;

        Inst ip = prog.inst(id);
        switch (ip.op) {
          case FAIL -> {}
          case ALT, ALT_MATCH -> {
            stack.push(new int[] {ip.out, capMask, emptyFlags});
            stack.push(new int[] {ip.out1, capMask, emptyFlags});
          }
          case NOP -> stack.push(new int[] {ip.out, capMask, emptyFlags});
          case CAPTURE -> {
            int reg = ip.arg;
            int newCapMask = (reg < MAX_CAP_REGS) ? (capMask | (1 << reg)) : capMask;
            stack.push(new int[] {ip.out, newCapMask, emptyFlags});
          }
          case EMPTY_WIDTH -> {
            int newEmpty = emptyFlags | ip.arg;
            stack.push(new int[] {ip.out, capMask, newEmpty});
          }
          case CHAR_RANGE -> {
            // For each equivalence class this CHAR_RANGE covers, set transition.
            for (int cls = 0; cls < numClasses; cls++) {
              int classLo = boundaries[cls];
              int classHi = (cls + 1 < boundaries.length) ? boundaries[cls + 1] - 1
                  : Character.MAX_CODE_POINT;
              // Check overlap.
              if (ip.lo > classHi || ip.hi < classLo) {
                continue;
              }

              // Get or create state for ip.out.
              int nextState;
              if (nodeMap.containsKey(ip.out)) {
                nextState = nodeMap.get(ip.out);
              } else {
                if (stateCount >= maxStates) {
                  return null; // too many states
                }
                nextState = stateCount++;
                nodeMap.put(ip.out, nextState);
                worklist.add(ip.out);
              }

              long action = encodeAction(nextState, capMask, emptyFlags);
              if (actions[stateIndex][cls] != NO_ACTION && actions[stateIndex][cls] != action) {
                // Two different transitions for the same equivalence class -> not one-pass.
                return null;
              }
              actions[stateIndex][cls] = action;
            }
          }
          case MATCH -> {
            long action = encodeAction(0, capMask, emptyFlags);
            if (matchActions[stateIndex] != NO_ACTION && matchActions[stateIndex] != action) {
              // Two match paths with different conditions -> not one-pass.
              return null;
            }
            matchActions[stateIndex] = action;
          }
          default -> {}
        }
      }
    }

    // Trim tables to actual state count.
    long[][] trimmedActions = Arrays.copyOf(actions, stateCount);
    long[] trimmedMatch = Arrays.copyOf(matchActions, stateCount);
    return new OnePass(trimmedActions, trimmedMatch, boundaries, prog.anchorEnd());
  }

  /** Builds sorted code point boundaries from all CHAR_RANGE instructions. */
  private static int[] buildBoundaries(Prog prog) {
    TreeSet<Integer> bounds = new TreeSet<>();
    bounds.add(0);
    bounds.add(Utils.MAX_RUNE + 1);
    for (int i = 0; i < prog.size(); i++) {
      Inst inst = prog.inst(i);
      if (inst.op == InstOp.CHAR_RANGE) {
        bounds.add(inst.lo);
        if (inst.hi < Utils.MAX_RUNE) {
          bounds.add(inst.hi + 1);
        }
      }
    }
    return bounds.stream().mapToInt(Integer::intValue).toArray();
  }

  // -------------------------------------------------------------------------
  // Search
  // -------------------------------------------------------------------------

  /**
   * Searches for a match in the given text starting at position 0. Convenience overload that
   * delegates to {@link #search(String, int, int, boolean, int)}.
   *
   * @param text the input text
   * @param endMatch if true, the match must cover the entire text
   * @param nsubmatch number of submatch groups to track (including group 0)
   * @return submatch positions as {@code int[2*nsubmatch]}, or null if no match
   */
  int[] search(String text, boolean endMatch, int nsubmatch) {
    return search(text, 0, text.length(), endMatch, nsubmatch);
  }

  /**
   * Searches for an anchored match in the text starting from {@code startPos}, scanning up to
   * {@code endPos}. This is equivalent to running OnePass on {@code text.substring(startPos,
   * endPos)} but avoids the substring allocation. Positions in the returned array are relative to
   * the original {@code text}.
   *
   * <p>Empty-width assertions ({@code \b}, {@code ^}, {@code $}) are evaluated against the full
   * text, preserving correct boundary semantics even when searching a sub-range.
   *
   * @param text the full input text
   * @param startPos position in {@code text} at which to anchor the match
   * @param endPos upper scan bound (exclusive); the match cannot consume characters beyond this
   * @param endMatch if true, the match must extend to exactly {@code endPos}
   * @param nsubmatch number of submatch groups to track (including group 0)
   * @return submatch positions relative to {@code text}, or null if no match
   */
  int[] search(String text, int startPos, int endPos, boolean endMatch, int nsubmatch) {
    int ncap = 2 * Math.max(nsubmatch, 1);
    int[] cap = new int[ncap];
    Arrays.fill(cap, -1);
    cap[0] = startPos;

    int state = 0;
    boolean matched = false;
    int[] bestCap = null;

    int pos = startPos;
    while (pos <= endPos) {
      // Check match condition at current state BEFORE consuming next character.
      long matchAct = matchAction[state];
      if (matchAct != NO_ACTION) {
        int reqEmpty = actionEmptyFlags(matchAct);
        int curEmpty = Nfa.emptyFlags(text, pos);
        if ((reqEmpty & ~curEmpty) == 0) {
          applyCaptures(matchAct, pos, cap);
          if (cap.length > 1) {
            cap[1] = pos;
          }
          matched = true;
          bestCap = cap.clone();
        }
      }

      if (pos >= endPos) {
        break;
      }

      int cp = text.codePointAt(pos);
      int nextPos = pos + Character.charCount(cp);
      int cls = classOf(cp);

      long action = (cls >= 0 && cls < actions[state].length) ? actions[state][cls] : NO_ACTION;
      if (action == NO_ACTION) {
        break;
      }

      int reqEmpty = actionEmptyFlags(action);
      if (reqEmpty != 0) {
        int curEmpty = Nfa.emptyFlags(text, pos);
        if ((reqEmpty & ~curEmpty) != 0) {
          break;
        }
      }

      applyCaptures(action, pos, cap);
      state = actionNextState(action);
      pos = nextPos;
    }

    if (!matched) {
      return null;
    }
    if (endMatch && bestCap[1] != endPos) {
      return null;
    }
    if (anchorEnd && bestCap[1] != endPos) {
      return null;
    }
    return Arrays.copyOf(bestCap, ncap);
  }

  /**
   * Unanchored search: scans from {@code startPos} through the text, trying an anchored OnePass
   * match at each position. Returns the first (leftmost) match found, with longest-match (greedy)
   * semantics at that position.
   *
   * @param text the full input text
   * @param startPos first position to try
   * @param searchLimit upper bound on start positions to try
   * @param nsubmatch number of submatch groups to track (including group 0)
   * @return submatch positions relative to {@code text}, or null if no match
   */
  int[] searchUnanchored(String text, int startPos, int searchLimit, int nsubmatch) {
    int textLen = text.length();
    int limit = Math.min(searchLimit, textLen) + 1;
    for (int start = startPos; start < limit; start++) {
      int[] result = search(text, start, textLen, false, nsubmatch);
      if (result != null) {
        return result;
      }
      // Advance to next code point boundary.
      if (start < textLen) {
        int cp = text.codePointAt(start);
        if (Character.charCount(cp) > 1) {
          start++; // skip low surrogate; loop increment handles the rest
        }
      }
    }
    return null;
  }

  /** Maps a code point to its equivalence class index. */
  private int classOf(int cp) {
    int idx = Arrays.binarySearch(boundaries, cp);
    if (idx >= 0) {
      return idx;
    }
    return (-idx - 1) - 1;
  }

  /** Applies capture register updates from an action at the given position. */
  private static void applyCaptures(long action, int pos, int[] cap) {
    int mask = actionCapMask(action);
    for (int reg = 0; mask != 0 && reg < cap.length; reg++) {
      if ((mask & (1 << reg)) != 0) {
        cap[reg] = pos;
        mask &= ~(1 << reg);
      }
    }
  }

  /** Prevents instantiation via no-arg constructor. */
  private OnePass() {
    throw new AssertionError("non-instantiable");
  }
}
