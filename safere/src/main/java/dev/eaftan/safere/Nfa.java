// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pike VM NFA execution engine. Simulates all possible NFA threads in lockstep, tracking submatch
 * boundaries. At most one thread exists per NFA state at any time, guaranteeing linear-time
 * matching.
 *
 * <p>This is a port of RE2's {@code nfa.cc}, adapted for Java's Unicode code point model.
 *
 * <p>Usage:
 *
 * <pre>
 *   Regexp re = Parser.parse("(\\w+)@(\\w+)", flags);
 *   Prog prog = Compiler.compile(re);
 *   int[] result = Nfa.search(prog, "user@host", Anchor.UNANCHORED,
 *                             MatchKind.FIRST_MATCH, prog.numCaptures());
 *   // result[0..1] = full match, result[2..3] = group 1, etc.
 * </pre>
 */
final class Nfa {

  /** Anchor mode for matching. */
  enum Anchor {
    /** Match anywhere in the text. */
    UNANCHORED,
    /** Match only at the start of the text. */
    ANCHORED
  }

  /** Match semantics. */
  enum MatchKind {
    /** Leftmost-biased match (Perl-like). Stops at the first match found. */
    FIRST_MATCH,
    /** Leftmost-longest match (POSIX-like). Keeps searching for longer matches. */
    LONGEST_MATCH,
    /** Match must cover the entire text. Implies anchored + longest. */
    FULL_MATCH
  }

  /** A thread in the NFA: an instruction index paired with a capture array. */
  private record Thread(int id, int[] capture) {}

  private final Prog prog;
  private final int ncapture;
  private final boolean longest;
  private final boolean endmatch;

  private boolean matched;
  private int[] bestMatch;

  // Thread queues: ordered lists of threads. The order is the thread priority.
  private List<Thread> runq;
  private List<Thread> nextq;

  private Nfa(Prog prog, int ncapture, boolean longest, boolean endmatch) {
    this.prog = prog;
    this.ncapture = ncapture;
    this.longest = longest;
    this.endmatch = endmatch;
    this.runq = new ArrayList<>();
    this.nextq = new ArrayList<>();
    this.bestMatch = new int[ncapture];
    Arrays.fill(bestMatch, -1);
  }

  /**
   * Searches for a match in the given text, starting from position 0.
   *
   * @param prog the compiled program
   * @param text the input text to search
   * @param anchor whether to anchor the match at the start
   * @param kind the match semantics (first, longest, or full)
   * @param nsubmatch number of submatch groups to track (including group 0 for the full match)
   * @return submatch positions as {@code int[2*nsubmatch]}, or null if no match. Positions are char
   *     indices into the text. {@code result[2*i]} is the start of group i, {@code result[2*i+1]}
   *     is the end. -1 means the group did not participate.
   */
  static int[] search(Prog prog, String text, Anchor anchor, MatchKind kind, int nsubmatch) {
    return search(prog, text, 0, text.length(), anchor, kind, nsubmatch);
  }

  /**
   * Searches for a match in the given text, starting from the specified position.
   *
   * @param prog the compiled program
   * @param text the full input text to search
   * @param startPos the char index in {@code text} at which to begin searching
   * @param anchor whether to anchor the match at {@code startPos}
   * @param kind the match semantics (first, longest, or full)
   * @param nsubmatch number of submatch groups to track (including group 0 for the full match)
   * @return submatch positions as {@code int[2*nsubmatch]}, or null if no match. Positions are char
   *     indices into the full text.
   */
  static int[] search(
      Prog prog, String text, int startPos, Anchor anchor, MatchKind kind, int nsubmatch) {
    return search(prog, text, startPos, text.length(), anchor, kind, nsubmatch);
  }

  /**
   * Searches for a match in the given text, with bounded search range.
   *
   * @param prog the compiled program
   * @param text the full input text to search
   * @param startPos the char index in {@code text} at which to begin searching
   * @param searchLimit upper bound on where to try new thread starts; only positions up to this
   *     index start new NFA threads. Active threads may still advance beyond this position. Use
   *     {@code text.length()} for unbounded search.
   * @param anchor whether to anchor the match at {@code startPos}
   * @param kind the match semantics (first, longest, or full)
   * @param nsubmatch number of submatch groups to track (including group 0 for the full match)
   * @return submatch positions as {@code int[2*nsubmatch]}, or null if no match. Positions are char
   *     indices into the full text.
   */
  static int[] search(Prog prog, String text, int startPos, int searchLimit, Anchor anchor,
      MatchKind kind, int nsubmatch) {
    if (prog.start() == 0) {
      return null;
    }

    boolean anchored = (anchor == Anchor.ANCHORED) || prog.anchorStart();
    boolean longestMode = (kind != MatchKind.FIRST_MATCH);
    boolean endmatch = prog.anchorEnd();

    if (kind == MatchKind.FULL_MATCH) {
      anchored = true;
      endmatch = true;
      if (nsubmatch == 0) {
        nsubmatch = 1;
      }
    }

    // We always need at least capture[0..1] to track the match boundaries.
    int ncapture = 2 * Math.max(nsubmatch, 1);

    Nfa nfa = new Nfa(prog, ncapture, longestMode, endmatch);
    nfa.doSearch(text, startPos, searchLimit, anchored);

    if (!nfa.matched) {
      return null;
    }
    if (kind == MatchKind.FULL_MATCH && nfa.bestMatch[1] != text.length()) {
      return null;
    }

    int[] result = new int[2 * nsubmatch];
    System.arraycopy(nfa.bestMatch, 0, result, 0, Math.min(result.length, nfa.bestMatch.length));
    // Fill any remaining slots with -1.
    for (int i = nfa.bestMatch.length; i < result.length; i++) {
      result[i] = -1;
    }
    return result;
  }

  /**
   * Main search loop. Iterates over each position in the text starting from {@code startPos} (plus
   * one past the end), stepping the NFA. At each position, starts a new thread if appropriate, then
   * advances all existing threads by one character.
   *
   * @param text the full input text
   * @param startPos the char index at which to begin searching
   * @param searchLimit upper bound on positions where new threads are started; active threads may
   *     still advance beyond this position
   * @param anchored whether to anchor the search at {@code startPos}
   */
  private void doSearch(String text, int startPos, int searchLimit, boolean anchored) {
    int textLen = text.length();

    // The set of instruction IDs in each queue, for deduplication in addToThreadq.
    Set<Integer> runqSet = new HashSet<>();
    Set<Integer> nextqSet = new HashSet<>();

    int pos = startPos;
    while (true) {
      int cp = (pos < textLen) ? text.codePointAt(pos) : -1;
      int nextPos = (pos < textLen) ? pos + Character.charCount(cp) : textLen + 1;

      // Start a new thread if there have not been any matches
      // (no point starting new threads to the right of an existing match).
      // Also don't start threads past searchLimit — the DFA has already determined
      // there's no match starting beyond that position.
      if (!matched && pos <= searchLimit && (!anchored || pos == startPos)) {
        int[] cap = new int[ncapture];
        Arrays.fill(cap, -1);
        cap[0] = pos;
        // Always use prog.start() (anchored start). Unanchored matching is achieved
        // by starting a new thread at each position. The startUnanchored() entry point
        // (which includes a .*? prefix) is only for the DFA engine.
        addToThreadq(runq, runqSet, prog.start(), text, pos, cap);
      }

      // If all threads have died, stop if anchored or we already have a match.
      // For unanchored searches without a match, keep trying new positions.
      if (runq.isEmpty()) {
        if (anchored || matched) {
          break;
        }
        // In unanchored mode with no match yet, advance to the next position
        // and try again. Clear the visited set so instructions can be re-added.
        if (pos >= textLen) {
          break;
        }
        runqSet.clear();
        pos = nextPos;
        continue;
      }

      boolean done = step(runq, runqSet, nextq, nextqSet, cp, text, pos, nextPos);

      // Swap queues.
      List<Thread> tmpQ = runq;
      runq = nextq;
      nextq = tmpQ;
      nextq.clear();

      Set<Integer> tmpS = runqSet;
      runqSet = nextqSet;
      nextqSet = tmpS;
      nextqSet.clear();

      if (done) {
        break;
      }

      if (pos >= textLen) {
        break;
      }

      pos = nextPos;
    }
  }

  /**
   * Follows all empty transitions from {@code id0} and enqueues consuming/accepting instructions
   * (CHAR_RANGE and MATCH) into the thread queue.
   *
   * <p>Uses an explicit stack. The visit set {@code visited} prevents re-processing of instructions
   * already in the queue.
   *
   * @param q the thread queue to add to
   * @param visited set of instruction IDs already visited/enqueued
   * @param id0 starting instruction ID
   * @param text the input text
   * @param pos the current position in the text
   * @param t0 the current capture array (shared — will be cloned before mutation)
   */
  private void addToThreadq(
      List<Thread> q, Set<Integer> visited, int id0, String text, int pos, int[] t0) {
    if (id0 == 0) {
      return;
    }

    // Explicit stack. Each entry is (instId, captureArray).
    // We push entries and process them LIFO. The capture array may be shared
    // and is cloned when a CAPTURE instruction modifies it.
    List<int[]> stack = new ArrayList<>();
    stack.add(new int[]{id0, -1}); // -1 = use current t0

    // Parallel list of capture arrays for stack entries that need a specific capture.
    // Index corresponds to stack index. null means "use current t0".
    List<int[]> captureStack = new ArrayList<>();
    captureStack.add(null);

    while (!stack.isEmpty()) {
      int last = stack.size() - 1;
      int[] entry = stack.remove(last);
      int[] entryCap = captureStack.remove(last);

      int id = entry[0];

      if (entryCap != null) {
        t0 = entryCap;
      }

      if (id == 0 || visited.contains(id)) {
        continue;
      }
      visited.add(id);

      Inst ip = prog.inst(id);
      switch (ip.op) {
        case FAIL:
          break;

        case ALT:
          // Push out1 first (lower priority), then out (higher priority).
          stack.add(new int[]{ip.out1, -1});
          captureStack.add(t0);
          stack.add(new int[]{ip.out, -1});
          captureStack.add(t0);
          break;

        case ALT_MATCH:
          // Enqueue this state and also explore the next alt branch.
          q.add(new Thread(id, t0.clone()));
          // Explore the next instruction after this one (the other alt branch).
          stack.add(new int[]{ip.out, -1});
          captureStack.add(t0);
          stack.add(new int[]{ip.out1, -1});
          captureStack.add(t0);
          break;

        case NOP:
          stack.add(new int[]{ip.out, -1});
          captureStack.add(null);
          break;

        case CAPTURE:
          if (ip.arg < ncapture) {
            // Clone the capture and record the current position.
            int[] newCap = t0.clone();
            newCap[ip.arg] = pos;
            stack.add(new int[]{ip.out, -1});
            captureStack.add(newCap);
          } else {
            // Capture register not tracked; just follow the transition.
            stack.add(new int[]{ip.out, -1});
            captureStack.add(null);
          }
          break;

        case EMPTY_WIDTH:
          int flags = emptyFlags(text, pos, prog.unixLines());
          if ((ip.arg & ~flags) == 0) {
            stack.add(new int[]{ip.out, -1});
            captureStack.add(null);
          }
          break;

        case CHAR_RANGE:
        case CHAR_CLASS:
        case MATCH:
          // These are "real" states: enqueue them with a copy of the capture.
          q.add(new Thread(id, t0.clone()));
          break;

        default:
          break;
      }
    }
  }

  /**
   * Processes all threads in {@code rq} for the current input character. Threads at CHAR_RANGE
   * instructions that match the character are advanced to {@code nq}. Threads at MATCH instructions
   * record the match.
   *
   * @return true if the search should stop (first-match found and remaining threads cut off)
   */
  private boolean step(
      List<Thread> rq, Set<Integer> rqSet, List<Thread> nq, Set<Integer> nqSet,
      int cp, String text, int matchPos, int nextPos) {
    nq.clear();
    nqSet.clear();

    for (Thread t : rq) {
      int id = t.id();
      int[] capture = t.capture();

      if (longest && matched && bestMatch[0] != -1 && capture[0] != -1
          && bestMatch[0] < capture[0]) {
        continue;
      }

      Inst ip = prog.inst(id);
      switch (ip.op) {
        case CHAR_RANGE:
          if (cp >= 0 && ip.matchesChar(cp)) {
            addToThreadq(nq, nqSet, ip.out, text, nextPos, capture);
          }
          break;

        case CHAR_CLASS:
          if (cp >= 0 && ip.matchesCharClass(cp)) {
            addToThreadq(nq, nqSet, ip.out, text, nextPos, capture);
          }
          break;

        case MATCH: {
          if (endmatch && matchPos != text.length()) {
            // $ (dollarAnchorEnd) allows ending before a trailing line terminator at text end.
            if (!prog.dollarAnchorEnd()
                || !isAtTrailingLineTerminator(text, matchPos, prog.unixLines())) {
              break;
            }
          }

          if (longest) {
            if (!matched || capture[0] < bestMatch[0]
                || (capture[0] == bestMatch[0] && matchPos > bestMatch[1])) {
              System.arraycopy(capture, 0, bestMatch, 0, ncapture);
              bestMatch[1] = matchPos;
              matched = true;
            }
          } else {
            // First match mode: this is the best match (leftmost, due to priority).
            // Cut off threads that can only find worse matches (remaining runq),
            // but do NOT stop the main loop — threads already in nextq continue.
            System.arraycopy(capture, 0, bestMatch, 0, ncapture);
            bestMatch[1] = matchPos;
            matched = true;
            // Clear remaining runq entries (they can only find worse matches).
            // We break out of the for-each loop; rq will be cleared after the loop.
            return false;
          }
          break;
        }

        case ALT_MATCH:
          // Optimization: if this is the first thread and we want the match, take it.
          if (longest || rq.indexOf(t) == 0) {
            System.arraycopy(capture, 0, bestMatch, 0, ncapture);
            matched = true;
          }
          break;

        default:
          break;
      }
    }
    rq.clear();
    rqSet.clear();
    return false;
  }

  // ---------------------------------------------------------------------------
  // Empty-width flag computation
  // ---------------------------------------------------------------------------

  /**
   * Returns true if the code point is a JDK line terminator character: {@code '\n'}, {@code '\r'},
   * {@code '\u0085'} (NEXT LINE), {@code '\u2028'} (LINE SEPARATOR), or {@code '\u2029'}
   * (PARAGRAPH SEPARATOR).
   */
  static boolean isLineTerminator(int cp) {
    return cp == '\n' || cp == '\r' || cp == '\u0085' || cp == '\u2028' || cp == '\u2029';
  }

  /**
   * Returns true if {@code pos} is at the start of a trailing line terminator sequence that extends
   * to the end of the text. Used for non-multiline {@code $} (dollarAnchorEnd) matching.
   *
   * @param unixLines if true, only {@code '\n'} is recognized as a line terminator
   */
  static boolean isAtTrailingLineTerminator(String text, int pos, boolean unixLines) {
    int len = text.length();
    if (pos < 0 || pos >= len) {
      return false;
    }
    char ch = text.charAt(pos);
    if (unixLines) {
      return ch == '\n' && pos + 1 == len;
    }
    if (ch == '\n' && pos + 1 == len) {
      return true;
    }
    if (ch == '\r') {
      return pos + 1 == len || (pos + 2 == len && text.charAt(pos + 1) == '\n');
    }
    return (ch == '\u0085' || ch == '\u2028' || ch == '\u2029') && pos + 1 == len;
  }

  /**
   * Computes which empty-width assertions hold at the given position in the text.
   *
   * @param text the input text
   * @param pos the position (char index) to check
   * @param unixLines if true, only {@code '\n'} is recognized as a line terminator; otherwise all
   *     JDK line terminators are recognized
   * @return a bitmask of {@link EmptyOp} flags
   */
  static int emptyFlags(String text, int pos, boolean unixLines) {
    int flags = 0;

    // ^ and \A
    // BEGIN_LINE is set at the start of text and after a line terminator, but NOT at
    // end-of-text after a final line terminator. JDK's MULTILINE ^ does not match at the
    // position past the last line terminator when that position is the end of the string.
    // For example, "a\n" has BEGIN_LINE at pos 0 but NOT at pos 2.
    // Also, JDK's MULTILINE ^ does not match at position 0 of an empty string — the empty
    // string has no lines for ^ to match at. BEGIN_TEXT is still set (for \A). See #41.
    if (pos == 0) {
      flags |= EmptyOp.BEGIN_TEXT;
      if (!text.isEmpty()) {
        flags |= EmptyOp.BEGIN_LINE;
      }
    } else if (pos < text.length()) {
      char prev = text.charAt(pos - 1);
      if (unixLines) {
        if (prev == '\n') {
          flags |= EmptyOp.BEGIN_LINE;
        }
      } else {
        // After \n: always a new line (whether standalone or part of \r\n).
        // After \r: new line only if NOT followed by \n (standalone \r).
        // After \u0085, \u2028, \u2029: always a new line.
        if (prev == '\n' || prev == '\u0085' || prev == '\u2028' || prev == '\u2029') {
          flags |= EmptyOp.BEGIN_LINE;
        } else if (prev == '\r' && text.charAt(pos) != '\n') {
          flags |= EmptyOp.BEGIN_LINE;
        }
      }
    }

    // $ and \z
    // END_LINE is set before any line terminator and at end of text (used by MULTILINE $).
    // END_TEXT is set only at end of text (used by \z).
    // DOLLAR_END is set at end of text and also before the trailing line terminator at end of
    // text (used by $ without MULTILINE — JDK's default $ behavior).
    if (pos == text.length()) {
      flags |= EmptyOp.END_TEXT | EmptyOp.END_LINE | EmptyOp.DOLLAR_END;
    } else {
      char ch = text.charAt(pos);
      if (unixLines) {
        if (ch == '\n') {
          flags |= EmptyOp.END_LINE;
          if (pos + 1 == text.length()) {
            flags |= EmptyOp.DOLLAR_END;
          }
        }
      } else if (isLineTerminator(ch)) {
        flags |= EmptyOp.END_LINE;
        if (isAtTrailingLineTerminator(text, pos, false)) {
          flags |= EmptyOp.DOLLAR_END;
        }
      }
    }

    // \b and \B
    boolean prevWord = pos > 0 && isWordChar(text.codePointBefore(pos));
    boolean nextWord = pos < text.length() && isWordChar(text.codePointAt(pos));
    if (prevWord != nextWord) {
      flags |= EmptyOp.WORD_BOUNDARY;
    } else {
      flags |= EmptyOp.NON_WORD_BOUNDARY;
    }

    return flags;
  }

  /** Returns true if the code point is a word character ({@code [A-Za-z0-9_]}). */
  static boolean isWordChar(int c) {
    return ('A' <= c && c <= 'Z') || ('a' <= c && c <= 'z')
        || ('0' <= c && c <= '9') || c == '_';
  }

  private Nfa() {
    throw new AssertionError("non-instantiable");
  }
}
