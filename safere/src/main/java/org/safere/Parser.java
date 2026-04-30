// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

/**
 * Parser for RE2 regular expression syntax. Converts a pattern string into a {@link Regexp} AST.
 *
 * <p>This is a stack-based operator-precedence parser ported from RE2's {@code parse.cc}. It
 * supports POSIX extended regular expressions (excluding backreferences, collating elements, and
 * collating classes), Perl extensions (when enabled via flags), Unicode character properties, and
 * named captures.
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
   * Perl's {@code (?x)} mode. Whitespace and comments become insignificant, allowing patterns to
   * be formatted with whitespace and annotations for readability.
   */
  private void skipCommentsAndWhitespace() {
    while (pos < pattern.length()) {
      int c = pattern.codePointAt(pos);
      if (c == '#') {
        // Skip from '#' to end of line (or end of pattern).
        while (pos < pattern.length() && pattern.charAt(pos) != '\n') {
          pos++;
        }
        // Skip the newline itself if present.
        if (pos < pattern.length()) {
          pos++;
        }
      } else if (Character.isWhitespace(c)) {
        pos += Character.charCount(c);
      } else {
        break;
      }
    }
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
    while (pos < pattern.length()) {
      // In comments mode, skip whitespace and #-comments before each token.
      if ((flags & ParseFlags.COMMENTS) != 0) {
        skipCommentsAndWhitespace();
        if (pos >= pattern.length()) {
          break;
        }
      }
      String isunary = null;
      int c = pattern.codePointAt(pos);
      switch (c) {
        case '(' -> {
          // "(?" introduces Perl escape.
          if ((flags & ParseFlags.PERL_X) != 0
              && pos + 1 < pattern.length()
              && pattern.charAt(pos + 1) == '?') {
            parsePerlFlags();
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
          RegexpOp op =
              c == '*'
                  ? RegexpOp.STAR
                  : c == '+' ? RegexpOp.PLUS : RegexpOp.QUEST;
          int opStart = pos;
          pos++; // the operator
          boolean nongreedy = false;
          if ((flags & ParseFlags.PERL_X) != 0) {
            if (pos < pattern.length() && pattern.charAt(pos) == '?') {
              nongreedy = true;
              pos++; // '?'
            }
            if (lastunary != null) {
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
            if (lastunary != null) {
              throw new PatternSyntaxException(
                  "invalid nested repetition operator", pattern, opStart);
            }
          }
          String opstr = pattern.substring(opStart, pos);
          pushRepetition(lo, hi, opstr, nongreedy);
          isunary = opstr;
        }
        case '\\' -> {
          parseBackslash();
        }
        default -> {
          pos += Character.charCount(c);
          pushLiteral(c);
        }
      }
      lastunary = isunary;
    }
    return doFinish();
  }

  // ---- Backslash handling (top-level) ----

  private void parseBackslash() {
    // \b and \B: word boundary or not
    if ((flags & ParseFlags.PERL_B) != 0
        && pos + 1 < pattern.length()
        && (pattern.charAt(pos + 1) == 'b' || pattern.charAt(pos + 1) == 'B')) {
      // \b{g}: grapheme cluster boundary — accepted for JDK compatibility.
      // Approximated as an empty match (matches at every position).
      if (pattern.charAt(pos + 1) == 'b'
          && pos + 4 < pattern.length()
          && pattern.charAt(pos + 2) == '{'
          && pattern.charAt(pos + 3) == 'g'
          && pattern.charAt(pos + 4) == '}') {
        pos += 5; // '\\', 'b', '{', 'g', '}'
        pushRegexp(Regexp.emptyMatch(flags));
        return;
      }
      pushWordBoundary(pattern.charAt(pos + 1) == 'b');
      pos += 2; // '\\', 'b' or 'B'
      return;
    }

    if ((flags & ParseFlags.PERL_X) != 0 && pos + 1 < pattern.length()) {
      char next = pattern.charAt(pos + 1);
      if (next == 'A') {
        pushSimpleOp(RegexpOp.BEGIN_TEXT);
        pos += 2;
        return;
      }
      if (next == 'z') {
        pushSimpleOp(RegexpOp.END_TEXT);
        pos += 2;
        return;
      }
      if (next == 'Z') {
        // \Z matches at end of input or before a final newline, same as $ in non-multiline mode.
        int oflags = flags;
        flags |= ParseFlags.WAS_DOLLAR;
        pushSimpleOp(RegexpOp.END_TEXT);
        flags = oflags;
        pos += 2;
        return;
      }
      if (next == 'G') {
        throw new PatternSyntaxException(
            "\\G (end of previous match) is not supported", pattern, pos);
      }
      if (next == 'Q') {
        // \Q ... \E: the ... is always literals
        pos += 2; // '\\', 'Q'
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
        }
        return;
      }
    }

    // \R: Unicode linebreak sequence.
    // Equivalent to (?:\r\n|[\n\x0B\f\r\x{85}\x{2028}\x{2029}]).
    if (pos + 1 < pattern.length() && pattern.charAt(pos + 1) == 'R') {
      pos += 2; // '\\', 'R'
      pushRegexp(buildLinebreakRegexp());
      return;
    }

    // \X: Extended grapheme cluster (simplified).
    // Equivalent to (?:\r\n|\P{M}\p{M}*|<any>), which handles base+combining-marks.
    if (pos + 1 < pattern.length() && pattern.charAt(pos + 1) == 'X') {
      pos += 2; // '\\', 'X'
      pushRegexp(buildGraphemeClusterRegexp());
      return;
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
        return;
      } else if (result == PARSE_ERROR) {
        // error already thrown by parseUnicodeGroup
        return;
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
        return;
      }
      pos = saved;
    }

    // Regular escape
    if (maybePushNumericBackreferenceEscape()) {
      return;
    }
    int r = parseEscape();
    pushLiteral(r);
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
    while (pos < pattern.length()
        && pattern.charAt(pos) >= '0'
        && pattern.charAt(pos) <= '9') {
      pos++;
    }
    pushRegexp(Regexp.noMatch(flags));
    return true;
  }

  // ---- Stack operations ----

  private boolean isMarker(StackEntry e) {
    return e != null && e.re != null && e.re.matchId < 0
        && e.re.op == RegexpOp.NO_MATCH;
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
        if ('A' <= r && r <= 'Z'
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
      ccb.addRange(0, '\n' - 1);             // 0x00–0x09
      ccb.addRange('\n' + 1, '\r' - 1);      // 0x0B–0x0C
      ccb.addRange('\r' + 1, '\u0085' - 1);  // 0x0E–0x0084
      ccb.addRange('\u0085' + 1, '\u2028' - 1); // 0x0086–0x2027
      ccb.addRange('\u2029' + 1, runeMax);    // 0x202A–max
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
    Regexp re = switch (op) {
      case BEGIN_LINE -> Regexp.beginLine(flags);
      case END_LINE -> Regexp.endLine(flags);
      case BEGIN_TEXT -> Regexp.beginText(flags);
      case END_TEXT -> Regexp.endText(flags);
      case ANY_CHAR -> Regexp.anyChar(flags);
      case WORD_BOUNDARY -> Regexp.wordBoundary(flags);
      case NO_WORD_BOUNDARY -> Regexp.noWordBoundary(flags);
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
    Regexp re = switch (op) {
      case STAR -> Regexp.star(sub, fl);
      case PLUS -> Regexp.plus(sub, fl);
      case QUEST -> Regexp.quest(sub, fl);
      default -> throw new IllegalStateException("unexpected repeat op: " + op);
    };
    stacktop.re = re;
  }

  private void pushRepetition(int min, int max, String opstr, boolean nongreedy) {
    if ((max != -1 && max < min) || min > MAX_REPEAT || max > MAX_REPEAT) {
      throw new PatternSyntaxException(
          "invalid repeat count", pattern, pos - opstr.length());
    }
    if (stacktop == null || isMarker(stacktop)) {
      throw new PatternSyntaxException(
          "missing argument to repetition operator", pattern, pos - opstr.length());
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
        throw new PatternSyntaxException(
            "invalid repeat count", pattern, pos - opstr.length());
      }
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
    int multiplier = 1;
    if (re.op == RegexpOp.REPEAT) {
      multiplier = re.max;
      if (multiplier < 0) {
        multiplier = re.min;
      }
      if (multiplier <= 0) {
        multiplier = 1;
      }
    }

    RepeatCount subCount = switch (re.op) {
      case ALTERNATE -> alternateRepeatCount(re, limit);
      case CONCAT -> concatRepeatCount(re, limit);
      default -> unaryRepeatCount(re, limit);
    };

    int cost = multiplySaturated(multiplier, subCount.cost, limit);
    return new RepeatCount(cost, re.op == RegexpOp.REPEAT || subCount.hasRepeat);
  }

  private static RepeatCount unaryRepeatCount(Regexp re, int limit) {
    if (re.subs == null || re.subs.isEmpty()) {
      return new RepeatCount(1, false);
    }
    RepeatCount maxCount = new RepeatCount(1, false);
    for (Regexp sub : re.subs) {
      RepeatCount subCount = repeatCount(sub, limit);
      if (subCount.cost > maxCount.cost) {
        maxCount = subCount;
      }
    }
    return maxCount;
  }

  private static RepeatCount alternateRepeatCount(Regexp re, int limit) {
    int cost = 1;
    boolean hasRepeat = false;
    for (Regexp sub : re.subs) {
      RepeatCount subCount = repeatCount(sub, limit);
      cost = Math.max(cost, subCount.cost);
      hasRepeat |= subCount.hasRepeat;
    }
    return new RepeatCount(cost, hasRepeat);
  }

  private static RepeatCount concatRepeatCount(Regexp re, int limit) {
    int cost = 0;
    boolean hasRepeat = false;
    for (Regexp sub : re.subs) {
      RepeatCount subCount = repeatCount(sub, limit);
      if (subCount.hasRepeat) {
        cost = addSaturated(cost, subCount.cost, limit);
        hasRepeat = true;
      }
    }
    if (!hasRepeat) {
      return new RepeatCount(1, false);
    }
    return new RepeatCount(cost, true);
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
      pushRegexp(r1.re);
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

    Regexp re = op == RegexpOp.CONCAT
        ? Regexp.concat(subs, flags)
        : Regexp.alternate(subs, flags);
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
    int classStart = pos;
    if (pos >= pattern.length() || pattern.charAt(pos) != '[') {
      throw new PatternSyntaxException("internal error", pattern, pos);
    }
    pos++; // '['

    boolean negated = false;
    CharClassBuilder ccb = new CharClassBuilder();
    if (pos < pattern.length() && pattern.charAt(pos) == '^') {
      pos++; // '^'
      negated = true;
      if ((flags & ParseFlags.CLASS_NL) == 0 || (flags & ParseFlags.NEVER_NL) != 0) {
        // If NL can't match implicitly, pretend negated classes include a leading \n.
        ccb.addRune('\n');
      }
    }

    boolean first = true; // ] is okay as first char in class
    while (pos < pattern.length() && (pattern.charAt(pos) != ']' || first)) {
      // In comments mode, skip whitespace and #-comments inside character classes.
      if ((flags & ParseFlags.COMMENTS) != 0) {
        skipCommentsAndWhitespace();
        if (pos >= pattern.length()) {
          break; // will hit "missing closing ]" below
        }
        // After skipping, re-check for ']' (unless first).
        if (pattern.charAt(pos) == ']' && !first) {
          break;
        }
      }

      // Character class intersection: &&
      if (!first
          && pos + 1 < pattern.length()
          && pattern.charAt(pos) == '&'
          && pattern.charAt(pos + 1) == '&') {
        pos += 2; // skip '&&'
        if (pos < pattern.length() && pattern.charAt(pos) == ']') {
          break;
        }
        // Parse the right-hand side of the intersection.
        CharClassBuilder rhs = new CharClassBuilder();
        while (pos < pattern.length() && pattern.charAt(pos) != ']'
            && !(pos + 1 < pattern.length()
                && pattern.charAt(pos) == '&' && pattern.charAt(pos + 1) == '&')) {
          if (pos < pattern.length() && pattern.charAt(pos) == '[') {
            Regexp nested = parseCharClass();
            rhs.addCharClass(nested.charClass);
          } else {
            int[] rr = parseCCRange();
            addRangeFlags(rhs, rr[0], rr[1], flags | ParseFlags.CLASS_NL);
          }
        }
        ccb.intersect(rhs);
        continue;
      }

      // - is only okay unescaped as first or last in class (except with PerlX).
      if (pattern.charAt(pos) == '-' && !first && (flags & ParseFlags.PERL_X) == 0
          && (pos + 1 >= pattern.length() || pattern.charAt(pos + 1) != ']')) {
        throw new PatternSyntaxException(
            "invalid character class range", pattern, pos);
      }
      first = false;

      // Look for [:alnum:] etc.
      if (pos + 2 < pattern.length()
          && pattern.charAt(pos) == '['
          && pattern.charAt(pos + 1) == ':') {
        int result = parseCCName(ccb);
        if (result == PARSE_OK) {
          continue;
        } else if (result == PARSE_ERROR) {
          // error already thrown
          return null;
        }
        // PARSE_NOTHING: fall through
      }

      // Look for nested character class like [[A-F]] (Java-style union).
      // At this point we know pattern.charAt(pos) == '[' and it's not a POSIX class.
      if (pos < pattern.length() && pattern.charAt(pos) == '[') {
        Regexp nested = parseCharClass();
        ccb.addCharClass(nested.charClass);
        continue;
      }

      // Look for \Q...\E quoted literal sequence inside character class.
      if (pos + 1 < pattern.length()
          && pattern.charAt(pos) == '\\'
          && pattern.charAt(pos + 1) == 'Q') {
        pos += 2; // skip \Q
        while (pos < pattern.length()) {
          if (pos + 1 < pattern.length()
              && pattern.charAt(pos) == '\\'
              && pattern.charAt(pos + 1) == 'E') {
            pos += 2; // skip \E
            break;
          }
          int r = pattern.codePointAt(pos);
          pos += Character.charCount(r);
          addRangeFlags(ccb, r, r, flags | ParseFlags.CLASS_NL);
        }
        continue;
      }

      // Look for Unicode character group like \p{Han}
      if (pos + 2 < pattern.length()
          && pattern.charAt(pos) == '\\'
          && (pattern.charAt(pos + 1) == 'p' || pattern.charAt(pos + 1) == 'P')) {
        int result = parseUnicodeGroup(ccb);
        if (result == PARSE_OK) {
          continue;
        } else if (result == PARSE_ERROR) {
          return null;
        }
        // PARSE_NOTHING: fall through
      }

      // Look for Perl character class symbols.
      {
        int saved = pos;
        CharClassBuilder perlCcb = maybeParsePerlCCEscape();
        if (perlCcb != null) {
          ccb.addCharClass(perlCcb);
          continue;
        }
        pos = saved;
      }

      // Otherwise assume single character or simple range.
      int[] rr = parseCCRange();
      // AddRangeFlags: for explicit ranges, set ClassNL so \n is not filtered out.
      addRangeFlags(ccb, rr[0], rr[1], flags | ParseFlags.CLASS_NL);
    }

    if (pos >= pattern.length()) {
      throw new PatternSyntaxException(
          "missing closing ]", pattern, classStart);
    }
    pos++; // ']'

    if (negated) {
      ccb.negate();
    }

    return Regexp.charClass(ccb.build(), flags & ~ParseFlags.FOLD_CASE);
  }

  private int[] parseCCRange() {
    int lo = parseCCCharacter();
    int hi = lo;
    // In comments mode, skip whitespace before checking for '-'.
    if ((flags & ParseFlags.COMMENTS) != 0) {
      skipCommentsAndWhitespace();
    }
    // [a-] means (a|-), so '-' at end of class is literal.
    // In comments mode, peek past whitespace after '-' to check for ']'.
    if (pos < pattern.length() && pattern.charAt(pos) == '-') {
      int peekPos = pos + 1;
      if ((flags & ParseFlags.COMMENTS) != 0) {
        while (peekPos < pattern.length() && Character.isWhitespace(pattern.charAt(peekPos))) {
          peekPos++;
        }
      }
      if (peekPos < pattern.length() && pattern.charAt(peekPos) != ']') {
        pos++; // '-'
        // In comments mode, skip whitespace after '-'.
        if ((flags & ParseFlags.COMMENTS) != 0) {
          skipCommentsAndWhitespace();
        }
        hi = parseCCCharacter();
        if (hi < lo) {
          throw new PatternSyntaxException("invalid character class range", pattern, pos);
        }
      }
    }
    return new int[] {lo, hi};
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
        if (pos >= pattern.length()
            || pattern.charAt(pos) < '0'
            || pattern.charAt(pos) > '7') {
          throw new PatternSyntaxException("Illegal octal escape sequence", pattern, pos);
        }
        int code = 0;
        int digits = 0;
        while (digits < 3 && pos < pattern.length()
            && pattern.charAt(pos) >= '0' && pattern.charAt(pos) <= '7') {
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
      case 'n' -> { return '\n'; }
      case 'r' -> { return '\r'; }
      case 't' -> { return '\t'; }
      case 'a' -> { return '\u0007'; } // bell
      case 'e' -> { return '\u001B'; } // escape
      case 'f' -> { return '\f'; }
      // Control character: \cX → X ^ 0x40
      case 'c' -> {
        if (pos >= pattern.length()) {
          throw new PatternSyntaxException("invalid escape sequence", pattern, pos - 2);
        }
        int ctrl = pattern.codePointAt(pos);
        pos += Character.charCount(ctrl);
        // JDK accepts ASCII letters and some symbols; the result is ctrl ^ 0x40.
        if (ctrl >= 0x40 && ctrl <= 0x7F) {
          return ctrl ^ 0x40;
        }
        throw new PatternSyntaxException("invalid escape sequence", pattern, pos - 1);
      }
      default -> {
        // Escaped non-word characters are always themselves.
        if (c < 0x80 && !Utils.isAlnum(c)) {
          return c;
        }
        throw new PatternSyntaxException("invalid escape sequence", pattern, pos - 2);
      }
    }
  }

  /**
   * Parses exactly {@code n} hex digits at the current position and returns their value.
   * Advances {@code pos} past the digits.
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
      case 'd' -> { posName = "\\d"; negate = false; }
      case 'D' -> { posName = "\\d"; negate = true; }
      case 'h' -> { posName = "\\h"; negate = false; }
      case 'H' -> { posName = "\\h"; negate = true; }
      case 's' -> { posName = "\\s"; negate = false; }
      case 'S' -> { posName = "\\s"; negate = true; }
      case 'v' -> { posName = "\\v"; negate = false; }
      case 'V' -> { posName = "\\v"; negate = true; }
      case 'w' -> { posName = "\\w"; negate = false; }
      case 'W' -> { posName = "\\w"; negate = true; }
      default -> { return null; }
    }

    pos += 2; // '\\', letter
    // Use Unicode-aware tables when UNICODE_CHAR_CLASS is active.
    int[][] table = (flags & ParseFlags.UNICODE_CHAR_CLASS) != 0
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
      throw new PatternSyntaxException(
          "invalid Unicode group: " + name, pattern, seqStart);
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
    table = unicodeCharacterClass
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

  // ---- POSIX class name parsing ([:alnum:], etc.) ----

  private int parseCCName(CharClassBuilder ccb) {
    // Check begins with [:
    if (pos + 2 >= pattern.length()
        || pattern.charAt(pos) != '['
        || pattern.charAt(pos + 1) != ':') {
      return PARSE_NOTHING;
    }

    // Look for closing :].
    int q = pattern.indexOf(":]", pos + 2);
    if (q < 0) {
      return PARSE_NOTHING;
    }
    q += 2; // past :]

    String name = pattern.substring(pos, q);
    // name is like "[:alnum:]" or "[:^alpha:]"

    // Check for negation: [:^...]
    boolean negated = false;
    String lookupName = name;
    if (name.length() > 4 && name.charAt(2) == '^') {
      negated = true;
      // Convert [:^alpha:] to [:alpha:]
      lookupName = "[:" + name.substring(3);
    }

    int[][] table = (flags & ParseFlags.UNICODE_CHAR_CLASS) != 0
        ? UnicodeTables.unicodePosixGroups().get(lookupName)
        : UnicodeTables.POSIX_GROUPS.get(lookupName);
    if (table == null) {
      throw new PatternSyntaxException("invalid POSIX class: " + name, pattern, pos);
    }

    pos = q; // advance past the class name

    if (negated) {
      addGroupNegated(ccb, table);
    } else {
      addGroupPositive(ccb, table);
    }
    return PARSE_OK;
  }

  // ---- Group add helpers ----

  private void addGroupPositive(CharClassBuilder ccb, int[][] table) {
    for (int[] row : table) {
      addRangeFlags(ccb, row[0], row[1], flags);
    }
  }

  private void addGroupNegated(CharClassBuilder ccb, int[][] table) {
    if ((flags & ParseFlags.FOLD_CASE) != 0) {
      // Build the positive set with folding, then negate, then merge.
      CharClassBuilder ccb1 = new CharClassBuilder();
      addGroupPositive(ccb1, table);
      boolean cutnl = (flags & ParseFlags.CLASS_NL) == 0
          || (flags & ParseFlags.NEVER_NL) != 0;
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

  /**
   * Add a range to the character class, but exclude newline if asked. Also handle case folding.
   */
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

  private void parsePerlFlags() {
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
          throw new PatternSyntaxException(
              "invalid named capture: " + name, pattern, pos);
        }
        doLeftParen(name);
        pos = end + 1; // skip past '>'
        return;
      }
    }

    pos += 2; // "(?"

    boolean negated = false;
    boolean sawflags = false;
    int nflags = flags;

    boolean done = false;
    while (!done) {
      if (pos >= pattern.length()) {
        throw new PatternSyntaxException(
            "invalid Perl operator", pattern, startPos);
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
            nflags &= ~(ParseFlags.UNICODE_CASE | ParseFlags.UNICODE_GROUPS
                | ParseFlags.UNICODE_CHAR_CLASS);
          } else {
            nflags |= ParseFlags.UNICODE_CASE | ParseFlags.UNICODE_GROUPS
                | ParseFlags.UNICODE_CHAR_CLASS;
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
  }

  // ---- Repetition parsing ----

  /**
   * Tries to parse a repetition suffix like {1,2} or {2} or {2,}. Returns null if the pattern
   * at pos does not look like a valid repetition. Otherwise returns int[]{lo, hi} and advances pos.
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
    ccb.addRune('\n');       // U+000A LINE FEED
    ccb.addRune('\u000B');   // U+000B VERTICAL TAB
    ccb.addRune('\f');       // U+000C FORM FEED
    ccb.addRune('\r');       // U+000D CARRIAGE RETURN
    ccb.addRune(0x85);       // U+0085 NEXT LINE
    ccb.addRune(0x2028);     // U+2028 LINE SEPARATOR
    ccb.addRune(0x2029);     // U+2029 PARAGRAPH SEPARATOR
    Regexp singleLinebreak = Regexp.charClass(ccb.build(), flags);

    return Regexp.alternate(List.of(crLf, singleLinebreak), flags);
  }

  /**
   * Builds a Regexp equivalent to {@code (?:\r\n|\P{M}\p{M}*|[\s\S])}, a simplified extended
   * grapheme cluster matcher. This handles:
   *
   * <ul>
   *   <li>{@code \r\n} as a single grapheme cluster
   *   <li>A non-combining-mark character followed by zero or more combining marks
   *   <li>Any single character as a fallback (e.g., standalone combining marks)
   * </ul>
   *
   * <p>This does not implement the full UAX #29 grapheme cluster algorithm (Hangul jamo
   * composition, emoji ZWJ sequences, regional indicator pairs, etc.).
   */
  private Regexp buildGraphemeClusterRegexp() {
    // Alternative 1: \r\n (CRLF as a single unit)
    Regexp crLf = Regexp.literalString(new int[] {'\r', '\n'}, flags);

    // Alternative 2: \P{M}\p{M}* (base character + combining marks)
    int[][] markTable = UnicodeTables.UNICODE_GROUPS.get("M");
    CharClassBuilder nonMarkCcb = new CharClassBuilder();
    for (int[] row : markTable) {
      nonMarkCcb.addRange(row[0], row[1]);
    }
    nonMarkCcb.negate(); // \P{M}
    Regexp nonMark = Regexp.charClass(nonMarkCcb.build(), flags);

    CharClassBuilder markCcb = new CharClassBuilder();
    for (int[] row : markTable) {
      markCcb.addRange(row[0], row[1]);
    }
    Regexp mark = Regexp.charClass(markCcb.build(), flags);
    Regexp markStar = Regexp.star(mark, flags);
    Regexp baseWithMarks = Regexp.concat(List.of(nonMark, markStar), flags);

    // Alternative 3: any single character (fallback for standalone combining marks, controls, etc.)
    // Use ANY_CHAR with DOT_NL to match all characters including newlines.
    Regexp anyOne = Regexp.anyChar(flags | ParseFlags.DOT_NL);

    return Regexp.alternate(List.of(crLf, baseWithMarks, anyOne), flags);
  }
}
