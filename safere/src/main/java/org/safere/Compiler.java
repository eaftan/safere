// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Compiles a {@link Regexp} AST into a {@link Prog} (bytecode program) via Thompson NFA
 * construction. Each node in the AST is compiled into a {@link Frag} (instruction fragment) and
 * fragments are combined using standard NFA construction rules.
 *
 * <p>This is a port of RE2's {@code compile.cc}, adapted for Java's Unicode code point model
 * (instead of UTF-8 byte ranges).
 *
 * <p>Usage:
 *
 * <pre>
 *   Regexp re = Parser.parse("a(b|c)*d", flags);
 *   Prog prog = Compiler.compile(re);
 * </pre>
 */
final class Compiler extends Walker<Compiler.Frag> {

  /** Maximum number of instructions allowed by default. */
  private static final int DEFAULT_MAX_INST = 100_000;


  private final Prog prog;
  private boolean failed;
  private boolean reversed;
  private final int maxInst;
  private int nextLoopReg;

  private Compiler() {
    this(DEFAULT_MAX_INST);
  }

  private Compiler(int maxInst) {
    this.prog = new Prog();
    this.maxInst = maxInst;
    // Instruction 0 is always the fail instruction (sentinel for PatchList).
    int fail = prog.allocInst();
    prog.mutableInst(fail).initFail();
  }

  /**
   * Compiles the given regexp into a program.
   *
   * @param re the regexp to compile
   * @return the compiled program, or null if compilation fails
   */
  static Prog compile(Regexp re) {
    return compile(re, false, true);
  }

  /**
   * Compiles the given regexp into a program.
   *
   * @param re the regexp to compile
   * @param reversed if true, compile for backward matching
   * @return the compiled program, or null if compilation fails
   */
  static Prog compile(Regexp re, boolean reversed) {
    return compile(re, reversed, true);
  }

  private static Prog compile(Regexp re, boolean reversed, boolean includeCaptureDebugInfo) {
    Compiler c = new Compiler();
    c.reversed = reversed;
    int numCaptures = maxCapture(re) + 1;

    // Simplify to remove REPEAT, complex char classes, etc.
    Regexp sre = Simplifier.simplify(re);
    if (sre == null) {
      return null;
    }

    // Detect anchoring, stripping the anchor nodes.
    boolean isAnchorStart = isAnchorStart(sre);
    Regexp stripped = stripAnchorStart(sre);
    boolean isAnchorEnd = isAnchorEnd(stripped);
    boolean isDollarEnd = isAnchorEnd && isDollarAnchorEnd(stripped);
    stripped = stripAnchorEnd(stripped);

    // Walk the AST to produce fragments.
    Frag all = c.walkExponential(stripped, Frag.NO_MATCH, 2 * c.maxInst);
    if (c.failed) {
      return null;
    }

    // Append Match(0).
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
      // Prepend .*? loop for unanchored matching.
      all = c.cat(c.dotStar(), all);
    }
    c.prog.setStartUnanchored(all.begin);

    // Freeze the instruction list into a flat array for fast indexed access.
    c.prog.freeze();

    c.prog.setNumCaptures(numCaptures);
    c.prog.setNumLoopRegs(c.nextLoopReg);
    if (includeCaptureDebugInfo && !reversed) {
      c.prog.setRetainedRepeatCaptures(extractRetainedRepeatCaptures(re));
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

  private static List<Prog.RetainedRepeatCapture> extractRetainedRepeatCaptures(Regexp re) {
    List<Prog.RetainedRepeatCapture> retained = new ArrayList<>();
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      if (node.op == RegexpOp.REPEAT && node.min >= 2 && (node.max == -1 || node.max >= node.min)
          && hasUnboundedCaptureRepeat(node.sub())) {
        Prog repeatProg = compile(node, false, false);
        Prog firstProg = compile(node.sub(), false, false);
        Prog restProg = compile(repeatCopies(node.sub(), node.min - 1, node.flags), false, false);
        if (repeatProg != null && firstProg != null && restProg != null) {
          retained.add(new Prog.RetainedRepeatCapture(
              repeatProg, firstProg, restProg, node.min * minRequiredCodeUnits(node.sub()),
              minRequiredCodeUnits(node.sub()), groupsInsideNestedQuantifier(node.sub())));
        }
      }
      if (isRepeatWithRemainingCopies(node)
          && !hasAlternation(node.sub())
          && hasGreedyBoundedCaptureRepeat(node.sub())) {
        Prog repeatProg = compile(node, false, false);
        Prog firstProg = compile(node.sub(), false, false);
        Prog restProg = compile(repeatAfterFirstCopy(node), false, false);
        if (repeatProg != null && firstProg != null && restProg != null) {
          retained.add(new Prog.RetainedRepeatCapture(
              repeatProg, firstProg, restProg, minRequiredCodeUnits(node),
              minRequiredCodeUnits(node.sub()), groupsInsideNestedQuantifier(node.sub())));
        }
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(sub);
        }
      }
    }
    return retained;
  }

  private static boolean isRepeatWithRemainingCopies(Regexp re) {
    return re.op == RegexpOp.STAR
        || re.op == RegexpOp.PLUS
        || (re.op == RegexpOp.REPEAT && (re.max == -1 || re.max >= 2));
  }

  private static Regexp repeatAfterFirstCopy(Regexp repeat) {
    return switch (repeat.op) {
      case STAR, PLUS -> Regexp.star(repeat.sub(), repeat.flags);
      case REPEAT -> Regexp.repeat(
          repeat.sub(), repeat.flags, Math.max(0, repeat.min - 1),
          repeat.max == -1 ? -1 : repeat.max - 1);
      default -> Regexp.emptyMatch(repeat.flags);
    };
  }

  private static Regexp repeatCopies(Regexp sub, int copies, int flags) {
    if (copies == 1) {
      return sub;
    }
    List<Regexp> subs = new ArrayList<>(copies);
    for (int i = 0; i < copies; i++) {
      subs.add(sub);
    }
    return Regexp.concat(subs, flags);
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
      if (node.op == RegexpOp.REPEAT && node.max >= 2 && node.max > node.min && !node.nonGreedy()
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

  private static boolean[] groupsInsideNestedQuantifier(Regexp re) {
    int ncap = maxCapture(re) + 1;
    boolean[] groups = new boolean[ncap];
    Deque<Node> stack = new ArrayDeque<>();
    stack.push(new Node(re, false));
    while (!stack.isEmpty()) {
      Node current = stack.pop();
      Regexp node = current.re;
      boolean inQuantifier = current.inQuantifier;
      boolean childInQuantifier = inQuantifier || isCaptureRetainingQuantifier(node);
      if (inQuantifier && node.op == RegexpOp.CAPTURE && node.cap > 0
          && node.cap < groups.length) {
        groups[node.cap] = true;
      }
      if (node.subs != null) {
        for (Regexp sub : node.subs) {
          stack.push(new Node(sub, childInQuantifier));
        }
      }
    }
    return groups;
  }

  private static boolean isCaptureRetainingQuantifier(Regexp re) {
    return (re.op == RegexpOp.PLUS
            || (re.op == RegexpOp.REPEAT && (re.max > 1 || (re.max == -1 && re.min >= 1))))
        && !re.nonGreedy();
  }

  private record Node(Regexp re, boolean inQuantifier) {}

  private static int minRequiredCodeUnits(Regexp re) {
    return new MinRequiredCodeUnitsWalker().walk(re, 0);
  }

  private static final class MinRequiredCodeUnitsWalker extends Walker<Integer> {

    @Override
    protected Integer shortVisit(Regexp re, Integer parentArg) {
      return 0;
    }

    @Override
    protected Integer postVisit(
        Regexp re, Integer parentArg, Integer preArg, List<Integer> childArgs) {
      return switch (re.op) {
        case NO_MATCH, EMPTY_MATCH, BEGIN_LINE, END_LINE, BEGIN_TEXT, END_TEXT, WORD_BOUNDARY,
            NO_WORD_BOUNDARY -> 0;
        case LITERAL, ANY_CHAR, CHAR_CLASS -> 1;
        case LITERAL_STRING -> re.runes == null ? 0 : re.runes.length;
        case CAPTURE, PLUS -> childArgs.isEmpty() ? 0 : childArgs.get(0);
        case STAR, QUEST -> 0;
        case REPEAT -> re.min * (childArgs.isEmpty() ? 0 : childArgs.get(0));
        case CONCAT -> {
          int min = 0;
          for (int childMin : childArgs) {
            min += childMin;
          }
          yield min;
        }
        case ALTERNATE -> {
          int min = Integer.MAX_VALUE;
          for (int childMin : childArgs) {
            min = Math.min(min, childMin);
          }
          yield min == Integer.MAX_VALUE ? 0 : min;
        }
        default -> 0;
      };
    }
  }

  // ---------------------------------------------------------------------------
  // Anchor detection — approximate, like RE2's IsAnchorStart/IsAnchorEnd
  // ---------------------------------------------------------------------------

  private static boolean isAnchorStart(Regexp re) {
    return isAnchorStartImpl(re, 0);
  }

  private static boolean isAnchorStartImpl(Regexp re, int depth) {
    if (re == null || depth >= 4) {
      return false;
    }
    return switch (re.op) {
      case BEGIN_TEXT -> true;
      case CONCAT -> re.nsub() > 0 && isAnchorStartImpl(re.subs.getFirst(), depth + 1);
      case CAPTURE -> isAnchorStartImpl(re.sub(), depth + 1);
      default -> false;
    };
  }

  private static Regexp stripAnchorStart(Regexp re) {
    return stripAnchorStartImpl(re, 0);
  }

  private static Regexp stripAnchorStartImpl(Regexp re, int depth) {
    if (re == null || depth >= 4) {
      return re;
    }
    return switch (re.op) {
      case BEGIN_TEXT -> Regexp.emptyMatch(re.flags);
      case CONCAT -> {
        if (re.nsub() > 0 && isAnchorStartImpl(re.subs.getFirst(), depth + 1)) {
          List<Regexp> newSubs = new ArrayList<>(re.subs);
          newSubs.set(0, stripAnchorStartImpl(re.subs.getFirst(), depth + 1));
          yield Regexp.concat(newSubs, re.flags);
        }
        yield re;
      }
      case CAPTURE -> {
        if (isAnchorStartImpl(re.sub(), depth + 1)) {
          Regexp capture = Regexp.capture(
              stripAnchorStartImpl(re.sub(), depth + 1), re.flags, re.cap, re.name);
          capture.sourceNonCapturingGroup = re.sourceNonCapturingGroup;
          yield capture;
        }
        yield re;
      }
      default -> re;
    };
  }

  private static boolean isAnchorEnd(Regexp re) {
    return isAnchorEndImpl(re, 0);
  }

  /**
   * Returns true if the trailing end anchor is {@code $} (WAS_DOLLAR), meaning the match may
   * end before a trailing newline. Must only be called when {@link #isAnchorEnd} is true.
   */
  private static boolean isDollarAnchorEnd(Regexp re) {
    return isDollarAnchorEndImpl(re, 0);
  }

  private static boolean isDollarAnchorEndImpl(Regexp re, int depth) {
    if (re == null || depth >= 4) {
      return false;
    }
    return switch (re.op) {
      case END_TEXT -> (re.flags & ParseFlags.WAS_DOLLAR) != 0;
      case CONCAT -> re.nsub() > 0
          && isDollarAnchorEndImpl(re.subs.get(re.nsub() - 1), depth + 1);
      case CAPTURE -> isDollarAnchorEndImpl(re.sub(), depth + 1);
      default -> false;
    };
  }

  private static boolean isAnchorEndImpl(Regexp re, int depth) {
    if (re == null || depth >= 4) {
      return false;
    }
    return switch (re.op) {
      case END_TEXT -> true;
      case CONCAT -> re.nsub() > 0 && isAnchorEndImpl(re.subs.get(re.nsub() - 1), depth + 1);
      case CAPTURE -> isAnchorEndImpl(re.sub(), depth + 1);
      default -> false;
    };
  }

  private static Regexp stripAnchorEnd(Regexp re) {
    return stripAnchorEndImpl(re, 0);
  }

  private static Regexp stripAnchorEndImpl(Regexp re, int depth) {
    if (re == null || depth >= 4) {
      return re;
    }
    return switch (re.op) {
      case END_TEXT -> Regexp.emptyMatch(re.flags);
      case CONCAT -> {
        if (re.nsub() > 0 && isAnchorEndImpl(re.subs.get(re.nsub() - 1), depth + 1)) {
          List<Regexp> newSubs = new ArrayList<>(re.subs);
          int last = re.nsub() - 1;
          newSubs.set(last, stripAnchorEndImpl(re.subs.get(last), depth + 1));
          yield Regexp.concat(newSubs, re.flags);
        }
        yield re;
      }
      case CAPTURE -> {
        if (isAnchorEndImpl(re.sub(), depth + 1)) {
          Regexp capture = Regexp.capture(
              stripAnchorEndImpl(re.sub(), depth + 1), re.flags, re.cap, re.name);
          capture.sourceNonCapturingGroup = re.sourceNonCapturingGroup;
          yield capture;
        }
        yield re;
      }
      default -> re;
    };
  }

  // ---------------------------------------------------------------------------
  // PatchList — linked list threaded through unused instruction out fields
  // ---------------------------------------------------------------------------

  /**
   * A patch list is a linked list of instruction output fields that need to be filled in
   * ("patched") to point to the next instruction. The list is threaded through the unused out/out1
   * fields of the instructions themselves.
   *
   * <p>Encoding: {@code (instIndex << 1)} means patch {@code inst.out}; {@code (instIndex << 1) |
   * 1} means patch {@code inst.out1}.
   */
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

  // ---------------------------------------------------------------------------
  // Frag — compiled program fragment
  // ---------------------------------------------------------------------------

  /**
   * A compiled program fragment with a beginning instruction, a patch list of unfilled outputs, and
   * a nullable flag.
   */
  record Frag(int begin, PatchList end, boolean nullable) {
    static final Frag NO_MATCH = new Frag(0, PatchList.EMPTY, false);
  }

  private static boolean isNoMatch(Frag f) {
    return f.begin == 0;
  }

  // ---------------------------------------------------------------------------
  // Fragment combinators
  // ---------------------------------------------------------------------------

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

    // Elide no-op.
    Inst begin = prog.mutableInst(a.begin);
    if (begin.op == InstOp.NOP
        && a.end.head == (a.begin << 1)
        && begin.out == 0) {
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
    return new Frag(
        id,
        PatchList.append(prog, a.end, b.end),
        a.nullable || b.nullable);
  }

  private Frag plus(Frag a, boolean nongreedy) {
    // When the body is nullable (can match zero characters), insert a PROGRESS_CHECK at the
    // loop entry to implement the JDK "zero-width match breaks repetition" rule.
    //
    // Structure: pcId: PROGRESS_CHECK(body, exit) where body end loops back to pcId.
    // On progress, PROGRESS_CHECK acts like ALT(body, exit) with greediness preference.
    // On zero-width, PROGRESS_CHECK forces exit only (terminates the loop).
    if (a.nullable) {
      int pcId = allocInst();
      if (pcId < 0) {
        return Frag.NO_MATCH;
      }
      int loopReg = nextLoopReg++;
      prog.mutableInst(pcId).initProgressCheck(loopReg, a.begin, 0, nongreedy);
      PatchList pl = PatchList.mk((pcId << 1) | 1);  // patch out1 (exit)

      // Patch the body end to loop back to pcId.
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

  private Frag charRange(int lo, int hi, boolean foldCase) {
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
    // Need two instructions: one for capture-start, one for capture-end.
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
    if (start.op == InstOp.CAPTURE && start.arg == 2 * cap
        && end.op == InstOp.CAPTURE && end.arg == 2 * cap + 1) {
      start.initNop(start.out);
      end.initNop(end.out);
    }
  }

  private static boolean isDirectRepeatedPureEmptyCapture(Regexp re) {
    return re.op == RegexpOp.CAPTURE
        && re.cap > 0
        && !re.sourceNonCapturingGroup
        && isPureEmpty(re.sub());
  }

  private static boolean isPureEmpty(Regexp re) {
    return switch (re.op) {
      case EMPTY_MATCH, BEGIN_LINE, END_LINE, BEGIN_TEXT, END_TEXT, WORD_BOUNDARY,
           NO_WORD_BOUNDARY -> true;
      case CAPTURE -> isPureEmpty(re.sub());
      case CONCAT -> {
        if (re.subs == null) {
          yield true;
        }
        boolean pure = true;
        for (Regexp sub : re.subs) {
          if (!isPureEmpty(sub)) {
            pure = false;
            break;
          }
        }
        yield pure;
      }
      default -> false;
    };
  }

  private Frag literal(int rune, boolean foldCase) {
    // In our code-point model, a literal is just a single CHAR_RANGE.
    return charRange(rune, rune, foldCase);
  }

  /**
   * Compiles an "any code point" fragment — matches all valid Unicode code points except
   * surrogates. This produces [0-0xD7FF] | [0xE000-0x10FFFF].
   */
  private Frag anyCodePoint() {
    Frag lo = charRange(0, Utils.MIN_SURROGATE - 1, false);
    Frag hi = charRange(Utils.MAX_SURROGATE + 1, Utils.MAX_RUNE, false);
    return alt(lo, hi);
  }

  /**
   * Creates a {@code .*?} fragment for unanchored matching. The DotStar loops over any code point
   * (non-greedy).
   */
  private Frag dotStar() {
    return star(anyCodePoint(), true);
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
  protected Frag postVisit(
      Regexp re, Frag parentArg, Frag preArg, List<Frag> childArgs) {
    if (failed) {
      return Frag.NO_MATCH;
    }

    return switch (re.op) {
      case REPEAT -> {
        // Should have been simplified away.
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

      case STAR -> {
        Frag child = childArgs.get(0);
        if (isDirectRepeatedPureEmptyCapture(re.sub())) {
          suppressCapture(child, re.sub().cap);
        }
        yield star(child, re.nonGreedy());
      }

      case PLUS -> plus(childArgs.get(0), re.nonGreedy());

      case QUEST -> quest(childArgs.get(0), re.nonGreedy());

      case LITERAL -> literal(re.rune, re.foldCase());

      case LITERAL_STRING -> {
        if (re.runes == null || re.runes.length == 0) {
          yield nop();
        }
        Frag f = literal(re.runes[0], re.foldCase());
        for (int i = 1; i < re.runes.length; i++) {
          f = cat(f, literal(re.runes[i], re.foldCase()));
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
          // $ (not \z): also matches before trailing \n, matching JDK behavior.
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

  // ---------------------------------------------------------------------------
  // Character class compilation
  // ---------------------------------------------------------------------------

  private Frag compileCharClass(Regexp re) {
    CharClass cc = re.charClass;
    if (cc == null || cc.isEmpty()) {
      return Frag.NO_MATCH;
    }

    int numRanges = cc.numRanges();

    // Single range: use a simple CHAR_RANGE instruction (no ALT overhead).
    if (numRanges == 1) {
      return charRange(cc.lo(0), cc.hi(0), false);
    }

    // Multi-range: emit a single CHAR_CLASS instruction with all ranges and an ASCII bitmap.
    int[] ranges = new int[numRanges * 2];
    for (int i = 0; i < numRanges; i++) {
      ranges[i * 2] = cc.lo(i);
      ranges[i * 2 + 1] = cc.hi(i);
    }
    int id = allocInst();
    if (id < 0) {
      return Frag.NO_MATCH;
    }
    prog.mutableInst(id).initCharClass(0, ranges);
    return new Frag(id, PatchList.mk(id << 1), false);
  }
}
