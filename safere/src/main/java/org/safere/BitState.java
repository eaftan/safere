// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Arrays;

/**
 * Bit-state backtracking execution engine. Uses explicit stack-based backtracking with a visited
 * bitmap to guarantee O(|prog| &times; |text|) time complexity, preventing exponential blowup.
 *
 * <p>The visited bitmap tracks which (instruction, position) pairs have been explored. If a pair
 * has already been visited, it is skipped — this ensures each pair is processed at most once.
 *
 * <p>BitState is faster than the general NFA (Pike VM) for small-to-medium texts because it avoids
 * per-step thread queue management. It is used when:
 *
 * <ul>
 *   <li>The pattern is not one-pass (or requires unanchored matching).
 *   <li>The text is small enough that the visited bitmap fits in memory.
 *   <li>Submatch (capture group) information is needed.
 * </ul>
 *
 * <p>This is a port of RE2's {@code bitstate.cc}, adapted for Java's Unicode code point model.
 */
final class BitState {

  /** Maximum bitmap size in bits. Limits the product of prog size × text length. */
  private static final int MAX_BITMAP_BITS = 256 * 1024;

  /** Maximum BitState jobs to run per instruction/position slot before falling back to NFA. */
  private static final int MAX_WORK_PER_SLOT = 8;

  /**
   * Returns the maximum text length (in chars) for which BitState can be used with the given
   * program, or -1 if the program is too large for BitState.
   */
  static int maxTextSize(Prog prog) {
    int instCount = prog.size();
    if (instCount == 0) {
      return -1;
    }
    return MAX_BITMAP_BITS / instCount - 1;
  }

  /**
   * Searches for a match using bit-state backtracking, starting from position 0.
   *
   * @param prog the compiled program
   * @param text the input text
   * @param anchored if true, match must start at position 0
   * @param longest if true, find the longest match; otherwise find the first (greedy) match
   * @param endMatch if true, match must extend to end of text
   * @param nsubmatch number of submatch groups to track (including group 0)
   * @return submatch positions as {@code int[2*nsubmatch]}, or null if no match
   */
  static int[] search(
      Prog prog, String text, boolean anchored, boolean longest, boolean endMatch, int nsubmatch) {
    return search(prog, text, 0, text.length(), anchored, longest, endMatch, nsubmatch);
  }

  /**
   * Searches for a match using bit-state backtracking, starting from the specified position.
   *
   * @param prog the compiled program
   * @param text the full input text
   * @param startPos the char index in {@code text} at which to begin searching
   * @param anchored if true, match must start at {@code startPos}
   * @param longest if true, find the longest match; otherwise find the first (greedy) match
   * @param endMatch if true, match must extend to end of text
   * @param nsubmatch number of submatch groups to track (including group 0)
   * @return submatch positions as {@code int[2*nsubmatch]}, or null if no match. Positions are char
   *     indices into the full text.
   */
  static int[] search(
      Prog prog,
      String text,
      int startPos,
      boolean anchored,
      boolean longest,
      boolean endMatch,
      int nsubmatch) {
    return search(prog, text, startPos, text.length(), anchored, longest, endMatch, nsubmatch);
  }

  /**
   * Searches for a match using bit-state backtracking, with bounded search range.
   *
   * @param prog the compiled program
   * @param text the full input text
   * @param startPos the char index in {@code text} at which to begin searching
   * @param searchLimit upper bound on where to try start positions; only positions up to this index
   *     are tried. The inner search may still match characters beyond this position. Use {@code
   *     text.length()} for unbounded search.
   * @param anchored if true, match must start at {@code startPos}
   * @param longest if true, find the longest match; otherwise find the first (greedy) match
   * @param endMatch if true, match must extend to end of text
   * @param nsubmatch number of submatch groups to track (including group 0)
   * @return submatch positions as {@code int[2*nsubmatch]}, or null if no match. Positions are char
   *     indices into the full text.
   */
  static int[] search(
      Prog prog,
      String text,
      int startPos,
      int searchLimit,
      boolean anchored,
      boolean longest,
      boolean endMatch,
      int nsubmatch) {
    return search(null, prog, text, startPos, searchLimit, anchored, longest, endMatch, nsubmatch);
  }

  /**
   * Searches using bit-state backtracking, optionally reusing a cached instance to avoid
   * allocations. If {@code cached} is non-null and its arrays are large enough for the current
   * text, it is reset and reused; otherwise a new instance is created.
   *
   * @param cached a previously created BitState to reuse, or null
   * @param prog the compiled program
   * @param text the full input text
   * @param startPos the char index at which to begin searching
   * @param searchLimit upper bound on start positions to try
   * @param anchored if true, match must start at {@code startPos}
   * @param longest if true, find the longest match
   * @param endMatch if true, match must extend to end of text
   * @param nsubmatch number of submatch groups to track (including group 0)
   * @return submatch positions as {@code int[2*nsubmatch]}, or null if no match
   */
  static int[] search(
      BitState cached,
      Prog prog,
      String text,
      int startPos,
      int searchLimit,
      boolean anchored,
      boolean longest,
      boolean endMatch,
      int nsubmatch) {
    return search(
        cached, prog, text, startPos, searchLimit, anchored, longest, endMatch, nsubmatch, null);
  }

  /**
   * Searches using bit-state backtracking, writing successful captures into {@code resultBuffer}
   * when it is large enough. This keeps the mutable backtracking capture registers separate from
   * the returned result while allowing tight find loops to avoid one result-array allocation per
   * match.
   */
  static int[] search(
      BitState cached,
      Prog prog,
      String text,
      int startPos,
      int searchLimit,
      boolean anchored,
      boolean longest,
      boolean endMatch,
      int nsubmatch,
      int[] resultBuffer) {
    return search(
        cached,
        prog,
        new StringInputScanner(text),
        startPos,
        searchLimit,
        anchored,
        longest,
        endMatch,
        nsubmatch,
        resultBuffer);
  }

  static int[] search(
      BitState cached,
      Prog prog,
      InputScanner text,
      int startPos,
      int searchLimit,
      boolean anchored,
      boolean longest,
      boolean endMatch,
      int nsubmatch,
      int[] resultBuffer) {
    int textLen = text.length();
    int maxLen = maxTextSize(prog);
    if (maxLen < 0 || textLen > maxLen) {
      return null; // text too large for BitState
    }

    if (prog.anchorStart()) {
      anchored = true;
    }
    if (prog.anchorEnd()) {
      endMatch = true;
    }

    int ncap = 2 * Math.max(nsubmatch, 1);
    BitState bs;
    if (cached != null && cached.canReuse(prog, text, startPos, textLen, ncap)) {
      bs = cached;
      bs.reset(text, startPos, textLen, ncap, longest, endMatch);
    } else {
      bs = new BitState(prog, text, startPos, textLen, ncap, longest, endMatch);
    }

    return bs.doSearch(startPos, searchLimit, anchored, resultBuffer);
  }

  /**
   * Returns a BitState instance suitable for the given parameters, either by resetting {@code
   * cached} (if compatible) or by creating a new one.
   */
  static BitState getOrCreate(
      BitState cached,
      Prog prog,
      String text,
      int startPos,
      int endPos,
      int ncap,
      boolean longest,
      boolean endMatch) {
    return getOrCreate(
        cached, prog, new StringInputScanner(text), startPos, endPos, ncap, longest, endMatch);
  }

  /**
   * Returns a BitState instance suitable for the given parameters, either by resetting {@code
   * cached} (if compatible) or by creating a new one.
   */
  static BitState getOrCreate(
      BitState cached,
      Prog prog,
      InputScanner text,
      int startPos,
      int endPos,
      int ncap,
      boolean longest,
      boolean endMatch) {
    if (cached != null && cached.canReuse(prog, text, startPos, endPos, ncap)) {
      cached.reset(text, startPos, endPos, ncap, longest, endMatch);
      return cached;
    }
    return new BitState(prog, text, startPos, endPos, ncap, longest, endMatch);
  }

  /**
   * Runs the bit-state search from the given start position.
   *
   * @param startPos the char index at which to begin searching
   * @param searchLimit upper bound on start positions to try
   * @param anchored if true, match must start at {@code startPos}
   * @return submatch positions, or null if no match
   */
  int[] doSearch(int startPos, int searchLimit, boolean anchored) {
    return doSearch(startPos, searchLimit, anchored, null);
  }

  /**
   * Runs the bit-state search from the given start position, writing successful captures into
   * {@code resultBuffer} when it is large enough.
   */
  int[] doSearch(int startPos, int searchLimit, boolean anchored, int[] resultBuffer) {
    budgetExceeded = false;
    stepCount = 0;
    stepBudget = Math.max(4096L, (long) MAX_WORK_PER_SLOT * prog.size() * textSlots);
    bestMatch = null;
    matchResult =
        resultBuffer != null && resultBuffer.length >= ncap ? resultBuffer : new int[ncap];
    int limit = anchored ? startPos + 1 : Math.min(searchLimit + 1, textLen + 1);
    pruneAcrossStarts = !anchored;
    for (int searchStart = startPos; searchStart < limit; searchStart++) {
      if (trySearch(prog.start(), searchStart)) {
        return bestMatch;
      }
      if (budgetExceeded) {
        return null;
      }
      if (pruneAcrossStarts) {
        foldVisitedIntoDead();
      }
      if (searchStart < textLen) {
        searchStart = InputScanner.position(text.decodeForward(searchStart)) - 1;
      }
    }
    return null;
  }

  // -------------------------------------------------------------------------
  // Instance fields
  // -------------------------------------------------------------------------

  private final Prog prog;
  private InputScanner text;
  private int textLen;
  private int endPos;
  private boolean longest;
  private boolean endMatch;
  private int ncap;
  private GraphemeSupport.Context graphemeContext;

  /**
   * Per-start visited bitmap: bit {@code ((pos - basePos) * progSize + instId)} tracks which (ALT,
   * position) pairs the current start position's search has reached. Position-major layout keeps
   * one start's marks contiguous, so folding them into {@link #dead} costs no more than the search
   * that produced them. Sized for the full text so the instance can be reused across searches with
   * different start/end bounds.
   */
  private final long[] visited;

  /**
   * Cross-start pruning bitmap, same layout as {@link #visited}: set for every ALT reached by an
   * earlier start position whose search failed. Whether a match is reachable from an (ALT,
   * position) pair does not depend on capture values, so a pair that led nowhere from one start
   * leads nowhere from any later start either. Checking it makes unanchored search linear (RE2 gets
   * the same effect by never clearing its visited bitmap between starts) while still letting
   * non-cycle ALTs be revisited within a single start, which capture priority requires.
   *
   * <p>PROGRESS_CHECK loop registers do not break this. They only guard nullable loop bodies, so a
   * first visit's body-only branch still reaches the exit through a zero-width iteration, and a
   * path that arrives with the register equal to the current position (exit-only) exists only
   * because the same start already took the body from that position. Either way the failed start
   * explored every future a later start could have through the pruned pair.
   *
   * <p>Allocated unconditionally alongside {@link #visited}, even though anchored-only instances
   * never use it: lazily allocating it on first unanchored use was tried and measured 40-60% slower
   * on unanchored searches (the case that actually prunes), for a memory saving that only applies
   * to anchored-only instances. Not a good trade.
   */
  private final long[] dead;

  /** Dirty word ranges of {@link #visited} and {@link #dead}; clears touch only these words. */
  private int visitedLo;

  private int visitedHi;
  private int deadLo;
  private int deadHi;

  private int textSlots;
  private final int progSize;

  /** Whether the current {@link #doSearch} prunes later starts with {@link #dead}. */
  private boolean pruneAcrossStarts;

  /**
   * Per-instruction kind for {@link #shouldVisit}: 0 = not an ALT, 1 = ALT, 2 = epsilon-cycle ALT.
   */
  private final byte[] altKind;

  private static final byte NOT_ALT = 0;
  private static final byte ALT = 1;
  private static final byte CYCLE_ALT = 2;

  /** Current capture registers. */
  private final int[] cap;

  /** Current loop progress-check registers. */
  private final int[] loopRegs;

  /** Best match found so far. */
  private int[] bestMatch;

  /** Caller-owned or BitState-owned array that receives successful capture results. */
  private int[] matchResult;

  /** Work-budget accounting for falling back when BitState backtracking is too expensive. */
  private long stepBudget;

  private long stepCount;
  private boolean budgetExceeded;

  /** Explicit job stack for backtracking. */
  private int[] jobInstId;

  private int[] jobPos;
  private int jobCount;
  private int basePos;

  private BitState(
      Prog prog,
      InputScanner text,
      int startPos,
      int endPos,
      int ncap,
      boolean longest,
      boolean endMatch) {
    this.prog = prog;
    this.text = text;
    this.textLen = text.length();
    this.basePos = startPos;
    this.endPos = endPos;
    this.longest = longest;
    this.endMatch = endMatch || prog.anchorEnd();
    this.ncap = ncap;
    this.textSlots = (endPos - basePos) + 2;
    this.progSize = prog.size();
    this.graphemeContext = GraphemeSupport.Context.create(text, prog.hasGraphemeSemantics());

    boolean[] cycleAlts = prog.epsilonCycleAlts();
    this.altKind = new byte[progSize];
    for (int i = 0; i < progSize; i++) {
      if (cycleAlts[i]) {
        altKind[i] = CYCLE_ALT;
      } else {
        int op = prog.inst(i).opCode;
        if (op == InstOp.OP_ALT || op == InstOp.OP_ALT_MATCH) {
          altKind[i] = ALT;
        }
      }
    }

    int totalBits = progSize * textSlots;
    int visitedLen = (totalBits + 63) / 64;
    this.visited = new long[visitedLen];
    this.dead = new long[visitedLen];
    this.visitedLo = Integer.MAX_VALUE;
    this.visitedHi = -1;
    this.deadLo = Integer.MAX_VALUE;
    this.deadHi = -1;

    this.cap = new int[ncap];
    Arrays.fill(cap, -1);
    int nlr = prog.numLoopRegs();
    this.loopRegs = new int[nlr];
    if (nlr > 0) {
      Arrays.fill(loopRegs, -1);
    }

    int maxJobs = Math.min(totalBits, 4096);
    this.jobInstId = new int[maxJobs];
    this.jobPos = new int[maxJobs];
    this.jobCount = 0;
    this.stepBudget = Math.max(4096L, (long) MAX_WORK_PER_SLOT * prog.size() * textSlots);
    this.stepCount = 0;
    this.budgetExceeded = false;
  }

  /**
   * Returns true if (instId, pos) should be explored. Records every ALT reached so a failed start
   * can prune later ones via {@link #dead}; within one start, only epsilon-cycle ALTs are blocked.
   *
   * <p>Only ALT/ALT_MATCH instructions that participate in epsilon cycles are blocked on revisit.
   * An epsilon cycle is a path from an ALT back to itself through only epsilon transitions (ALT,
   * NOP, CAPTURE, EMPTY_WIDTH) — without any CHAR_RANGE to consume input. Only these can cause
   * infinite loops.
   *
   * <p>Non-cycle ALTs can be safely revisited. This is critical for nested quantifiers where an
   * inner repetition (e.g., {@code .+?}) and an outer repetition (e.g., {@code *}) share the same
   * ALT entry instruction. If the visited bitmap blocked the shared ALT, the outer repetition could
   * not re-enter its body, causing a premature match.
   *
   * <p>All other instruction types are always revisitable:
   *
   * <ul>
   *   <li>MATCH — terminal, no outgoing edges
   *   <li>FAIL — terminal, no outgoing edges
   *   <li>CAPTURE, NOP, EMPTY_WIDTH — epsilon with a single outgoing edge, cannot form cycles alone
   *   <li>CHAR_RANGE — consumes input (advances position), cannot form cycles
   * </ul>
   */
  private boolean shouldVisit(int instId, int pos) {
    int kind = altKind[instId];
    if (kind == NOT_ALT) {
      return true;
    }
    int bit = (pos - basePos) * progSize + instId;
    int word = bit >>> 6;
    long mask = 1L << (bit & 63);
    if (pruneAcrossStarts) {
      if ((dead[word] & mask) != 0) {
        return false; // an earlier start already exhausted this pair without matching
      }
    } else if (kind == ALT) {
      return true; // non-cycle ALT: safe to revisit
    }
    long w = visited[word];
    if ((w & mask) != 0) {
      // Cycle ALT: block the epsilon loop. Non-cycle ALT: revisitable, and already recorded.
      return kind == ALT;
    }
    visited[word] = w | mask;
    if (word < visitedLo) {
      visitedLo = word;
    }
    if (word > visitedHi) {
      visitedHi = word;
    }
    return true;
  }

  /** Moves the failed start's marks into {@link #dead} and clears them for the next start. */
  private void foldVisitedIntoDead() {
    if (visitedHi < visitedLo) {
      return;
    }
    for (int w = visitedLo; w <= visitedHi; w++) {
      dead[w] |= visited[w];
      visited[w] = 0L;
    }
    if (visitedLo < deadLo) {
      deadLo = visitedLo;
    }
    if (visitedHi > deadHi) {
      deadHi = visitedHi;
    }
    visitedLo = Integer.MAX_VALUE;
    visitedHi = -1;
  }

  /** Pushes a job onto the stack, growing if needed. */
  private void push(int instId, int pos) {
    if (jobCount >= jobInstId.length) {
      int newLen = jobInstId.length * 2;
      jobInstId = Arrays.copyOf(jobInstId, newLen);
      jobPos = Arrays.copyOf(jobPos, newLen);
    }
    jobInstId[jobCount] = instId;
    jobPos[jobCount] = pos;
    jobCount++;
  }

  /**
   * Attempts a search starting from the given instruction and position. Returns true if a match is
   * found (stored in {@link #bestMatch}).
   */
  private boolean trySearch(int startInst, int startPos) {
    boolean matched = false;

    // Initialize captures and loop registers.
    Arrays.fill(cap, -1);
    if (ncap > 0) {
      cap[0] = startPos;
    }
    if (loopRegs.length > 0) {
      Arrays.fill(loopRegs, -1);
    }

    // Seed the search.
    jobCount = 0;
    if (shouldVisit(startInst, startPos)) {
      push(startInst, startPos);
    }

    while (jobCount > 0) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      if (++stepCount > stepBudget) {
        budgetExceeded = true;
        return false;
      }
      jobCount--;
      int id = jobInstId[jobCount];
      int pos = jobPos[jobCount];

      // Negative IDs are restore sentinels: cap or loopReg restore on backtrack.
      // Capture sentinels use -(reg+1) where reg < ncap.
      // Loop-reg sentinels use -(ncap+reg+1) where reg is the loop register index.
      if (id < 0) {
        int idx = -id - 1;
        if (idx < ncap) {
          cap[idx] = pos;
        } else {
          loopRegs[idx - ncap] = pos;
        }
        continue;
      }

      Inst ip = prog.inst(id);
      switch (ip.opCode) {
        case InstOp.OP_FAIL -> {}

        case InstOp.OP_ALT, InstOp.OP_ALT_MATCH -> {
          // Push second alternative first (it will be tried if first fails).
          if (shouldVisit(ip.out1, pos)) {
            push(ip.out1, pos);
          }
          // Then push first alternative (tried first due to stack LIFO).
          if (shouldVisit(ip.out, pos)) {
            push(ip.out, pos);
          }
        }

        case InstOp.OP_NOP -> {
          if (shouldVisit(ip.out, pos)) {
            push(ip.out, pos);
          }
        }

        case InstOp.OP_CAPTURE -> {
          int reg = ip.arg;
          if (reg < ncap) {
            // Push restore sentinel: if we backtrack past this, undo the capture.
            push(-(reg + 1), cap[reg]);
            cap[reg] = pos;
          }
          if (shouldVisit(ip.out, pos)) {
            push(ip.out, pos);
          }
        }

        case InstOp.OP_EMPTY_WIDTH -> {
          int curFlags = Nfa.emptyFlags(text, pos, prog.hasGraphemeSemantics(), graphemeContext);
          if ((ip.arg & ~curFlags) == 0) {
            if (shouldVisit(ip.out, pos)) {
              push(ip.out, pos);
            }
          }
        }

        case InstOp.OP_PROGRESS_CHECK -> {
          int reg = ip.arg;
          int saved = loopRegs[reg];
          if (saved == -1) {
            // First visit: must enter body at least once (plus semantics).
            push(-(ncap + reg + 1), saved);
            loopRegs[reg] = pos;
            if (shouldVisit(ip.out, pos)) {
              push(ip.out, pos);
            }
          } else if (saved == pos) {
            // Zero-width body match: only exit.
            if (shouldVisit(ip.out1, pos)) {
              push(ip.out1, pos);
            }
          } else {
            // Progress: save and push both paths like ALT.
            push(-(ncap + reg + 1), saved);
            loopRegs[reg] = pos;
            boolean nonGreedy = ip.foldCase;
            if (nonGreedy) {
              // Non-greedy: prefer exit. Push body first (lower pri), exit second (higher pri).
              if (shouldVisit(ip.out, pos)) {
                push(ip.out, pos);
              }
              if (shouldVisit(ip.out1, pos)) {
                push(ip.out1, pos);
              }
            } else {
              // Greedy: prefer body. Push exit first (lower pri), body second (higher pri).
              if (shouldVisit(ip.out1, pos)) {
                push(ip.out1, pos);
              }
              if (shouldVisit(ip.out, pos)) {
                push(ip.out, pos);
              }
            }
          }
        }

        case InstOp.OP_CHAR_RANGE -> {
          if (pos < endPos) {
            int cp = WorkCounterConfig.ENABLED ? -1 : text.asciiAt(pos);
            int nextPos = pos + 1;
            if (cp < 0) {
              long decoded = text.decodeForward(pos);
              cp = InputScanner.codePoint(decoded);
              nextPos = InputScanner.position(decoded);
            }
            if (ip.matchesChar(cp)) {
              if (shouldVisit(ip.out, nextPos)) {
                push(ip.out, nextPos);
              }
            }
          }
        }

        case InstOp.OP_CHAR_CLASS -> {
          if (pos < endPos) {
            int cp = WorkCounterConfig.ENABLED ? -1 : text.asciiAt(pos);
            int nextPos = pos + 1;
            if (cp < 0) {
              long decoded = text.decodeForward(pos);
              cp = InputScanner.codePoint(decoded);
              nextPos = InputScanner.position(decoded);
            }
            if (ip.matchesCharClass(cp)) {
              if (shouldVisit(ip.out, nextPos)) {
                push(ip.out, nextPos);
              }
            }
          }
        }

        case InstOp.OP_MATCH -> {
          if (endMatch && pos != endPos) {
            // $ (dollarAnchorEnd) allows ending before a trailing line terminator at the actual
            // text end. Use text.length() (not endPos) because dollarAnchorEnd is a property of
            // the text boundary, not the search range.
            if (!prog.dollarAnchorEnd()
                || !Nfa.isAtTrailingLineTerminator(text, pos, prog.dollarAnchorUnixLines())) {
              break; // must match at the end boundary
            }
          }
          if (ncap > 1) {
            cap[1] = pos; // match end
          }

          if (!matched || (longest && pos > bestMatch[1])) {
            matched = true;
            System.arraycopy(cap, 0, matchResult, 0, ncap);
            bestMatch = matchResult;
          }

          if (!longest) {
            return true; // first match is sufficient
          }
        }

        default -> {}
      }
    }

    return matched;
  }

  /** Returns whether the previous search stopped because BitState exceeded its work budget. */
  boolean budgetExceeded() {
    return budgetExceeded;
  }

  /** Returns the current work budget. Package-private for testing. */
  long stepBudgetForTesting() {
    return stepBudget;
  }

  /**
   * Returns whether this BitState can be reused for the given parameters. Reuse is possible when
   * the program is the same and the pre-allocated arrays are large enough for the full text.
   */
  boolean canReuse(Prog prog, InputScanner text, int startPos, int endPos, int ncap) {
    if (this.prog != prog) {
      return false;
    }
    int newTextSlots = (endPos - startPos) + 2;
    int totalBits = prog.size() * newTextSlots;
    int requiredVisitedLen = (totalBits + 63) / 64;
    return visited.length >= requiredVisitedLen
        && cap.length >= ncap
        && jobInstId.length >= Math.min(totalBits, 4096);
  }

  /** Clears input-dependent references before this object enters the Pattern-level reuse cache. */
  void releaseInput() {
    text = null;
    graphemeContext = null;
  }

  /**
   * Resets this BitState for a new search, clearing the visited bitmap and capture arrays without
   * reallocating. The caller must verify {@link #canReuse} first.
   */
  private void reset(
      InputScanner text, int startPos, int endPos, int ncap, boolean longest, boolean endMatch) {
    this.text = text;
    this.textLen = text.length();
    this.basePos = startPos;
    this.endPos = endPos;
    this.longest = longest;
    this.endMatch = endMatch || prog.anchorEnd();
    this.ncap = ncap;
    this.textSlots = (endPos - basePos) + 2;
    this.graphemeContext = GraphemeSupport.Context.create(text, prog.hasGraphemeSemantics());
    this.bestMatch = null;
    this.jobCount = 0;
    if (visitedHi >= visitedLo) {
      Arrays.fill(visited, visitedLo, visitedHi + 1, 0L);
      visitedLo = Integer.MAX_VALUE;
      visitedHi = -1;
    }
    if (deadHi >= deadLo) {
      Arrays.fill(dead, deadLo, deadHi + 1, 0L);
      deadLo = Integer.MAX_VALUE;
      deadHi = -1;
    }
    Arrays.fill(cap, 0, ncap, -1);
    if (loopRegs.length > 0) {
      Arrays.fill(loopRegs, -1);
    }
  }
}
