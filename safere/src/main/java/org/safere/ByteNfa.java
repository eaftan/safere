// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Arrays;

/**
 * NFA execution engine for raw byte matching on {@code byte[]}. Implements linear-time Thompson NFA
 * search.
 */
final class ByteNfa {

  private static final class NfaThread {
    int id;
    int[] capture;
  }

  private static final class QueueState {
    final NfaThread[] threads;
    int size = 0;
    final boolean[] visitedInst;

    QueueState(int progSize) {
      this.threads = new NfaThread[progSize + 1];
      this.visitedInst = new boolean[progSize + 1];
    }

    boolean isEmpty() {
      return size == 0;
    }

    void clear() {
      size = 0;
      Arrays.fill(visitedInst, false);
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

  static final class SearchResult {
    private final int[] groups;

    SearchResult(int[] groups) {
      this.groups = groups;
    }

    int[] groups() {
      return groups;
    }
  }

  private final Prog prog;
  private final int ncapture;
  private final int threadArraySize;
  private final boolean longest;
  private final boolean endmatch;
  private final ByteEngineContext context;

  private boolean matched;
  private final int[] bestMatch;
  private final AddToThreadqStack addToThreadqStack = new AddToThreadqStack();

  private NfaThread[] threadPool = new NfaThread[16];
  private int threadPoolSize = 0;

  static SearchResult search(
      Prog prog,
      byte[] text,
      int startPos,
      int searchLimit,
      int endPos,
      int consumeRegionStart,
      int boundaryRegionStart,
      int boundaryEndPos,
      int anchorEndPos,
      int emptyAnchorStartPos,
      int emptyAnchorEndPos,
      Nfa.Anchor anchor,
      Nfa.MatchKind kind,
      int nsubmatch) {

    if (prog.start() == 0) {
      return new SearchResult(null);
    }

    boolean anchored = (anchor == Nfa.Anchor.ANCHORED) || prog.anchorStart();
    boolean longestMode = (kind != Nfa.MatchKind.FIRST_MATCH);
    boolean endmatch = prog.anchorEnd();

    if (kind == Nfa.MatchKind.FULL_MATCH) {
      anchored = true;
      endmatch = true;
      if (nsubmatch == 0) {
        nsubmatch = 1;
      }
    }

    int ncapture = 2 * Math.max(nsubmatch, 1);
    ByteEngineContext context =
        ByteEngineContext.create(
            prog,
            text,
            startPos,
            searchLimit,
            endPos,
            consumeRegionStart,
            boundaryRegionStart,
            boundaryEndPos,
            anchorEndPos,
            emptyAnchorStartPos,
            emptyAnchorEndPos);

    ByteNfa nfa = new ByteNfa(prog, context, ncapture, longestMode, endmatch);
    nfa.doSearch(anchored);

    if (!nfa.matched) {
      return new SearchResult(null);
    }
    if (kind == Nfa.MatchKind.FULL_MATCH && nfa.bestMatch[1] != endPos) {
      return new SearchResult(null);
    }

    int[] result = new int[2 * nsubmatch];
    System.arraycopy(nfa.bestMatch, 0, result, 0, Math.min(result.length, nfa.bestMatch.length));
    for (int i = nfa.bestMatch.length; i < result.length; i++) {
      result[i] = -1;
    }
    return new SearchResult(result);
  }

  private ByteNfa(
      Prog prog, ByteEngineContext context, int ncapture, boolean longest, boolean endmatch) {
    this.prog = prog;
    this.context = context;
    this.ncapture = ncapture;
    this.threadArraySize = ncapture + prog.numLoopRegs();
    this.longest = longest;
    this.endmatch = endmatch;
    this.bestMatch = new int[ncapture];
    Arrays.fill(bestMatch, -1);
  }

  private NfaThread allocThread(int id, int[] capture) {
    NfaThread t;
    if (threadPoolSize > 0) {
      t = threadPool[--threadPoolSize];
      threadPool[threadPoolSize] = null;
    } else {
      t = new NfaThread();
      t.capture = new int[threadArraySize];
    }
    t.id = id;
    System.arraycopy(capture, 0, t.capture, 0, threadArraySize);
    return t;
  }

  private void freeThread(NfaThread t) {
    if (threadPoolSize == threadPool.length) {
      threadPool = Arrays.copyOf(threadPool, threadPool.length * 2);
    }
    threadPool[threadPoolSize++] = t;
  }

  private QueueState allocQueueState() {
    return new QueueState(prog.size());
  }

  private void freeQueue(QueueState q) {
    for (int i = 0; i < q.size; i++) {
      freeThread(q.threads[i]);
      q.threads[i] = null;
    }
    q.clear();
  }

  private void freeQueueState(QueueState q) {
    freeQueue(q);
  }

  private void doSearch(boolean anchored) {
    byte[] text = context.text();
    int startPos = context.searchStart();
    int searchLimit = context.searchLimit();
    int endPos = context.endPos();
    QueueState runq = allocQueueState();
    QueueState nextq = allocQueueState();

    int[] initialCap = new int[threadArraySize];

    int pos = startPos;
    while (true) {
      int b = (pos < endPos) ? (text[pos] & 0xFF) : -1;
      int nextPos = pos + 1;

      if (!matched && pos <= searchLimit && (!anchored || pos == startPos)) {
        Arrays.fill(initialCap, -1);
        initialCap[0] = pos;
        addToThreadq(runq, prog.start(), text, pos, initialCap, false);
      }

      if (runq.isEmpty()) {
        if (anchored || matched) {
          break;
        }
        if (pos >= endPos) {
          break;
        }
        freeQueue(runq);
        pos = nextPos;
        continue;
      }

      boolean done = stepCodePoint(runq, nextq, b, text, pos, nextPos);

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

  private void addToThreadq(
      QueueState q, int id, byte[] text, int pos, int[] t0, boolean consumedInput) {
    AddToThreadqStack stack = addToThreadqStack;
    stack.clear();
    stack.pushInstruction(id, consumedInput);

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

      if (ip.op != InstOp.PROGRESS_CHECK && ip.op != InstOp.ALT && ip.op != InstOp.CAPTURE) {
        if (q.visitedInst[instructionId]) {
          continue;
        }
        q.visitedInst[instructionId] = true;
      }

      switch (ip.op) {
        case FAIL -> {}
        case ALT -> {
          stack.pushInstruction(ip.out1, frameConsumedInput);
          stack.pushInstruction(ip.out, frameConsumedInput);
        }
        case ALT_MATCH -> {
          q.threads[q.size++] = allocThread(instructionId, t0);
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
          int flags =
              Nfa.emptyFlags(
                  text,
                  pos,
                  prog.unixLines(),
                  false,
                  t0[0],
                  context.boundaryRegionStart(),
                  frameConsumedInput,
                  context.emptyAnchorStartPos(),
                  context.emptyAnchorEndPos(),
                  context.boundaryEndPos(),
                  prog.hasWordBoundary(),
                  prog.hasTextAnchor());
          if ((ip.arg & ~flags) == 0) {
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
        case CHAR_RANGE, MATCH -> {
          q.threads[q.size++] = allocThread(instructionId, t0);
        }
        default -> {}
      }
    }
  }

  private boolean matchesEndPosition(byte[] text, int matchPos) {
    if (matchPos == context.anchorEndPos()) {
      return true;
    }
    return prog.dollarAnchorEnd()
        && Nfa.isAtTrailingLineTerminator(text, matchPos, context.anchorEndPos());
  }

  private boolean stepCodePoint(
      QueueState rq, QueueState nq, int b, byte[] text, int matchPos, int nextPos) {
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
          if (b >= 0 && ip.lo <= b && b <= ip.hi) {
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
              System.arraycopy(capture, 0, bestMatch, 0, ncapture);
              bestMatch[1] = matchPos;
              matched = true;
              freeQueue(rq);
              return false;
            }
          }
        }
        case ALT_MATCH -> {
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
}
