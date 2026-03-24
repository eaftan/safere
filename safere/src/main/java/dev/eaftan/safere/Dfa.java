// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

  /** Flag bit: last consumed character was a word character (for {@code \b}/\B}). */
  private static final int FLAG_LAST_WORD = 1 << 9;

  /**
   * Flag bit: match was triggered by a word-boundary assertion BEFORE consuming the transition
   * character. The match position should be recorded at the current position, not after the
   * character.
   */
  private static final int FLAG_MATCH_BEFORE = 1 << 10;

  /** Maximum number of DFA states before bailing out to NFA. */
  private static final int DEFAULT_MAX_STATES = 10_000;

  // ---------------------------------------------------------------------------
  // State representation
  // ---------------------------------------------------------------------------

  /**
   * A DFA state: a set of NFA instruction IDs (the "frontier" of consuming/accepting instructions)
   * plus position-dependent flags. States are cached and shared across transitions to avoid
   * recomputation.
   */
  private static final class State {
    final int[] insts; // sorted NFA instruction IDs (CHAR_RANGE, EMPTY_WIDTH, and MATCH only)
    final int flags;
    /** Match IDs from word-boundary expansion (for PatternSet multi-match). Null if not applicable. */
    final int[] wordBoundaryMatchIds;
    final List<State> next; // transitions indexed by equivalence class; null = not yet computed

    State(int[] insts, int flags, int numClasses) {
      this(insts, flags, null, numClasses);
    }

    State(int[] insts, int flags, int[] wordBoundaryMatchIds, int numClasses) {
      this.insts = insts;
      this.flags = flags;
      this.wordBoundaryMatchIds = wordBoundaryMatchIds;
      this.next = new ArrayList<>(Collections.nCopies(numClasses, null));
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

  /**
   * Fast ASCII-to-class lookup table. For code points 0–127, {@code asciiClassMap[cp]} gives the
   * equivalence class index directly, avoiding binary search. -1 means "not populated" (should not
   * happen for valid ASCII).
   */
  private final int[] asciiClassMap;

  /** State cache: maps instruction-set + flags to canonical State instance. */
  private final Map<StateKey, State> cache = new HashMap<>();

  /** Sentinel dead state: no instructions, no transitions possible. */
  private final State deadState = new State(new int[0], 0, 0);

  /** Pre-allocated visited array for {@link #expand}, reused across calls. */
  private final boolean[] expandVisited;

  /**
   * Pre-allocated stack array for {@link #expand}. Sized to the program length (worst case: every
   * instruction pushed once).
   */
  private final int[] expandStack;

  /**
   * Pre-allocated frontier array for {@link #expand}. Sized to the program length (worst case:
   * every instruction is a frontier instruction).
   */
  private final int[] expandFrontier;

  // ---------------------------------------------------------------------------
  // Construction
  // ---------------------------------------------------------------------------

  Dfa(Prog prog, int maxStates) {
    this.prog = prog;
    this.maxStates = maxStates;
    this.boundaries = buildBoundaries(prog);
    this.numClasses = boundaries.length + 1 + 1; // intervals + end-of-text
    this.asciiClassMap = buildAsciiClassMap(boundaries);
    this.expandVisited = new boolean[prog.size()];
    this.expandStack = new int[prog.size()];
    this.expandFrontier = new int[prog.size()];
  }

  /**
   * Collects all code point range boundaries from the program's CHAR_RANGE instructions. The
   * boundaries define equivalence classes: code points within the same interval between consecutive
   * boundaries are indistinguishable to the DFA.
   *
   * <p>When the program contains word-boundary assertions ({@code \b} or {@code \B}), additional
   * boundaries are added at the edges of the word-character ranges ({@code [A-Za-z0-9_]}) so that
   * no equivalence class straddles the word/non-word boundary. This is necessary because the DFA
   * caches transitions per (state, class) and the word-boundary computation depends on whether the
   * current character is a word character.
   */
  private static int[] buildBoundaries(Prog prog) {
    TreeSet<Integer> bounds = new TreeSet<>();
    bounds.add(0);
    bounds.add(Utils.MAX_RUNE + 1);
    boolean hasWordBoundary = false;
    for (int i = 0; i < prog.size(); i++) {
      Inst inst = prog.inst(i);
      if (inst.op == InstOp.CHAR_RANGE) {
        bounds.add(inst.lo);
        if (inst.hi < Utils.MAX_RUNE) {
          bounds.add(inst.hi + 1);
        }
      } else if (inst.op == InstOp.EMPTY_WIDTH
          && (inst.arg & (EmptyOp.WORD_BOUNDARY | EmptyOp.NON_WORD_BOUNDARY)) != 0) {
        hasWordBoundary = true;
      }
    }
    if (hasWordBoundary) {
      // Add boundaries at the edges of word-character ranges [0-9A-Za-z_].
      bounds.add(0x30);   // '0'
      bounds.add(0x3A);   // '9' + 1
      bounds.add(0x41);   // 'A'
      bounds.add(0x5B);   // 'Z' + 1
      bounds.add(0x5F);   // '_'
      bounds.add(0x60);   // '_' + 1
      bounds.add(0x61);   // 'a'
      bounds.add(0x7B);   // 'z' + 1
    }
    return bounds.stream().mapToInt(Integer::intValue).toArray();
  }

  /**
   * Builds a 128-element lookup table mapping ASCII code points (0–127) to their equivalence class
   * indices. This avoids binary search for the most common characters.
   */
  private static int[] buildAsciiClassMap(int[] boundaries) {
    int[] map = new int[128];
    for (int cp = 0; cp < 128; cp++) {
      int idx = Arrays.binarySearch(boundaries, cp);
      map[cp] = (idx >= 0) ? idx : (-idx - 1) - 1;
    }
    return map;
  }

  /** Maps a code point (or -1 for end-of-text) to its equivalence class index. */
  private int classOf(int cp) {
    if (cp < 0) {
      return numClasses - 1;
    }
    if (cp < 128) {
      return asciiClassMap[cp];
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
    boolean[] visited = expandVisited;
    int[] stack = expandStack;
    int[] frontier = expandFrontier;
    int stackTop = 0;
    int frontierSize = 0;

    // Push seeds onto stack.
    for (int i = 0; i < seeds.size(); i++) {
      stack[stackTop++] = seeds.get(i);
    }

    while (stackTop > 0) {
      int id = stack[--stackTop];
      if (id == 0 || id >= prog.size() || visited[id]) {
        continue;
      }
      visited[id] = true;

      Inst ip = prog.inst(id);
      switch (ip.op) {
        case FAIL -> {}
        case ALT, ALT_MATCH -> {
          stack[stackTop++] = ip.out;
          stack[stackTop++] = ip.out1;
        }
        case NOP -> stack[stackTop++] = ip.out;
        case CAPTURE -> stack[stackTop++] = ip.out;
        case EMPTY_WIDTH -> {
          if ((ip.arg & ~emptyFlags) == 0) {
            stack[stackTop++] = ip.out;
          } else {
            frontier[frontierSize++] = id;
          }
        }
        case CHAR_RANGE, MATCH -> frontier[frontierSize++] = id;
        default -> {}
      }
    }

    // Clear visited flags for next call.
    for (int i = 0; i < prog.size(); i++) {
      visited[i] = false;
    }

    Arrays.sort(frontier, 0, frontierSize);
    return Arrays.copyOf(frontier, frontierSize);
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
    return getOrCreate(insts, flags, null);
  }

  /**
   * Gets or creates a cached state with optional word-boundary match IDs. Returns null if the
   * state budget is exceeded.
   */
  private State getOrCreate(int[] insts, int flags, int[] wordBoundaryMatchIds) {
    if (insts.length == 0 && (flags & FLAG_MATCH) == 0) {
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
    s = new State(insts, flags, wordBoundaryMatchIds, numClasses);
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
    return startState(text, pos, anchored, false);
  }

  /**
   * Computes the start state with an explicit "last word" override for reverse searches.
   *
   * @param reverseContext if true, FLAG_LAST_WORD is set based on the character AT pos (the char
   *     to the right of where a reverse scan begins), rather than the character BEFORE pos
   */
  private State startState(String text, int pos, boolean anchored, boolean reverseContext) {
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
    // Track word-character context for \b/\B support in subsequent computeNext() calls.
    // For forward search: the "last" character is the one before pos.
    // For reverse search: the "last" character (in reverse direction) is the one at pos.
    if (reverseContext) {
      if (pos < text.length() && Nfa.isWordChar(text.codePointAt(pos))) {
        flags |= FLAG_LAST_WORD;
      }
    } else {
      if (pos > 0 && Nfa.isWordChar(text.codePointBefore(pos))) {
        flags |= FLAG_LAST_WORD;
      }
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
    // This allows empty-width assertions like $ and \b to fire.
    if (cp < 0) {
      // Compute empty flags for end-of-text, but override word boundary using state context.
      int emptyFlags = Nfa.emptyFlags(text, nextPos);
      // At end-of-text the "current" character is not a word char.
      boolean wasWord = (s.flags & FLAG_LAST_WORD) != 0;
      if (wasWord) {
        emptyFlags = (emptyFlags | EmptyOp.WORD_BOUNDARY) & ~EmptyOp.NON_WORD_BOUNDARY;
      } else {
        emptyFlags = (emptyFlags | EmptyOp.NON_WORD_BOUNDARY) & ~EmptyOp.WORD_BOUNDARY;
      }
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
      // End-of-text is not a word char, so FLAG_LAST_WORD is not set.
      return getOrCreate(nextInsts, flags);
    }

    // Step 1: Re-evaluate unsatisfied word-boundary EMPTY_WIDTH instructions.
    // Compute word boundary context BEFORE consuming cp: the boundary sits between
    // the "last word" char (from state's FLAG_LAST_WORD) and cp.
    boolean isWord = Nfa.isWordChar(cp);
    boolean wasWord = (s.flags & FLAG_LAST_WORD) != 0;
    int wordBeforeFlags = (isWord != wasWord) ? EmptyOp.WORD_BOUNDARY
        : EmptyOp.NON_WORD_BOUNDARY;

    // Check if any unsatisfied EMPTY_WIDTH instructions are now satisfiable.
    // If so, re-expand the state to include their successors before consuming cp.
    List<Integer> reExpandSeeds = null;
    for (int id : s.insts) {
      Inst ip = prog.inst(id);
      if (ip.op == InstOp.EMPTY_WIDTH) {
        int wordFlags = ip.arg & (EmptyOp.WORD_BOUNDARY | EmptyOp.NON_WORD_BOUNDARY);
        int otherFlags = ip.arg & ~(EmptyOp.WORD_BOUNDARY | EmptyOp.NON_WORD_BOUNDARY);
        if (otherFlags == 0 && wordFlags != 0 && (wordFlags & ~wordBeforeFlags) == 0) {
          if (reExpandSeeds == null) {
            reExpandSeeds = new ArrayList<>();
          }
          reExpandSeeds.add(ip.out);
        }
      }
    }

    // If word-boundary assertions fired, expand their successors to get additional
    // CHAR_RANGE and MATCH instructions that are now reachable.
    int[] expandedInsts = s.insts;
    boolean hasMatchFromWordBoundary = false;
    int[] wordBoundaryMatchIds = null;
    if (reExpandSeeds != null) {
      // Expand the newly reachable instructions (without word boundary flags since
      // we can't predict the NEXT word boundary).
      int[] newInsts = expand(reExpandSeeds, s.flags & 0xFF);

      // Check if the re-expansion revealed any MATCH instructions. Collect their IDs
      // for PatternSet multi-match before merging with the original state.
      int wbMatchCount = 0;
      int[] wbIds = null;
      for (int id : newInsts) {
        Inst ip = prog.inst(id);
        if (ip.op == InstOp.MATCH) {
          hasMatchFromWordBoundary = true;
          if (wbIds == null) {
            wbIds = new int[newInsts.length];
          }
          wbIds[wbMatchCount++] = ip.arg;
        }
      }
      if (wbMatchCount > 0) {
        wordBoundaryMatchIds = Arrays.copyOf(wbIds, wbMatchCount);
      }

      // Merge with existing instructions.
      expandedInsts = mergeInsts(s.insts, newInsts);
    }

    // Step 2: Process CHAR_RANGE transitions against cp using the (possibly expanded) state.
    List<Integer> successors = new ArrayList<>();
    for (int id : expandedInsts) {
      Inst ip = prog.inst(id);
      if (ip.op == InstOp.CHAR_RANGE && ip.matchesChar(cp)) {
        successors.add(ip.out);
      }
    }

    if (successors.isEmpty()) {
      // No CHAR_RANGE matched, but if word-boundary expansion revealed a MATCH,
      // return a match state. FLAG_MATCH_BEFORE indicates the match position should be
      // recorded at the current position (before consuming cp), not after.
      if (hasMatchFromWordBoundary) {
        return getOrCreate(new int[0],
            FLAG_MATCH | FLAG_MATCH_BEFORE | (isWord ? FLAG_LAST_WORD : 0),
            wordBoundaryMatchIds);
      }
      return deadState;
    }

    // Compute empty flags at nextPos (after consuming cp). Omit word boundary flags
    // since they depend on the next character, which we don't know yet. Unsatisfied
    // \b/\B EMPTY_WIDTH instructions will remain in the frontier for re-evaluation.
    int emptyFlags = Nfa.emptyFlags(text, nextPos);
    emptyFlags &= ~(EmptyOp.WORD_BOUNDARY | EmptyOp.NON_WORD_BOUNDARY);

    int[] nextInsts = expand(successors, emptyFlags);

    if (nextInsts.length == 0) {
      if (hasMatchFromWordBoundary) {
        return getOrCreate(new int[0],
            FLAG_MATCH | FLAG_MATCH_BEFORE | (isWord ? FLAG_LAST_WORD : 0),
            wordBoundaryMatchIds);
      }
      return deadState;
    }

    int flags = emptyFlags & 0xFF;
    if (hasMatch(nextInsts)) {
      flags |= FLAG_MATCH;
    } else if (hasMatchFromWordBoundary) {
      flags |= FLAG_MATCH | FLAG_MATCH_BEFORE;
    }
    if (isWord) {
      flags |= FLAG_LAST_WORD;
    }
    return getOrCreate(nextInsts, flags, wordBoundaryMatchIds);
  }

  /** Merges two sorted instruction arrays into a sorted, deduplicated array. */
  private static int[] mergeInsts(int[] a, int[] b) {
    int[] merged = new int[a.length + b.length];
    int i = 0;
    int j = 0;
    int k = 0;
    while (i < a.length && j < b.length) {
      if (a[i] < b[j]) {
        merged[k++] = a[i++];
      } else if (a[i] > b[j]) {
        merged[k++] = b[j++];
      } else {
        merged[k++] = a[i++];
        j++;
      }
    }
    while (i < a.length) {
      merged[k++] = a[i++];
    }
    while (j < b.length) {
      merged[k++] = b[j++];
    }
    return Arrays.copyOf(merged, k);
  }

  // ---------------------------------------------------------------------------
  // Search
  // ---------------------------------------------------------------------------

  /**
   * Searches for a match using the DFA, starting from position 0.
   *
   * @param prog the compiled program
   * @param text the input text
   * @param anchored whether to anchor the search at the start
   * @param longest whether to find the longest match (vs. earliest/first match)
   * @return search result, or {@code null} if the DFA exceeded its state budget (caller should fall
   *     back to NFA)
   */
  static SearchResult search(Prog prog, String text, boolean anchored, boolean longest) {
    return search(prog, text, 0, anchored, longest, DEFAULT_MAX_STATES);
  }

  /** Search with explicit state budget, starting from position 0. */
  static SearchResult search(
      Prog prog, String text, boolean anchored, boolean longest, int maxStates) {
    return search(prog, text, 0, anchored, longest, maxStates);
  }

  /**
   * Search with explicit start position and state budget.
   *
   * @param prog the compiled program
   * @param text the input text
   * @param startPos the char index in {@code text} at which to begin searching
   * @param anchored whether to anchor the search at {@code startPos}
   * @param longest whether to find the longest match (vs. earliest/first match)
   * @param maxStates maximum DFA state budget
   * @return search result, or {@code null} if the DFA exceeded its state budget
   */
  static SearchResult search(
      Prog prog, String text, int startPos, boolean anchored, boolean longest, int maxStates) {
    Dfa dfa = new Dfa(prog, maxStates);
    return dfa.doSearch(text, startPos, anchored, longest);
  }

  /**
   * Main DFA search loop, starting from position 0.
   *
   * @see #doSearch(String, int, boolean, boolean)
   */
  SearchResult doSearch(String text, boolean anchored, boolean longest) {
    return doSearch(text, 0, anchored, longest);
  }

  /**
   * Main DFA search loop.
   *
   * <p>Iterates over each code point in the text starting from {@code startPos}, following
   * transitions. When a match state is reached, records the position. In earliest-match mode,
   * returns immediately. In longest-match mode, continues to find the longest match.
   *
   * @param text the full input text
   * @param startPos the char index in {@code text} at which to begin searching
   * @param anchored whether to anchor the search at {@code startPos}
   * @param longest whether to find the longest match
   * @return search result with positions relative to {@code text}, or {@code null} if the DFA
   *     exceeded its state budget
   */
  SearchResult doSearch(String text, int startPos, boolean anchored, boolean longest) {
    int textLen = text.length();
    // If the compiled program requires end-of-text matching (stripped $), enforce it.
    boolean needEndMatch = prog.anchorEnd();

    State s = startState(text, startPos, anchored);
    if (s == null) {
      return null;
    }

    boolean matched = false;
    int matchEnd = -1;

    // Check if start state is already a match (e.g., empty pattern or .*? prefix).
    if (s.isMatch()) {
      if (!needEndMatch || textLen == startPos) {
        matched = true;
        matchEnd = startPos;
        if (!longest && !needEndMatch) {
          return new SearchResult(true, startPos);
        }
      }
    }

    if (s == deadState) {
      return new SearchResult(matched, matchEnd);
    }

    int pos = startPos;
    while (pos <= textLen) {
      int cp;
      int nextPos;
      int cls;
      if (pos < textLen) {
        char ch = text.charAt(pos);
        if (ch < 128) {
          // ASCII fast path: no surrogate handling, use pre-computed class map.
          cp = ch;
          nextPos = pos + 1;
          cls = asciiClassMap[ch];
        } else if (Character.isHighSurrogate(ch) && pos + 1 < textLen) {
          cp = Character.toCodePoint(ch, text.charAt(pos + 1));
          nextPos = pos + 2;
          cls = classOf(cp);
        } else {
          cp = ch;
          nextPos = pos + 1;
          cls = classOf(cp);
        }
      } else {
        cp = -1;
        nextPos = textLen + 1;
        cls = numClasses - 1;
      }

      // Try cached transition first.
      State ns = s.next.get(cls);
      if (ns == null) {
        ns = computeNext(s, cp, text, Math.min(nextPos, textLen));
        if (ns == null) {
          return null; // budget exceeded
        }
        s.next.set(cls, ns);
      }
      s = ns;

      if (s == deadState) {
        break;
      }

      if (s.isMatch()) {
        // FLAG_MATCH_BEFORE indicates the match was triggered by a word-boundary assertion
        // before consuming the current character. Record the match at pos, not nextPos.
        int endPos = (s.flags & FLAG_MATCH_BEFORE) != 0
            ? pos : Math.min(nextPos, textLen);
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
  // Reverse search
  // ---------------------------------------------------------------------------

  /**
   * Reverse DFA search: scans backward through text from {@code endPos} to find match start. Used
   * with a reversed program to find where the leftmost match begins after the forward DFA has
   * determined where it ends.
   *
   * <p>This enables a critical optimization: instead of running the expensive NFA/BitState engine
   * on the entire remaining text, we can narrow the search to just {@code [matchStart, matchEnd]}.
   *
   * @param text the full input text
   * @param endPos the position to start scanning backward from (exclusive upper bound of the
   *     match)
   * @param startLimit the earliest position to scan back to (inclusive), typically 0 or the
   *     prefix-acceleration start
   * @param anchored if true, the reverse match must start at {@code endPos} (meaning the forward
   *     match ends exactly there)
   * @param longest if true, find the longest reverse match (earliest start position)
   * @return search result where {@code pos} is the match start position, or {@code null} if the
   *     DFA exceeded its state budget
   */
  SearchResult doSearchReverse(String text, int endPos, int startLimit,
      boolean anchored, boolean longest) {
    // The reversed program's "start of text" corresponds to endPos (the right edge of the match
    // region), and its "end of text" corresponds to startLimit (the left edge). We scan from
    // endPos backward to startLimit, feeding characters in reverse order.
    boolean needEndMatch = prog.anchorEnd();

    // Compute empty flags at the reverse start position (= endPos in the original text).
    State s = startState(text, endPos, anchored, true);
    if (s == null) {
      return null;
    }

    boolean matched = false;
    int matchStart = -1;

    // Check if start state is already a match (e.g., empty pattern).
    if (s.isMatch()) {
      if (!needEndMatch || endPos == startLimit) {
        matched = true;
        matchStart = endPos;
        if (!longest && !needEndMatch) {
          return new SearchResult(true, matchStart);
        }
      }
    }

    if (s == deadState) {
      return new SearchResult(matched, matchStart);
    }

    int pos = endPos;
    while (pos >= startLimit) {
      int cp;
      int prevPos;
      int cls;
      if (pos > startLimit) {
        // Read the code point just before pos (scanning backward).
        char ch = text.charAt(pos - 1);
        if (ch < 128) {
          // ASCII fast path.
          cp = ch;
          prevPos = pos - 1;
          cls = asciiClassMap[ch];
        } else if (Character.isLowSurrogate(ch) && pos - 2 >= startLimit
            && Character.isHighSurrogate(text.charAt(pos - 2))) {
          // Surrogate pair: the low surrogate is at pos-1, high at pos-2.
          cp = Character.toCodePoint(text.charAt(pos - 2), ch);
          prevPos = pos - 2;
          cls = classOf(cp);
        } else {
          cp = ch;
          prevPos = pos - 1;
          cls = classOf(cp);
        }
      } else {
        // Reached the start limit — present end-of-text to the reversed DFA.
        cp = -1;
        prevPos = startLimit - 1;
        cls = numClasses - 1;
      }

      // Try cached transition first.
      State ns = s.next.get(cls);
      if (ns == null) {
        ns = computeNext(s, cp, text, Math.max(prevPos, startLimit));
        if (ns == null) {
          return null; // budget exceeded
        }
        s.next.set(cls, ns);
      }
      s = ns;

      if (s == deadState) {
        break;
      }

      if (s.isMatch()) {
        // For reverse search, FLAG_MATCH_BEFORE means the match happened at pos, not prevPos.
        int startPos = (s.flags & FLAG_MATCH_BEFORE) != 0
            ? pos : Math.max(prevPos, startLimit);
        if (!needEndMatch || startPos == startLimit) {
          matched = true;
          matchStart = startPos;
          if (!longest && !needEndMatch) {
            return new SearchResult(true, matchStart);
          }
        }
      }

      if (pos <= startLimit) {
        break;
      }
      pos = prevPos;
    }

    return new SearchResult(matched, matchStart);
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
  ManyMatchResult doSearchMany(String text, boolean anchored) {
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
        int cls;
        if (pos < textLen) {
          char ch = text.charAt(pos);
          if (ch < 128) {
            cp = ch;
            nextPos = pos + 1;
            cls = asciiClassMap[ch];
          } else if (Character.isHighSurrogate(ch) && pos + 1 < textLen) {
            cp = Character.toCodePoint(ch, text.charAt(pos + 1));
            nextPos = pos + 2;
            cls = classOf(cp);
          } else {
            cp = ch;
            nextPos = pos + 1;
            cls = classOf(cp);
          }
        } else {
          cp = -1;
          nextPos = textLen + 1;
          cls = numClasses - 1;
        }

        State ns = s.next.get(cls);
        if (ns == null) {
          ns = computeNext(s, cp, text, Math.min(nextPos, textLen));
          if (ns == null) {
            return null; // budget exceeded
          }
          s.next.set(cls, ns);
        }
        s = ns;

        if (s == deadState) {
          break;
        }

        if (s.isMatch()) {
          int endPos = (s.flags & FLAG_MATCH_BEFORE) != 0
              ? pos : Math.min(nextPos, textLen);
          if (!needEndMatch || endPos == textLen) {
            // Collect match IDs from the state's instructions.
            for (int id : collectMatchIds(s.insts)) {
              seen.set(id);
            }
            // Also collect match IDs from word-boundary expansion (if any).
            if (s.wordBoundaryMatchIds != null) {
              for (int id : s.wordBoundaryMatchIds) {
                seen.set(id);
              }
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
