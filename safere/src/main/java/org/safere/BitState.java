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
  static int[] search(Prog prog, String text, boolean anchored, boolean longest,
      boolean endMatch, int nsubmatch) {
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
   * @return submatch positions as {@code int[2*nsubmatch]}, or null if no match. Positions are
   *     char indices into the full text.
   */
  static int[] search(Prog prog, String text, int startPos, boolean anchored, boolean longest,
      boolean endMatch, int nsubmatch) {
    return search(prog, text, startPos, text.length(), anchored, longest, endMatch, nsubmatch);
  }

  /**
   * Searches for a match using bit-state backtracking, with bounded search range.
   *
   * @param prog the compiled program
   * @param text the full input text
   * @param startPos the char index in {@code text} at which to begin searching
   * @param searchLimit upper bound on where to try start positions; only positions up to this
   *     index are tried. The inner search may still match characters beyond this position. Use
   *     {@code text.length()} for unbounded search.
   * @param anchored if true, match must start at {@code startPos}
   * @param longest if true, find the longest match; otherwise find the first (greedy) match
   * @param endMatch if true, match must extend to end of text
   * @param nsubmatch number of submatch groups to track (including group 0)
   * @return submatch positions as {@code int[2*nsubmatch]}, or null if no match. Positions are
   *     char indices into the full text.
   */
  static int[] search(Prog prog, String text, int startPos, int searchLimit, boolean anchored,
      boolean longest, boolean endMatch, int nsubmatch) {
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
  static int[] search(BitState cached, Prog prog, String text, int startPos, int searchLimit,
      boolean anchored, boolean longest, boolean endMatch, int nsubmatch) {
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
    if (cached != null && cached.canReuse(prog, text, ncap)) {
      bs = cached;
      bs.reset(text, textLen, ncap, longest, endMatch);
    } else {
      bs = new BitState(prog, text, ncap, longest, endMatch);
    }

    return bs.doSearch(startPos, searchLimit, anchored);
  }

  /**
   * Returns a BitState instance suitable for the given parameters, either by resetting
   * {@code cached} (if compatible) or by creating a new one.
   */
  static BitState getOrCreate(BitState cached, Prog prog, String text, int endPos, int ncap,
      boolean longest, boolean endMatch) {
    if (cached != null && cached.canReuse(prog, text, ncap)) {
      cached.reset(text, endPos, ncap, longest, endMatch);
      return cached;
    }
    BitState bs = new BitState(prog, text, ncap, longest, endMatch);
    bs.endPos = endPos;
    return bs;
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
    int limit = anchored ? startPos + 1 : Math.min(searchLimit + 1, textLen + 1);
    for (int searchStart = startPos; searchStart < limit; searchStart++) {
      if (trySearch(prog.start(), searchStart)) {
        return bestMatch;
      }
      if (searchStart < textLen) {
        int cp = text.codePointAt(searchStart);
        searchStart += Character.charCount(cp) - 1;
      }
    }
    return null;
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

  /**
   * Visited bitmap: bit {@code (instId * textSlots + pos)} tracks whether the given (instruction,
   * position) pair has been explored. Sized for the full text so the instance can be reused across
   * searches with different start/end bounds.
   */
  private long[] visited;
  private int textSlots;

  /** Tracks which words in the visited bitmap were dirtied, for incremental clearing. */
  private int[] dirtyWords;
  private int dirtyCount;

  /** Which ALT instructions are part of epsilon cycles and need the visited bitmap. */
  private final boolean[] cycleAlts;

  /** Current capture registers. */
  private int[] cap;

  /** Current loop progress-check registers. */
  private int[] loopRegs;

  /** Best match found so far. */
  private int[] bestMatch;

  /** Explicit job stack for backtracking. */
  private int[] jobInstId;
  private int[] jobPos;
  private int jobCount;

  private BitState(Prog prog, String text, int ncap, boolean longest, boolean endMatch) {
    this.prog = prog;
    this.text = text;
    this.textLen = text.length();
    this.endPos = textLen;
    this.longest = longest;
    this.endMatch = endMatch || prog.anchorEnd();
    this.ncap = ncap;
    this.textSlots = textLen + 1;
    this.cycleAlts = prog.epsilonCycleAlts();

    int totalBits = prog.size() * textSlots;
    int visitedLen = (totalBits + 63) / 64;
    this.visited = new long[visitedLen];
    this.dirtyWords = new int[Math.min(visitedLen, 4096)];
    this.dirtyCount = 0;
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
  }

  /**
   * Returns true if (instId, pos) should be explored; marks epsilon-cycle ALTs as visited.
   *
   * <p>Only ALT/ALT_MATCH instructions that participate in epsilon cycles use the visited bitmap.
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
    if (!cycleAlts[instId]) {
      return true; // non-cycle or non-ALT instruction: safe to revisit
    }
    // Cycle ALT: use visited bitmap to prevent infinite epsilon loops.
    int bit = instId * textSlots + pos;
    int word = bit / 64;
    long mask = 1L << (bit % 64);
    if ((visited[word] & mask) != 0) {
      return false; // already visited
    }
    visited[word] |= mask;
    // Track dirty word for incremental clearing.
    if (dirtyCount >= 0 && dirtyCount < dirtyWords.length) {
      dirtyWords[dirtyCount++] = word;
    } else if (dirtyCount >= 0) {
      // Overflow — flag that we need a full clear by setting dirtyCount to -1.
      dirtyCount = -1;
    }
    return true;
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
          int curFlags = Nfa.emptyFlags(text, pos, prog.unixLines());
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
            int cp = text.codePointAt(pos);
            if (ip.matchesChar(cp)) {
              int nextPos = pos + Character.charCount(cp);
              if (shouldVisit(ip.out, nextPos)) {
                push(ip.out, nextPos);
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
                || !Nfa.isAtTrailingLineTerminator(text, pos, prog.unixLines())) {
              break; // must match at the end boundary
            }
          }
          if (ncap > 1) {
            cap[1] = pos; // match end
          }

          if (!matched || (longest && pos > bestMatch[1])) {
            matched = true;
            bestMatch = cap.clone();
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
        && jobInstId.length >= Math.min(totalBits, 4096);
  }

  /**
   * Resets this BitState for a new search, clearing the visited bitmap and capture arrays without
   * reallocating. The caller must verify {@link #canReuse} first.
   */
  private void reset(String text, int endPos, int ncap, boolean longest, boolean endMatch) {
    this.text = text;
    this.textLen = text.length();
    this.endPos = endPos;
    this.longest = longest;
    this.endMatch = endMatch || prog.anchorEnd();
    this.ncap = ncap;
    this.textSlots = textLen + 1;
    this.bestMatch = null;
    this.jobCount = 0;
    // Incrementally clear only dirtied bitmap words (much faster than full Arrays.fill).
    if (dirtyCount > 0) {
      for (int i = 0; i < dirtyCount; i++) {
        visited[dirtyWords[i]] = 0L;
      }
    } else if (dirtyCount == -1) {
      // Overflow — must do a full clear.
      int totalBits = prog.size() * textSlots;
      int usedLen = (totalBits + 63) / 64;
      Arrays.fill(visited, 0, usedLen, 0L);
    }
    dirtyCount = 0;
    Arrays.fill(cap, 0, ncap, -1);
    if (loopRegs.length > 0) {
      Arrays.fill(loopRegs, -1);
    }
  }
}
