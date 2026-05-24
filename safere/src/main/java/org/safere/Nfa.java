// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
  private static final int[][] EXTENDED_PICTOGRAPHIC =
      UnicodeProperties.lookupBinaryProperty("Extended_Pictographic");

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

  /** A thread in the NFA: an instruction index paired with capture metadata. */
  // TODO(#98): Replace int[] with Guava ImmutableIntArray to get proper value semantics.
  @SuppressWarnings("ArrayRecordComponent")
  private record NfaThread(int id, int[] capture, boolean consumedInput, int graphemeStart) {}

  private static final int VISIT_KEY_VARIANT_BITS = 5;
  private static final int GRAPHEME_LOW_SURROGATE_PAIR_VISIBLE = 1;
  private static final int GRAPHEME_EXTENDED_PICTOGRAPHIC_VISIBLE = 1 << 1;
  private static final int GRAPHEME_REGIONAL_INDICATOR_ODD = 1 << 2;
  private static final int GRAPHEME_INDIC_CONJUNCT_LINKER_VISIBLE = 1 << 3;
  private static final int GRAPHEME_INDIC_CONJUNCT_SEQUENCE_VISIBLE = 1 << 4;

  private static final class QueueState {
    final List<NfaThread> threads = new ArrayList<>();
    final Set<Long> visited = new HashSet<>();

    boolean isEmpty() {
      return threads.isEmpty();
    }

    void clear() {
      threads.clear();
      visited.clear();
    }
  }

  @SuppressWarnings("ArrayRecordComponent")
  record SearchResult(int[] groups) {}

  /**
   * Per-input grapheme segmentation context.
   *
   * <p>UAX #29 boundary checks are finite-state, but some rules need state from earlier code
   * points. Keep that state here so engines can answer boundary questions in O(1) while scanning.
   */
  static final class GraphemeContext {
    private static final GraphemeContext EMPTY = new GraphemeContext(null);

    private final String text;
    private int[] regionalIndicatorStartsBefore;
    private int[] regionalIndicatorRunStartBefore;
    private int[] extendedPictographicStartBefore;
    private int[] extendedPictographicPrependBefore;
    private int[] indicConjunctSequenceStartBefore;
    private int[] indicConjunctLinkerStartBefore;

    private GraphemeContext(String text) {
      this.text = text;
    }

    static GraphemeContext create(String text, boolean includeGraphemeClusterBoundary) {
      if (!includeGraphemeClusterBoundary) {
        return EMPTY;
      }
      return new GraphemeContext(text);
    }

    boolean hasEvenRegionalIndicatorsBefore(int pos, int regionStart) {
      if (text == null) {
        return true;
      }
      int start = Math.max(0, Math.min(regionStart, text.length()));
      if (pos <= start || pos > text.length()) {
        return true;
      }
      ensureRegionalIndicatorRunData();
      int runStart = regionalIndicatorRunStartBefore[pos];
      int countStart = Math.max(start, runStart);
      int regionalIndicatorsBefore =
          regionalIndicatorStartsBefore[pos] - regionalIndicatorStartsBefore[countStart];
      return (regionalIndicatorsBefore & 1) == 0;
    }

    boolean hasExtendedPictographicBefore(int pos, int regionStart) {
      if (text == null) {
        return false;
      }
      int start = Math.max(0, Math.min(regionStart, text.length()));
      if (pos <= start || pos > text.length()) {
        return false;
      }
      ensureExtendedPictographicData();
      int pictographicStart = extendedPictographicStartBefore[pos];
      if (pictographicStart < start) {
        return false;
      }
      int prependStart = extendedPictographicPrependBefore[pos];
      return prependStart < start;
    }

    boolean hasIndicConjunctLinkerBefore(int pos, int regionStart) {
      if (text == null) {
        return false;
      }
      int start = Math.max(0, Math.min(regionStart, text.length()));
      if (pos <= start || pos > text.length()) {
        return false;
      }
      ensureIndicConjunctData();
      return indicConjunctLinkerStartBefore[pos] >= start;
    }

    boolean hasIndicConjunctSequenceBefore(int pos, int regionStart) {
      if (text == null) {
        return false;
      }
      int start = Math.max(0, Math.min(regionStart, text.length()));
      if (pos <= start || pos > text.length()) {
        return false;
      }
      ensureIndicConjunctData();
      return indicConjunctSequenceStartBefore[pos] >= start;
    }

    private void ensureRegionalIndicatorRunData() {
      if (regionalIndicatorStartsBefore != null) {
        return;
      }
      regionalIndicatorStartsBefore = new int[text.length() + 1];
      regionalIndicatorRunStartBefore = new int[text.length() + 1];
      int runStart = 0;
      int runLength = 0;
      int pos = 0;
      while (pos < text.length()) {
        int cp = text.codePointAt(pos);
        int next = pos + Character.charCount(cp);
        if (isRegionalIndicator(cp)) {
          if (runLength == 0) {
            runStart = pos;
          }
          runLength++;
          for (int i = pos + 1; i <= next; i++) {
            regionalIndicatorStartsBefore[i] = runLength;
            regionalIndicatorRunStartBefore[i] = runStart;
          }
        } else {
          runLength = 0;
        }
        pos = next;
      }
    }

    private void ensureIndicConjunctData() {
      if (indicConjunctLinkerStartBefore != null) {
        return;
      }
      indicConjunctSequenceStartBefore = new int[text.length() + 1];
      indicConjunctLinkerStartBefore = new int[text.length() + 1];
      Arrays.fill(indicConjunctSequenceStartBefore, -1);
      Arrays.fill(indicConjunctLinkerStartBefore, -1);

      int sequenceStart = -1;
      boolean linkerSeen = false;
      int pos = 0;
      while (pos < text.length()) {
        int cp = text.codePointAt(pos);
        int next = pos + Character.charCount(cp);
        if (isIndicConjunctConsonant(cp)) {
          sequenceStart = pos;
          linkerSeen = false;
        } else if (isIndicConjunctLinker(cp)) {
          if (sequenceStart >= 0) {
            linkerSeen = true;
          }
        } else if (!isIndicConjunctExtend(cp)) {
          sequenceStart = -1;
          linkerSeen = false;
        }
        int activeSequenceStart = sequenceStart;
        int linkerStart = linkerSeen ? sequenceStart : -1;
        for (int i = pos + 1; i <= next; i++) {
          indicConjunctSequenceStartBefore[i] = activeSequenceStart;
          indicConjunctLinkerStartBefore[i] = linkerStart;
        }
        pos = next;
      }
    }

    private void ensureExtendedPictographicData() {
      if (extendedPictographicStartBefore != null) {
        return;
      }
      extendedPictographicStartBefore = new int[text.length() + 1];
      extendedPictographicPrependBefore = new int[text.length() + 1];
      Arrays.fill(extendedPictographicStartBefore, -1);
      Arrays.fill(extendedPictographicPrependBefore, -1);

      int visiblePictographicStart = -1;
      int visiblePrependStart = -1;
      int pos = 0;
      while (pos < text.length()) {
        int cp = text.codePointAt(pos);
        int next = pos + Character.charCount(cp);
        if (containsCodePoint(EXTENDED_PICTOGRAPHIC, cp)) {
          visiblePictographicStart = pos;
          visiblePrependStart = immediatePrependStartBefore(text, pos);
        } else if (!isGraphemeExtend(cp)) {
          visiblePictographicStart = -1;
          visiblePrependStart = -1;
        }
        for (int i = pos + 1; i <= next; i++) {
          extendedPictographicStartBefore[i] = visiblePictographicStart;
          extendedPictographicPrependBefore[i] = visiblePrependStart;
        }
        pos = next;
      }
    }
  }

  private final Prog prog;
  private final int ncapture;

  /** Total thread array size: ncapture slots for captures + numLoopRegs for progress checks. */
  private final int threadArraySize;

  private final boolean longest;
  private final boolean endmatch;
  private final int endPos;
  private final int anchorEndPos;
  private final int graphemeConsumeEndPos;
  private final int consumeRegionStart;
  private final int boundaryRegionStart;
  private final int boundaryEndPos;
  private final GraphemeContext graphemeContext;

  private boolean matched;
  private int[] bestMatch;

  private Nfa(
      Prog prog,
      GraphemeContext graphemeContext,
      int ncapture,
      boolean longest,
      boolean endmatch,
      int endPos,
      int anchorEndPos,
      int graphemeConsumeEndPos,
      int consumeRegionStart,
      int boundaryRegionStart,
      int boundaryEndPos) {
    this.prog = prog;
    this.ncapture = ncapture;
    this.threadArraySize = ncapture + prog.numLoopRegs();
    this.longest = longest;
    this.endmatch = endmatch;
    this.endPos = endPos;
    this.anchorEndPos = anchorEndPos;
    this.graphemeConsumeEndPos = graphemeConsumeEndPos;
    this.consumeRegionStart = consumeRegionStart;
    this.boundaryRegionStart = boundaryRegionStart;
    this.boundaryEndPos = boundaryEndPos;
    this.graphemeContext = graphemeContext;
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
  static SearchResult search(Prog prog, String text, Anchor anchor, MatchKind kind, int nsubmatch) {
    return search(prog, text, 0, text.length(), text.length(), anchor, kind, nsubmatch);
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
  static SearchResult search(
      Prog prog, String text, int startPos, Anchor anchor, MatchKind kind, int nsubmatch) {
    return search(prog, text, startPos, text.length(), text.length(), anchor, kind, nsubmatch);
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
  static SearchResult search(
      Prog prog,
      String text,
      int startPos,
      int searchLimit,
      Anchor anchor,
      MatchKind kind,
      int nsubmatch) {
    return search(prog, text, startPos, searchLimit, text.length(), anchor, kind, nsubmatch);
  }

  static SearchResult search(
      Prog prog,
      String text,
      int startPos,
      int searchLimit,
      int endPos,
      Anchor anchor,
      MatchKind kind,
      int nsubmatch) {
    return search(prog, text, startPos, searchLimit, endPos, 0, anchor, kind, nsubmatch);
  }

  static SearchResult search(
      Prog prog,
      String text,
      int startPos,
      int searchLimit,
      int endPos,
      int regionStart,
      Anchor anchor,
      MatchKind kind,
      int nsubmatch) {
    return search(
        prog, text, startPos, searchLimit, endPos, regionStart, anchor, kind, nsubmatch, null);
  }

  static SearchResult search(
      Prog prog,
      String text,
      int startPos,
      int searchLimit,
      int endPos,
      int regionStart,
      Anchor anchor,
      MatchKind kind,
      int nsubmatch,
      GraphemeContext graphemeContext) {
    return search(
        prog,
        text,
        startPos,
        searchLimit,
        endPos,
        endPos,
        regionStart,
        regionStart,
        endPos,
        endPos,
        anchor,
        kind,
        nsubmatch,
        graphemeContext);
  }

  static SearchResult search(
      Prog prog,
      String text,
      int startPos,
      int searchLimit,
      int endPos,
      int consumeRegionStart,
      int boundaryRegionStart,
      int boundaryEndPos,
      Anchor anchor,
      MatchKind kind,
      int nsubmatch,
      GraphemeContext graphemeContext) {
    return search(
        prog,
        text,
        startPos,
        searchLimit,
        endPos,
        endPos,
        consumeRegionStart,
        boundaryRegionStart,
        boundaryEndPos,
        endPos,
        anchor,
        kind,
        nsubmatch,
        graphemeContext);
  }

  static SearchResult search(
      Prog prog,
      String text,
      int startPos,
      int searchLimit,
      int endPos,
      int graphemeConsumeEndPos,
      int consumeRegionStart,
      int boundaryRegionStart,
      int boundaryEndPos,
      int anchorEndPos,
      Anchor anchor,
      MatchKind kind,
      int nsubmatch,
      GraphemeContext graphemeContext) {
    if (prog.start() == 0) {
      return new SearchResult(null);
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

    GraphemeContext context =
        graphemeContext != null
            ? graphemeContext
            : GraphemeContext.create(text, prog.hasGraphemeClusterBoundary());
    Nfa nfa =
        new Nfa(
            prog,
            context,
            ncapture,
            longestMode,
            endmatch,
            endPos,
            anchorEndPos,
            graphemeConsumeEndPos,
            consumeRegionStart,
            boundaryRegionStart,
            boundaryEndPos);
    if (prog.hasGraphemeClusterBoundary()) {
      nfa.doSearchEveryCharPosition(text, startPos, searchLimit, anchored);
    } else {
      nfa.doSearch(text, startPos, searchLimit, anchored);
    }

    if (!nfa.matched) {
      return new SearchResult(null);
    }
    if (kind == MatchKind.FULL_MATCH && nfa.bestMatch[1] != endPos) {
      return new SearchResult(null);
    }

    int[] result = new int[2 * nsubmatch];
    System.arraycopy(nfa.bestMatch, 0, result, 0, Math.min(result.length, nfa.bestMatch.length));
    // Fill any remaining slots with -1.
    for (int i = nfa.bestMatch.length; i < result.length; i++) {
      result[i] = -1;
    }
    return new SearchResult(result);
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
    QueueState runq = new QueueState();
    QueueState nextq = new QueueState();

    int pos = startPos;
    while (true) {
      int cp = (pos < endPos) ? text.codePointAt(pos) : -1;
      int nextPos = (pos < endPos) ? pos + Character.charCount(cp) : endPos + 1;

      // Start a new thread if there have not been any matches
      // (no point starting new threads to the right of an existing match).
      // Also don't start threads past searchLimit — the DFA has already determined
      // there's no match starting beyond that position.
      if (!matched && pos <= searchLimit && (!anchored || pos == startPos)) {
        int[] cap = new int[threadArraySize];
        Arrays.fill(cap, -1);
        cap[0] = pos;
        // Always use prog.start() (anchored start). Unanchored matching is achieved
        // by starting a new thread at each position. The startUnanchored() entry point
        // (which includes a .*? prefix) is only for the DFA engine.
        addToThreadq(runq.threads, runq.visited, prog.start(), text, pos, cap, false);
      }

      // If all threads have died, stop if anchored or we already have a match.
      // For unanchored searches without a match, keep trying new positions.
      if (runq.isEmpty()) {
        if (anchored || matched) {
          break;
        }
        // In unanchored mode with no match yet, advance to the next position and try again.
        if (pos >= endPos) {
          break;
        }
        runq.clear();
        pos = nextPos;
        continue;
      }

      boolean done = stepCodePoint(runq, nextq, cp, text, pos, nextPos);

      QueueState tmp = runq;
      runq = nextq;
      nextq = tmp;
      nextq.clear();

      if (done) {
        break;
      }

      if (pos >= endPos) {
        break;
      }

      pos = nextPos;
    }
  }

  /**
   * Unanchored search variant for grapheme programs.
   *
   * <p>Active threads still consume Unicode code points, but new candidate matches are started at
   * every Java character offset. That matches {@link java.util.regex.Matcher}'s character-index
   * search contract for grapheme constructs without changing the code-point stepping used by
   * ordinary SafeRE programs.
   */
  private void doSearchEveryCharPosition(
      String text, int startPos, int searchLimit, boolean anchored) {
    int start = Math.max(0, startPos);
    QueueState runq = new QueueState();
    Map<Integer, QueueState> delayed = new HashMap<>();

    int engineEndPos = Math.max(endPos, graphemeConsumeEndPos);
    for (int pos = start; pos < engineEndPos + 2; pos++) {
      mergeDelayedQueue(delayed, pos, runq);
      if (!matched && pos <= searchLimit && (!anchored || pos == startPos)) {
        int[] cap = new int[threadArraySize];
        Arrays.fill(cap, -1);
        cap[0] = pos;
        addToThreadq(runq.threads, runq.visited, prog.start(), text, pos, cap, false);
      }

      if (!runq.isEmpty()) {
        int cp = inputCodePointAt(text, pos);
        int nextPos = inputNextPos(text, pos);
        step(runq, delayed, cp, text, pos, nextPos);
      } else {
        runq.clear();
      }
      if (matched && runq.isEmpty() && delayed.isEmpty()) {
        break;
      }
    }
  }

  private void mergeDelayedQueue(
      Map<Integer, QueueState> delayed, int pos, QueueState destination) {
    QueueState source = delayed.remove(pos);
    if (source == null) {
      return;
    }
    destination.threads.addAll(source.threads);
    destination.visited.addAll(source.visited);
  }

  private QueueState delayedQueueAt(Map<Integer, QueueState> delayed, int pos) {
    int engineEndPos = Math.max(endPos, graphemeConsumeEndPos);
    if (pos < 0 || pos > engineEndPos + 1) {
      return null;
    }
    return delayed.computeIfAbsent(pos, unused -> new QueueState());
  }

  private long visitKey(Inst ip, int id, String text, int pos, int graphemeStart) {
    long instructionKey = ((long) id) << VISIT_KEY_VARIANT_BITS;
    if (ip.op != InstOp.GRAPHEME_CLUSTER) {
      return instructionKey;
    }
    return instructionKey | graphemeVisitVariant(text, pos, graphemeStart);
  }

  private int graphemeVisitVariant(String text, int pos, int graphemeStart) {
    int start = Math.max(consumeRegionStart, graphemeStart);
    if (pos < 0 || pos >= graphemeConsumeEndPos || pos >= text.length()) {
      return 0;
    }

    int variant = 0;
    char ch = text.charAt(pos);
    if (Character.isLowSurrogate(ch)
        && hasHighSurrogateBeforeLowSurrogateInRegion(text, pos, start)) {
      variant |= GRAPHEME_LOW_SURROGATE_PAIR_VISIBLE;
    }
    if (graphemeContext.hasExtendedPictographicBefore(pos, start)) {
      variant |= GRAPHEME_EXTENDED_PICTOGRAPHIC_VISIBLE;
    }
    int cp = graphemeCodePointAt(text, pos);
    int scalarEnd = nextRegionLocalScalarEnd(text, pos, graphemeConsumeEndPos);
    int nextCp = graphemeCodePointAt(text, scalarEnd);
    // Candidate starts inside a pending Indic conjunct sequence are not equivalent to starts
    // before that sequence; future linker+consonant boundaries can diverge.
    if (graphemeContext.hasIndicConjunctSequenceBefore(pos, start)) {
      variant |= GRAPHEME_INDIC_CONJUNCT_SEQUENCE_VISIBLE;
    }
    if ((isIndicConjunctConsonant(cp) && graphemeContext.hasIndicConjunctLinkerBefore(pos, start))
        || (isIndicConjunctConsonant(nextCp)
            && graphemeContext.hasIndicConjunctLinkerBefore(scalarEnd, start))) {
      variant |= GRAPHEME_INDIC_CONJUNCT_LINKER_VISIBLE;
    }
    if (isRegionalIndicator(cp)) {
      if (!graphemeContext.hasEvenRegionalIndicatorsBefore(scalarEnd, start)) {
        variant |= GRAPHEME_REGIONAL_INDICATOR_ODD;
      }
    }
    return variant;
  }

  private int inputCodePointAt(String text, int pos) {
    if (pos < 0 || pos >= endPos || pos >= text.length()) {
      return -1;
    }
    if (!prog.hasGraphemeClusterBoundary()) {
      return text.codePointAt(pos);
    }
    char ch = text.charAt(pos);
    if (Character.isHighSurrogate(ch)
        && pos + 1 < endPos
        && pos + 1 < text.length()
        && Character.isLowSurrogate(text.charAt(pos + 1))) {
      return Character.toCodePoint(ch, text.charAt(pos + 1));
    }
    if (Character.isHighSurrogate(ch)
        && pos + 1 < text.length()
        && Character.isLowSurrogate(text.charAt(pos + 1))) {
      // The scalar is only partially inside the region, so ordinary atoms cannot consume it.
      return -1;
    }
    return ch;
  }

  private int inputNextPos(String text, int pos) {
    if (pos < 0 || pos >= endPos || pos >= text.length()) {
      return endPos + 1;
    }
    if (!prog.hasGraphemeClusterBoundary()) {
      return pos + Character.charCount(text.codePointAt(pos));
    }
    int cp = inputCodePointAt(text, pos);
    if (cp < 0) {
      return endPos + 1;
    }
    if (Character.isSupplementaryCodePoint(cp)) {
      return pos + 2;
    }
    return pos + 1;
  }

  private int graphemeCodePointAt(String text, int pos) {
    if (pos < 0 || pos >= graphemeConsumeEndPos || pos >= text.length()) {
      return -1;
    }
    char ch = text.charAt(pos);
    if (Character.isHighSurrogate(ch)
        && pos + 1 < graphemeConsumeEndPos
        && pos + 1 < text.length()
        && Character.isLowSurrogate(text.charAt(pos + 1))) {
      return Character.toCodePoint(ch, text.charAt(pos + 1));
    }
    return ch;
  }

  private int graphemeNextPos(String text, int pos) {
    if (pos < 0 || pos >= graphemeConsumeEndPos || pos >= text.length()) {
      return graphemeConsumeEndPos + 1;
    }
    return nextRegionLocalScalarEnd(text, pos, graphemeConsumeEndPos);
  }

  private static int nextRegionLocalScalarEnd(String text, int pos, int endPos) {
    if (pos < 0 || pos >= endPos || pos >= text.length()) {
      return endPos + 1;
    }
    char ch = text.charAt(pos);
    if (Character.isHighSurrogate(ch)
        && pos + 1 < endPos
        && pos + 1 < text.length()
        && Character.isLowSurrogate(text.charAt(pos + 1))) {
      return pos + 2;
    }
    return pos + 1;
  }

  /**
   * Follows all empty transitions from {@code id0} and enqueues consuming/accepting instructions
   * (CHAR_RANGE and MATCH) into the thread queue.
   *
   * <p>Uses an explicit stack. The visit set {@code visited} prevents re-processing of instructions
   * already in the queue.
   *
   * @param q the thread queue to add to
   * @param visited set of instruction keys already visited/enqueued
   * @param id0 starting instruction ID
   * @param text the input text
   * @param pos the current position in the text
   * @param t0 the current capture array (shared — will be cloned before mutation)
   */
  private void addToThreadq(
      List<NfaThread> q,
      Set<Long> visited,
      int id0,
      String text,
      int pos,
      int[] t0,
      boolean consumedInput0) {
    if (id0 == 0) {
      return;
    }

    // Explicit stack. Each entry is (instId, captureArray).
    // We push entries and process them LIFO. The capture array may be shared
    // and is cloned when a CAPTURE instruction modifies it.
    List<int[]> stack = new ArrayList<>();
    stack.add(new int[] {id0, -1}); // -1 = use current t0

    // Parallel list of capture arrays for stack entries that need a specific capture.
    // Index corresponds to stack index. null means "use current t0".
    List<int[]> captureStack = new ArrayList<>();
    captureStack.add(null);
    List<Boolean> consumedInputStack = new ArrayList<>();
    consumedInputStack.add(consumedInput0);

    while (!stack.isEmpty()) {
      int last = stack.size() - 1;
      int[] entry = stack.remove(last);
      int[] entryCap = captureStack.remove(last);
      boolean consumedInput = consumedInputStack.remove(last);

      int id = entry[0];

      if (entryCap != null) {
        t0 = entryCap;
      }

      if (id == 0) {
        continue;
      }
      // PROGRESS_CHECK is excluded from the visited set: it manages its own re-entry
      // via registers. Within one addToThreadq call, it is visited at most twice (once
      // to save the position, once to detect zero-width and redirect to exit).
      //
      // ALT and CAPTURE are also excluded because a nullable quantified body can revisit the
      // same alternation or capture instruction at the same input position with different capture
      // registers. The later zero-width iteration is JDK-visible and must not be discarded.
      Inst ip = prog.inst(id);
      if (ip.op != InstOp.PROGRESS_CHECK && ip.op != InstOp.ALT && ip.op != InstOp.CAPTURE) {
        long visitKey = visitKey(ip, id, text, pos, pos);
        if (!visited.add(visitKey)) {
          continue;
        }
      }
      switch (ip.op) {
        case FAIL -> {}

        case ALT -> {
          // Push out1 first (lower priority), then out (higher priority).
          stack.add(new int[] {ip.out1, -1});
          captureStack.add(t0);
          consumedInputStack.add(consumedInput);
          stack.add(new int[] {ip.out, -1});
          captureStack.add(t0);
          consumedInputStack.add(consumedInput);
        }

        case ALT_MATCH -> {
          // Enqueue this state and also explore the next alt branch.
          q.add(new NfaThread(id, t0, consumedInput, -1));
          // Explore the next instruction after this one (the other alt branch).
          stack.add(new int[] {ip.out, -1});
          captureStack.add(t0);
          consumedInputStack.add(consumedInput);
          stack.add(new int[] {ip.out1, -1});
          captureStack.add(t0);
          consumedInputStack.add(consumedInput);
        }

        case NOP -> {
          stack.add(new int[] {ip.out, -1});
          captureStack.add(null);
          consumedInputStack.add(consumedInput);
        }

        case CAPTURE -> {
          if (ip.arg < ncapture) {
            // Clone the capture and record the current position.
            int[] newCap = t0.clone();
            newCap[ip.arg] = pos;
            stack.add(new int[] {ip.out, -1});
            captureStack.add(newCap);
            consumedInputStack.add(consumedInput);
          } else {
            // Capture register not tracked; just follow the transition.
            stack.add(new int[] {ip.out, -1});
            captureStack.add(null);
            consumedInputStack.add(consumedInput);
          }
        }

        case EMPTY_WIDTH -> {
          int effectiveBoundaryEndPos =
              consumedInput && graphemeConsumeEndPos > boundaryEndPos
                  ? graphemeConsumeEndPos
                  : boundaryEndPos;
          int flags =
              emptyFlags(
                  text,
                  pos,
                  prog.unixLines(),
                  prog.hasGraphemeClusterBoundary(),
                  graphemeContext,
                  t0[0],
                  boundaryRegionStart,
                  consumedInput,
                  anchorEndPos,
                  effectiveBoundaryEndPos);
          if ((ip.arg & ~flags) == 0) {
            stack.add(new int[] {ip.out, -1});
            captureStack.add(null);
            consumedInputStack.add(consumedInput);
          }
        }

        case PROGRESS_CHECK -> {
          int reg = ip.arg;
          int regIdx = ncapture + reg;
          int saved = t0[regIdx];
          if (saved == -1) {
            // First visit: must enter body at least once (plus semantics).
            int[] newCap = t0.clone();
            newCap[regIdx] = pos;
            stack.add(new int[] {ip.out, -1});
            captureStack.add(newCap);
            consumedInputStack.add(consumedInput);
          } else if (saved == pos) {
            // Zero-width body match: only exit.
            stack.add(new int[] {ip.out1, -1});
            captureStack.add(t0);
            consumedInputStack.add(consumedInput);
          } else {
            // Progress: push both paths like ALT, respecting greediness.
            int[] newCap = t0.clone();
            newCap[regIdx] = pos;
            boolean nonGreedy = ip.foldCase;
            if (nonGreedy) {
              // Non-greedy: prefer exit (push body first = lower pri, exit second = higher pri).
              stack.add(new int[] {ip.out, -1});
              captureStack.add(newCap);
              consumedInputStack.add(consumedInput);
              stack.add(new int[] {ip.out1, -1});
              captureStack.add(newCap);
              consumedInputStack.add(consumedInput);
            } else {
              // Greedy: prefer body (push exit first = lower pri, body second = higher pri).
              stack.add(new int[] {ip.out1, -1});
              captureStack.add(newCap);
              consumedInputStack.add(consumedInput);
              stack.add(new int[] {ip.out, -1});
              captureStack.add(newCap);
              consumedInputStack.add(consumedInput);
            }
          }
        }

        case CHAR_RANGE, CHAR_CLASS, GRAPHEME_CLUSTER, MATCH ->
            // These are "real" states. Capture arrays are immutable from this point
            // until a later CAPTURE or PROGRESS_CHECK transition clones them.
            q.add(
                new NfaThread(id, t0, consumedInput, ip.op == InstOp.GRAPHEME_CLUSTER ? pos : -1));

        default -> {}
      }
    }
  }

  /**
   * Processes all threads in {@code rq} for the current input character. Threads at CHAR_RANGE
   * instructions that match the character are advanced to the delayed queues. Threads at MATCH
   * instructions record the match.
   *
   * @return true if the search should stop (first-match found and remaining threads cut off)
   */
  private boolean step(
      QueueState rq,
      Map<Integer, QueueState> delayed,
      int cp,
      String text,
      int matchPos,
      int nextPos) {
    for (int threadIndex = 0; threadIndex < rq.threads.size(); threadIndex++) {
      NfaThread t = rq.threads.get(threadIndex);
      int id = t.id();
      int[] capture = t.capture();

      if (longest
          && matched
          && bestMatch[0] != -1
          && capture[0] != -1
          && bestMatch[0] < capture[0]) {
        continue;
      }

      Inst ip = prog.inst(id);
      switch (ip.op) {
        case CHAR_RANGE -> {
          if (cp >= 0 && ip.matchesChar(cp)) {
            QueueState destination = delayedQueueAt(delayed, nextPos);
            if (destination != null) {
              addToThreadq(
                  destination.threads, destination.visited, ip.out, text, nextPos, capture, true);
            }
          }
        }

        case CHAR_CLASS -> {
          if (cp >= 0 && ip.matchesCharClass(cp)) {
            QueueState destination = delayedQueueAt(delayed, nextPos);
            if (destination != null) {
              addToThreadq(
                  destination.threads, destination.visited, ip.out, text, nextPos, capture, true);
            }
          }
        }

        case GRAPHEME_CLUSTER -> {
          if (matchPos < endPos) {
            int graphemeStart =
                Math.max(consumeRegionStart, t.graphemeStart() >= 0 ? t.graphemeStart() : matchPos);
            int scalarEnd = graphemeNextPos(text, matchPos);
            QueueState destination = delayedQueueAt(delayed, scalarEnd);
            if (destination != null) {
              if (scalarEnd == graphemeConsumeEndPos
                  || isGraphemeClusterBoundary(text, scalarEnd, graphemeStart, graphemeContext)) {
                addToThreadq(
                    destination.threads,
                    destination.visited,
                    ip.out,
                    text,
                    scalarEnd,
                    capture,
                    true);
              } else if (destination.visited.add(
                  visitKey(ip, id, text, scalarEnd, graphemeStart))) {
                destination.threads.add(new NfaThread(id, capture, true, graphemeStart));
              }
            }
          }
        }

        case MATCH -> {
          boolean skip = endmatch && !matchesEndPosition(text, matchPos);
          if (!skip) {
            if (longest) {
              if (!matched
                  || capture[0] < bestMatch[0]
                  || (capture[0] == bestMatch[0] && matchPos > bestMatch[1])) {
                System.arraycopy(capture, 0, bestMatch, 0, ncapture);
                bestMatch[1] = matchPos;
                matched = true;
              }
            } else {
              // First match mode: this is the best match (leftmost, due to priority).
              // Cut off threads that can only find worse matches (remaining runq),
              // but do not stop the main loop: threads already queued for later positions continue.
              System.arraycopy(capture, 0, bestMatch, 0, ncapture);
              bestMatch[1] = matchPos;
              matched = true;
              // Clear remaining runq entries; they can only find worse matches.
              rq.clear();
              return false;
            }
          }
        }

        case ALT_MATCH -> {
          // Optimization: if this is the first thread and we want the match, take it.
          if (longest || threadIndex == 0) {
            System.arraycopy(capture, 0, bestMatch, 0, ncapture);
            matched = true;
          }
        }

        default -> {}
      }
    }
    rq.clear();
    return false;
  }

  private boolean stepCodePoint(
      QueueState rq, QueueState nq, int cp, String text, int matchPos, int nextPos) {
    nq.clear();

    for (int threadIndex = 0; threadIndex < rq.threads.size(); threadIndex++) {
      NfaThread t = rq.threads.get(threadIndex);
      int id = t.id();
      int[] capture = t.capture();

      if (longest
          && matched
          && bestMatch[0] != -1
          && capture[0] != -1
          && bestMatch[0] < capture[0]) {
        continue;
      }

      Inst ip = prog.inst(id);
      switch (ip.op) {
        case CHAR_RANGE -> {
          if (cp >= 0 && ip.matchesChar(cp)) {
            addToThreadq(nq.threads, nq.visited, ip.out, text, nextPos, capture, true);
          }
        }

        case CHAR_CLASS -> {
          if (cp >= 0 && ip.matchesCharClass(cp)) {
            addToThreadq(nq.threads, nq.visited, ip.out, text, nextPos, capture, true);
          }
        }

        case MATCH -> {
          boolean skip = endmatch && !matchesEndPosition(text, matchPos);
          if (!skip) {
            if (longest) {
              if (!matched
                  || capture[0] < bestMatch[0]
                  || (capture[0] == bestMatch[0] && matchPos > bestMatch[1])) {
                System.arraycopy(capture, 0, bestMatch, 0, ncapture);
                bestMatch[1] = matchPos;
                matched = true;
              }
            } else {
              // First match mode: this is the best match (leftmost, due to priority).
              // Cut off threads that can only find worse matches (remaining runq),
              // but do not stop the main loop: threads already in nextq continue.
              System.arraycopy(capture, 0, bestMatch, 0, ncapture);
              bestMatch[1] = matchPos;
              matched = true;
              rq.clear();
              return false;
            }
          }
        }

        case ALT_MATCH -> {
          // Optimization: if this is the first thread and we want the match, take it.
          if (longest || threadIndex == 0) {
            System.arraycopy(capture, 0, bestMatch, 0, ncapture);
            matched = true;
          }
        }

        default -> {}
      }
    }
    rq.clear();
    return false;
  }

  private boolean matchesEndPosition(String text, int matchPos) {
    if (matchPos == anchorEndPos) {
      return true;
    }
    if (matchPos == graphemeConsumeEndPos
        && graphemeConsumeEndPos != endPos
        && anchorEndPos == endPos) {
      return true;
    }
    return prog.dollarAnchorEnd()
        && isAtTrailingLineTerminator(text, matchPos, prog.unixLines(), anchorEndPos);
  }

  // ---------------------------------------------------------------------------
  // Empty-width flag computation
  // ---------------------------------------------------------------------------

  /**
   * Returns true if the code point is a JDK line terminator character: {@code '\n'}, {@code '\r'},
   * {@code '\u0085'} (NEXT LINE), {@code '\u2028'} (LINE SEPARATOR), or {@code '\u2029'} (PARAGRAPH
   * SEPARATOR).
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
    return isAtTrailingLineTerminator(text, pos, unixLines, text.length());
  }

  static int trailingLineTerminatorStart(String text, boolean unixLines) {
    return trailingLineTerminatorStart(text, unixLines, text.length());
  }

  private static boolean isAtTrailingLineTerminator(
      String text, int pos, boolean unixLines, int logicalEndPos) {
    return pos == trailingLineTerminatorStart(text, unixLines, logicalEndPos);
  }

  private static int trailingLineTerminatorStart(
      String text, boolean unixLines, int logicalEndPos) {
    int len = logicalEndPos;
    if (len <= 0 || len > text.length()) {
      return -1;
    }
    char ch = text.charAt(len - 1);
    if (unixLines) {
      return ch == '\n' ? len - 1 : -1;
    }
    if (ch == '\n') {
      return len >= 2 && text.charAt(len - 2) == '\r' ? len - 2 : len - 1;
    }
    if (ch == '\r' || ch == '\u0085' || ch == '\u2028' || ch == '\u2029') {
      return len - 1;
    }
    return -1;
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
    return emptyFlags(text, pos, unixLines, true);
  }

  static int emptyFlags(
      String text, int pos, boolean unixLines, boolean includeGraphemeClusterBoundary) {
    return emptyFlags(
        text, pos, unixLines, includeGraphemeClusterBoundary, (GraphemeContext) null, -1, 0, false);
  }

  static int emptyFlags(
      String text,
      int pos,
      boolean unixLines,
      boolean includeGraphemeClusterBoundary,
      GraphemeContext graphemeContext) {
    return emptyFlags(
        text, pos, unixLines, includeGraphemeClusterBoundary, graphemeContext, -1, 0, false);
  }

  static int emptyFlags(
      String text,
      int pos,
      boolean unixLines,
      boolean includeGraphemeClusterBoundary,
      int matchStart) {
    return emptyFlags(
        text,
        pos,
        unixLines,
        includeGraphemeClusterBoundary,
        (GraphemeContext) null,
        matchStart,
        0,
        false);
  }

  static int emptyFlags(
      String text,
      int pos,
      boolean unixLines,
      boolean includeGraphemeClusterBoundary,
      int matchStart,
      int regionStart) {
    return emptyFlags(
        text, pos, unixLines, includeGraphemeClusterBoundary, null, matchStart, regionStart, false);
  }

  private static int emptyFlags(
      String text,
      int pos,
      boolean unixLines,
      boolean includeGraphemeClusterBoundary,
      GraphemeContext graphemeContext,
      int matchStart,
      int regionStart,
      boolean consumedInput) {
    return emptyFlags(
        text,
        pos,
        unixLines,
        includeGraphemeClusterBoundary,
        graphemeContext,
        matchStart,
        regionStart,
        consumedInput,
        text.length(),
        text.length());
  }

  private static int emptyFlags(
      String text,
      int pos,
      boolean unixLines,
      boolean includeGraphemeClusterBoundary,
      GraphemeContext graphemeContext,
      int matchStart,
      int regionStart,
      boolean consumedInput,
      int anchorEndPos,
      int boundaryEndPos) {
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
    if (pos == anchorEndPos) {
      flags |= EmptyOp.END_TEXT | EmptyOp.END_LINE | EmptyOp.DOLLAR_END;
    } else if (pos < text.length()) {
      char ch = text.charAt(pos);
      if (unixLines) {
        if (ch == '\n') {
          flags |= EmptyOp.END_LINE;
          if (pos + 1 == anchorEndPos) {
            flags |= EmptyOp.DOLLAR_END;
          }
        }
      } else if (isLineTerminator(ch)) {
        // Don't set END_LINE at the \n of an atomic \r\n pair — JDK treats \r\n as a single
        // line terminator. END_LINE fires before the \r (the start of the pair), not between
        // \r and \n.
        boolean isAtomicLF = (ch == '\n' && pos > 0 && text.charAt(pos - 1) == '\r');
        if (!isAtomicLF) {
          flags |= EmptyOp.END_LINE;
          if (isAtTrailingLineTerminator(text, pos, false, anchorEndPos)) {
            flags |= EmptyOp.DOLLAR_END;
          }
        }
      }
    }

    // \b and \B
    boolean prevWord = pos > regionStart && isWordChar(text.codePointBefore(pos));
    boolean nextWord = pos < boundaryEndPos && isWordChar(text.codePointAt(pos));
    if (prevWord != nextWord) {
      flags |= EmptyOp.WORD_BOUNDARY;
    } else {
      flags |= EmptyOp.NON_WORD_BOUNDARY;
    }

    // Unicode \b and \B
    boolean prevUnicodeWord = pos > regionStart && isUnicodeWordChar(text.codePointBefore(pos));
    boolean nextUnicodeWord = pos < boundaryEndPos && isUnicodeWordChar(text.codePointAt(pos));
    if (prevUnicodeWord != nextUnicodeWord) {
      flags |= EmptyOp.UNICODE_WORD_BOUNDARY;
    } else {
      flags |= EmptyOp.UNICODE_NON_WORD_BOUNDARY;
    }

    if (includeGraphemeClusterBoundary) {
      if (isGraphemeBoundaryContextEdge(pos, regionStart, boundaryEndPos)) {
        flags |= EmptyOp.GRAPHEME_CLUSTER_BOUNDARY | EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
      } else if (isRegionStartSplitSurrogateBoundary(text, pos, regionStart)
          || isRegionEndSplitSurrogateBoundary(text, pos, boundaryEndPos)) {
        flags |= EmptyOp.GRAPHEME_CLUSTER_BOUNDARY | EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
      } else if (isAfterRegionStartSplitLowSurrogateBoundary(text, pos, regionStart)) {
        flags |= EmptyOp.GRAPHEME_CLUSTER_BOUNDARY;
        if (!suppressesConsumedSplitLowSurrogateExplicitBoundary(
            text, pos, boundaryEndPos, consumedInput)) {
          flags |= EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
        }
      } else if (consumedInput
          && isStandaloneZwjBeforePictographicBoundary(text, pos, regionStart, graphemeContext)) {
        flags |= EmptyOp.GRAPHEME_CLUSTER_BOUNDARY;
        if (!isAfterPairedExtendedPictographicZwj(text, pos, regionStart, graphemeContext)) {
          flags |= EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
        }
      } else if (isGraphemeClusterBoundary(text, pos, regionStart, graphemeContext)) {
        flags |= EmptyOp.GRAPHEME_CLUSTER_BOUNDARY;
        if (!isAfterPairedExtendedPictographicZwj(text, pos, regionStart, graphemeContext)
            || !isStandaloneZwjBeforePictographicBoundary(
                text, pos, regionStart, graphemeContext)) {
          flags |= EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
        }
      } else if (isLowHighSurrogateBoundary(text, pos)
          && matchStart > 0
          && pos > matchStart
          && !hasHighSurrogateBeforeLowSurrogateInRegion(text, pos - 1, regionStart)) {
        flags |= EmptyOp.GRAPHEME_CLUSTER_BOUNDARY | EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
      } else if (startsAtLowSurrogate(text, matchStart)
          && !hasHighSurrogateBeforeLowSurrogateInRegion(text, matchStart, regionStart)
          && pos == matchStart + 1) {
        flags |= EmptyOp.GRAPHEME_CLUSTER_BOUNDARY;
        if (!hasGraphemeExtendAt(text, pos)
            && !suppressesRegionStartSplitExplicitBoundary(
                text, regionStart, matchStart, consumedInput)) {
          flags |= EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
        }
      } else if (startsAtStandaloneZwj(text, matchStart, regionStart)
          && pos == matchStart + 1
          && !hasGraphemeExtendAt(text, pos)) {
        flags |= EmptyOp.GRAPHEME_CLUSTER_BOUNDARY;
        if (!consumedInput
            && !suppressesRegionStartSplitExplicitBoundary(
                text, regionStart, matchStart, consumedInput)) {
          flags |= EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
        }
      } else if (!consumedInput
          && (isLowSurrogateBeforeZwjBoundary(text, pos, matchStart, regionStart)
              || isStandaloneZwjAfterLowSurrogateBoundary(text, pos, matchStart, regionStart))
          && !isAfterRegionStartSplitLowSurrogate(text, pos, regionStart)
          && !hasGraphemeExtendAt(text, pos)) {
        flags |= EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
      } else if (!consumedInput
          && isStandaloneZwjBeforePictographicBoundary(text, pos, regionStart, graphemeContext)) {
        flags |= EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
      } else if (isExplicitGraphemeClusterBoundary(text, pos, matchStart, regionStart)) {
        flags |= EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY;
      }
    }

    return flags;
  }

  private static boolean isGraphemeBoundaryContextEdge(
      int pos, int boundaryStart, int boundaryEnd) {
    return pos == boundaryStart || pos == boundaryEnd;
  }

  /**
   * Returns true if {@code pos} is a grapheme cluster boundary for SafeRE's supported approximation
   * of JDK {@code \X}.
   */
  static boolean isGraphemeClusterBoundary(String text, int pos) {
    return isGraphemeClusterBoundary(text, pos, 0, GraphemeContext.create(text, true));
  }

  static boolean isGraphemeClusterBoundary(String text, int pos, GraphemeContext graphemeContext) {
    return isGraphemeClusterBoundary(text, pos, 0, graphemeContext);
  }

  static boolean isGraphemeClusterBoundary(
      String text, int pos, int regionStart, GraphemeContext graphemeContext) {
    if (pos < 0 || pos > text.length()) {
      return false;
    }
    if (pos == 0 || pos == text.length()) {
      return true;
    }
    char prevChar = text.charAt(pos - 1);
    char nextChar = text.charAt(pos);
    if (Character.isHighSurrogate(prevChar) && Character.isLowSurrogate(nextChar)) {
      return false;
    }
    if (prevChar == '\r' && nextChar == '\n') {
      return false;
    }
    if (isGraphemeControl(prevChar) || isGraphemeControl(nextChar)) {
      return true;
    }
    if (Character.isHighSurrogate(prevChar) && !Character.isLowSurrogate(nextChar)) {
      return true;
    }
    if (Character.isLowSurrogate(prevChar)
        && !hasHighSurrogateBeforeLowSurrogateInRegion(text, pos - 1, regionStart)) {
      return true;
    }
    int prev = text.codePointBefore(pos);
    int next = text.codePointAt(pos);
    if (isGraphemePrepend(prev) && isUnpairedSurrogateAt(text, pos)) {
      return true;
    }
    if (isGraphemeExtend(next) || isGraphemePrepend(prev)) {
      return false;
    }
    if (isHangulGraphemeContinuation(prev, next)) {
      return false;
    }
    GraphemeContext context =
        graphemeContext != null ? graphemeContext : GraphemeContext.create(text, true);
    if (isIndicConjunctConsonant(next) && context.hasIndicConjunctLinkerBefore(pos, regionStart)) {
      return false;
    }
    if (prev == 0x200D
        && containsCodePoint(EXTENDED_PICTOGRAPHIC, next)
        && context.hasExtendedPictographicBefore(pos - 1, regionStart)) {
      return false;
    }
    if (isRegionalIndicator(prev) && isRegionalIndicator(next)) {
      return context.hasEvenRegionalIndicatorsBefore(pos, regionStart);
    }
    return true;
  }

  private static boolean isExplicitGraphemeClusterBoundary(
      String text, int pos, int matchStart, int regionStart) {
    if (matchStart <= 0 || pos <= matchStart || pos <= 1 || pos >= text.length()) {
      return false;
    }
    char prevChar = text.charAt(pos - 1);
    char nextChar = text.charAt(pos);
    if (Character.isLowSurrogate(prevChar) && Character.isHighSurrogate(nextChar)) {
      return !hasHighSurrogateBeforeLowSurrogateInRegion(text, pos - 1, regionStart);
    }
    if (prevChar == '\r' && nextChar == '\n') {
      return true;
    }
    if (isGraphemeExtend(nextChar)
        && Character.isLowSurrogate(prevChar)
        && hasHighSurrogateBeforeLowSurrogateInRegion(text, pos - 1, regionStart)) {
      return false;
    }
    int prev = text.codePointBefore(pos);
    int next = text.codePointAt(pos);
    return isGraphemeExtend(next) || isGraphemePrepend(prev);
  }

  private static boolean isLowHighSurrogateBoundary(String text, int pos) {
    return pos > 0
        && pos < text.length()
        && Character.isLowSurrogate(text.charAt(pos - 1))
        && Character.isHighSurrogate(text.charAt(pos));
  }

  private static boolean isRegionStartSplitSurrogateBoundary(
      String text, int pos, int regionStart) {
    return pos == regionStart
        && regionStart > 0
        && regionStart < text.length()
        && Character.isHighSurrogate(text.charAt(regionStart - 1))
        && Character.isLowSurrogate(text.charAt(regionStart));
  }

  private static boolean isAfterRegionStartSplitLowSurrogateBoundary(
      String text, int pos, int regionStart) {
    return pos == regionStart + 1
        && isRegionStartSplitSurrogateBoundary(text, regionStart, regionStart);
  }

  private static boolean isRegionEndSplitSurrogateBoundary(String text, int pos, int regionEnd) {
    return pos == regionEnd
        && regionEnd > 0
        && regionEnd < text.length()
        && Character.isHighSurrogate(text.charAt(regionEnd - 1))
        && Character.isLowSurrogate(text.charAt(regionEnd));
  }

  private static boolean suppressesConsumedSplitLowSurrogateExplicitBoundary(
      String text, int pos, int boundaryEndPos, boolean consumedInput) {
    if (!consumedInput || pos >= boundaryEndPos) {
      return false;
    }
    if (hasGraphemeExtendAt(text, pos)) {
      return true;
    }
    return isRegionalIndicator(regionLocalCodePointAt(text, pos, boundaryEndPos));
  }

  private static int regionLocalCodePointAt(String text, int pos, int endPos) {
    if (pos < 0 || pos >= endPos || pos >= text.length()) {
      return -1;
    }
    char ch = text.charAt(pos);
    if (Character.isHighSurrogate(ch)
        && pos + 1 < endPos
        && pos + 1 < text.length()
        && Character.isLowSurrogate(text.charAt(pos + 1))) {
      return Character.toCodePoint(ch, text.charAt(pos + 1));
    }
    return ch;
  }

  private static boolean isLowSurrogateBeforeZwjBoundary(
      String text, int pos, int matchStart, int regionStart) {
    return pos == matchStart
        && pos > 0
        && pos < text.length()
        && Character.isLowSurrogate(text.charAt(pos - 1))
        && text.charAt(pos) == 0x200D
        && !hasHighSurrogateBeforeLowSurrogateInRegion(text, pos - 1, regionStart);
  }

  private static boolean suppressesRegionStartSplitExplicitBoundary(
      String text, int regionStart, int matchStart, boolean consumedInput) {
    return consumedInput
        && matchStart == regionStart + 1
        && isRegionStartSplitSurrogateBoundary(text, regionStart, regionStart);
  }

  private static boolean isStandaloneZwjAfterLowSurrogateBoundary(
      String text, int pos, int matchStart, int regionStart) {
    return pos == matchStart
        && pos > 1
        && pos < text.length()
        && text.charAt(pos - 1) == 0x200D
        && Character.isLowSurrogate(text.charAt(pos - 2))
        && !hasHighSurrogateBeforeLowSurrogateInRegion(text, pos - 2, regionStart);
  }

  private static boolean isStandaloneZwjBeforePictographicBoundary(
      String text, int pos, int regionStart, GraphemeContext graphemeContext) {
    GraphemeContext context =
        graphemeContext != null ? graphemeContext : GraphemeContext.create(text, true);
    return pos > 0
        && pos < text.length()
        && text.charAt(pos - 1) == 0x200D
        && containsCodePoint(EXTENDED_PICTOGRAPHIC, text.codePointAt(pos))
        && !isAfterRegionStartSplitLowSurrogate(text, pos - 1, regionStart)
        && !context.hasExtendedPictographicBefore(pos - 1, regionStart);
  }

  private static boolean isAfterPairedExtendedPictographicZwj(
      String text, int pos, int regionStart, GraphemeContext graphemeContext) {
    GraphemeContext context =
        graphemeContext != null ? graphemeContext : GraphemeContext.create(text, true);
    int lowSurrogatePos = pos - 2;
    if (regionStart <= lowSurrogatePos && regionStart > 0) {
      return false;
    }
    return pos > 2
        && text.charAt(pos - 1) == 0x200D
        && Character.isLowSurrogate(text.charAt(lowSurrogatePos))
        && Character.isHighSurrogate(text.charAt(pos - 3))
        && context.hasExtendedPictographicBefore(pos - 1, regionStart);
  }

  private static boolean isAfterRegionStartSplitLowSurrogate(
      String text, int pos, int regionStart) {
    return pos == regionStart + 1
        && isRegionStartSplitSurrogateBoundary(text, regionStart, regionStart);
  }

  private static int immediatePrependStartBefore(String text, int pos) {
    if (pos <= 0) {
      return -1;
    }
    int previous = text.codePointBefore(pos);
    if (!isGraphemePrepend(previous)) {
      return -1;
    }
    return pos - Character.charCount(previous);
  }

  private static boolean hasHighSurrogateBeforeLowSurrogateInRegion(
      String text, int lowSurrogatePos, int regionStart) {
    return lowSurrogatePos > regionStart
        && lowSurrogatePos > 0
        && Character.isHighSurrogate(text.charAt(lowSurrogatePos - 1));
  }

  private static boolean startsAtLowSurrogate(String text, int matchStart) {
    return matchStart >= 0
        && matchStart < text.length()
        && Character.isLowSurrogate(text.charAt(matchStart));
  }

  private static boolean startsAtStandaloneZwj(String text, int matchStart, int regionStart) {
    return matchStart >= 0
        && matchStart < text.length()
        && text.charAt(matchStart) == 0x200D
        && (matchStart == 0
            || matchStart <= regionStart
            || !Character.isLowSurrogate(text.charAt(matchStart - 1))
            || !hasHighSurrogateBeforeLowSurrogateInRegion(text, matchStart - 1, regionStart));
  }

  private static boolean isGraphemeExtend(int c) {
    return isCombiningMark(c) || isEmojiModifier(c) || c == 0x200D;
  }

  private static boolean isUnpairedSurrogateAt(String text, int pos) {
    char c = text.charAt(pos);
    if (Character.isLowSurrogate(c)) {
      return true;
    }
    return Character.isHighSurrogate(c)
        && (pos + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(pos + 1)));
  }

  static boolean hasGraphemeExtendAt(String text, int pos) {
    return pos >= 0 && pos < text.length() && isGraphemeExtend(text.codePointAt(pos));
  }

  static boolean isExtendedPictographicAt(String text, int pos) {
    return pos >= 0
        && pos < text.length()
        && containsCodePoint(EXTENDED_PICTOGRAPHIC, text.codePointAt(pos));
  }

  private static boolean isGraphemeControl(int c) {
    int type = Character.getType(c);
    return type == Character.CONTROL
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR;
  }

  private static boolean isCombiningMark(int c) {
    int type = Character.getType(c);
    return type == Character.NON_SPACING_MARK
        || type == Character.ENCLOSING_MARK
        || type == Character.COMBINING_SPACING_MARK;
  }

  private static boolean isEmojiModifier(int c) {
    return 0x1F3FB <= c && c <= 0x1F3FF;
  }

  private static boolean isGraphemePrepend(int c) {
    return (0x0600 <= c && c <= 0x0605)
        || c == 0x06DD
        || c == 0x070F
        || (0x0890 <= c && c <= 0x0891)
        || c == 0x08E2
        || c == 0x110BD
        || c == 0x110CD;
  }

  private static boolean isHangulGraphemeContinuation(int prev, int next) {
    return (isHangulL(prev)
            && (isHangulL(next) || isHangulV(next) || isHangulLv(next) || isHangulLvt(next)))
        || ((isHangulV(prev) || isHangulLv(prev)) && (isHangulV(next) || isHangulT(next)))
        || ((isHangulT(prev) || isHangulLvt(prev)) && isHangulT(next));
  }

  private static boolean isHangulL(int c) {
    return (0x1100 <= c && c <= 0x115F) || (0xA960 <= c && c <= 0xA97C);
  }

  private static boolean isHangulV(int c) {
    return (0x1160 <= c && c <= 0x11A7) || (0xD7B0 <= c && c <= 0xD7C6);
  }

  private static boolean isHangulT(int c) {
    return (0x11A8 <= c && c <= 0x11FF) || (0xD7CB <= c && c <= 0xD7FB);
  }

  private static boolean isHangulLv(int c) {
    return 0xAC00 <= c && c <= 0xD7A3 && (c - 0xAC00) % 28 == 0;
  }

  private static boolean isHangulLvt(int c) {
    return 0xAC00 <= c && c <= 0xD7A3 && (c - 0xAC00) % 28 != 0;
  }

  private static boolean isRegionalIndicator(int c) {
    return 0x1F1E6 <= c && c <= 0x1F1FF;
  }

  private static boolean isIndicConjunctConsonant(int c) {
    return (0x0915 <= c && c <= 0x0939)
        || (0x0958 <= c && c <= 0x095F)
        || (0x0978 <= c && c <= 0x097F)
        || (0x0995 <= c && c <= 0x09A8)
        || (0x09AA <= c && c <= 0x09B0)
        || c == 0x09B2
        || (0x09B6 <= c && c <= 0x09B9)
        || (0x09DC <= c && c <= 0x09DD)
        || c == 0x09DF
        || (0x09F0 <= c && c <= 0x09F1)
        || (0x0A95 <= c && c <= 0x0AA8)
        || (0x0AAA <= c && c <= 0x0AB0)
        || (0x0AB2 <= c && c <= 0x0AB3)
        || (0x0AB5 <= c && c <= 0x0AB9)
        || c == 0x0AF9
        || (0x0B15 <= c && c <= 0x0B28)
        || (0x0B2A <= c && c <= 0x0B30)
        || (0x0B32 <= c && c <= 0x0B33)
        || (0x0B35 <= c && c <= 0x0B39)
        || (0x0B5C <= c && c <= 0x0B5D)
        || c == 0x0B5F
        || c == 0x0B71
        || (0x0C15 <= c && c <= 0x0C28)
        || (0x0C2A <= c && c <= 0x0C39)
        || (0x0C58 <= c && c <= 0x0C5A)
        || (0x0D15 <= c && c <= 0x0D3A);
  }

  private static boolean isIndicConjunctLinker(int c) {
    return c == 0x094D || c == 0x09CD || c == 0x0ACD || c == 0x0B4D || c == 0x0C4D || c == 0x0D4D;
  }

  private static boolean isIndicConjunctExtend(int c) {
    return isGraphemeExtend(c) || (0xE0020 <= c && c <= 0xE007F);
  }

  private static boolean containsCodePoint(int[][] ranges, int c) {
    int lo = 0;
    int hi = ranges.length - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      int[] range = ranges[mid];
      if (c < range[0]) {
        hi = mid - 1;
      } else if (c > range[1]) {
        lo = mid + 1;
      } else {
        return true;
      }
    }
    return false;
  }

  /** Returns true if the code point is a word character ({@code [A-Za-z0-9_]}). */
  static boolean isWordChar(int c) {
    return ('A' <= c && c <= 'Z') || ('a' <= c && c <= 'z') || ('0' <= c && c <= '9') || c == '_';
  }

  /** Returns true if the code point is a Unicode word character (matching {@code \w} under UCC). */
  static boolean isUnicodeWordChar(int c) {
    return Character.isAlphabetic(c)
        || Character.getType(c) == Character.NON_SPACING_MARK
        || Character.getType(c) == Character.ENCLOSING_MARK
        || Character.getType(c) == Character.COMBINING_SPACING_MARK
        || Character.isDigit(c)
        || Character.getType(c) == Character.CONNECTOR_PUNCTUATION
        || c == 0x200C // ZWNJ
        || c == 0x200D; // ZWJ
  }

  private Nfa() {
    throw new AssertionError("non-instantiable");
  }
}
