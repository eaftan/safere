// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import java.util.Arrays;

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
public final class Regexp {

  /** The operator for this node. */
  public final RegexpOp op;

  /** Parse flags in effect for this node. */
  public final int flags;

  /** Sub-expressions (children). Used by CONCAT, ALTERNATE, STAR, PLUS, QUEST, REPEAT, CAPTURE. */
  public Regexp[] subs;

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
  public static Regexp concat(Regexp[] subs, int flags) {
    Regexp re = new Regexp(RegexpOp.CONCAT, flags);
    re.subs = Arrays.copyOf(subs, subs.length);
    return re;
  }

  /** Creates an ALTERNATE node matching any one of the given sub-expressions. */
  public static Regexp alternate(Regexp[] subs, int flags) {
    Regexp re = new Regexp(RegexpOp.ALTERNATE, flags);
    re.subs = Arrays.copyOf(subs, subs.length);
    return re;
  }

  /** Creates a STAR node matching the sub-expression zero or more times. */
  public static Regexp star(Regexp sub, int flags) {
    Regexp re = new Regexp(RegexpOp.STAR, flags);
    re.subs = new Regexp[] {sub};
    return re;
  }

  /** Creates a PLUS node matching the sub-expression one or more times. */
  public static Regexp plus(Regexp sub, int flags) {
    Regexp re = new Regexp(RegexpOp.PLUS, flags);
    re.subs = new Regexp[] {sub};
    return re;
  }

  /** Creates a QUEST node matching the sub-expression zero or one times. */
  public static Regexp quest(Regexp sub, int flags) {
    Regexp re = new Regexp(RegexpOp.QUEST, flags);
    re.subs = new Regexp[] {sub};
    return re;
  }

  /**
   * Creates a REPEAT node matching the sub-expression between {@code min} and {@code max} times. A
   * max of -1 means unbounded.
   */
  public static Regexp repeat(Regexp sub, int flags, int min, int max) {
    Regexp re = new Regexp(RegexpOp.REPEAT, flags);
    re.subs = new Regexp[] {sub};
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
    re.subs = new Regexp[] {sub};
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
    if (subs == null || subs.length != 1) {
      throw new IllegalStateException(op + " does not have exactly one sub-expression");
    }
    return subs[0];
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
    // Minimal toString for debugging; a full implementation will come in Phase 4.
    return switch (op) {
      case NO_MATCH -> "[nomatch]";
      case EMPTY_MATCH -> "[empty]";
      case LITERAL -> Utils.runeToString(rune);
      case LITERAL_STRING -> {
        StringBuilder sb = new StringBuilder();
        for (int r : runes) {
          sb.appendCodePoint(r);
        }
        yield sb.toString();
      }
      case CONCAT -> {
        StringBuilder sb = new StringBuilder();
        for (Regexp sub : subs) {
          sb.append(sub);
        }
        yield sb.toString();
      }
      case ALTERNATE -> {
        StringBuilder sb = new StringBuilder("(?:");
        for (int i = 0; i < subs.length; i++) {
          if (i > 0) {
            sb.append('|');
          }
          sb.append(subs[i]);
        }
        sb.append(')');
        yield sb.toString();
      }
      case STAR -> subs[0] + "*";
      case PLUS -> subs[0] + "+";
      case QUEST -> subs[0] + "?";
      case REPEAT -> {
        if (max == -1) {
          yield subs[0] + "{" + min + ",}";
        } else {
          yield subs[0] + "{" + min + "," + max + "}";
        }
      }
      case CAPTURE -> "(" + subs[0] + ")";
      case ANY_CHAR -> ".";
      case BEGIN_LINE -> "^";
      case END_LINE -> "$";
      case WORD_BOUNDARY -> "\\b";
      case NO_WORD_BOUNDARY -> "\\B";
      case BEGIN_TEXT -> "\\A";
      case END_TEXT -> "\\z";
      case CHAR_CLASS -> charClass.toString();
      case HAVE_MATCH -> "[match " + matchId + "]";
      default -> "[" + op + "]";
    };
  }
}
