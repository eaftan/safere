// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Lazy DFA execution engine. Builds DFA states on demand from the compiled NFA program using the
 * subset construction. Each DFA state represents a set of simultaneously active NFA states.
 *
 * <p>The DFA provides fast boolean matching and match-end detection. It cannot track submatch
 * (capture group) boundaries — that requires the NFA. The typical usage pattern is:
 *
 * <ol>
 *   <li>Use the forward DFA to quickly determine if a match exists and where it ends.
 *   <li>Use the NFA on the narrowed range to extract capture groups.
 * </ol>
 *
 * <p>This is a port of RE2's {@code dfa.cc}, adapted for Java's Unicode code point model:
 *
 * <ul>
 *   <li>Operates on Unicode code points (0–0x10FFFF), not bytes (0–255).
 *   <li>Uses code point equivalence classes (derived from CHAR_RANGE instructions) instead of a
 *       byte-level bytemap.
 *   <li>Single-threaded (no lock-free atomic transitions).
 * </ul>
 */
final class Dfa {

  /** Result of a DFA search. */
  record SearchResult(boolean matched, int pos) {}

  /** Result of a multi-match DFA search. */
  record ManyMatchResult(boolean matched, int[] matchIds) {}

  /** Flag bit: this state contains a MATCH instruction. */
  private static final int FLAG_MATCH = 1 << 8;

  /** Maximum number of DFA states before bailing out to NFA. */
  private static final int DEFAULT_MAX_STATES = 10_000;

  // ---------------------------------------------------------------------------
  // State representation
  // ---------------------------------------------------------------------------

  /**
   * A DFA state: a set of NFA instruction IDs (the "frontier" of consuming/accepting instructions)
   * plus position-dependent flags.
   */
  private static final class State {
    final int[] insts; // sorted NFA instruction IDs (CHAR_RANGE and MATCH only)
    final int flags;
    final State[] next; // transitions indexed by equivalence class; null = not yet computed

    State(int[] insts, int flags, int numClasses) {
      this.insts = insts;
      this.flags = flags;
      this.next = new State[numClasses];
    }

    boolean isMatch() {
      return (flags & FLAG_MATCH) != 0;
    }
  }

  /** Cache key for state deduplication. */
  private record StateKey(int[] insts, int flags) {
    @Override
    public int hashCode() {
      return Arrays.hashCode(insts) * 31 + flags;
    }

    @Override
    public boolean equals(Object o) {
      return (o instanceof StateKey other)
          && flags == other.flags
          && Arrays.equals(insts, other.insts);
    }
  }

  // ---------------------------------------------------------------------------
  // Instance fields
  // ---------------------------------------------------------------------------

  private final Prog prog;
  private final int maxStates;

  /** Sorted code point boundaries defining equivalence classes. */
  private final int[] boundaries;

  /** Total number of equivalence classes (intervals between boundaries + 1 for end-of-text). */
  private final int numClasses;

  /** State cache: maps instruction-set + flags to canonical State instance. */
  private final Map<StateKey, State> cache = new HashMap<>();

  /** Sentinel dead state: no instructions, no transitions possible. */
  private final State deadState = new State(new int[0], 0, 0);

  // ---------------------------------------------------------------------------
  // Construction
  // ---------------------------------------------------------------------------

  private Dfa(Prog prog, int maxStates) {
    this.prog = prog;
    this.maxStates = maxStates;
    this.boundaries = buildBoundaries(prog);
    this.numClasses = boundaries.length + 1 + 1; // intervals + end-of-text
  }

  /**
   * Collects all code point range boundaries from the program's CHAR_RANGE instructions. The
   * boundaries define equivalence classes: code points within the same interval between consecutive
   * boundaries are indistinguishable to the DFA.
   */
  private static int[] buildBoundaries(Prog prog) {
    TreeSet<Integer> bounds = new TreeSet<>();
    bounds.add(0);
    bounds.add(0x110000);
    for (int i = 0; i < prog.size(); i++) {
      Inst inst = prog.inst(i);
      if (inst.op == InstOp.CHAR_RANGE) {
        bounds.add(inst.lo);
        if (inst.hi < 0x10FFFF) {
          bounds.add(inst.hi + 1);
        }
      }
    }
    return bounds.stream().mapToInt(Integer::intValue).toArray();
  }

  /** Maps a code point (or -1 for end-of-text) to its equivalence class index. */
  private int classOf(int cp) {
    if (cp < 0) {
      return numClasses - 1;
    }
    int idx = Arrays.binarySearch(boundaries, cp);
    if (idx >= 0) {
      return idx;
    }
    return (-idx - 1) - 1;
  }

  // ---------------------------------------------------------------------------
  // Subset construction
  // ---------------------------------------------------------------------------

  /**
   * Starting from a set of instruction IDs, follows all empty transitions (ALT, NOP, CAPTURE,
   * EMPTY_WIDTH) and returns the sorted frontier of CHAR_RANGE, MATCH, and unsatisfied EMPTY_WIDTH
   * instruction IDs.
   *
   * <p>Unsatisfied EMPTY_WIDTH instructions are retained in the frontier so they can be re-checked
   * when context changes (e.g., at end-of-text, the EndText flag becomes true and {@code $} can
   * fire).
   */
  private int[] expand(List<Integer> seeds, int emptyFlags) {
    boolean[] visited = new boolean[prog.size()];
    List<Integer> frontier = new ArrayList<>();

    // Use seeds list as initial stack (copy to avoid mutation issues).
    List<Integer> stack = new ArrayList<>(seeds);

    while (!stack.isEmpty()) {
      int id = stack.remove(stack.size() - 1);
      if (id == 0 || id >= prog.size() || visited[id]) {
        continue;
      }
      visited[id] = true;

      Inst ip = prog.inst(id);
      switch (ip.op) {
        case FAIL -> {}
        case ALT, ALT_MATCH -> {
          stack.add(ip.out);
          stack.add(ip.out1);
        }
        case NOP -> stack.add(ip.out);
        case CAPTURE -> stack.add(ip.out);
        case EMPTY_WIDTH -> {
          if ((ip.arg & ~emptyFlags) == 0) {
            stack.add(ip.out);
          } else {
            // Keep unsatisfied empty-width assertions in frontier so they can be re-checked
            // when context changes (e.g., at end-of-text).
            frontier.add(id);
          }
        }
        case CHAR_RANGE, MATCH -> frontier.add(id);
        default -> {}
      }
    }

    frontier.sort(null);
    return frontier.stream().mapToInt(Integer::intValue).toArray();
  }

  /** Returns true if any instruction ID in the sorted array is a MATCH instruction. */
  private boolean hasMatch(int[] insts) {
    for (int id : insts) {
      if (prog.inst(id).op == InstOp.MATCH) {
        return true;
      }
    }
    return false;
  }

  /** Collects all match IDs (MATCH instruction arg values) from a DFA state's NFA instructions. */
  private int[] collectMatchIds(int[] insts) {
    int count = 0;
    for (int id : insts) {
      Inst ip = prog.inst(id);
      if (ip.op == InstOp.MATCH) {
        count++;
      }
    }
    if (count == 0) {
      return new int[0];
    }
    int[] ids = new int[count];
    int idx = 0;
    for (int id : insts) {
      Inst ip = prog.inst(id);
      if (ip.op == InstOp.MATCH) {
        ids[idx++] = ip.arg;
      }
    }
    return ids;
  }

  /** Gets or creates a cached state. Returns null if the state budget is exceeded. */
  private State getOrCreate(int[] insts, int flags) {
    if (insts.length == 0) {
      return deadState;
    }
    StateKey key = new StateKey(insts, flags);
    State s = cache.get(key);
    if (s != null) {
      return s;
    }
    if (cache.size() >= maxStates) {
      return null;
    }
    s = new State(insts, flags, numClasses);
    cache.put(key, s);
    return s;
  }

  /**
   * Computes the start state for the given position context.
   *
   * <p>For unanchored searches, uses {@code prog.startUnanchored()} which enters the {@code .*?}
   * prefix loop that the compiler generates. This keeps all start positions alive within the DFA
   * state without needing to restart at each position (unlike the NFA).
   */
  private State startState(String text, int pos, boolean anchored) {
    int startInst = anchored ? prog.start() : prog.startUnanchored();
    if (startInst == 0) {
      return deadState;
    }
    int emptyFlags = Nfa.emptyFlags(text, pos);
    int[] insts = expand(List.of(startInst), emptyFlags);
    int flags = emptyFlags & 0xFF;
    if (hasMatch(insts)) {
      flags |= FLAG_MATCH;
    }
    return getOrCreate(insts, flags);
  }

  /**
   * Computes the next DFA state from the current state for a given code point.
   *
   * <p>For each CHAR_RANGE instruction in the current state, checks if it matches the code point.
   * Collects the successor instructions, expands them through empty transitions, and returns the
   * resulting state.
   */
  private State computeNext(State s, int cp, String text, int nextPos) {
    // At end of text, re-expand the current instruction set with end-of-text empty flags.
    // This allows empty-width assertions like $ to fire.
    if (cp < 0) {
      int emptyFlags = Nfa.emptyFlags(text, nextPos);
      // Re-expand from the successors of EMPTY_WIDTH instructions that now pass.
      List<Integer> seeds = new ArrayList<>();
      for (int id : s.insts) {
        Inst ip = prog.inst(id);
        if (ip.op == InstOp.EMPTY_WIDTH && (ip.arg & ~emptyFlags) == 0) {
          seeds.add(ip.out);
        }
      }
      if (seeds.isEmpty()) {
        return deadState;
      }
      int[] nextInsts = expand(seeds, emptyFlags);
      if (nextInsts.length == 0) {
        return deadState;
      }
      int flags = emptyFlags & 0xFF;
      if (hasMatch(nextInsts)) {
        flags |= FLAG_MATCH;
      }
      return getOrCreate(nextInsts, flags);
    }

    List<Integer> successors = new ArrayList<>();
    for (int id : s.insts) {
      Inst ip = prog.inst(id);
      if (ip.op == InstOp.CHAR_RANGE && ip.matchesChar(cp)) {
        successors.add(ip.out);
      }
    }

    if (successors.isEmpty()) {
      return deadState;
    }

    int emptyFlags = Nfa.emptyFlags(text, nextPos);
    int[] nextInsts = expand(successors, emptyFlags);

    if (nextInsts.length == 0) {
      return deadState;
    }

    int flags = emptyFlags & 0xFF;
    if (hasMatch(nextInsts)) {
      flags |= FLAG_MATCH;
    }
    return getOrCreate(nextInsts, flags);
  }

  // ---------------------------------------------------------------------------
  // Search
  // ---------------------------------------------------------------------------

  /**
   * Searches for a match using the DFA.
   *
   * @param prog the compiled program
   * @param text the input text
   * @param anchored whether to anchor the search at the start
   * @param longest whether to find the longest match (vs. earliest/first match)
   * @return search result, or {@code null} if the DFA exceeded its state budget (caller should fall
   *     back to NFA)
   */
  static SearchResult search(Prog prog, String text, boolean anchored, boolean longest) {
    return search(prog, text, anchored, longest, DEFAULT_MAX_STATES);
  }

  /** Search with explicit state budget. */
  static SearchResult search(
      Prog prog, String text, boolean anchored, boolean longest, int maxStates) {
    Dfa dfa = new Dfa(prog, maxStates);
    return dfa.doSearch(text, anchored, longest);
  }

  /**
   * Main DFA search loop.
   *
   * <p>Iterates over each code point in the text, following transitions. When a match state is
   * reached, records the position. In earliest-match mode, returns immediately. In longest-match
   * mode, continues to find the longest match.
   */
  private SearchResult doSearch(String text, boolean anchored, boolean longest) {
    int textLen = text.length();
    // If the compiled program requires end-of-text matching (stripped $), enforce it.
    boolean needEndMatch = prog.anchorEnd();

    State s = startState(text, 0, anchored);
    if (s == null) {
      return null;
    }

    boolean matched = false;
    int matchEnd = -1;

    // Check if start state is already a match (e.g., empty pattern or .*? prefix).
    if (s.isMatch()) {
      if (!needEndMatch || textLen == 0) {
        matched = true;
        matchEnd = 0;
        if (!longest && !needEndMatch) {
          return new SearchResult(true, 0);
        }
      }
    }

    if (s == deadState) {
      return new SearchResult(matched, matchEnd);
    }

    int pos = 0;
    while (pos <= textLen) {
      int cp;
      int nextPos;
      if (pos < textLen) {
        cp = text.codePointAt(pos);
        nextPos = pos + Character.charCount(cp);
      } else {
        cp = -1;
        nextPos = textLen + 1;
      }

      // Try cached transition first.
      int cls = classOf(cp);
      State ns = s.next[cls];
      if (ns == null) {
        ns = computeNext(s, cp, text, Math.min(nextPos, textLen));
        if (ns == null) {
          return null; // budget exceeded
        }
        s.next[cls] = ns;
      }
      s = ns;

      if (s == deadState) {
        break;
      }

      if (s.isMatch()) {
        int endPos = Math.min(nextPos, textLen);
        if (!needEndMatch || endPos == textLen) {
          matched = true;
          matchEnd = endPos;
          if (!longest && !needEndMatch) {
            return new SearchResult(true, matchEnd);
          }
        }
      }

      if (pos >= textLen) {
        break;
      }
      pos = nextPos;
    }

    return new SearchResult(matched, matchEnd);
  }

  // ---------------------------------------------------------------------------
  // Multi-match search (for PatternSet)
  // ---------------------------------------------------------------------------

  /**
   * Searches for all matching patterns in a multi-pattern program.
   *
   * <p>Unlike {@link #search}, this method does not stop at the first match. It processes the
   * entire text and collects the match IDs (from {@link Inst#arg}) of all MATCH instructions
   * reached. This is used by {@link PatternSet} to determine which patterns matched.
   *
   * @param prog the compiled multi-pattern program (built with HAVE_MATCH markers)
   * @param text the input text
   * @param anchored whether to anchor the search at the start
   * @return multi-match result, or {@code null} if the DFA exceeded its state budget
   */
  static ManyMatchResult searchMany(Prog prog, String text, boolean anchored) {
    return searchMany(prog, text, anchored, DEFAULT_MAX_STATES);
  }

  /** Multi-match search with explicit state budget. */
  static ManyMatchResult searchMany(Prog prog, String text, boolean anchored, int maxStates) {
    Dfa dfa = new Dfa(prog, maxStates);
    return dfa.doSearchMany(text, anchored);
  }

  /**
   * Multi-match DFA search loop. Processes the entire text and collects all match IDs from match
   * states reached along the way.
   */
  private ManyMatchResult doSearchMany(String text, boolean anchored) {
    int textLen = text.length();
    boolean needEndMatch = prog.anchorEnd();

    State s = startState(text, 0, anchored);
    if (s == null) {
      return null;
    }

    // Use a bitset to track which match IDs have been seen.
    java.util.BitSet seen = new java.util.BitSet();

    // Check if start state is already a match.
    if (s.isMatch()) {
      if (!needEndMatch || textLen == 0) {
        for (int id : collectMatchIds(s.insts)) {
          seen.set(id);
        }
      }
    }

    if (s != deadState) {
      int pos = 0;
      while (pos <= textLen) {
        int cp;
        int nextPos;
        if (pos < textLen) {
          cp = text.codePointAt(pos);
          nextPos = pos + Character.charCount(cp);
        } else {
          cp = -1;
          nextPos = textLen + 1;
        }

        int cls = classOf(cp);
        State ns = s.next[cls];
        if (ns == null) {
          ns = computeNext(s, cp, text, Math.min(nextPos, textLen));
          if (ns == null) {
            return null; // budget exceeded
          }
          s.next[cls] = ns;
        }
        s = ns;

        if (s == deadState) {
          break;
        }

        if (s.isMatch()) {
          int endPos = Math.min(nextPos, textLen);
          if (!needEndMatch || endPos == textLen) {
            for (int id : collectMatchIds(s.insts)) {
              seen.set(id);
            }
          }
        }

        if (pos >= textLen) {
          break;
        }
        pos = nextPos;
      }
    }

    boolean matched = !seen.isEmpty();
    int[] matchIds = seen.stream().toArray();
    return new ManyMatchResult(matched, matchIds);
  }

  private Dfa() {
    throw new AssertionError("non-instantiable");
  }
}
