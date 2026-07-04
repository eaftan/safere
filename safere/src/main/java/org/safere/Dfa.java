// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
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
  record SearchResult(boolean matched, int pos, boolean ambiguous) {
    SearchResult(boolean matched, int pos) {
      this(matched, pos, false);
    }
  }

  /** Result of a multi-match DFA search. */
  // TODO(#98): Replace int[] with Guava ImmutableIntArray to get proper value semantics.
  @SuppressWarnings("ArrayRecordComponent")
  record ManyMatchResult(boolean matched, int[] matchIds) {}

  /** Flag bit: this state contains a MATCH instruction. */
  private static final int FLAG_MATCH = 1 << 10;

  /** Flag bit: last consumed character was a word character (for {@code \b}/\B}). */
  private static final int FLAG_LAST_WORD = 1 << 11;

  /**
   * Flag bit: match was triggered by a word-boundary assertion BEFORE consuming the transition
   * character. The match position should be recorded at the current position, not after the
   * character.
   */
  private static final int FLAG_MATCH_BEFORE = 1 << 12;

  /**
   * Flag bit: when {@link #FLAG_MATCH_BEFORE} is set, indicates that an after-consume match ALSO
   * exists (from character transitions reaching MATCH). The search loop should try the
   * before-consume match first (earlier position) and fall back to the after-consume match if the
   * before-consume match is rejected (e.g., by {@code needEndMatch} requiring end-of-text).
   */
  private static final int FLAG_MATCH_AFTER_DEFERRED = 1 << 13;

  /**
   * Flag bit: last consumed character was a Unicode word character (for Unicode {@code \b}/\B}).
   */
  private static final int FLAG_LAST_UNICODE_WORD = 1 << 14;

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
    final int id;
    final int[] insts; // sorted NFA instruction IDs (CHAR_RANGE, EMPTY_WIDTH, and MATCH only)
    final int flags;
    final boolean isHighestPriorityMatch;

    /**
     * Match IDs from word-boundary expansion (for PatternSet multi-match). Null if not applicable.
     */
    final int[] wordBoundaryMatchIds;

    /** Transitions indexed by equivalence class; null entry = not yet computed. */
    final State[] next;

    State(
        int id,
        int[] insts,
        int flags,
        int[] wordBoundaryMatchIds,
        int numClasses,
        boolean isHighestPriorityMatch) {
      this.id = id;
      this.insts = insts;
      this.flags = flags;
      this.wordBoundaryMatchIds = wordBoundaryMatchIds;
      this.isHighestPriorityMatch = isHighestPriorityMatch;
      this.next = new State[numClasses];
    }

    boolean isMatch() {
      return (flags & FLAG_MATCH) != 0;
    }
  }

  /** Cache key for state deduplication. */
  private static final class StateKey {
    private int[] insts;
    private int flags;

    StateKey() {}

    StateKey(int[] insts, int flags) {
      this.insts = insts;
      this.flags = flags;
    }

    void reset(int[] insts, int flags) {
      this.insts = insts;
      this.flags = flags;
    }

    StateKey copy() {
      return new StateKey(insts, flags);
    }

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
  private final boolean longest;
  private final boolean hasGraphemeSemantics;
  private final int stateEmptyFlagsMask;
  private final int startCacheEmptyFlagsMask;
  private final int anchoredCacheBit;
  private final int reverseCacheBit;

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

  /** Cache for mapping code points >= 128 to their equivalence class. */
  private final int[] cacheCps = new int[256];

  private final int[] cacheClasses = new int[256];

  /** State cache: maps instruction-set + flags to canonical State instance. */
  private final Map<StateKey, State> cache = new HashMap<>();

  /** Pre-allocated mutable key to perform allocation-free cache lookups. */
  private final StateKey lookupKey = new StateKey();

  /** Sentinel dead state: no instructions, no transitions possible. */
  private final State deadState;

  /** Pre-allocated visited generation array for {@link #expand}, reused across calls. */
  private final int[] expandVisitedGen;

  private int expandGeneration;

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

  /** Pre-allocated workspace for seed/successor collection in computeNext(). */
  private final int[] computeBuf;

  /**
   * Cache of DFA start states indexed by position context. The start state depends on four factors:
   * whether the search is anchored, whether it's a reverse context, the empty-width flags at the
   * position, and whether the previous character was a word character. This gives at most 2 × 2 ×
   * masked-empty-flag-count × 2 × 2 combinations. Caching avoids the expensive {@link #expand} call
   * and its {@code Arrays.copyOf} allocation on every DFA search.
   */
  private final State[] startStateByContext;

  /** Shared empty instruction array to avoid repeated zero-length allocations. */
  private static final int[] EMPTY_INSTS = new int[0];

  /** Per-search grapheme context. Set only while a search method is active. */
  private GraphemeSupport.Context graphemeContext;

  private int[] transitions;
  private State[] offsetToState;
  private int nextStateId;

  // ---------------------------------------------------------------------------
  // Construction
  // ---------------------------------------------------------------------------

  /**
   * Pre-computed immutable DFA setup: equivalence class boundaries and ASCII lookup table. These
   * are derived solely from the compiled {@link Prog} and can be shared across all DFA instances
   * for the same program (e.g., across multiple {@link org.safere.Matcher} instances).
   */
  // TODO(#98): Replace int[] with Guava ImmutableIntArray to get proper value semantics.
  @SuppressWarnings("ArrayRecordComponent")
  record Setup(int[] boundaries, int numClasses, int[] asciiClassMap) {}

  /**
   * Builds a reusable {@link Setup} from a compiled program. The result is immutable and can be
   * stored on a {@link org.safere.Pattern} for sharing across matchers.
   */
  static Setup buildSetup(Prog prog) {
    int[] boundaries = buildBoundaries(prog);
    int numClasses = boundaries.length + 1 + 1; // intervals + end-of-text
    int[] asciiClassMap = buildAsciiClassMap(boundaries);
    return new Setup(boundaries, numClasses, asciiClassMap);
  }

  Dfa(Prog prog, int maxStates, Setup setup, boolean longest) {
    this.prog = prog;
    this.maxStates = maxStates;
    this.longest = longest;
    this.hasGraphemeSemantics = prog.hasGraphemeSemantics();
    this.stateEmptyFlagsMask =
        hasGraphemeSemantics
            ? EmptyOp.ALL_FLAGS
            : EmptyOp.ALL_FLAGS & ~EmptyOp.GRAPHEME_CLUSTER_BOUNDARY;
    this.startCacheEmptyFlagsMask =
        hasGraphemeSemantics
            ? EmptyOp.ALL_FLAGS
            : EmptyOp.ALL_FLAGS
                & ~(EmptyOp.GRAPHEME_CLUSTER_BOUNDARY | EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY);
    this.reverseCacheBit = (startCacheEmptyFlagsMask + 1) << 2;
    this.anchoredCacheBit = reverseCacheBit << 1;
    this.startStateByContext = new State[anchoredCacheBit << 1];
    this.boundaries = setup.boundaries;
    this.numClasses = setup.numClasses;
    this.asciiClassMap = setup.asciiClassMap;
    Arrays.fill(this.cacheCps, -1);
    this.expandVisitedGen = new int[prog.size()];
    this.expandStack = new int[prog.size()];
    this.expandFrontier = new int[prog.size()];
    this.computeBuf = new int[prog.size()];
    this.deadState = new State(0, EMPTY_INSTS, 0, null, numClasses, false);
    this.nextStateId = 1;
    this.transitions = new int[1024];
    this.offsetToState = new State[1024];
    addStateToFlatArrays(deadState);
  }

  private void addStateToFlatArrays(State s) {
    int minTransLen = (s.id + 1) * numClasses;
    if (minTransLen > transitions.length) {
      int newLen = Math.max(transitions.length * 2, minTransLen);
      transitions = Arrays.copyOf(transitions, newLen);
      offsetToState = Arrays.copyOf(offsetToState, newLen);
    }
    offsetToState[s.id * numClasses] = s;
  }

  private void setTransition(int fromId, int cls, int toId) {
    int fromBase = fromId * numClasses;
    State toState = offsetToState[toId * numClasses];
    boolean isMatch = toState.isMatch();
    int storedId = toId * numClasses;
    if (isMatch) {
      storedId = -storedId;
    }
    transitions[fromBase + cls] = storedId;
  }

  private void addTransition(State from, int cls, State to) {
    from.next[cls] = to;
    setTransition(from.id, cls, to.id);
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
    boolean hasLineBoundary = false;
    for (int i = 0; i < prog.size(); i++) {
      Inst inst = prog.inst(i);
      if (inst.opCode == InstOp.OP_CHAR_RANGE) {
        addRangeBoundaries(bounds, inst.lo, inst.hi);
        if (inst.foldCase) {
          addCaseFoldBoundaries(bounds, inst.lo, inst.hi);
        }
      } else if (inst.opCode == InstOp.OP_CHAR_CLASS) {
        for (int j = 0; j < inst.ranges.length; j += 2) {
          bounds.add(inst.ranges[j]);
          if (inst.ranges[j + 1] < Utils.MAX_RUNE) {
            bounds.add(inst.ranges[j + 1] + 1);
          }
        }
      } else if (inst.opCode == InstOp.OP_EMPTY_WIDTH) {
        if ((inst.arg & (EmptyOp.WORD_BOUNDARY | EmptyOp.NON_WORD_BOUNDARY)) != 0) {
          hasWordBoundary = true;
        }
        if ((inst.arg & (EmptyOp.UNICODE_WORD_BOUNDARY | EmptyOp.UNICODE_NON_WORD_BOUNDARY)) != 0) {
          hasWordBoundary = true;
        }
        if ((inst.arg & (EmptyOp.BEGIN_LINE | EmptyOp.END_LINE)) != 0) {
          hasLineBoundary = true;
        }
      }
    }
    if (hasLineBoundary) {
      // Line terminator characters trigger BEGIN_LINE (at the position after them) and
      // END_LINE (at their position). Give each its own equivalence class so the DFA can
      // cache different transitions for line terminators vs other characters.
      bounds.add(0x0A); // '\n'
      bounds.add(0x0B); // '\n' + 1
      bounds.add(0x0D); // '\r'
      bounds.add(0x0E); // '\r' + 1
      // Additional JDK line terminators: \u0085, \u2028, \u2029
      bounds.add(0x0085); // NEXT LINE
      bounds.add(0x0086); // NEXT LINE + 1
      bounds.add(0x2028); // LINE SEPARATOR
      bounds.add(0x202A); // PARAGRAPH SEPARATOR + 1 (covers both \u2028 and \u2029)
    }
    if (hasWordBoundary) {
      // Add boundaries at the edges of word-character ranges [0-9A-Za-z_].
      bounds.add(0x30); // '0'
      bounds.add(0x3A); // '9' + 1
      bounds.add(0x41); // 'A'
      bounds.add(0x5B); // 'Z' + 1
      bounds.add(0x5F); // '_'
      bounds.add(0x60); // '_' + 1
      bounds.add(0x61); // 'a'
      bounds.add(0x7B); // 'z' + 1
    }
    return bounds.stream().mapToInt(Integer::intValue).toArray();
  }

  /** Adds boundaries for a [lo, hi] code point range. */
  private static void addRangeBoundaries(NavigableSet<Integer> bounds, int lo, int hi) {
    bounds.add(lo);
    if (hi < Utils.MAX_RUNE) {
      bounds.add(hi + 1);
    }
  }

  /**
   * Adds boundaries for all case-fold equivalents of each code point in [lo, hi]. When a CHAR_RANGE
   * instruction has the fold-case flag, characters outside [lo, hi] that fold into the range must
   * have their own equivalence classes so the DFA doesn't conflate them with non-matching
   * characters in the same class.
   */
  private static void addCaseFoldBoundaries(NavigableSet<Integer> bounds, int lo, int hi) {
    for (int cp = lo; cp <= hi; cp++) {
      int folded = Inst.simpleFold(cp);
      while (folded != cp) {
        addRangeBoundaries(bounds, folded, folded);
        folded = Inst.simpleFold(folded);
      }
    }
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
    int cacheIdx = cp & 255;
    if (cacheCps[cacheIdx] == cp) {
      return cacheClasses[cacheIdx];
    }
    int idx = Arrays.binarySearch(boundaries, cp);
    int cls = (idx >= 0) ? idx : (-idx - 1) - 1;
    cacheCps[cacheIdx] = cp;
    cacheClasses[cacheIdx] = cls;
    return cls;
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
  private int[] expand(int[] seeds, int seedCount, int emptyFlags) {
    int gen = ++expandGeneration;
    int[] visitedGen = expandVisitedGen;
    int[] stack = expandStack;
    int[] frontier = expandFrontier;
    int stackTop = 0;
    int frontierSize = 0;

    // Push seeds onto stack in reverse priority order.
    for (int i = seedCount - 1; i >= 0; i--) {
      stack[stackTop++] = seeds[i];
    }

    boolean flat = prog.didFlatten();

    while (stackTop > 0) {
      int id = stack[--stackTop];
      if (id == 0 || id >= prog.size() || visitedGen[id] == gen) {
        continue;
      }
      visitedGen[id] = gen;

      Inst ip = prog.inst(id);

      if (flat) {
        // Flat program list iteration
        if (!ip.last) {
          stack[stackTop++] = id + 1;
        }

        switch (ip.opCode) {
          case InstOp.OP_FAIL -> {}
          case InstOp.OP_ALT, InstOp.OP_ALT_MATCH -> {
            // AltMatch and ALT only transition to id + 1 (already pushed above)
          }
          case InstOp.OP_NOP, InstOp.OP_CAPTURE -> {
            stack[stackTop++] = ip.out;
          }
          case InstOp.OP_PROGRESS_CHECK -> {
            stack[stackTop++] = ip.out1;
            stack[stackTop++] = ip.out;
          }
          case InstOp.OP_EMPTY_WIDTH -> {
            if ((ip.arg & ~emptyFlags) == 0) {
              stack[stackTop++] = ip.out;
            } else {
              frontier[frontierSize++] = id;
            }
          }
          case InstOp.OP_CHAR_RANGE, InstOp.OP_CHAR_CLASS -> frontier[frontierSize++] = id;
          case InstOp.OP_MATCH -> {
            frontier[frontierSize++] = id;
            if (!longest && !prog.anchorEnd()) {
              stackTop = 0;
            }
          }
          default -> {}
        }
      } else {
        // Standard unflattened program traversal
        switch (ip.opCode) {
          case InstOp.OP_FAIL -> {}
          case InstOp.OP_ALT, InstOp.OP_ALT_MATCH -> {
            stack[stackTop++] = ip.out1;
            stack[stackTop++] = ip.out;
          }
          case InstOp.OP_NOP, InstOp.OP_CAPTURE -> {
            stack[stackTop++] = ip.out;
          }
          case InstOp.OP_PROGRESS_CHECK -> {
            stack[stackTop++] = ip.out1;
            stack[stackTop++] = ip.out;
          }
          case InstOp.OP_EMPTY_WIDTH -> {
            if ((ip.arg & ~emptyFlags) == 0) {
              stack[stackTop++] = ip.out;
            } else {
              frontier[frontierSize++] = id;
            }
          }
          case InstOp.OP_CHAR_RANGE, InstOp.OP_CHAR_CLASS -> frontier[frontierSize++] = id;
          case InstOp.OP_MATCH -> {
            frontier[frontierSize++] = id;
            if (!longest && !prog.anchorEnd()) {
              stackTop = 0;
            }
          }
          default -> {}
        }
      }
    }

    if (longest) {
      sortSmall(frontier, frontierSize);
    }
    return Arrays.copyOf(frontier, frontierSize);
  }

  /** Sorts DFA frontiers, keeping insertion sort only for genuinely small arrays. */
  private static void sortSmall(int[] a, int len) {
    if (len > 32) {
      Arrays.sort(a, 0, len);
      return;
    }
    for (int i = 1; i < len; i++) {
      int key = a[i];
      int j = i - 1;
      while (j >= 0 && a[j] > key) {
        a[j + 1] = a[j];
        j--;
      }
      a[j + 1] = key;
    }
  }

  /** Returns true if any instruction ID in the sorted array is a MATCH instruction. */
  private boolean hasMatch(int[] insts) {
    for (int id : insts) {
      if (prog.inst(id).opCode == InstOp.OP_MATCH) {
        return true;
      }
    }
    return false;
  }

  /** Returns a new array with all MATCH instructions removed from the input array. */
  private int[] stripMatch(int[] insts) {
    int count = 0;
    for (int id : insts) {
      if (prog.inst(id).opCode != InstOp.OP_MATCH) {
        count++;
      }
    }
    if (count == insts.length) {
      return insts;
    }
    int[] result = new int[count];
    int idx = 0;
    for (int id : insts) {
      if (prog.inst(id).opCode != InstOp.OP_MATCH) {
        result[idx++] = id;
      }
    }
    return result;
  }

  /** Performs a stable deduplication of the input array, preserving insertion order. */
  private int[] stableDedup(int[] insts, int count) {
    int uniqueCount = 0;
    int gen = ++expandGeneration;
    int[] visitedGen = expandVisitedGen;
    for (int i = 0; i < count; i++) {
      int id = insts[i];
      if (visitedGen[id] != gen) {
        visitedGen[id] = gen;
        uniqueCount++;
      }
    }
    if (uniqueCount == count) {
      int[] result = new int[count];
      System.arraycopy(insts, 0, result, 0, count);
      return result;
    }
    int[] result = new int[uniqueCount];
    gen = ++expandGeneration;
    int idx = 0;
    for (int i = 0; i < count; i++) {
      int id = insts[i];
      if (visitedGen[id] != gen) {
        visitedGen[id] = gen;
        result[idx++] = id;
      }
    }
    return result;
  }

  /** Collects all match IDs (MATCH instruction arg values) from a DFA state's NFA instructions. */
  private int[] collectMatchIds(int[] insts) {
    int count = 0;
    for (int id : insts) {
      if (prog.inst(id).opCode == InstOp.OP_MATCH) {
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
      if (ip.opCode == InstOp.OP_MATCH) {
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
   * Gets or creates a cached state with optional word-boundary match IDs. Returns null if the state
   * budget is exceeded.
   */
  private State getOrCreate(int[] insts, int flags, int[] wordBoundaryMatchIds) {
    if (insts.length == 0 && (flags & FLAG_MATCH) == 0) {
      return deadState;
    }
    lookupKey.reset(insts, flags);
    State s = cache.get(lookupKey);
    if (s != null) {
      return s;
    }
    if (cache.size() >= maxStates) {
      return null;
    }
    boolean isHighestPriorityMatch =
        insts.length > 0 && prog.inst(insts[0]).opCode == InstOp.OP_MATCH;
    s =
        new State(
            nextStateId++, insts, flags, wordBoundaryMatchIds, numClasses, isHighestPriorityMatch);
    addStateToFlatArrays(s);
    cache.put(lookupKey.copy(), s);
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

  private int emptyFlags(String text, int pos) {
    int flags =
        Nfa.emptyFlags(
            text,
            pos,
            prog.unixLines(),
            hasGraphemeSemantics,
            graphemeContext,
            prog.hasWordBoundary(),
            prog.hasTextAnchor());
    if (prog.reversed()) {
      int rev =
          flags
              & ~(EmptyOp.BEGIN_TEXT
                  | EmptyOp.END_TEXT
                  | EmptyOp.DOLLAR_END
                  | EmptyOp.BEGIN_LINE
                  | EmptyOp.END_LINE);
      if ((flags & EmptyOp.BEGIN_TEXT) != 0) {
        rev |= EmptyOp.END_TEXT;
      }
      if ((flags & (EmptyOp.END_TEXT | EmptyOp.DOLLAR_END)) != 0) {
        rev |= EmptyOp.BEGIN_TEXT;
      }
      if ((flags & EmptyOp.BEGIN_LINE) != 0) {
        rev |= EmptyOp.END_LINE;
      }
      if ((flags & EmptyOp.END_LINE) != 0) {
        rev |= EmptyOp.BEGIN_LINE;
      }
      flags = rev;
    }
    return flags;
  }

  /**
   * Computes the start state with an explicit "last word" override for reverse searches.
   *
   * @param reverseContext if true, FLAG_LAST_WORD is set based on the character AT pos (the char to
   *     the right of where a reverse scan begins), rather than the character BEFORE pos
   */
  private State startState(String text, int pos, boolean anchored, boolean reverseContext) {
    int startInst = anchored ? prog.start() : prog.startUnanchored();
    if (startInst == 0) {
      return deadState;
    }
    int emptyFlags = emptyFlags(text, pos);

    // Determine word-character context for \b/\B support.
    boolean lastWord = false;
    boolean lastUnicodeWord = false;
    if (prog.hasWordBoundary()) {
      if (reverseContext) {
        if (pos < text.length()) {
          int cp = text.codePointAt(pos);
          lastWord = Nfa.isWordChar(cp);
          lastUnicodeWord = cp < 128 ? lastWord : Nfa.isUnicodeWordChar(cp);
        }
      } else {
        if (pos > 0) {
          int cp = text.codePointBefore(pos);
          lastWord = Nfa.isWordChar(cp);
          lastUnicodeWord = cp < 128 ? lastWord : Nfa.isUnicodeWordChar(cp);
        }
      }
    }

    // Check the start state cache. The start state depends only on (anchored, reverseContext,
    // emptyFlags, lastWord, lastUnicodeWord), so positions with identical context share the same
    // start state.
    int cacheKey =
        (anchored ? anchoredCacheBit : 0)
            | (reverseContext ? reverseCacheBit : 0)
            | ((emptyFlags & startCacheEmptyFlagsMask) << 2)
            | (lastWord ? 2 : 0)
            | (lastUnicodeWord ? 1 : 0);
    State cached = startStateByContext[cacheKey];
    if (cached != null) {
      return cached;
    }

    computeBuf[0] = startInst;
    int[] insts = expand(computeBuf, 1, emptyFlags);
    int flags = emptyFlags & stateEmptyFlagsMask;
    if (hasMatch(insts)) {
      flags |= FLAG_MATCH;
    }
    if (lastWord) {
      flags |= FLAG_LAST_WORD;
    }
    if (lastUnicodeWord) {
      flags |= FLAG_LAST_UNICODE_WORD;
    }
    State s = getOrCreate(insts, flags);
    if (s != null) {
      startStateByContext[cacheKey] = s;
    }
    return s;
  }

  /**
   * Returns the lowest position at which text-length-dependent emptyFlags (END_TEXT or DOLLAR_END)
   * are active. Transitions whose destination is at or beyond this threshold must bypass the {@code
   * State.next[]} cache, because the cache is keyed by (state, character-class) which is
   * position-independent, but END_TEXT/DOLLAR_END depend on the absolute position relative to
   * {@code text.length()}.
   *
   * <ul>
   *   <li>END_TEXT is set at {@code pos == text.length()}.
   *   <li>DOLLAR_END is set at {@code pos == text.length()} and also before the trailing line
   *       terminator at end of text. For {@code \r\n} this can be up to 2 characters back.
   * </ul>
   *
   * <p>For most of the text, transitions are cached normally. Only the last 1–3 character
   * transitions (near end-of-text) bypass the cache. The end-of-text sentinel ({@code cp < 0}) is
   * always safe to cache because it always represents "at text end".
   */
  private int positionDependentThreshold(String text) {
    if (hasGraphemeSemantics) {
      return 0;
    }
    int len = text.length();
    if (prog.unixLines()) {
      return (len > 0 && text.charAt(len - 1) == '\n') ? len - 1 : len;
    }
    if (len >= 2 && text.charAt(len - 2) == '\r' && text.charAt(len - 1) == '\n') {
      return len - 2;
    }
    if (len > 0 && Nfa.isLineTerminator(text.charAt(len - 1))) {
      return len - 1;
    }
    return len;
  }

  /**
   * Returns the start position of the trailing line terminator at the end of text, or {@code
   * text.length()} if no trailing line terminator exists. This is the earliest position where
   * non-multiline {@code $} can match before a trailing line terminator.
   */
  private int trailingLineStart(String text) {
    int len = text.length();
    if (len == 0) {
      return len;
    }
    if (prog.unixLines()) {
      return (text.charAt(len - 1) == '\n') ? len - 1 : len;
    }
    if (len >= 2 && text.charAt(len - 2) == '\r' && text.charAt(len - 1) == '\n') {
      return len - 2;
    }
    if (Nfa.isLineTerminator(text.charAt(len - 1))) {
      return len - 1;
    }
    return len;
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
      int emptyFlags = emptyFlags(text, nextPos);
      // At end-of-text the "current" character is not a word char.
      boolean wasWord = (s.flags & FLAG_LAST_WORD) != 0;
      if (wasWord) {
        emptyFlags = (emptyFlags | EmptyOp.WORD_BOUNDARY) & ~EmptyOp.NON_WORD_BOUNDARY;
      } else {
        emptyFlags = (emptyFlags | EmptyOp.NON_WORD_BOUNDARY) & ~EmptyOp.WORD_BOUNDARY;
      }
      boolean wasUnicodeWord = (s.flags & FLAG_LAST_UNICODE_WORD) != 0;
      if (wasUnicodeWord) {
        emptyFlags =
            (emptyFlags | EmptyOp.UNICODE_WORD_BOUNDARY) & ~EmptyOp.UNICODE_NON_WORD_BOUNDARY;
      } else {
        emptyFlags =
            (emptyFlags | EmptyOp.UNICODE_NON_WORD_BOUNDARY) & ~EmptyOp.UNICODE_WORD_BOUNDARY;
      }
      // Re-expand from the successors of EMPTY_WIDTH instructions that now pass.
      int seedCount = 0;
      for (int id : s.insts) {
        Inst ip = prog.inst(id);
        if (ip.opCode == InstOp.OP_EMPTY_WIDTH && (ip.arg & ~emptyFlags) == 0) {
          computeBuf[seedCount++] = ip.out;
        }
      }
      if (seedCount == 0) {
        return deadState;
      }
      int[] nextInsts = expand(computeBuf, seedCount, emptyFlags);
      if (nextInsts.length == 0) {
        return deadState;
      }
      int flags = emptyFlags & stateEmptyFlagsMask;
      if (hasMatch(nextInsts)) {
        flags |= FLAG_MATCH | FLAG_MATCH_BEFORE;
      }
      // End-of-text is not a word char, so FLAG_LAST_WORD is not set.
      return getOrCreate(nextInsts, flags);
    }

    int pos = cp >= 0 ? nextPos - Character.charCount(cp) : text.length();
    // Step 1: Re-evaluate unsatisfied EMPTY_WIDTH instructions that are now satisfiable.
    // Two kinds of assertions fire BEFORE consuming cp and depend on context that can't
    // be predicted when the state was built:
    //
    //   (a) Word boundary (\b, \B): depends on whether cp is a word character, combined
    //       with the previous character (FLAG_LAST_WORD in the state).
    //   (b) END_LINE ($): fires when cp is a line terminator. The DFA caches transitions
    //       per (state, char-class). END_LINE at position P depends on whether text[P] is
    //       a line terminator, but two positions with the same (state, char-class) may have
    //       different characters at P. So END_LINE is deferred and re-expanded here when
    //       the current character is a line terminator.
    //
    // Both are re-expanded before consuming the character so that MATCH instructions
    // reached through these assertions are detected at the correct position.
    boolean isWord = false;
    boolean isUnicodeWord = false;
    int wordBeforeFlags = 0;
    int unicodeWordBeforeFlags = 0;
    if (prog.hasWordBoundary()) {
      isWord = Nfa.isWordChar(cp);
      boolean wasWord = (s.flags & FLAG_LAST_WORD) != 0;
      wordBeforeFlags = (isWord != wasWord) ? EmptyOp.WORD_BOUNDARY : EmptyOp.NON_WORD_BOUNDARY;
      isUnicodeWord = cp < 128 ? isWord : Nfa.isUnicodeWordChar(cp);
      boolean wasUnicodeWord = (s.flags & FLAG_LAST_UNICODE_WORD) != 0;
      unicodeWordBeforeFlags =
          (isUnicodeWord != wasUnicodeWord)
              ? EmptyOp.UNICODE_WORD_BOUNDARY
              : EmptyOp.UNICODE_NON_WORD_BOUNDARY;
    }
    boolean endLineHere;
    if (prog.unixLines()) {
      endLineHere = (cp == '\n');
    } else {
      endLineHere = Nfa.isLineTerminator(cp);
      // Don't fire END_LINE at the \n of an atomic \r\n pair. END_LINE fires before the \r
      // (the start of the pair), not between \r and \n.
      if (endLineHere && cp == '\n' && nextPos >= 2 && text.charAt(nextPos - 2) == '\r') {
        endLineHere = false;
      }
    }

    int reExpandEmptyFlags =
        (s.flags & stateEmptyFlagsMask) | wordBeforeFlags | unicodeWordBeforeFlags;
    if (endLineHere) {
      reExpandEmptyFlags |= EmptyOp.END_LINE;
    }
    int[] instsWithoutMatch = stripMatch(s.insts);

    // Collect expanded instructions and detect deferred matches in priority order.
    int[] tempExpanded = new int[prog.instructions.size()];
    int tempCount = 0;
    boolean hasMatchFromDeferred = false;
    boolean deferredMatchIsPrimary = false;
    int[] deferredMatchIds = null;
    boolean isMatchValid = false;

    for (int id : instsWithoutMatch) {
      Inst ip = prog.inst(id);
      if (ip.opCode == InstOp.OP_EMPTY_WIDTH && (ip.arg & ~reExpandEmptyFlags) == 0) {
        int[] expanded = expand(new int[] {ip.out}, 1, reExpandEmptyFlags);
        if (hasMatch(expanded)) {
          hasMatchFromDeferred = true;
          deferredMatchIds = collectMatchIds(expanded);
          deferredMatchIsPrimary = isMatchPrimary(expanded);
          // Add non-MATCH instructions from this expansion.
          for (int x : expanded) {
            if (prog.inst(x).opCode != InstOp.OP_MATCH) {
              if (tempCount == tempExpanded.length) {
                tempExpanded = Arrays.copyOf(tempExpanded, tempExpanded.length * 2);
              }
              tempExpanded[tempCount++] = x;
            }
          }
          // Check if this match is valid.
          if (!prog.anchorEnd()) {
            isMatchValid = true;
          } else {
            int textLen = text.length();
            int trailingTermStart = prog.dollarAnchorEnd() ? trailingLineStart(text) : textLen;
            if (pos == textLen || (trailingTermStart < textLen && pos == trailingTermStart)) {
              isMatchValid = true;
            }
          }
          if (isMatchValid && !prog.reversed()) {
            break; // Prune all lower-priority branches!
          }
        } else {
          for (int x : expanded) {
            if (tempCount == tempExpanded.length) {
              tempExpanded = Arrays.copyOf(tempExpanded, tempExpanded.length * 2);
            }
            tempExpanded[tempCount++] = x;
          }
        }
      } else {
        if (tempCount == tempExpanded.length) {
          tempExpanded = Arrays.copyOf(tempExpanded, tempExpanded.length * 2);
        }
        tempExpanded[tempCount++] = id;
      }
    }
    int[] expandedInsts = stableDedup(tempExpanded, tempCount);

    // Step 2: Process CHAR_RANGE/CHAR_CLASS transitions against cp.
    int successorCount = 0;
    boolean hasUnanchoredLoopTransition = false;
    int unanchoredStart = prog.anchorStart() ? 0 : prog.startUnanchored();
    for (int id : expandedInsts) {
      Inst ip = prog.inst(id);
      if (ip.opCode == InstOp.OP_CHAR_RANGE && ip.matchesChar(cp)) {
        if (unanchoredStart != 0 && ip.out == unanchoredStart) {
          hasUnanchoredLoopTransition = true;
        } else {
          computeBuf[successorCount++] = ip.out;
        }
      } else if (ip.opCode == InstOp.OP_CHAR_CLASS && ip.matchesCharClass(cp)) {
        if (unanchoredStart != 0 && ip.out == unanchoredStart) {
          hasUnanchoredLoopTransition = true;
        } else {
          computeBuf[successorCount++] = ip.out;
        }
      }
    }
    if (hasUnanchoredLoopTransition && !isMatchValid) {
      computeBuf[successorCount++] = unanchoredStart;
    }

    if (successorCount == 0) {
      // No character transition matched, but if deferred assertion expansion revealed a MATCH,
      // return a match state. FLAG_MATCH_BEFORE indicates the match position should be
      // recorded at the current position (before consuming cp), not after.
      if (hasMatchFromDeferred) {
        return getOrCreate(
            EMPTY_INSTS,
            FLAG_MATCH
                | FLAG_MATCH_BEFORE
                | (isWord ? FLAG_LAST_WORD : 0)
                | (isUnicodeWord ? FLAG_LAST_UNICODE_WORD : 0),
            deferredMatchIds);
      }
      return deadState;
    }

    // Compute empty flags at nextPos (after consuming cp). Omit deferred flags:
    // word boundary (depends on the next character) and END_LINE (depends on what's at
    // nextPos, not deterministic for cache). Unsatisfied EMPTY_WIDTH instructions will
    // remain in the frontier for re-evaluation when the next character arrives.
    int emptyFlags = emptyFlags(text, nextPos);
    emptyFlags &=
        ~(EmptyOp.WORD_BOUNDARY
            | EmptyOp.NON_WORD_BOUNDARY
            | EmptyOp.UNICODE_WORD_BOUNDARY
            | EmptyOp.UNICODE_NON_WORD_BOUNDARY
            | EmptyOp.END_LINE);

    int[] nextInsts = expand(computeBuf, successorCount, emptyFlags);

    if (nextInsts.length == 0) {
      if (hasMatchFromDeferred) {
        return getOrCreate(
            EMPTY_INSTS,
            FLAG_MATCH
                | FLAG_MATCH_BEFORE
                | (isWord ? FLAG_LAST_WORD : 0)
                | (isUnicodeWord ? FLAG_LAST_UNICODE_WORD : 0),
            deferredMatchIds);
      }
      return deadState;
    }

    int flags = emptyFlags & stateEmptyFlagsMask;
    if (hasMatchFromDeferred) {
      // A deferred assertion (\b, \B, or multiline $) fired before consuming the current
      // character and reached a MATCH instruction.
      if (deferredMatchIsPrimary) {
        flags |= FLAG_MATCH | FLAG_MATCH_BEFORE;
        if (hasMatch(nextInsts)) {
          flags |= FLAG_MATCH_AFTER_DEFERRED;
        }
      } else {
        if (hasMatch(nextInsts)) {
          flags |= FLAG_MATCH;
        } else {
          flags |= FLAG_MATCH | FLAG_MATCH_BEFORE;
        }
      }
    } else if (hasMatch(nextInsts)) {
      flags |= FLAG_MATCH;
    }
    if (isWord) {
      flags |= FLAG_LAST_WORD;
    }
    if (isUnicodeWord) {
      flags |= FLAG_LAST_UNICODE_WORD;
    }
    return getOrCreate(nextInsts, flags, deferredMatchIds);
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
    Dfa dfa = new Dfa(prog, maxStates, buildSetup(prog), longest);
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
    graphemeContext = GraphemeSupport.Context.create(text, hasGraphemeSemantics);
    int textLen = text.length();
    // If the compiled program requires end-of-text matching (stripped $ or \z), enforce it.
    boolean needEndMatch = prog.anchorEnd();
    boolean dollarEnd = prog.dollarAnchorEnd();
    // $ allows matching before a trailing line terminator at end of text (JDK default $ behavior).
    // Compute the start position of the trailing line terminator for dollarAnchorEnd matching.
    int trailingTermStart = dollarEnd ? trailingLineStart(text) : textLen;

    // Position-dependent flag threshold: emptyFlags at positions >= this threshold contain
    // text-length-dependent flags (END_TEXT at textLen, DOLLAR_END near the end when text ends
    // with a line terminator). Transitions computed at such positions must NOT be cached
    // because the same (state, character-class) pair at a different position (in the same or
    // a different text) would produce a different result. See the class-level comment on
    // DFA caching invariants.
    int posDepThreshold = positionDependentThreshold(text);

    State s = startState(text, startPos, anchored);
    if (s == null) {
      return null;
    }

    boolean matched = false;
    int matchEnd = -1;
    // Check if start state is already a match (e.g., empty pattern or .*? prefix).
    if (s.isMatch()) {
      if (isRequiredEndMatch(startPos, needEndMatch, textLen, trailingTermStart)) {
        matched = true;
        matchEnd = startPos;
        if (!longest && canStopAtFirstMatch(s, text, startPos, needEndMatch)) {
          return new SearchResult(true, startPos);
        }
      }
    }

    if (s == deadState) {
      return new SearchResult(matched, matchEnd);
    }

    int pos = startPos;
    // Fast path: loop through ASCII characters (characters < 128)
    while (pos < textLen) {
      int limit = Math.min(textLen, posDepThreshold - 1);
      int sId = s.id * numClasses;
      while (pos < limit) {
        char ch = text.charAt(pos);
        if (ch >= 128) {
          break;
        }
        int cls = asciiClassMap[ch];
        int nsId = transitions[sId + cls];
        if (nsId == 0) {
          break;
        }
        if (nsId < 0) {
          nsId = -nsId;
          State ns = offsetToState[nsId];
          if (ns.isMatch() && !needEndMatch) {
            boolean useBefore =
                (ns.flags & (FLAG_MATCH_BEFORE | FLAG_MATCH_AFTER_DEFERRED)) == FLAG_MATCH_BEFORE;
            int endPos = useBefore ? pos : pos + 1;
            if (!longest && ns.isHighestPriorityMatch) {
              return new SearchResult(true, endPos);
            }
            matched = true;
            matchEnd = endPos;
          }
        }
        sId = nsId;
        pos++;
      }
      s = offsetToState[sId];

      if (pos >= textLen) {
        break;
      }
      if (pos + 1 >= posDepThreshold) {
        break; // fall back to general loop for position-dependent flags
      }

      char ch = text.charAt(pos);
      if (ch >= 128) {
        break; // fall back to general loop
      }
      int cls = asciiClassMap[ch];
      State ns = s.next[cls];
      if (ns == null) {
        int effectiveNextPos = pos + 1;
        ns = computeNext(s, ch, text, effectiveNextPos);
        if (ns == null) {
          return null; // budget exceeded
        }
        addTransition(s, cls, ns);
      }
      s = ns;
      if (s == deadState) {
        break;
      }
      if (s.isMatch()) {
        boolean useBefore =
            (s.flags & (FLAG_MATCH_BEFORE | FLAG_MATCH_AFTER_DEFERRED)) == FLAG_MATCH_BEFORE;
        int endPos = useBefore ? pos : pos + 1;
        if (isRequiredEndMatch(endPos, needEndMatch, textLen, trailingTermStart)) {
          matched = true;
          matchEnd = endPos;
          if (!longest && canStopAtFirstMatch(s, text, endPos, needEndMatch)) {
            return new SearchResult(true, matchEnd);
          }
        }
      }
      pos++;
    }

    // General loop handles non-ASCII, position-dependent checks, and trailing end-of-text sentinel
    while (pos <= textLen) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
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
        } else if (Character.isHighSurrogate(ch)
            && pos + 1 < textLen
            && Character.isLowSurrogate(text.charAt(pos + 1))) {
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

      // Compute the next state, using the transition cache when safe.
      // Transitions where the destination position has text-length-dependent emptyFlags
      // (END_TEXT, DOLLAR_END) must bypass the cache to preserve the invariant that
      // cached transitions are position-independent. The end-of-text sentinel (cp < 0)
      // is always safe to cache because it always means "at text end".
      int effectiveNextPos = Math.min(nextPos, textLen);
      State ns;
      if (cp >= 0 && effectiveNextPos >= posDepThreshold) {
        ns = computeNext(s, cp, text, effectiveNextPos);
        if (ns == null) {
          return null; // budget exceeded
        }
      } else {
        ns = s.next[cls];
        if (ns == null) {
          ns = computeNext(s, cp, text, effectiveNextPos);
          if (ns == null) {
            return null; // budget exceeded
          }
          addTransition(s, cls, ns);
        }
      }

      s = ns;

      if (s == deadState) {
        break;
      }

      if (s.isMatch()) {
        // FLAG_MATCH_BEFORE indicates a deferred assertion (\b, multiline $) fired before
        // consuming the current character and reached MATCH. Try the before-consume position
        // first (it's at an earlier position, preserving leftmost-first semantics).
        if ((s.flags & (FLAG_MATCH_BEFORE | FLAG_MATCH_AFTER_DEFERRED)) == FLAG_MATCH_BEFORE) {
          int endPos = pos;
          if (isRequiredEndMatch(endPos, needEndMatch, textLen, trailingTermStart)) {
            matched = true;
            matchEnd = endPos;
            if (!longest && canStopAtFirstMatch(s, text, endPos, needEndMatch)) {
              return new SearchResult(true, matchEnd);
            }
          }
        }
        // Try the after-consume position: either no before-consume match exists, or it was
        // rejected (e.g., needEndMatch but pos != textLen) and an after-consume match also
        // exists (FLAG_MATCH_AFTER_DEFERRED).
        if ((s.flags & (FLAG_MATCH_BEFORE | FLAG_MATCH_AFTER_DEFERRED)) != FLAG_MATCH_BEFORE) {
          int endPos = Math.min(nextPos, textLen);
          if (isRequiredEndMatch(endPos, needEndMatch, textLen, trailingTermStart)) {
            matched = true;
            matchEnd = endPos;
            if (!longest && canStopAtFirstMatch(s, text, endPos, needEndMatch)) {
              return new SearchResult(true, matchEnd);
            }
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
   * @param endPos the position to start scanning backward from (exclusive upper bound of the match)
   * @param startLimit the earliest position to scan back to (inclusive), typically 0 or the
   *     prefix-acceleration start
   * @param anchored if true, the reverse match must start at {@code endPos} (meaning the forward
   *     match ends exactly there)
   * @param longest if true, find the longest reverse match (earliest start position)
   * @return search result where {@code pos} is the match start position, or {@code null} if the DFA
   *     exceeded its state budget
   */
  SearchResult doSearchReverse(
      String text, int endPos, int startLimit, boolean anchored, boolean longest) {
    graphemeContext = GraphemeSupport.Context.create(text, hasGraphemeSemantics);
    // The reversed program's "start of text" corresponds to endPos (the right edge of the match
    // region), and its "end of text" corresponds to startLimit (the left edge). We scan from
    // endPos backward to startLimit, feeding characters in reverse order.
    boolean needEndMatch = prog.anchorEnd();

    // Position-dependent threshold: same invariant as doSearch — bypass the transition cache
    // for positions where text-length-dependent emptyFlags (END_TEXT, DOLLAR_END) are active.
    int posDepThreshold = positionDependentThreshold(text);

    // Compute empty flags at the reverse start position (= endPos in the original text).
    State s = startState(text, endPos, anchored, true);
    if (s == null) {
      return null;
    }

    boolean matched = false;
    int matchStart = -1;
    boolean ambiguous = false;

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
    // Fast path: scan backward through ASCII characters
    while (pos > startLimit) {
      if (pos <= posDepThreshold) {
        int limit = startLimit;
        int sId = s.id * numClasses;
        while (pos > limit) {
          char ch = text.charAt(pos - 1);
          if (ch >= 128) {
            break;
          }
          int cls = asciiClassMap[ch];
          int nsId = transitions[sId + cls];
          if (nsId == 0) {
            break;
          }
          if (nsId < 0) {
            nsId = -nsId;
            State ns = offsetToState[nsId];
            if (ns.isMatch()) {
              int flags = ns.flags;
              if ((flags & FLAG_MATCH_BEFORE) != 0) {
                if (pos >= startLimit && (!needEndMatch || pos == startLimit)) {
                  boolean alreadyMatched = matched;
                  matched = true;
                  matchStart = longest && alreadyMatched ? Math.min(matchStart, pos) : pos;
                  ambiguous |= (flags & FLAG_MATCH_AFTER_DEFERRED) != 0;
                  if (!longest && !needEndMatch) {
                    return new SearchResult(true, matchStart, ambiguous);
                  }
                }
              }
              boolean hasOnlyBeforeMatch =
                  (flags & (FLAG_MATCH_BEFORE | FLAG_MATCH_AFTER_DEFERRED)) == FLAG_MATCH_BEFORE;
              int prevPos = pos - 1;
              if (!hasOnlyBeforeMatch) {
                if (prevPos >= startLimit && (!needEndMatch || prevPos == startLimit)) {
                  boolean alreadyMatched = matched;
                  matched = true;
                  matchStart = longest && alreadyMatched ? Math.min(matchStart, prevPos) : prevPos;
                  if (!longest && !needEndMatch) {
                    return new SearchResult(true, matchStart, ambiguous);
                  }
                }
              }
            }
          }
          sId = nsId;
          pos--;
        }
        s = offsetToState[sId];
      }

      if (pos <= startLimit) {
        break;
      }
      if (pos - 1 >= posDepThreshold) {
        break; // fall back to general loop for position-dependent context
      }

      char ch = text.charAt(pos - 1);
      if (ch >= 128) {
        break; // fall back
      }
      int cls = asciiClassMap[ch];
      State ns = s.next[cls];
      if (ns == null) {
        int effectivePrevPos = pos - 1;
        ns = computeNext(s, ch, text, effectivePrevPos);
        if (ns == null) {
          return null; // budget exceeded
        }
        addTransition(s, cls, ns);
      }
      s = ns;
      if (s == deadState) {
        break;
      }
      if (s.isMatch()) {
        if ((s.flags & FLAG_MATCH_BEFORE) != 0) {
          if (pos >= startLimit && (!needEndMatch || pos == startLimit)) {
            boolean alreadyMatched = matched;
            matched = true;
            matchStart = longest && alreadyMatched ? Math.min(matchStart, pos) : pos;
            ambiguous |= (s.flags & FLAG_MATCH_AFTER_DEFERRED) != 0;
            if (!longest && !needEndMatch) {
              return new SearchResult(true, matchStart, ambiguous);
            }
          }
        }
        boolean hasOnlyBeforeMatch =
            (s.flags & (FLAG_MATCH_BEFORE | FLAG_MATCH_AFTER_DEFERRED)) == FLAG_MATCH_BEFORE;
        int prevPos = pos - 1;
        if (!hasOnlyBeforeMatch) {
          if (prevPos >= startLimit && (!needEndMatch || prevPos == startLimit)) {
            boolean alreadyMatched = matched;
            matched = true;
            matchStart = longest && alreadyMatched ? Math.min(matchStart, prevPos) : prevPos;
            ambiguous |= (s.flags & FLAG_MATCH_AFTER_DEFERRED) != 0;
            if (!longest && !needEndMatch) {
              return new SearchResult(true, matchStart, ambiguous);
            }
          }
        }
      }
      pos--;
    }

    // General reverse loop
    while (pos >= startLimit) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      int cp;
      int prevPos;
      int cls;
      if (pos > 0) {
        // Read the code point just before pos (scanning backward).
        char ch = text.charAt(pos - 1);
        if (ch < 128) {
          // ASCII fast path.
          cp = ch;
          prevPos = pos - 1;
          cls = asciiClassMap[ch];
        } else if (Character.isLowSurrogate(ch)
            && pos - 2 >= startLimit
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

      // Bypass cache for position-dependent transitions (same invariant as doSearch).
      int effectivePrevPos = Math.max(prevPos, startLimit);
      State ns;
      if (cp >= 0 && effectivePrevPos >= posDepThreshold) {
        ns = computeNext(s, cp, text, effectivePrevPos);
        if (ns == null) {
          return null; // budget exceeded
        }
      } else {
        ns = s.next[cls];
        if (ns == null) {
          ns = computeNext(s, cp, text, effectivePrevPos);
          if (ns == null) {
            return null; // budget exceeded
          }
          addTransition(s, cls, ns);
        }
      }
      s = ns;

      if (s == deadState) {
        break;
      }

      if (s.isMatch()) {
        if ((s.flags & FLAG_MATCH_BEFORE) != 0) {
          if (pos >= startLimit && (!needEndMatch || pos == startLimit)) {
            boolean alreadyMatched = matched;
            matched = true;
            matchStart = longest && alreadyMatched ? Math.min(matchStart, pos) : pos;
            ambiguous |= (s.flags & FLAG_MATCH_AFTER_DEFERRED) != 0;
            if (!longest && !needEndMatch) {
              return new SearchResult(true, matchStart, ambiguous);
            }
          }
        }
        boolean hasOnlyBeforeMatch =
            (s.flags & (FLAG_MATCH_BEFORE | FLAG_MATCH_AFTER_DEFERRED)) == FLAG_MATCH_BEFORE;
        if (!hasOnlyBeforeMatch) {
          if (prevPos >= startLimit && (!needEndMatch || prevPos == startLimit)) {
            boolean alreadyMatched = matched;
            matched = true;
            matchStart = longest && alreadyMatched ? Math.min(matchStart, prevPos) : prevPos;
            ambiguous |= (s.flags & FLAG_MATCH_AFTER_DEFERRED) != 0;
            if (!longest && !needEndMatch) {
              return new SearchResult(true, matchStart, ambiguous);
            }
          }
        }
      }

      if (pos <= startLimit) {
        break;
      }
      pos = prevPos;
    }
    return new SearchResult(matched, matchStart, ambiguous);
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
    Dfa dfa = new Dfa(prog, maxStates, buildSetup(prog), true);
    return dfa.doSearchMany(text, anchored);
  }

  /**
   * Multi-match DFA search loop. Processes the entire text and collects all match IDs from match
   * states reached along the way.
   */
  ManyMatchResult doSearchMany(String text, boolean anchored) {
    graphemeContext = GraphemeSupport.Context.create(text, hasGraphemeSemantics);
    int textLen = text.length();
    boolean needEndMatch = prog.anchorEnd();
    boolean dollarEnd = prog.dollarAnchorEnd();
    int trailingTermStart = dollarEnd ? trailingLineStart(text) : textLen;

    // Position-dependent threshold: same invariant as doSearch.
    int posDepThreshold = positionDependentThreshold(text);

    State s = startState(text, 0, anchored);
    if (s == null) {
      return null;
    }

    // Use a bitset to track which match IDs have been seen.
    BitSet seen = new BitSet();

    // Check if start state is already a match.
    if (s.isMatch()) {
      if (!needEndMatch
          || textLen == 0
          || (trailingTermStart < textLen && trailingTermStart == 0)) {
        for (int id : collectMatchIds(s.insts)) {
          seen.set(id);
        }
      }
    }

    if (s != deadState) {
      int pos = 0;
      // Fast path: scan forward through ASCII characters
      int sId = s.id * numClasses;
      while (pos < textLen) {
        if (pos + 1 >= posDepThreshold) {
          break; // fall back to general loop for position-dependent context
        }
        char ch = text.charAt(pos);
        if (ch >= 128) {
          break; // fall back
        }
        int cls = asciiClassMap[ch];
        int nsId = transitions[sId + cls];
        if (nsId == 0) {
          s = offsetToState[sId];
          int effectiveNextPos = pos + 1;
          State ns = computeNext(s, ch, text, effectiveNextPos);
          if (ns == null) {
            return null; // budget exceeded
          }
          addTransition(s, cls, ns);
          nsId = transitions[sId + cls];
        }
        if (nsId == 0) { // deadState
          break;
        }
        int realNsId;
        if (nsId < 0) {
          realNsId = -nsId;
          State ns = offsetToState[realNsId];
          int flags = ns.flags;
          int endPos =
              ((flags & (FLAG_MATCH_BEFORE | FLAG_MATCH_AFTER_DEFERRED)) == FLAG_MATCH_BEFORE)
                  ? pos
                  : pos + 1;
          if (!needEndMatch
              || endPos == textLen
              || (trailingTermStart < textLen && endPos == trailingTermStart)) {
            // Collect match IDs from the state's instructions.
            for (int id : collectMatchIds(ns.insts)) {
              seen.set(id);
            }
            if (ns.wordBoundaryMatchIds != null) {
              for (int id : ns.wordBoundaryMatchIds) {
                seen.set(id);
              }
            }
          }
        } else {
          realNsId = nsId;
        }
        sId = realNsId;
        pos++;
      }
      s = offsetToState[sId];

      // General loop
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
          } else if (Character.isHighSurrogate(ch)
              && pos + 1 < textLen
              && Character.isLowSurrogate(text.charAt(pos + 1))) {
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

        // Bypass cache for position-dependent transitions (same invariant as doSearch).
        int effectiveNextPos = Math.min(nextPos, textLen);
        State ns;
        if (cp >= 0 && effectiveNextPos >= posDepThreshold) {
          ns = computeNext(s, cp, text, effectiveNextPos);
          if (ns == null) {
            return null; // budget exceeded
          }
        } else {
          ns = s.next[cls];
          if (ns == null) {
            ns = computeNext(s, cp, text, effectiveNextPos);
            if (ns == null) {
              return null; // budget exceeded
            }
            addTransition(s, cls, ns);
          }
        }
        s = ns;

        if (s == deadState) {
          break;
        }

        if (s.isMatch()) {
          int endPos =
              ((s.flags & (FLAG_MATCH_BEFORE | FLAG_MATCH_AFTER_DEFERRED)) == FLAG_MATCH_BEFORE)
                  ? pos
                  : Math.min(nextPos, textLen);
          if (!needEndMatch
              || endPos == textLen
              || (trailingTermStart < textLen && endPos == trailingTermStart)) {
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

  private boolean isMatchPrimary(int[] expanded) {
    int firstMatch = -1;
    int firstActive = -1;
    for (int i = 0; i < expanded.length; i++) {
      int op = prog.inst(expanded[i]).opCode;
      if (op == InstOp.OP_MATCH) {
        if (firstMatch == -1) {
          firstMatch = i;
        }
      } else if (op == InstOp.OP_CHAR_RANGE || op == InstOp.OP_CHAR_CLASS) {
        if (firstActive == -1) {
          firstActive = i;
        }
      }
    }
    if (firstMatch == -1) {
      return false;
    }
    if (firstActive == -1) {
      return true;
    }
    return firstMatch < firstActive;
  }

  private boolean canStopAtFirstMatch(State s, String text, int pos, boolean needEndMatch) {
    if (!needEndMatch) {
      return s.isHighestPriorityMatch;
    }
    int cp = codePointAt(text, pos);
    for (int id : s.insts) {
      Inst inst = prog.inst(id);
      switch (inst.opCode) {
        case InstOp.OP_MATCH -> {
          return true;
        }
        case InstOp.OP_CHAR_RANGE -> {
          if (cp >= 0 && inst.matchesChar(cp)) {
            return false;
          }
        }
        case InstOp.OP_CHAR_CLASS -> {
          if (cp >= 0 && inst.matchesCharClass(cp)) {
            return false;
          }
        }
        default -> {}
      }
    }
    return false;
  }

  private static int codePointAt(String text, int pos) {
    if (pos >= text.length()) {
      return -1;
    }
    char ch = text.charAt(pos);
    if (Character.isHighSurrogate(ch)
        && pos + 1 < text.length()
        && Character.isLowSurrogate(text.charAt(pos + 1))) {
      return Character.toCodePoint(ch, text.charAt(pos + 1));
    }
    return ch;
  }

  private static boolean isRequiredEndMatch(
      int pos, boolean needEndMatch, int textLen, int trailingTermStart) {
    return !needEndMatch
        || pos == textLen
        || (trailingTermStart < textLen && pos == trailingTermStart);
  }

  private Dfa() {
    throw new AssertionError("non-instantiable");
  }
}
