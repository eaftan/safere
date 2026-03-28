// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

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
    int rangeSlots = textLen + 1;
    BitState bs;
    if (cached != null && cached.canReuse(prog, rangeSlots, ncap)) {
      bs = cached;
      bs.reset(text, 0, textLen, ncap, longest, endMatch);
    } else {
      bs = new BitState(prog, text, ncap, longest, endMatch);
    }

    return bs.doSearch(startPos, searchLimit, anchored);
  }

  /**
   * Returns a BitState instance suitable for the given parameters, either by resetting
   * {@code cached} (if compatible) or by creating a new one.
   */
  static BitState getOrCreate(BitState cached, Prog prog, String text, int startOffset,
      int endPos, int ncap, boolean longest, boolean endMatch) {
    int rangeSlots = endPos - startOffset + 1;
    if (cached != null && cached.canReuse(prog, rangeSlots, ncap)) {
      cached.reset(text, startOffset, endPos, ncap, longest, endMatch);
      return cached;
    }
    // Create a new instance sized for the search range, not the full text.
    BitState bs = new BitState(prog, text, ncap, longest, endMatch);
    bs.startOffset = startOffset;
    bs.endPos = endPos;
    bs.rangeSlots = rangeSlots;
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
  private int startOffset;
  private int rangeSlots;
  private boolean longest;
  private boolean endMatch;
  private int ncap;

  /**
   * Visited bitmap: bit {@code (instId * rangeSlots + (pos - startOffset))} tracks whether
   * the given (instruction, position) pair has been explored. The bitmap only covers
   * positions in {@code [startOffset, endPos]}, keeping it compact for bounded searches.
   */
  private long[] visited;

  /** Which ALT instructions are part of epsilon cycles and need the visited bitmap. */
  private final boolean[] cycleAlts;

  /** Current capture registers. */
  private int[] cap;

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
    this.startOffset = 0;
    this.rangeSlots = textLen + 1;
    this.longest = longest;
    // Enforce end-of-text matching if the caller requests it OR if the program's anchor flags
    // indicate it (the compiler strips trailing $ and sets anchorEnd instead).
    this.endMatch = endMatch || prog.anchorEnd();
    this.ncap = ncap;
    this.cycleAlts = prog.epsilonCycleAlts();

    int totalBits = prog.size() * rangeSlots;
    this.visited = new long[(totalBits + 63) / 64];
    this.cap = new int[ncap];
    Arrays.fill(cap, -1);

    // Job stack: max size is bounded by the visited bitmap (each job is visited at most once).
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
    InstOp op = prog.inst(instId).op;
    if (op != InstOp.ALT && op != InstOp.ALT_MATCH) {
      return true;
    }
    if (!cycleAlts[instId]) {
      return true; // non-cycle ALT: safe to revisit
    }
    // Cycle ALT: use visited bitmap to prevent infinite epsilon loops.
    int bit = instId * rangeSlots + (pos - startOffset);
    int word = bit / 64;
    long mask = 1L << (bit % 64);
    if ((visited[word] & mask) != 0) {
      return false; // already visited
    }
    visited[word] |= mask;
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

    // Initialize captures.
    Arrays.fill(cap, -1);
    if (ncap > 0) {
      cap[0] = startPos;
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

      // Negative IDs are capture-restore sentinels: restore cap[-id-1] to pos.
      if (id < 0) {
        cap[-id - 1] = pos;
        continue;
      }

      Inst ip = prog.inst(id);
      switch (ip.op) {
        case FAIL -> {}

        case ALT, ALT_MATCH -> {
          // Push second alternative first (it will be tried if first fails).
          if (shouldVisit(ip.out1, pos)) {
            push(ip.out1, pos);
          }
          // Then push first alternative (tried first due to stack LIFO).
          if (shouldVisit(ip.out, pos)) {
            push(ip.out, pos);
          }
        }

        case NOP -> {
          if (shouldVisit(ip.out, pos)) {
            push(ip.out, pos);
          }
        }

        case CAPTURE -> {
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

        case EMPTY_WIDTH -> {
          int curFlags = Nfa.emptyFlags(text, pos);
          if ((ip.arg & ~curFlags) == 0) {
            if (shouldVisit(ip.out, pos)) {
              push(ip.out, pos);
            }
          }
        }

        case CHAR_RANGE -> {
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

        case MATCH -> {
          if (endMatch && pos != endPos) {
            break; // must match at the end boundary
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
   * the program is the same and the pre-allocated arrays are large enough for the search range.
   */
  boolean canReuse(Prog prog, int rangeSlots, int ncap) {
    if (this.prog != prog) {
      return false;
    }
    int totalBits = prog.size() * rangeSlots;
    int requiredVisitedLen = (totalBits + 63) / 64;
    return visited.length >= requiredVisitedLen
        && cap.length >= ncap
        && jobInstId.length >= Math.min(totalBits, 4096);
  }

  /**
   * Resets this BitState for a new search, clearing the visited bitmap and capture arrays without
   * reallocating. The caller must verify {@link #canReuse} first.
   */
  private void reset(String text, int startOffset, int endPos, int ncap, boolean longest,
      boolean endMatch) {
    this.text = text;
    this.textLen = text.length();
    this.endPos = endPos;
    this.startOffset = startOffset;
    this.rangeSlots = endPos - startOffset + 1;
    this.longest = longest;
    this.endMatch = endMatch || prog.anchorEnd();
    this.ncap = ncap;
    this.bestMatch = null;
    this.jobCount = 0;
    // Clear only the portion of the visited bitmap needed for this search range.
    int totalBits = prog.size() * rangeSlots;
    int usedLen = (totalBits + 63) / 64;
    Arrays.fill(visited, 0, usedLen, 0L);
    Arrays.fill(cap, 0, ncap, -1);
  }
}
