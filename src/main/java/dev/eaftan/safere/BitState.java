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
    int textLen = text.length();
    int maxLen = maxTextSize(prog);
    if (maxLen < 0 || textLen > maxLen) {
      return null; // text too large for BitState
    }

    int ncap = 2 * Math.max(nsubmatch, 1);
    BitState bs = new BitState(prog, text, ncap, longest, endMatch);

    // For unanchored search, try each start position until a match is found.
    int limit = anchored ? startPos + 1 : Math.min(searchLimit + 1, textLen + 1);
    for (int searchStart = startPos; searchStart < limit; searchStart++) {
      if (bs.trySearch(prog.start(), searchStart)) {
        return bs.bestMatch;
      }
      // Advance to next code point boundary.
      if (searchStart < textLen) {
        int cp = text.codePointAt(searchStart);
        searchStart += Character.charCount(cp) - 1; // loop increment adds 1
      }
    }
    return null;
  }

  // -------------------------------------------------------------------------
  // Instance fields
  // -------------------------------------------------------------------------

  private final Prog prog;
  private final String text;
  private final int textLen;
  private final boolean longest;
  private final boolean endMatch;
  private final int ncap;

  /** Visited bitmap: bit (instId * (textLen+1) + charPos) tracks whether visited. */
  private final long[] visited;
  private final int textSlots; // textLen + 1

  /** Current capture registers. */
  private final int[] cap;

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
    this.longest = longest;
    // Enforce end-of-text matching if the caller requests it OR if the program's anchor flags
    // indicate it (the compiler strips trailing $ and sets anchorEnd instead).
    this.endMatch = endMatch || prog.anchorEnd();
    this.ncap = ncap;
    this.textSlots = textLen + 1;

    int totalBits = prog.size() * textSlots;
    this.visited = new long[(totalBits + 63) / 64];
    this.cap = new int[ncap];
    Arrays.fill(cap, -1);

    // Job stack: max size is bounded by the visited bitmap (each job is visited at most once).
    int maxJobs = Math.min(totalBits, 4096);
    this.jobInstId = new int[maxJobs];
    this.jobPos = new int[maxJobs];
    this.jobCount = 0;
  }

  /** Returns true if (instId, pos) has been visited; marks it as visited if not. */
  private boolean shouldVisit(int instId, int pos) {
    int bit = instId * textSlots + pos;
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
          if (pos < textLen) {
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
          if (endMatch && pos != textLen) {
            break; // must match entire text
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

  /** Prevents instantiation via no-arg constructor. */
  private BitState() {
    throw new AssertionError("non-instantiable");
  }
}
