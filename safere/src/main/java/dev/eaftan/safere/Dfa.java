// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import java.util.Arrays;
import java.util.HashMap;
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

  /**
   * Flag bit: when {@link #FLAG_MATCH_BEFORE} is set, indicates that an after-consume match ALSO
   * exists (from character transitions reaching MATCH). The search loop should try the
   * before-consume match first (earlier position) and fall back to the after-consume match if the
   * before-consume match is rejected (e.g., by {@code needEndMatch} requiring end-of-text).
   */
  private static final int FLAG_MATCH_AFTER_DEFERRED = 1 << 11;

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
    /** Transitions indexed by equivalence class; null entry = not yet computed. */
    final State[] next;

    State(int[] insts, int flags, int numClasses) {
      this(insts, flags, null, numClasses);
    }

    State(int[] insts, int flags, int[] wordBoundaryMatchIds, int numClasses) {
      this.insts = insts;
      this.flags = flags;
      this.wordBoundaryMatchIds = wordBoundaryMatchIds;
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
   * position (6 bits for BOL/EOL/BOT/EOT/WB/NWB), and whether the previous character was a word
   * character. This gives at most 2 × 2 × 64 × 2 = 512 combinations. Caching avoids the expensive
   * {@link #expand} call and its {@code Arrays.copyOf} allocation on every DFA search.
   */
  private final State[] startStateByContext = new State[1024];

  /** Shared empty instruction array to avoid repeated zero-length allocations. */
  private static final int[] EMPTY_INSTS = new int[0];

  // ---------------------------------------------------------------------------
  // Construction
  // ---------------------------------------------------------------------------

  /**
   * Pre-computed immutable DFA setup: equivalence class boundaries and ASCII lookup table. These are
   * derived solely from the compiled {@link Prog} and can be shared across all DFA instances for the
   * same program (e.g., across multiple {@link dev.eaftan.safere.Matcher} instances).
   */
  record Setup(int[] boundaries, int numClasses, int[] asciiClassMap) {}

  /**
   * Builds a reusable {@link Setup} from a compiled program. The result is immutable and can be
   * stored on a {@link dev.eaftan.safere.Pattern} for sharing across matchers.
   */
  static Setup buildSetup(Prog prog) {
    int[] boundaries = buildBoundaries(prog);
    int numClasses = boundaries.length + 1 + 1; // intervals + end-of-text
    int[] asciiClassMap = buildAsciiClassMap(boundaries);
    return new Setup(boundaries, numClasses, asciiClassMap);
  }

  Dfa(Prog prog, int maxStates, Setup setup) {
    this.prog = prog;
    this.maxStates = maxStates;
    this.boundaries = setup.boundaries;
    this.numClasses = setup.numClasses;
    this.asciiClassMap = setup.asciiClassMap;
    this.expandVisitedGen = new int[prog.size()];
    this.expandStack = new int[prog.size()];
    this.expandFrontier = new int[prog.size()];
    this.computeBuf = new int[prog.size()];
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
        if ((inst.arg & (EmptyOp.BEGIN_LINE | EmptyOp.END_LINE)) != 0) {
          hasLineBoundary = true;
        }
      }
    }
    if (hasLineBoundary) {
      // Line terminator characters trigger BEGIN_LINE (at the position after them) and
      // END_LINE (at their position). Give each its own equivalence class so the DFA can
      // cache different transitions for line terminators vs other characters.
      bounds.add(0x0A);   // '\n'
      bounds.add(0x0B);   // '\n' + 1
      bounds.add(0x0D);   // '\r'
      bounds.add(0x0E);   // '\r' + 1
      // Additional JDK line terminators: \u0085, \u2028, \u2029
      bounds.add(0x0085); // NEXT LINE
      bounds.add(0x0086); // NEXT LINE + 1
      bounds.add(0x2028); // LINE SEPARATOR
      bounds.add(0x202A); // PARAGRAPH SEPARATOR + 1 (covers both \u2028 and \u2029)
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

  /** Adds boundaries for a [lo, hi] code point range. */
  private static void addRangeBoundaries(TreeSet<Integer> bounds, int lo, int hi) {
    bounds.add(lo);
    if (hi < Utils.MAX_RUNE) {
      bounds.add(hi + 1);
    }
  }

  /**
   * Adds boundaries for all case-fold equivalents of each code point in [lo, hi]. When a
   * CHAR_RANGE instruction has the fold-case flag, characters outside [lo, hi] that fold into
   * the range must have their own equivalence classes so the DFA doesn't conflate them with
   * non-matching characters in the same class.
   */
  private static void addCaseFoldBoundaries(TreeSet<Integer> bounds, int lo, int hi) {
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
  private int[] expand(int[] seeds, int seedCount, int emptyFlags) {
    int gen = ++expandGeneration;
    int[] visitedGen = expandVisitedGen;
    int[] stack = expandStack;
    int[] frontier = expandFrontier;
    int stackTop = 0;
    int frontierSize = 0;

    // Push seeds onto stack.
    for (int i = 0; i < seedCount; i++) {
      stack[stackTop++] = seeds[i];
    }

    while (stackTop > 0) {
      int id = stack[--stackTop];
      if (id == 0 || id >= prog.size() || visitedGen[id] == gen) {
        continue;
      }
      visitedGen[id] = gen;

      Inst ip = prog.inst(id);
      switch (ip.opCode) {
        case InstOp.OP_FAIL -> {}
        case InstOp.OP_ALT, InstOp.OP_ALT_MATCH -> {
          stack[stackTop++] = ip.out;
          stack[stackTop++] = ip.out1;
        }
        case InstOp.OP_NOP -> stack[stackTop++] = ip.out;
        case InstOp.OP_CAPTURE -> stack[stackTop++] = ip.out;
        case InstOp.OP_PROGRESS_CHECK -> {
          stack[stackTop++] = ip.out;
          stack[stackTop++] = ip.out1;
        }
        case InstOp.OP_EMPTY_WIDTH -> {
          if ((ip.arg & ~emptyFlags) == 0) {
            stack[stackTop++] = ip.out;
          } else {
            frontier[frontierSize++] = id;
          }
        }
        case InstOp.OP_CHAR_RANGE, InstOp.OP_CHAR_CLASS, InstOp.OP_MATCH ->
            frontier[frontierSize++] = id;
        default -> {}
      }
    }

    sortSmall(frontier, frontierSize);
    return Arrays.copyOf(frontier, frontierSize);
  }

  /** Insertion sort for small arrays; faster than Arrays.sort for typical DFA frontier sizes. */
  private static void sortSmall(int[] a, int len) {
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
    int emptyFlags = Nfa.emptyFlags(text, pos, prog.unixLines());

    // Determine word-character context for \b/\B support.
    boolean lastWord;
    if (reverseContext) {
      lastWord = pos < text.length() && Nfa.isWordChar(text.codePointAt(pos));
    } else {
      lastWord = pos > 0 && Nfa.isWordChar(text.codePointBefore(pos));
    }

    // Check the start state cache. The start state depends only on (anchored, reverseContext,
    // emptyFlags, lastWord), so positions with identical context share the same start state.
    int cacheKey = (anchored ? 512 : 0) | (reverseContext ? 256 : 0)
        | ((emptyFlags & 0x7F) << 1) | (lastWord ? 1 : 0);
    State cached = startStateByContext[cacheKey];
    if (cached != null) {
      return cached;
    }

    computeBuf[0] = startInst;
    int[] insts = expand(computeBuf, 1, emptyFlags);
    int flags = emptyFlags & 0xFF;
    if (hasMatch(insts)) {
      flags |= FLAG_MATCH;
    }
    if (lastWord) {
      flags |= FLAG_LAST_WORD;
    }
    State s = getOrCreate(insts, flags);
    if (s != null) {
      startStateByContext[cacheKey] = s;
    }
    return s;
  }

  /**
   * Returns the lowest position at which text-length-dependent emptyFlags (END_TEXT or DOLLAR_END)
   * are active. Transitions whose destination is at or beyond this threshold must bypass the
   * {@code State.next[]} cache, because the cache is keyed by (state, character-class) which is
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
   * transitions (near end-of-text) bypass the cache. The end-of-text sentinel ({@code cp < 0})
   * is always safe to cache because it always represents "at text end".
   */
  private int positionDependentThreshold(String text) {
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
   * Returns the start position of the trailing line terminator at the end of text, or
   * {@code text.length()} if no trailing line terminator exists. This is the earliest position
   * where non-multiline {@code $} can match before a trailing line terminator.
   */
  private int trailingLineTermStart(String text) {
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
      int emptyFlags = Nfa.emptyFlags(text, nextPos, prog.unixLines());
      // At end-of-text the "current" character is not a word char.
      boolean wasWord = (s.flags & FLAG_LAST_WORD) != 0;
      if (wasWord) {
        emptyFlags = (emptyFlags | EmptyOp.WORD_BOUNDARY) & ~EmptyOp.NON_WORD_BOUNDARY;
      } else {
        emptyFlags = (emptyFlags | EmptyOp.NON_WORD_BOUNDARY) & ~EmptyOp.WORD_BOUNDARY;
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
      int flags = emptyFlags & 0xFF;
      if (hasMatch(nextInsts)) {
        flags |= FLAG_MATCH;
      }
      // End-of-text is not a word char, so FLAG_LAST_WORD is not set.
      return getOrCreate(nextInsts, flags);
    }

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
    boolean isWord = Nfa.isWordChar(cp);
    boolean wasWord = (s.flags & FLAG_LAST_WORD) != 0;
    int wordBeforeFlags = (isWord != wasWord) ? EmptyOp.WORD_BOUNDARY
        : EmptyOp.NON_WORD_BOUNDARY;
    boolean endLineHere;
    if (prog.unixLines()) {
      endLineHere = (cp == '\n');
    } else {
      endLineHere = Nfa.isLineTerminator(cp);
      // Don't fire END_LINE at the \n of an atomic \r\n pair. END_LINE fires before the \r
      // (the start of the pair), not between \r and \n.
      if (endLineHere && cp == '\n'
          && nextPos >= 2 && text.charAt(nextPos - 2) == '\r') {
        endLineHere = false;
      }
    }

    // Collect successors of unsatisfied EMPTY_WIDTH instructions whose deferred flags
    // are now satisfiable and that have no other unsatisfied flags.
    int reExpandCount = 0;
    int deferredMask = EmptyOp.WORD_BOUNDARY | EmptyOp.NON_WORD_BOUNDARY | EmptyOp.END_LINE;
    for (int id : s.insts) {
      Inst ip = prog.inst(id);
      if (ip.opCode == InstOp.OP_EMPTY_WIDTH) {
        int wordFlags = ip.arg & (EmptyOp.WORD_BOUNDARY | EmptyOp.NON_WORD_BOUNDARY);
        int endLineFlag = ip.arg & EmptyOp.END_LINE;
        int otherFlags = ip.arg & ~deferredMask;

        // The instruction must have at least one deferred flag, no non-deferred flags,
        // and all deferred flags must be currently satisfiable.
        boolean hasDeferred = (wordFlags | endLineFlag) != 0;
        boolean wordOk = wordFlags == 0 || (wordFlags & ~wordBeforeFlags) == 0;
        boolean lineOk = endLineFlag == 0 || endLineHere;
        if (otherFlags == 0 && hasDeferred && wordOk && lineOk) {
          computeBuf[reExpandCount++] = ip.out;
        }
      }
    }

    // If deferred assertions fired, expand their successors to get additional
    // CHAR_RANGE and MATCH instructions that are now reachable.
    int[] expandedInsts = s.insts;
    boolean hasMatchFromDeferred = false;
    int[] deferredMatchIds = null;
    if (reExpandCount > 0) {
      // Include the computed deferred flags so that chained assertions of the same
      // kind (e.g., \b\b or $$) can fire during expansion. Without this, the first
      // \b fires but expand() wouldn't satisfy the second \b because WORD_BOUNDARY
      // was stripped from the state's cached emptyFlags.
      int reExpandEmptyFlags = (s.flags & 0xFF) | wordBeforeFlags;
      if (endLineHere) {
        reExpandEmptyFlags |= EmptyOp.END_LINE;
      }
      int[] newInsts = expand(computeBuf, reExpandCount, reExpandEmptyFlags);

      // Check if the re-expansion revealed any MATCH instructions. Collect their IDs
      // for PatternSet multi-match before merging with the original state.
      int matchCount = 0;
      int[] matchIds = null;
      for (int id : newInsts) {
        Inst ip = prog.inst(id);
        if (ip.opCode == InstOp.OP_MATCH) {
          hasMatchFromDeferred = true;
          if (matchIds == null) {
            matchIds = new int[newInsts.length];
          }
          matchIds[matchCount++] = ip.arg;
        }
      }
      if (matchCount > 0) {
        deferredMatchIds = Arrays.copyOf(matchIds, matchCount);
      }

      // Merge with existing instructions.
      expandedInsts = mergeInsts(s.insts, newInsts);
    }

    // Step 2: Process CHAR_RANGE/CHAR_CLASS transitions against cp.
    int successorCount = 0;
    for (int id : expandedInsts) {
      Inst ip = prog.inst(id);
      if (ip.opCode == InstOp.OP_CHAR_RANGE && ip.matchesChar(cp)) {
        computeBuf[successorCount++] = ip.out;
      } else if (ip.opCode == InstOp.OP_CHAR_CLASS && ip.matchesCharClass(cp)) {
        computeBuf[successorCount++] = ip.out;
      }
    }

    if (successorCount == 0) {
      // No character transition matched, but if deferred assertion expansion revealed a MATCH,
      // return a match state. FLAG_MATCH_BEFORE indicates the match position should be
      // recorded at the current position (before consuming cp), not after.
      if (hasMatchFromDeferred) {
        return getOrCreate(EMPTY_INSTS,
            FLAG_MATCH | FLAG_MATCH_BEFORE | (isWord ? FLAG_LAST_WORD : 0),
            deferredMatchIds);
      }
      return deadState;
    }

    // Compute empty flags at nextPos (after consuming cp). Omit deferred flags:
    // word boundary (depends on the next character) and END_LINE (depends on what's at
    // nextPos, not deterministic for cache). Unsatisfied EMPTY_WIDTH instructions will
    // remain in the frontier for re-evaluation when the next character arrives.
    int emptyFlags = Nfa.emptyFlags(text, nextPos, prog.unixLines());
    emptyFlags &= ~(EmptyOp.WORD_BOUNDARY | EmptyOp.NON_WORD_BOUNDARY | EmptyOp.END_LINE);

    int[] nextInsts = expand(computeBuf, successorCount, emptyFlags);

    if (nextInsts.length == 0) {
      if (hasMatchFromDeferred) {
        return getOrCreate(EMPTY_INSTS,
            FLAG_MATCH | FLAG_MATCH_BEFORE | (isWord ? FLAG_LAST_WORD : 0),
            deferredMatchIds);
      }
      return deadState;
    }

    int flags = emptyFlags & 0xFF;
    if (hasMatchFromDeferred) {
      // A deferred assertion (\b, \B, or multiline $) fired before consuming the current
      // character and reached a MATCH instruction. This match is at position `pos` (before
      // the character), which is earlier than any match at `nextPos` (after consuming).
      // FLAG_MATCH_BEFORE ensures doSearch records the match end at `pos`, preserving
      // leftmost-first semantics.
      flags |= FLAG_MATCH | FLAG_MATCH_BEFORE;
      if (hasMatch(nextInsts)) {
        // Character transitions also reach MATCH (match at nextPos). Record this so doSearch
        // can fall back to the after-consume match when the before-consume match is rejected
        // (e.g., patterns ending with $ require the match to end at text length).
        flags |= FLAG_MATCH_AFTER_DEFERRED;
      }
    } else if (hasMatch(nextInsts)) {
      flags |= FLAG_MATCH;
    }
    if (isWord) {
      flags |= FLAG_LAST_WORD;
    }
    return getOrCreate(nextInsts, flags, deferredMatchIds);
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
    Dfa dfa = new Dfa(prog, maxStates, buildSetup(prog));
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
    // If the compiled program requires end-of-text matching (stripped $ or \z), enforce it.
    boolean needEndMatch = prog.anchorEnd();
    boolean dollarEnd = prog.dollarAnchorEnd();
    // $ allows matching before a trailing line terminator at end of text (JDK default $ behavior).
    // Compute the start position of the trailing line terminator for dollarAnchorEnd matching.
    int trailingTermStart = dollarEnd ? trailingLineTermStart(text) : textLen;

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
      if (!needEndMatch || textLen == startPos
          || (trailingTermStart < textLen && trailingTermStart == startPos)) {
        matched = true;
        matchEnd = startPos;
        if (!longest && (!needEndMatch || startPos == textLen)) {
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
          s.next[cls] = ns;
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
        if ((s.flags & FLAG_MATCH_BEFORE) != 0) {
          int endPos = pos;
          if (!needEndMatch || endPos == textLen
              || (trailingTermStart < textLen && endPos == trailingTermStart)) {
            matched = true;
            matchEnd = endPos;
            if (!longest && (!needEndMatch || endPos == textLen)) {
              return new SearchResult(true, matchEnd);
            }
          }
        }
        // Try the after-consume position: either no before-consume match exists, or it was
        // rejected (e.g., needEndMatch but pos != textLen) and an after-consume match also
        // exists (FLAG_MATCH_AFTER_DEFERRED).
        if ((s.flags & FLAG_MATCH_BEFORE) == 0
            || (s.flags & FLAG_MATCH_AFTER_DEFERRED) != 0) {
          int endPos = Math.min(nextPos, textLen);
          if (!needEndMatch || endPos == textLen
              || (trailingTermStart < textLen && endPos == trailingTermStart)) {
            matched = true;
            matchEnd = endPos;
            if (!longest && (!needEndMatch || endPos == textLen)) {
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
          s.next[cls] = ns;
        }
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
    Dfa dfa = new Dfa(prog, maxStates, buildSetup(prog));
    return dfa.doSearchMany(text, anchored);
  }

  /**
   * Multi-match DFA search loop. Processes the entire text and collects all match IDs from match
   * states reached along the way.
   */
  ManyMatchResult doSearchMany(String text, boolean anchored) {
    int textLen = text.length();
    boolean needEndMatch = prog.anchorEnd();
    boolean dollarEnd = prog.dollarAnchorEnd();
    int trailingTermStart = dollarEnd ? trailingLineTermStart(text) : textLen;

    // Position-dependent threshold: same invariant as doSearch.
    int posDepThreshold = positionDependentThreshold(text);

    State s = startState(text, 0, anchored);
    if (s == null) {
      return null;
    }

    // Use a bitset to track which match IDs have been seen.
    java.util.BitSet seen = new java.util.BitSet();

    // Check if start state is already a match.
    if (s.isMatch()) {
      if (!needEndMatch || textLen == 0
          || (trailingTermStart < textLen && trailingTermStart == 0)) {
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
            s.next[cls] = ns;
          }
        }
        s = ns;

        if (s == deadState) {
          break;
        }

        if (s.isMatch()) {
          int endPos = (s.flags & FLAG_MATCH_BEFORE) != 0
              ? pos : Math.min(nextPos, textLen);
          if (!needEndMatch || endPos == textLen
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

  private Dfa() {
    throw new AssertionError("non-instantiable");
  }
}
