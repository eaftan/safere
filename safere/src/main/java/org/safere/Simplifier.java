// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Simplifies a {@link Regexp} AST so that it uses only basic operators ({@link RegexpOp#STAR},
 * {@link RegexpOp#PLUS}, {@link RegexpOp#QUEST}, {@link RegexpOp#CONCAT}, {@link
 * RegexpOp#ALTERNATE}, {@link RegexpOp#CAPTURE}) instead of {@link RegexpOp#REPEAT}. Also
 * simplifies character classes (empty → NO_MATCH, full → ANY_CHAR).
 *
 * <p>The simplification is done in two passes:
 *
 * <ol>
 *   <li><b>Coalesce</b>: Merges adjacent repetitions of the same sub-expression in CONCAT nodes
 *       (e.g., {@code a*a+} → {@code REPEAT(a, 1, -1)}).
 *   <li><b>Simplify</b>: Converts REPEAT nodes into STAR/PLUS/QUEST/CONCAT and simplifies
 *       character classes.
 * </ol>
 *
 * <p>This is a port of RE2's {@code simplify.cc}.
 */
final class Simplifier {

  private Simplifier() {}

  /** Returns a simplified copy of the given regexp. */
  static Regexp simplify(Regexp re) {
    // Pass 1: coalesce adjacent repeats.
    CoalesceWalker cw = new CoalesceWalker();
    Regexp cre = cw.walk(re, null);
    if (cre == null || cw.stoppedEarly()) {
      return null;
    }
    // Pass 2: simplify repeats and char classes.
    SimplifyWalker sw = new SimplifyWalker();
    Regexp sre = sw.walk(cre, null);
    if (sre == null || sw.stoppedEarly()) {
      return null;
    }
    return sre;
  }

  // ---------------------------------------------------------------------------
  // Structural equality (port of Regexp::Equal from regexp.cc)
  // ---------------------------------------------------------------------------

  /** Returns true if the two regexps are structurally equal. */
  // Switches mirror the C++ RE2 Regexp::Equal structure and are clearer as statement switches.
  @SuppressWarnings("StatementSwitchToExpressionSwitch")
  static boolean equal(Regexp a, Regexp b) {
    if (a == b) {
      return true;
    }
    if (a == null || b == null) {
      return a == b;
    }
    if (!topEqual(a, b)) {
      return false;
    }

    // For leaf nodes, topEqual is sufficient.
    switch (a.op) {
      case ALTERNATE:
      case CONCAT:
      case STAR:
      case PLUS:
      case QUEST:
      case REPEAT:
      case NON_CAPTURE:
      case CAPTURE:
        break;
      default:
        return true;
    }

    // Recursively compare children using an explicit stack.
    java.util.ArrayDeque<Regexp> stk = new java.util.ArrayDeque<>();
    for (;;) {
      switch (a.op) {
        case ALTERNATE:
        case CONCAT:
          for (int i = 0; i < a.subs.size(); i++) {
            Regexp a2 = a.subs.get(i);
            Regexp b2 = b.subs.get(i);
            if (!topEqual(a2, b2)) {
              return false;
            }
            stk.push(a2);
            stk.push(b2);
          }
          break;
        case STAR:
        case PLUS:
        case QUEST:
        case REPEAT:
        case NON_CAPTURE:
        case CAPTURE:
          Regexp a2 = a.subs.get(0);
          Regexp b2 = b.subs.get(0);
          if (!topEqual(a2, b2)) {
            return false;
          }
          a = a2;
          b = b2;
          continue;
        default:
          break;
      }

      if (stk.isEmpty()) {
        break;
      }
      b = stk.pop();
      a = stk.pop();
    }
    return true;
  }

  /** Compares top-level structure of two Regexp nodes (not their children). */
  // Switch mirrors the C++ RE2 structure and is clearer as a statement switch.
  @SuppressWarnings("StatementSwitchToExpressionSwitch")
  private static boolean topEqual(Regexp a, Regexp b) {
    if (a.op != b.op) {
      return false;
    }
    switch (a.op) {
      case NO_MATCH:
      case EMPTY_MATCH:
      case ANY_CHAR:
      case ANY_BYTE:
      case BEGIN_LINE:
      case END_LINE:
      case WORD_BOUNDARY:
      case NO_WORD_BOUNDARY:
      case BEGIN_TEXT:
        return true;

      case END_TEXT:
        return ((a.flags ^ b.flags) & ParseFlags.WAS_DOLLAR) == 0;

      case LITERAL:
        return a.rune == b.rune
            && ((a.flags ^ b.flags) & ParseFlags.FOLD_CASE) == 0;

      case LITERAL_STRING:
        return Arrays.equals(a.runes, b.runes)
            && ((a.flags ^ b.flags) & ParseFlags.FOLD_CASE) == 0;

      case ALTERNATE:
      case CONCAT:
        return a.subs.size() == b.subs.size();

      case STAR:
      case PLUS:
      case QUEST:
        return ((a.flags ^ b.flags) & ParseFlags.NON_GREEDY) == 0;

      case REPEAT:
        return ((a.flags ^ b.flags) & ParseFlags.NON_GREEDY) == 0
            && a.min == b.min && a.max == b.max;

      case NON_CAPTURE:
        return true;

      case CAPTURE:
        return a.cap == b.cap && Objects.equals(a.name, b.name);

      case HAVE_MATCH:
        return a.matchId == b.matchId;

      case CHAR_CLASS:
        return Arrays.equals(a.charClass.flatRanges(), b.charClass.flatRanges());

      default:
        return false;
    }
  }

  // ---------------------------------------------------------------------------
  // CoalesceWalker
  // ---------------------------------------------------------------------------

  /**
   * Merges adjacent repetitions of the same sub-expression in CONCAT nodes. For example, {@code
   * a*a+} becomes {@code REPEAT(a, 1, -1)}.
   */
  private static final class CoalesceWalker extends Walker<Regexp> {

    @Override
    protected Regexp copy(Regexp re) {
      return re; // No ref-counting in Java.
    }

    @Override
    protected Regexp shortVisit(Regexp re, Regexp parentArg) {
      return re;
    }

    @Override
    protected Regexp postVisit(
        Regexp re, Regexp parentArg, Regexp preArg, List<Regexp> childArgs) {
      if (childArgs.isEmpty()) {
        return re;
      }

      if (re.op != RegexpOp.CONCAT) {
        if (!childArgsChanged(re, childArgs)) {
          return re;
        }
        // Something changed. Build a new node.
        return copyWithNewSubs(re, childArgs);
      }

      // CONCAT: check if any adjacent pair can coalesce.
      boolean canCoalesce = false;
      for (int i = 0; i + 1 < childArgs.size(); i++) {
        if (canCoalesce(childArgs.get(i), childArgs.get(i + 1))) {
          canCoalesce = true;
          break;
        }
      }

      if (!canCoalesce) {
        if (!childArgsChanged(re, childArgs)) {
          return re;
        }
        return Regexp.concat(childArgs, re.flags);
      }

      // Do the coalescing in-place on childArgs.
      for (int i = 0; i + 1 < childArgs.size(); i++) {
        if (canCoalesce(childArgs.get(i), childArgs.get(i + 1))) {
          CoalescePair pair = doCoalesce(childArgs.get(i), childArgs.get(i + 1));
          childArgs.set(i, pair.first);
          childArgs.set(i + 1, pair.second);
        }
      }

      // Build new CONCAT without the empty matches left by coalescing.
      List<Regexp> newSubs = new ArrayList<>(childArgs.size());
      for (int i = 0; i < childArgs.size(); i++) {
        if (childArgs.get(i).op != RegexpOp.EMPTY_MATCH) {
          newSubs.add(childArgs.get(i));
        }
      }
      if (newSubs.size() == 1) {
        return newSubs.getFirst();
      }
      return Regexp.concat(newSubs, re.flags);
    }
  }

  /** Returns true if the child args differ from the original subs. */
  private static boolean childArgsChanged(Regexp re, List<Regexp> childArgs) {
    for (int i = 0; i < childArgs.size(); i++) {
      if (childArgs.get(i) != re.subs.get(i)) {
        return true;
      }
    }
    return false;
  }

  /** Creates a copy of {@code re} with new sub-expressions, preserving op-specific fields. */
  // Switch mirrors the C++ RE2 structure and is clearer as a statement switch.
  @SuppressWarnings("StatementSwitchToExpressionSwitch")
  private static Regexp copyWithNewSubs(Regexp re, List<Regexp> newSubs) {
    switch (re.op) {
      case REPEAT:
        return Regexp.repeat(newSubs.getFirst(), re.flags, re.min, re.max);
      case NON_CAPTURE:
        return Regexp.nonCapture(newSubs.getFirst(), re.flags);
      case CAPTURE:
        return Regexp.capture(newSubs.getFirst(), re.flags, re.cap, re.name);
      case STAR:
        return Regexp.star(newSubs.getFirst(), re.flags);
      case PLUS:
        return Regexp.plus(newSubs.getFirst(), re.flags);
      case QUEST:
        return Regexp.quest(newSubs.getFirst(), re.flags);
      case ALTERNATE:
        return Regexp.alternate(newSubs, re.flags);
      case CONCAT:
        return Regexp.concat(newSubs, re.flags);
      default:
        throw new IllegalArgumentException("Unexpected op: " + re.op);
    }
  }

  /**
   * Returns true if r1 and r2 can be coalesced. r1 must be a quantifier (STAR/PLUS/QUEST/REPEAT)
   * of a LITERAL, CHAR_CLASS, ANY_CHAR, or ANY_BYTE, and r2 must be a compatible quantifier of the
   * same operand, or the operand itself, or a LITERAL_STRING beginning with the same literal.
   */
  private static boolean canCoalesce(Regexp r1, Regexp r2) {
    if (r1.op != RegexpOp.STAR && r1.op != RegexpOp.PLUS
        && r1.op != RegexpOp.QUEST && r1.op != RegexpOp.REPEAT) {
      return false;
    }
    Regexp r1sub = r1.subs.getFirst();
    if (r1sub.op != RegexpOp.LITERAL && r1sub.op != RegexpOp.CHAR_CLASS
        && r1sub.op != RegexpOp.ANY_CHAR && r1sub.op != RegexpOp.ANY_BYTE) {
      return false;
    }

    // r2 is a quantifier of the same thing.
    if ((r2.op == RegexpOp.STAR || r2.op == RegexpOp.PLUS
            || r2.op == RegexpOp.QUEST || r2.op == RegexpOp.REPEAT)
        && equal(r1sub, r2.subs.getFirst())
        && ((r1.flags & ParseFlags.NON_GREEDY) == (r2.flags & ParseFlags.NON_GREEDY))) {
      return true;
    }

    // r2 is the operand itself.
    if (equal(r1sub, r2)) {
      return true;
    }

    // r2 is a literal string beginning with r1's literal.
    if (r1sub.op == RegexpOp.LITERAL
        && r2.op == RegexpOp.LITERAL_STRING
        && r2.runes.length > 0
        && r2.runes[0] == r1sub.rune
        && ((r1sub.flags & ParseFlags.FOLD_CASE) == (r2.flags & ParseFlags.FOLD_CASE))) {
      return true;
    }

    return false;
  }

  /** A pair of coalesced regexp results. */
  private record CoalescePair(Regexp first, Regexp second) {}

  /**
   * Coalesces r1 and r2 into a single repeat. Returns a {@link CoalescePair}: the first element
   * replaces r1's position, the second replaces r2's position. One of them will typically be
   * EMPTY_MATCH.
   */
  // Switches assign min/max from two separate enum dispatches; clearer as statement switches.
  @SuppressWarnings("StatementSwitchToExpressionSwitch")
  private static CoalescePair doCoalesce(Regexp r1, Regexp r2) {
    int min, max;
    Regexp operand = r1.subs.getFirst();

    switch (r1.op) {
      case STAR:
        min = 0;
        max = -1;
        break;
      case PLUS:
        min = 1;
        max = -1;
        break;
      case QUEST:
        min = 0;
        max = 1;
        break;
      case REPEAT:
        min = r1.min;
        max = r1.max;
        break;
      default:
        throw new IllegalArgumentException("Bad r1 op: " + r1.op);
    }

    switch (r2.op) {
      case STAR:
        max = -1;
        return leaveEmpty(operand, r1.flags, min, max);
      case PLUS:
        min++;
        max = -1;
        return leaveEmpty(operand, r1.flags, min, max);
      case QUEST:
        if (max != -1) {
          max++;
        }
        return leaveEmpty(operand, r1.flags, min, max);
      case REPEAT:
        min += r2.min;
        if (r2.max == -1) {
          max = -1;
        } else if (max != -1) {
          max += r2.max;
        }
        return leaveEmpty(operand, r1.flags, min, max);
      case LITERAL:
      case CHAR_CLASS:
      case ANY_CHAR:
      case ANY_BYTE:
        min++;
        if (max != -1) {
          max++;
        }
        return leaveEmpty(operand, r1.flags, min, max);
      case LITERAL_STRING: {
        int r = r1.subs.getFirst().rune;
        int n = 1;
        while (n < r2.runes.length && r2.runes[n] == r) {
          n++;
        }
        min += n;
        if (max != -1) {
          max += n;
        }
        if (n == r2.runes.length) {
          return leaveEmpty(operand, r1.flags, min, max);
        }
        // Partial match: the repeat goes first, remainder of literal string second.
        Regexp nre = Regexp.repeat(operand, r1.flags, min, max);
        int[] remainRunes = Arrays.copyOfRange(r2.runes, n, r2.runes.length);
        Regexp remainder = Regexp.literalString(remainRunes, r2.flags);
        return new CoalescePair(nre, remainder);
      }
      default:
        throw new IllegalArgumentException("Bad r2 op: " + r2.op);
    }
  }

  /**
   * Returns a {@link CoalescePair} of [EMPTY_MATCH, REPEAT(operand, min, max)]. The first element
   * replaces r1 (empty placeholder), and the second replaces r2 (the coalesced repeat).
   */
  private static CoalescePair leaveEmpty(Regexp operand, int flags, int min, int max) {
    return new CoalescePair(
        Regexp.emptyMatch(ParseFlags.NONE), Regexp.repeat(operand, flags, min, max));
  }

  // ---------------------------------------------------------------------------
  // SimplifyWalker
  // ---------------------------------------------------------------------------

  /** Converts REPEAT nodes to STAR/PLUS/QUEST/CONCAT and simplifies character classes. */
  private static final class SimplifyWalker extends Walker<Regexp> {

    @Override
    protected Regexp copy(Regexp re) {
      return re;
    }

    @Override
    protected Regexp shortVisit(Regexp re, Regexp parentArg) {
      return re;
    }

    @Override
    protected Regexp preVisit(Regexp re, Regexp parentArg, boolean[] stop) {
      if (computeSimple(re)) {
        stop[0] = true;
        return re;
      }
      return null;
    }

    @Override
    // Switch mirrors the C++ RE2 simplify structure and is clearer as a statement switch.
    @SuppressWarnings("StatementSwitchToExpressionSwitch")
    protected Regexp postVisit(
        Regexp re, Regexp parentArg, Regexp preArg, List<Regexp> childArgs) {
      switch (re.op) {
        case NO_MATCH:
        case EMPTY_MATCH:
        case LITERAL:
        case LITERAL_STRING:
        case BEGIN_LINE:
        case END_LINE:
        case BEGIN_TEXT:
        case WORD_BOUNDARY:
        case NO_WORD_BOUNDARY:
        case END_TEXT:
        case ANY_CHAR:
        case ANY_BYTE:
        case HAVE_MATCH:
          return re;

        case CONCAT:
        case ALTERNATE: {
          if (!childArgsChanged(re, childArgs)) {
            return re;
          }
          if (re.op == RegexpOp.CONCAT) {
            return Regexp.concat(childArgs, re.flags);
          } else {
            return Regexp.alternate(childArgs, re.flags);
          }
        }

        case NON_CAPTURE: {
          Regexp newsub = childArgs.get(0);
          if (!isPureEmpty(newsub) || !hasCapture(newsub)) {
            return newsub;
          }
          if (newsub == re.subs.getFirst()) {
            return re;
          }
          return Regexp.nonCapture(newsub, re.flags);
        }

        case CAPTURE: {
          Regexp newsub = childArgs.get(0);
          if (newsub == re.subs.getFirst()) {
            return re;
          }
          return Regexp.capture(newsub, re.flags, re.cap, re.name);
        }

        case STAR:
        case PLUS:
        case QUEST: {
          Regexp newsub = childArgs.get(0);
          // Repeat of empty string is still empty string.
          if (newsub.op == RegexpOp.EMPTY_MATCH) {
            return newsub;
          }
          if (newsub == re.subs.getFirst()) {
            return re;
          }
          // Idempotent squashing: e.g. STAR(STAR(x)) → STAR(x).
          if (re.op == newsub.op && re.flags == newsub.flags) {
            return newsub;
          }
          return starPlusOrQuest(re.op, newsub, re.flags);
        }

        case REPEAT: {
          Regexp newsub = childArgs.get(0);
          if (newsub.op == RegexpOp.EMPTY_MATCH) {
            return newsub;
          }
          return simplifyRepeat(newsub, re.min, re.max, re.flags);
        }

        case CHAR_CLASS:
          return simplifyCharClass(re);

        default:
          return re;
      }
    }
  }

  /** Returns true if re is an empty-width assertion op. */
  private static boolean isEmptyOp(Regexp re) {
    return re.op == RegexpOp.BEGIN_LINE
        || re.op == RegexpOp.END_LINE
        || re.op == RegexpOp.WORD_BOUNDARY
        || re.op == RegexpOp.NO_WORD_BOUNDARY
        || re.op == RegexpOp.BEGIN_TEXT
        || re.op == RegexpOp.END_TEXT;
  }

  private static boolean isPureEmpty(Regexp re) {
    ArrayDeque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      switch (node.op) {
        case EMPTY_MATCH, BEGIN_LINE, END_LINE, BEGIN_TEXT, END_TEXT, WORD_BOUNDARY,
             NO_WORD_BOUNDARY -> {}
        case NON_CAPTURE, CAPTURE -> stack.push(node.sub());
        case CONCAT -> {
          for (Regexp sub : node.subs) {
            stack.push(sub);
          }
        }
        default -> {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean hasCapture(Regexp re) {
    ArrayDeque<Regexp> stack = new ArrayDeque<>();
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

  /**
   * Determines whether a Regexp is already "simple" (no REPEAT, no empty/full char classes, no
   * nested quantifiers on quantifiers).
   */
  // Switch mirrors the C++ RE2 structure and is clearer as a statement switch.
  @SuppressWarnings("StatementSwitchToExpressionSwitch")
  private static boolean computeSimple(Regexp re) {
    switch (re.op) {
      case NO_MATCH:
      case EMPTY_MATCH:
      case LITERAL:
      case LITERAL_STRING:
      case BEGIN_LINE:
      case END_LINE:
      case BEGIN_TEXT:
      case WORD_BOUNDARY:
      case NO_WORD_BOUNDARY:
      case END_TEXT:
      case ANY_CHAR:
      case ANY_BYTE:
      case HAVE_MATCH:
        return true;
      case CONCAT:
      case ALTERNATE:
        for (Regexp sub : re.subs) {
          if (!computeSimple(sub)) {
            return false;
          }
        }
        return true;
      case CHAR_CLASS:
        return !re.charClass.isEmpty() && !isFull(re.charClass);
      case NON_CAPTURE:
        return false;
      case CAPTURE:
        return computeSimple(re.subs.getFirst());
      case STAR:
      case PLUS:
      case QUEST:
        if (!computeSimple(re.subs.getFirst())) {
          return false;
        }
        RegexpOp subOp = re.subs.getFirst().op;
        return subOp != RegexpOp.STAR
            && subOp != RegexpOp.PLUS
            && subOp != RegexpOp.QUEST
            && subOp != RegexpOp.EMPTY_MATCH
            && subOp != RegexpOp.NO_MATCH;
      case REPEAT:
        return false;
      default:
        return false;
    }
  }

  /**
   * Creates a STAR/PLUS/QUEST node without any cross-op squashing. Used by the SimplifyWalker's
   * postVisit to match the C++ behavior (which also creates nodes directly).
   */
  private static Regexp rawQuantifier(RegexpOp op, Regexp sub, int flags) {
    return Regexp.rawQuantifier(op, sub, flags);
  }

  /** Returns true if the character class matches every valid Unicode code point. */
  private static boolean isFull(CharClass cc) {
    // A "full" class covers [0, 0xD7FF] and [0xE000, 0x10FFFF] (skipping surrogates).
    if (cc.numRanges() == 2
        && cc.lo(0) == 0 && cc.hi(0) == Utils.MIN_SURROGATE - 1
        && cc.lo(1) == Utils.MAX_SURROGATE + 1 && cc.hi(1) == Utils.MAX_RUNE) {
      return true;
    }
    // Or possibly one range [0, 0x10FFFF] if surrogates are included.
    if (cc.numRanges() == 1 && cc.lo(0) == 0 && cc.hi(0) == Utils.MAX_RUNE) {
      return true;
    }
    return false;
  }

  /**
   * Simplifies the expression {@code re{min,max}} in terms of STAR, PLUS, QUEST, and CONCAT.
   *
   * <p>For empty-width ops (or concatenations/alternations of them), the repetition count is
   * capped at 1.
   */
  private static Regexp simplifyRepeat(Regexp re, int min, int max, int flags) {
    // Cap repetition of empty-width ops at 1.
    if (isEmptyOp(re)
        || ((re.op == RegexpOp.CONCAT || re.op == RegexpOp.ALTERNATE)
            && allEmptyOp(re))) {
      min = Math.min(min, 1);
      max = Math.min(max, 1);
    }

    // x{n,} means at least n matches.
    if (max == -1) {
      if (min == 0) {
        return starPlusOrQuest(RegexpOp.STAR, re, flags);
      }
      if (min == 1) {
        return starPlusOrQuest(RegexpOp.PLUS, re, flags);
      }
      // General case: x{4,} is (?:x)(?:x)(?:x)(x)+. Keep captures in every
      // copy: the language-only optimization of stripping captures from the
      // mandatory prefix changes JDK-visible group state for quantified captures.
      List<Regexp> subs = new ArrayList<>(min);
      for (int i = 0; i < min - 1; i++) {
        subs.add(re);
      }
      subs.add(starPlusOrQuest(RegexpOp.PLUS, re, flags));
      return Regexp.concat(subs, flags);
    }

    // x{0} → empty match
    if (min == 0 && max == 0) {
      return Regexp.emptyMatch(flags);
    }

    // x{1} → x
    if (min == 1 && max == 1) {
      return re;
    }

    // General case: x{n,m} means n copies of x and (m-n) nested x?
    // x{2,5} = xx(x(x(x)?)?)?
    Regexp nre = null;
    if (min > 0) {
      List<Regexp> subs = new ArrayList<>(min);
      for (int i = 0; i < min; i++) {
        subs.add(re);
      }
      nre = Regexp.concat(subs, flags);
    }

    if (max > min) {
      Regexp suf = starPlusOrQuest(RegexpOp.QUEST, re, flags);
      for (int i = min + 1; i < max; i++) {
        suf = starPlusOrQuest(RegexpOp.QUEST,
            Regexp.concat(List.of(re, suf), flags), flags);
      }
      if (nre == null) {
        nre = suf;
      } else {
        nre = Regexp.concat(List.of(nre, suf), flags);
      }
    }

    if (nre == null) {
      return Regexp.noMatch(flags);
    }
    return nre;
  }

  /** Returns true if all children of re are empty-width ops. */
  private static boolean allEmptyOp(Regexp re) {
    for (Regexp sub : re.subs) {
      if (!isEmptyOp(sub)) {
        return false;
      }
    }
    return true;
  }

  /** Simplifies a character class: empty → NO_MATCH, full → ANY_CHAR. */
  private static Regexp simplifyCharClass(Regexp re) {
    if (re.charClass.isEmpty()) {
      return Regexp.noMatch(re.flags);
    }
    if (isFull(re.charClass)) {
      return Regexp.anyChar(re.flags);
    }
    return re;
  }

  /**
   * Creates a STAR, PLUS, or QUEST node, squashing nested quantifiers when possible. This mirrors
   * RE2's {@code StarPlusOrQuest()} factory method.
   *
   * <ul>
   *   <li>Same op + same flags → return sub unchanged (e.g., STAR(STAR(x)) → STAR(x))
   *   <li>Different quantifier ops + same flags → return STAR(x)
   * </ul>
   */
  // Switch mirrors the C++ RE2 structure and is clearer as a statement switch.
  @SuppressWarnings("StatementSwitchToExpressionSwitch")
  private static Regexp starPlusOrQuest(RegexpOp op, Regexp sub, int flags) {
    if (hasVisibleCapture(sub)) {
      return rawQuantifier(op, sub, flags);
    }
    // Squash identical: **, ++, ??
    if (op == sub.op && flags == sub.flags) {
      return sub;
    }
    // Squash mixed: *+, *?, +*, +?, ?*, ?+ all → *
    if ((sub.op == RegexpOp.STAR || sub.op == RegexpOp.PLUS || sub.op == RegexpOp.QUEST)
        && flags == sub.flags) {
      if (sub.op == RegexpOp.STAR) {
        return sub;
      }
      return Regexp.star(sub.subs.getFirst(), flags);
    }
    switch (op) {
      case STAR:
        return Regexp.star(sub, flags);
      case PLUS:
        return Regexp.plus(sub, flags);
      case QUEST:
        return Regexp.quest(sub, flags);
      default:
        throw new IllegalArgumentException("Bad op: " + op);
    }
  }

  private static boolean hasVisibleCapture(Regexp re) {
    return new HasVisibleCaptureWalker().walk(re, false);
  }

  private static final class HasVisibleCaptureWalker extends Walker<Boolean> {

    @Override
    protected Boolean shortVisit(Regexp re, Boolean parentArg) {
      return false;
    }

    @Override
    protected Boolean postVisit(
        Regexp re, Boolean parentArg, Boolean preArg, List<Boolean> childArgs) {
      if (re.op == RegexpOp.CAPTURE && re.cap > 0) {
        return true;
      }
      for (boolean childHasCapture : childArgs) {
        if (childHasCapture) {
          return true;
        }
      }
      return false;
    }
  }
}
