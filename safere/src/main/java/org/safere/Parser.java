// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

/**
 * Parser for SafeRE's {@link Pattern} syntax. Converts a JDK-compatible regular expression string
 * into a {@link Regexp} AST, rejecting unsupported non-regular constructs rather than accepting
 * another regex dialect as an extension.
 *
 * <p>This is a stack-based operator-precedence parser derived from RE2's {@code parse.cc}. The
 * parser's accepted language is governed by {@code java.util.regex.Pattern} compatibility and the
 * linear-time execution contract, not by RE2 source syntax compatibility.
 */
final class Parser {

  // Maximum repeat count to prevent excessive AST expansion. Matches RE2's kMaxRepeat.
  private static final int MAX_REPEAT = 1000;

  // Pseudo-ops used only on the parse stack (never in the final AST).
  // Values must be negative so that isMarker()/tag() can distinguish them from
  // real HAVE_MATCH matchIds (which are non-negative).
  private static final int LEFT_PAREN = -1;
  private static final int VERTICAL_BAR = -2;

  // Stack entry: wraps a Regexp with linked-list pointer and extra metadata for parens.
  private static final class StackEntry {
    Regexp re;
    StackEntry down;
    // For LEFT_PAREN entries:
    int cap; // capture index, or -1 for non-capturing
    String name; // capture name, or null
    int savedFlags; // flags at time of paren open

    StackEntry(Regexp re) {
      this.re = re;
    }
  }

  private static final class RepeatCount {
    final int cost;
    final boolean hasRepeat;

    RepeatCount(int cost, boolean hasRepeat) {
      this.cost = cost;
      this.hasRepeat = hasRepeat;
    }
  }

  private static final class RepeatCountFrame {
    final Regexp re;
    final int multiplier;
    int nextSub;
    int cost;
    boolean hasRepeat;

    RepeatCountFrame(Regexp re) {
      this.re = re;
      if (re.op == RegexpOp.REPEAT) {
        int repeatMultiplier = re.max;
        if (repeatMultiplier < 0) {
          repeatMultiplier = re.min;
        }
        if (repeatMultiplier <= 0) {
          repeatMultiplier = 1;
        }
        multiplier = repeatMultiplier;
      } else {
        multiplier = 1;
      }
      cost = re.op == RegexpOp.CONCAT ? 0 : 1;
    }

    void addChild(RepeatCount child, int limit) {
      switch (re.op) {
        case ALTERNATE -> {
          cost = Math.max(cost, child.cost);
          hasRepeat |= child.hasRepeat;
        }
        case CONCAT -> {
          if (child.hasRepeat) {
            cost = addSaturated(cost, child.cost, limit);
            hasRepeat = true;
          }
        }
        default -> {
          if (child.cost > cost) {
            cost = child.cost;
            hasRepeat = child.hasRepeat;
          }
        }
      }
    }

    RepeatCount finish(int limit) {
      int subCost = cost;
      boolean subHasRepeat = hasRepeat;
      if (re.op == RegexpOp.CONCAT && !subHasRepeat) {
        subCost = 1;
      }
      int totalCost = multiplySaturated(multiplier, subCost, limit);
      return new RepeatCount(totalCost, re.op == RegexpOp.REPEAT || subHasRepeat);
    }
  }

  // Parse state
  private int flags;
  private final String pattern;
  private int pos; // current parse position (char index into pattern)
  private StackEntry stacktop;
  private int ncap;
  private final int runeMax;
  private final Set<String> namedCaptures = new HashSet<>();

  private Parser(String pattern, int flags) {
    this.pattern = pattern;
    this.flags = flags;
    this.pos = 0;
    this.stacktop = null;
    this.ncap = 0;
    this.runeMax = Utils.MAX_RUNE;
  }

  private static boolean isCommentsWhitespace(int cp) {
    return cp == ' ' || ('\t' <= cp && cp <= '\r');
  }

  /**
   * Parses a regular expression pattern into a {@link Regexp} AST.
   *
   * @param pattern the regular expression pattern
   * @param flags parse flags from {@link ParseFlags}
   * @return the parsed AST
   * @throws PatternSyntaxException if the pattern is invalid
   */
  public static Regexp parse(String pattern, int flags) {
    Parser p = new Parser(pattern, flags);
    return p.doParse();
  }

  // ---- Comments mode helpers ----

  /**
   * If comments mode ({@link ParseFlags#COMMENTS}) is active, skips whitespace characters and
   * {@code #}-to-end-of-line comments at the current position. Advances {@link #pos} past any
   * skipped content.
   *
   * <p>This implements the behavior of Java's {@link java.util.regex.Pattern#COMMENTS} flag and
   * Perl's {@code (?x)} mode. Whitespace and comments become insignificant, allowing patterns to be
   * formatted with whitespace and annotations for readability.
   */
  private void skipCommentsAndWhitespace() {
    pos = skipCommentsAndWhitespaceAt(pos);
  }

  private int skipCommentsAndWhitespaceAt(int index) {
    while (index < pattern.length()) {
      int c = pattern.codePointAt(index);
      if (c == '#') {
        // Skip from '#' to end of line (or end of pattern).
        index++;
        while (index < pattern.length()) {
          int commentChar = pattern.codePointAt(index);
          if (isCommentTerminator(commentChar)) {
            break;
          }
          index += Character.charCount(commentChar);
        }
      } else if (isCommentsWhitespace(c)) {
        index += Character.charCount(c);
      } else {
        break;
      }
    }
    return index;
  }

  private void skipQuantifierModifierTrivia() {
    if ((flags & ParseFlags.COMMENTS) != 0) {
      skipCommentsAndWhitespace();
    }
  }

  private boolean isCommentTerminator(int c) {
    if (c == '\0' || c == '\n') {
      return true;
    }
    return (flags & ParseFlags.UNIX_LINES) == 0 && Nfa.isLineTerminator(c);
  }

  // ---- Main parse method ----

  private Regexp doParse() {
    if ((flags & ParseFlags.LITERAL) != 0) {
      // Special parse loop for literal string.
      int i = 0;
      while (i < pattern.length()) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        int r = pattern.codePointAt(i);
        i += Character.charCount(r);
        pushLiteral(r);
      }
      return doFinish();
    }

    String lastunary = null;
    boolean lastUnaryWasBareGreedyPlus = false;
    boolean lastTokenNonRepeatable = false;
    boolean lastTokenWasEmptyQuotedLiteral = false;
    while (pos < pattern.length()) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      // In comments mode, skip whitespace and #-comments before each token.
      if ((flags & ParseFlags.COMMENTS) != 0) {
        skipCommentsAndWhitespace();
        if (pos >= pattern.length()) {
          break;
        }
      }
      String isunary = null;
      boolean isBareGreedyPlusUnary = false;
      boolean isNonRepeatable = false;
      int c = pattern.codePointAt(pos);
      switch (c) {
        case '(' -> {
          // "(?" introduces Perl escape.
          if ((flags & ParseFlags.PERL_X) != 0
              && pos + 1 < pattern.length()
              && pattern.charAt(pos + 1) == '?') {
            isNonRepeatable = parsePerlFlags();
            break;
          }
          if ((flags & ParseFlags.NEVER_CAPTURE) != 0) {
            doLeftParenNoCapture();
          } else {
            doLeftParen(null);
          }
          pos++; // '('
        }
        case '|' -> {
          doVerticalBar();
          pos++; // '|'
        }
        case ')' -> {
          doRightParen();
          pos++; // ')'
        }
        case '^' -> {
          pushCaret();
          pos++; // '^'
        }
        case '$' -> {
          pushDollar();
          pos++; // '$'
        }
        case '.' -> {
          pushDot();
          pos++; // '.'
        }
        case '[' -> {
          Regexp re = parseCharClass();
          pushRegexp(re);
        }
        case '*', '+', '?' -> {
          if (lastTokenNonRepeatable) {
            throw new PatternSyntaxException(
                "missing argument to repetition operator", pattern, pos);
          }
          RegexpOp op = c == '*' ? RegexpOp.STAR : c == '+' ? RegexpOp.PLUS : RegexpOp.QUEST;
          int opStart = pos;
          pos++; // the operator
          boolean nongreedy = false;
          boolean possessive = false;
          if ((flags & ParseFlags.PERL_X) != 0) {
            skipQuantifierModifierTrivia();
            if (pos < pattern.length() && pattern.charAt(pos) == '+') {
              if (!canIgnorePossessiveModifierOnZeroWidthOperand()) {
                throw new PatternSyntaxException(
                    "possessive quantifiers are not supported", pattern, opStart);
              }
              possessive = true;
              pos++; // '+'
            }
            if (!possessive && pos < pattern.length() && pattern.charAt(pos) == '?') {
              nongreedy = true;
              pos++; // '?'
            }
            if (lastunary != null && lastTokenWasEmptyQuotedLiteral && c != '*') {
              isunary = lastunary;
              isBareGreedyPlusUnary = lastUnaryWasBareGreedyPlus;
              break;
            }
            if (lastunary != null && !canRepeatAfterUnary(op, lastUnaryWasBareGreedyPlus)) {
              throw new PatternSyntaxException(
                  "invalid nested repetition operator", pattern, opStart);
            }
          }
          String opstr = pattern.substring(opStart, pos);
          if (possessive && op == RegexpOp.STAR && canObservePossessiveZeroWidthIteration()) {
            pushPossessiveZeroWidthRepeatOp(op);
          } else {
            pushRepeatOp(op, opstr, nongreedy);
          }
          isunary = opstr;
          isBareGreedyPlusUnary = op == RegexpOp.PLUS && !nongreedy && !possessive;
        }
        case '{' -> {
          int opStart = pos;
          int[] lohi = maybeParseRepetition();
          if (lohi == null) {
            throw new PatternSyntaxException("Illegal repetition", pattern, opStart);
          }
          int lo = lohi[0];
          int hi = lohi[1];
          boolean nongreedy = false;
          boolean possessive = false;
          if ((flags & ParseFlags.PERL_X) != 0) {
            skipQuantifierModifierTrivia();
            if (pos < pattern.length() && pattern.charAt(pos) == '+') {
              // A counted repetition with no operand of its own, as at the start of a sequence
              // or after another quantifier, repeats an empty expression in the JDK, so a
              // possessive modifier on it cannot change the match.
              boolean implicitEmptyOperand =
                  lastTokenNonRepeatable
                      || lastunary != null
                      || stacktop == null
                      || isMarker(stacktop);
              if (!implicitEmptyOperand && !canIgnorePossessiveModifierOnZeroWidthOperand()) {
                throw new PatternSyntaxException(
                    "possessive quantifiers are not supported", pattern, opStart);
              }
              possessive = true;
              pos++; // '+'
            }
            if (!possessive && pos < pattern.length() && pattern.charAt(pos) == '?') {
              nongreedy = true;
              pos++; // '?'
            }
          }
          String opstr = pattern.substring(opStart, pos);
          if (lastTokenNonRepeatable) {
            validateRepeatCount(lo, hi, opstr);
            isNonRepeatable = true;
            break;
          }
          if (lastunary != null) {
            validateRepeatCount(lo, hi, opstr);
            isunary = opstr;
            break;
          }
          if (possessive && lo == 0 && hi != 0 && canObservePossessiveZeroWidthIteration()) {
            pushPossessiveZeroWidthRepeatRange(hi);
          } else {
            pushRepetition(lo, hi, opstr, nongreedy);
          }
          isunary = opstr;
        }
        case '\\' -> {
          if (parseBackslash()) {
            if (stacktop == null || isMarker(stacktop) || lastTokenNonRepeatable) {
              lastunary = null;
              lastUnaryWasBareGreedyPlus = false;
              lastTokenNonRepeatable = true;
            }
            lastTokenWasEmptyQuotedLiteral = true;
            continue;
          }
        }
        default -> {
          pos += Character.charCount(c);
          pushLiteral(c);
        }
      }
      lastunary = isunary;
      lastUnaryWasBareGreedyPlus = isBareGreedyPlusUnary;
      lastTokenNonRepeatable = isNonRepeatable;
      lastTokenWasEmptyQuotedLiteral = false;
    }
    return doFinish();
  }

  // ---- Backslash handling (top-level) ----

  private boolean parseBackslash() {
    // \b and \B: word boundary or not
    if ((flags & ParseFlags.PERL_B) != 0
        && pos + 1 < pattern.length()
        && (pattern.charAt(pos + 1) == 'b' || pattern.charAt(pos + 1) == 'B')) {
      // \b{g}: grapheme cluster boundary.
      if (pattern.charAt(pos + 1) == 'b'
          && pos + 4 < pattern.length()
          && pattern.charAt(pos + 2) == '{'
          && pattern.charAt(pos + 3) == 'g'
          && pattern.charAt(pos + 4) == '}') {
        pos += 5; // '\\', 'b', '{', 'g', '}'
        pushSimpleOp(RegexpOp.GRAPHEME_CLUSTER_BOUNDARY);
        return false;
      }
      pushWordBoundary(pattern.charAt(pos + 1) == 'b');
      pos += 2; // '\\', 'b' or 'B'
      return false;
    }

    if ((flags & ParseFlags.PERL_X) != 0 && pos + 1 < pattern.length()) {
      char next = pattern.charAt(pos + 1);
      if (next == 'A') {
        pushSimpleOp(RegexpOp.BEGIN_TEXT);
        pos += 2;
        return false;
      }
      if (next == 'z') {
        pushSimpleOp(RegexpOp.END_TEXT);
        pos += 2;
        return false;
      }
      if (next == 'Z') {
        // \Z matches at end of input or before a final newline, same as $ in non-multiline mode.
        int oflags = flags;
        flags |= ParseFlags.WAS_DOLLAR;
        pushSimpleOp(RegexpOp.END_TEXT);
        flags = oflags;
        pos += 2;
        return false;
      }
      if (next == 'G') {
        throw new PatternSyntaxException(
            "\\G (end of previous match) is not supported", pattern, pos);
      }
      if (next == 'Q') {
        // \Q ... \E: the ... is always literals
        pos += 2; // '\\', 'Q'
        boolean sawLiteral = false;
        while (pos < pattern.length()) {
          if (pos + 1 < pattern.length()
              && pattern.charAt(pos) == '\\'
              && pattern.charAt(pos + 1) == 'E') {
            pos += 2; // '\\', 'E'
            break;
          }
          int r = pattern.codePointAt(pos);
          pos += Character.charCount(r);
          pushLiteral(r);
          sawLiteral = true;
        }
        return !sawLiteral;
      }
    }

    // \R: Unicode linebreak sequence.
    // Equivalent to (?:\r\n|[\n\x0B\f\r\x{85}\x{2028}\x{2029}]).
    if (pos + 1 < pattern.length() && pattern.charAt(pos + 1) == 'R') {
      pos += 2; // '\\', 'R'
      pushRegexp(buildLinebreakRegexp());
      return false;
    }

    // \X: Extended grapheme cluster.
    if (pos + 1 < pattern.length() && pattern.charAt(pos + 1) == 'X') {
      pos += 2; // '\\', 'X'
      pushRegexp(Regexp.graphemeCluster(flags));
      return false;
    }

    // Unicode group \p{...} or \P{...}
    if (pos + 1 < pattern.length()
        && (pattern.charAt(pos + 1) == 'p' || pattern.charAt(pos + 1) == 'P')) {
      CharClassBuilder ccb = new CharClassBuilder();
      int saved = pos;
      int result = parseUnicodeGroup(ccb);
      if (result == PARSE_OK) {
        Regexp re = finishCharClassBuilder(ccb);
        pushRegexp(re);
        return false;
      } else if (result == PARSE_ERROR) {
        // error already thrown by parseUnicodeGroup
        return false;
      }
      // PARSE_NOTHING: fall through
      pos = saved;
    }

    // Perl character class \d, \D, \s, \S, \w, \W
    {
      int saved = pos;
      CharClassBuilder ccb = maybeParsePerlCCEscape();
      if (ccb != null) {
        Regexp re = finishCharClassBuilder(ccb);
        pushRegexp(re);
        return false;
      }
      pos = saved;
    }

    // Regular escape
    int r = parseEscape();
    pushLiteral(r);
    return false;
  }

  // ---- Stack operations ----

  private static boolean isMarker(StackEntry e) {
    return e != null && e.re != null && e.re.matchId < 0 && e.re.op == RegexpOp.NO_MATCH;
  }

  private static boolean isLeftParen(StackEntry e) {
    return isMarker(e) && e.re.matchId == LEFT_PAREN;
  }

  private static boolean isVerticalBar(StackEntry e) {
    return isMarker(e) && e.re.matchId == VERTICAL_BAR;
  }

  private StackEntry newMarker(int markerTag) {
    Regexp re = Regexp.noMatch(flags);
    re.matchId = markerTag;
    StackEntry e = new StackEntry(re);
    if (markerTag == LEFT_PAREN) {
      e.savedFlags = flags;
    }
    return e;
  }

  private void pushRegexp(Regexp re) {
    // Special case: a character class of one character is just a literal.
    if (re.op == RegexpOp.CHAR_CLASS && re.charClass != null) {
      CharClass cc = re.charClass;
      if (cc.numRanges() == 1 && cc.lo(0) == cc.hi(0)) {
        int r = cc.lo(0);
        re = Regexp.literal(r, re.flags);
      }
    }

    StackEntry e = new StackEntry(re);
    e.down = stacktop;
    stacktop = e;
  }

  private void pushLiteral(int r) {
    // Do case folding if needed.
    if ((flags & ParseFlags.FOLD_CASE) != 0 && (flags & ParseFlags.UNICODE_CASE) != 0) {
      if (UnicodeCaseFolding.hasUnicodeCaseVariant(r) || UnicodeCaseFolding.cycleFoldRune(r) != r) {
        CharClassBuilder ccb = new CharClassBuilder();
        UnicodeCaseFolding.addUnicodeFoldedRange(ccb, r, r);
        Regexp re = finishCharClassBuilder(ccb);
        pushRegexp(re);
        return;
      }
    }

    // Exclude newline if applicable.
    if ((flags & ParseFlags.NEVER_NL) != 0 && r == '\n') {
      pushRegexp(Regexp.noMatch(flags));
      return;
    }

    // No fancy stuff worked. Ordinary literal.
    int literalFlags = flags;
    if ((flags & ParseFlags.FOLD_CASE) != 0
        && (flags & ParseFlags.UNICODE_CASE) == 0
        && UnicodeCaseFolding.asciiFoldRune(r) == r
        && !('a' <= r && r <= 'z')) {
      literalFlags &= ~ParseFlags.FOLD_CASE;
    }
    Regexp re = Regexp.literal(r, literalFlags);
    pushRegexp(re);
  }

  private void pushCaret() {
    if ((flags & ParseFlags.ONE_LINE) != 0) {
      pushSimpleOp(RegexpOp.BEGIN_TEXT);
    } else {
      pushSimpleOp(RegexpOp.BEGIN_LINE);
    }
  }

  private void pushDollar() {
    if ((flags & ParseFlags.ONE_LINE) != 0) {
      int oflags = flags;
      flags |= ParseFlags.WAS_DOLLAR;
      pushSimpleOp(RegexpOp.END_TEXT);
      flags = oflags;
    } else {
      pushSimpleOp(RegexpOp.END_LINE);
    }
  }

  private void pushDot() {
    if ((flags & ParseFlags.DOT_NL) != 0 && (flags & ParseFlags.NEVER_NL) == 0) {
      pushSimpleOp(RegexpOp.ANY_CHAR);
    } else if ((flags & ParseFlags.UNIX_LINES) != 0) {
      // UNIX_LINES: . matches everything except \n
      CharClassBuilder ccb = new CharClassBuilder();
      ccb.addRange(0, '\n' - 1);
      ccb.addRange('\n' + 1, runeMax);
      Regexp re = Regexp.charClass(ccb.build(), flags & ~ParseFlags.FOLD_CASE);
      pushRegexp(re);
    } else {
      // Default JDK behavior: . matches everything except line terminators
      // (\n, \r, \u0085, \u2028, \u2029)
      CharClassBuilder ccb = new CharClassBuilder();
      ccb.addRange(0, '\n' - 1); // 0x00–0x09
      ccb.addRange('\n' + 1, '\r' - 1); // 0x0B–0x0C
      ccb.addRange('\r' + 1, '\u0085' - 1); // 0x0E–0x0084
      ccb.addRange('\u0085' + 1, '\u2028' - 1); // 0x0086–0x2027
      ccb.addRange('\u2029' + 1, runeMax); // 0x202A–max
      Regexp re = Regexp.charClass(ccb.build(), flags & ~ParseFlags.FOLD_CASE);
      pushRegexp(re);
    }
  }

  private void pushWordBoundary(boolean word) {
    if (word) {
      pushSimpleOp(RegexpOp.WORD_BOUNDARY);
    } else {
      pushSimpleOp(RegexpOp.NO_WORD_BOUNDARY);
    }
  }

  private void pushSimpleOp(RegexpOp op) {
    Regexp re =
        switch (op) {
          case BEGIN_LINE -> Regexp.beginLine(flags);
          case END_LINE -> Regexp.endLine(flags);
          case BEGIN_TEXT -> Regexp.beginText(flags);
          case END_TEXT -> Regexp.endText(flags);
          case ANY_CHAR -> Regexp.anyChar(flags);
          case WORD_BOUNDARY -> Regexp.wordBoundary(flags);
          case NO_WORD_BOUNDARY -> Regexp.noWordBoundary(flags);
          case GRAPHEME_CLUSTER_BOUNDARY -> Regexp.graphemeClusterBoundary(flags);
          case GRAPHEME_CLUSTER -> Regexp.graphemeCluster(flags);
          default -> Regexp.emptyMatch(flags);
        };
    pushRegexp(re);
  }

  private void pushRepeatOp(RegexpOp op, String opstr, boolean nongreedy) {
    if (stacktop == null || isMarker(stacktop)) {
      throw new PatternSyntaxException(
          "missing argument to repetition operator", pattern, pos - opstr.length());
    }

    int fl = flags;
    if (nongreedy) {
      fl ^= ParseFlags.NON_GREEDY;
    }

    // Squash **, ++ and ??.
    if (op == stacktop.re.op && fl == stacktop.re.flags) {
      return;
    }

    // Squash *+, *?, +*, +?, ?* and ?+. They all squash to *.
    if ((stacktop.re.op == RegexpOp.STAR
            || stacktop.re.op == RegexpOp.PLUS
            || stacktop.re.op == RegexpOp.QUEST)
        && fl == stacktop.re.flags) {
      // Replace with star. Since Regexp is immutable, rebuild.
      Regexp sub = stacktop.re.subs.getFirst();
      stacktop.re = Regexp.star(sub, fl);
      return;
    }

    Regexp sub = stacktop.re;
    Regexp re =
        switch (op) {
          case STAR -> Regexp.star(sub, fl);
          case PLUS -> Regexp.plus(sub, fl);
          case QUEST -> Regexp.quest(sub, fl);
          default -> throw new IllegalStateException("unexpected repeat op: " + op);
        };
    stacktop.re = re;
  }

  private void pushPossessiveZeroWidthRepeatOp(RegexpOp op) {
    switch (op) {
      case STAR -> {
        Regexp sub = stacktop.re;
        stacktop.re = Regexp.rawQuantifier(RegexpOp.QUEST, Regexp.plus(sub, flags), flags);
      }
      case PLUS -> pushRepeatOp(RegexpOp.PLUS, "+", false);
      case QUEST -> {
        // Best-effort zero-width possessive normalization: prefer the one-iteration capture path.
      }
      default -> throw new IllegalStateException("unexpected repeat op: " + op);
    }
  }

  private void pushPossessiveZeroWidthRepeatRange(int max) {
    Regexp sub = stacktop.re;
    Regexp positiveRepeat = max == 1 ? sub : Regexp.repeat(sub, flags, 1, max);
    stacktop.re = Regexp.rawQuantifier(RegexpOp.QUEST, positiveRepeat, flags);
  }

  private boolean canRepeatAfterUnary(RegexpOp op, boolean lastUnaryWasBareGreedyPlus) {
    return lastUnaryWasBareGreedyPlus
        && op == RegexpOp.PLUS
        && stacktop != null
        && stacktop.re.op == RegexpOp.PLUS
        && !stacktop.re.nonGreedy()
        && isQuantifiedZeroWidth(stacktop.re);
  }

  private boolean canIgnorePossessiveModifierOnZeroWidthOperand() {
    return stacktop != null && !isMarker(stacktop) && isZeroWidth(stacktop.re);
  }

  private boolean canObservePossessiveZeroWidthIteration() {
    return stacktop != null
        && !isMarker(stacktop)
        && hasCapture(stacktop.re)
        && isZeroWidth(stacktop.re);
  }

  private static boolean hasCapture(Regexp re) {
    ArrayDeque<Regexp> pending = new ArrayDeque<>();
    pending.add(re);
    while (!pending.isEmpty()) {
      Regexp current = pending.removeLast();
      if (current.op == RegexpOp.CAPTURE && current.cap > 0) {
        return true;
      }
      if (current.subs != null) {
        pending.addAll(current.subs);
      }
    }
    return false;
  }

  private static boolean isQuantifiedZeroWidth(Regexp re) {
    return switch (re.op) {
      case STAR, PLUS, QUEST, REPEAT -> isZeroWidth(re.subs.getFirst());
      default -> false;
    };
  }

  private static boolean isZeroWidth(Regexp re) {
    ArrayDeque<Regexp> pending = new ArrayDeque<>();
    pending.add(re);
    while (!pending.isEmpty()) {
      Regexp current = pending.removeLast();
      switch (current.op) {
        case EMPTY_MATCH,
            BEGIN_LINE,
            END_LINE,
            WORD_BOUNDARY,
            NO_WORD_BOUNDARY,
            GRAPHEME_CLUSTER_BOUNDARY,
            BEGIN_TEXT,
            END_TEXT -> {
          // Zero-width by definition.
        }
        case CAPTURE, NON_CAPTURE, STAR, PLUS, QUEST, REPEAT ->
            pending.add(current.subs.getFirst());
        case CONCAT, ALTERNATE -> pending.addAll(current.subs);
        default -> {
          return false;
        }
      }
    }
    return true;
  }

  private void pushRepetition(int min, int max, String opstr, boolean nongreedy) {
    validateRepeatCount(min, max, opstr);
    if (stacktop == null || isMarker(stacktop)) {
      pushRegexp(Regexp.emptyMatch(flags));
    }

    int fl = flags;
    if (nongreedy) {
      fl ^= ParseFlags.NON_GREEDY;
    }

    Regexp sub = stacktop.re;
    Regexp re = Regexp.repeat(sub, fl, min, max);
    stacktop.re = re;

    // Check for too-deep nesting of repeats.
    if (min >= 2 || max >= 2) {
      if (countRepeat(stacktop.re, MAX_REPEAT) == 0) {
        throw new PatternSyntaxException("invalid repeat count", pattern, pos - opstr.length());
      }
    }
  }

  private void validateRepeatCount(int min, int max, String opstr) {
    if ((max != -1 && max < min) || min > MAX_REPEAT || max > MAX_REPEAT) {
      throw new PatternSyntaxException("invalid repeat count", pattern, pos - opstr.length());
    }
  }

  // Walk the regexp tree to check that nested repetitions don't exceed limit.
  private static int countRepeat(Regexp re, int limit) {
    RepeatCount count = repeatCount(re, limit);
    if (count.cost > limit) {
      return 0;
    }
    return limit / count.cost;
  }

  private static RepeatCount repeatCount(Regexp re, int limit) {
    ArrayDeque<RepeatCountFrame> stack = new ArrayDeque<>();
    stack.push(new RepeatCountFrame(re));
    RepeatCount result = null;
    while (!stack.isEmpty()) {
      RepeatCountFrame frame = stack.peek();
      int nsub = frame.re.subs == null ? 0 : frame.re.subs.size();
      if (frame.nextSub < nsub) {
        stack.push(new RepeatCountFrame(frame.re.subs.get(frame.nextSub)));
        frame.nextSub++;
        continue;
      }

      result = frame.finish(limit);
      stack.pop();
      if (!stack.isEmpty()) {
        stack.peek().addChild(result, limit);
      }
    }
    return result;
  }

  private static int multiplySaturated(int a, int b, int limit) {
    if (a != 0 && b > limit / a) {
      return limit + 1;
    }
    return a * b;
  }

  private static int addSaturated(int a, int b, int limit) {
    if (b > limit - a) {
      return limit + 1;
    }
    return a + b;
  }

  private void doLeftParen(String name) {
    if (name != null && !namedCaptures.add(name)) {
      throw new PatternSyntaxException(
          "named capturing group <" + name + "> is already defined", pattern, pos);
    }
    StackEntry e = newMarker(LEFT_PAREN);
    e.cap = ++ncap;
    e.name = name;
    e.savedFlags = flags;
    e.down = stacktop;
    stacktop = e;
  }

  private void doLeftParenNoCapture() {
    StackEntry e = newMarker(LEFT_PAREN);
    e.cap = -1;
    e.savedFlags = flags;
    e.down = stacktop;
    stacktop = e;
  }

  private void doVerticalBar() {
    doConcatenation();

    // Below the vertical bar is a list to alternate.
    // Above the vertical bar is a list to concatenate.
    // We just did the concatenation, so either swap
    // the result below the vertical bar or push a new one.
    StackEntry r1 = stacktop;
    StackEntry r2 = r1 != null ? r1.down : null;
    if (r1 != null && r2 != null && isVerticalBar(r2)) {
      // Swap r1 below vertical bar (r2).
      r1.down = r2.down;
      r2.down = r1;
      stacktop = r2;
      return;
    }

    // Push a vertical bar marker.
    StackEntry vbar = newMarker(VERTICAL_BAR);
    vbar.down = stacktop;
    stacktop = vbar;
  }

  private void doRightParen() {
    // Finish current concatenation and alternation.
    doAlternation();

    // The stack should be: LeftParen regexp
    StackEntry r1 = stacktop;
    StackEntry r2 = r1 != null ? r1.down : null;
    if (r1 == null || r2 == null || !isLeftParen(r2)) {
      throw new PatternSyntaxException("unexpected )", pattern, pos);
    }

    // Pop off r1, r2.
    stacktop = r2.down;

    // Restore flags from when paren opened.
    flags = r2.savedFlags;

    // Rewrite LeftParen as capture if needed.
    if (r2.cap > 0) {
      Regexp re = Regexp.capture(r1.re, flags, r2.cap, r2.name);
      pushRegexp(re);
    } else {
      pushRegexp(Regexp.nonCapture(r1.re, flags));
    }
  }

  private Regexp doFinish() {
    doAlternation();
    StackEntry top = stacktop;
    if (top != null && top.down != null) {
      throw new PatternSyntaxException("missing closing )", pattern, pattern.length());
    }
    if (top == null) {
      return Regexp.emptyMatch(flags);
    }
    stacktop = null;
    return top.re;
  }

  private void doConcatenation() {
    StackEntry r1 = stacktop;
    if (r1 == null || isMarker(r1)) {
      // Empty concatenation.
      Regexp re = Regexp.emptyMatch(flags);
      pushRegexp(re);
    }
    doCollapse(RegexpOp.CONCAT);
  }

  private void doAlternation() {
    doVerticalBar();
    // Now stack top is kVerticalBar.
    StackEntry r1 = stacktop;
    stacktop = r1.down;
    doCollapse(RegexpOp.ALTERNATE);
  }

  private void doCollapse(RegexpOp op) {
    // Scan backward to marker, counting children of composite.
    int n = 0;
    for (StackEntry e = stacktop; e != null && !isMarker(e); e = e.down) {
      if (e.re.op == op && e.re.subs != null) {
        n += e.re.subs.size();
      } else {
        n++;
      }
    }

    // If there's just one child, leave it alone.
    if (stacktop != null && !isMarker(stacktop)) {
      StackEntry first = stacktop;
      StackEntry belowFirst = first.down;
      if (belowFirst == null || isMarker(belowFirst)) {
        return; // just one
      }
    }

    // Construct op (alternation or concatenation), flattening op of op.
    // We build the list in reverse order (walking the stack from top), then reverse.
    List<Regexp> subs = new ArrayList<>(n);
    StackEntry next;
    for (StackEntry e = stacktop; e != null && !isMarker(e); e = next) {
      next = e.down;
      if (e.re.op == op && e.re.subs != null) {
        for (int k = e.re.subs.size() - 1; k >= 0; k--) {
          subs.add(e.re.subs.get(k));
        }
      } else {
        subs.add(e.re);
      }
      if (next == null || isMarker(next)) {
        stacktop = next;
        break;
      }
    }
    Collections.reverse(subs);
    if (op == RegexpOp.CONCAT) {
      subs = coalesceLiteralStrings(subs);
    }

    Regexp re;
    if (op == RegexpOp.CONCAT && subs.size() == 1) {
      re = subs.getFirst();
    } else {
      re = op == RegexpOp.CONCAT ? Regexp.concat(subs, flags) : Regexp.alternate(subs, flags);
    }
    StackEntry entry = new StackEntry(re);
    entry.down = stacktop;
    stacktop = entry;
  }

  // ---- Literal string coalescing ----

  private static List<Regexp> coalesceLiteralStrings(List<Regexp> subs) {
    List<Regexp> coalesced = new ArrayList<>(subs.size());
    int literalStart = -1;
    int literalLength = 0;
    int literalFlags = 0;
    for (int i = 0; i < subs.size(); i++) {
      Regexp re = subs.get(i);
      if (!isLiteralStringPart(re)
          || (literalStart >= 0 && literalFold(literalFlags) != literalFold(re.flags))) {
        flushLiteralString(subs, coalesced, literalStart, i, literalLength, literalFlags);
        literalStart = -1;
        literalLength = 0;
        coalesced.add(re);
        continue;
      }

      if (literalStart < 0) {
        literalStart = i;
        literalFlags = re.flags;
      }
      literalLength += literalLength(re);
    }
    flushLiteralString(subs, coalesced, literalStart, subs.size(), literalLength, literalFlags);
    return coalesced;
  }

  private static void flushLiteralString(
      List<Regexp> source,
      List<Regexp> target,
      int start,
      int end,
      int runeCount,
      int literalFlags) {
    if (start < 0) {
      return;
    }
    if (runeCount == 1) {
      target.add(source.get(start));
      return;
    }

    int[] runes = new int[runeCount];
    int out = 0;
    for (int i = start; i < end; i++) {
      Regexp re = source.get(i);
      if (re.op == RegexpOp.LITERAL) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        runes[out++] = re.rune;
      } else {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(re.runes.length);
        }
        System.arraycopy(re.runes, 0, runes, out, re.runes.length);
        out += re.runes.length;
      }
    }
    target.add(Regexp.literalString(runes, literalFlags));
  }

  private static boolean isLiteralStringPart(Regexp re) {
    return re.op == RegexpOp.LITERAL || re.op == RegexpOp.LITERAL_STRING;
  }

  private static boolean literalFold(int flags) {
    return (flags & ParseFlags.FOLD_CASE) != 0;
  }

  private static int literalLength(Regexp re) {
    return re.op == RegexpOp.LITERAL ? 1 : re.runes.length;
  }

  // ---- Character class parsing ----

  private Regexp parseCharClass() {
    CharClassBuilder ccb = parseCharClassBuilder();
    return Regexp.charClass(ccb.build(), flags & ~ParseFlags.FOLD_CASE);
  }

  private CharClassBuilder parseCharClassBuilder() {
    if (pos >= pattern.length() || pattern.charAt(pos) != '[') {
      throw new PatternSyntaxException("internal error", pattern, pos);
    }
    ArrayDeque<ClassExpressionFrame> stack = new ArrayDeque<>();
    int rootStart = pos;
    pos++;
    boolean rootNegated = false;
    if (pos < pattern.length() && pattern.charAt(pos) == '^') {
      rootNegated = true;
      pos++;
    }
    stack.push(new ClassExpressionFrame(rootStart, rootNegated));

    while (!stack.isEmpty()) {
      ClassExpressionFrame frame = stack.peek();
      if ((flags & ParseFlags.COMMENTS) != 0) {
        skipCommentsAndWhitespace();
      }
      if (pos >= pattern.length()) {
        throw new PatternSyntaxException("missing closing ]", pattern, frame.classStart);
      }

      char c = pattern.charAt(pos);

      // Check for closing bracket ']'
      if (c == ']') {
        if (!frame.hasItems && !frame.afterIntersection) {
          // POSIX/JDK rule: ']' as very first element is treated as literal character ']'
          int scalar = parseCCCharacter();
          addScalarClassItem(frame.currentUnion, scalar);
          frame.hasItems = true;
          continue;
        }
        if (frame.afterIntersection) {
          throw new IntentionalDivergenceSyntaxException(
              "empty right side of character class intersection", pattern, pos);
        }
        pos++; // consume ']'
        CharClassBuilder completed = completeClassExpression(frame);
        stack.pop();
        if (stack.isEmpty()) {
          return completed;
        }
        ClassExpressionFrame parent = stack.peek();
        parent.currentUnion.addCharClass(completed);
        parent.hasItems = true;
        parent.afterIntersection = false;
        continue;
      }

      // Check for nested character class '['
      if (c == '[') {
        int classStart = pos;
        pos++;
        boolean negated = false;
        if (pos < pattern.length() && pattern.charAt(pos) == '^') {
          negated = true;
          pos++;
        }
        stack.push(new ClassExpressionFrame(classStart, negated));
        continue;
      }

      // Check for intersection operator '&&'. COMMENTS-mode trivia is lexically insignificant,
      // including between the two ampersands.
      int intersectionEnd = scanClassIntersectionEnd();
      if (intersectionEnd >= 0) {
        int nextToken = skipClassOperatorTriviaAt(intersectionEnd);
        if (nextToken < pattern.length() && pattern.charAt(nextToken) == '&') {
          throw new IntentionalDivergenceSyntaxException(
              "invalid character class intersection", pattern, pos);
        }
        if (!frame.hasItems || frame.afterIntersection) {
          throw new IntentionalDivergenceSyntaxException(
              "empty left side of character class intersection", pattern, pos);
        }
        pos = intersectionEnd;
        if (frame.leftOperand == null) {
          frame.leftOperand = frame.currentUnion;
        } else {
          frame.leftOperand.intersect(frame.currentUnion);
        }
        frame.currentUnion = new CharClassBuilder();
        frame.afterIntersection = true;

        if ((flags & ParseFlags.COMMENTS) != 0) {
          skipCommentsAndWhitespace();
        }
        if (pos < pattern.length()) {
          char next = pattern.charAt(pos);
          if (next == ']') {
            throw new IntentionalDivergenceSyntaxException(
                "empty right side of character class intersection", pattern, pos);
          }
          if (next == '&' && pos + 1 < pattern.length() && pattern.charAt(pos + 1) == '&') {
            throw new IntentionalDivergenceSyntaxException(
                "empty right side of character class intersection", pattern, pos);
          }
          if (next == '-') {
            throw new IntentionalDivergenceSyntaxException(
                "dangling character class '-'", pattern, pos);
          }
        }
        continue;
      }

      // If we are right after an intersection and the next character is unescaped '-', reject
      if (frame.afterIntersection && c == '-') {
        throw new IntentionalDivergenceSyntaxException(
            "dangling character class '-'", pattern, pos);
      }

      // Any other character atom / range / single '&' / quoted literal
      if (parseClassAtomOrRange(frame.currentUnion)) {
        frame.hasItems = true;
        frame.afterIntersection = false;
      }
    }

    throw new PatternSyntaxException("missing closing ]", pattern, rootStart);
  }

  private int scanClassIntersectionEnd() {
    if (pattern.charAt(pos) != '&') {
      return -1;
    }
    int secondAmpersand = skipClassOperatorTriviaAt(pos + 1);
    if (secondAmpersand >= pattern.length() || pattern.charAt(secondAmpersand) != '&') {
      return -1;
    }
    return secondAmpersand + 1;
  }

  private int skipClassOperatorTriviaAt(int index) {
    while (true) {
      int next = index;
      if ((flags & ParseFlags.COMMENTS) != 0) {
        next = skipCommentsAndWhitespaceAt(next);
      }
      if (startsEmptyQuotedLiteralAt(next)) {
        index = next + 4;
        continue;
      }
      return next;
    }
  }

  private CharClassBuilder completeClassExpression(ClassExpressionFrame frame) {
    CharClassBuilder result;
    if (frame.leftOperand != null) {
      frame.leftOperand.intersect(frame.currentUnion);
      result = frame.leftOperand;
    } else {
      result = frame.currentUnion;
    }
    if (frame.negated) {
      if ((flags & ParseFlags.CLASS_NL) == 0 || (flags & ParseFlags.NEVER_NL) != 0) {
        result.addRune('\n');
      }
      result.negate();
    }
    return result;
  }

  private static final class ClassExpressionFrame {
    final int classStart;
    final boolean negated;
    CharClassBuilder leftOperand;
    CharClassBuilder currentUnion = new CharClassBuilder();
    boolean afterIntersection;
    boolean hasItems;

    ClassExpressionFrame(int classStart, boolean negated) {
      this.classStart = classStart;
      this.negated = negated;
    }
  }

  private static final class IntentionalDivergenceSyntaxException extends PatternSyntaxException {
    private static final long serialVersionUID = 1L;

    IntentionalDivergenceSyntaxException(String description, String regex, int index) {
      super(description, regex, index);
    }
  }

  private boolean parseClassAtomOrRange(CharClassBuilder ccb) {
    // Look for Unicode character group like \p{Han}
    if (pos + 2 < pattern.length()
        && pattern.charAt(pos) == '\\'
        && (pattern.charAt(pos + 1) == 'p' || pattern.charAt(pos + 1) == 'P')) {
      int result = parseUnicodeGroup(ccb);
      if (result == PARSE_OK) {
        return true;
      } else if (result == PARSE_ERROR) {
        throw new PatternSyntaxException("invalid Unicode group", pattern, pos);
      }
      // PARSE_NOTHING: fall through
    }

    // Look for Perl character class symbols.
    {
      int saved = pos;
      CharClassBuilder perlCcb = maybeParsePerlCCEscape();
      if (perlCcb != null) {
        ccb.addCharClass(perlCcb);
        return true;
      }
      pos = saved;
    }

    if (startsQuotedLiteral()) {
      return addQuotedLiteralClassItem(ccb);
    }

    int scalar = parseCCCharacter();
    addScalarClassItem(ccb, scalar);
    return true;
  }

  private boolean addQuotedLiteralClassItem(CharClassBuilder ccb) {
    int[] literals = parseQuotedLiteralSequence();
    if (literals.length == 0) {
      return false;
    }
    for (int i = 0; i + 1 < literals.length; i++) {
      addRangeFlags(ccb, literals[i], literals[i], flags | ParseFlags.CLASS_NL);
    }
    addScalarClassItem(ccb, literals[literals.length - 1]);
    return true;
  }

  private void addScalarClassItem(CharClassBuilder ccb, int lo) {
    int hi = lo;
    // In comments mode, skip whitespace before checking for '-'.
    if ((flags & ParseFlags.COMMENTS) != 0) {
      skipCommentsAndWhitespace();
    }
    while (startsEmptyQuotedLiteralAt(pos)) {
      pos += 4;
      if ((flags & ParseFlags.COMMENTS) != 0) {
        skipCommentsAndWhitespace();
      }
    }
    if (pos < pattern.length() && pattern.charAt(pos) == '-') {
      if (hasRangeEndpointAfterHyphen()) {
        pos++; // '-'
        // In comments mode, skip whitespace after '-'.
        if ((flags & ParseFlags.COMMENTS) != 0) {
          skipCommentsAndWhitespace();
        }
        RangeEndpoint endpoint = parseCCRangeEndpoint();
        hi = endpoint.first;
        if (hi < lo) {
          throw new PatternSyntaxException("invalid character class range", pattern, pos);
        }
        addRangeFlags(ccb, lo, hi, flags | ParseFlags.CLASS_NL);
        for (int r : endpoint.trailingLiterals) {
          addRangeFlags(ccb, r, r, flags | ParseFlags.CLASS_NL);
        }
        return;
      }
    }

    addRangeFlags(ccb, lo, hi, flags | ParseFlags.CLASS_NL);
  }

  private boolean hasRangeEndpointAfterHyphen() {
    int peekPos = pos + 1;
    if (peekPos < pattern.length() && pattern.charAt(peekPos) == ']') {
      return false;
    }
    while (startsEmptyQuotedLiteralAt(peekPos)) {
      peekPos += 4;
      if (peekPos < pattern.length() && pattern.charAt(peekPos) == ']') {
        return false;
      }
    }
    if (peekPos < pattern.length() && pattern.charAt(peekPos) == '[') {
      return false;
    }
    return peekPos < pattern.length();
  }

  private RangeEndpoint parseCCRangeEndpoint() {
    skipClassRangeEndpointTrivia();
    if (pos < pattern.length() && pattern.charAt(pos) == '[') {
      throw new PatternSyntaxException("bad class syntax", pattern, pos);
    }
    if (startsQuotedLiteral()) {
      int[] literals = parseQuotedLiteralSequence();
      if (literals.length == 0) {
        throw new PatternSyntaxException("bad class syntax", pattern, pos);
      }
      int[] trailing = new int[literals.length - 1];
      System.arraycopy(literals, 1, trailing, 0, trailing.length);
      return new RangeEndpoint(literals[0], trailing);
    }
    return new RangeEndpoint(parseCCCharacter(), new int[0]);
  }

  private void skipClassRangeEndpointTrivia() {
    while (true) {
      int before = pos;
      if ((flags & ParseFlags.COMMENTS) != 0) {
        skipCommentsAndWhitespace();
      }
      skipEmptyQuotedLiterals();
      if (pos == before) {
        return;
      }
    }
  }

  private boolean startsQuotedLiteral() {
    return pos + 1 < pattern.length()
        && pattern.charAt(pos) == '\\'
        && pattern.charAt(pos + 1) == 'Q';
  }

  private boolean startsEmptyQuotedLiteralAt(int index) {
    return index + 3 < pattern.length()
        && pattern.charAt(index) == '\\'
        && pattern.charAt(index + 1) == 'Q'
        && pattern.charAt(index + 2) == '\\'
        && pattern.charAt(index + 3) == 'E';
  }

  private void skipEmptyQuotedLiterals() {
    while (startsEmptyQuotedLiteralAt(pos)) {
      pos += 4;
    }
  }

  private int[] parseQuotedLiteralSequence() {
    pos += 2; // skip \Q
    int[] buffer = new int[Math.max(4, pattern.length() - pos)];
    int count = 0;
    while (pos < pattern.length()) {
      if (pos + 1 < pattern.length()
          && pattern.charAt(pos) == '\\'
          && pattern.charAt(pos + 1) == 'E') {
        pos += 2; // skip \E
        break;
      }
      int r = pattern.codePointAt(pos);
      pos += Character.charCount(r);
      if (count == buffer.length) {
        int[] expanded = new int[buffer.length * 2];
        System.arraycopy(buffer, 0, expanded, 0, buffer.length);
        buffer = expanded;
      }
      buffer[count++] = r;
    }
    int[] result = new int[count];
    System.arraycopy(buffer, 0, result, 0, count);
    return result;
  }

  private static final class RangeEndpoint {
    final int first;
    final int[] trailingLiterals;

    RangeEndpoint(int first, int[] trailingLiterals) {
      this.first = first;
      this.trailingLiterals = trailingLiterals;
    }
  }

  private int parseCCCharacter() {
    if (pos >= pattern.length()) {
      throw new PatternSyntaxException("missing closing ]", pattern, pos);
    }
    if (pattern.charAt(pos) == '\\') {
      return parseEscape();
    }
    int r = pattern.codePointAt(pos);
    pos += Character.charCount(r);
    return r;
  }

  // ---- Escape parsing ----

  private int parseEscape() {
    if (pos >= pattern.length() || pattern.charAt(pos) != '\\') {
      throw new PatternSyntaxException("internal error: expected \\", pattern, pos);
    }
    if (pos + 1 >= pattern.length()) {
      throw new PatternSyntaxException("trailing backslash", pattern, pos);
    }
    pos++; // '\\'
    int c = pattern.codePointAt(pos);
    pos += Character.charCount(c);

    switch (c) {
      // Named Unicode character: \N{name}
      case 'N' -> {
        if (pos >= pattern.length() || pattern.charAt(pos) != '{') {
          throw new PatternSyntaxException("invalid escape sequence", pattern, pos - 2);
        }
        pos++; // '{'
        int nameStart = pos;
        int end = pattern.indexOf('}', pos);
        if (end < 0) {
          throw new PatternSyntaxException("invalid escape sequence", pattern, pos - 3);
        }
        String name = pattern.substring(nameStart, end);
        pos = end + 1; // skip '}'
        try {
          return Character.codePointOf(name);
        } catch (IllegalArgumentException e) {
          throw new PatternSyntaxException(
              "unknown Unicode character name: " + name, pattern, nameStart);
        }
      }
      // JDK treats all non-zero numeric escapes as back references, not octal literals.
      case '1', '2', '3', '4', '5', '6', '7', '8', '9' ->
          throw new PatternSyntaxException("backreferences are not supported", pattern, pos - 2);
      case '0' -> {
        // JDK: \0nnn — up to three octal digits after \0 (max value 0377 = 255).
        if (pos >= pattern.length() || pattern.charAt(pos) < '0' || pattern.charAt(pos) > '7') {
          throw new PatternSyntaxException("Illegal octal escape sequence", pattern, pos);
        }
        int code = 0;
        int digits = 0;
        while (digits < 3
            && pos < pattern.length()
            && pattern.charAt(pos) >= '0'
            && pattern.charAt(pos) <= '7') {
          int next = code * 8 + pattern.charAt(pos) - '0';
          if (next > 0377) {
            break;
          }
          code = next;
          pos++;
          digits++;
        }
        return code;
      }
      // Hexadecimal escapes.
      case 'x' -> {
        if (pos >= pattern.length()) {
          throw new PatternSyntaxException("invalid escape sequence", pattern, pos - 2);
        }
        int c2 = pattern.codePointAt(pos);
        pos += Character.charCount(c2);
        if (c2 == '{') {
          // Any number of digits in braces.
          if (pos >= pattern.length()) {
            throw new PatternSyntaxException("invalid escape sequence", pattern, pos);
          }
          int code = 0;
          int nhex = 0;
          while (pos < pattern.length()) {
            int hc = pattern.codePointAt(pos);
            if (hc == '}') {
              pos++; // '}'
              break;
            }
            if (!Utils.isHexDigit(hc)) {
              throw new PatternSyntaxException("invalid escape sequence", pattern, pos);
            }
            nhex++;
            code = code * 16 + Utils.unhex(hc);
            if (code > runeMax) {
              throw new PatternSyntaxException("invalid escape sequence", pattern, pos);
            }
            pos += Character.charCount(hc);
            if (pos >= pattern.length()) {
              throw new PatternSyntaxException("invalid escape sequence", pattern, pos);
            }
          }
          if (nhex == 0) {
            throw new PatternSyntaxException("invalid escape sequence", pattern, pos);
          }
          return code;
        }
        // Two hex digits.
        if (pos >= pattern.length()) {
          throw new PatternSyntaxException("invalid escape sequence", pattern, pos - 3);
        }
        int c3 = pattern.codePointAt(pos);
        pos += Character.charCount(c3);
        if (!Utils.isHexDigit(c2) || !Utils.isHexDigit(c3)) {
          throw new PatternSyntaxException("invalid escape sequence", pattern, pos);
        }
        return Utils.unhex(c2) * 16 + Utils.unhex(c3);
      }
      // Unicode escape: \\uhhhh (exactly 4 hex digits).
      // If the value is a high surrogate and the next escape is a low surrogate,
      // they are combined into a single supplementary code point.
      case 'u' -> {
        int code = parseExactHex(4);
        if (Character.isHighSurrogate((char) code)
            && pos + 5 < pattern.length()
            && pattern.charAt(pos) == '\\'
            && pattern.charAt(pos + 1) == 'u') {
          int savedPos = pos;
          pos += 2; // skip \\u
          int low = parseExactHex(4);
          if (Character.isLowSurrogate((char) low)) {
            code = Character.toCodePoint((char) code, (char) low);
          } else {
            pos = savedPos; // not a surrogate pair, backtrack
          }
        }
        return code;
      }
      // C escapes.
      case 'n' -> {
        return '\n';
      }
      case 'r' -> {
        return '\r';
      }
      case 't' -> {
        return '\t';
      }
      case 'a' -> {
        return '\u0007';
      } // bell
      case 'e' -> {
        return '\u001B';
      } // escape
      case 'f' -> {
        return '\f';
      }
      // Control character: \cX → X ^ 0x40
      case 'c' -> {
        if ((flags & ParseFlags.COMMENTS) != 0) {
          skipCommentsAndWhitespace();
        }
        if (pos >= pattern.length()) {
          throw new PatternSyntaxException("invalid escape sequence", pattern, pos - 2);
        }
        int ctrl = pattern.codePointAt(pos);
        pos += Character.charCount(ctrl);
        return ctrl ^ 0x40;
      }
      default -> {
        // JDK reserves backslash before ASCII alphabetic characters for escaped constructs.
        if (!Utils.isAlpha(c)) {
          return c;
        }
        throw new PatternSyntaxException("invalid escape sequence", pattern, pos - 2);
      }
    }
  }

  /**
   * Parses exactly {@code n} hex digits at the current position and returns their value. Advances
   * {@code pos} past the digits.
   */
  private int parseExactHex(int n) {
    if (pos + n > pattern.length()) {
      throw new PatternSyntaxException("invalid unicode escape", pattern, pos - 2);
    }
    int code = 0;
    for (int i = 0; i < n; i++) {
      int hc = pattern.charAt(pos);
      if (!Utils.isHexDigit(hc)) {
        throw new PatternSyntaxException("invalid unicode escape", pattern, pos);
      }
      code = code * 16 + Utils.unhex(hc);
      pos++;
    }
    return code;
  }

  // ---- Perl character class escapes (\d, \s, \w, \D, \S, \W) ----

  private CharClassBuilder maybeParsePerlCCEscape() {
    if ((flags & ParseFlags.PERL_CLASSES) == 0) return null;
    if (pos + 1 >= pattern.length() || pattern.charAt(pos) != '\\') return null;

    char c2 = pattern.charAt(pos + 1);
    String posName; // the positive version
    boolean negate;
    switch (c2) {
      case 'd' -> {
        posName = "\\d";
        negate = false;
      }
      case 'D' -> {
        posName = "\\d";
        negate = true;
      }
      case 'h' -> {
        posName = "\\h";
        negate = false;
      }
      case 'H' -> {
        posName = "\\h";
        negate = true;
      }
      case 's' -> {
        posName = "\\s";
        negate = false;
      }
      case 'S' -> {
        posName = "\\s";
        negate = true;
      }
      case 'v' -> {
        posName = "\\v";
        negate = false;
      }
      case 'V' -> {
        posName = "\\v";
        negate = true;
      }
      case 'w' -> {
        posName = "\\w";
        negate = false;
      }
      case 'W' -> {
        posName = "\\w";
        negate = true;
      }
      default -> {
        return null;
      }
    }

    pos += 2; // '\\', letter
    CharClassExpander.Group group =
        CharClassExpander.lookupPerlGroup(posName, (flags & ParseFlags.UNICODE_CHAR_CLASS) != 0);
    if (group == null) {
      return null;
    }

    CharClassBuilder ccb = new CharClassBuilder();
    if (negate) {
      CharClassExpander.addNegatedGroup(ccb, group, flags);
    } else {
      CharClassExpander.addPositiveGroup(ccb, group, flags);
    }
    return ccb;
  }

  // ---- Unicode group parsing (\p{...}, \P{...}) ----

  private static final int PARSE_OK = 0;
  private static final int PARSE_ERROR = 1;
  private static final int PARSE_NOTHING = 2;

  private int parseUnicodeGroup(CharClassBuilder ccb) {
    if ((flags & ParseFlags.UNICODE_GROUPS) == 0) return PARSE_NOTHING;
    if (pos + 1 >= pattern.length() || pattern.charAt(pos) != '\\') return PARSE_NOTHING;
    char c = pattern.charAt(pos + 1);
    if (c != 'p' && c != 'P') return PARSE_NOTHING;

    int sign = (c == 'P') ? -1 : 1;
    int seqStart = pos;
    pos += 2; // '\\', 'p'/'P'

    if (pos >= pattern.length()) {
      throw new PatternSyntaxException("invalid Unicode group", pattern, seqStart);
    }

    int c2 = pattern.codePointAt(pos);
    pos += Character.charCount(c2);

    String name;
    if (c2 != '{') {
      // Single char property name, e.g. \pL
      name = new String(Character.toChars(c2));
    } else {
      // Name is in braces.
      int nameStart = pos;
      int end = pattern.indexOf('}', pos);
      if (end < 0) {
        throw new PatternSyntaxException("invalid Unicode group", pattern, seqStart);
      }
      name = pattern.substring(nameStart, end);
      pos = end + 1; // skip '}'
    }

    CharClassExpander.Group group =
        CharClassExpander.lookupUnicodeGroup(name, (flags & ParseFlags.UNICODE_CHAR_CLASS) != 0);
    if (group == null) {
      throw new PatternSyntaxException("invalid Unicode group: " + name, pattern, seqStart);
    }

    if (sign > 0) {
      CharClassExpander.addPositiveGroup(ccb, group, flags);
    } else {
      CharClassExpander.addNegatedGroup(ccb, group, flags);
    }
    return PARSE_OK;
  }

  /** Add a range to the character class, but exclude newline if asked. Also handle case folding. */
  private static void addRangeFlags(CharClassBuilder ccb, int lo, int hi, int parseFlags) {
    CharClassExpander.addRange(ccb, lo, hi, parseFlags);
  }

  // ---- Perl flags parsing ----

  private boolean parsePerlFlags() {
    // Caller checked that pattern[pos] == '(' and pattern[pos+1] == '?'
    if ((flags & ParseFlags.PERL_X) == 0
        || pos + 1 >= pattern.length()
        || pattern.charAt(pos) != '('
        || pattern.charAt(pos + 1) != '?') {
      throw new PatternSyntaxException("internal error", pattern, pos);
    }

    int startPos = pos;

    // Check for look-around assertions.
    if (pos + 2 < pattern.length()) {
      char c2 = pattern.charAt(pos + 2);
      if (c2 == '=' || c2 == '!') {
        throw new PatternSyntaxException(
            "invalid Perl operator: " + pattern.substring(pos, pos + 3), pattern, pos);
      }
      if (c2 == '<' && pos + 3 < pattern.length()) {
        char c3 = pattern.charAt(pos + 3);
        if (c3 == '=' || c3 == '!') {
          throw new PatternSyntaxException(
              "invalid Perl operator: " + pattern.substring(pos, pos + 4), pattern, pos);
        }
      }
    }

    // Check for named captures.
    // (?<name>expr)
    if (pos + 3 < pattern.length()) {
      if (pattern.charAt(pos + 2) == '<') {
        int begin = pos + 3;
        int end = pattern.indexOf('>', begin);
        if (end < 0) {
          throw new PatternSyntaxException("invalid named capture", pattern, pos);
        }
        String name = pattern.substring(begin, end);
        if (!isValidCaptureName(name, false)) {
          throw new PatternSyntaxException("invalid named capture: " + name, pattern, pos);
        }
        doLeftParen(name);
        pos = end + 1; // skip past '>'
        return false;
      }
    }
    // (?P<name>expr) is a SafeRE extension that supports Python-style named groups in
    // addition to the JDK (?<name>expr) syntax above.
    if (pos + 4 < pattern.length()) {
      if (pattern.charAt(pos + 2) == 'P' && pattern.charAt(pos + 3) == '<') {
        int begin = pos + 4;
        int end = pattern.indexOf('>', begin);
        if (end < 0) {
          throw new PatternSyntaxException("invalid named capture", pattern, pos);
        }
        String name = pattern.substring(begin, end);
        if (!isValidCaptureName(name, true)) {
          throw new PatternSyntaxException("invalid named capture: " + name, pattern, pos);
        }
        doLeftParen(name);
        pos = end + 1; // skip past '>'
        return false;
      }
    }

    pos += 2; // "(?"

    boolean negated = false;
    boolean sawflags = false;
    boolean standaloneFlags = false;
    int nflags = flags;

    boolean done = false;
    while (!done) {
      if (pos >= pattern.length()) {
        throw new PatternSyntaxException("invalid Perl operator", pattern, startPos);
      }
      int c = pattern.codePointAt(pos);
      pos += Character.charCount(c);
      switch (c) {
        case 'd' -> {
          sawflags = true;
          if (negated) nflags &= ~ParseFlags.UNIX_LINES;
          else nflags |= ParseFlags.UNIX_LINES;
        }
        case 'i' -> {
          sawflags = true;
          if (negated) nflags &= ~ParseFlags.FOLD_CASE;
          else nflags |= ParseFlags.FOLD_CASE;
        }
        case 'm' -> { // opposite of OneLine
          sawflags = true;
          if (negated) nflags |= ParseFlags.ONE_LINE;
          else nflags &= ~ParseFlags.ONE_LINE;
        }
        case 's' -> {
          sawflags = true;
          if (negated) nflags &= ~ParseFlags.DOT_NL;
          else nflags |= ParseFlags.DOT_NL;
        }
        case 'u' -> {
          sawflags = true;
          if (negated) nflags &= ~ParseFlags.UNICODE_CASE;
          else nflags |= ParseFlags.UNICODE_CASE;
        }
        case 'U' -> {
          sawflags = true;
          if (negated) {
            nflags &=
                ~(ParseFlags.UNICODE_CASE
                    | ParseFlags.UNICODE_GROUPS
                    | ParseFlags.UNICODE_CHAR_CLASS);
          } else {
            nflags |=
                ParseFlags.UNICODE_CASE | ParseFlags.UNICODE_GROUPS | ParseFlags.UNICODE_CHAR_CLASS;
          }
        }
        case 'x' -> {
          sawflags = true;
          if (negated) nflags &= ~ParseFlags.COMMENTS;
          else nflags |= ParseFlags.COMMENTS;
        }
        case '-' -> {
          if (negated) {
            throw new PatternSyntaxException("invalid Perl operator", pattern, startPos);
          }
          negated = true;
          sawflags = false;
        }
        case ':' -> {
          doLeftParenNoCapture();
          done = true;
        }
        case ')' -> {
          standaloneFlags = true;
          done = true;
        }
        default -> {
          throw new PatternSyntaxException("invalid Perl operator", pattern, startPos);
        }
      }
    }

    if (negated && !sawflags) {
      throw new PatternSyntaxException("invalid Perl operator", pattern, startPos);
    }

    flags = nflags;
    return standaloneFlags;
  }

  // ---- Repetition parsing ----

  /**
   * Tries to parse a repetition suffix like {1,2} or {2} or {2,}. Returns null if the pattern at
   * pos does not look like a valid repetition. Otherwise returns int[]{lo, hi} and advances pos.
   */
  private int[] maybeParseRepetition() {
    int saved = pos;
    if (pos >= pattern.length() || pattern.charAt(pos) != '{') {
      return null;
    }
    pos++; // '{'

    int lo = parseDecimal();
    if (lo < 0) {
      pos = saved;
      return null;
    }

    int hi;
    if (pos >= pattern.length()) {
      pos = saved;
      return null;
    }
    if (pattern.charAt(pos) == ',') {
      pos++; // ','
      if (pos >= pattern.length()) {
        pos = saved;
        return null;
      }
      if (pattern.charAt(pos) == '}') {
        hi = -1; // unbounded
      } else {
        hi = parseDecimal();
        if (hi < 0) {
          pos = saved;
          return null;
        }
      }
    } else {
      hi = lo;
    }

    if (pos >= pattern.length() || pattern.charAt(pos) != '}') {
      pos = saved;
      return null;
    }
    pos++; // '}'
    return new int[] {lo, hi};
  }

  /** Parses a decimal integer at current pos. Returns -1 if no digits. */
  private int parseDecimal() {
    if (pos >= pattern.length() || !Utils.isDigit(pattern.charAt(pos))) {
      return -1;
    }
    int n = 0;
    while (pos < pattern.length() && Utils.isDigit(pattern.charAt(pos))) {
      if (n >= 100_000_000) return -1; // avoid overflow
      n = n * 10 + pattern.charAt(pos) - '0';
      pos++;
    }
    return n;
  }

  // ---- Capture name validation ----

  private static boolean isValidCaptureName(String name, boolean allowUnderscores) {
    if (name.isEmpty()) return false;
    // Match java.util.regex.Pattern rules: first character must be an ASCII letter,
    // subsequent characters must be ASCII letters or digits. When allowUnderscores is true,
    // underscores are also permitted.
    char first = name.charAt(0);
    if (!((first >= 'A' && first <= 'Z')
        || (first >= 'a' && first <= 'z')
        || (allowUnderscores && first == '_'))) {
      return false;
    }
    for (int i = 1; i < name.length(); i++) {
      char c = name.charAt(i);
      if ((c >= 'A' && c <= 'Z')
          || (c >= 'a' && c <= 'z')
          || (c >= '0' && c <= '9')
          || (allowUnderscores && c == '_')) {
        continue;
      }
      return false;
    }
    return true;
  }

  // ---- Helper: finish CharClassBuilder into a Regexp ----

  private Regexp finishCharClassBuilder(CharClassBuilder ccb) {
    return Regexp.charClass(ccb.build(), flags & ~ParseFlags.FOLD_CASE);
  }

  // ---- \R expansion helper ----

  /**
   * Builds a Regexp equivalent to {@code (?:\r\n|[\n\x0B\f\r\x{85}\x{2028}\x{2029}])}, which
   * matches any Unicode linebreak sequence. With POSIX leftmost-longest semantics, the two-char
   * {@code \r\n} alternative naturally wins over a single {@code \r}.
   */
  private Regexp buildLinebreakRegexp() {
    // Alternative 1: \r\n (CRLF as a single unit)
    Regexp crLf = Regexp.literalString(new int[] {'\r', '\n'}, flags);

    // Alternative 2: any single linebreak character
    CharClassBuilder ccb = new CharClassBuilder();
    ccb.addRune('\n'); // U+000A LINE FEED
    ccb.addRune('\u000B'); // U+000B VERTICAL TAB
    ccb.addRune('\f'); // U+000C FORM FEED
    ccb.addRune('\r'); // U+000D CARRIAGE RETURN
    ccb.addRune(0x85); // U+0085 NEXT LINE
    ccb.addRune(0x2028); // U+2028 LINE SEPARATOR
    ccb.addRune(0x2029); // U+2029 PARAGRAPH SEPARATOR
    Regexp singleLinebreak = Regexp.charClass(ccb.build(), flags);

    return Regexp.alternate(List.of(crLf, singleLinebreak), flags);
  }
}
