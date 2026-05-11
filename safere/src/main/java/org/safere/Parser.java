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
  private final boolean containsEmptyQuotedLiteral;
  private int pos; // current parse position (char index into pattern)
  private StackEntry stacktop;
  private int ncap;
  private final int runeMax;
  private final Set<String> namedCaptures = new HashSet<>();
  private boolean lastClassAtomSkippedCommentsTrivia;
  private boolean lastClassAtomRawAmpersandRangeTail;

  private Parser(String pattern, int flags) {
    this.pattern = pattern;
    this.containsEmptyQuotedLiteral = pattern.contains("\\Q\\E");
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
    while (pos < pattern.length()) {
      int c = pattern.codePointAt(pos);
      if (c == '#') {
        // Skip from '#' to end of line (or end of pattern).
        pos++;
        while (pos < pattern.length()) {
          int commentChar = pattern.codePointAt(pos);
          if (isCommentTerminator(commentChar)) {
            break;
          }
          pos += Character.charCount(commentChar);
        }
      } else if (isCommentsWhitespace(c)) {
        pos += Character.charCount(c);
      } else {
        break;
      }
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
        int r = pattern.codePointAt(i);
        i += Character.charCount(r);
        pushLiteral(r);
      }
      return doFinish();
    }

    String lastunary = null;
    boolean lastTokenNonRepeatable = false;
    boolean lastTokenWasEmptyQuotedLiteral = false;
    while (pos < pattern.length()) {
      // In comments mode, skip whitespace and #-comments before each token.
      if ((flags & ParseFlags.COMMENTS) != 0) {
        skipCommentsAndWhitespace();
        if (pos >= pattern.length()) {
          break;
        }
      }
      String isunary = null;
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
          if ((flags & ParseFlags.PERL_X) != 0) {
            if (pos < pattern.length() && pattern.charAt(pos) == '?') {
              nongreedy = true;
              pos++; // '?'
            }
            if (lastunary != null && lastTokenWasEmptyQuotedLiteral && c != '*') {
              isunary = lastunary;
              break;
            }
            if (lastunary != null && !canRepeatAfterUnary(op)) {
              throw new PatternSyntaxException(
                  "invalid nested repetition operator", pattern, opStart);
            }
          }
          String opstr = pattern.substring(opStart, pos);
          pushRepeatOp(op, opstr, nongreedy);
          isunary = opstr;
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
          if ((flags & ParseFlags.PERL_X) != 0) {
            if (pos < pattern.length() && pattern.charAt(pos) == '?') {
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
          pushRepetition(lo, hi, opstr, nongreedy);
          isunary = opstr;
        }
        case '\\' -> {
          if (parseBackslash()) {
            if (stacktop == null || isMarker(stacktop) || lastTokenNonRepeatable) {
              lastunary = null;
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
        return true;
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

    // \X: Extended grapheme cluster (simplified).
    // Equivalent to (?:\r\n|\P{M}\p{M}*|<any>), which handles base+combining-marks.
    if (pos + 1 < pattern.length() && pattern.charAt(pos + 1) == 'X') {
      pos += 2; // '\\', 'X'
      pushRegexp(buildGraphemeClusterRegexp());
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
    if (maybePushNumericBackreferenceEscape()) {
      return false;
    }
    int r = parseEscape();
    pushLiteral(r);
    return false;
  }

  private boolean maybePushNumericBackreferenceEscape() {
    if (pos + 1 >= pattern.length() || pattern.charAt(pos) != '\\') {
      return false;
    }
    char firstDigit = pattern.charAt(pos + 1);
    if (firstDigit < '1' || firstDigit > '9') {
      return false;
    }
    if (firstDigit - '0' <= ncap) {
      throw new PatternSyntaxException("backreferences are not supported", pattern, pos);
    }

    pos += 2;
    while (pos < pattern.length() && pattern.charAt(pos) >= '0' && pattern.charAt(pos) <= '9') {
      pos++;
    }
    pushRegexp(Regexp.noMatch(flags));
    return true;
  }

  // ---- Stack operations ----

  private boolean isMarker(StackEntry e) {
    return e != null && e.re != null && e.re.matchId < 0 && e.re.op == RegexpOp.NO_MATCH;
  }

  private boolean isLeftParen(StackEntry e) {
    return isMarker(e) && e.re.matchId == LEFT_PAREN;
  }

  private boolean isVerticalBar(StackEntry e) {
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
    maybeConcatString(-1, 0);

    // Special case: a character class of one character is just a literal.
    if (re.op == RegexpOp.CHAR_CLASS && re.charClass != null) {
      CharClass cc = re.charClass;
      if (cc.numRanges() == 1 && cc.lo(0) == cc.hi(0)) {
        int r = cc.lo(0);
        re = Regexp.literal(r, re.flags);
      } else if (cc.numRanges() == 2) {
        int r = cc.lo(0);
        if ('A' <= r
            && r <= 'Z'
            && cc.hi(0) == r
            && cc.numRanges() == 2
            && cc.lo(1) == r + ('a' - 'A')
            && cc.hi(1) == r + ('a' - 'A')) {
          re = Regexp.literal(r + ('a' - 'A'), flags | ParseFlags.FOLD_CASE);
        }
      }
    }

    StackEntry e = new StackEntry(re);
    e.down = stacktop;
    stacktop = e;
  }

  private void pushLiteral(int r) {
    // Do case folding if needed.
    if ((flags & ParseFlags.FOLD_CASE) != 0) {
      if ((flags & ParseFlags.UNICODE_CASE) == 0) {
        int folded = asciiFoldRune(r);
        if (folded != r) {
          pushRegexp(Regexp.literal(folded, flags));
          return;
        }
      } else if (cycleFoldRune(r) != r) {
        CharClassBuilder ccb = new CharClassBuilder();
        int r1 = r;
        do {
          if ((flags & ParseFlags.NEVER_NL) == 0 || r != '\n') {
            ccb.addRune(r);
          }
          r = cycleFoldRune(r);
        } while (r != r1);
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
        && asciiFoldRune(r) == r
        && !('a' <= r && r <= 'z')) {
      literalFlags &= ~ParseFlags.FOLD_CASE;
    }
    if (maybeConcatString(r, literalFlags)) {
      return;
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

  private boolean canRepeatAfterUnary(RegexpOp op) {
    return op == RegexpOp.PLUS && stacktop != null && isQuantifiedZeroWidth(stacktop.re);
  }

  private boolean isQuantifiedZeroWidth(Regexp re) {
    return switch (re.op) {
      case STAR, PLUS, QUEST, REPEAT -> isZeroWidth(re.subs.getFirst());
      default -> false;
    };
  }

  private boolean isZeroWidth(Regexp re) {
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
    maybeConcatString(-1, 0);
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

    Regexp re = op == RegexpOp.CONCAT ? Regexp.concat(subs, flags) : Regexp.alternate(subs, flags);
    StackEntry entry = new StackEntry(re);
    entry.down = stacktop;
    stacktop = entry;
  }

  // ---- MaybeConcatString ----

  /**
   * Tries to merge the top two stack entries if they're both literals/literal-strings with the same
   * flags. If r >= 0, consider pushing a literal r on the stack. Returns true if that happened.
   */
  private boolean maybeConcatString(int r, int fl) {
    StackEntry re1Entry = stacktop;
    if (re1Entry == null) return false;
    StackEntry re2Entry = re1Entry.down;
    if (re2Entry == null) return false;

    Regexp re1 = re1Entry.re;
    Regexp re2 = re2Entry.re;

    if (re1.op != RegexpOp.LITERAL && re1.op != RegexpOp.LITERAL_STRING) return false;
    if (re2.op != RegexpOp.LITERAL && re2.op != RegexpOp.LITERAL_STRING) return false;

    boolean re1Fold = (re1.flags & ParseFlags.FOLD_CASE) != 0;
    boolean re2Fold = (re2.flags & ParseFlags.FOLD_CASE) != 0;
    if (re1Fold != re2Fold) return false;

    // Convert re2 to LITERAL_STRING if it's a LITERAL.
    int[] re2Runes;
    if (re2.op == RegexpOp.LITERAL) {
      re2Runes = new int[] {re2.rune};
    } else {
      re2Runes = re2.runes;
    }

    // Append re1 runes.
    int[] re1Runes;
    if (re1.op == RegexpOp.LITERAL) {
      re1Runes = new int[] {re1.rune};
    } else {
      re1Runes = re1.runes;
    }

    int[] combined = new int[re2Runes.length + re1Runes.length];
    System.arraycopy(re2Runes, 0, combined, 0, re2Runes.length);
    System.arraycopy(re1Runes, 0, combined, re2Runes.length, re1Runes.length);

    Regexp newRe2 = Regexp.literalString(combined, re2.flags);

    // Reuse re1 slot if r >= 0.
    if (r >= 0) {
      re1Entry.re = Regexp.literal(r, fl);
      re2Entry.re = newRe2;
      return true;
    }

    // Pop re1, replace re2.
    stacktop = re2Entry;
    re2Entry.re = newRe2;
    return false;
  }

  // ---- Character class parsing ----

  private Regexp parseCharClass() {
    CharClassBuilder ccb = parseCharClassBuilder();
    return Regexp.charClass(ccb.build(), flags & ~ParseFlags.FOLD_CASE);
  }

  private CharClassBuilder parseCharClassBuilder() {
    NormalizedClassPattern normalizedPattern = removeEmptyQuotedLiteralsFromClassIfNeeded();
    if (normalizedPattern != null) {
      Parser normalizedParser = new Parser(normalizedPattern.pattern(), flags);
      try {
        CharClassBuilder ccb = normalizedParser.parseCharClassBuilder();
        pos = normalizedPattern.originalEnd();
        return ccb;
      } catch (PatternSyntaxException e) {
        int originalIndex = normalizedPattern.originalIndex(e.getIndex());
        throw new PatternSyntaxException(e.getDescription(), pattern, originalIndex);
      }
    }

    ArrayDeque<ClassExpressionFrame> stack = new ArrayDeque<>();
    stack.push(new ClassExpressionFrame(true, ClassContinuation.ROOT));

    while (!stack.isEmpty()) {
      ClassExpressionFrame frame = stack.peek();
      ClassNormalization normalization = skipClassTriviaAndEmptySyntax();
      if (pos >= pattern.length()) {
        throw new PatternSyntaxException("missing closing ]", pattern, frame.classStart);
      }
      ClassOperatorToken token = scanClassOperatorToken(normalization);
      if (frame.ignoreUntilClassTerminator) {
        if (frame.shouldCompleteAt(token.c())) {
          CharClassBuilder completed = completeClassExpression(frame);
          stack.pop();
          if (stack.isEmpty()) {
            return completed;
          }
          addCompletedClassExpression(stack.peek(), frame, completed);
          continue;
        }
        skipIgnoredClassItem();
        continue;
      }

      if (frame.parsingIntersectionRight) {
        if (token.isAmpersand()
            && snapshotPendingExpression(frame) != null
            && shouldDiscardCommentsModeRhsTail()) {
          if (frame.pendingScalarItemsAfterCurrentOperand
              && frame.pendingScalarRole == ClassAtomRole.ORDINARY_SCALAR) {
            throw new PatternSyntaxException("bad class syntax", pattern, pos);
          }
          frame.accumulatedClass = new CharClassBuilder();
          frame.currentIntersectionOperand = frame.accumulatedClass;
          frame.pendingScalarItems = new CharClassBuilder();
          frame.hasPendingScalarItems = false;
          frame.pendingScalarItemsAfterCurrentOperand = false;
          frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
          frame.rawAmpersandSeparatorActive = false;
          frame.rawAmpersandLeftExpression = null;
          frame.parsingIntersectionRight = false;
          frame.intersectionStartedAfterOddAmpersandRun = false;
          frame.ignoreUntilClassTerminator = true;
          frame.suppressNegation = true;
          pos++;
          continue;
        }
        if (token.isCloseBracket() || token.isAmpersand()) {
          if (token.isSingleAmpersand()
              && token.skippedZeroWidthSyntax()
              && !token.skippedCommentsTrivia()) {
            if (rawAmpersandBeforeCommentsModeCloseAt(pos)) {
              throw new PatternSyntaxException("bad class syntax", pattern, pos);
            }
            if (hasInvalidRangeTailAfterRawAmpersand(pos)) {
              throw new PatternSyntaxException("illegal character range", pattern, pos + 1);
            }
            CharClassBuilder expression = snapshotZeroWidthAmpersandUnionExpression(frame);
            if (rawAmpersandRangeAfterAmpersandUsesCurrentOperandOnly(pos)
                && frame.currentIntersectionOperand != null
                && frame.hasPendingScalarItems
                && !frame.pendingScalarItemsAfterCurrentOperand
                && frame.pendingScalarRole == ClassAtomRole.ORDINARY_SCALAR) {
              expression = new CharClassBuilder().addCharClass(frame.currentIntersectionOperand);
            }
            if (expression == null) {
              throw new PatternSyntaxException("bad class syntax", pattern, pos);
            }
            if (frame.pendingScalarItemsAfterCurrentOperand
                && frame.pendingScalarRole == ClassAtomRole.ORDINARY_SCALAR) {
              throw new PatternSyntaxException("bad class syntax", pattern, pos);
            }
            CharClassBuilder rawAmpersandLeftExpression =
                new CharClassBuilder().addCharClass(expression);
            pos++;
            boolean consumedRawAmpersandRangeTail = addRawAmpersandRangeTailIfPresent(expression);
            if (!consumedRawAmpersandRangeTail) {
              addRangeFlags(expression, '&', '&', flags | ParseFlags.CLASS_NL);
            }
            frame.accumulatedClass = expression;
            frame.currentIntersectionOperand = expression;
            if (!consumedRawAmpersandRangeTail) {
              markNormalizedRawAmpersandSeparatorBeforeNextRun(frame, rawAmpersandLeftExpression);
            }
            frame.pendingScalarItems = new CharClassBuilder();
            frame.hasPendingScalarItems = false;
            frame.pendingScalarItemsAfterCurrentOperand = false;
            frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
            frame.parsingIntersectionRight = false;
            frame.intersectionRightStartedAfterCommentsTrivia = false;
            frame.intersectionStartedAfterOddAmpersandRun = false;
            continue;
          }
          if (token.isAmpersand()) {
            if (token.oddAmpersands()
                && token.ampersands() > 1
                && frame.intersectionRightHasExpression
                && frame.intersectionRightOnlyNestedClasses) {
              OddAmpersandRunTail tail = token.ampersandTail();
              if (tail.skippedCommentsTrivia()
                  && tail.pos() < pattern.length()
                  && pattern.charAt(tail.pos()) == ']'
                  && !canUseCloseBracketAsClassLiteral(frame, tail.pos())) {
                throw new PatternSyntaxException("bad class syntax", pattern, tail.pos());
              }
              if (tail.skippedNormalizedSyntax()
                  && tail.pos() < pattern.length()
                  && finishNestedRightAfterOddAmpersandRun(frame, tail)) {
                continue;
              }
              pos += token.ampersands() - 1;
              finishNestedRightBeforeTrailingAmpersand(frame);
              continue;
            }
            if (shouldFinishOddAmpersandRhsAsUnion(frame, token)) {
              finishOddAmpersandUnionRun(frame, token.ampersands());
              frame.parsingIntersectionRight = false;
              frame.intersectionRightStartedAfterCommentsTrivia = false;
              frame.intersectionStartedAfterOddAmpersandRun = false;
              continue;
            }
            if (token.isSingleAmpersand() && hasInvalidRangeTailAfterRawAmpersand(pos)) {
              throw new PatternSyntaxException("illegal character range", pattern, pos + 1);
            }
            if (token.isSingleAmpersand()
                && frame.intersectionRightHasExpression
                && frame.intersectionRightOnlyNestedClasses) {
              finishNestedRightBeforeTrailingAmpersand(frame);
              continue;
            }
            OddAmpersandRunTail tail = token.ampersandTail();
            if (token.evenAmpersands()
                && tail.skippedZeroWidthSyntax()
                && !tail.skippedCommentsTrivia()
                && tail.pos() < pattern.length()
                && pattern.charAt(tail.pos()) == '&'
                && token.ampersandTailHasSingleAmpersand()) {
              if (rawAmpersandBeforeCommentsModeCloseAt(tail.pos())) {
                throw new PatternSyntaxException("bad class syntax", pattern, tail.pos());
              }
              if (hasInvalidRangeTailAfterRawAmpersand(tail.pos())) {
                throw new PatternSyntaxException(
                    "illegal character range", pattern, tail.pos() + 1);
              }
              CharClassBuilder expression = snapshotZeroWidthAmpersandUnionExpression(frame);
              if (rawAmpersandRangeAfterAmpersandUsesCurrentOperandOnly(tail.pos())
                  && frame.currentIntersectionOperand != null
                  && frame.hasPendingScalarItems
                  && !frame.pendingScalarItemsAfterCurrentOperand
                  && frame.pendingScalarRole == ClassAtomRole.ORDINARY_SCALAR) {
                expression = new CharClassBuilder().addCharClass(frame.currentIntersectionOperand);
              }
              if (expression == null) {
                throw new PatternSyntaxException("bad class syntax", pattern, pos);
              }
              if (frame.pendingScalarItemsAfterCurrentOperand
                  && frame.pendingScalarRole == ClassAtomRole.ORDINARY_SCALAR) {
                throw new PatternSyntaxException("bad class syntax", pattern, pos);
              }
              CharClassBuilder rawAmpersandLeftExpression =
                  new CharClassBuilder().addCharClass(expression);
              pos = tail.pos() + 1;
              boolean consumedRawAmpersandRangeTail = addRawAmpersandRangeTailIfPresent(expression);
              if (!consumedRawAmpersandRangeTail) {
                addRangeFlags(expression, '&', '&', flags | ParseFlags.CLASS_NL);
              }
              frame.accumulatedClass = expression;
              frame.currentIntersectionOperand = expression;
              if (!consumedRawAmpersandRangeTail) {
                markNormalizedRawAmpersandSeparatorBeforeNextRun(frame, rawAmpersandLeftExpression);
              }
              frame.pendingScalarItems = new CharClassBuilder();
              frame.hasPendingScalarItems = false;
              frame.pendingScalarItemsAfterCurrentOperand = false;
              frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
              frame.parsingIntersectionRight = false;
              frame.intersectionRightStartedAfterCommentsTrivia = false;
              frame.intersectionStartedAfterOddAmpersandRun = false;
              continue;
            }
          }
          if (token.isSingleAmpersand() && frame.intersectionRightStartedAfterCommentsTrivia) {
            if (rawAmpersandBeforeCommentsModeCloseAt(pos)) {
              throw new PatternSyntaxException("bad class syntax", pattern, pos);
            }
            if (frame.intersectionRightHasExpression && frame.intersectionRightOnlyNestedClasses) {
              finishNestedRightBeforeTrailingAmpersand(frame);
              continue;
            }
            if (snapshotPendingExpression(frame) == null) {
              throw new PatternSyntaxException("bad class syntax", pattern, pos);
            }
            if (frame.pendingScalarItemsAfterCurrentOperand
                && frame.pendingScalarRole == ClassAtomRole.ORDINARY_SCALAR) {
              throw new PatternSyntaxException("bad class syntax", pattern, pos);
            }
            if (frame.hasPendingScalarItems
                && !frame.pendingScalarItemsAfterCurrentOperand
                && frame.pendingScalarRole == ClassAtomRole.ORDINARY_SCALAR
                && frame.currentIntersectionOperand != null
                && rawAmpersandStartsRangeAt(pos)) {
              frame.pendingScalarItems = new CharClassBuilder();
              frame.hasPendingScalarItems = false;
            }
            if (frame.currentIntersectionOperand != null && !frame.hasPendingScalarItems) {
              frame.accumulatedClass =
                  new CharClassBuilder().addCharClass(frame.currentIntersectionOperand);
              if (!rawAmpersandStartsRangeAt(pos)
                  && frame.pendingScalarItemsBeforeOddAmpersandUnion != null) {
                frame.accumulatedClass.addCharClass(
                    frame.pendingScalarItemsBeforeOddAmpersandUnion);
                frame.pendingScalarItemsBeforeOddAmpersandUnion = null;
              }
              frame.currentIntersectionOperand = frame.accumulatedClass;
            }
            if (rawAmpersandStartsRangeAt(pos)) {
              frame.dropPendingRawAmpersandSeparatorBeforeNextRangeTail = true;
            }
            frame.parsingIntersectionRight = false;
            frame.intersectionRightStartedAfterCommentsTrivia = false;
            frame.intersectionStartedAfterOddAmpersandRun = false;
            continue;
          }
          if (token.isAmpersand()) {
            OddAmpersandRunTail tail = token.ampersandTail();
            if (token.evenAmpersands()
                && tail.skippedCommentsTrivia()
                && tail.pos() < pattern.length()
                && pattern.charAt(tail.pos()) == '&'
                && token.ampersandTailHasSingleAmpersand()) {
              if (rawAmpersandBeforeCommentsModeCloseAt(tail.pos())) {
                throw new PatternSyntaxException("bad class syntax", pattern, tail.pos());
              }
              if (frame.intersectionRightHasExpression
                  && frame.intersectionRightOnlyNestedClasses) {
                pos = tail.pos();
                finishNestedRightBeforeTrailingAmpersand(frame);
                continue;
              }
              if (snapshotPendingExpression(frame) == null) {
                throw new PatternSyntaxException("bad class syntax", pattern, pos);
              }
              if (frame.pendingScalarItemsAfterCurrentOperand
                  && frame.pendingScalarRole == ClassAtomRole.ORDINARY_SCALAR) {
                throw new PatternSyntaxException("bad class syntax", pattern, pos);
              }
              if (rawAmpersandRangeAfterAmpersandUsesCurrentOperandOnly(tail.pos())
                  && frame.currentIntersectionOperand != null) {
                frame.accumulatedClass =
                    new CharClassBuilder().addCharClass(frame.currentIntersectionOperand);
                frame.currentIntersectionOperand = frame.accumulatedClass;
                frame.pendingScalarItems = new CharClassBuilder();
                frame.hasPendingScalarItems = false;
              } else if (frame.currentIntersectionOperand != null && !frame.hasPendingScalarItems) {
                frame.accumulatedClass =
                    new CharClassBuilder().addCharClass(frame.currentIntersectionOperand);
                frame.currentIntersectionOperand = frame.accumulatedClass;
              }
              pos = tail.pos();
              frame.parsingIntersectionRight = false;
              frame.intersectionRightStartedAfterCommentsTrivia = false;
              frame.intersectionStartedAfterOddAmpersandRun = false;
              continue;
            }
          }
          finishClassIntersection(frame);
          frame.parsingIntersectionRight = false;
          frame.intersectionRightStartedAfterCommentsTrivia = false;
          frame.intersectionStartedAfterOddAmpersandRun = false;
          continue;
        }
        if (token.isOpenBracket()) {
          stack.push(new ClassExpressionFrame(true, ClassContinuation.INTERSECTION_RIGHT));
          continue;
        }
        stack.push(new ClassExpressionFrame(false, ClassContinuation.INTERSECTION_RIGHT));
        continue;
      }

      if (token.isOpenBracket()) {
        stack.push(new ClassExpressionFrame(true, ClassContinuation.UNION));
        continue;
      }
      if (token.isAmpersand()) {
        if (token.ampersands() >= 2) {
          if (frame.rawAmpersandSeparatorActive) {
            finishRawAmpersandSeparatorRun(frame, token.ampersands());
          } else if (token.oddAmpersands()
              && shouldParseOddAmpersandRunAsUnion(frame, token.ampersands())) {
            finishOddAmpersandUnionRun(frame, token.ampersands());
          } else {
            boolean intersectionHasLeftExpression = snapshotPendingExpression(frame) != null;
            pos += 2;
            OddAmpersandRunTail tail = token.tailAfterFirstAmpersandPair();
            if (tail.skippedCommentsTrivia()) {
              pos = tail.pos();
              frame.intersectionRightStartedAfterCommentsTrivia = true;
            } else {
              frame.intersectionRightStartedAfterCommentsTrivia = false;
            }
            frame.parsingIntersectionRight = true;
            frame.intersectionHasLeftExpression = intersectionHasLeftExpression;
            frame.intersectionStartedAfterOddAmpersandRun = false;
            frame.oddAmpersandUnionStartedWithOrdinaryScalars = false;
            frame.intersectionRight = null;
            frame.intersectionRightHasExpression = false;
            frame.intersectionRightOnlyNestedClasses = false;
          }
          continue;
        }
      } else if (frame.shouldCompleteAt(token.c())) {
        if (frame.rawAmpersandSeparatorActive
            && (token.skippedCommentsTrivia()
                || frame.rawAmpersandSeparatorSkippedCommentsTrivia)) {
          if (canUseCloseBracketAsLiteralAfterRawAmpersand(frame)) {
            finishRawAmpersandSeparatorWithLiteralCloseBracket(frame);
            continue;
          }
          if (!frame.rawAmpersandSeparatorRepeated) {
            throw new PatternSyntaxException("bad class syntax", pattern, pos);
          }
          finishRawAmpersandSeparatorBeforeClassClose(frame);
        }
        CharClassBuilder completed = completeClassExpression(frame);
        stack.pop();
        if (stack.isEmpty()) {
          return completed;
        }
        addCompletedClassExpression(stack.peek(), frame, completed);
        continue;
      }

      ParsedClassAtom atom = parseClassAtomOrRange();
      if (atom.role == ClassAtomRole.RAW_AMPERSAND_SEPARATOR) {
        boolean repeatedRawAmpersandSeparator = frame.rawAmpersandSeparatorActive;
        if (!repeatedRawAmpersandSeparator) {
          frame.rawAmpersandLeftExpressionOrdinaryScalars =
              (frame.currentIntersectionOperand == null
                      && frame.hasPendingScalarItems
                      && !frame.pendingScalarItemsAfterCurrentOperand
                      && frame.pendingScalarRole == ClassAtomRole.ORDINARY_SCALAR)
                  || (frame.currentIntersectionOperand != null
                      && !frame.hasPendingScalarItems
                      && frame.currentIntersectionOperandRole == ClassAtomRole.ORDINARY_SCALAR);
          frame.rawAmpersandLeftExpressionNestedOperand =
              frame.currentIntersectionOperandNestedClass && !frame.hasPendingScalarItems;
          frame.rawAmpersandLeftExpression = snapshotPendingExpression(frame);
          if (frame.rawAmpersandLeftExpression == null) {
            frame.rawAmpersandLeftExpression = new CharClassBuilder();
          }
        }
        frame.rawAmpersandSeparatorActive = true;
        frame.rawAmpersandSeparatorSkippedCommentsTrivia = lastClassAtomSkippedCommentsTrivia;
        frame.rawAmpersandSeparatorRepeated = repeatedRawAmpersandSeparator;
      } else if (atom.role == ClassAtomRole.INTERSECTION_OPERAND) {
        if (frame.commentsOddRunCurrentOperandForRhs != null) {
          frame.accumulatedClass =
              new CharClassBuilder().addCharClass(frame.commentsOddRunCurrentOperandForRhs);
          frame.currentIntersectionOperand = frame.accumulatedClass;
          frame.currentIntersectionOperandRole = ClassAtomRole.INTERSECTION_OPERAND;
          frame.pendingScalarItems = new CharClassBuilder();
          frame.hasPendingScalarItems = false;
          frame.pendingScalarItemsAfterCurrentOperand = false;
          frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
        }
        frame.rawAmpersandSeparatorActive = false;
        frame.rawAmpersandLeftExpression = null;
      }
      if (atom.role == ClassAtomRole.INTERSECTION_OPERAND
          && lastClassAtomRawAmpersandRangeTail
          && frame.dropPendingRawAmpersandSeparatorBeforeNextRangeTail
          && frame.hasPendingScalarItems
          && !frame.pendingScalarItemsAfterCurrentOperand
          && frame.pendingScalarRole == ClassAtomRole.RAW_AMPERSAND_SEPARATOR) {
        frame.pendingScalarItems = new CharClassBuilder();
        frame.hasPendingScalarItems = false;
        frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
      }
      frame.dropPendingRawAmpersandSeparatorBeforeNextRangeTail = false;
      frame.commentsOddRunCurrentOperandForRhs = null;
      if (atom.role != ClassAtomRole.INTERSECTION_OPERAND) {
        if (frame.accumulatedClass != null
            && !frame.pendingScalarItemsAfterCurrentOperand
            && frame.pendingScalarItemsBeforeOddAmpersandUnion != null) {
          frame.accumulatedClass.addCharClass(frame.pendingScalarItemsBeforeOddAmpersandUnion);
        }
        boolean rawAmpersandBecameOrdinaryScalar =
            frame.rawAmpersandSeparatorActive && atom.role != ClassAtomRole.RAW_AMPERSAND_SEPARATOR;
        boolean alreadyHadPendingScalarsAfterCurrent =
            frame.hasPendingScalarItems && frame.pendingScalarItemsAfterCurrentOperand;
        frame.pendingScalarItems.addCharClass(atom.ccb);
        frame.hasPendingScalarItems = true;
        frame.pendingScalarRole =
            rawAmpersandBecameOrdinaryScalar
                ? ClassAtomRole.ORDINARY_SCALAR
                : alreadyHadPendingScalarsAfterCurrent
                    ? ClassAtomRole.merge(frame.pendingScalarRole, atom.role)
                    : atom.role;
        if (rawAmpersandBecameOrdinaryScalar) {
          frame.rawAmpersandSeparatorActive = false;
          frame.rawAmpersandLeftExpression = null;
        }
        if (frame.accumulatedClass != null) {
          frame.pendingScalarItemsAfterCurrentOperand = true;
        }
        frame.currentIntersectionOperandIsOnlyOperand = false;
      } else {
        boolean currentOperandIsOnlyOperand =
            frame.accumulatedClass == null && !frame.hasPendingScalarItems;
        frame.currentIntersectionOperand = atom.ccb;
        frame.currentIntersectionOperandRole = atom.role;
        frame.currentIntersectionOperandNestedClass = false;
        frame.currentIntersectionOperandIsOnlyOperand = currentOperandIsOnlyOperand;
        frame.accumulatedClass =
            unionClass(frame.accumulatedClass, frame.currentIntersectionOperand);
        if (frame.hasPendingScalarItems) {
          frame.pendingScalarItemsAfterCurrentOperand = false;
        }
      }
    }

    throw new PatternSyntaxException("internal error", pattern, pos);
  }

  private NormalizedClassPattern removeEmptyQuotedLiteralsFromClassIfNeeded() {
    if ((flags & ParseFlags.COMMENTS) == 0 && !containsEmptyQuotedLiteral) {
      return null;
    }
    if (pos >= pattern.length() || pattern.charAt(pos) != '[') {
      return null;
    }

    StringBuilder normalized = new StringBuilder();
    List<Integer> originalIndexes = new ArrayList<>();
    ArrayDeque<ClassPatternNormalizationFrame> stack = new ArrayDeque<>();
    boolean changed = false;
    boolean trailingAmpersandRunSkippedCommentsTrivia = false;
    int trailingAmpersandRun = 0;

    appendMapped(normalized, originalIndexes, '[', pos);
    stack.push(new ClassPatternNormalizationFrame());
    int index = pos + 1;
    while (index < pattern.length()) {
      ClassPatternNormalizationFrame frame = stack.peek();
      if ((flags & ParseFlags.COMMENTS) != 0) {
        int afterTrivia = appendCommentsTrivia(normalized, originalIndexes, index);
        if (afterTrivia != index) {
          if (frame != null && !frame.hasContent) {
            frame.allowsNegation = false;
          }
          if (trailingAmpersandRun > 0) {
            trailingAmpersandRunSkippedCommentsTrivia = true;
          }
          index = afterTrivia;
          continue;
        }
      }

      if (startsEmptyQuotedLiteralAt(index)) {
        if ((flags & ParseFlags.COMMENTS) != 0
            && !changed
            && skipCommentsAndWhitespaceAt(index + 4) < pattern.length()
            && pattern.charAt(skipCommentsAndWhitespaceAt(index + 4)) == ']') {
          return null;
        }
        changed = true;
        index += 4;
        continue;
      }

      if (index + 1 < pattern.length()
          && pattern.charAt(index) == '\\'
          && pattern.charAt(index + 1) == 'Q') {
        int quoteStart = index;
        index += 2;
        int literalStart = index;
        while (index < pattern.length()) {
          if (index + 1 < pattern.length()
              && pattern.charAt(index) == '\\'
              && pattern.charAt(index + 1) == 'E') {
            index += 2;
            break;
          }
          index += Character.charCount(pattern.codePointAt(index));
        }
        appendMapped(normalized, originalIndexes, pattern, quoteStart, index);
        if (literalStart < index - 2
            || (index == pattern.length() && literalStart < pattern.length())) {
          markClassPatternContent(frame);
          trailingAmpersandRun = 0;
          trailingAmpersandRunSkippedCommentsTrivia = false;
        }
        continue;
      }

      char c = pattern.charAt(index);
      if (c == '&') {
        appendMapped(normalized, originalIndexes, c, index);
        index++;
        trailingAmpersandRun++;
        int nextAmpersand = nextAmpersandAfterSkippedClassSyntax(index);
        while (nextAmpersand >= 0) {
          changed = true;
          appendMapped(normalized, originalIndexes, '&', nextAmpersand);
          index = nextAmpersand + 1;
          trailingAmpersandRun++;
          nextAmpersand = nextAmpersandAfterSkippedClassSyntax(index);
        }
        markClassPatternContent(frame);
        continue;
      }
      if (c == '\\') {
        int next = Math.min(pattern.length(), index + 2);
        appendMapped(normalized, originalIndexes, pattern, index, next);
        markClassPatternContent(frame);
        trailingAmpersandRun = 0;
        trailingAmpersandRunSkippedCommentsTrivia = false;
        index = next;
        continue;
      }
      if (c == '[') {
        appendMapped(normalized, originalIndexes, c, index);
        markClassPatternContent(frame);
        trailingAmpersandRun = 0;
        trailingAmpersandRunSkippedCommentsTrivia = false;
        stack.push(new ClassPatternNormalizationFrame());
        index++;
        continue;
      }
      if (c == ']') {
        if ((flags & ParseFlags.COMMENTS) != 0
            && trailingAmpersandRunSkippedCommentsTrivia
            && frame != null
            && frame.hasContent
            && trailingAmpersandRun % 2 == 1) {
          if (index + 1 < pattern.length() && pattern.charAt(index + 1) == ']') {
            appendMapped(normalized, originalIndexes, c, index);
            markClassPatternContent(frame);
            trailingAmpersandRun = 0;
            trailingAmpersandRunSkippedCommentsTrivia = false;
            index++;
            continue;
          }
          return null;
        }
        appendMapped(normalized, originalIndexes, c, index);
        if (frame != null && frame.hasContent) {
          stack.pop();
          index++;
          if (stack.isEmpty()) {
            if ((flags & ParseFlags.COMMENTS) != 0
                && !changed
                && index < pattern.length()
                && pattern.charAt(index) == ']') {
              return null;
            }
            return finishNormalizedClassPattern(normalized, originalIndexes, index, changed);
          }
          markClassPatternContent(stack.peek());
        } else {
          markClassPatternContent(frame);
          index++;
        }
        continue;
      }
      if (c == '^' && frame != null && frame.allowsNegation && !frame.hasContent) {
        appendMapped(normalized, originalIndexes, c, index);
        frame.allowsNegation = false;
        trailingAmpersandRun = 0;
        trailingAmpersandRunSkippedCommentsTrivia = false;
        index++;
        continue;
      }

      int cp = pattern.codePointAt(index);
      appendMapped(normalized, originalIndexes, cp, index);
      markClassPatternContent(frame);
      trailingAmpersandRun = 0;
      trailingAmpersandRunSkippedCommentsTrivia = false;
      index += Character.charCount(cp);
    }

    return finishNormalizedClassPattern(normalized, originalIndexes, index, changed);
  }

  private int nextAmpersandAfterSkippedClassSyntax(int index) {
    int current = index;
    boolean skippedAny = false;
    boolean skipped;
    do {
      skipped = false;
      if ((flags & ParseFlags.COMMENTS) != 0) {
        int afterCommentsTrivia = skipCommentsAndWhitespaceAt(current);
        skipped |= afterCommentsTrivia != current;
        current = afterCommentsTrivia;
      }
      while (startsEmptyQuotedLiteralAt(current)) {
        current += 4;
        skipped = true;
        if ((flags & ParseFlags.COMMENTS) != 0) {
          int afterCommentsTrivia = skipCommentsAndWhitespaceAt(current);
          skipped |= afterCommentsTrivia != current;
          current = afterCommentsTrivia;
        }
      }
      skippedAny |= skipped;
    } while (skipped);
    return skippedAny && current < pattern.length() && pattern.charAt(current) == '&'
        ? current
        : -1;
  }

  private int appendCommentsTrivia(
      StringBuilder normalized, List<Integer> originalIndexes, int index) {
    while (index < pattern.length()) {
      int cp = pattern.codePointAt(index);
      if (isCommentsWhitespace(cp)) {
        appendMapped(normalized, originalIndexes, cp, index);
        index += Character.charCount(cp);
        continue;
      }
      if (cp == '#') {
        appendMapped(normalized, originalIndexes, cp, index);
        index += Character.charCount(cp);
        while (index < pattern.length()) {
          int commentCp = pattern.codePointAt(index);
          appendMapped(normalized, originalIndexes, commentCp, index);
          index += Character.charCount(commentCp);
          if (commentCp == '\n' || commentCp == '\r') {
            break;
          }
        }
        continue;
      }
      break;
    }
    return index;
  }

  private NormalizedClassPattern finishNormalizedClassPattern(
      StringBuilder normalized, List<Integer> originalIndexes, int originalEnd, boolean changed) {
    if (!changed) {
      return null;
    }
    int[] originalIndexByNormalizedIndex = new int[originalIndexes.size() + 1];
    for (int i = 0; i < originalIndexes.size(); i++) {
      originalIndexByNormalizedIndex[i] = originalIndexes.get(i);
    }
    originalIndexByNormalizedIndex[originalIndexes.size()] = originalEnd;
    return new NormalizedClassPattern(
        normalized.toString(), originalEnd, originalIndexByNormalizedIndex);
  }

  private static void markClassPatternContent(ClassPatternNormalizationFrame frame) {
    if (frame == null) {
      return;
    }
    frame.hasContent = true;
    frame.allowsNegation = false;
  }

  private static void appendMapped(
      StringBuilder normalized, List<Integer> originalIndexes, char c, int originalIndex) {
    normalized.append(c);
    originalIndexes.add(originalIndex);
  }

  private static void appendMapped(
      StringBuilder normalized, List<Integer> originalIndexes, int cp, int originalIndex) {
    int before = normalized.length();
    normalized.appendCodePoint(cp);
    while (originalIndexes.size() < normalized.length()) {
      originalIndexes.add(originalIndex + originalIndexes.size() - before);
    }
  }

  private static void appendMapped(
      StringBuilder normalized, List<Integer> originalIndexes, String source, int start, int end) {
    for (int i = start; i < end; i++) {
      normalized.append(source.charAt(i));
      originalIndexes.add(i);
    }
  }

  private static final class ClassPatternNormalizationFrame {
    boolean hasContent;
    boolean allowsNegation = true;
  }

  private static final class NormalizedClassPattern {
    private final String pattern;
    private final int originalEnd;
    private final int[] originalIndexByNormalizedIndex;

    NormalizedClassPattern(String pattern, int originalEnd, int[] originalIndexByNormalizedIndex) {
      this.pattern = pattern;
      this.originalEnd = originalEnd;
      this.originalIndexByNormalizedIndex = originalIndexByNormalizedIndex;
    }

    String pattern() {
      return pattern;
    }

    int originalEnd() {
      return originalEnd;
    }

    int originalIndex(int normalizedIndex) {
      if (normalizedIndex < 0) {
        return -1;
      }
      if (normalizedIndex >= originalIndexByNormalizedIndex.length) {
        return originalIndexByNormalizedIndex[originalIndexByNormalizedIndex.length - 1];
      }
      return originalIndexByNormalizedIndex[normalizedIndex];
    }
  }

  private int countAmpersandsAt(int index) {
    int count = 0;
    while (index + count < pattern.length() && pattern.charAt(index + count) == '&') {
      count++;
    }
    return count;
  }

  private ClassOperatorToken scanClassOperatorToken(ClassNormalization normalization) {
    char c = pattern.charAt(pos);
    ClassOperatorTokenKind kind =
        switch (c) {
          case '&' -> ClassOperatorTokenKind.AMPERSAND;
          case '[' -> ClassOperatorTokenKind.OPEN_BRACKET;
          case ']' -> ClassOperatorTokenKind.CLOSE_BRACKET;
          default -> ClassOperatorTokenKind.OTHER;
        };
    int ampersands = 0;
    OddAmpersandRunTail ampersandTail = null;
    int ampersandTailAmpersands = 0;
    OddAmpersandRunTail tailAfterFirstAmpersandPair = null;
    if (kind == ClassOperatorTokenKind.AMPERSAND) {
      ampersands = countAmpersandsAt(pos);
      ampersandTail = inspectOddAmpersandRunTail(pos + ampersands);
      if (ampersandTail.pos() < pattern.length() && pattern.charAt(ampersandTail.pos()) == '&') {
        ampersandTailAmpersands = inspectNormalizedAmpersandRun(ampersandTail.pos()).count();
      }
      if (ampersands >= 2) {
        tailAfterFirstAmpersandPair = inspectOddAmpersandRunTail(pos + 2);
      }
    }
    return new ClassOperatorToken(
        pos,
        c,
        kind,
        normalization,
        ampersands,
        ampersandTail,
        ampersandTailAmpersands,
        tailAfterFirstAmpersandPair);
  }

  private enum ClassOperatorTokenKind {
    AMPERSAND,
    OPEN_BRACKET,
    CLOSE_BRACKET,
    OTHER
  }

  private record ClassOperatorToken(
      int index,
      char c,
      ClassOperatorTokenKind kind,
      ClassNormalization normalization,
      int ampersands,
      OddAmpersandRunTail ampersandTail,
      int ampersandTailAmpersands,
      OddAmpersandRunTail tailAfterFirstAmpersandPair) {
    boolean isAmpersand() {
      return kind == ClassOperatorTokenKind.AMPERSAND;
    }

    boolean isOpenBracket() {
      return kind == ClassOperatorTokenKind.OPEN_BRACKET;
    }

    boolean isCloseBracket() {
      return kind == ClassOperatorTokenKind.CLOSE_BRACKET;
    }

    boolean isSingleAmpersand() {
      return ampersands == 1;
    }

    boolean evenAmpersands() {
      return ampersands % 2 == 0;
    }

    boolean oddAmpersands() {
      return ampersands % 2 == 1;
    }

    boolean ampersandTailHasSingleAmpersand() {
      return ampersandTailAmpersands == 1;
    }

    boolean skippedZeroWidthSyntax() {
      return normalization.skippedZeroWidthSyntax();
    }

    boolean skippedCommentsTrivia() {
      return normalization.skippedCommentsTrivia();
    }
  }

  private CharClassBuilder snapshotPendingExpression(ClassExpressionFrame frame) {
    if (frame.accumulatedClass == null && !frame.hasPendingScalarItems) {
      return null;
    }
    CharClassBuilder snapshot = new CharClassBuilder();
    if (frame.accumulatedClass != null) {
      snapshot.addCharClass(frame.accumulatedClass);
    }
    if (frame.hasPendingScalarItems) {
      snapshot.addCharClass(frame.pendingScalarItems);
    }
    return snapshot;
  }

  private boolean canUseCloseBracketAsLiteralAfterRawAmpersand(ClassExpressionFrame frame) {
    return canUseCloseBracketAsClassLiteral(frame, pos)
        && !frame.rawAmpersandSeparatorRepeated
        && pattern.charAt(pos) == ']';
  }

  private void finishRawAmpersandSeparatorWithLiteralCloseBracket(ClassExpressionFrame frame) {
    frame.accumulatedClass = new CharClassBuilder().addCharClass(frame.rawAmpersandLeftExpression);
    addRangeFlags(frame.accumulatedClass, ']', ']', flags | ParseFlags.CLASS_NL);
    frame.currentIntersectionOperand = frame.accumulatedClass;
    frame.currentIntersectionOperandRole = ClassAtomRole.ORDINARY_SCALAR;
    frame.pendingScalarItems = new CharClassBuilder();
    frame.hasPendingScalarItems = false;
    frame.pendingScalarItemsAfterCurrentOperand = false;
    frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
    frame.rawAmpersandSeparatorActive = false;
    frame.rawAmpersandSeparatorSkippedCommentsTrivia = false;
    frame.rawAmpersandSeparatorRepeated = false;
    frame.rawAmpersandLeftExpression = null;
    pos++;
  }

  private void finishRawAmpersandSeparatorBeforeClassClose(ClassExpressionFrame frame) {
    frame.accumulatedClass = new CharClassBuilder().addCharClass(frame.rawAmpersandLeftExpression);
    frame.currentIntersectionOperand = frame.accumulatedClass;
    frame.currentIntersectionOperandRole = ClassAtomRole.ORDINARY_SCALAR;
    frame.pendingScalarItems = new CharClassBuilder();
    frame.hasPendingScalarItems = false;
    frame.pendingScalarItemsAfterCurrentOperand = false;
    frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
    frame.rawAmpersandSeparatorActive = false;
    frame.rawAmpersandSeparatorSkippedCommentsTrivia = false;
    frame.rawAmpersandSeparatorRepeated = false;
    frame.rawAmpersandLeftExpression = null;
  }

  private void finishNestedRightBeforeTrailingAmpersand(ClassExpressionFrame frame) {
    boolean rawAmpersandRangeTail = rawAmpersandStartsRangeAt(pos);
    if (!rawAmpersandRangeTail && rawAmpersandBeforeCommentsModeCloseAt(pos)) {
      throw new PatternSyntaxException("bad class syntax", pattern, pos);
    }
    boolean rawAmpersandRangeTailPreservesDeferredScalars =
        rawAmpersandRangeTail && rawAmpersandRangeAtAmpersandPreservesDeferredScalars(pos);
    boolean preserveDeferredOrdinaryScalars =
        !rawAmpersandRangeTail || rawAmpersandRangeTailPreservesDeferredScalars;
    CharClassBuilder expression =
        nestedRightBeforeTrailingAmpersandExpression(
            frame, rawAmpersandRangeTail, preserveDeferredOrdinaryScalars);
    if (!rawAmpersandRangeTail && frame.pendingScalarItemsBeforeEmptyIntersection != null) {
      expression.addCharClass(frame.pendingScalarItemsBeforeEmptyIntersection);
    }
    if (!rawAmpersandRangeTail && frame.pendingScalarItemsBeforeOddAmpersandUnion != null) {
      expression.addCharClass(frame.pendingScalarItemsBeforeOddAmpersandUnion);
    }
    pos++;
    if (!addRawAmpersandRangeTailIfPresent(expression)) {
      addRangeFlags(expression, '&', '&', flags | ParseFlags.CLASS_NL);
    }
    frame.accumulatedClass = expression;
    frame.currentIntersectionOperand = expression;
    frame.currentIntersectionOperandRole = ClassAtomRole.RAW_AMPERSAND_SEPARATOR;
    frame.pendingScalarItems = new CharClassBuilder();
    frame.hasPendingScalarItems = false;
    frame.pendingScalarItemsAfterCurrentOperand = false;
    frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
    frame.pendingScalarItemsBeforeEmptyIntersection = null;
    frame.pendingScalarItemsBeforeOddAmpersandUnion = null;
    frame.parsingIntersectionRight = false;
    frame.intersectionRightStartedAfterCommentsTrivia = false;
    frame.intersectionStartedAfterOddAmpersandRun = false;
    frame.oddAmpersandUnionStartedWithOrdinaryScalars = false;
    frame.intersectionRight = null;
    frame.intersectionRightHasExpression = false;
    frame.intersectionRightOnlyNestedClasses = false;
  }

  private boolean rawAmpersandBeforeCommentsModeCloseAt(int ampersandIndex) {
    if ((flags & ParseFlags.COMMENTS) == 0) {
      return false;
    }
    ClassSyntaxLookahead tail = inspectNormalizedClassSyntax(ampersandIndex + 1);
    return tail.skippedCommentsTrivia()
        && tail.pos() < pattern.length()
        && pattern.charAt(tail.pos()) == ']';
  }

  private CharClassBuilder nestedRightBeforeTrailingAmpersandExpression(
      ClassExpressionFrame frame,
      boolean rawAmpersandRangeTail,
      boolean preserveDeferredOrdinaryScalars) {
    CharClassBuilder expression;
    if (frame.accumulatedClass == null) {
      expression = snapshotPendingExpression(frame);
      if (expression == null) {
        expression = new CharClassBuilder().addCharClass(frame.intersectionRight);
      } else if (!preserveDeferredOrdinaryScalars && frame.intersectionRight != null) {
        expression.intersect(frame.intersectionRight);
      }
    } else if (frame.currentIntersectionOperandRole == ClassAtomRole.ORDINARY_SCALAR
        && !frame.hasPendingScalarItems) {
      expression = new CharClassBuilder().addCharClass(frame.accumulatedClass);
      if (!preserveDeferredOrdinaryScalars && frame.intersectionRight != null) {
        expression.intersect(frame.intersectionRight);
      }
    } else if (frame.hasPendingScalarItems
        && frame.pendingScalarItemsAfterCurrentOperand
        && frame.pendingScalarRole == ClassAtomRole.ORDINARY_SCALAR) {
      expression = new CharClassBuilder().addCharClass(frame.accumulatedClass);
      expression.intersect(frame.intersectionRight);
      if (rawAmpersandRangeTail) {
        CharClassBuilder pendingOperand =
            new CharClassBuilder().addCharClass(frame.pendingScalarItems);
        pendingOperand.intersect(frame.intersectionRight);
        expression.addCharClass(pendingOperand);
      } else {
        expression.addCharClass(frame.pendingScalarItems);
      }
    } else {
      expression = new CharClassBuilder().addCharClass(frame.accumulatedClass);
      expression.intersect(frame.intersectionRight);
      if (frame.hasPendingScalarItems) {
        if (!frame.pendingScalarItemsAfterCurrentOperand
            && frame.pendingScalarRole == ClassAtomRole.ORDINARY_SCALAR) {
          if (!preserveDeferredOrdinaryScalars) {
            CharClassBuilder pendingOperand =
                new CharClassBuilder().addCharClass(frame.pendingScalarItems);
            pendingOperand.intersect(frame.intersectionRight);
            expression.addCharClass(pendingOperand);
          } else {
            expression.addCharClass(frame.pendingScalarItems);
          }
        } else {
          CharClassBuilder pendingOperand =
              new CharClassBuilder().addCharClass(frame.pendingScalarItems);
          pendingOperand.intersect(frame.intersectionRight);
          expression.addCharClass(pendingOperand);
        }
      }
    }
    return expression;
  }

  private boolean finishNestedRightAfterOddAmpersandRun(
      ClassExpressionFrame frame, OddAmpersandRunTail tail) {
    char c = pattern.charAt(tail.pos());
    if (c == '&') {
      NormalizedAmpersandRun normalizedRun = inspectNormalizedAmpersandRun(tail.pos());
      int ampersands = normalizedRun.count();
      CharClassBuilder expression =
          nestedRightBeforeTrailingAmpersandExpression(frame, false, ampersands % 2 == 0);
      pos = normalizedRun.pos();
      if (ampersands % 2 == 0) {
        if (normalizedRunEndsBeforeLiteralCloseBracket(frame, normalizedRun)) {
          addRangeFlags(expression, ']', ']', flags | ParseFlags.CLASS_NL);
          pos++;
        } else {
          rejectEvenAmpersandRunBeforeCommentsModeClassTerminator(frame, normalizedRun);
          addRangeFlags(expression, '&', '&', flags | ParseFlags.CLASS_NL);
        }
      } else if (ampersands == 1) {
        CharClassBuilder literalTail = parseOddAmpersandRangeTailLiteralsIfPresent();
        if (literalTail != null) {
          expression.intersect(literalTail);
        }
        if (frame.intersectionRight != null) {
          expression.intersect(frame.intersectionRight);
        }
      }
      finishNestedRightAsCurrentOperand(frame, expression);
      return true;
    }
    if (tail.skippedCommentsTrivia() && c == '[') {
      pos = tail.pos();
      finishNestedRightAsEmptyUntilClassTerminator(frame);
      return true;
    }
    if (commentsTailEndsBeforeLiteralCloseBracket(frame, tail)) {
      pos = tail.pos() + 1;
      CharClassBuilder expression =
          nestedRightBeforeTrailingAmpersandExpression(frame, false, false);
      addRangeFlags(expression, ']', ']', flags | ParseFlags.CLASS_NL);
      finishNestedRightAsCurrentOperand(frame, expression);
      return true;
    }
    if (tail.skippedCommentsTrivia()) {
      pos = tail.pos();
      boolean preserveDeferredOrdinaryScalars = commentsOddRunRhsStartsOrdinaryScalar(tail.pos());
      CharClassBuilder expression =
          nestedRightBeforeTrailingAmpersandExpression(
              frame, false, preserveDeferredOrdinaryScalars);
      if (frame.currentIntersectionOperandRole == ClassAtomRole.RAW_AMPERSAND_SEPARATOR
          || frame.pendingScalarItemsBeforeOddAmpersandUnion != null
          || frame.intersectionStartedAfterOddAmpersandRun
          || accumulatedClassAlreadyContainsLiteralAmpersand(frame, expression)) {
        addRangeFlags(expression, '&', '&', flags | ParseFlags.CLASS_NL);
      }
      finishNestedRightAsCurrentOperand(frame, expression);
      return true;
    }
    return false;
  }

  private boolean commentsOddRunRhsStartsOrdinaryScalar(int index) {
    ClassSyntaxLookahead lookahead = inspectNormalizedClassSyntax(index);
    index = lookahead.pos();
    if (index >= pattern.length()) {
      return false;
    }
    if (pattern.charAt(index) == '[' || pattern.charAt(index) == '&') {
      return false;
    }
    if (startsPredefinedClassAt(index) || startsPropertyClassAt(index)) {
      return false;
    }
    int scalarEnd;
    int scalar;
    if (index + 1 < pattern.length()
        && pattern.charAt(index) == '\\'
        && pattern.charAt(index + 1) == 'Q') {
      int quoteIndex = index + 2;
      if (quoteIndex >= pattern.length()
          || (quoteIndex + 1 < pattern.length()
              && pattern.charAt(quoteIndex) == '\\'
              && pattern.charAt(quoteIndex + 1) == 'E')) {
        return false;
      }
      scalar = pattern.codePointAt(quoteIndex);
      scalarEnd = index + 2;
      while (scalarEnd < pattern.length()) {
        if (scalarEnd + 1 < pattern.length()
            && pattern.charAt(scalarEnd) == '\\'
            && pattern.charAt(scalarEnd + 1) == 'E') {
          scalarEnd += 2;
          break;
        }
        scalarEnd += Character.charCount(pattern.codePointAt(scalarEnd));
      }
    } else if (pattern.charAt(index) == '\\') {
      if (index + 1 >= pattern.length()) {
        return false;
      }
      scalar = pattern.codePointAt(index + 1);
      scalarEnd = index + 2;
    } else {
      scalar = pattern.codePointAt(index);
      scalarEnd = index + Character.charCount(scalar);
    }
    return scalar <= 0xFF && !classAtomHasRangeTailAt(scalarEnd);
  }

  private boolean classAtomHasRangeTailAt(int index) {
    ClassSyntaxLookahead lookahead = inspectNormalizedClassSyntax(index);
    index = lookahead.pos();
    if (index >= pattern.length() || pattern.charAt(index) != '-') {
      return false;
    }
    int saved = pos;
    pos = index;
    boolean hasRangeTail = hasRangeEndpointAfterHyphen();
    pos = saved;
    return hasRangeTail;
  }

  private void finishNestedRightAsCurrentOperand(
      ClassExpressionFrame frame, CharClassBuilder expression) {
    frame.accumulatedClass = expression;
    frame.currentIntersectionOperand = expression;
    frame.currentIntersectionOperandRole = ClassAtomRole.RAW_AMPERSAND_SEPARATOR;
    frame.pendingScalarItems = new CharClassBuilder();
    frame.hasPendingScalarItems = false;
    frame.pendingScalarItemsAfterCurrentOperand = false;
    frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
    frame.pendingScalarItemsBeforeEmptyIntersection = null;
    frame.pendingScalarItemsBeforeOddAmpersandUnion = null;
    frame.parsingIntersectionRight = false;
    frame.intersectionRightStartedAfterCommentsTrivia = false;
    frame.intersectionStartedAfterOddAmpersandRun = false;
    frame.oddAmpersandUnionStartedWithOrdinaryScalars = false;
    frame.intersectionRight = null;
    frame.intersectionRightHasExpression = false;
    frame.intersectionRightOnlyNestedClasses = false;
  }

  private void finishNestedRightAsEmptyUntilClassTerminator(ClassExpressionFrame frame) {
    frame.accumulatedClass = new CharClassBuilder();
    frame.currentIntersectionOperand = frame.accumulatedClass;
    frame.currentIntersectionOperandRole = ClassAtomRole.INTERSECTION_OPERAND;
    frame.pendingScalarItems = new CharClassBuilder();
    frame.hasPendingScalarItems = false;
    frame.pendingScalarItemsAfterCurrentOperand = false;
    frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
    frame.pendingScalarItemsBeforeEmptyIntersection = null;
    frame.pendingScalarItemsBeforeOddAmpersandUnion = null;
    frame.parsingIntersectionRight = false;
    frame.intersectionRightStartedAfterCommentsTrivia = false;
    frame.intersectionRight = null;
    frame.intersectionRightHasExpression = false;
    frame.intersectionRightOnlyNestedClasses = false;
    frame.ignoreUntilClassTerminator = true;
    frame.suppressNegation = true;
    frame.propagateSuppressNegation = true;
  }

  private void finishRawAmpersandSeparatorRun(ClassExpressionFrame frame, int ampersands) {
    if (frame.rawAmpersandLeftExpression.isEmpty()) {
      throw new PatternSyntaxException("bad class syntax", pattern, pos);
    }
    pos += ampersands;
    if (ampersands % 2 == 0) {
      OddAmpersandRunTail tail = inspectOddAmpersandRunTail(pos);
      if (tail.skippedNormalizedSyntax()) {
        pos = tail.pos();
        if (pos >= pattern.length()) {
          throw new PatternSyntaxException("missing closing ]", pattern, frame.classStart);
        }
        if (frame.shouldCompleteAt(pattern.charAt(pos))) {
          if (tail.skippedCommentsTrivia()) {
            if (commentsTailEndsBeforeLiteralCloseBracket(frame, tail)) {
              frame.accumulatedClass =
                  new CharClassBuilder().addCharClass(frame.rawAmpersandLeftExpression);
              addRangeFlags(frame.accumulatedClass, ']', ']', flags | ParseFlags.CLASS_NL);
              frame.currentIntersectionOperand = frame.accumulatedClass;
              frame.currentIntersectionOperandRole = ClassAtomRole.RAW_AMPERSAND_SEPARATOR;
              frame.pendingScalarItems = new CharClassBuilder();
              frame.hasPendingScalarItems = false;
              frame.pendingScalarItemsAfterCurrentOperand = false;
              frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
              frame.rawAmpersandSeparatorActive = false;
              frame.rawAmpersandLeftExpression = null;
              pos++;
              return;
            }
            throw new PatternSyntaxException("bad class syntax", pattern, pos);
          }
          frame.accumulatedClass =
              new CharClassBuilder().addCharClass(snapshotPendingExpression(frame));
          frame.currentIntersectionOperand = frame.accumulatedClass;
          frame.pendingScalarItems = new CharClassBuilder();
          frame.hasPendingScalarItems = false;
          frame.pendingScalarItemsAfterCurrentOperand = false;
          frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
          frame.rawAmpersandSeparatorActive = false;
          frame.rawAmpersandLeftExpression = null;
          return;
        }
        if (!tail.skippedCommentsTrivia()) {
          rejectInvalidRangeTailAfterOddAmpersandRun();
        } else if (pattern.charAt(pos) == '-') {
          NormalizedAmpersandRun normalizedRun = inspectNormalizedAmpersandRun(pos + 1);
          if (normalizedRun.count() >= 2
              && normalizedRun.pos() < pattern.length()
              && pattern.charAt(normalizedRun.pos()) != ']'
              && pattern.charAt(normalizedRun.pos()) != '[') {
            finishRawAmpersandSeparatorAsEmptyUntilClassTerminator(frame);
            return;
          }
          if (normalizedRun.count() >= 2) {
            throw new PatternSyntaxException("bad class syntax", pattern, pos);
          }
          frame.accumulatedClass =
              new CharClassBuilder().addCharClass(frame.rawAmpersandLeftExpression);
          if (!addCommentsModeHyphenRangeTailIfPresent(frame.accumulatedClass)) {
            addRangeFlags(frame.accumulatedClass, '-', '-', flags | ParseFlags.CLASS_NL);
            pos++;
          }
          frame.currentIntersectionOperand = frame.accumulatedClass;
          frame.currentIntersectionOperandRole = ClassAtomRole.RAW_AMPERSAND_SEPARATOR;
          frame.pendingScalarItems = new CharClassBuilder();
          frame.hasPendingScalarItems = false;
          frame.pendingScalarItemsAfterCurrentOperand = false;
          frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
          frame.rawAmpersandSeparatorActive = false;
          frame.rawAmpersandLeftExpression = null;
          return;
        }
        if (tail.skippedCommentsTrivia() && pattern.charAt(pos) == '[') {
          finishRawAmpersandSeparatorAsEmptyUntilClassTerminator(frame);
          return;
        }
        boolean includeSeparatorLiteral = !tail.skippedCommentsTrivia();
        if (pattern.charAt(pos) == '&') {
          NormalizedAmpersandRun normalizedRun = inspectNormalizedAmpersandRun(pos);
          int tailAmpersands = normalizedRun.count();
          boolean literalCloseBracketTail;
          if (tailAmpersands == 1) {
            pos = normalizedRun.pos();
            frame.accumulatedClass =
                new CharClassBuilder().addCharClass(frame.rawAmpersandLeftExpression);
            frame.currentIntersectionOperand = frame.accumulatedClass;
            frame.currentIntersectionOperandRole = ClassAtomRole.RAW_AMPERSAND_SEPARATOR;
            frame.pendingScalarItems = new CharClassBuilder();
            frame.hasPendingScalarItems = false;
            frame.pendingScalarItemsAfterCurrentOperand = false;
            frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
            frame.rawAmpersandSeparatorActive = false;
            frame.rawAmpersandLeftExpression = null;
            if (pos >= pattern.length()) {
              throw new PatternSyntaxException("missing closing ]", pattern, frame.classStart);
            }
            if (!frame.shouldCompleteAt(pattern.charAt(pos))) {
              frame.parsingIntersectionRight = true;
              frame.intersectionRightStartedAfterCommentsTrivia = false;
              frame.intersectionRight = null;
              frame.intersectionRightHasExpression = false;
              frame.intersectionRightOnlyNestedClasses = false;
            }
            return;
          } else {
            includeSeparatorLiteral = tailAmpersands % 2 == 0;
            literalCloseBracketTail =
                includeSeparatorLiteral
                    && normalizedRunEndsBeforeLiteralCloseBracket(frame, normalizedRun);
          }
          if (includeSeparatorLiteral && !literalCloseBracketTail) {
            rejectEvenAmpersandRunBeforeCommentsModeClassTerminator(frame, normalizedRun);
          }
          pos = literalCloseBracketTail ? normalizedRun.pos() + 1 : normalizedRun.pos();
          if (literalCloseBracketTail) {
            frame.accumulatedClass =
                new CharClassBuilder().addCharClass(frame.rawAmpersandLeftExpression);
            addRangeFlags(frame.accumulatedClass, ']', ']', flags | ParseFlags.CLASS_NL);
            frame.currentIntersectionOperand = frame.accumulatedClass;
            frame.currentIntersectionOperandRole = ClassAtomRole.RAW_AMPERSAND_SEPARATOR;
            frame.pendingScalarItems = new CharClassBuilder();
            frame.hasPendingScalarItems = false;
            frame.pendingScalarItemsAfterCurrentOperand = false;
            frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
            frame.rawAmpersandSeparatorActive = false;
            frame.rawAmpersandLeftExpression = null;
            return;
          }
        }
        boolean nestedClassFollowedByIntersection =
            pos < pattern.length()
                && pattern.charAt(pos) == '['
                && nestedClassFollowedByIntersectionAt(pos);
        boolean keepNestedRawLeft =
            frame.rawAmpersandLeftExpressionNestedOperand && !nestedClassFollowedByIntersection;
        CharClassBuilder expression =
            includeSeparatorLiteral
                ? ampersands > 2 || keepNestedRawLeft
                    ? snapshotPendingExpression(frame)
                    : frame.rawAmpersandLeftExpressionNestedOperand
                        ? new CharClassBuilder()
                        : snapshotPendingExpression(frame)
                : frame.rawAmpersandLeftExpression;
        if (includeSeparatorLiteral && (ampersands > 2 || keepNestedRawLeft)) {
          addRangeFlags(expression, '&', '&', flags | ParseFlags.CLASS_NL);
        }
        if (frame.rawAmpersandLeftExpressionOrdinaryScalars
            && !frame.rawAmpersandLeftExpression.isEmpty()) {
          addPendingScalarsBeforeOddAmpersandUnion(frame, frame.rawAmpersandLeftExpression);
        }
        addRawAmpersandRangeTailIfPresent(expression);
        frame.accumulatedClass = new CharClassBuilder().addCharClass(expression);
        frame.currentIntersectionOperand = frame.accumulatedClass;
        frame.pendingScalarItems = new CharClassBuilder();
        frame.hasPendingScalarItems = false;
        frame.pendingScalarItemsAfterCurrentOperand = false;
        frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
      }
      if (!tail.skippedNormalizedSyntax()) {
        rejectInvalidRangeTailAfterOddAmpersandRun();
        CharClassBuilder expression = snapshotPendingExpression(frame);
        if (pos < pattern.length() && pattern.charAt(pos) == '[') {
          boolean nestedClassFollowedByIntersection = nestedClassFollowedByIntersectionAt(pos);
          if (ampersands > 2
              || (frame.rawAmpersandLeftExpressionNestedOperand
                  && !nestedClassFollowedByIntersection)) {
            addRangeFlags(expression, '&', '&', flags | ParseFlags.CLASS_NL);
          } else {
            if (frame.rawAmpersandLeftExpressionOrdinaryScalars
                && !frame.rawAmpersandLeftExpression.isEmpty()) {
              addPendingScalarsBeforeOddAmpersandUnion(frame, frame.rawAmpersandLeftExpression);
            }
          }
          frame.accumulatedClass = new CharClassBuilder().addCharClass(expression);
          frame.currentIntersectionOperand = frame.accumulatedClass;
          frame.currentIntersectionOperandRole = ClassAtomRole.RAW_AMPERSAND_SEPARATOR;
          frame.currentIntersectionOperandNestedClass = false;
          frame.pendingScalarItems = new CharClassBuilder();
          frame.hasPendingScalarItems = false;
          frame.pendingScalarItemsAfterCurrentOperand = false;
          frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
        }
        if (addRawAmpersandRangeTailIfPresent(expression)) {
          frame.accumulatedClass = new CharClassBuilder().addCharClass(expression);
          frame.currentIntersectionOperand = frame.accumulatedClass;
          frame.pendingScalarItems = new CharClassBuilder();
          frame.hasPendingScalarItems = false;
          frame.pendingScalarItemsAfterCurrentOperand = false;
          frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
        }
      }
      frame.rawAmpersandSeparatorActive = false;
      frame.rawAmpersandLeftExpression = null;
      return;
    }
    if (frame.rawAmpersandLeftExpressionOrdinaryScalars
        && !frame.rawAmpersandLeftExpression.isEmpty()) {
      addPendingScalarsBeforeOddAmpersandUnion(frame, frame.rawAmpersandLeftExpression);
    }
    frame.accumulatedClass = new CharClassBuilder().addCharClass(frame.rawAmpersandLeftExpression);
    frame.currentIntersectionOperand = frame.accumulatedClass;
    frame.currentIntersectionOperandRole = ClassAtomRole.RAW_AMPERSAND_SEPARATOR;
    frame.pendingScalarItems = new CharClassBuilder();
    frame.hasPendingScalarItems = false;
    frame.pendingScalarItemsAfterCurrentOperand = false;
    frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
    frame.rawAmpersandSeparatorActive = false;
    frame.rawAmpersandLeftExpression = null;
    frame.parsingIntersectionRight = true;
    frame.intersectionHasLeftExpression = true;
    frame.intersectionRight = null;
    frame.intersectionRightHasExpression = false;
    frame.intersectionRightOnlyNestedClasses = false;
  }

  private void finishRawAmpersandSeparatorAsEmptyUntilClassTerminator(ClassExpressionFrame frame) {
    frame.accumulatedClass = new CharClassBuilder();
    frame.currentIntersectionOperand = frame.accumulatedClass;
    frame.currentIntersectionOperandRole = ClassAtomRole.RAW_AMPERSAND_SEPARATOR;
    frame.pendingScalarItems = new CharClassBuilder();
    frame.hasPendingScalarItems = false;
    frame.pendingScalarItemsAfterCurrentOperand = false;
    frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
    frame.rawAmpersandSeparatorActive = false;
    frame.rawAmpersandSeparatorSkippedCommentsTrivia = false;
    frame.rawAmpersandSeparatorRepeated = false;
    frame.rawAmpersandLeftExpression = null;
    frame.parsingIntersectionRight = false;
    frame.ignoreUntilClassTerminator = true;
    frame.suppressNegation = true;
  }

  private boolean shouldParseOddAmpersandRunAsUnion(ClassExpressionFrame frame, int ampersands) {
    if (snapshotOddAmpersandUnionExpression(frame) == null) {
      return false;
    }
    if (frame.pendingScalarItemsAfterCurrentOperand
        && frame.pendingScalarRole == ClassAtomRole.ORDINARY_SCALAR) {
      return false;
    }
    int afterRun = pos + ampersands;
    if ((flags & ParseFlags.COMMENTS) == 0) {
      return true;
    }
    if (!frame.bracketed) {
      return true;
    }
    OddAmpersandRunTail tail = inspectOddAmpersandRunTail(afterRun);
    return !tail.skippedCommentsTrivia()
        || tail.pos() >= pattern.length()
        || pattern.charAt(tail.pos()) != '[';
  }

  private boolean shouldFinishOddAmpersandRhsAsUnion(
      ClassExpressionFrame frame, ClassOperatorToken token) {
    if (!token.oddAmpersands() || frame.intersectionRight != null) {
      return false;
    }
    if (snapshotOddAmpersandUnionExpression(frame) == null) {
      return false;
    }
    if (token.skippedZeroWidthSyntax() && !token.skippedCommentsTrivia()) {
      return hasDeferredOrdinaryScalarsBeforeIntersectionOperand(frame);
    }
    return (token.skippedCommentsTrivia() || frame.intersectionRightStartedAfterCommentsTrivia)
        && hasDeferredOrdinaryScalarsBeforeIntersectionOperand(frame);
  }

  private static boolean hasDeferredOrdinaryScalarsBeforeIntersectionOperand(
      ClassExpressionFrame frame) {
    return frame.currentIntersectionOperandRole == ClassAtomRole.INTERSECTION_OPERAND
        && frame.currentIntersectionOperand != null
        && frame.hasPendingScalarItems
        && !frame.pendingScalarItemsAfterCurrentOperand
        && frame.pendingScalarRole == ClassAtomRole.ORDINARY_SCALAR;
  }

  private void finishOddAmpersandUnionRun(ClassExpressionFrame frame, int ampersands) {
    boolean startedWithOnlyOrdinaryScalars =
        (frame.currentIntersectionOperand == null
                && frame.accumulatedClass == null
                && frame.hasPendingScalarItems
                && !frame.pendingScalarItemsAfterCurrentOperand
                && frame.pendingScalarRole == ClassAtomRole.ORDINARY_SCALAR)
            || (frame.currentIntersectionOperand != null
                && !frame.hasPendingScalarItems
                && (!frame.currentIntersectionOperandNestedClass
                    || frame.currentIntersectionOperandIsOnlyOperand)
                && (frame.currentIntersectionOperandRole == ClassAtomRole.ORDINARY_SCALAR
                    || frame.currentIntersectionOperandRole == ClassAtomRole.INTERSECTION_OPERAND));
    frame.oddAmpersandUnionStartedWithOrdinaryScalars =
        frame.oddAmpersandUnionStartedWithOrdinaryScalars || startedWithOnlyOrdinaryScalars;
    if (frame.rawAmpersandSeparatorActive
        && frame.rawAmpersandLeftExpressionOrdinaryScalars
        && frame.rawAmpersandLeftExpression != null
        && !frame.rawAmpersandLeftExpression.isEmpty()) {
      addPendingScalarsBeforeOddAmpersandUnion(frame, frame.rawAmpersandLeftExpression);
    } else if (frame.currentIntersectionOperand == null && frame.hasPendingScalarItems) {
      addPendingScalarsBeforeOddAmpersandUnion(frame, frame.pendingScalarItems);
    } else if (frame.currentIntersectionOperand != null
        && frame.hasPendingScalarItems
        && !frame.pendingScalarItemsAfterCurrentOperand
        && frame.pendingScalarRole == ClassAtomRole.ORDINARY_SCALAR) {
      addPendingScalarsBeforeOddAmpersandUnion(frame, frame.pendingScalarItems);
    }
    pos += ampersands;
    OddAmpersandRunTail tail = inspectOddAmpersandRunTail(pos);
    boolean includeNestedAmpersandLiteral =
        !tail.skippedCommentsTrivia()
            || ((frame.intersectionStartedAfterOddAmpersandRun
                    || frame.oddAmpersandUnionStartedWithOrdinaryScalars)
                && commentsOddRunRhsStartsOrdinaryScalar(tail.pos()));
    CharClassBuilder expression =
        snapshotOddAmpersandUnionExpression(frame, includeNestedAmpersandLiteral);
    boolean consumedRawAmpersandRangeTail = false;
    boolean rawAmpersandRangeTailEndedAtLiteralAmpersand = false;
    if (!tail.skippedNormalizedSyntax()) {
      rejectInvalidRangeTailAfterOddAmpersandRun();
      if (rawAmpersandRangeTailUsesCurrentOperandOnly()
          && frame.currentIntersectionOperand != null
          && frame.hasPendingScalarItems
          && !frame.pendingScalarItemsAfterCurrentOperand
          && frame.pendingScalarRole == ClassAtomRole.ORDINARY_SCALAR) {
        expression = new CharClassBuilder().addCharClass(frame.currentIntersectionOperand);
      }
      rawAmpersandRangeTailEndedAtLiteralAmpersand = rawAmpersandRangeTailEndsAtLiteralAmpersand();
      consumedRawAmpersandRangeTail = addRawAmpersandRangeTailIfPresent(expression);
      if (!consumedRawAmpersandRangeTail) {
        addRangeFlags(expression, '&', '&', flags | ParseFlags.CLASS_NL);
      }
    } else {
      pos = tail.pos();
      if (!tail.skippedCommentsTrivia()) {
        rejectInvalidRangeTailAfterOddAmpersandRun();
      }
      if (tail.skippedCommentsTrivia()
          && frame.currentIntersectionOperand != null
          && (pos >= pattern.length() || pattern.charAt(pos) != '&')) {
        frame.commentsOddRunCurrentOperandForRhs =
            new CharClassBuilder().addCharClass(frame.currentIntersectionOperand);
      }
      if (pos >= pattern.length()) {
        throw new PatternSyntaxException("missing closing ]", pattern, frame.classStart);
      }
      if (frame.shouldCompleteAt(pattern.charAt(pos))) {
        if (tail.skippedCommentsTrivia()) {
          if (commentsTailEndsBeforeLiteralCloseBracket(frame, tail)) {
            addRangeFlags(expression, ']', ']', flags | ParseFlags.CLASS_NL);
            pos++;
          } else if (!frame.bracketed && pattern.charAt(pos) == '&') {
            // Let the normalized ampersand-run handler below consume parent-level continuations.
          } else if (expression.isEmpty()) {
            // Let an empty completed expression close below.
          } else if (frame.currentIntersectionOperand == null
              && frame.accumulatedClass == null
              && frame.pendingScalarRole == ClassAtomRole.ORDINARY_SCALAR
              && expression.numRunes() == 1
              && expression.contains('&')) {
            if (frame.parsingIntersectionRight && frame.intersectionHasLeftExpression) {
              expression = new CharClassBuilder();
              frame.ignoreUntilClassTerminator = false;
              frame.suppressNegation = false;
            } else {
              throw new PatternSyntaxException("bad class syntax", pattern, pos);
            }
          } else {
            CharClassBuilder emptyIntersection =
                emptyCompletedIntersectionBeforeCommentsClose(frame);
            if (emptyIntersection == null) {
              throw new PatternSyntaxException("bad class syntax", pattern, pos);
            }
            expression = emptyIntersection;
          }
        } else if (tail.skippedZeroWidthSyntax() && canUseCloseBracketAsClassLiteral(frame, pos)) {
          pos++;
          finishOddAmpersandRunAsEmptyUntilClassTerminator(frame);
          return;
        } else {
          rawAmpersandRangeTailEndedAtLiteralAmpersand =
              rawAmpersandRangeTailEndsAtLiteralAmpersand();
          consumedRawAmpersandRangeTail = addRawAmpersandRangeTailIfPresent(expression);
          if (!consumedRawAmpersandRangeTail) {
            addRangeFlags(expression, '&', '&', flags | ParseFlags.CLASS_NL);
          }
        }
      }
      if (pattern.charAt(pos) == '&') {
        NormalizedAmpersandRun normalizedRun = inspectNormalizedAmpersandRun(pos);
        int tailAmpersands = normalizedRun.count();
        if ((ampersands + tailAmpersands) % 2 == 0) {
          pos = normalizedRun.pos();
          if (pos >= pattern.length() || pattern.charAt(pos) != '[') {
            frame.pendingScalarItemsBeforeOddAmpersandUnion = null;
          }
          if (normalizedRunEndsBeforeLiteralCloseBracket(frame, normalizedRun)) {
            CharClassBuilder closeBracket = new CharClassBuilder();
            addRangeFlags(closeBracket, ']', ']', flags | ParseFlags.CLASS_NL);
            expression.intersect(closeBracket);
            pos = normalizedRun.pos() + 1;
          } else {
            if (rawAmpersandRangeTailAtCurrentPosition()
                && normalizedRun.skippedNormalizedSyntax()) {
              startIntersectionRightAfterNormalizedOddRun(frame, expression, normalizedRun);
              return;
            } else if (rawAmpersandRangeTailAtCurrentPosition()) {
              CharClassBuilder rangeTail = parseRawAmpersandRangeTailIfPresent();
              expression.intersect(rangeTail);
              consumedRawAmpersandRangeTail = true;
            } else if (pos < pattern.length() && !frame.shouldCompleteAt(pattern.charAt(pos))) {
              startIntersectionRightAfterNormalizedOddRun(frame, expression, normalizedRun);
              return;
            } else if (hasDeferredOrdinaryScalarsBeforeIntersectionOperand(frame)) {
              expression = new CharClassBuilder().addCharClass(frame.currentIntersectionOperand);
            }
          }
        } else if (tailAmpersands == 1) {
          CharClassBuilder intersectionLeft = expression;
          if (frame.currentIntersectionOperand != null) {
            intersectionLeft =
                new CharClassBuilder().addCharClass(frame.currentIntersectionOperand);
          }
          startIntersectionRightAfterOddRunDelimiter(frame, intersectionLeft, tail);
          return;
        } else if (tailAmpersands % 2 == 0) {
          if (normalizedRunEndsBeforeLiteralCloseBracket(frame, normalizedRun)) {
            addRangeFlags(expression, ']', ']', flags | ParseFlags.CLASS_NL);
            pos = normalizedRun.pos() + 1;
          } else {
            rejectEvenAmpersandRunBeforeCommentsModeClassTerminator(frame, normalizedRun);
            pos = normalizedRun.pos();
            if (normalizedRun.skippedTrailingCommentsTrivia()
                && commentsModeRangeTailBeforeIntersectionAtCurrentPosition()) {
              throw new PatternSyntaxException("bad class syntax", pattern, pos);
            }
            if (normalizedRun.skippedTrailingCommentsTrivia()
                && pos < pattern.length()
                && pattern.charAt(pos) == '[') {
              finishOddAmpersandRunAsEmptyUntilClassTerminator(frame);
              return;
            }
            if (rawAmpersandRangeTailUsesCurrentOperandOnly()
                && frame.currentIntersectionOperand != null
                && frame.hasPendingScalarItems
                && !frame.pendingScalarItemsAfterCurrentOperand
                && frame.pendingScalarRole == ClassAtomRole.ORDINARY_SCALAR) {
              expression = new CharClassBuilder().addCharClass(frame.currentIntersectionOperand);
            }
            rawAmpersandRangeTailEndedAtLiteralAmpersand =
                rawAmpersandRangeTailEndsAtLiteralAmpersand();
            if (normalizedRun.skippedTrailingCommentsTrivia()
                && rawAmpersandRangeTailEndpointIsAmpersand()) {
              CharClassBuilder literalTail = parseOddAmpersandRangeTailLiteralsIfPresent();
              if (literalTail != null) {
                expression.addCharClass(literalTail);
                consumedRawAmpersandRangeTail = true;
              }
            } else {
              boolean canConsumeRawAmpersandRangeTail =
                  !normalizedRun.skippedTrailingCommentsTrivia()
                      || rawAmpersandRangeTailEndedAtLiteralAmpersand;
              consumedRawAmpersandRangeTail =
                  canConsumeRawAmpersandRangeTail && addRawAmpersandRangeTailIfPresent(expression);
            }
            if (!consumedRawAmpersandRangeTail && !normalizedRun.skippedTrailingCommentsTrivia()) {
              addRangeFlags(expression, '&', '&', flags | ParseFlags.CLASS_NL);
            }
          }
        } else {
          if (normalizedRun.pos() < pattern.length()
              && !frame.shouldCompleteAt(pattern.charAt(normalizedRun.pos()))) {
            pos = normalizedRun.pos();
            if (normalizedRun.skippedInterAmpersandCommentsTrivia()) {
              if (!rawAmpersandRangeTailAtCurrentPosition()) {
                expression =
                    frame.currentIntersectionOperand == null
                        ? new CharClassBuilder()
                        : new CharClassBuilder().addCharClass(frame.currentIntersectionOperand);
              }
              finishOddAmpersandRunAsCurrentUntilClassTerminator(frame, expression);
              return;
            }
            startIntersectionRightAfterNormalizedOddRun(frame, expression, normalizedRun);
            return;
          }
          if (frame.currentIntersectionOperand != null) {
            expression = new CharClassBuilder().addCharClass(frame.currentIntersectionOperand);
          }
          frame.pendingScalarItemsBeforeOddAmpersandUnion = null;
          pos = normalizedRun.pos();
        }
      } else if (pattern.charAt(pos) == '[' && tail.skippedCommentsTrivia()) {
        if (frame.parsingIntersectionRight && frame.intersectionHasLeftExpression) {
          skipIgnoredBracketedClass();
          if (pos < pattern.length() && pattern.charAt(pos) == '&') {
            pos++;
            addRawAmpersandRangeTailIfPresent(new CharClassBuilder());
          } else if (pos < pattern.length()
              && pattern.charAt(pos) == '-'
              && rawAmpersandRangeTailEndpointIsAmpersand()) {
            pos++;
            if ((flags & ParseFlags.COMMENTS) != 0) {
              skipCommentsAndWhitespace();
            }
            parseCCRangeEndpoint();
            consumeSingleAmpersandAfterIgnoredRangeEndpoint();
          }
          finishOddAmpersandRunAsEmptyUntilClassTerminator(frame);
          return;
        }
        if (!frame.bracketed) {
          skipIgnoredBracketedClass();
          if (pos < pattern.length() && pattern.charAt(pos) == '&') {
            pos++;
            addRawAmpersandRangeTailIfPresent(new CharClassBuilder());
          } else if (pos < pattern.length()
              && pattern.charAt(pos) == '-'
              && rawAmpersandRangeTailEndpointIsAmpersand()) {
            pos++;
            if ((flags & ParseFlags.COMMENTS) != 0) {
              skipCommentsAndWhitespace();
            }
            parseCCRangeEndpoint();
            consumeSingleAmpersandAfterIgnoredRangeEndpoint();
          }
          finishOddAmpersandRunAsEmptyUntilClassTerminator(frame);
          return;
        }
        frame.accumulatedClass = expression;
        frame.currentIntersectionOperand = frame.accumulatedClass;
        frame.pendingScalarItems = new CharClassBuilder();
        frame.hasPendingScalarItems = false;
        frame.pendingScalarItemsAfterCurrentOperand = false;
        frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
        frame.rawAmpersandSeparatorActive = false;
        frame.rawAmpersandLeftExpression = null;
        frame.parsingIntersectionRight = false;
        frame.intersectionStartedAfterOddAmpersandRun = false;
        return;
      } else if (!tail.skippedCommentsTrivia()) {
        if (rawAmpersandRangeTailUsesCurrentOperandOnly()
            && frame.currentIntersectionOperand != null
            && frame.hasPendingScalarItems
            && !frame.pendingScalarItemsAfterCurrentOperand
            && frame.pendingScalarRole == ClassAtomRole.ORDINARY_SCALAR) {
          expression = new CharClassBuilder().addCharClass(frame.currentIntersectionOperand);
        }
        rawAmpersandRangeTailEndedAtLiteralAmpersand =
            rawAmpersandRangeTailEndsAtLiteralAmpersand();
        consumedRawAmpersandRangeTail = addRawAmpersandRangeTailIfPresent(expression);
        if (!consumedRawAmpersandRangeTail) {
          addRangeFlags(expression, '&', '&', flags | ParseFlags.CLASS_NL);
        }
      }
    }
    if (frame.pendingScalarItemsBeforeOddAmpersandUnion != null
        && (!consumedRawAmpersandRangeTail || rawAmpersandRangeTailEndedAtLiteralAmpersand)) {
      expression.addCharClass(frame.pendingScalarItemsBeforeOddAmpersandUnion);
    }
    frame.accumulatedClass = expression;
    frame.currentIntersectionOperand = expression;
    frame.currentIntersectionOperandRole = ClassAtomRole.RAW_AMPERSAND_SEPARATOR;
    frame.pendingScalarItems = new CharClassBuilder();
    frame.hasPendingScalarItems = false;
    frame.pendingScalarItemsAfterCurrentOperand = false;
    frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
    frame.rawAmpersandSeparatorActive = false;
    frame.rawAmpersandLeftExpression = null;
  }

  private CharClassBuilder emptyCompletedIntersectionBeforeCommentsClose(
      ClassExpressionFrame frame) {
    if (frame.accumulatedClass == null || frame.currentIntersectionOperand == null) {
      return null;
    }
    CharClassBuilder completed = new CharClassBuilder().addCharClass(frame.accumulatedClass);
    completed.intersect(frame.currentIntersectionOperand);
    return completed.isEmpty() ? completed : null;
  }

  private void finishOddAmpersandRunAsCurrentUntilClassTerminator(
      ClassExpressionFrame frame, CharClassBuilder expression) {
    frame.accumulatedClass = expression;
    frame.currentIntersectionOperand = expression;
    frame.currentIntersectionOperandRole = ClassAtomRole.RAW_AMPERSAND_SEPARATOR;
    frame.pendingScalarItems = new CharClassBuilder();
    frame.hasPendingScalarItems = false;
    frame.pendingScalarItemsAfterCurrentOperand = false;
    frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
    frame.pendingScalarItemsBeforeOddAmpersandUnion = null;
    frame.rawAmpersandSeparatorActive = false;
    frame.rawAmpersandLeftExpression = null;
    frame.parsingIntersectionRight = false;
    frame.intersectionRightStartedAfterCommentsTrivia = false;
    frame.intersectionStartedAfterOddAmpersandRun = false;
    frame.oddAmpersandUnionStartedWithOrdinaryScalars = false;
    frame.intersectionRight = null;
    frame.intersectionRightHasExpression = false;
    frame.intersectionRightOnlyNestedClasses = false;
    frame.ignoreUntilClassTerminator = true;
  }

  private void finishOddAmpersandRunAsEmptyUntilClassTerminator(ClassExpressionFrame frame) {
    finishOddAmpersandRunAsCurrentUntilClassTerminator(frame, new CharClassBuilder());
    frame.suppressNegation = true;
    frame.propagateSuppressNegation = true;
  }

  private void startIntersectionRightAfterNormalizedOddRun(
      ClassExpressionFrame frame, CharClassBuilder expression, NormalizedAmpersandRun run) {
    frame.accumulatedClass = expression;
    frame.currentIntersectionOperand = expression;
    frame.currentIntersectionOperandRole = ClassAtomRole.RAW_AMPERSAND_SEPARATOR;
    frame.pendingScalarItems = new CharClassBuilder();
    frame.hasPendingScalarItems = false;
    frame.pendingScalarItemsAfterCurrentOperand = false;
    frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
    frame.rawAmpersandSeparatorActive = false;
    frame.rawAmpersandLeftExpression = null;
    frame.parsingIntersectionRight = true;
    frame.intersectionHasLeftExpression = expression != null && !expression.isEmpty();
    frame.intersectionRightStartedAfterCommentsTrivia = run.skippedTrailingCommentsTrivia();
    frame.intersectionStartedAfterOddAmpersandRun = true;
    frame.intersectionRight = null;
    frame.intersectionRightHasExpression = false;
    frame.intersectionRightOnlyNestedClasses = false;
  }

  private void startIntersectionRightAfterOddRunDelimiter(
      ClassExpressionFrame frame, CharClassBuilder expression, OddAmpersandRunTail tail) {
    pos++;
    frame.accumulatedClass = expression;
    frame.currentIntersectionOperand = expression;
    frame.currentIntersectionOperandRole = ClassAtomRole.RAW_AMPERSAND_SEPARATOR;
    frame.pendingScalarItems = new CharClassBuilder();
    frame.hasPendingScalarItems = false;
    frame.pendingScalarItemsAfterCurrentOperand = false;
    frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
    frame.rawAmpersandSeparatorActive = false;
    frame.rawAmpersandLeftExpression = null;
    frame.parsingIntersectionRight = true;
    frame.intersectionHasLeftExpression = expression != null && !expression.isEmpty();
    frame.intersectionRightStartedAfterCommentsTrivia = tail.skippedCommentsTrivia();
    frame.intersectionStartedAfterOddAmpersandRun = true;
    frame.intersectionRight = null;
    frame.intersectionRightHasExpression = false;
    frame.intersectionRightOnlyNestedClasses = false;
  }

  private void markNormalizedRawAmpersandSeparatorBeforeNextRun(
      ClassExpressionFrame frame, CharClassBuilder rawAmpersandLeftExpression) {
    ClassSyntaxLookahead lookahead = inspectNormalizedClassSyntax(pos);
    if (!lookahead.skippedNormalizedSyntax()
        || lookahead.pos() >= pattern.length()
        || pattern.charAt(lookahead.pos()) != '&') {
      return;
    }
    NormalizedAmpersandRun run = inspectNormalizedAmpersandRun(lookahead.pos());
    if (run.count() < 2) {
      return;
    }
    frame.currentIntersectionOperandRole = ClassAtomRole.RAW_AMPERSAND_SEPARATOR;
    frame.rawAmpersandSeparatorActive = true;
    frame.rawAmpersandSeparatorSkippedCommentsTrivia = lookahead.skippedCommentsTrivia();
    frame.rawAmpersandSeparatorRepeated = false;
    frame.rawAmpersandLeftExpression =
        new CharClassBuilder().addCharClass(rawAmpersandLeftExpression);
    frame.rawAmpersandLeftExpressionOrdinaryScalars = false;
    frame.rawAmpersandLeftExpressionNestedOperand = false;
  }

  private boolean nestedClassFollowedByIntersectionAt(int index) {
    int depth = 0;
    int i = index;
    while (i < pattern.length()) {
      char c = pattern.charAt(i);
      if (c == '\\') {
        if (i + 1 < pattern.length() && pattern.charAt(i + 1) == 'Q') {
          int endQuote = pattern.indexOf("\\E", i + 2);
          i = endQuote >= 0 ? endQuote + 2 : pattern.length();
        } else {
          i += Math.min(2, pattern.length() - i);
        }
        continue;
      }
      if (c == '[') {
        depth++;
      } else if (c == ']') {
        depth--;
        if (depth == 0) {
          i++;
          ClassSyntaxLookahead lookahead = inspectNormalizedClassSyntax(i);
          return lookahead.pos() + 1 < pattern.length()
              && pattern.charAt(lookahead.pos()) == '&'
              && pattern.charAt(lookahead.pos() + 1) == '&';
        }
      }
      i++;
    }
    return false;
  }

  private static void addPendingScalarsBeforeOddAmpersandUnion(
      ClassExpressionFrame frame, CharClassBuilder scalars) {
    if (frame.pendingScalarItemsBeforeOddAmpersandUnion == null) {
      frame.pendingScalarItemsBeforeOddAmpersandUnion = new CharClassBuilder();
    }
    frame.pendingScalarItemsBeforeOddAmpersandUnion.addCharClass(scalars);
  }

  private void rejectInvalidRangeTailAfterOddAmpersandRun() {
    if (pos + 1 >= pattern.length() || pattern.charAt(pos) != '-') {
      return;
    }
    if (startsPredefinedClassAt(pos + 1) || startsPropertyClassAt(pos + 1)) {
      throw new PatternSyntaxException("illegal character range", pattern, pos);
    }
  }

  private boolean startsPredefinedClassAt(int index) {
    if (index + 1 >= pattern.length() || pattern.charAt(index) != '\\') {
      return false;
    }
    return switch (pattern.charAt(index + 1)) {
      case 'd', 'D', 'h', 'H', 's', 'S', 'v', 'V', 'w', 'W' -> true;
      default -> false;
    };
  }

  private boolean startsPropertyClassAt(int index) {
    if (index + 1 >= pattern.length() || pattern.charAt(index) != '\\') {
      return false;
    }
    return pattern.charAt(index + 1) == 'p' || pattern.charAt(index + 1) == 'P';
  }

  private boolean hasInvalidRangeTailAfterRawAmpersand(int ampersandIndex) {
    OddAmpersandRunTail tail = inspectOddAmpersandRunTail(ampersandIndex + 1);
    if (tail.pos() + 1 >= pattern.length() || pattern.charAt(tail.pos()) != '-') {
      return false;
    }
    return startsPredefinedClassAt(tail.pos() + 1) || startsPropertyClassAt(tail.pos() + 1);
  }

  private boolean rawAmpersandStartsRangeAt(int ampersandIndex) {
    if (ampersandIndex + 1 >= pattern.length() || pattern.charAt(ampersandIndex + 1) != '-') {
      return false;
    }
    int saved = pos;
    pos = ampersandIndex + 1;
    boolean hasEndpoint = hasRangeEndpointAfterHyphen();
    pos = saved;
    return hasEndpoint;
  }

  private boolean rawAmpersandRangeAfterAmpersandUsesCurrentOperandOnly(int ampersandIndex) {
    if (ampersandIndex + 1 >= pattern.length() || pattern.charAt(ampersandIndex + 1) != '-') {
      return false;
    }
    int saved = pos;
    pos = ampersandIndex + 1;
    boolean usesCurrentOperandOnly = rawAmpersandRangeTailUsesCurrentOperandOnly();
    pos = saved;
    return usesCurrentOperandOnly;
  }

  private boolean rawAmpersandRangeAtAmpersandHasRawAmpersandEndpointTail(int ampersandIndex) {
    return rawAmpersandRangeEndpointTailAmpersandIndex(ampersandIndex) >= 0;
  }

  private boolean rawAmpersandRangeAtAmpersandPreservesDeferredScalars(int ampersandIndex) {
    int afterEndpoint = rawAmpersandRangeEndpointAmpersandEndIndex(ampersandIndex);
    if (afterEndpoint < 0) {
      return false;
    }
    int index = skipIgnoredCharacterClassSyntaxAt(afterEndpoint);
    if (index < pattern.length() && pattern.charAt(index) == '-') {
      return true;
    }
    return index < pattern.length()
        && pattern.charAt(index) == '&'
        && !rawAmpersandStartsRangeAt(index);
  }

  private int rawAmpersandRangeEndpointAmpersandEndIndex(int ampersandIndex) {
    if (ampersandIndex + 1 >= pattern.length() || pattern.charAt(ampersandIndex + 1) != '-') {
      return -1;
    }
    int index = skipIgnoredCharacterClassSyntaxAt(ampersandIndex + 2);
    if (index >= pattern.length() || pattern.charAt(index) != '&') {
      return -1;
    }
    return index + 1;
  }

  private int rawAmpersandRangeEndpointTailAmpersandIndex(int ampersandIndex) {
    if (ampersandIndex + 1 >= pattern.length() || pattern.charAt(ampersandIndex + 1) != '-') {
      return -1;
    }
    int index = skipIgnoredCharacterClassSyntaxAt(ampersandIndex + 2);
    if (index >= pattern.length() || pattern.charAt(index) != '&') {
      return -1;
    }
    index++;
    index = skipIgnoredCharacterClassSyntaxAt(index);
    if (index < pattern.length() && pattern.charAt(index) == '&') {
      return index;
    }
    return -1;
  }

  private int skipIgnoredCharacterClassSyntaxAt(int index) {
    if ((flags & ParseFlags.COMMENTS) != 0) {
      index = skipCommentsAndWhitespaceAt(index);
    }
    while (startsEmptyQuotedLiteralAt(index)) {
      index += 4;
      if ((flags & ParseFlags.COMMENTS) != 0) {
        index = skipCommentsAndWhitespaceAt(index);
      }
    }
    return index;
  }

  private boolean addCommentsModeHyphenRangeTailIfPresent(CharClassBuilder ccb) {
    if ((flags & ParseFlags.COMMENTS) == 0
        || pos + 2 >= pattern.length()
        || pattern.charAt(pos) != '-'
        || pattern.charAt(pos + 1) != '-'
        || pattern.charAt(pos + 2) == '-'
        || pattern.charAt(pos + 2) == ']'
        || pattern.charAt(pos + 2) == '[') {
      return false;
    }
    if (startsPredefinedClassAt(pos + 2)
        || startsPropertyClassAt(pos + 2)
        || inspectNormalizedAmpersandRun(pos + 2).count() >= 2) {
      throw new PatternSyntaxException("illegal character range", pattern, pos);
    }
    pos += 2;
    RangeEndpoint endpoint = parseCCRangeEndpoint();
    if (endpoint.first < '-') {
      throw new PatternSyntaxException("invalid character class range", pattern, pos);
    }
    addRangeFlags(ccb, '-', endpoint.first, flags | ParseFlags.CLASS_NL);
    for (int r : endpoint.trailingLiterals) {
      addRangeFlags(ccb, r, r, flags | ParseFlags.CLASS_NL);
    }
    return true;
  }

  private boolean addRawAmpersandRangeTailIfPresent(CharClassBuilder ccb) {
    CharClassBuilder rangeTail = parseRawAmpersandRangeTailIfPresent();
    if (rangeTail == null) {
      return false;
    }
    ccb.addCharClass(rangeTail);
    return true;
  }

  private CharClassBuilder parseRawAmpersandRangeTailIfPresent() {
    if (!rawAmpersandRangeTailAtCurrentPosition()) {
      return null;
    }
    CharClassBuilder ccb = new CharClassBuilder();
    pos++;
    if ((flags & ParseFlags.COMMENTS) != 0) {
      skipCommentsAndWhitespace();
    }
    RangeEndpoint endpoint = parseCCRangeEndpoint();
    if (endpoint.first < '&') {
      throw new PatternSyntaxException("invalid character class range", pattern, pos);
    }
    addRangeFlags(ccb, '&', endpoint.first, flags | ParseFlags.CLASS_NL);
    for (int r : endpoint.trailingLiterals) {
      addRangeFlags(ccb, r, r, flags | ParseFlags.CLASS_NL);
    }
    if (pos < pattern.length() && pattern.charAt(pos) == '&' && !rawAmpersandStartsRangeAt(pos)) {
      NormalizedAmpersandRun run = inspectNormalizedAmpersandRun(pos);
      if (run.count() == 1 && !run.skippedTrailingCommentsTrivia()) {
        addRangeFlags(ccb, '&', '&', flags | ParseFlags.CLASS_NL);
        pos = run.pos();
      }
    }
    return ccb;
  }

  private void consumeSingleAmpersandAfterIgnoredRangeEndpoint() {
    if (pos < pattern.length() && pattern.charAt(pos) == '&') {
      NormalizedAmpersandRun run = inspectNormalizedAmpersandRun(pos);
      if (run.count() == 1) {
        pos = run.pos();
      }
    }
  }

  private CharClassBuilder parseOddAmpersandRangeTailLiteralsIfPresent() {
    if (!rawAmpersandRangeTailAtCurrentPosition()) {
      return null;
    }
    CharClassBuilder ccb = new CharClassBuilder();
    pos++;
    if ((flags & ParseFlags.COMMENTS) != 0) {
      skipCommentsAndWhitespace();
    }
    RangeEndpoint endpoint = parseCCRangeEndpoint();
    addRangeFlags(ccb, '-', '-', flags | ParseFlags.CLASS_NL);
    addRangeFlags(ccb, endpoint.first, endpoint.first, flags | ParseFlags.CLASS_NL);
    for (int r : endpoint.trailingLiterals) {
      addRangeFlags(ccb, r, r, flags | ParseFlags.CLASS_NL);
    }
    if (pos < pattern.length() && pattern.charAt(pos) == '&' && !rawAmpersandStartsRangeAt(pos)) {
      NormalizedAmpersandRun run = inspectNormalizedAmpersandRun(pos);
      if (run.count() == 1 && !run.skippedTrailingCommentsTrivia()) {
        addRangeFlags(ccb, '&', '&', flags | ParseFlags.CLASS_NL);
        pos = run.pos();
      }
    }
    return ccb;
  }

  private boolean rawAmpersandRangeTailAtCurrentPosition() {
    return pos < pattern.length() && pattern.charAt(pos) == '-' && hasRangeEndpointAfterHyphen();
  }

  private boolean rawAmpersandRangeTailEndsAtLiteralAmpersand() {
    if (pos >= pattern.length() || pattern.charAt(pos) != '-') {
      return false;
    }
    int index = pos + 1;
    if ((flags & ParseFlags.COMMENTS) != 0) {
      index = skipCommentsAndWhitespaceAt(index);
    }
    while (startsEmptyQuotedLiteralAt(index)) {
      index += 4;
      if ((flags & ParseFlags.COMMENTS) != 0) {
        index = skipCommentsAndWhitespaceAt(index);
      }
    }
    if (index >= pattern.length() || pattern.charAt(index) != '&') {
      return false;
    }
    index++;
    if ((flags & ParseFlags.COMMENTS) != 0) {
      index = skipCommentsAndWhitespaceAt(index);
    }
    return index < pattern.length()
        && pattern.charAt(index) == '&'
        && inspectNormalizedAmpersandRun(index).count() == 1;
  }

  private boolean rawAmpersandRangeTailEndpointIsAmpersand() {
    if (pos >= pattern.length() || pattern.charAt(pos) != '-') {
      return false;
    }
    int index = pos + 1;
    if ((flags & ParseFlags.COMMENTS) != 0) {
      index = skipCommentsAndWhitespaceAt(index);
    }
    while (startsEmptyQuotedLiteralAt(index)) {
      index += 4;
      if ((flags & ParseFlags.COMMENTS) != 0) {
        index = skipCommentsAndWhitespaceAt(index);
      }
    }
    return index < pattern.length() && pattern.charAt(index) == '&';
  }

  private boolean rawAmpersandRangeTailUsesCurrentOperandOnly() {
    if (pos >= pattern.length() || pattern.charAt(pos) != '-') {
      return false;
    }
    int index = pos + 1;
    if ((flags & ParseFlags.COMMENTS) != 0) {
      index = skipCommentsAndWhitespaceAt(index);
    }
    if (index >= pattern.length() || pattern.charAt(index) != '&') {
      return index < pattern.length();
    }
    index++;
    if ((flags & ParseFlags.COMMENTS) != 0) {
      index = skipCommentsAndWhitespaceAt(index);
    }
    if (index < pattern.length() && pattern.charAt(index) == '&') {
      return false;
    }
    return index < pattern.length() && pattern.charAt(index) == ']';
  }

  private CharClassBuilder snapshotOddAmpersandUnionExpression(ClassExpressionFrame frame) {
    return snapshotOddAmpersandUnionExpression(frame, true);
  }

  private CharClassBuilder snapshotOddAmpersandUnionExpression(
      ClassExpressionFrame frame, boolean includeNestedAmpersandLiteral) {
    if (frame.currentIntersectionOperand == null) {
      return snapshotPendingExpression(frame);
    }
    CharClassBuilder snapshot =
        new CharClassBuilder().addCharClass(frame.currentIntersectionOperand);
    if (includeNestedAmpersandLiteral
        && frame.currentIntersectionOperandNestedClass
        && frame.accumulatedClass != null
        && frame.accumulatedClass.contains('&')
        && !snapshot.contains('&')) {
      addRangeFlags(snapshot, '&', '&', flags | ParseFlags.CLASS_NL);
    }
    if (frame.hasPendingScalarItems && !frame.pendingScalarItemsAfterCurrentOperand) {
      snapshot.addCharClass(frame.pendingScalarItems);
    }
    return snapshot;
  }

  private boolean accumulatedClassAlreadyContainsLiteralAmpersand(
      ClassExpressionFrame frame, CharClassBuilder expression) {
    return frame.accumulatedClass != null
        && frame.accumulatedClass.contains('&')
        && !expression.contains('&');
  }

  private CharClassBuilder snapshotZeroWidthAmpersandUnionExpression(ClassExpressionFrame frame) {
    CharClassBuilder expression = snapshotOddAmpersandUnionExpression(frame);
    if (!frame.intersectionRightHasExpression || !frame.intersectionRightOnlyNestedClasses) {
      return expression;
    }
    if (rawAmpersandStartsRangeAt(pos)
        && rawAmpersandRangeAtAmpersandHasRawAmpersandEndpointTail(pos)) {
      return nestedRightBeforeTrailingAmpersandExpression(frame, true, true);
    }
    expression = snapshotPendingExpression(frame);
    if (expression == null) {
      return new CharClassBuilder().addCharClass(frame.intersectionRight);
    }
    expression.intersect(frame.intersectionRight);
    return expression;
  }

  private OddAmpersandRunTail inspectOddAmpersandRunTail(int index) {
    ClassSyntaxLookahead lookahead = inspectNormalizedClassSyntax(index);
    return new OddAmpersandRunTail(
        lookahead.pos(), lookahead.skippedZeroWidthSyntax(), lookahead.skippedCommentsTrivia());
  }

  private record OddAmpersandRunTail(
      int pos, boolean skippedZeroWidthSyntax, boolean skippedCommentsTrivia) {
    boolean skippedNormalizedSyntax() {
      return skippedZeroWidthSyntax || skippedCommentsTrivia;
    }
  }

  private ClassSyntaxLookahead inspectNormalizedClassSyntax(int index) {
    boolean skippedZeroWidthSyntax = false;
    boolean skippedCommentsTrivia = false;
    int before;
    do {
      before = index;
      if ((flags & ParseFlags.COMMENTS) != 0) {
        int beforeCommentsTrivia = index;
        index = skipCommentsAndWhitespaceAt(index);
        skippedCommentsTrivia |= index != beforeCommentsTrivia;
      }
      while (startsEmptyQuotedLiteralAt(index)) {
        skippedZeroWidthSyntax = true;
        index += 4;
      }
    } while (index != before);
    return new ClassSyntaxLookahead(index, skippedZeroWidthSyntax, skippedCommentsTrivia);
  }

  private NormalizedAmpersandRun inspectNormalizedAmpersandRun(int index) {
    ClassSyntaxLookahead lookahead = inspectNormalizedClassSyntax(index);
    int current = lookahead.pos();
    int first = current;
    int count = 0;
    boolean skippedZeroWidthSyntax = lookahead.skippedZeroWidthSyntax();
    boolean skippedCommentsTrivia = lookahead.skippedCommentsTrivia();
    boolean skippedInterAmpersandCommentsTrivia = false;
    boolean skippedTrailingCommentsTrivia = false;
    while (current < pattern.length() && pattern.charAt(current) == '&') {
      count++;
      lookahead = inspectNormalizedClassSyntax(current + 1);
      skippedZeroWidthSyntax |= lookahead.skippedZeroWidthSyntax();
      skippedCommentsTrivia |= lookahead.skippedCommentsTrivia();
      skippedTrailingCommentsTrivia = lookahead.skippedCommentsTrivia();
      current = lookahead.pos();
      if (current < pattern.length() && pattern.charAt(current) == '&') {
        skippedInterAmpersandCommentsTrivia |= lookahead.skippedCommentsTrivia();
      }
    }
    return new NormalizedAmpersandRun(
        first,
        count,
        current,
        skippedZeroWidthSyntax,
        skippedCommentsTrivia,
        skippedInterAmpersandCommentsTrivia,
        skippedTrailingCommentsTrivia);
  }

  private void rejectEvenAmpersandRunBeforeCommentsModeClassTerminator(
      ClassExpressionFrame frame, NormalizedAmpersandRun run) {
    if (run.skippedTrailingCommentsTrivia()
        && run.pos() < pattern.length()
        && !normalizedRunEndsBeforeLiteralCloseBracket(frame, run)
        && frame.shouldCompleteAt(pattern.charAt(run.pos()))) {
      throw new PatternSyntaxException("bad class syntax", pattern, run.pos());
    }
  }

  private boolean normalizedRunEndsBeforeLiteralCloseBracket(
      ClassExpressionFrame frame, NormalizedAmpersandRun run) {
    return run.skippedTrailingCommentsTrivia()
        && canUseCloseBracketAsClassLiteral(frame, run.pos());
  }

  private boolean commentsModeRangeTailBeforeIntersectionAtCurrentPosition() {
    return (flags & ParseFlags.COMMENTS) != 0
        && pos + 1 < pattern.length()
        && pattern.charAt(pos) == '-'
        && inspectNormalizedAmpersandRun(pos + 1).count() >= 2;
  }

  private boolean commentsTailEndsBeforeLiteralCloseBracket(
      ClassExpressionFrame frame, OddAmpersandRunTail tail) {
    return tail.skippedCommentsTrivia() && canUseCloseBracketAsClassLiteral(frame, tail.pos());
  }

  private boolean canUseCloseBracketAsClassLiteral(ClassExpressionFrame frame, int closeIndex) {
    return (frame.bracketed || frame.continuation == ClassContinuation.INTERSECTION_RIGHT)
        && closeIndex + 1 < pattern.length()
        && pattern.charAt(closeIndex) == ']'
        && pattern.charAt(closeIndex + 1) == ']';
  }

  private record ClassSyntaxLookahead(
      int pos, boolean skippedZeroWidthSyntax, boolean skippedCommentsTrivia) {
    boolean skippedNormalizedSyntax() {
      return skippedZeroWidthSyntax || skippedCommentsTrivia;
    }
  }

  private record NormalizedAmpersandRun(
      int first,
      int count,
      int pos,
      boolean skippedZeroWidthSyntax,
      boolean skippedCommentsTrivia,
      boolean skippedInterAmpersandCommentsTrivia,
      boolean skippedTrailingCommentsTrivia) {
    boolean skippedNormalizedSyntax() {
      return skippedZeroWidthSyntax || skippedCommentsTrivia;
    }
  }

  private void finishClassIntersection(ClassExpressionFrame frame) {
    boolean emptyRight = frame.intersectionRight == null;
    boolean hadPendingScalarItems = frame.hasPendingScalarItems;
    boolean pendingScalarsAfterCurrentOperand = frame.pendingScalarItemsAfterCurrentOperand;
    ClassAtomRole pendingRole = frame.pendingScalarRole;
    if (emptyRight
        && frame.currentIntersectionOperand != null
        && frame.hasPendingScalarItems
        && !frame.pendingScalarItemsAfterCurrentOperand
        && frame.pendingScalarRole == ClassAtomRole.ORDINARY_SCALAR) {
      frame.pendingScalarItemsBeforeEmptyIntersection =
          new CharClassBuilder().addCharClass(frame.pendingScalarItems);
    }
    if (frame.hasPendingScalarItems) {
      if (frame.accumulatedClass == null) {
        frame.accumulatedClass = frame.pendingScalarItems;
        frame.currentIntersectionOperand = frame.pendingScalarItems;
        frame.currentIntersectionOperandRole = pendingRole;
      } else {
        frame.accumulatedClass.addCharClass(frame.pendingScalarItems);
        if (pendingScalarsAfterCurrentOperand
            && pendingRole == ClassAtomRole.INTERSECTION_OPERAND) {
          frame.currentIntersectionOperand = frame.pendingScalarItems;
          frame.currentIntersectionOperandRole = pendingRole;
        }
        if (pendingScalarsAfterCurrentOperand
            && pendingRole == ClassAtomRole.RAW_AMPERSAND_SEPARATOR) {
          frame.currentIntersectionOperand = frame.accumulatedClass;
          frame.currentIntersectionOperandRole = pendingRole;
        }
      }
      frame.pendingScalarItems = new CharClassBuilder();
      frame.hasPendingScalarItems = false;
      frame.pendingScalarItemsAfterCurrentOperand = false;
      frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
      frame.rawAmpersandSeparatorActive = false;
      frame.rawAmpersandLeftExpression = null;
    }
    if (frame.intersectionRight != null) {
      frame.currentIntersectionOperand = frame.intersectionRight;
      frame.currentIntersectionOperandRole = ClassAtomRole.INTERSECTION_OPERAND;
      frame.pendingScalarItemsBeforeEmptyIntersection = null;
    }
    if (frame.accumulatedClass == null) {
      if (frame.intersectionRight == null) {
        throw new PatternSyntaxException("bad class syntax", pattern, pos);
      }
      frame.accumulatedClass = frame.intersectionRight;
    } else {
      if (emptyRight
          && hadPendingScalarItems
          && pendingScalarsAfterCurrentOperand
          && pendingRole == ClassAtomRole.ORDINARY_SCALAR) {
        throw new PatternSyntaxException("bad class syntax", pattern, pos);
      }
      if (frame.currentIntersectionOperand == null) {
        throw new PatternSyntaxException("bad class syntax", pattern, pos);
      }
      frame.accumulatedClass.intersect(frame.currentIntersectionOperand);
    }
  }

  private CharClassBuilder completeClassExpression(ClassExpressionFrame frame) {
    if (frame.bracketed) {
      pos++;
    }
    CharClassBuilder result;
    if (frame.accumulatedClass == null) {
      result = frame.pendingScalarItems;
    } else {
      result = frame.accumulatedClass;
      if (frame.hasPendingScalarItems) {
        result.addCharClass(frame.pendingScalarItems);
      }
    }
    if (frame.negated && !frame.suppressNegation) {
      if ((flags & ParseFlags.CLASS_NL) == 0 || (flags & ParseFlags.NEVER_NL) != 0) {
        result.addRune('\n');
      }
      result.negate();
    }
    return result;
  }

  private void addCompletedClassExpression(
      ClassExpressionFrame parent, ClassExpressionFrame child, CharClassBuilder completed) {
    switch (child.continuation) {
      case ROOT -> throw new PatternSyntaxException("internal error", pattern, pos);
      case UNION -> {
        if (parent.rawAmpersandSeparatorActive) {
          foldRawAmpersandSeparatorBeforeNestedClass(parent, completed);
          return;
        }
        boolean currentOperandIsOnlyOperand =
            parent.accumulatedClass == null && !parent.hasPendingScalarItems;
        parent.currentIntersectionOperand = completed;
        parent.currentIntersectionOperandRole = ClassAtomRole.INTERSECTION_OPERAND;
        parent.currentIntersectionOperandNestedClass = child.bracketed;
        parent.currentIntersectionOperandIsOnlyOperand = currentOperandIsOnlyOperand;
        parent.accumulatedClass =
            unionClass(parent.accumulatedClass, parent.currentIntersectionOperand);
        if (parent.hasPendingScalarItems) {
          parent.pendingScalarItemsAfterCurrentOperand = false;
        }
      }
      case INTERSECTION_RIGHT -> {
        if (child.propagateSuppressNegation) {
          parent.suppressNegation = true;
        }
        parent.intersectionRight = unionClass(parent.intersectionRight, completed);
        parent.intersectionRightOnlyNestedClasses =
            !parent.intersectionRightHasExpression
                ? child.bracketed
                : parent.intersectionRightOnlyNestedClasses && child.bracketed;
        parent.intersectionRightHasExpression = true;
      }
    }
  }

  private void foldRawAmpersandSeparatorBeforeNestedClass(
      ClassExpressionFrame frame, CharClassBuilder completed) {
    CharClassBuilder expression = new CharClassBuilder();
    if (frame.rawAmpersandSeparatorRepeated) {
      expression.addCharClass(completed);
      if (!frame.rawAmpersandLeftExpression.isEmpty()) {
        expression.intersect(frame.rawAmpersandLeftExpression);
      }
    }
    frame.accumulatedClass = unionClass(frame.accumulatedClass, expression);
    frame.currentIntersectionOperand = frame.accumulatedClass;
    frame.currentIntersectionOperandRole = ClassAtomRole.INTERSECTION_OPERAND;
    frame.pendingScalarItems = new CharClassBuilder();
    frame.hasPendingScalarItems = false;
    frame.pendingScalarItemsAfterCurrentOperand = false;
    frame.pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
    frame.rawAmpersandSeparatorActive = false;
    frame.rawAmpersandSeparatorSkippedCommentsTrivia = false;
    frame.rawAmpersandSeparatorRepeated = false;
    frame.rawAmpersandLeftExpression = null;
  }

  private ClassNormalization skipClassTriviaAndEmptySyntax() {
    ClassSyntaxLookahead lookahead = inspectNormalizedClassSyntax(pos);
    pos = lookahead.pos();
    return new ClassNormalization(
        lookahead.skippedZeroWidthSyntax(), lookahead.skippedCommentsTrivia());
  }

  private record ClassNormalization(
      boolean skippedZeroWidthSyntax, boolean skippedCommentsTrivia) {}

  private boolean shouldDiscardCommentsModeRhsTail() {
    if ((flags & ParseFlags.COMMENTS) == 0) {
      return false;
    }
    OddAmpersandRunTail tail = inspectOddAmpersandRunTail(pos + 1);
    return tail.skippedCommentsTrivia()
        && tail.pos() < pattern.length()
        && pattern.charAt(tail.pos()) == '[';
  }

  private int skipCommentsAndWhitespaceAt(int index) {
    while (index < pattern.length()) {
      int cp = pattern.codePointAt(index);
      if (isCommentsWhitespace(cp)) {
        index += Character.charCount(cp);
        continue;
      }
      if (cp == '#') {
        index += Character.charCount(cp);
        while (index < pattern.length()) {
          int commentCp = pattern.codePointAt(index);
          index += Character.charCount(commentCp);
          if (commentCp == '\n' || commentCp == '\r') {
            break;
          }
        }
        continue;
      }
      break;
    }
    return index;
  }

  private void skipIgnoredClassItem() {
    if (pos < pattern.length() && pattern.charAt(pos) == '[') {
      skipIgnoredBracketedClass();
      return;
    }
    parseClassAtomOrRange();
  }

  private void skipIgnoredBracketedClass() {
    int depth = 0;
    while (pos < pattern.length()) {
      if ((flags & ParseFlags.COMMENTS) != 0) {
        skipCommentsAndWhitespace();
        if (pos >= pattern.length()) {
          break;
        }
      }
      if (startsQuotedLiteral()) {
        parseQuotedLiteralSequence();
        continue;
      }
      char c = pattern.charAt(pos);
      if (c == '\\') {
        parseEscape();
        continue;
      }
      pos++;
      if (c == '[') {
        depth++;
      } else if (c == ']') {
        depth--;
        if (depth == 0) {
          return;
        }
      }
    }
    throw new PatternSyntaxException("missing closing ]", pattern, pos);
  }

  private CharClassBuilder unionClass(CharClassBuilder left, CharClassBuilder right) {
    if (left == null) {
      return new CharClassBuilder().addCharClass(right);
    }
    left.addCharClass(right);
    return left;
  }

  private enum ClassContinuation {
    ROOT,
    UNION,
    INTERSECTION_RIGHT
  }

  private enum ClassAtomRole {
    ORDINARY_SCALAR,
    RAW_AMPERSAND_SEPARATOR,
    INTERSECTION_OPERAND;

    static ClassAtomRole merge(ClassAtomRole left, ClassAtomRole right) {
      if (left == INTERSECTION_OPERAND || right == INTERSECTION_OPERAND) {
        return INTERSECTION_OPERAND;
      }
      if (left == RAW_AMPERSAND_SEPARATOR || right == RAW_AMPERSAND_SEPARATOR) {
        return RAW_AMPERSAND_SEPARATOR;
      }
      if (left == ORDINARY_SCALAR && right == ORDINARY_SCALAR) {
        return ORDINARY_SCALAR;
      }
      return ORDINARY_SCALAR;
    }
  }

  private final class ClassExpressionFrame {
    final boolean bracketed;
    final int classStart;
    final boolean negated;
    final ClassContinuation continuation;
    CharClassBuilder accumulatedClass;
    CharClassBuilder currentIntersectionOperand;
    ClassAtomRole currentIntersectionOperandRole = ClassAtomRole.ORDINARY_SCALAR;
    boolean currentIntersectionOperandNestedClass;
    boolean currentIntersectionOperandIsOnlyOperand;
    CharClassBuilder pendingScalarItems = new CharClassBuilder();
    boolean hasPendingScalarItems;
    boolean pendingScalarItemsAfterCurrentOperand;
    ClassAtomRole pendingScalarRole = ClassAtomRole.ORDINARY_SCALAR;
    boolean parsingIntersectionRight;
    boolean intersectionHasLeftExpression;
    boolean intersectionRightStartedAfterCommentsTrivia;
    boolean intersectionStartedAfterOddAmpersandRun;
    boolean oddAmpersandUnionStartedWithOrdinaryScalars;
    boolean ignoreUntilClassTerminator;
    boolean suppressNegation;
    boolean propagateSuppressNegation;
    boolean rawAmpersandSeparatorActive;
    boolean rawAmpersandSeparatorSkippedCommentsTrivia;
    boolean rawAmpersandSeparatorRepeated;
    CharClassBuilder rawAmpersandLeftExpression;
    boolean rawAmpersandLeftExpressionOrdinaryScalars;
    boolean rawAmpersandLeftExpressionNestedOperand;
    CharClassBuilder commentsOddRunCurrentOperandForRhs;
    CharClassBuilder pendingScalarItemsBeforeEmptyIntersection;
    CharClassBuilder pendingScalarItemsBeforeOddAmpersandUnion;
    boolean dropPendingRawAmpersandSeparatorBeforeNextRangeTail;
    CharClassBuilder intersectionRight;
    boolean intersectionRightHasExpression;
    boolean intersectionRightOnlyNestedClasses;

    ClassExpressionFrame(boolean bracketed, ClassContinuation continuation) {
      if (bracketed && (pos >= pattern.length() || pattern.charAt(pos) != '[')) {
        throw new PatternSyntaxException("internal error", pattern, pos);
      }
      this.bracketed = bracketed;
      this.classStart = pos;
      this.continuation = continuation;
      if (bracketed) {
        pos++;
        if (pos < pattern.length() && pattern.charAt(pos) == '^') {
          pos++;
          negated = true;
          return;
        }
      }
      negated = false;
    }

    boolean shouldCompleteAt(char c) {
      if (accumulatedClass == null && !hasPendingScalarItems) {
        return false;
      }
      return bracketed ? c == ']' : c == ']' || c == '&';
    }
  }

  private static final class ParsedClassAtom {
    final CharClassBuilder ccb;
    final ClassAtomRole role;

    ParsedClassAtom(CharClassBuilder ccb, ClassAtomRole role) {
      this.ccb = ccb;
      this.role = role;
    }
  }

  private ParsedClassAtom parseClassAtomOrRange() {
    lastClassAtomRawAmpersandRangeTail = false;
    CharClassBuilder ccb = new CharClassBuilder();
    // Look for Unicode character group like \p{Han}
    if (pos + 2 < pattern.length()
        && pattern.charAt(pos) == '\\'
        && (pattern.charAt(pos + 1) == 'p' || pattern.charAt(pos + 1) == 'P')) {
      int result = parseUnicodeGroup(ccb);
      if (result == PARSE_OK) {
        lastClassAtomSkippedCommentsTrivia = false;
        return new ParsedClassAtom(ccb, ClassAtomRole.INTERSECTION_OPERAND);
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
        lastClassAtomSkippedCommentsTrivia = false;
        return new ParsedClassAtom(ccb, ClassAtomRole.INTERSECTION_OPERAND);
      }
      pos = saved;
    }

    if (startsQuotedLiteral()) {
      ClassAtomRole role = addQuotedLiteralClassItem(ccb);
      return new ParsedClassAtom(ccb, role);
    }

    boolean rawSource = pattern.charAt(pos) != '\\';
    int scalar = parseCCCharacter();
    ClassAtomRole role = addScalarClassItem(ccb, scalar, rawSource);
    return new ParsedClassAtom(ccb, role);
  }

  private ClassAtomRole addQuotedLiteralClassItem(CharClassBuilder ccb) {
    int[] literals = parseQuotedLiteralSequence();
    if (literals.length == 0) {
      lastClassAtomSkippedCommentsTrivia = false;
      return ClassAtomRole.ORDINARY_SCALAR;
    }
    for (int i = 0; i + 1 < literals.length; i++) {
      addRangeFlags(ccb, literals[i], literals[i], flags | ParseFlags.CLASS_NL);
    }
    return addScalarClassItem(ccb, literals[literals.length - 1], false);
  }

  private ClassAtomRole addScalarClassItem(CharClassBuilder ccb, int lo, boolean rawSource) {
    int hi = lo;
    boolean skippedNonItemSyntax = false;
    boolean skippedCommentsTrivia = false;
    // In comments mode, skip whitespace before checking for '-'.
    if ((flags & ParseFlags.COMMENTS) != 0) {
      int beforeTrivia = pos;
      skipCommentsAndWhitespace();
      skippedNonItemSyntax = pos != beforeTrivia;
      skippedCommentsTrivia = pos != beforeTrivia;
    }
    while (startsEmptyQuotedLiteralAt(pos)) {
      skippedNonItemSyntax = true;
      pos += 4;
      if ((flags & ParseFlags.COMMENTS) != 0) {
        int beforeTrivia = pos;
        skipCommentsAndWhitespace();
        boolean skippedTrivia = pos != beforeTrivia;
        skippedNonItemSyntax |= skippedTrivia;
        skippedCommentsTrivia |= skippedTrivia;
      }
    }
    if (pos < pattern.length() && pattern.charAt(pos) == '-') {
      if (!(rawSource && lo == '&' && skippedNonItemSyntax) && hasRangeEndpointAfterHyphen()) {
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
        lastClassAtomSkippedCommentsTrivia = skippedCommentsTrivia;
        lastClassAtomRawAmpersandRangeTail = rawSource && lo == '&';
        return ClassAtomRole.INTERSECTION_OPERAND;
      }
    }

    lastClassAtomSkippedCommentsTrivia = skippedCommentsTrivia;
    if (rawSource && lo == '&' && skippedNonItemSyntax) {
      if (skippedCommentsTrivia || (pos < pattern.length() && pattern.charAt(pos) != ']')) {
        return ClassAtomRole.RAW_AMPERSAND_SEPARATOR;
      }
    }
    addRangeFlags(ccb, lo, hi, flags | ParseFlags.CLASS_NL);
    if (lo > 0xFF) {
      return ClassAtomRole.INTERSECTION_OPERAND;
    }
    return ClassAtomRole.ORDINARY_SCALAR;
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
    skipEmptyQuotedLiterals();
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
    // Use Unicode-aware tables when UNICODE_CHAR_CLASS is active.
    int[][] table =
        (flags & ParseFlags.UNICODE_CHAR_CLASS) != 0
            ? UnicodeTables.unicodePerlGroups().get(posName)
            : UnicodeTables.PERL_GROUPS.get(posName);
    if (table == null) return null;

    CharClassBuilder ccb = new CharClassBuilder();
    if (negate) {
      addGroupNegated(ccb, table);
    } else {
      addGroupPositive(ccb, table);
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

    int[][] table = lookupUnicodeGroup(name, (flags & ParseFlags.UNICODE_CHAR_CLASS) != 0);
    if (table == null) {
      throw new PatternSyntaxException("invalid Unicode group: " + name, pattern, seqStart);
    }

    if (sign > 0) {
      addGroupPositive(ccb, table);
    } else {
      addGroupNegated(ccb, table);
    }
    return PARSE_OK;
  }

  private static int[][] lookupUnicodeGroup(String name, boolean unicodeCharacterClass) {
    int[][] table = JavaCharacterClasses.lookup(name);
    if (table != null) {
      return table;
    }
    table =
        unicodeCharacterClass
            ? UnicodeTables.unicodePosixPropertyGroups().get(name)
            : UnicodeTables.POSIX_PROPERTY_GROUPS.get(name);
    if (table != null) {
      return table;
    }

    // Keyword forms: script=, sc=, block=, blk=, general_category=, gc=.
    int eq = name.indexOf('=');
    if (eq >= 0) {
      String key = name.substring(0, eq);
      String value = name.substring(eq + 1);
      return lookupKeywordProperty(key, value);
    }

    // "Is" prefix: try script/category (case-insensitive), then binary property.
    // The prefix is case-sensitive ("Is" only, not "is" or "IS").
    if (name.startsWith("Is") && name.length() > 2) {
      String stripped = name.substring(2);
      table = UnicodeProperties.lookupScriptOrCategory(stripped);
      if (table != null) {
        return table;
      }
      return UnicodeProperties.lookupBinaryProperty(stripped);
    }

    // "In" prefix: Unicode block lookup.
    if (name.startsWith("In") && name.length() > 2) {
      return UnicodeProperties.lookupBlock(name.substring(2));
    }

    // Bare Unicode properties are valid only for general categories such as "L" or "Lu".
    // JDK Pattern requires scripts to use Is/script=/sc= and blocks to use In/block=/blk=.
    return UnicodeProperties.lookupCategory(name);
  }

  private static int[][] lookupKeywordProperty(String key, String value) {
    // Keywords are case-insensitive per JDK behavior; remove underscores/hyphens/spaces.
    String normalizedKey =
        key.toUpperCase(java.util.Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
    return switch (normalizedKey) {
      case "SCRIPT", "SC" -> UnicodeProperties.lookupScript(value);
      case "BLOCK", "BLK" -> UnicodeProperties.lookupBlock(value);
      case "GENERALCATEGORY", "GC" -> UnicodeProperties.lookupCategory(value);
      default -> null;
    };
  }

  // ---- Group add helpers ----

  private void addGroupPositive(CharClassBuilder ccb, int[][] table) {
    if ((flags & ParseFlags.FOLD_CASE) == 0
        && (flags & ParseFlags.CLASS_NL) != 0
        && (flags & ParseFlags.NEVER_NL) == 0) {
      ccb.addTable(table);
      return;
    }
    for (int[] row : table) {
      addRangeFlags(ccb, row[0], row[1], flags);
    }
  }

  private void addGroupNegated(CharClassBuilder ccb, int[][] table) {
    if ((flags & ParseFlags.FOLD_CASE) != 0) {
      // Build the positive set with folding, then negate, then merge.
      CharClassBuilder ccb1 = new CharClassBuilder();
      addGroupPositive(ccb1, table);
      boolean cutnl = (flags & ParseFlags.CLASS_NL) == 0 || (flags & ParseFlags.NEVER_NL) != 0;
      if (cutnl) {
        ccb1.addRune('\n');
      }
      ccb1.negate();
      ccb.addCharClass(ccb1);
      return;
    }
    int next = 0;
    for (int[] row : table) {
      if (next < row[0]) {
        addRangeFlags(ccb, next, row[0] - 1, flags);
      }
      next = row[1] + 1;
    }
    if (next <= Utils.MAX_RUNE) {
      addRangeFlags(ccb, next, Utils.MAX_RUNE, flags);
    }
  }

  /** Add a range to the character class, but exclude newline if asked. Also handle case folding. */
  private void addRangeFlags(CharClassBuilder ccb, int lo, int hi, int parseFlags) {
    // Take out \n if the flags say so.
    boolean cutnl =
        (parseFlags & ParseFlags.CLASS_NL) == 0 || (parseFlags & ParseFlags.NEVER_NL) != 0;
    if (cutnl && lo <= '\n' && '\n' <= hi) {
      if (lo < '\n') {
        addRangeFlags(ccb, lo, '\n' - 1, parseFlags);
      }
      if (hi > '\n') {
        addRangeFlags(ccb, '\n' + 1, hi, parseFlags);
      }
      return;
    }

    // If folding case, add fold-equivalent characters too.
    if ((parseFlags & ParseFlags.FOLD_CASE) != 0) {
      if ((parseFlags & ParseFlags.UNICODE_CASE) == 0) {
        addAsciiFoldedRange(ccb, lo, hi);
        return;
      }
      addFoldedRange(ccb, lo, hi, 0);
    } else {
      ccb.addRange(lo, hi);
    }
  }

  // ---- Case folding ----

  private static int asciiFoldRune(int r) {
    if ('A' <= r && r <= 'Z') {
      return r + ('a' - 'A');
    }
    if ('a' <= r && r <= 'z') {
      return r;
    }
    return r;
  }

  private static void addAsciiFoldedRange(CharClassBuilder ccb, int lo, int hi) {
    ccb.addRange(lo, hi);
    int upperLo = Math.max(lo, 'A');
    int upperHi = Math.min(hi, 'Z');
    if (upperLo <= upperHi) {
      ccb.addRange(upperLo + ('a' - 'A'), upperHi + ('a' - 'A'));
    }
    int lowerLo = Math.max(lo, 'a');
    int lowerHi = Math.min(hi, 'z');
    if (lowerLo <= lowerHi) {
      ccb.addRange(lowerLo - ('a' - 'A'), lowerHi - ('a' - 'A'));
    }
  }

  /**
   * Look up the case fold entry containing r. Returns the index into CASE_FOLD, or -1 if none
   * contains r. If r is between entries, returns the index of the next entry after r.
   */
  private static final int CASE_FOLD_NOT_FOUND = Integer.MIN_VALUE;

  private static int lookupCaseFold(int r) {
    int[][] cf = UnicodeTables.CASE_FOLD;
    int lo = 0;
    int hi = cf.length - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (cf[mid][0] <= r && r <= cf[mid][1]) {
        return mid;
      }
      if (r < cf[mid][0]) {
        hi = mid - 1;
      } else {
        lo = mid + 1;
      }
    }
    // r is not in any entry. lo points at the first entry after r.
    if (lo < cf.length) {
      return -(lo + 1); // negative to indicate "not found, but next is at lo"
    }
    return CASE_FOLD_NOT_FOUND;
  }

  /** Returns the result of applying the fold to rune r given the fold entry at index idx. */
  private static int applyFold(int[] entry, int r) {
    int delta = entry[2];
    if (delta == UnicodeTables.EVEN_ODD_SKIP) {
      if ((r - entry[0]) % 2 != 0) return r;
      // fall through to EVEN_ODD
      delta = UnicodeTables.EVEN_ODD;
    }
    if (delta == UnicodeTables.ODD_EVEN_SKIP) {
      if ((r - entry[0]) % 2 != 0) return r;
      // fall through to ODD_EVEN
      delta = UnicodeTables.ODD_EVEN;
    }
    if (delta == UnicodeTables.EVEN_ODD) {
      return (r % 2 == 0) ? r + 1 : r - 1;
    }
    if (delta == UnicodeTables.ODD_EVEN) {
      return (r % 2 == 1) ? r + 1 : r - 1;
    }
    return r + delta;
  }

  /** Returns the next rune in r's folding cycle. */
  static int cycleFoldRune(int r) {
    int[][] cf = UnicodeTables.CASE_FOLD;
    int idx = lookupCaseFold(r);
    if (idx < 0) {
      return r; // no fold for this rune
    }
    return applyFold(cf[idx], r);
  }

  /** Add lo-hi to the class, along with their fold-equivalent characters. */
  private static void addFoldedRange(CharClassBuilder ccb, int lo, int hi, int depth) {
    if (depth > 10) return;

    // Track whether we actually added something new.
    int oldRunes = ccb.numRunes();
    ccb.addRange(lo, hi);
    if (ccb.numRunes() == oldRunes && depth > 0) {
      // lo-hi was already there; assume fold-equivalents are too.
      return;
    }

    int[][] cf = UnicodeTables.CASE_FOLD;
    int r = lo;
    while (r <= hi) {
      int idx = lookupCaseFold(r);
      if (idx < 0) {
        if (idx == CASE_FOLD_NOT_FOUND) break; // no more entries
        // -(lo+1) means entry at that index is above r
        int nextIdx = -(idx + 1);
        if (nextIdx >= cf.length) break;
        r = cf[nextIdx][0];
        continue;
      }
      if (r < cf[idx][0]) {
        r = cf[idx][0];
        continue;
      }

      // Add in the result of folding the range r to min(hi, cf[idx][1])
      int lo1 = r;
      int hi1 = Math.min(hi, cf[idx][1]);
      int delta = cf[idx][2];
      int flo, fhi;
      if (delta == UnicodeTables.EVEN_ODD || delta == UnicodeTables.EVEN_ODD_SKIP) {
        flo = (lo1 % 2 == 1) ? lo1 - 1 : lo1;
        fhi = (hi1 % 2 == 0) ? hi1 + 1 : hi1;
      } else if (delta == UnicodeTables.ODD_EVEN || delta == UnicodeTables.ODD_EVEN_SKIP) {
        flo = (lo1 % 2 == 0) ? lo1 - 1 : lo1;
        fhi = (hi1 % 2 == 1) ? hi1 + 1 : hi1;
      } else {
        flo = lo1 + delta;
        fhi = hi1 + delta;
      }
      addFoldedRange(ccb, flo, fhi, depth + 1);

      r = cf[idx][1] + 1;
    }
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
        if (!isValidCaptureName(name)) {
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
    // Disallow leading zeros.
    if (pos + 1 < pattern.length()
        && pattern.charAt(pos) == '0'
        && Utils.isDigit(pattern.charAt(pos + 1))) {
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

  private static boolean isValidCaptureName(String name) {
    if (name.isEmpty()) return false;
    // Match java.util.regex.Pattern rules: first character must be an ASCII letter,
    // subsequent characters must be ASCII letters or digits.
    char first = name.charAt(0);
    if (!((first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z'))) {
      return false;
    }
    for (int i = 1; i < name.length(); i++) {
      char c = name.charAt(i);
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
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

  // ---- \R and \X expansion helpers ----

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

  /** Builds a regular approximation of JDK {@code \X} extended grapheme cluster matching. */
  private Regexp buildGraphemeClusterRegexp() {
    // Alternative 1: \r\n (CRLF as a single unit)
    Regexp crLf = Regexp.literalString(new int[] {'\r', '\n'}, flags);

    Regexp extend = buildGraphemeExtendClass(true);
    Regexp extendStar = Regexp.star(extend, flags);
    Regexp extendNoZwjStar = Regexp.star(buildGraphemeExtendClass(false), flags);

    // Extended pictographic sequences joined by ZWJ, e.g. emoji presentation chains.
    Regexp pictographic =
        charClassFromTable(UnicodeProperties.lookupBinaryProperty("Extended_Pictographic"));
    Regexp pictographicWithExtends = Regexp.concat(List.of(pictographic, extendNoZwjStar), flags);
    Regexp zwjPictographicSegment =
        Regexp.concat(List.of(Regexp.literal(0x200D, flags), pictographic, extendNoZwjStar), flags);
    Regexp zwjSequence =
        Regexp.concat(
            List.of(pictographicWithExtends, Regexp.plus(zwjPictographicSegment, flags)), flags);

    // Regional indicators pair into flag clusters.
    Regexp regionalIndicator = charClassFromRange(0x1F1E6, 0x1F1FF);
    Regexp regionalIndicatorPair =
        Regexp.concat(List.of(regionalIndicator, regionalIndicator), flags);

    Regexp hangulCluster = buildHangulGraphemeCluster();

    // \P{M} plus grapheme-extending code points keeps the historical base+mark behavior and
    // covers emoji modifier sequences such as thumbs-up plus skin tone.
    CharClassBuilder nonMarkCcb = new CharClassBuilder();
    addTable(nonMarkCcb, UnicodeTables.UNICODE_GROUPS.get("M"));
    nonMarkCcb.negate(); // \P{M}
    Regexp nonMark = Regexp.charClass(nonMarkCcb.build(), flags);

    Regexp baseWithExtends = Regexp.concat(List.of(nonMark, extendStar), flags);
    Regexp prependCluster =
        Regexp.concat(
            List.of(Regexp.plus(buildGraphemePrependClass(), flags), baseWithExtends), flags);

    // Alternative 3: any single character (fallback for standalone combining marks, controls, etc.)
    // Use ANY_CHAR with DOT_NL to match all characters including newlines.
    Regexp anyOne = Regexp.anyChar(flags | ParseFlags.DOT_NL);

    return Regexp.alternate(
        List.of(
            crLf,
            zwjSequence,
            regionalIndicatorPair,
            hangulCluster,
            prependCluster,
            baseWithExtends,
            anyOne),
        flags);
  }

  private Regexp buildGraphemeExtendClass(boolean includeZwj) {
    CharClassBuilder ccb = new CharClassBuilder();
    addTable(ccb, UnicodeTables.UNICODE_GROUPS.get("M"));
    addTable(ccb, UnicodeProperties.lookupBinaryProperty("Emoji_Modifier"));
    if (includeZwj) {
      ccb.addRune(0x200D);
    }
    return Regexp.charClass(ccb.build(), flags);
  }

  private Regexp buildGraphemePrependClass() {
    CharClassBuilder ccb = new CharClassBuilder();
    ccb.addRange(0x0600, 0x0605);
    ccb.addRune(0x06DD);
    ccb.addRune(0x070F);
    ccb.addRange(0x0890, 0x0891);
    ccb.addRune(0x08E2);
    ccb.addRune(0x110BD);
    ccb.addRune(0x110CD);
    return Regexp.charClass(ccb.build(), flags);
  }

  private Regexp buildHangulGraphemeCluster() {
    Regexp l = buildHangulLClass();
    Regexp v = charClassFromRanges(new int[][] {{0x1160, 0x11A7}, {0xD7B0, 0xD7C6}});
    Regexp t = charClassFromRanges(new int[][] {{0x11A8, 0x11FF}, {0xD7CB, 0xD7FB}});
    Regexp lv = buildHangulSyllableClass(true);
    Regexp lvt = buildHangulSyllableClass(false);
    Regexp tStar = Regexp.star(t, flags);

    Regexp lTail =
        Regexp.alternate(
            List.of(
                Regexp.concat(List.of(Regexp.plus(v, flags), tStar), flags),
                Regexp.concat(List.of(lv, Regexp.star(v, flags), tStar), flags),
                Regexp.concat(List.of(lvt, tStar), flags)),
            flags);
    Regexp lSequence =
        Regexp.concat(List.of(Regexp.plus(l, flags), Regexp.quest(lTail, flags)), flags);
    Regexp lvOrVSequence =
        Regexp.concat(
            List.of(Regexp.alternate(List.of(lv, v), flags), Regexp.star(v, flags), tStar), flags);
    Regexp lvtOrTSequence =
        Regexp.concat(List.of(Regexp.alternate(List.of(lvt, t), flags), tStar), flags);
    return Regexp.alternate(List.of(lSequence, lvOrVSequence, lvtOrTSequence), flags);
  }

  private Regexp buildHangulLClass() {
    return charClassFromRanges(new int[][] {{0x1100, 0x115F}, {0xA960, 0xA97C}});
  }

  private Regexp buildHangulSyllableClass(boolean lv) {
    CharClassBuilder ccb = new CharClassBuilder();
    for (int cp = 0xAC00; cp <= 0xD7A3; cp += 28) {
      if (lv) {
        ccb.addRune(cp);
      } else {
        int hi = Math.min(cp + 27, 0xD7A3);
        if (cp + 1 <= hi) {
          ccb.addRange(cp + 1, hi);
        }
      }
    }
    return Regexp.charClass(ccb.build(), flags);
  }

  private Regexp charClassFromRange(int lo, int hi) {
    CharClassBuilder ccb = new CharClassBuilder();
    ccb.addRange(lo, hi);
    return Regexp.charClass(ccb.build(), flags);
  }

  private Regexp charClassFromRanges(int[][] ranges) {
    CharClassBuilder ccb = new CharClassBuilder();
    addTable(ccb, ranges);
    return Regexp.charClass(ccb.build(), flags);
  }

  private Regexp charClassFromTable(int[][] table) {
    CharClassBuilder ccb = new CharClassBuilder();
    addTable(ccb, table);
    return Regexp.charClass(ccb.build(), flags);
  }

  private void addTable(CharClassBuilder ccb, int[][] table) {
    for (int[] row : table) {
      ccb.addRange(row[0], row[1]);
    }
  }
}
