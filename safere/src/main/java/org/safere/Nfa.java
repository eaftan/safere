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

  /** A thread in the NFA: an instruction index paired with capture metadata. */
  // TODO(#98): Replace int[] with Guava ImmutableIntArray to get proper value semantics.
  @SuppressWarnings("ArrayRecordComponent")
  private record NfaThread(int id, int[] capture, boolean consumedInput, int graphemeStart) {}

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

  private final Prog prog;
  private final int ncapture;

  /** Total thread array size: ncapture slots for captures + numLoopRegs for progress checks. */
  private final int threadArraySize;

  private final boolean longest;
  private final boolean endmatch;
  private final EngineContext context;

  private boolean matched;
  private int[] bestMatch;

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
      GraphemeSupport.Context graphemeContext) {
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
            graphemeContext);
    Nfa nfa = new Nfa(prog, context, ncapture, longestMode, endmatch);
    if (prog.hasGraphemeSemantics()) {
      nfa.doSearchEveryCharPosition(anchored);
    } else {
      nfa.doSearch(anchored);
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
   * @param anchored whether to anchor the search at the context start
   */
  private void doSearch(boolean anchored) {
    String text = context.text();
    int startPos = context.searchStart();
    int searchLimit = context.searchLimit();
    int endPos = context.endPos();
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
  private void doSearchEveryCharPosition(boolean anchored) {
    String text = context.text();
    int startPos = context.searchStart();
    int searchLimit = context.searchLimit();
    int start = Math.max(0, startPos);
    QueueState runq = new QueueState();
    Map<Integer, QueueState> delayed = new HashMap<>();

    int engineEndPos = context.engineEndPos();
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
    int engineEndPos = context.engineEndPos();
    if (pos < 0 || pos > engineEndPos + 1) {
      return null;
    }
    return delayed.computeIfAbsent(pos, unused -> new QueueState());
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

  private int inputCodePointAt(String text, int pos) {
    return GraphemeSupport.inputCodePointAt(
        text, pos, context.endPos(), prog.hasGraphemeSemantics());
  }

  private int inputNextPos(String text, int pos) {
    return GraphemeSupport.inputNextPos(text, pos, context.endPos(), prog.hasGraphemeSemantics());
  }

  private int graphemeNextPos(String text, int pos) {
    return GraphemeSupport.graphemeNextPos(text, pos, context.graphemeConsumeEndPos());
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
          int flags =
              emptyFlags(
                  text,
                  pos,
                  prog.unixLines(),
                  prog.hasGraphemeSemantics(),
                  context.graphemeContext(),
                  t0[0],
                  context.boundaryRegionStart(),
                  consumedInput,
                  context.anchorEndPos(),
                  context.effectiveBoundaryEndPos(consumedInput));
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
          if (matchPos < context.endPos()) {
            int graphemeStart =
                Math.max(
                    context.consumeRegionStart(),
                    t.graphemeStart() >= 0 ? t.graphemeStart() : matchPos);
            int scalarEnd = graphemeNextPos(text, matchPos);
            QueueState destination = delayedQueueAt(delayed, scalarEnd);
            if (destination != null) {
              if (scalarEnd == context.graphemeConsumeEndPos()
                  || GraphemeSupport.isGraphemeClusterBoundary(
                      text, scalarEnd, graphemeStart, context.graphemeContext())) {
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
        text.length(),
        text.length());
  }

  private static int emptyFlags(
      String text,
      int pos,
      boolean unixLines,
      boolean includeGraphemeClusterBoundary,
      GraphemeSupport.Context graphemeContext,
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
