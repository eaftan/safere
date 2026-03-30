// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

  // Parse state
  private int flags;
  private final String pattern;
  private int pos; // current parse position (char index into pattern)
  private StackEntry stacktop;
  private int ncap;
  private final int runeMax;

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
            // Treat like a literal.
            pushLiteral('{');
            pos++; // '{'
            break;
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
    int r = parseEscape();
    pushLiteral(r);
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
        re = Regexp.literal(r, flags);
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
      if (cycleFoldRune(r) != r) {
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
    if (maybeConcatString(r, flags)) {
      return;
    }

    Regexp re = Regexp.literal(r, flags);
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
    stacktop.down = stacktop.down; // unchanged; sub's old entry is replaced
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
    if (re.op == RegexpOp.REPEAT) {
      int m = re.max;
      if (m < 0) {
        m = re.min;
      }
      if (m <= 0) {
        m = 1;
      }
      limit /= m;
    }
    if (limit == 0) {
      return 0;
    }
    if (re.subs != null) {
      for (Regexp sub : re.subs) {
        int subLimit = countRepeat(sub, limit);
        if (subLimit < limit) {
          limit = subLimit;
        }
      }
    }
    return limit;
  }

  private void doLeftParen(String name) {
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
    // [a-] means (a|-), so check for final ].
    if (pos + 1 < pattern.length()
        && pattern.charAt(pos) == '-'
        && pattern.charAt(pos + 1) != ']') {
      pos++; // '-'
      hi = parseCCCharacter();
      if (hi < lo) {
        throw new PatternSyntaxException("invalid character class range", pattern, pos);
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
      // Octal escapes.
      case '1', '2', '3', '4', '5', '6', '7' -> {
        // Single non-zero octal digit is a backreference; not supported.
        if (pos >= pattern.length()
            || pattern.charAt(pos) < '0'
            || pattern.charAt(pos) > '7') {
          throw new PatternSyntaxException("invalid escape sequence", pattern, pos - 2);
        }
        // fall through to octal parsing
        int code = c - '0';
        if (pos < pattern.length() && pattern.charAt(pos) >= '0'
            && pattern.charAt(pos) <= '7') {
          code = code * 8 + pattern.charAt(pos) - '0';
          pos++;
          if (pos < pattern.length() && pattern.charAt(pos) >= '0'
              && pattern.charAt(pos) <= '7') {
            code = code * 8 + pattern.charAt(pos) - '0';
            pos++;
          }
        }
        if (code > runeMax) {
          throw new PatternSyntaxException("invalid escape sequence", pattern, pos);
        }
        return code;
      }
      case '0' -> {
        // Consume up to two more octal digits; already have one.
        int code = 0;
        if (pos < pattern.length() && pattern.charAt(pos) >= '0'
            && pattern.charAt(pos) <= '7') {
          code = code * 8 + pattern.charAt(pos) - '0';
          pos++;
          if (pos < pattern.length() && pattern.charAt(pos) >= '0'
              && pattern.charAt(pos) <= '7') {
            code = code * 8 + pattern.charAt(pos) - '0';
            pos++;
          }
        }
        if (code > runeMax) {
          throw new PatternSyntaxException("invalid escape sequence", pattern, pos);
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
      // C escapes.
      case 'n' -> { return '\n'; }
      case 'r' -> { return '\r'; }
      case 't' -> { return '\t'; }
      case 'a' -> { return '\u0007'; } // bell
      case 'f' -> { return '\f'; }
      case 'v' -> { return '\u000B'; } // vertical tab
      default -> {
        // Escaped non-word characters are always themselves.
        if (c < 0x80 && !Utils.isAlnum(c)) {
          return c;
        }
        throw new PatternSyntaxException("invalid escape sequence", pattern, pos - 2);
      }
    }
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
      case 's' -> { posName = "\\s"; negate = false; }
      case 'S' -> { posName = "\\s"; negate = true; }
      case 'w' -> { posName = "\\w"; negate = false; }
      case 'W' -> { posName = "\\w"; negate = true; }
      default -> { return null; }
    }

    pos += 2; // '\\', letter
    int[][] table = UnicodeTables.PERL_GROUPS.get(posName);
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

    if (!name.isEmpty() && name.charAt(0) == '^') {
      sign = -sign;
      name = name.substring(1);
    }

    int[][] table = lookupUnicodeGroup(name);
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

  private static int[][] lookupUnicodeGroup(String name) {
    if ("Any".equals(name)) {
      return new int[][] {{0, Utils.MAX_RUNE}};
    }
    return UnicodeTables.UNICODE_GROUPS.get(name);
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

    int[][] table = UnicodeTables.POSIX_GROUPS.get(lookupName);
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
      addFoldedRange(ccb, lo, hi, 0);
    } else {
      ccb.addRange(lo, hi);
    }
  }

  // ---- Case folding ----

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
    // (?P<name>expr) or (?<name>expr)
    if (pos + 3 < pattern.length()) {
      if ((pattern.charAt(pos + 2) == 'P' && pos + 4 < pattern.length()
              && pattern.charAt(pos + 3) == '<')
          || pattern.charAt(pos + 2) == '<') {
        int begin = (pattern.charAt(pos + 2) == 'P') ? pos + 4 : pos + 3;
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
        case 'U' -> {
          sawflags = true;
          if (negated) nflags &= ~ParseFlags.NON_GREEDY;
          else nflags |= ParseFlags.NON_GREEDY;
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
    for (int i = 0; i < name.length(); ) {
      int r = name.codePointAt(i);
      i += Character.charCount(r);
      if (Utils.isAlnum(r) || r == '_') continue;
      // Also allow Unicode letters and digits.
      if (Character.isLetterOrDigit(r) || Character.getType(r) == Character.CONNECTOR_PUNCTUATION) {
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
}
