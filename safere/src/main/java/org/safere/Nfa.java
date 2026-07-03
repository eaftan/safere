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

  // TODO(#98): Replace int[] with Guava ImmutableIntArray to get proper value semantics.
  private static final class NfaThread {
    int id;
    final int[] capture;
    int graphemeStart;

    NfaThread(int threadArraySize) {
      this.capture = new int[threadArraySize];
    }
  }

  private static final class QueueState {
    final NfaThread[] threads;
    int size = 0;

    final int[] visitedInst;
    int visitedGeneration = 1;
    final Set<Long> visitedGrapheme;
    final boolean hasGraphemeSemantics;

    QueueState(int progSize, boolean hasGraphemeSemantics) {
      this.threads = new NfaThread[progSize + 1];
      this.visitedInst = new int[progSize + 1];
      this.visitedGrapheme = hasGraphemeSemantics ? new HashSet<>() : null;
      this.hasGraphemeSemantics = hasGraphemeSemantics;
    }

    boolean isEmpty() {
      return size == 0;
    }

    void clear() {
      size = 0;
      if (hasGraphemeSemantics) {
        visitedGrapheme.clear();
      } else {
        visitedGeneration++;
        if (visitedGeneration == 0) {
          Arrays.fill(visitedInst, 0);
          visitedGeneration = 1;
        }
      }
    }
  }

  private static final class AddToThreadqStack {
    private int[] ids = new int[32];
    private boolean[] consumedInputs = new boolean[32];
    private int[] restoreIndexes = filledArray(32, -1);
    private int[] restoreValues = new int[32];
    private int size;

    int id;
    boolean consumedInput;
    int restoreIndex;
    int restoreValue;

    void clear() {
      size = 0;
    }

    boolean isEmpty() {
      return size == 0;
    }

    void pushInstruction(int id, boolean consumedInput) {
      ensureCapacity(size + 1);
      ids[size] = id;
      consumedInputs[size] = consumedInput;
      restoreIndexes[size] = -1;
      restoreValues[size] = -1;
      size++;
    }

    void pushRestore(int restoreIndex, int restoreValue) {
      ensureCapacity(size + 1);
      ids[size] = 0;
      consumedInputs[size] = false;
      restoreIndexes[size] = restoreIndex;
      restoreValues[size] = restoreValue;
      size++;
    }

    void pop() {
      size--;
      id = ids[size];
      consumedInput = consumedInputs[size];
      restoreIndex = restoreIndexes[size];
      restoreValue = restoreValues[size];
    }

    private void ensureCapacity(int minCapacity) {
      if (minCapacity <= ids.length) {
        return;
      }
      int newCapacity = ids.length * 2;
      while (newCapacity < minCapacity) {
        newCapacity *= 2;
      }
      ids = Arrays.copyOf(ids, newCapacity);
      consumedInputs = Arrays.copyOf(consumedInputs, newCapacity);
      int oldLength = restoreIndexes.length;
      restoreIndexes = Arrays.copyOf(restoreIndexes, newCapacity);
      Arrays.fill(restoreIndexes, oldLength, restoreIndexes.length, -1);
      restoreValues = Arrays.copyOf(restoreValues, newCapacity);
    }

    private static int[] filledArray(int length, int value) {
      int[] result = new int[length];
      Arrays.fill(result, value);
      return result;
    }
  }

  private Prog prog;
  private int ncapture;

  /** Total thread array size: ncapture slots for captures + numLoopRegs for progress checks. */
  private int threadArraySize;

  private boolean longest;
  private boolean endmatch;
  private EngineContext context;

  private boolean matched;
  private int[] bestMatch;
  private final AddToThreadqStack addToThreadqStack = new AddToThreadqStack();

  // NfaThread pool
  private NfaThread[] threadPool = new NfaThread[16];
  private int threadPoolSize = 0;

  // QueueState pool
  private final List<QueueState> queueStatePool = new ArrayList<>();

  private NfaThread allocThread(int id, int[] captureSource, int graphemeStart) {
    NfaThread t;
    if (threadPoolSize > 0) {
      threadPoolSize--;
      t = threadPool[threadPoolSize];
      threadPool[threadPoolSize] = null;
    } else {
      t = new NfaThread(threadArraySize);
    }
    t.id = id;
    if (captureSource != null) {
      System.arraycopy(captureSource, 0, t.capture, 0, threadArraySize);
    } else {
      Arrays.fill(t.capture, -1);
    }
    t.graphemeStart = graphemeStart;
    return t;
  }

  private void freeThread(NfaThread t) {
    if (threadPool.length <= threadPoolSize) {
      threadPool = Arrays.copyOf(threadPool, threadPool.length * 2);
    }
    threadPool[threadPoolSize] = t;
    threadPoolSize++;
  }

  private void freeQueue(QueueState q) {
    for (int i = 0; i < q.size; i++) {
      freeThread(q.threads[i]);
      q.threads[i] = null;
    }
    q.clear();
  }

  private QueueState allocQueueState() {
    if (!queueStatePool.isEmpty()) {
      return queueStatePool.remove(queueStatePool.size() - 1);
    }
    return new QueueState(prog.size(), prog.hasGraphemeSemantics());
  }

  private void freeQueueState(QueueState q) {
    freeQueue(q);
    queueStatePool.add(q);
  }

  private Nfa(Prog prog, EngineContext context, int ncapture, boolean longest, boolean endmatch) {
    this.prog = prog;
    this.ncapture = ncapture;
    this.threadArraySize = ncapture + prog.numLoopRegs();
    this.longest = longest;
    this.endmatch = endmatch;
    this.context = context;
    this.bestMatch = new int[ncapture];
    Arrays.fill(bestMatch, -1);
  }

  static Nfa getOrCreate(
      Nfa cached,
      Prog prog,
      EngineContext context,
      int ncapture,
      boolean longest,
      boolean endmatch) {
    if (cached != null && cached.canReuse(prog, ncapture)) {
      cached.reset(prog, context, ncapture, longest, endmatch);
      return cached;
    }
    return new Nfa(prog, context, ncapture, longest, endmatch);
  }

  private boolean canReuse(Prog prog, int ncapture) {
    int requiredThreadArraySize = ncapture + prog.numLoopRegs();
    return this.threadArraySize >= requiredThreadArraySize
        && this.bestMatch.length >= ncapture
        && this.prog.size() >= prog.size()
        && this.prog.hasGraphemeSemantics() == prog.hasGraphemeSemantics();
  }

  private void reset(
      Prog prog, EngineContext context, int ncapture, boolean longest, boolean endmatch) {
    this.prog = prog;
    this.ncapture = ncapture;
    this.threadArraySize = ncapture + prog.numLoopRegs();
    this.longest = longest;
    this.endmatch = endmatch;
    this.context = context;
    this.matched = false;
    if (this.bestMatch.length < ncapture) {
      this.bestMatch = new int[ncapture];
    }
    Arrays.fill(this.bestMatch, 0, ncapture, -1);
    this.addToThreadqStack.clear();
  }

  void releaseInputContext() {
    context = null;
  }

  int[] runSearch(boolean anchored, MatchKind kind, int nsubmatch, int endPos) {
    if (prog.hasGraphemeSemantics()) {
      doSearchEveryCharPosition(anchored);
    } else {
      doSearch(anchored);
    }

    if (!matched) {
      return null;
    }
    if (kind == MatchKind.FULL_MATCH && bestMatch[1] != endPos) {
      return null;
    }

    int[] result = new int[2 * nsubmatch];
    int ncopy = Math.min(ncapture, result.length);
    System.arraycopy(bestMatch, 0, result, 0, ncopy);
    if (ncopy < result.length) {
      Arrays.fill(result, ncopy, result.length, -1);
    }
    return result;
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
  static int[] search(
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
  static int[] search(
      Prog prog,
      String text,
      int startPos,
      int searchLimit,
      Anchor anchor,
      MatchKind kind,
      int nsubmatch) {
    return search(prog, text, startPos, searchLimit, text.length(), anchor, kind, nsubmatch);
  }

  static int[] search(
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

  static int[] search(
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

  static int[] search(
      Prog prog,
      String text,
      int startPos,
      int searchLimit,
      int endPos,
      int regionStart,
      Anchor anchor,
      MatchKind kind,
      int nsubmatch,
      GraphemeSupport.Context graphemeContext) {
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
        0,
        endPos,
        anchor,
        kind,
        nsubmatch,
        graphemeContext);
  }

  static int[] search(
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
      GraphemeSupport.Context graphemeContext) {
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
        0,
        endPos,
        anchor,
        kind,
        nsubmatch,
        graphemeContext);
  }

  static int[] search(
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
      int emptyAnchorStartPos,
      int emptyAnchorEndPos,
      Anchor anchor,
      MatchKind kind,
      int nsubmatch,
      GraphemeSupport.Context graphemeContext) {
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

    EngineContext context =
        EngineContext.create(
            prog,
            text,
            startPos,
            searchLimit,
            endPos,
            graphemeConsumeEndPos,
            consumeRegionStart,
            boundaryRegionStart,
            boundaryEndPos,
            anchorEndPos,
            emptyAnchorStartPos,
            emptyAnchorEndPos,
            graphemeContext);
    Nfa nfa = new Nfa(prog, context, ncapture, longestMode, endmatch);
    return nfa.runSearch(anchored, kind, nsubmatch, endPos);
  }

  /**
   * Main search loop. Iterates over each position in the text starting from {@code startPos} (plus
   * one past the end), stepping the NFA. At each position, starts a new thread if appropriate, then
   * advances all existing threads by one character.
   *
   * @param anchored whether to anchor the search at the context start
   */
  private void doSearch(boolean anchored) {
    String text = context.text();
    int startPos = context.searchStart();
    int searchLimit = context.searchLimit();
    int endPos = context.endPos();
    QueueState runq = allocQueueState();
    QueueState nextq = allocQueueState();

    int[] initialCap = new int[threadArraySize];

    int pos = startPos;
    while (true) {
      int cp = codePointAtConsumeBoundary(text, pos);
      int nextPos = cp >= 0 ? pos + Character.charCount(cp) : nextBoundaryPosition(pos, endPos);

      // Start a new thread if there have not been any matches
      // (no point starting new threads to the right of an existing match).
      // Also don't start threads past searchLimit — the DFA has already determined
      // there's no match starting beyond that position.
      if (!matched && pos <= searchLimit && (!anchored || pos == startPos)) {
        Arrays.fill(initialCap, -1);
        initialCap[0] = pos;
        // Always use prog.start() (anchored start). Unanchored matching is achieved
        // by starting a new thread at each position. The startUnanchored() entry point
        // (which includes a .*? prefix) is only for the DFA engine.
        addToThreadq(runq, prog.start(), text, pos, initialCap, false);
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
        freeQueue(runq);
        pos = nextPos;
        continue;
      }

      boolean done = stepCodePoint(runq, nextq, cp, text, pos, nextPos);

      QueueState tmp = runq;
      runq = nextq;
      nextq = tmp;
      freeQueue(nextq);

      if (done) {
        break;
      }

      if (pos >= endPos) {
        break;
      }

      pos = nextPos;
    }

    freeQueueState(runq);
    freeQueueState(nextq);
  }

  /**
   * Unanchored search variant for grapheme programs.
   *
   * <p>Active threads still consume Unicode code points, but new candidate matches are started at
   * every Java character offset. That matches {@link java.util.regex.Matcher}'s character-index
   * search contract for grapheme constructs without changing the code-point stepping used by
   * ordinary SafeRE programs.
   */
  private void doSearchEveryCharPosition(boolean anchored) {
    String text = context.text();
    int startPos = context.searchStart();
    int searchLimit = context.searchLimit();
    int start = Math.max(0, startPos);
    QueueState runq = allocQueueState();

    QueueState[] delayedBuffer = null;
    Map<Integer, QueueState> delayedGrapheme = null;
    if (prog.hasGraphemeSemantics()) {
      delayedGrapheme = new HashMap<>();
    } else {
      delayedBuffer = new QueueState[4];
    }

    int[] initialCap = new int[threadArraySize];

    int engineEndPos = context.engineEndPos();
    for (int pos = start; pos < engineEndPos + 2; pos++) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      if (prog.hasGraphemeSemantics()) {
        mergeDelayedQueue(delayedGrapheme, pos, runq);
      } else {
        mergeDelayedQueue(delayedBuffer, pos, runq);
      }
      if (!matched && pos <= searchLimit && (!anchored || pos == startPos)) {
        Arrays.fill(initialCap, -1);
        initialCap[0] = pos;
        addToThreadq(runq, prog.start(), text, pos, initialCap, false);
      }

      if (!runq.isEmpty()) {
        long stepResult = inputStep(text, pos);
        int cp = (int) (stepResult >>> 32);
        int nextPos = (int) stepResult;
        step(runq, delayedBuffer, delayedGrapheme, cp, text, pos, nextPos);
      } else {
        freeQueue(runq);
      }
      if (matched
          && runq.isEmpty()
          && (prog.hasGraphemeSemantics()
              ? delayedGrapheme.isEmpty()
              : isDelayedBufferEmpty(delayedBuffer))) {
        break;
      }
    }

    freeQueueState(runq);
    if (prog.hasGraphemeSemantics()) {
      for (QueueState q : delayedGrapheme.values()) {
        freeQueueState(q);
      }
      delayedGrapheme.clear();
    } else {
      for (int i = 0; i < delayedBuffer.length; i++) {
        if (delayedBuffer[i] != null) {
          freeQueueState(delayedBuffer[i]);
          delayedBuffer[i] = null;
        }
      }
    }
  }

  private static boolean isDelayedBufferEmpty(QueueState[] delayedBuffer) {
    for (QueueState q : delayedBuffer) {
      if (q != null && !q.isEmpty()) {
        return false;
      }
    }
    return true;
  }

  private void mergeDelayedQueue(QueueState[] delayedBuffer, int pos, QueueState destination) {
    int idx = pos % delayedBuffer.length;
    QueueState source = delayedBuffer[idx];
    if (source == null) {
      return;
    }
    mergeQueues(source, destination, pos);
    delayedBuffer[idx] = null;
    freeQueueState(source);
  }

  private void mergeDelayedQueue(
      Map<Integer, QueueState> delayedGrapheme, int pos, QueueState destination) {
    QueueState source = delayedGrapheme.remove(pos);
    if (source == null) {
      return;
    }
    mergeQueues(source, destination, pos);
    freeQueueState(source);
  }

  private void mergeQueues(QueueState source, QueueState destination, int pos) {
    String text = context.text();
    for (int i = 0; i < source.size; i++) {
      NfaThread t = source.threads[i];
      boolean visited;
      if (destination.hasGraphemeSemantics) {
        long key = visitKey(prog.inst(t.id), t.id, text, pos, t.graphemeStart);
        visited = !destination.visitedGrapheme.add(key);
      } else {
        visited = destination.visitedInst[t.id] == destination.visitedGeneration;
        destination.visitedInst[t.id] = destination.visitedGeneration;
      }
      if (visited) {
        freeThread(t);
      } else {
        destination.threads[destination.size++] = t;
      }
      source.threads[i] = null;
    }
    source.size = 0;
  }

  private QueueState delayedQueueAt(QueueState[] delayedBuffer, int pos) {
    int idx = pos % delayedBuffer.length;
    QueueState q = delayedBuffer[idx];
    if (q == null) {
      q = allocQueueState();
      delayedBuffer[idx] = q;
    }
    return q;
  }

  private QueueState delayedQueueAt(Map<Integer, QueueState> delayedGrapheme, int pos) {
    int engineEndPos = context.engineEndPos();
    if (pos < 0 || pos > engineEndPos + 1) {
      return null;
    }
    return delayedGrapheme.computeIfAbsent(pos, unused -> allocQueueState());
  }

  private long visitKey(Inst ip, int id, String text, int pos, int graphemeStart) {
    long instructionKey = ((long) id) << GraphemeSupport.visitKeyVariantBits();
    if (ip.op != InstOp.GRAPHEME_CLUSTER) {
      return instructionKey;
    }
    return instructionKey
        | GraphemeSupport.visitVariant(
            text,
            pos,
            graphemeStart,
            context.consumeRegionStart(),
            context.graphemeConsumeEndPos(),
            context.graphemeContext());
  }

  private long inputStep(String text, int pos) {
    if (prog.hasGraphemeSemantics()) {
      int cp = GraphemeSupport.inputCodePointAt(text, pos, context.endPos(), true);
      int nextPos = GraphemeSupport.inputNextPos(text, pos, context.endPos(), true);
      return ((long) cp << 32) | (nextPos & 0xFFFFFFFFL);
    }
    if (pos < 0 || pos >= context.engineEndPos()) {
      int nextPos = nextBoundaryPosition(pos, context.endPos());
      return (-1L << 32) | (nextPos & 0xFFFFFFFFL);
    }
    int cp = text.codePointAt(pos);
    int width = Character.charCount(cp);
    if (pos + width <= context.engineEndPos()) {
      return ((long) cp << 32) | ((pos + width) & 0xFFFFFFFFL);
    } else {
      int nextPos = nextBoundaryPosition(pos, context.endPos());
      return (-1L << 32) | (nextPos & 0xFFFFFFFFL);
    }
  }

  private int graphemeNextPos(String text, int pos) {
    return GraphemeSupport.graphemeNextPos(text, pos, context.graphemeConsumeEndPos());
  }

  private int codePointAtConsumeBoundary(String text, int pos) {
    if (pos < 0 || pos >= context.engineEndPos()) {
      return -1;
    }
    int cp = text.codePointAt(pos);
    return pos + Character.charCount(cp) <= context.engineEndPos() ? cp : -1;
  }

  private static int nextBoundaryPosition(int pos, int endPos) {
    return pos < endPos ? endPos : endPos + 1;
  }

  /**
   * Follows all empty transitions from {@code id0} and enqueues consuming/accepting instructions
   * (CHAR_RANGE and MATCH) into the thread queue.
   *
   * @param q the thread queue to add to
   * @param id starting instruction ID
   * @param text the input text
   * @param pos the current position in the text
   * @param t0 the current capture array (shared — will be cloned before mutation)
   */
  private void addToThreadq(
      QueueState q, int id, String text, int pos, int[] t0, boolean consumedInput) {
    AddToThreadqStack stack = addToThreadqStack;
    stack.clear();
    stack.pushInstruction(id, consumedInput);

    int cachedFlags = -1;

    while (!stack.isEmpty()) {
      stack.pop();
      if (stack.restoreIndex >= 0) {
        t0[stack.restoreIndex] = stack.restoreValue;
        continue;
      }
      if (stack.id == 0) {
        continue;
      }

      int instructionId = stack.id;
      boolean frameConsumedInput = stack.consumedInput;
      Inst ip = prog.inst(instructionId);
      // PROGRESS_CHECK is excluded from the visited set: it manages its own re-entry
      // via registers. Within one addToThreadq call, it is visited at most twice (once
      // to save the position, once to detect zero-width and redirect to exit).
      //
      // ALT and CAPTURE are also excluded because a nullable quantified body can revisit the
      // same alternation or capture instruction at the same input position with different capture
      // registers. The later zero-width iteration is JDK-visible and must not be discarded.
      if (ip.op != InstOp.PROGRESS_CHECK && ip.op != InstOp.ALT && ip.op != InstOp.CAPTURE) {
        if (q.hasGraphemeSemantics) {
          long visitKey = visitKey(ip, instructionId, text, pos, pos);
          if (!q.visitedGrapheme.add(visitKey)) {
            continue;
          }
        } else {
          if (q.visitedInst[instructionId] == q.visitedGeneration) {
            continue;
          }
          q.visitedInst[instructionId] = q.visitedGeneration;
        }
      }

      switch (ip.op) {
        case FAIL -> {}

        case ALT -> {
          stack.pushInstruction(ip.out1, frameConsumedInput);
          stack.pushInstruction(ip.out, frameConsumedInput);
        }

        case ALT_MATCH -> {
          q.threads[q.size++] = allocThread(instructionId, t0, -1);
          stack.pushInstruction(ip.out, frameConsumedInput);
          stack.pushInstruction(ip.out1, frameConsumedInput);
        }

        case NOP -> stack.pushInstruction(ip.out, frameConsumedInput);

        case CAPTURE -> {
          if (ip.arg < ncapture) {
            int opos = t0[ip.arg];
            t0[ip.arg] = pos;
            stack.pushRestore(ip.arg, opos);
            stack.pushInstruction(ip.out, frameConsumedInput);
          } else {
            stack.pushInstruction(ip.out, frameConsumedInput);
          }
        }

        case EMPTY_WIDTH -> {
          if (cachedFlags == -1) {
            cachedFlags =
                emptyFlags(
                    text,
                    pos,
                    prog.unixLines(),
                    prog.hasGraphemeSemantics(),
                    context.graphemeContext(),
                    t0[0],
                    context.boundaryRegionStart(),
                    frameConsumedInput,
                    context.emptyAnchorStartPos(),
                    context.emptyAnchorEndPos(),
                    context.effectiveBoundaryEndPos(frameConsumedInput),
                    prog.hasWordBoundary(),
                    prog.hasTextAnchor());
          }
          if ((ip.arg & ~cachedFlags) == 0) {
            stack.pushInstruction(ip.out, frameConsumedInput);
          }
        }

        case PROGRESS_CHECK -> {
          int reg = ip.arg;
          int regIdx = ncapture + reg;
          int saved = t0[regIdx];
          if (saved == -1) {
            t0[regIdx] = pos;
            stack.pushRestore(regIdx, -1);
            stack.pushInstruction(ip.out, frameConsumedInput);
          } else if (saved == pos) {
            stack.pushInstruction(ip.out1, frameConsumedInput);
          } else {
            t0[regIdx] = pos;
            stack.pushRestore(regIdx, saved);
            if (ip.foldCase) {
              stack.pushInstruction(ip.out, frameConsumedInput);
              stack.pushInstruction(ip.out1, frameConsumedInput);
            } else {
              stack.pushInstruction(ip.out1, frameConsumedInput);
              stack.pushInstruction(ip.out, frameConsumedInput);
            }
          }
        }

        case CHAR_RANGE, CHAR_CLASS, GRAPHEME_CLUSTER, MATCH -> {
          q.threads[q.size++] =
              allocThread(instructionId, t0, ip.op == InstOp.GRAPHEME_CLUSTER ? pos : -1);
        }
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
      QueueState[] delayedBuffer,
      Map<Integer, QueueState> delayedGrapheme,
      int cp,
      String text,
      int matchPos,
      int nextPos) {
    for (int threadIndex = 0; threadIndex < rq.size; threadIndex++) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      NfaThread t = rq.threads[threadIndex];
      int id = t.id;
      int[] capture = t.capture;

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
            QueueState destination =
                prog.hasGraphemeSemantics()
                    ? delayedQueueAt(delayedGrapheme, nextPos)
                    : delayedQueueAt(delayedBuffer, nextPos);
            if (destination != null) {
              addToThreadq(destination, ip.out, text, nextPos, capture, true);
            }
          }
        }

        case CHAR_CLASS -> {
          if (cp >= 0 && ip.matchesCharClass(cp)) {
            QueueState destination =
                prog.hasGraphemeSemantics()
                    ? delayedQueueAt(delayedGrapheme, nextPos)
                    : delayedQueueAt(delayedBuffer, nextPos);
            if (destination != null) {
              addToThreadq(destination, ip.out, text, nextPos, capture, true);
            }
          }
        }

        case GRAPHEME_CLUSTER -> {
          if (matchPos < context.endPos()) {
            int graphemeStart =
                Math.max(
                    context.consumeRegionStart(),
                    t.graphemeStart >= 0 ? t.graphemeStart : matchPos);
            int scalarEnd = graphemeNextPos(text, matchPos);
            QueueState destination =
                prog.hasGraphemeSemantics()
                    ? delayedQueueAt(delayedGrapheme, scalarEnd)
                    : delayedQueueAt(delayedBuffer, scalarEnd);
            if (destination != null) {
              if (scalarEnd == context.graphemeConsumeEndPos()
                  || GraphemeSupport.isGraphemeClusterBoundary(
                      text, scalarEnd, graphemeStart, context.graphemeContext())) {
                addToThreadq(destination, ip.out, text, scalarEnd, capture, true);
              } else {
                boolean visited;
                if (destination.hasGraphemeSemantics) {
                  long key = visitKey(ip, id, text, scalarEnd, graphemeStart);
                  visited = !destination.visitedGrapheme.add(key);
                } else {
                  visited = destination.visitedInst[id] == destination.visitedGeneration;
                  destination.visitedInst[id] = destination.visitedGeneration;
                }
                if (!visited) {
                  destination.threads[destination.size++] = allocThread(id, capture, graphemeStart);
                }
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
              freeQueue(rq);
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
    freeQueue(rq);
    return false;
  }

  private boolean stepCodePoint(
      QueueState rq, QueueState nq, int cp, String text, int matchPos, int nextPos) {
    nq.clear();

    for (int threadIndex = 0; threadIndex < rq.size; threadIndex++) {
      NfaThread t = rq.threads[threadIndex];
      int id = t.id;
      int[] capture = t.capture;

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
            addToThreadq(nq, ip.out, text, nextPos, capture, true);
          }
        }

        case CHAR_CLASS -> {
          if (cp >= 0 && ip.matchesCharClass(cp)) {
            addToThreadq(nq, ip.out, text, nextPos, capture, true);
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
              freeQueue(rq);
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
    freeQueue(rq);
    return false;
  }

  private boolean matchesEndPosition(String text, int matchPos) {
    if (matchPos == context.anchorEndPos()) {
      return true;
    }
    if (matchPos == context.graphemeConsumeEndPos()
        && context.graphemeConsumeEndPos() != context.endPos()
        && context.anchorEndPos() == context.endPos()) {
      return true;
    }
    return prog.dollarAnchorEnd()
        && isAtTrailingLineTerminator(text, matchPos, prog.unixLines(), context.anchorEndPos());
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
        text,
        pos,
        unixLines,
        includeGraphemeClusterBoundary,
        (GraphemeSupport.Context) null,
        -1,
        0,
        false);
  }

  static int emptyFlags(
      String text,
      int pos,
      boolean unixLines,
      boolean includeGraphemeClusterBoundary,
      GraphemeSupport.Context graphemeContext) {
    return emptyFlags(
        text, pos, unixLines, includeGraphemeClusterBoundary, graphemeContext, true, true);
  }

  static int emptyFlags(
      String text,
      int pos,
      boolean unixLines,
      boolean includeGraphemeClusterBoundary,
      GraphemeSupport.Context graphemeContext,
      boolean hasWordBoundary,
      boolean hasTextAnchor) {
    return emptyFlags(
        text,
        pos,
        unixLines,
        includeGraphemeClusterBoundary,
        graphemeContext,
        -1,
        0,
        false,
        0,
        text.length(),
        text.length(),
        hasWordBoundary,
        hasTextAnchor);
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
        (GraphemeSupport.Context) null,
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
      GraphemeSupport.Context graphemeContext,
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
        0,
        text.length(),
        text.length(),
        true,
        true);
  }

  static int emptyFlags(
      String text,
      int pos,
      boolean unixLines,
      boolean includeGraphemeClusterBoundary,
      GraphemeSupport.Context graphemeContext,
      int matchStart,
      int regionStart,
      boolean consumedInput,
      int anchorStartPos,
      int anchorEndPos,
      int boundaryEndPos,
      boolean hasWordBoundary,
      boolean hasTextAnchor) {
    int flags = 0;

    // ^ and \A
    // BEGIN_LINE is set at the start of text and after a line terminator, but NOT at
    // end-of-text after a final line terminator. JDK's MULTILINE ^ does not match at the
    // position past the last line terminator when that position is the end of the string.
    // For example, "a\n" has BEGIN_LINE at pos 0 but NOT at pos 2.
    // Also, JDK's MULTILINE ^ does not match at position 0 of an empty string — the empty
    // string has no lines for ^ to match at. BEGIN_TEXT is still set (for \A). See #41.
    if (hasTextAnchor) {
      if (pos == anchorStartPos) {
        flags |= EmptyOp.BEGIN_TEXT;
        if (!text.isEmpty() && pos != anchorEndPos) {
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
    }

    // \b and \B
    if (hasWordBoundary) {
      int prevCp = pos > regionStart ? text.codePointBefore(pos) : -1;
      boolean prevWord = prevCp >= 0 && isWordChar(prevCp);
      int nextCp = pos < boundaryEndPos ? text.codePointAt(pos) : -1;
      boolean nextWord = nextCp >= 0 && isWordChar(nextCp);
      if (prevWord != nextWord) {
        flags |= EmptyOp.WORD_BOUNDARY;
      } else {
        flags |= EmptyOp.NON_WORD_BOUNDARY;
      }

      // Unicode \b and \B
      boolean prevUnicodeWord =
          prevCp >= 0 && (prevCp < 128 ? prevWord : isUnicodeWordChar(prevCp));
      boolean nextUnicodeWord =
          nextCp >= 0 && (nextCp < 128 ? nextWord : isUnicodeWordChar(nextCp));
      if (prevUnicodeWord != nextUnicodeWord) {
        flags |= EmptyOp.UNICODE_WORD_BOUNDARY;
      } else {
        flags |= EmptyOp.UNICODE_NON_WORD_BOUNDARY;
      }
    }

    if (includeGraphemeClusterBoundary) {
      flags |=
          GraphemeSupport.boundaryFlags(
              text, pos, graphemeContext, matchStart, regionStart, consumedInput, boundaryEndPos);
    }

    return flags;
  }

  /**
   * Returns true if {@code pos} is a grapheme cluster boundary for SafeRE's supported approximation
   * of JDK {@code \X}.
   */
  static boolean isGraphemeClusterBoundary(String text, int pos) {
    return GraphemeSupport.isGraphemeClusterBoundary(text, pos);
  }

  static boolean isGraphemeClusterBoundary(
      String text, int pos, GraphemeSupport.Context graphemeContext) {
    return GraphemeSupport.isGraphemeClusterBoundary(text, pos, graphemeContext);
  }

  static boolean isGraphemeClusterBoundary(
      String text, int pos, int regionStart, GraphemeSupport.Context graphemeContext) {
    return GraphemeSupport.isGraphemeClusterBoundary(text, pos, regionStart, graphemeContext);
  }

  static boolean hasGraphemeExtendAt(String text, int pos) {
    return GraphemeSupport.hasGraphemeExtendAt(text, pos);
  }

  static boolean isExtendedPictographicAt(String text, int pos) {
    return GraphemeSupport.isExtendedPictographicAt(text, pos);
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
