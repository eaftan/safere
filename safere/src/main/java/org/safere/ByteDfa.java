// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Lazy DFA execution engine for raw byte matching on {@code byte[]}. Builds DFA states on demand
 * from the compiled UTF-8 NFA program.
 */
final class ByteDfa {

  record SearchResult(boolean matched, int pos) {}

  private static final int FLAG_MATCH = 1 << 10;
  private static final int FLAG_LAST_WORD = 1 << 11;
  private static final int FLAG_MATCH_BEFORE = 1 << 12;
  private static final int FLAG_MATCH_AFTER_DEFERRED = 1 << 13;

  private static final int DEFAULT_MAX_STATES = 10_000;

  private static final class State {
    final int[] insts;
    final int flags;
    final State[] next;
    final boolean isHighestPriorityMatch;

    State(int[] insts, int flags, int numClasses, boolean isHighestPriorityMatch) {
      this.insts = insts;
      this.flags = flags;
      this.next = new State[numClasses];
      this.isHighestPriorityMatch = isHighestPriorityMatch;
    }

    boolean isMatch() {
      return (flags & FLAG_MATCH) != 0;
    }
  }

  private final Prog prog;
  private final int numClasses;
  private final int[] byteClassMap;
  private final boolean longest;

  private final Map<StateKey, State> stateCache = new HashMap<>();
  private final State deadState;
  private final State[] startStateByContext;
  private final int stateEmptyFlagsMask = EmptyOp.ALL_FLAGS & ~EmptyOp.GRAPHEME_CLUSTER_BOUNDARY;
  private final int startCacheEmptyFlagsMask =
      EmptyOp.ALL_FLAGS & ~EmptyOp.GRAPHEME_CLUSTER_BOUNDARY;
  private final int reverseCacheBit = (startCacheEmptyFlagsMask + 1) << 2;
  private final int anchoredCacheBit = reverseCacheBit << 1;

  private int stateCount;
  private final int maxStates;

  // Reusable search state buffers to avoid allocations in search calls
  private final int[] expandVisitedGen;
  private final int[] expandStack;
  private final int[] expandFrontier;
  private final int[] computeBuf;
  private int expandGeneration;

  private static final class StateKey {
    private final int[] insts;
    private final int flags;

    StateKey(int[] insts, int flags) {
      this.insts = insts;
      this.flags = flags;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof StateKey other)) {
        return false;
      }
      return flags == other.flags && Arrays.equals(insts, other.insts);
    }

    @Override
    public int hashCode() {
      return 31 * flags + Arrays.hashCode(insts);
    }
  }

  static final class Setup {
    final int numClasses;
    final int[] byteClassMap;

    Setup(int numClasses, int[] byteClassMap) {
      this.numClasses = numClasses;
      this.byteClassMap = byteClassMap;
    }
  }

  static Setup buildSetup(Prog prog) {
    int[] boundaries = buildBoundaries(prog);
    int numClasses = boundaries.length + 1 + 1; // intervals + end-of-text
    int[] byteClassMap = buildByteClassMap(boundaries);
    return new Setup(numClasses, byteClassMap);
  }

  ByteDfa(Prog prog, Setup setup, boolean longest) {
    this(prog, setup, DEFAULT_MAX_STATES, longest);
  }

  ByteDfa(Prog prog, Setup setup, int maxStates, boolean longest) {
    this.prog = prog;
    this.numClasses = setup.numClasses;
    this.byteClassMap = setup.byteClassMap;
    this.maxStates = maxStates;
    this.longest = longest;

    this.deadState = new State(new int[0], 0, numClasses, false);
    this.stateCache.put(new StateKey(deadState.insts, deadState.flags), deadState);
    this.stateCount = 1;

    this.startStateByContext = new State[anchoredCacheBit << 1];
    this.expandVisitedGen = new int[prog.size()];
    this.expandStack = new int[prog.size()];
    this.expandFrontier = new int[prog.size()];
    this.computeBuf = new int[prog.size()];
  }

  private static int[] buildBoundaries(Prog prog) {
    java.util.TreeSet<Integer> bounds = new java.util.TreeSet<>();
    bounds.add(0);
    bounds.add(256);
    boolean hasWordBoundary = false;
    boolean hasLineBoundary = false;
    for (int i = 0; i < prog.size(); i++) {
      Inst inst = prog.inst(i);
      if (inst.opCode == InstOp.OP_CHAR_RANGE) {
        bounds.add(inst.lo);
        if (inst.hi < 255) {
          bounds.add(inst.hi + 1);
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
      bounds.add(0x0A); // '\n'
      bounds.add(0x0B); // '\n' + 1
      bounds.add(0x0D); // '\r'
      bounds.add(0x0E); // '\r' + 1
    }
    if (hasWordBoundary) {
      // Add boundaries at ASCII word character edges [0-9A-Za-z_]
      bounds.add(0x30); // '0'
      bounds.add(0x3A); // '9' + 1
      bounds.add(0x41); // 'A'
      bounds.add(0x5B); // 'Z' + 1
      bounds.add(0x5F); // '_'
      bounds.add(0x60); // '_' + 1
      bounds.add(0x61); // 'a'
      bounds.add(0x7B); // 'z' + 1
    }

    int[] res = new int[bounds.size()];
    int idx = 0;
    for (int b : bounds) {
      res[idx++] = b;
    }
    return res;
  }

  private static int[] buildByteClassMap(int[] boundaries) {
    int[] map = new int[256];
    for (int b = 0; b < 256; b++) {
      int idx = Arrays.binarySearch(boundaries, b);
      map[b] = (idx >= 0) ? idx : (-idx - 1) - 1;
    }
    return map;
  }

  private State cachedState(int[] insts, int flags) {
    StateKey key = new StateKey(insts, flags);
    State s = stateCache.get(key);
    if (s != null) {
      return s;
    }
    if (stateCount >= maxStates) {
      return null; // budget exceeded
    }
    boolean isHighestPriorityMatch =
        insts.length > 0 && prog.inst(insts[0]).opCode == InstOp.OP_MATCH;
    s = new State(insts, flags, numClasses, isHighestPriorityMatch);
    stateCache.put(key, s);
    stateCount++;
    return s;
  }

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
      Arrays.sort(frontier, 0, frontierSize);
    }
    return Arrays.copyOf(frontier, frontierSize);
  }

  private State computeNext(State s, int b, byte[] text, int nextPos) {
    if (b < 0) {
      int emptyFlags = emptyFlags(text, nextPos);
      boolean wasWord = (s.flags & FLAG_LAST_WORD) != 0;
      if (wasWord) {
        emptyFlags = (emptyFlags | EmptyOp.WORD_BOUNDARY) & ~EmptyOp.NON_WORD_BOUNDARY;
        emptyFlags =
            (emptyFlags | EmptyOp.UNICODE_WORD_BOUNDARY) & ~EmptyOp.UNICODE_NON_WORD_BOUNDARY;
      } else {
        emptyFlags = (emptyFlags | EmptyOp.NON_WORD_BOUNDARY) & ~EmptyOp.WORD_BOUNDARY;
        emptyFlags =
            (emptyFlags | EmptyOp.UNICODE_NON_WORD_BOUNDARY) & ~EmptyOp.UNICODE_WORD_BOUNDARY;
      }
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
      return cachedState(nextInsts, flags);
    }

    boolean isWord = Nfa.isWordChar(b);
    boolean wasWord = (s.flags & FLAG_LAST_WORD) != 0;
    int wordBeforeFlags =
        (isWord != wasWord)
            ? (EmptyOp.WORD_BOUNDARY | EmptyOp.UNICODE_WORD_BOUNDARY)
            : (EmptyOp.NON_WORD_BOUNDARY | EmptyOp.UNICODE_NON_WORD_BOUNDARY);

    boolean endLineHere = prog.unixLines() ? (b == '\n') : (b == '\n' || b == '\r');
    if (endLineHere && b == '\n' && nextPos >= 2 && (text[nextPos - 2] & 0xFF) == '\r') {
      endLineHere = false;
    }

    int reExpandEmptyFlags = (s.flags & stateEmptyFlagsMask) | wordBeforeFlags;
    if (endLineHere) {
      reExpandEmptyFlags |= EmptyOp.END_LINE;
    }

    int[] tempExpanded = new int[prog.size()];
    int tempCount = 0;
    boolean hasMatchFromDeferred = false;
    boolean deferredMatchIsPrimary = false;
    boolean isMatchValid = false;

    int[] instsWithoutMatch = stripMatch(s.insts);

    for (int id : instsWithoutMatch) {
      Inst ip = prog.inst(id);
      if (ip.opCode == InstOp.OP_EMPTY_WIDTH && (ip.arg & ~reExpandEmptyFlags) == 0) {
        int[] expanded = expand(new int[] {ip.out}, 1, reExpandEmptyFlags);
        if (hasMatch(expanded)) {
          hasMatchFromDeferred = true;
          deferredMatchIsPrimary = isMatchPrimary(expanded);
          for (int x : expanded) {
            if (prog.inst(x).opCode != InstOp.OP_MATCH) {
              tempExpanded[tempCount++] = x;
            }
          }
          if (!prog.anchorEnd()) {
            isMatchValid = true;
          } else {
            int textLen = text.length;
            int trailingTermStart = prog.dollarAnchorEnd() ? trailingLineStart(text) : textLen;
            int pos = nextPos - 1;
            if (pos == textLen || (trailingTermStart < textLen && pos == trailingTermStart)) {
              isMatchValid = true;
            }
          }
          if (isMatchValid && !prog.reversed()) {
            break; // Prune all lower-priority branches!
          }
        } else {
          for (int x : expanded) {
            tempExpanded[tempCount++] = x;
          }
        }
      } else {
        tempExpanded[tempCount++] = id;
      }
    }

    int[] expandedInsts = stableDedup(tempExpanded, tempCount);
    int unanchoredStart = prog.anchorStart() ? 0 : prog.startUnanchored();

    int successorCount = 0;
    boolean hasUnanchoredLoopTransition = false;
    for (int id : expandedInsts) {
      Inst ip = prog.inst(id);
      if (ip.opCode == InstOp.OP_CHAR_RANGE && ip.lo <= b && b <= ip.hi) {
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
      if (hasMatchFromDeferred) {
        return cachedState(
            new int[0], FLAG_MATCH | FLAG_MATCH_BEFORE | (isWord ? FLAG_LAST_WORD : 0));
      }
      return deadState;
    }

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
        return cachedState(
            new int[0], FLAG_MATCH | FLAG_MATCH_BEFORE | (isWord ? FLAG_LAST_WORD : 0));
      }
      return deadState;
    }

    int flags = emptyFlags & stateEmptyFlagsMask;
    if (hasMatchFromDeferred) {
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

    return cachedState(nextInsts, flags);
  }

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

  private boolean hasMatch(int[] insts) {
    for (int inst : insts) {
      if (prog.inst(inst).opCode == InstOp.OP_MATCH) {
        return true;
      }
    }
    return false;
  }

  private static boolean posIsWordChar(byte[] text, int pos) {
    if (pos < 0 || pos >= text.length) {
      return false;
    }
    return Nfa.isWordChar(text[pos] & 0xFF);
  }

  private State startState(byte[] text, int pos, boolean anchored) {
    return startState(text, pos, anchored, false);
  }

  private State startState(byte[] text, int pos, boolean anchored, boolean reverseContext) {
    int startInst = anchored ? prog.start() : prog.startUnanchored();
    if (startInst == 0) {
      return deadState;
    }
    int emptyFlags = emptyFlags(text, pos);

    boolean lastWord = false;
    if (prog.hasWordBoundary()) {
      if (reverseContext) {
        if (posIsWordChar(text, pos)) {
          lastWord = true;
        }
      } else {
        if (posIsWordChar(text, pos - 1)) {
          lastWord = true;
        }
      }
    }

    int cacheKey =
        (anchored ? anchoredCacheBit : 0)
            | (reverseContext ? reverseCacheBit : 0)
            | ((emptyFlags & startCacheEmptyFlagsMask) << 2)
            | (lastWord ? 2 : 0);

    State cached = startStateByContext[cacheKey];
    if (cached != null) {
      return cached;
    }

    int[] buf = computeBuf;
    buf[0] = startInst;
    int[] insts = expand(buf, 1, emptyFlags);
    int flags = emptyFlags & stateEmptyFlagsMask;
    if (hasMatch(insts)) {
      flags |= FLAG_MATCH;
    }
    if (lastWord) {
      flags |= FLAG_LAST_WORD;
    }
    State s = cachedState(insts, flags);
    if (s != null) {
      startStateByContext[cacheKey] = s;
    }
    return s;
  }

  SearchResult doSearch(byte[] text, int startPos, boolean anchored, boolean longest) {
    int textLen = text.length;
    boolean needEndMatch = prog.anchorEnd();
    boolean dollarEnd = prog.dollarAnchorEnd();
    int trailingTermStart = dollarEnd ? trailingLineStart(text) : textLen;

    State s = startState(text, startPos, anchored);
    if (s == null) {
      return null;
    }

    boolean matched = false;
    int matchEnd = -1;
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

    int posDepThreshold = positionDependentThreshold(text);

    int pos = startPos;
    while (pos < textLen) {
      int b = text[pos] & 0xFF;
      int cls = byteClassMap[b];

      int effectiveNextPos = pos + 1;
      State ns;
      if (effectiveNextPos >= posDepThreshold) {
        ns = computeNext(s, b, text, effectiveNextPos);
        if (ns == null) {
          return null; // budget exceeded
        }
      } else {
        ns = s.next[cls];
        if (ns == null) {
          ns = computeNext(s, b, text, effectiveNextPos);
          if (ns == null) {
            return null; // budget exceeded
          }
          s.next[cls] = ns;
        }
      }

      s = ns;
      if (s == deadState) {
        return new SearchResult(matched, matchEnd);
      }

      if (s.isMatch()) {
        if ((s.flags & FLAG_MATCH_BEFORE) != 0) {
          int endPos = pos;
          if (isRequiredEndMatch(endPos, needEndMatch, textLen, trailingTermStart)) {
            matched = true;
            matchEnd = endPos;
            if (!longest && canStopAtFirstMatch(s, text, endPos, needEndMatch)) {
              return new SearchResult(true, matchEnd);
            }
          }
        }
        if ((s.flags & FLAG_MATCH_BEFORE) == 0 || (s.flags & FLAG_MATCH_AFTER_DEFERRED) != 0) {
          int endPos = pos + 1;
          if (isRequiredEndMatch(endPos, needEndMatch, textLen, trailingTermStart)) {
            matched = true;
            matchEnd = endPos;
            if (!longest && canStopAtFirstMatch(s, text, endPos, needEndMatch)) {
              return new SearchResult(true, matchEnd);
            }
          }
        }
      }
      pos++;
    }

    // EOF transition (b = -1)
    if (pos == textLen) {
      int cls = numClasses - 1;
      State ns = s.next[cls];
      if (ns == null) {
        ns = computeNext(s, -1, text, textLen);
        if (ns == null) {
          return null;
        }
        s.next[cls] = ns;
      }
      s = ns;
      if (s != deadState && s.isMatch()) {
        int endPos = textLen;
        if (isRequiredEndMatch(endPos, needEndMatch, textLen, trailingTermStart)) {
          matched = true;
          matchEnd = endPos;
        }
      }
    }

    return new SearchResult(matched, matchEnd);
  }

  SearchResult doSearchReverse(
      byte[] text, int endPos, int startLimit, boolean anchored, boolean longest) {
    int textLen = text.length;
    boolean needEndMatch = prog.anchorEnd();

    State s = startState(text, endPos, anchored, true);
    if (s == null) {
      return null;
    }

    boolean matched = false;
    int matchStart = -1;

    if (s.isMatch()) {
      if (isRequiredEndMatch(textLen - endPos, needEndMatch, textLen, textLen)) {
        matched = true;
        matchStart = endPos;
        if (!longest) {
          return new SearchResult(true, endPos);
        }
      }
    }

    if (s == deadState) {
      return new SearchResult(matched, matchStart);
    }

    int posDepThreshold = positionDependentThreshold(text);

    int pos = endPos;
    while (pos >= startLimit) {
      int b;
      int prevPos;
      int cls;
      if (pos > 0) {
        b = text[pos - 1] & 0xFF;
        prevPos = pos - 1;
        cls = byteClassMap[b];
      } else {
        b = -1;
        prevPos = startLimit - 1;
        cls = numClasses - 1;
      }

      int effectivePrevPos = Math.max(prevPos, startLimit);
      State ns;
      if (b >= 0 && effectivePrevPos >= posDepThreshold) {
        ns = computeNext(s, b, text, effectivePrevPos);
        if (ns == null) {
          return null; // budget exceeded
        }
      } else {
        ns = s.next[cls];
        if (ns == null) {
          ns = computeNext(s, b, text, effectivePrevPos);
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
        if ((s.flags & FLAG_MATCH_BEFORE) == 0 || (s.flags & FLAG_MATCH_AFTER_DEFERRED) != 0) {
          int startPos = prevPos;
          if (startPos >= startLimit && (!needEndMatch || startPos == startLimit)) {
            matched = true;
            matchStart = startPos;
            if (!longest && !needEndMatch) {
              return new SearchResult(true, matchStart);
            }
          }
        } else {
          int startPos = pos;
          if (startPos >= startLimit && (!needEndMatch || startPos == startLimit)) {
            matched = true;
            matchStart = startPos;
            if (!longest && !needEndMatch) {
              return new SearchResult(true, matchStart);
            }
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

  private static boolean isRequiredEndMatch(
      int pos, boolean needEndMatch, int textLen, int trailingTermStart) {
    if (!needEndMatch) {
      return true;
    }
    return pos == textLen || pos == trailingTermStart;
  }

  private int positionDependentThreshold(byte[] text) {
    int len = text.length;
    if (prog.unixLines()) {
      return (len > 0 && text[len - 1] == '\n') ? len - 1 : len;
    }
    if (len >= 2 && text[len - 2] == '\r' && text[len - 1] == '\n') {
      return len - 2;
    }
    if (len > 0 && Nfa.isLineTerminator(text[len - 1] & 0xFF)) {
      return len - 1;
    }
    return len;
  }

  private int trailingLineStart(byte[] text) {
    return positionDependentThreshold(text);
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

  private boolean canStopAtFirstMatch(State s, byte[] text, int pos, boolean needEndMatch) {
    if (!needEndMatch) {
      return s.isHighestPriorityMatch;
    }
    int b = (pos >= text.length) ? -1 : (text[pos] & 0xFF);
    for (int id : s.insts) {
      Inst inst = prog.inst(id);
      switch (inst.opCode) {
        case InstOp.OP_MATCH -> {
          return true;
        }
        case InstOp.OP_CHAR_RANGE -> {
          if (b >= 0 && inst.lo <= b && b <= inst.hi) {
            return false;
          }
        }
        default -> {}
      }
    }
    return false;
  }

  private int emptyFlags(byte[] text, int pos) {
    int flags =
        Nfa.emptyFlags(
            text,
            pos,
            prog.unixLines(),
            false,
            0,
            0,
            false,
            0,
            text.length,
            text.length,
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
}
