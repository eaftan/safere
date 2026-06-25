// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Compiles a {@link Regexp} AST into a byte-oriented {@link Prog} (bytecode program) where
 * instructions match raw UTF-8 bytes (0-255) rather than Unicode code points.
 */
final class Utf8Compiler extends Walker<Utf8Compiler.Frag> {

  private static final int DEFAULT_MAX_INST = 1_000_000;

  private final Prog prog;
  private boolean failed;
  private boolean reversed;
  private final int maxInst;
  private int nextLoopReg;

  private Utf8Compiler() {
    this(DEFAULT_MAX_INST);
  }

  private Utf8Compiler(int maxInst) {
    this.prog = new Prog();
    this.maxInst = maxInst;
    int fail = prog.allocInst();
    prog.mutableInst(fail).initFail();
  }

  static Prog compile(Regexp re) {
    return compile(re, false, true);
  }

  static Prog compile(Regexp re, boolean reversed) {
    return compile(re, reversed, true);
  }

  private static Prog compile(Regexp re, boolean reversed, boolean includeCaptureDebugInfo) {
    Utf8Compiler c = new Utf8Compiler();
    c.reversed = reversed;
    int numCaptures = maxCapture(re) + 1;

    Regexp lowered = lowerCaptureRetention(re);
    if (lowered == null) {
      return null;
    }

    Regexp sre = Simplifier.simplify(lowered);
    if (sre == null) {
      return null;
    }

    boolean isAnchorStart = isAnchorStart(sre);
    Regexp stripped = stripAnchorStart(sre);
    boolean isAnchorEnd = isAnchorEnd(stripped);
    boolean isDollarEnd = isAnchorEnd && isDollarAnchorEnd(stripped);
    stripped = stripAnchorEnd(stripped);

    Frag all = c.walkExponential(stripped, Frag.NO_MATCH, 2 * c.maxInst);
    if (c.failed) {
      return null;
    }

    c.reversed = false;
    all = c.cat(all, c.match(0));
    if (c.failed) {
      return null;
    }

    c.prog.setReversed(reversed);
    if (reversed) {
      c.prog.setAnchorStart(isAnchorEnd);
      c.prog.setAnchorEnd(isAnchorStart);
    } else {
      c.prog.setAnchorStart(isAnchorStart);
      c.prog.setAnchorEnd(isAnchorEnd);
      c.prog.setDollarAnchorEnd(isDollarEnd);
    }

    c.prog.setStart(all.begin);

    if (!c.prog.anchorStart()) {
      all = c.cat(c.dotStar(), all);
    }
    c.prog.setStartUnanchored(all.begin);

    c.prog.freeze();

    c.prog.setNumCaptures(numCaptures);
    c.prog.setNumLoopRegs(c.nextLoopReg);
    if (includeCaptureDebugInfo && !reversed) {
      c.prog.setRequiresPikeNfaCaptureSemantics(requiresPikeNfaCaptureSemantics(re));
    }

    return c.prog;
  }

  private static int maxCapture(Regexp re) {
    int max = 0;
    ArrayDeque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      if (node.op == RegexpOp.CAPTURE && node.cap > max) {
        max = node.cap;
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(sub);
        }
      }
    }
    return max;
  }

  private static boolean requiresPikeNfaCaptureSemantics(Regexp re) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      if (node.op == RegexpOp.REPEAT
          && node.min >= 2
          && (node.max == -1 || node.max >= node.min)
          && hasUnboundedCaptureRepeat(node.sub())) {
        return true;
      }
      if (isRepeatWithRemainingCopies(node)
          && !hasAlternation(node.sub())
          && hasGreedyBoundedCaptureRepeat(node.sub())) {
        return true;
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(sub);
        }
      }
    }
    return false;
  }

  private static boolean isRepeatWithRemainingCopies(Regexp re) {
    return re.op == RegexpOp.STAR
        || re.op == RegexpOp.PLUS
        || (re.op == RegexpOp.REPEAT && (re.max == -1 || re.max >= 2));
  }

  private static boolean hasUnboundedCaptureRepeat(Regexp re) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      if ((node.op == RegexpOp.PLUS
              || (node.op == RegexpOp.REPEAT && node.min >= 1 && node.max == -1))
          && !node.nonGreedy()
          && hasCapture(node.sub())) {
        return true;
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(sub);
        }
      }
    }
    return false;
  }

  private static boolean hasGreedyBoundedCaptureRepeat(Regexp re) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      if (node.op == RegexpOp.REPEAT
          && node.max >= 2
          && node.max > node.min
          && !node.nonGreedy()
          && hasCapture(node.sub())) {
        return true;
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(sub);
        }
      }
    }
    return false;
  }

  private static boolean hasCapture(Regexp re) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      if (node.op == RegexpOp.CAPTURE && node.cap > 0) {
        return true;
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(sub);
        }
      }
    }
    return false;
  }

  private static boolean hasAlternation(Regexp re) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      if (node.op == RegexpOp.ALTERNATE) {
        return true;
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(sub);
        }
      }
    }
    return false;
  }

  private static Regexp lowerCaptureRetention(Regexp re) {
    return Compiler.lowerCaptureRetention(re);
  }

  private static boolean isAnchorStart(Regexp re) {
    return Compiler.isAnchorStart(re);
  }

  private static Regexp stripAnchorStart(Regexp re) {
    return Compiler.stripAnchorStart(re);
  }

  private static boolean isAnchorEnd(Regexp re) {
    return Compiler.isAnchorEnd(re);
  }

  private static boolean isDollarAnchorEnd(Regexp re) {
    return Compiler.isDollarAnchorEnd(re);
  }

  private static Regexp stripAnchorEnd(Regexp re) {
    return Compiler.stripAnchorEnd(re);
  }

  // ---------------------------------------------------------------------------
  // PatchList & Frag
  // ---------------------------------------------------------------------------

  record PatchList(int head, int tail) {
    static final PatchList EMPTY = new PatchList(0, 0);

    static PatchList mk(int encoded) {
      return new PatchList(encoded, encoded);
    }

    static void patch(Prog prog, PatchList l, int target) {
      int current = l.head;
      while (current != 0) {
        Inst ip = prog.mutableInst(current >> 1);
        if ((current & 1) != 0) {
          current = ip.out1;
          ip.out1 = target;
        } else {
          current = ip.out;
          ip.out = target;
        }
      }
    }

    static PatchList append(Prog prog, PatchList l1, PatchList l2) {
      if (l1.head == 0) {
        return l2;
      }
      if (l2.head == 0) {
        return l1;
      }
      Inst ip = prog.mutableInst(l1.tail >> 1);
      if ((l1.tail & 1) != 0) {
        ip.out1 = l2.head;
      } else {
        ip.out = l2.head;
      }
      return new PatchList(l1.head, l2.tail);
    }
  }

  record Frag(int begin, PatchList end, boolean nullable) {
    static final Frag NO_MATCH = new Frag(0, PatchList.EMPTY, false);
  }

  private static boolean isNoMatch(Frag f) {
    return f.begin == 0;
  }

  private int allocInst() {
    if (failed || prog.size() >= maxInst) {
      failed = true;
      return -1;
    }
    return prog.allocInst();
  }

  private Frag cat(Frag a, Frag b) {
    if (isNoMatch(a) || isNoMatch(b)) {
      return Frag.NO_MATCH;
    }
    Inst begin = prog.mutableInst(a.begin);
    if (begin.op == InstOp.NOP && a.end.head == (a.begin << 1) && begin.out == 0) {
      PatchList.patch(prog, a.end, b.begin);
      return b;
    }
    if (reversed) {
      PatchList.patch(prog, b.end, a.begin);
      return new Frag(b.begin, a.end, b.nullable && a.nullable);
    }
    PatchList.patch(prog, a.end, b.begin);
    return new Frag(a.begin, b.end, a.nullable && b.nullable);
  }

  private Frag alt(Frag a, Frag b) {
    if (isNoMatch(a)) {
      return b;
    }
    if (isNoMatch(b)) {
      return a;
    }
    int id = allocInst();
    if (id < 0) {
      return Frag.NO_MATCH;
    }
    prog.mutableInst(id).initAlt(a.begin, b.begin);
    return new Frag(id, PatchList.append(prog, a.end, b.end), a.nullable || b.nullable);
  }

  private Frag plus(Frag a, boolean nongreedy) {
    if (a.nullable) {
      int pcId = allocInst();
      if (pcId < 0) {
        return Frag.NO_MATCH;
      }
      int loopReg = nextLoopReg++;
      prog.mutableInst(pcId).initProgressCheck(loopReg, a.begin, 0, nongreedy);
      PatchList pl = PatchList.mk((pcId << 1) | 1);
      PatchList.patch(prog, a.end, pcId);
      return new Frag(pcId, pl, true);
    }
    int id = allocInst();
    if (id < 0) {
      return Frag.NO_MATCH;
    }
    PatchList pl;
    if (nongreedy) {
      prog.mutableInst(id).initAlt(0, a.begin);
      pl = PatchList.mk(id << 1);
    } else {
      prog.mutableInst(id).initAlt(a.begin, 0);
      pl = PatchList.mk((id << 1) | 1);
    }
    PatchList.patch(prog, a.end, id);
    return new Frag(a.begin, pl, a.nullable);
  }

  private Frag star(Frag a, boolean nongreedy) {
    if (a.nullable) {
      return quest(plus(a, nongreedy), nongreedy);
    }
    int id = allocInst();
    if (id < 0) {
      return Frag.NO_MATCH;
    }
    PatchList pl;
    if (nongreedy) {
      prog.mutableInst(id).initAlt(0, a.begin);
      pl = PatchList.mk(id << 1);
    } else {
      prog.mutableInst(id).initAlt(a.begin, 0);
      pl = PatchList.mk((id << 1) | 1);
    }
    PatchList.patch(prog, a.end, id);
    return new Frag(id, pl, true);
  }

  private Frag quest(Frag a, boolean nongreedy) {
    if (isNoMatch(a)) {
      return nop();
    }
    int id = allocInst();
    if (id < 0) {
      return Frag.NO_MATCH;
    }
    PatchList pl;
    if (nongreedy) {
      prog.mutableInst(id).initAlt(0, a.begin);
      pl = PatchList.mk(id << 1);
    } else {
      prog.mutableInst(id).initAlt(a.begin, 0);
      pl = PatchList.mk((id << 1) | 1);
    }
    return new Frag(id, PatchList.append(prog, pl, a.end), true);
  }

  private Frag byteRange(int lo, int hi, boolean foldCase) {
    int id = allocInst();
    if (id < 0) {
      return Frag.NO_MATCH;
    }
    prog.mutableInst(id).initCharRange(lo, hi, foldCase, 0);
    return new Frag(id, PatchList.mk(id << 1), false);
  }

  private Frag nop() {
    int id = allocInst();
    if (id < 0) {
      return Frag.NO_MATCH;
    }
    prog.mutableInst(id).initNop(0);
    return new Frag(id, PatchList.mk(id << 1), true);
  }

  private Frag match(int matchId) {
    int id = allocInst();
    if (id < 0) {
      return Frag.NO_MATCH;
    }
    prog.mutableInst(id).initMatch(matchId);
    return new Frag(id, PatchList.EMPTY, false);
  }

  private Frag emptyWidth(int emptyFlags) {
    int id = allocInst();
    if (id < 0) {
      return Frag.NO_MATCH;
    }
    prog.mutableInst(id).initEmptyWidth(emptyFlags, 0);
    return new Frag(id, PatchList.mk(id << 1), true);
  }

  private Frag capture(Frag a, int cap) {
    if (isNoMatch(a)) {
      return Frag.NO_MATCH;
    }
    int id = allocInst();
    if (id < 0) {
      return Frag.NO_MATCH;
    }
    int id2 = allocInst();
    if (id2 < 0) {
      return Frag.NO_MATCH;
    }
    prog.mutableInst(id).initCapture(2 * cap, a.begin);
    prog.mutableInst(id2).initCapture(2 * cap + 1, 0);
    PatchList.patch(prog, a.end, id2);
    return new Frag(id, PatchList.mk(id2 << 1), a.nullable);
  }

  private void suppressCapture(Frag a, int cap) {
    if (isNoMatch(a) || a.end.head != a.end.tail || (a.end.head & 1) != 0) {
      return;
    }
    Inst start = prog.mutableInst(a.begin);
    int endId = a.end.head >> 1;
    Inst end = prog.mutableInst(endId);
    if (start.op == InstOp.CAPTURE
        && start.arg == 2 * cap
        && end.op == InstOp.CAPTURE
        && end.arg == 2 * cap + 1) {
      start.initNop(start.out);
      end.initNop(end.out);
    }
  }

  private static boolean isDirectRepeatedPureEmptyCapture(Regexp re) {
    return Compiler.isDirectRepeatedPureEmptyCapture(re);
  }

  private Frag literal(int cp, int flags) {
    boolean foldCase = (flags & ParseFlags.FOLD_CASE) != 0;
    if (foldCase && (flags & ParseFlags.UNICODE_CASE) == 0) {
      if (cp < 128) {
        return asciiFoldedLiteral(cp);
      }
      foldCase = false;
    }
    return addRuneRangeUTF8(cp, cp, foldCase);
  }

  private Frag asciiFoldedLiteral(int rune) {
    int lower = UnicodeCaseFolding.asciiFoldRune(rune);
    int upper = lower - ('a' - 'A');
    Frag a = byteRange(upper, upper, false);
    Frag b = byteRange(lower, lower, false);
    return alt(a, b);
  }

  private Frag anyCodePoint() {
    return addRuneRangeUTF8(0, Utils.MAX_RUNE, false);
  }

  private Frag dotStar() {
    return star(anyCodePoint(), true);
  }

  // ---------------------------------------------------------------------------
  // UTF-8 Range Decomposition Compiler Logic
  // ---------------------------------------------------------------------------

  private Frag addRuneRangeUTF8(int lo, int hi, boolean foldCase) {
    if (lo > hi) {
      return Frag.NO_MATCH;
    }

    // Split range into same-length UTF-8 sequence ranges.
    for (int i = 1; i < 4; i++) {
      int max = maxRune(i);
      if (lo <= max && max < hi) {
        Frag first = addRuneRangeUTF8(lo, max, foldCase);
        Frag second = addRuneRangeUTF8(max + 1, hi, foldCase);
        return alt(first, second);
      }
    }

    // ASCII range is always a single byte range suffix.
    if (hi < 128) {
      return byteRange(lo, hi, foldCase);
    }

    // Split range into sections that agree on leading bytes.
    for (int i = 1; i < 4; i++) {
      int m = (1 << (6 * i)) - 1; // last i bytes of a UTF-8 sequence
      if ((lo & ~m) != (hi & ~m)) {
        if ((lo & m) != 0) {
          Frag first = addRuneRangeUTF8(lo, lo | m, foldCase);
          Frag second = addRuneRangeUTF8((lo | m) + 1, hi, foldCase);
          return alt(first, second);
        }
        if ((hi & m) != m) {
          Frag first = addRuneRangeUTF8(lo, (hi & ~m) - 1, foldCase);
          Frag second = addRuneRangeUTF8(hi & ~m, hi, foldCase);
          return alt(first, second);
        }
      }
    }

    // Generate byte range sequence concatenation.
    byte[] ulo = new String(Character.toChars(lo)).getBytes(StandardCharsets.UTF_8);
    byte[] uhi = new String(Character.toChars(hi)).getBytes(StandardCharsets.UTF_8);

    Frag path = byteRange(ulo[0] & 0xFF, uhi[0] & 0xFF, false);
    for (int i = 1; i < ulo.length; i++) {
      path = cat(path, byteRange(ulo[i] & 0xFF, uhi[i] & 0xFF, false));
    }
    return path;
  }

  private static int maxRune(int bytes) {
    return switch (bytes) {
      case 1 -> 0x7F;
      case 2 -> 0x7FF;
      case 3 -> 0xFFFF;
      default -> 0x10FFFF;
    };
  }

  // ---------------------------------------------------------------------------
  // Walker overrides
  // ---------------------------------------------------------------------------

  @Override
  protected Frag preVisit(Regexp re, Frag parentArg, boolean[] stop) {
    if (failed) {
      stop[0] = true;
    }
    return Frag.NO_MATCH;
  }

  @Override
  protected Frag postVisit(Regexp re, Frag parentArg, Frag preArg, List<Frag> childArgs) {
    if (failed) {
      return Frag.NO_MATCH;
    }

    return switch (re.op) {
      case REPEAT -> {
        failed = true;
        yield Frag.NO_MATCH;
      }
      case NO_MATCH -> Frag.NO_MATCH;
      case EMPTY_MATCH -> nop();
      case HAVE_MATCH -> match(re.matchId);
      case CONCAT -> {
        Frag f = childArgs.get(0);
        for (int i = 1; i < childArgs.size(); i++) {
          f = cat(f, childArgs.get(i));
        }
        yield f;
      }
      case ALTERNATE -> {
        Frag f = childArgs.get(0);
        for (int i = 1; i < childArgs.size(); i++) {
          f = alt(f, childArgs.get(i));
        }
        yield f;
      }
      case NON_CAPTURE -> childArgs.get(0);
      case STAR -> {
        Frag child = childArgs.get(0);
        if (isDirectRepeatedPureEmptyCapture(re.sub())) {
          suppressCapture(child, re.sub().cap);
        }
        yield star(child, re.nonGreedy());
      }
      case PLUS -> plus(childArgs.get(0), re.nonGreedy());
      case QUEST -> quest(childArgs.get(0), re.nonGreedy());
      case LITERAL -> literal(re.rune, re.flags);
      case LITERAL_STRING -> {
        if (re.runes == null || re.runes.length == 0) {
          yield nop();
        }
        Frag f = literal(re.runes[0], re.flags);
        for (int i = 1; i < re.runes.length; i++) {
          f = cat(f, literal(re.runes[i], re.flags));
        }
        yield f;
      }
      case ANY_CHAR -> anyCodePoint();
      case CHAR_CLASS -> compileCharClass(re);
      case CAPTURE -> {
        if (re.cap < 0) {
          yield childArgs.get(0);
        }
        yield capture(childArgs.get(0), re.cap);
      }
      case BEGIN_LINE -> emptyWidth(reversed ? EmptyOp.END_LINE : EmptyOp.BEGIN_LINE);
      case END_LINE -> emptyWidth(reversed ? EmptyOp.BEGIN_LINE : EmptyOp.END_LINE);
      case BEGIN_TEXT -> emptyWidth(reversed ? EmptyOp.END_TEXT : EmptyOp.BEGIN_TEXT);
      case END_TEXT -> {
        if (!reversed && (re.flags & ParseFlags.WAS_DOLLAR) != 0) {
          yield emptyWidth(EmptyOp.DOLLAR_END);
        }
        yield emptyWidth(reversed ? EmptyOp.BEGIN_TEXT : EmptyOp.END_TEXT);
      }
      case WORD_BOUNDARY -> {
        if ((re.flags & ParseFlags.UNICODE_CHAR_CLASS) != 0) {
          yield emptyWidth(EmptyOp.UNICODE_WORD_BOUNDARY);
        }
        yield emptyWidth(EmptyOp.WORD_BOUNDARY);
      }
      case NO_WORD_BOUNDARY -> {
        if ((re.flags & ParseFlags.UNICODE_CHAR_CLASS) != 0) {
          yield emptyWidth(EmptyOp.UNICODE_NON_WORD_BOUNDARY);
        }
        yield emptyWidth(EmptyOp.NON_WORD_BOUNDARY);
      }
      case GRAPHEME_CLUSTER_BOUNDARY -> emptyWidth(EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY);
      default -> {
        failed = true;
        yield Frag.NO_MATCH;
      }
    };
  }

  @Override
  protected Frag shortVisit(Regexp re, Frag parentArg) {
    failed = true;
    return Frag.NO_MATCH;
  }

  private Frag compileCharClass(Regexp re) {
    CharClass cc = re.charClass;
    if (cc == null || cc.isEmpty()) {
      return Frag.NO_MATCH;
    }
    Frag all = Frag.NO_MATCH;
    for (int i = 0; i < cc.numRanges(); i++) {
      Frag path = addRuneRangeUTF8(cc.lo(i), cc.hi(i), false);
      all = alt(all, path);
    }
    return all;
  }
}
