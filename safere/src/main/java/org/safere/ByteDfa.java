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

    State(int[] insts, int flags, int numClasses) {
      this.insts = insts;
      this.flags = flags;
      this.next = new State[numClasses];
    }

    boolean isMatch() {
      return (flags & FLAG_MATCH) != 0;
    }
  }

  private final Prog prog;
  private final int[] boundaries;
  private final int numClasses;
  private final int[] byteClassMap;

  private final Map<StateKey, State> stateCache = new HashMap<>();
  private final State deadState;
  private State startState;
  private State startStateAnchored;

  private int stateCount;
  private final int maxStates;

  // Reusable search state buffers to avoid allocations in search calls
  private final int[] expandVisitedGen;
  private final int[] expandStack;
  private final int[] expandFrontier;
  private final int[] computeBuf;
  private int expandGeneration;

  private record StateKey(int[] insts, int flags) {
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof StateKey other)) return false;
      return flags == other.flags && Arrays.equals(insts, other.insts);
    }

    @Override
    public int hashCode() {
      return 31 * flags + Arrays.hashCode(insts);
    }
  }

  record Setup(int[] boundaries, int numClasses, int[] byteClassMap) {}

  static Setup buildSetup(Prog prog) {
    int[] boundaries = buildBoundaries(prog);
    int numClasses = boundaries.length + 1 + 1; // intervals + end-of-text
    int[] byteClassMap = buildByteClassMap(boundaries);
    return new Setup(boundaries, numClasses, byteClassMap);
  }

  ByteDfa(Prog prog, Setup setup) {
    this(prog, setup, DEFAULT_MAX_STATES);
  }

  ByteDfa(Prog prog, Setup setup, int maxStates) {
    this.prog = prog;
    this.boundaries = setup.boundaries;
    this.numClasses = setup.numClasses;
    this.byteClassMap = setup.byteClassMap;
    this.maxStates = maxStates;

    this.deadState = new State(new int[0], 0, numClasses);
    this.stateCache.put(new StateKey(deadState.insts, deadState.flags), deadState);
    this.stateCount = 1;

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

  private int classOf(int b) {
    if (b < 0) {
      return numClasses - 1; // end of text
    }
    return byteClassMap[b];
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
    s = new State(insts, flags, numClasses);
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

    for (int i = seedCount - 1; i >= 0; i--) {
      int id = seeds[i];
      stack[stackTop++] = id;
      visitedGen[id] = gen;
    }

    while (stackTop > 0) {
      int id = stack[--stackTop];
      Inst inst = prog.inst(id);

      switch (inst.opCode) {
        case InstOp.OP_ALT:
          if (visitedGen[inst.out] != gen) {
            stack[stackTop++] = inst.out;
            visitedGen[inst.out] = gen;
          }
          if (visitedGen[inst.out1] != gen) {
            stack[stackTop++] = inst.out1;
            visitedGen[inst.out1] = gen;
          }
          break;
        case InstOp.OP_NOP:
          if (visitedGen[inst.out] != gen) {
            stack[stackTop++] = inst.out;
            visitedGen[inst.out] = gen;
          }
          break;
        case InstOp.OP_CAPTURE:
          if (visitedGen[inst.out] != gen) {
            stack[stackTop++] = inst.out;
            visitedGen[inst.out] = gen;
          }
          break;
        case InstOp.OP_EMPTY_WIDTH:
          if ((inst.arg & ~emptyFlags) == 0) {
            if (visitedGen[inst.out] != gen) {
              stack[stackTop++] = inst.out;
              visitedGen[inst.out] = gen;
            }
          } else {
            frontier[frontierSize++] = id;
          }
          break;
        case InstOp.OP_CHAR_RANGE:
        case InstOp.OP_MATCH:
          frontier[frontierSize++] = id;
          break;
        default:
          break;
      }
    }

    int[] res = new int[frontierSize];
    System.arraycopy(frontier, 0, res, 0, frontierSize);
    Arrays.sort(res);
    return res;
  }

  private State computeNext(State s, int b, byte[] text, int nextPos) {
    int emptyFlags =
        Nfa.emptyFlags(
            text,
            nextPos,
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

    int frontierSize = 0;
    int[] buf = computeBuf;

    for (int id : s.insts) {
      Inst inst = prog.inst(id);
      if (inst.opCode == InstOp.OP_CHAR_RANGE) {
        if (inst.lo <= b && b <= inst.hi) {
          buf[frontierSize++] = inst.out;
        }
      }
    }

    if (frontierSize == 0) {
      return deadState;
    }

    int[] frontier = expand(buf, frontierSize, emptyFlags);
    int flags = 0;
    for (int id : frontier) {
      Inst inst = prog.inst(id);
      if (inst.opCode == InstOp.OP_MATCH) {
        flags |= FLAG_MATCH;
      }
    }

    if ((b & 0xFF) == '\n' || b == '\r') {
      // Just check ASCII word boundary
    }
    if (posIsWordChar(text, nextPos - 1)) {
      flags |= FLAG_LAST_WORD;
    }

    return cachedState(frontier, flags);
  }

  private boolean posIsWordChar(byte[] text, int pos) {
    if (pos < 0 || pos >= text.length) {
      return false;
    }
    return Nfa.isWordChar(text[pos] & 0xFF);
  }

  private State startState(byte[] text, int pos, boolean anchored) {
    if (anchored) {
      if (startStateAnchored != null) {
        return startStateAnchored;
      }
    } else {
      if (startState != null) {
        return startState;
      }
    }

    int emptyFlags =
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

    int start = anchored ? prog.start() : prog.startUnanchored();
    int[] frontier = expand(new int[] {start}, 1, emptyFlags);
    int flags = 0;
    for (int id : frontier) {
      if (prog.inst(id).opCode == InstOp.OP_MATCH) {
        flags |= FLAG_MATCH;
      }
    }
    if (posIsWordChar(text, pos - 1)) {
      flags |= FLAG_LAST_WORD;
    }

    State s = cachedState(frontier, flags);
    if (s != null) {
      if (anchored) {
        startStateAnchored = s;
      } else {
        startState = s;
      }
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
        if (!longest) {
          return new SearchResult(true, startPos);
        }
      }
    }

    if (s == deadState) {
      return new SearchResult(matched, matchEnd);
    }

    int pos = startPos;
    while (pos <= textLen) {
      int b;
      int nextPos;
      int cls;
      if (pos < textLen) {
        b = text[pos] & 0xFF;
        nextPos = pos + 1;
        cls = byteClassMap[b];
      } else {
        b = -1;
        nextPos = textLen + 1;
        cls = numClasses - 1;
      }

      State ns = s.next[cls];
      if (ns == null) {
        ns = computeNext(s, b, text, Math.min(nextPos, textLen));
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
        if (isRequiredEndMatch(endPos, needEndMatch, textLen, trailingTermStart)) {
          matched = true;
          matchEnd = endPos;
          if (!longest) {
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

  SearchResult doSearchReverse(
      byte[] text, int endPos, int startLimit, boolean anchored, boolean longest) {
    int textLen = text.length;
    boolean needEndMatch = prog.anchorEnd();

    int emptyFlags =
        Nfa.emptyFlags(
            text,
            endPos,
            prog.unixLines(),
            false,
            0,
            0,
            false,
            0,
            textLen,
            textLen,
            prog.hasWordBoundary(),
            prog.hasTextAnchor());

    if (prog.reversed()) {
      int rev =
          emptyFlags
              & ~(EmptyOp.BEGIN_TEXT
                  | EmptyOp.END_TEXT
                  | EmptyOp.DOLLAR_END
                  | EmptyOp.BEGIN_LINE
                  | EmptyOp.END_LINE);
      if ((emptyFlags & EmptyOp.BEGIN_TEXT) != 0) rev |= EmptyOp.END_TEXT;
      if ((emptyFlags & (EmptyOp.END_TEXT | EmptyOp.DOLLAR_END)) != 0) rev |= EmptyOp.BEGIN_TEXT;
      if ((emptyFlags & EmptyOp.BEGIN_LINE) != 0) rev |= EmptyOp.END_LINE;
      if ((emptyFlags & EmptyOp.END_LINE) != 0) rev |= EmptyOp.BEGIN_LINE;
      emptyFlags = rev;
    }

    int start = anchored ? prog.start() : prog.startUnanchored();
    int[] frontier = expand(new int[] {start}, 1, emptyFlags);
    int flags = 0;
    for (int id : frontier) {
      if (prog.inst(id).opCode == InstOp.OP_MATCH) {
        flags |= FLAG_MATCH;
      }
    }
    if (posIsWordChar(text, endPos)) {
      flags |= FLAG_LAST_WORD;
    }

    State s = cachedState(frontier, flags);
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

    int pos = endPos;
    while (pos > startLimit) {
      int b = text[pos - 1] & 0xFF;
      int cls = byteClassMap[b];

      State ns = s.next[cls];
      if (ns == null) {
        int nextPos = pos - 1;
        ns = computeNext(s, b, text, nextPos);
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
        int nextPos = pos - 1;
        if (isRequiredEndMatch(textLen - nextPos, needEndMatch, textLen, textLen)) {
          matched = true;
          matchStart = nextPos;
          if (!longest) {
            return new SearchResult(true, matchStart);
          }
        }
      }

      pos--;
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

  private static int trailingLineStart(byte[] text) {
    int len = text.length;
    if (len == 0) {
      return 0;
    }
    int last = text[len - 1] & 0xFF;
    if (last == '\n') {
      if (len > 1 && (text[len - 2] & 0xFF) == '\r') {
        return len - 2;
      }
      return len - 1;
    }
    if (last == '\r') {
      return len - 1;
    }
    return len;
  }
}
