// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Arrays;

/**
 * Hybrid stack-and-recursion bit-state backtracking execution engine.
 *
 * <p>Runs the search recursively using the JVM thread call stack for maximum speed, but aborts and
 * restarts the search iteration using a flat iterative solver if the recursion depth exceeds a
 * safety limit, avoiding StackOverflowError.
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
 * <p>Can hot-swap to a flat iterative matching loop if a spill occurs to prevent recursion/spill
 * overhead on the remainder of the search, or can always run in iterative fallback mode if
 * configured via {@link EnginePathOptions}.
 *
 * <p>This is a port of RE2's {@code bitstate.cc}, adapted for Java's Unicode code point model.
 */
final class BitState {

  /** Maximum bitmap size in bits. Limits the product of prog size × text length. */
  private static final int MAX_BITMAP_BITS = 256 * 1024;

  /** Maximum BitState jobs to run per instruction/position slot before falling back to NFA. */
  private static final int MAX_WORK_PER_SLOT = 8;

  /** Maximum depth of recursive call stack before spilling and restarting iteratively. */
  private static final int MAX_RECURSION_DEPTH = 512;

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
    EnginePathOptions options = EnginePathOptions.allEnabled();
    if (cached != null && cached.canReuse(prog, text, ncap)) {
      bs = cached;
      bs.reset(text, textLen, ncap, longest, endMatch, options);
    } else {
      bs = new BitState(prog, text, ncap, longest, endMatch, options);
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
      int endPos,
      int ncap,
      boolean longest,
      boolean endMatch,
      EnginePathOptions options) {
    if (cached != null && cached.canReuse(prog, text, ncap)) {
      cached.reset(text, endPos, ncap, longest, endMatch, options);
      return cached;
    }
    BitState bs = new BitState(prog, text, ncap, longest, endMatch, options);
    bs.endPos = endPos;
    return bs;
  }

  // -------------------------------------------------------------------------
  // Instance fields
  // -------------------------------------------------------------------------

  private final Prog prog;
  private String text;
  private int textLen;
  private int endPos;
  private boolean longest;
  private boolean endMatch;
  private int ncap;
  private GraphemeSupport.Context graphemeContext;
  private EnginePathOptions options;

  /**
   * Visited bitmap: bit {@code (instId * textSlots + pos)} tracks whether the given (instruction,
   * position) pair has been explored. Sized for the full text so the instance can be reused across
   * searches with different start/end bounds.
   */
  private final long[] visited;

  private int textSlots;

  /** Which ALT instructions are part of epsilon cycles and need the visited bitmap. */
  private final boolean[] cycleAlts;

  /** Current capture registers. */
  private final int[] cap;

  /** Current loop progress-check registers. */
  private final int[] loopRegs;

  /** Best match found so far. */
  private int[] bestMatch;

  /** Caller-owned or BitState-owned array that receives successful capture results. */
  private int[] matchResult;

  /** True if a match was found in the current search. */
  private boolean matched;

  /** Work-budget accounting for falling back when BitState backtracking is too expensive. */
  private long stepBudget;

  private long stepCount;
  private boolean budgetExceeded;

  /** Explicit job stack for backtracking spilled jobs. Compacted to single long values. */
  private long[] jobStack;

  private int jobCount;

  /** Spillage hot-swap control state. */
  private boolean useIterativeFallback;

  private BitState(
      Prog prog,
      String text,
      int ncap,
      boolean longest,
      boolean endMatch,
      EnginePathOptions options) {
    this.prog = prog;
    this.text = text;
    this.textLen = text.length();
    this.endPos = textLen;
    this.longest = longest;
    this.endMatch = endMatch || prog.anchorEnd();
    this.ncap = ncap;
    this.textSlots = textLen + 1;
    this.cycleAlts = prog.epsilonCycleAlts();
    this.graphemeContext = GraphemeSupport.Context.create(text, prog.hasGraphemeSemantics());
    this.options = options;

    int totalBits = prog.size() * textSlots;
    int visitedLen = (totalBits + 63) / 64;
    this.visited = new long[visitedLen];

    this.cap = new int[ncap];
    Arrays.fill(cap, -1);
    int nlr = prog.numLoopRegs();
    this.loopRegs = new int[nlr];
    if (nlr > 0) {
      Arrays.fill(loopRegs, -1);
    }

    this.jobStack = new long[64];
    this.jobCount = 0;

    this.stepBudget = Math.max(4096L, (long) MAX_WORK_PER_SLOT * prog.size() * (textLen + 1));
    this.stepCount = 0;
    this.budgetExceeded = false;
    this.matched = false;
    this.useIterativeFallback = false;
  }

  /** Runs the search, coordinating the recursive solver and the iterative fallback engine. */
  int[] doSearch(int startPos, int searchLimit, boolean anchored, int[] resultBuffer) {
    budgetExceeded = false;
    stepCount = 0;
    stepBudget = Math.max(4096L, (long) MAX_WORK_PER_SLOT * prog.size() * (endPos + 1));
    bestMatch = null;
    matched = false;
    matchResult =
        resultBuffer != null && resultBuffer.length >= ncap ? resultBuffer : new int[ncap];
    int limit = anchored ? startPos + 1 : Math.min(searchLimit + 1, textLen + 1);
    for (int searchStart = startPos; searchStart < limit; searchStart++) {
      Arrays.fill(cap, -1);
      if (ncap > 0) {
        cap[0] = searchStart;
      }
      if (loopRegs.length > 0) {
        Arrays.fill(loopRegs, -1);
      }
      jobCount = 0;
      useIterativeFallback = !options.bitStateRecursive();

      if (!useIterativeFallback && shouldVisit(prog.start(), searchStart)) {
        if (walkRecursive(prog.start(), searchStart, 0)) {
          return bestMatch;
        }
      }

      if (useIterativeFallback) {
        // Reset state for clean iterative rerun
        int totalBits = prog.size() * textSlots;
        int usedLen = (totalBits + 63) / 64;
        Arrays.fill(visited, 0, usedLen, 0L);
        Arrays.fill(cap, -1);
        if (ncap > 0) {
          cap[0] = searchStart;
        }
        if (loopRegs.length > 0) {
          Arrays.fill(loopRegs, -1);
        }
        jobCount = 0;
        matched = false;
        bestMatch = null;

        if (shouldVisit(prog.start(), searchStart)) {
          pushIterative(prog.start(), searchStart);
          runIterativeLoop();
          if (budgetExceeded) {
            return null;
          }
          if (matched && !longest) {
            return bestMatch;
          }
        }
      }

      if (budgetExceeded) {
        return null;
      }
      if (matched) {
        return bestMatch;
      }
      if (searchStart < textLen) {
        int cp = text.codePointAt(searchStart);
        searchStart += Character.charCount(cp) - 1;
      }
    }
    return null;
  }

  boolean budgetExceeded() {
    return budgetExceeded;
  }

  /**
   * Returns whether this BitState can be reused for the given parameters. Reuse is possible when
   * the program is the same and the pre-allocated arrays are large enough for the full text.
   */
  boolean canReuse(Prog prog, String text, int ncap) {
    if (this.prog != prog) {
      return false;
    }
    int newTextSlots = text.length() + 1;
    int totalBits = prog.size() * newTextSlots;
    int requiredVisitedLen = (totalBits + 63) / 64;
    return visited.length >= requiredVisitedLen
        && cap.length >= ncap
        && jobStack.length >= Math.min(totalBits, 4096);
  }

  /**
   * Resets this BitState for a new search, clearing the visited bitmap and capture arrays without
   * reallocating. The caller must verify {@link #canReuse} first.
   */
  private void reset(
      String text,
      int endPos,
      int ncap,
      boolean longest,
      boolean endMatch,
      EnginePathOptions options) {
    this.text = text;
    this.textLen = text.length();
    this.endPos = endPos;
    this.longest = longest;
    this.endMatch = endMatch || prog.anchorEnd();
    this.ncap = ncap;
    this.textSlots = textLen + 1;
    this.graphemeContext = GraphemeSupport.Context.create(text, prog.hasGraphemeSemantics());
    this.options = options;
    this.bestMatch = null;
    this.matched = false;
    this.jobCount = 0;
    this.useIterativeFallback = false;
    int totalBits = prog.size() * textSlots;
    int usedLen = (totalBits + 63) / 64;
    Arrays.fill(visited, 0, usedLen, 0L);
    Arrays.fill(cap, 0, ncap, -1);
    if (loopRegs.length > 0) {
      Arrays.fill(loopRegs, -1);
    }
  }

  /** Returns true if (instId, pos) should be explored; marks epsilon-cycle ALTs as visited. */
  private boolean shouldVisit(int instId, int pos) {
    if (!cycleAlts[instId]) {
      return true; // non-cycle or non-ALT instruction: safe to revisit
    }
    int bit = instId * textSlots + pos;
    int word = bit / 64;
    long mask = 1L << (bit % 64);
    if ((visited[word] & mask) != 0) {
      return false; // already visited
    }
    visited[word] |= mask;
    return true;
  }

  /** Pushes a job onto the iterative backtracking stack (uses restore sentinels). */
  private void pushIterative(int instId, int pos) {
    if (jobCount >= jobStack.length) {
      int newLen = jobStack.length * 2;
      jobStack = Arrays.copyOf(jobStack, newLen);
    }
    jobStack[jobCount] = ((long) pos << 32) | (instId & 0xFFFFFFFFL);
    jobCount++;
  }

  /** Runs search recursively on the JVM call stack for optimal latency. */
  private boolean walkRecursive(int id, int pos, int depth) {
    if (budgetExceeded || useIterativeFallback) {
      return false;
    }
    if (++stepCount > stepBudget) {
      budgetExceeded = true;
      return false;
    }
    if (depth > MAX_RECURSION_DEPTH) {
      useIterativeFallback = true;
      return false;
    }

    Inst ip = prog.inst(id);
    switch (ip.opCode) {
      case InstOp.OP_FAIL -> {
        return false;
      }

      case InstOp.OP_ALT, InstOp.OP_ALT_MATCH -> {
        boolean visitedOut1 = !shouldVisit(ip.out1, pos);
        if (shouldVisit(ip.out, pos)) {
          if (walkRecursive(ip.out, pos, depth + 1)) {
            return true;
          }
        }
        if (!visitedOut1) {
          return walkRecursive(ip.out1, pos, depth + 1);
        }
        return false;
      }

      case InstOp.OP_NOP -> {
        if (shouldVisit(ip.out, pos)) {
          return walkRecursive(ip.out, pos, depth + 1);
        }
        return false;
      }

      case InstOp.OP_CAPTURE -> {
        int reg = ip.arg;
        int oldVal = -1;
        if (reg < ncap) {
          oldVal = cap[reg];
          cap[reg] = pos;
        }
        if (shouldVisit(ip.out, pos)) {
          if (walkRecursive(ip.out, pos, depth + 1)) {
            return true;
          }
        }
        if (reg < ncap && !useIterativeFallback) {
          cap[reg] = oldVal; // restore capture register on backtrack
        }
        return false;
      }

      case InstOp.OP_EMPTY_WIDTH -> {
        int curFlags =
            Nfa.emptyFlags(
                text, pos, prog.unixLines(), prog.hasGraphemeSemantics(), graphemeContext);
        if ((ip.arg & ~curFlags) == 0) {
          if (shouldVisit(ip.out, pos)) {
            return walkRecursive(ip.out, pos, depth + 1);
          }
        }
        return false;
      }

      case InstOp.OP_PROGRESS_CHECK -> {
        int reg = ip.arg;
        int saved = loopRegs[reg];
        if (saved == -1) {
          // First visit: must enter body at least once.
          loopRegs[reg] = pos;
          if (shouldVisit(ip.out, pos)) {
            if (walkRecursive(ip.out, pos, depth + 1)) {
              return true;
            }
          }
          if (!useIterativeFallback) {
            loopRegs[reg] = -1;
          }
          return false;
        } else if (saved == pos) {
          // Zero-width body match: only exit.
          if (shouldVisit(ip.out1, pos)) {
            return walkRecursive(ip.out1, pos, depth + 1);
          }
          return false;
        } else {
          // Progress: save and push both paths like ALT.
          loopRegs[reg] = pos;
          boolean nonGreedy = ip.foldCase;
          if (nonGreedy) {
            // Non-greedy: prefer exit. Pre-mark body to prune duplicate paths.
            boolean visitedOut = !shouldVisit(ip.out, pos);
            if (shouldVisit(ip.out1, pos)) {
              if (walkRecursive(ip.out1, pos, depth + 1)) {
                return true;
              }
            }
            if (!visitedOut && !useIterativeFallback) {
              if (walkRecursive(ip.out, pos, depth + 1)) {
                return true;
              }
            }
          } else {
            // Greedy: prefer body. Pre-mark exit to prune duplicate paths.
            boolean visitedOut1 = !shouldVisit(ip.out1, pos);
            if (shouldVisit(ip.out, pos)) {
              if (walkRecursive(ip.out, pos, depth + 1)) {
                return true;
              }
            }
            if (!visitedOut1 && !useIterativeFallback) {
              if (walkRecursive(ip.out1, pos, depth + 1)) {
                return true;
              }
            }
          }
          if (!useIterativeFallback) {
            loopRegs[reg] = saved;
          }
          return false;
        }
      }

      case InstOp.OP_CHAR_RANGE -> {
        if (pos < endPos) {
          int cp = text.codePointAt(pos);
          if (ip.matchesChar(cp)) {
            int nextPos = pos + Character.charCount(cp);
            if (shouldVisit(ip.out, nextPos)) {
              return walkRecursive(ip.out, nextPos, depth + 1);
            }
          }
        }
        return false;
      }

      case InstOp.OP_CHAR_CLASS -> {
        if (pos < endPos) {
          int cp = text.codePointAt(pos);
          if (ip.matchesCharClass(cp)) {
            int nextPos = pos + Character.charCount(cp);
            if (shouldVisit(ip.out, nextPos)) {
              return walkRecursive(ip.out, nextPos, depth + 1);
            }
          }
        }
        return false;
      }

      case InstOp.OP_MATCH -> {
        if (endMatch && pos != endPos) {
          if (!prog.dollarAnchorEnd()
              || !Nfa.isAtTrailingLineTerminator(text, pos, prog.unixLines())) {
            return false;
          }
        }
        if (ncap > 1) {
          cap[1] = pos;
        }
        if (!matched || (longest && pos > bestMatch[1])) {
          matched = true;
          System.arraycopy(cap, 0, matchResult, 0, ncap);
          bestMatch = matchResult;
        }
        return !longest;
      }

      default -> {
        return false;
      }
    }
  }

  /** Consumes jobStack iteratively to resolve remaining matching paths without recursion. */
  private void runIterativeLoop() {
    while (jobCount > 0) {
      if (++stepCount > stepBudget) {
        budgetExceeded = true;
        return;
      }
      jobCount--;
      long job = jobStack[jobCount];
      int id = (int) job;
      int pos = (int) (job >>> 32);

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
          if (shouldVisit(ip.out1, pos)) {
            pushIterative(ip.out1, pos);
          }
          if (shouldVisit(ip.out, pos)) {
            pushIterative(ip.out, pos);
          }
        }

        case InstOp.OP_NOP -> {
          if (shouldVisit(ip.out, pos)) {
            pushIterative(ip.out, pos);
          }
        }

        case InstOp.OP_CAPTURE -> {
          int reg = ip.arg;
          if (reg < ncap) {
            pushIterative(-(reg + 1), cap[reg]);
            cap[reg] = pos;
          }
          if (shouldVisit(ip.out, pos)) {
            pushIterative(ip.out, pos);
          }
        }

        case InstOp.OP_EMPTY_WIDTH -> {
          int curFlags =
              Nfa.emptyFlags(
                  text, pos, prog.unixLines(), prog.hasGraphemeSemantics(), graphemeContext);
          if ((ip.arg & ~curFlags) == 0) {
            if (shouldVisit(ip.out, pos)) {
              pushIterative(ip.out, pos);
            }
          }
        }

        case InstOp.OP_PROGRESS_CHECK -> {
          int reg = ip.arg;
          int saved = loopRegs[reg];
          if (saved == -1) {
            pushIterative(-(ncap + reg + 1), saved);
            loopRegs[reg] = pos;
            if (shouldVisit(ip.out, pos)) {
              pushIterative(ip.out, pos);
            }
          } else if (saved == pos) {
            if (shouldVisit(ip.out1, pos)) {
              pushIterative(ip.out1, pos);
            }
          } else {
            pushIterative(-(ncap + reg + 1), saved);
            loopRegs[reg] = pos;
            boolean nonGreedy = ip.foldCase;
            if (nonGreedy) {
              if (shouldVisit(ip.out, pos)) {
                pushIterative(ip.out, pos);
              }
              if (shouldVisit(ip.out1, pos)) {
                pushIterative(ip.out1, pos);
              }
            } else {
              if (shouldVisit(ip.out1, pos)) {
                pushIterative(ip.out1, pos);
              }
              if (shouldVisit(ip.out, pos)) {
                pushIterative(ip.out, pos);
              }
            }
          }
        }

        case InstOp.OP_CHAR_RANGE -> {
          if (pos < endPos) {
            int cp = text.codePointAt(pos);
            if (ip.matchesChar(cp)) {
              int nextPos = pos + Character.charCount(cp);
              if (shouldVisit(ip.out, nextPos)) {
                pushIterative(ip.out, nextPos);
              }
            }
          }
        }

        case InstOp.OP_CHAR_CLASS -> {
          if (pos < endPos) {
            int cp = text.codePointAt(pos);
            if (ip.matchesCharClass(cp)) {
              int nextPos = pos + Character.charCount(cp);
              if (shouldVisit(ip.out, nextPos)) {
                pushIterative(ip.out, nextPos);
              }
            }
          }
        }

        case InstOp.OP_MATCH -> {
          if (endMatch && pos != endPos) {
            if (!prog.dollarAnchorEnd()
                || !Nfa.isAtTrailingLineTerminator(text, pos, prog.unixLines())) {
              break;
            }
          }
          if (ncap > 1) {
            cap[1] = pos;
          }

          if (!matched || (longest && pos > bestMatch[1])) {
            matched = true;
            System.arraycopy(cap, 0, matchResult, 0, ncap);
            bestMatch = matchResult;
          }

          if (!longest) {
            return;
          }
        }

        default -> {}
      }
    }
  }
}
