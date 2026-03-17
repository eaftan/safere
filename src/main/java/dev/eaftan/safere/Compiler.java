// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

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

  private static final int MAX_UNICODE = 0x10FFFF;

  private final Prog prog;
  private boolean failed;
  private boolean reversed;
  private final int maxInst;

  private Compiler() {
    this(DEFAULT_MAX_INST);
  }

  private Compiler(int maxInst) {
    this.prog = new Prog();
    this.maxInst = maxInst;
    // Instruction 0 is always the fail instruction (sentinel for PatchList).
    int fail = prog.allocInst();
    prog.inst(fail).initFail();
  }

  /**
   * Compiles the given regexp into a program.
   *
   * @param re the regexp to compile
   * @return the compiled program, or null if compilation fails
   */
  static Prog compile(Regexp re) {
    return compile(re, false);
  }

  /**
   * Compiles the given regexp into a program.
   *
   * @param re the regexp to compile
   * @param reversed if true, compile for backward matching
   * @return the compiled program, or null if compilation fails
   */
  static Prog compile(Regexp re, boolean reversed) {
    Compiler c = new Compiler();
    c.reversed = reversed;

    // Simplify to remove REPEAT, complex char classes, etc.
    Regexp sre = Simplifier.simplify(re);
    if (sre == null) {
      return null;
    }

    // Detect anchoring, stripping the anchor nodes.
    boolean isAnchorStart = isAnchorStart(sre);
    Regexp stripped = stripAnchorStart(sre);
    boolean isAnchorEnd = isAnchorEnd(stripped);
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
    }

    c.prog.setStart(all.begin);

    if (!c.prog.anchorStart()) {
      // Prepend .*? loop for unanchored matching.
      all = c.cat(c.dotStar(), all);
    }
    c.prog.setStartUnanchored(all.begin);

    // Count captures by scanning instructions.
    int maxCap = 0;
    for (int i = 0; i < c.prog.size(); i++) {
      Inst inst = c.prog.inst(i);
      if (inst.op == InstOp.CAPTURE) {
        int capIdx = inst.arg;
        // cap register index is 2*cap for start, 2*cap+1 for end
        // so the capture group number is capIdx/2
        int capNum = capIdx / 2;
        if (capNum > maxCap) {
          maxCap = capNum;
        }
      }
    }
    c.prog.setNumCaptures(maxCap + 1);

    return c.prog;
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
    switch (re.op) {
      case BEGIN_TEXT:
        return true;
      case CONCAT:
        if (re.nsub() > 0) {
          return isAnchorStartImpl(re.subs[0], depth + 1);
        }
        return false;
      case CAPTURE:
        return isAnchorStartImpl(re.sub(), depth + 1);
      default:
        return false;
    }
  }

  private static Regexp stripAnchorStart(Regexp re) {
    return stripAnchorStartImpl(re, 0);
  }

  private static Regexp stripAnchorStartImpl(Regexp re, int depth) {
    if (re == null || depth >= 4) {
      return re;
    }
    switch (re.op) {
      case BEGIN_TEXT:
        return Regexp.emptyMatch(re.flags);
      case CONCAT:
        if (re.nsub() > 0 && isAnchorStartImpl(re.subs[0], depth + 1)) {
          Regexp[] newSubs = new Regexp[re.nsub()];
          newSubs[0] = stripAnchorStartImpl(re.subs[0], depth + 1);
          System.arraycopy(re.subs, 1, newSubs, 1, re.nsub() - 1);
          return Regexp.concat(newSubs, re.flags);
        }
        return re;
      case CAPTURE:
        if (isAnchorStartImpl(re.sub(), depth + 1)) {
          return Regexp.capture(
              stripAnchorStartImpl(re.sub(), depth + 1), re.flags, re.cap, re.name);
        }
        return re;
      default:
        return re;
    }
  }

  private static boolean isAnchorEnd(Regexp re) {
    return isAnchorEndImpl(re, 0);
  }

  private static boolean isAnchorEndImpl(Regexp re, int depth) {
    if (re == null || depth >= 4) {
      return false;
    }
    switch (re.op) {
      case END_TEXT:
        return true;
      case CONCAT:
        if (re.nsub() > 0) {
          return isAnchorEndImpl(re.subs[re.nsub() - 1], depth + 1);
        }
        return false;
      case CAPTURE:
        return isAnchorEndImpl(re.sub(), depth + 1);
      default:
        return false;
    }
  }

  private static Regexp stripAnchorEnd(Regexp re) {
    return stripAnchorEndImpl(re, 0);
  }

  private static Regexp stripAnchorEndImpl(Regexp re, int depth) {
    if (re == null || depth >= 4) {
      return re;
    }
    switch (re.op) {
      case END_TEXT:
        return Regexp.emptyMatch(re.flags);
      case CONCAT:
        if (re.nsub() > 0 && isAnchorEndImpl(re.subs[re.nsub() - 1], depth + 1)) {
          Regexp[] newSubs = new Regexp[re.nsub()];
          int last = re.nsub() - 1;
          System.arraycopy(re.subs, 0, newSubs, 0, last);
          newSubs[last] = stripAnchorEndImpl(re.subs[last], depth + 1);
          return Regexp.concat(newSubs, re.flags);
        }
        return re;
      case CAPTURE:
        if (isAnchorEndImpl(re.sub(), depth + 1)) {
          return Regexp.capture(
              stripAnchorEndImpl(re.sub(), depth + 1), re.flags, re.cap, re.name);
        }
        return re;
      default:
        return re;
    }
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
        Inst ip = prog.inst(current >> 1);
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
      Inst ip = prog.inst(l1.tail >> 1);
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
    Inst begin = prog.inst(a.begin);
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

    prog.inst(id).initAlt(a.begin, b.begin);
    return new Frag(
        id,
        PatchList.append(prog, a.end, b.end),
        a.nullable || b.nullable);
  }

  private Frag plus(Frag a, boolean nongreedy) {
    int id = allocInst();
    if (id < 0) {
      return Frag.NO_MATCH;
    }
    PatchList pl;
    if (nongreedy) {
      prog.inst(id).initAlt(0, a.begin);
      pl = PatchList.mk(id << 1);
    } else {
      prog.inst(id).initAlt(a.begin, 0);
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
      prog.inst(id).initAlt(0, a.begin);
      pl = PatchList.mk(id << 1);
    } else {
      prog.inst(id).initAlt(a.begin, 0);
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
      prog.inst(id).initAlt(0, a.begin);
      pl = PatchList.mk(id << 1);
    } else {
      prog.inst(id).initAlt(a.begin, 0);
      pl = PatchList.mk((id << 1) | 1);
    }
    return new Frag(id, PatchList.append(prog, pl, a.end), true);
  }

  private Frag charRange(int lo, int hi, boolean foldCase) {
    int id = allocInst();
    if (id < 0) {
      return Frag.NO_MATCH;
    }
    prog.inst(id).initCharRange(lo, hi, foldCase, 0);
    return new Frag(id, PatchList.mk(id << 1), false);
  }

  private Frag nop() {
    int id = allocInst();
    if (id < 0) {
      return Frag.NO_MATCH;
    }
    prog.inst(id).initNop(0);
    return new Frag(id, PatchList.mk(id << 1), true);
  }

  private Frag match(int matchId) {
    int id = allocInst();
    if (id < 0) {
      return Frag.NO_MATCH;
    }
    prog.inst(id).initMatch(matchId);
    return new Frag(id, PatchList.EMPTY, false);
  }

  private Frag emptyWidth(int emptyFlags) {
    int id = allocInst();
    if (id < 0) {
      return Frag.NO_MATCH;
    }
    prog.inst(id).initEmptyWidth(emptyFlags, 0);
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
    prog.inst(id).initCapture(2 * cap, a.begin);
    prog.inst(id2).initCapture(2 * cap + 1, 0);
    PatchList.patch(prog, a.end, id2);
    return new Frag(id, PatchList.mk(id2 << 1), a.nullable);
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
    Frag hi = charRange(Utils.MAX_SURROGATE + 1, MAX_UNICODE, false);
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

    switch (re.op) {
      case REPEAT:
        // Should have been simplified away.
        failed = true;
        return Frag.NO_MATCH;

      case NO_MATCH:
        return Frag.NO_MATCH;

      case EMPTY_MATCH:
        return nop();

      case HAVE_MATCH:
        return match(re.matchId);

      case CONCAT: {
        Frag f = childArgs.get(0);
        for (int i = 1; i < childArgs.size(); i++) {
          f = cat(f, childArgs.get(i));
        }
        return f;
      }

      case ALTERNATE: {
        Frag f = childArgs.get(0);
        for (int i = 1; i < childArgs.size(); i++) {
          f = alt(f, childArgs.get(i));
        }
        return f;
      }

      case STAR:
        return star(childArgs.get(0), re.nonGreedy());

      case PLUS:
        return plus(childArgs.get(0), re.nonGreedy());

      case QUEST:
        return quest(childArgs.get(0), re.nonGreedy());

      case LITERAL:
        return literal(re.rune, re.foldCase());

      case LITERAL_STRING: {
        if (re.runes == null || re.runes.length == 0) {
          return nop();
        }
        Frag f = literal(re.runes[0], re.foldCase());
        for (int i = 1; i < re.runes.length; i++) {
          f = cat(f, literal(re.runes[i], re.foldCase()));
        }
        return f;
      }

      case ANY_CHAR:
        return anyCodePoint();

      case CHAR_CLASS:
        return compileCharClass(re);

      case CAPTURE:
        if (re.cap < 0) {
          return childArgs.get(0);
        }
        return capture(childArgs.get(0), re.cap);

      case BEGIN_LINE:
        return emptyWidth(reversed ? EmptyOp.END_LINE : EmptyOp.BEGIN_LINE);

      case END_LINE:
        return emptyWidth(reversed ? EmptyOp.BEGIN_LINE : EmptyOp.END_LINE);

      case BEGIN_TEXT:
        return emptyWidth(reversed ? EmptyOp.END_TEXT : EmptyOp.BEGIN_TEXT);

      case END_TEXT:
        return emptyWidth(reversed ? EmptyOp.BEGIN_TEXT : EmptyOp.END_TEXT);

      case WORD_BOUNDARY:
        return emptyWidth(EmptyOp.WORD_BOUNDARY);

      case NO_WORD_BOUNDARY:
        return emptyWidth(EmptyOp.NON_WORD_BOUNDARY);

      default:
        failed = true;
        return Frag.NO_MATCH;
    }
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

    // Build an alternation of CHAR_RANGE instructions for each range.
    Frag f = Frag.NO_MATCH;
    for (int i = 0; i < cc.numRanges(); i++) {
      Frag r = charRange(cc.lo(i), cc.hi(i), false);
      f = alt(f, r);
    }
    return f;
  }
}
