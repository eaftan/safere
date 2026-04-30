// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Arrays;
import java.util.List;

/**
 * A node in the regular expression abstract syntax tree (AST). Each node has a {@link RegexpOp}
 * that determines what kind of regexp it represents, and operator-specific data stored in the
 * node's fields.
 *
 * <p>The AST is produced by the {@code Parser} and consumed by the {@code Compiler} to produce a
 * {@link Prog}. The AST can also be simplified by the {@code Simplifier} and converted back to a
 * string via {@link #toString()}.
 *
 * <p>Field usage by operator:
 *
 * <table>
 *   <tr><th>Operator</th><th>Fields used</th></tr>
 *   <tr><td>{@link RegexpOp#LITERAL}</td><td>{@link #rune}, {@link #flags}</td></tr>
 *   <tr><td>{@link RegexpOp#LITERAL_STRING}</td><td>{@link #runes}, {@link #flags}</td></tr>
 *   <tr><td>{@link RegexpOp#CHAR_CLASS}</td><td>{@link #charClass}</td></tr>
 *   <tr><td>{@link RegexpOp#CONCAT}, {@link RegexpOp#ALTERNATE}</td><td>{@link #subs}</td></tr>
 *   <tr><td>{@link RegexpOp#STAR}, {@link RegexpOp#PLUS}, {@link RegexpOp#QUEST}</td>
 *       <td>{@link #subs} (length 1), {@link #flags}</td></tr>
 *   <tr><td>{@link RegexpOp#REPEAT}</td><td>{@link #subs} (length 1), {@link #min},
 *       {@link #max}, {@link #flags}</td></tr>
 *   <tr><td>{@link RegexpOp#CAPTURE}</td><td>{@link #subs} (length 1), {@link #cap},
 *       {@link #name}</td></tr>
 *   <tr><td>{@link RegexpOp#HAVE_MATCH}</td><td>{@link #matchId}</td></tr>
 *   <tr><td>All others</td><td>No additional fields</td></tr>
 * </table>
 */
final class Regexp {

  /** The operator for this node. */
  public final RegexpOp op;

  /** Parse flags in effect for this node. */
  public final int flags;

  /** Sub-expressions (children). Used by CONCAT, ALTERNATE, STAR, PLUS, QUEST, REPEAT, CAPTURE. */
  public List<Regexp> subs;

  /**
   * A single Unicode code point. Used by LITERAL. The code point may be case-folded if {@link
   * ParseFlags#FOLD_CASE} is set in {@link #flags}.
   */
  public int rune;

  /** An array of Unicode code points. Used by LITERAL_STRING. */
  public int[] runes;

  /** The character class. Used by CHAR_CLASS. */
  public CharClass charClass;

  /** Minimum repetition count. Used by REPEAT. */
  public int min;

  /** Maximum repetition count (-1 = unbounded). Used by REPEAT. */
  public int max;

  /** Capture group index (1-based for user groups; 0 for full match). Used by CAPTURE. */
  public int cap;

  /** Capture group name, or null for unnamed groups. Used by CAPTURE. */
  public String name;

  /** Match ID for multi-pattern matching. Used by HAVE_MATCH. */
  public int matchId;

  private Regexp(RegexpOp op, int flags) {
    this.op = op;
    this.flags = flags;
  }

  // --- Factory methods ---

  /** Creates a NO_MATCH node. */
  public static Regexp noMatch(int flags) {
    return new Regexp(RegexpOp.NO_MATCH, flags);
  }

  /** Creates an EMPTY_MATCH node. */
  public static Regexp emptyMatch(int flags) {
    return new Regexp(RegexpOp.EMPTY_MATCH, flags);
  }

  /** Creates a LITERAL node matching a single code point. */
  public static Regexp literal(int rune, int flags) {
    Regexp re = new Regexp(RegexpOp.LITERAL, flags);
    re.rune = rune;
    return re;
  }

  /** Creates a LITERAL_STRING node matching a sequence of code points. */
  public static Regexp literalString(int[] runes, int flags) {
    Regexp re = new Regexp(RegexpOp.LITERAL_STRING, flags);
    re.runes = Arrays.copyOf(runes, runes.length);
    return re;
  }

  /** Creates a CONCAT node matching the concatenation of the given sub-expressions in order. */
  public static Regexp concat(List<Regexp> subs, int flags) {
    Regexp re = new Regexp(RegexpOp.CONCAT, flags);
    re.subs = List.copyOf(subs);
    return re;
  }

  /** Creates an ALTERNATE node matching any one of the given sub-expressions. */
  public static Regexp alternate(List<Regexp> subs, int flags) {
    Regexp re = new Regexp(RegexpOp.ALTERNATE, flags);
    re.subs = List.copyOf(subs);
    return re;
  }

  /** Creates a STAR node matching the sub-expression zero or more times. */
  public static Regexp star(Regexp sub, int flags) {
    return starPlusOrQuest(RegexpOp.STAR, sub, flags);
  }

  /** Creates a PLUS node matching the sub-expression one or more times. */
  public static Regexp plus(Regexp sub, int flags) {
    return starPlusOrQuest(RegexpOp.PLUS, sub, flags);
  }

  /** Creates a QUEST node matching the sub-expression zero or one times. */
  public static Regexp quest(Regexp sub, int flags) {
    return starPlusOrQuest(RegexpOp.QUEST, sub, flags);
  }

  /**
   * Creates a STAR, PLUS, or QUEST node, squashing nested quantifiers when possible. Mirrors RE2's
   * {@code Regexp::StarPlusOrQuest()}.
   */
  private static Regexp starPlusOrQuest(RegexpOp op, Regexp sub, int flags) {
    // Squash **, ++, ??
    if (op == sub.op && flags == sub.flags) {
      return sub;
    }
    // Squash *+, *?, +*, +?, ?*, ?+ — all become STAR.
    if ((sub.op == RegexpOp.STAR || sub.op == RegexpOp.PLUS || sub.op == RegexpOp.QUEST)
        && flags == sub.flags) {
      if (sub.op == RegexpOp.STAR) {
        return sub;
      }
      Regexp re = new Regexp(RegexpOp.STAR, flags);
      re.subs = List.of(sub.subs.get(0));
      return re;
    }
    return rawQuantifier(op, sub, flags);
  }

  /**
   * Creates a STAR, PLUS, or QUEST node without any squashing. Used by the SimplifyWalker's
   * PostVisit which (like the C++ code) creates nodes directly rather than through the squashing
   * factory.
   */
  static Regexp rawQuantifier(RegexpOp op, Regexp sub, int flags) {
    Regexp re = new Regexp(op, flags);
    re.subs = List.of(sub);
    return re;
  }

  /**
   * Creates a REPEAT node matching the sub-expression between {@code min} and {@code max} times. A
   * max of -1 means unbounded.
   */
  public static Regexp repeat(Regexp sub, int flags, int min, int max) {
    Regexp re = new Regexp(RegexpOp.REPEAT, flags);
    re.subs = List.of(sub);
    re.min = min;
    re.max = max;
    return re;
  }

  /**
   * Creates a CAPTURE node wrapping the sub-expression with capture group index {@code cap} and
   * optional name.
   */
  public static Regexp capture(Regexp sub, int flags, int cap, String name) {
    Regexp re = new Regexp(RegexpOp.CAPTURE, flags);
    re.subs = List.of(sub);
    re.cap = cap;
    re.name = name;
    return re;
  }

  /** Creates an ANY_CHAR node matching any character. */
  public static Regexp anyChar(int flags) {
    return new Regexp(RegexpOp.ANY_CHAR, flags);
  }

  /** Creates a BEGIN_LINE node. */
  public static Regexp beginLine(int flags) {
    return new Regexp(RegexpOp.BEGIN_LINE, flags);
  }

  /** Creates an END_LINE node. */
  public static Regexp endLine(int flags) {
    return new Regexp(RegexpOp.END_LINE, flags);
  }

  /** Creates a WORD_BOUNDARY node. */
  public static Regexp wordBoundary(int flags) {
    return new Regexp(RegexpOp.WORD_BOUNDARY, flags);
  }

  /** Creates a NO_WORD_BOUNDARY node. */
  public static Regexp noWordBoundary(int flags) {
    return new Regexp(RegexpOp.NO_WORD_BOUNDARY, flags);
  }

  /** Creates a BEGIN_TEXT node. */
  public static Regexp beginText(int flags) {
    return new Regexp(RegexpOp.BEGIN_TEXT, flags);
  }

  /** Creates an END_TEXT node. */
  public static Regexp endText(int flags) {
    return new Regexp(RegexpOp.END_TEXT, flags);
  }

  /** Creates a CHAR_CLASS node. */
  public static Regexp charClass(CharClass cc, int flags) {
    Regexp re = new Regexp(RegexpOp.CHAR_CLASS, flags);
    re.charClass = cc;
    return re;
  }

  /** Creates a HAVE_MATCH node with the given match ID. */
  public static Regexp haveMatch(int matchId, int flags) {
    Regexp re = new Regexp(RegexpOp.HAVE_MATCH, flags);
    re.matchId = matchId;
    return re;
  }

  // --- Accessors ---

  /** Returns the single sub-expression, or throws if there isn't exactly one. */
  public Regexp sub() {
    if (subs == null || subs.size() != 1) {
      throw new IllegalStateException(op + " does not have exactly one sub-expression");
    }
    return subs.getFirst();
  }

  /** Returns the number of sub-expressions. */
  public int nsub() {
    return (subs != null) ? subs.size() : 0;
  }

  /** Returns true if the {@link ParseFlags#NON_GREEDY} flag is set. */
  public boolean nonGreedy() {
    return (flags & ParseFlags.NON_GREEDY) != 0;
  }

  /** Returns true if the {@link ParseFlags#FOLD_CASE} flag is set. */
  public boolean foldCase() {
    return (flags & ParseFlags.FOLD_CASE) != 0;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    ToStringWalker w = new ToStringWalker(sb);
    w.walkExponential(this, PREC_TOPLEVEL, 100_000);
    if (w.stoppedEarly()) {
      sb.append(" [truncated]");
    }
    return sb.toString();
  }

  // Precedence levels for toString(), matching RE2's tostring.cc.
  private static final int PREC_ATOM = 0;
  private static final int PREC_UNARY = 1;
  private static final int PREC_CONCAT = 2;
  private static final int PREC_ALTERNATE = 3;
  private static final int PREC_EMPTY = 4;
  private static final int PREC_PAREN = 5;
  private static final int PREC_TOPLEVEL = 6;

  /** Walker that generates a string representation by accumulating into a StringBuilder. */
  private static final class ToStringWalker extends Walker<Integer> {
    private final StringBuilder sb;

    ToStringWalker(StringBuilder sb) {
      this.sb = sb;
    }

    @Override
    protected Integer shortVisit(Regexp re, Integer parentArg) {
      return 0;
    }

    @Override
    protected Integer preVisit(Regexp re, Integer parentArg, boolean[] stop) {
      int prec = parentArg;
      int nprec;

      nprec = switch (re.op) {
        case NO_MATCH, EMPTY_MATCH, LITERAL, ANY_CHAR, ANY_BYTE, BEGIN_LINE, END_LINE,
             BEGIN_TEXT, END_TEXT, WORD_BOUNDARY, NO_WORD_BOUNDARY, CHAR_CLASS, HAVE_MATCH ->
            PREC_ATOM;
        case CONCAT, LITERAL_STRING -> {
          if (prec < PREC_CONCAT) {
            sb.append("(?:");
          }
          yield PREC_CONCAT;
        }
        case ALTERNATE -> {
          if (prec < PREC_ALTERNATE) {
            sb.append("(?:");
          }
          yield PREC_ALTERNATE;
        }
        case CAPTURE -> {
          sb.append('(');
          if (re.cap == 0) {
            throw new IllegalStateException("CAPTURE with cap == 0");
          }
          if (re.name != null) {
            sb.append("?<").append(re.name).append('>');
          }
          yield PREC_PAREN;
        }
        case STAR, PLUS, QUEST, REPEAT -> {
          if (prec < PREC_UNARY) {
            sb.append("(?:");
          }
          yield PREC_ATOM;
        }
        default -> PREC_ATOM;
      };
      return nprec;
    }

    @Override
    protected Integer postVisit(
        Regexp re, Integer parentArg, Integer preArg, List<Integer> childArgs) {
      int prec = parentArg;
      switch (re.op) {
        case NO_MATCH -> sb.append("[^\\x00-\\x{10ffff}]");
        case EMPTY_MATCH -> {
          if (prec < PREC_EMPTY) {
            sb.append("(?:)");
          }
        }
        case LITERAL -> appendLiteral(sb, re.rune, (re.flags & ParseFlags.FOLD_CASE) != 0);
        case LITERAL_STRING -> {
          for (int r : re.runes) {
            appendLiteral(sb, r, (re.flags & ParseFlags.FOLD_CASE) != 0);
          }
          if (prec < PREC_CONCAT) {
            sb.append(')');
          }
        }
        case CONCAT -> {
          if (prec < PREC_CONCAT) {
            sb.append(')');
          }
        }
        case ALTERNATE -> {
          // Children appended '|' after themselves; remove the trailing one.
          if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '|') {
            sb.setLength(sb.length() - 1);
          }
          if (prec < PREC_ALTERNATE) {
            sb.append(')');
          }
        }
        case STAR -> {
          sb.append('*');
          if ((re.flags & ParseFlags.NON_GREEDY) != 0) {
            sb.append('?');
          }
          if (prec < PREC_UNARY) {
            sb.append(')');
          }
        }
        case PLUS -> {
          sb.append('+');
          if ((re.flags & ParseFlags.NON_GREEDY) != 0) {
            sb.append('?');
          }
          if (prec < PREC_UNARY) {
            sb.append(')');
          }
        }
        case QUEST -> {
          sb.append('?');
          if ((re.flags & ParseFlags.NON_GREEDY) != 0) {
            sb.append('?');
          }
          if (prec < PREC_UNARY) {
            sb.append(')');
          }
        }
        case REPEAT -> {
          if (re.max == -1) {
            sb.append('{').append(re.min).append(",}");
          } else if (re.min == re.max) {
            sb.append('{').append(re.min).append('}');
          } else {
            sb.append('{').append(re.min).append(',').append(re.max).append('}');
          }
          if ((re.flags & ParseFlags.NON_GREEDY) != 0) {
            sb.append('?');
          }
          if (prec < PREC_UNARY) {
            sb.append(')');
          }
        }
        case ANY_CHAR -> sb.append('.');
        case ANY_BYTE -> sb.append("\\C");
        case BEGIN_LINE -> sb.append('^');
        case END_LINE -> sb.append('$');
        case BEGIN_TEXT -> sb.append("(?-m:^)");
        case END_TEXT -> {
          if ((re.flags & ParseFlags.WAS_DOLLAR) != 0) {
            sb.append("(?-m:$)");
          } else {
            sb.append("\\z");
          }
        }
        case WORD_BOUNDARY -> sb.append("\\b");
        case NO_WORD_BOUNDARY -> sb.append("\\B");
        case CHAR_CLASS -> appendCharClass(sb, re.charClass);
        case CAPTURE -> sb.append(')');
        case HAVE_MATCH -> sb.append("(?HaveMatch:").append(re.matchId).append(')');
        default -> sb.append("[").append(re.op).append("]");
      }

      // If the parent is an alternation, append | separator.
      if (prec == PREC_ALTERNATE) {
        sb.append('|');
      }
      return 0;
    }
  }

  /** Appends a literal code point, escaping metacharacters as needed. */
  private static void appendLiteral(StringBuilder sb, int r, boolean foldCase) {
    if (r != 0 && r < 0x80 && "(){}[]*+?|.^$\\".indexOf((char) r) >= 0) {
      sb.append('\\');
      sb.appendCodePoint(r);
    } else if (foldCase && 'a' <= r && r <= 'z') {
      int upper = r - ('a' - 'A');
      sb.append('[');
      sb.append((char) upper);
      sb.append((char) r);
      sb.append(']');
    } else {
      appendCCRange(sb, r, r);
    }
  }

  /** Appends a full character class, using negation heuristic based on 0xFFFE membership. */
  private static void appendCharClass(StringBuilder sb, CharClass cc) {
    if (cc.numRanges() == 0) {
      sb.append("[^\\x00-\\x{10ffff}]");
      return;
    }
    sb.append('[');
    boolean negated = cc.contains(0xFFFE);
    CharClass display = cc;
    if (negated) {
      display = cc.negate();
      sb.append('^');
    }
    for (int i = 0; i < display.numRanges(); i++) {
      appendCCRange(sb, display.lo(i), display.hi(i));
    }
    sb.append(']');
  }

  /** Appends a character class range [lo, hi]. */
  private static void appendCCRange(StringBuilder sb, int lo, int hi) {
    if (lo > hi) {
      return;
    }
    appendCCChar(sb, lo);
    if (lo < hi) {
      sb.append('-');
      appendCCChar(sb, hi);
    }
  }

  /** Appends a single code point formatted for use inside a character class. */
  private static void appendCCChar(StringBuilder sb, int r) {
    if (0x20 <= r && r <= 0x7E) {
      if ("[]^-\\".indexOf((char) r) >= 0) {
        sb.append('\\');
      }
      sb.append((char) r);
      return;
    }
    switch (r) {
      case '\r' -> {
        sb.append("\\r");
        return;
      }
      case '\t' -> {
        sb.append("\\t");
        return;
      }
      case '\n' -> {
        sb.append("\\n");
        return;
      }
      case '\f' -> {
        sb.append("\\f");
        return;
      }
      default -> {}
    }
    if (r < 0x100) {
      sb.append(String.format("\\x%02x", r));
    } else {
      sb.append(String.format("\\x{%x}", r));
    }
  }
}
