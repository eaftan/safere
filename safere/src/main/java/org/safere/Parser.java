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
  private final RegexLexer lexer;
  private int tokenStart;
  private StackEntry stacktop;
  private int ncap;
  private final Set<String> namedCaptures = new HashSet<>();

  private Parser(String pattern, int flags) {
    this.lexer = new RegexLexer(pattern, flags);
    this.flags = flags;
    this.stacktop = null;
    this.ncap = 0;
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
    Regexp result;
    try {
      result = p.doParse();
    } catch (PatternSyntaxException syntaxFailure) {
      try {
        p.reportIntentionalDivergences(RegexLexer.isClassSyntaxError(syntaxFailure));
      } catch (RuntimeException | Error notificationFailure) {
        if (!syntaxFailure.equals(notificationFailure)) {
          syntaxFailure.addSuppressed(notificationFailure);
        }
      }
      throw syntaxFailure;
    }
    p.reportIntentionalDivergences(false);
    return result;
  }

  private void reportIntentionalDivergences(boolean classSyntaxFailure) {
    SafeReMatchDiagnostics listener = Pattern.diagnostics();
    if (lexer.sawCommentQuoting()) {
      listener.onIntentionalDivergence(IntentionalDivergence.COMMENT_QUOTING);
    }
    if (classSyntaxFailure) {
      listener.onIntentionalDivergence(IntentionalDivergence.CHARACTER_CLASS_SYNTAX);
    }
  }

  // ---- Token-driven iterative parse method ----

  private Regexp doParse() {
    boolean lastUnary = false;
    boolean lastUnaryWasBareGreedyPlus = false;
    boolean lastTokenNonRepeatable = false;
    while (true) {
      lexer.setFlags(flags);
      RegexLexer.Token token = lexer.next();
      tokenStart = token.start;
      if (token.kind == RegexLexer.Kind.END) break;
      if (WorkCounterConfig.ENABLED) WorkCounter.record();
      boolean unary = false;
      boolean barePlus = false;
      boolean nonRepeatable = false;
      switch (token.kind) {
        case LITERAL -> pushLiteral(token.rune);
        case OPEN -> {
          if (token.text != null || (flags & ParseFlags.NEVER_CAPTURE) == 0) {
            doLeftParen(token.text);
          } else {
            doLeftParenNoCapture();
          }
        }
        case NONCAPTURE, SCOPED_FLAGS -> {
          doLeftParenNoCapture();
          flags = token.flags;
        }
        case FLAGS -> {
          flags = token.flags;
          nonRepeatable = true;
        }
        case CLOSE -> doRightParen();
        case ALTERNATION -> doVerticalBar();
        case CARET -> pushCaret();
        case DOLLAR -> pushDollar();
        case DOT -> pushDot();
        case CLASS_OPEN -> pushRegexp(parseCharClass(token.start));
        case PROPERTY, PREDEFINED -> pushRegexp(finishCharClassBuilder(expandClassToken(token)));
        case BOUNDARY -> pushWordBoundary(!token.negated);
        case GRAPHEME_BOUNDARY -> pushSimpleOp(RegexpOp.GRAPHEME_CLUSTER_BOUNDARY);
        case BEGIN -> pushSimpleOp(RegexpOp.BEGIN_TEXT);
        case END_TEXT -> pushSimpleOp(RegexpOp.END_TEXT);
        case FINAL_END -> {
          int savedFlags = flags;
          flags |= ParseFlags.WAS_DOLLAR;
          pushSimpleOp(RegexpOp.END_TEXT);
          flags = savedFlags;
        }
        case LINEBREAK -> pushRegexp(buildLinebreakRegexp());
        case GRAPHEME -> pushRegexp(Regexp.graphemeCluster(flags));
        case REPEAT -> {
          boolean counted = token.rune == '{';
          boolean reluctant = token.mode == RegexLexer.RepetitionMode.RELUCTANT;
          boolean possessive = token.mode == RegexLexer.RepetitionMode.POSSESSIVE;
          boolean implicitEmpty =
              counted
                  && (lastTokenNonRepeatable
                      || lastUnary
                      || stacktop == null
                      || isMarker(stacktop));
          if (possessive && !implicitEmpty && !canIgnorePossessiveModifierOnZeroWidthOperand()) {
            throw lexer.error("possessive quantifiers are not supported", token.start);
          }
          if (counted) {
            validateRepeatCount(token.min, token.max, token.start);
            if (lastTokenNonRepeatable) {
              nonRepeatable = true;
            } else if (lastUnary) {
              unary = true;
            } else {
              if (possessive
                  && token.min == 0
                  && token.max != 0
                  && canObservePossessiveZeroWidthIteration()) {
                pushPossessiveZeroWidthRepeatRange(token.max);
              } else {
                pushRepetition(token.min, token.max, token.start, reluctant);
              }
              unary = true;
            }
          } else {
            if (lastTokenNonRepeatable) {
              throw lexer.error("missing argument to repetition operator", token.start);
            }
            RegexpOp op =
                token.rune == '*'
                    ? RegexpOp.STAR
                    : token.rune == '+' ? RegexpOp.PLUS : RegexpOp.QUEST;
            if ((flags & ParseFlags.PERL_X) != 0
                && lastUnary
                && !canRepeatAfterUnary(op, lastUnaryWasBareGreedyPlus)) {
              throw lexer.error("invalid nested repetition operator", token.start);
            }
            if (possessive && op == RegexpOp.STAR && canObservePossessiveZeroWidthIteration()) {
              pushPossessiveZeroWidthRepeatOp(op);
            } else {
              pushRepeatOp(op, token.start, reluctant);
            }
            unary = true;
            barePlus = op == RegexpOp.PLUS && !reluctant && !possessive;
          }
        }
        default -> throw new AssertionError("unexpected token: " + token.kind);
      }
      lastUnary = unary;
      lastUnaryWasBareGreedyPlus = barePlus;
      lastTokenNonRepeatable = nonRepeatable;
    }
    return doFinish();
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
      ccb.addRange('\n' + 1, Utils.MAX_RUNE);
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
      ccb.addRange('\u2029' + 1, Utils.MAX_RUNE); // 0x202A–max
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

  private void pushRepeatOp(RegexpOp op, int opStart, boolean nongreedy) {
    if (stacktop == null || isMarker(stacktop)) {
      throw lexer.error("missing argument to repetition operator", opStart);
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
      case PLUS -> pushRepeatOp(RegexpOp.PLUS, tokenStart, false);
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

  private void pushRepetition(int min, int max, int opStart, boolean nongreedy) {
    validateRepeatCount(min, max, opStart);
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
        throw lexer.error("invalid repeat count", opStart);
      }
    }
  }

  private void validateRepeatCount(int min, int max, int opStart) {
    if ((max != -1 && max < min) || min > MAX_REPEAT || max > MAX_REPEAT) {
      throw lexer.error("invalid repeat count", opStart);
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
      throw lexer.error("named capturing group <" + name + "> is already defined", tokenStart);
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
      throw lexer.error("unexpected )", tokenStart);
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
      throw lexer.error("missing closing )", lexer.length());
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

  // ---- Character-class grammar over shared lexical tokens ----

  private Regexp parseCharClass(int rootStart) {
    ArrayDeque<ClassExpressionFrame> stack = new ArrayDeque<>();
    stack.push(newClassFrame(rootStart));
    while (!stack.isEmpty()) {
      ClassExpressionFrame frame = stack.peek();
      RegexLexer.Token token = lexer.takeClass();
      int start = token.start;
      switch (token.kind) {
        case END -> throw lexer.error("missing closing ]", frame.classStart);
        case CLASS_CLOSE -> {
          if (!frame.hasItems && !frame.afterIntersection) {
            addScalarClassItem(frame.currentUnion, ']');
            frame.hasItems = true;
            continue;
          }
          if (frame.afterIntersection)
            throw lexer.classError("empty right side of character class intersection", start);
          CharClassBuilder completed = completeClassExpression(frame);
          stack.pop();
          if (stack.isEmpty()) return finishCharClassBuilder(completed);
          ClassExpressionFrame parent = stack.peek();
          parent.currentUnion.addCharClass(completed);
          parent.hasItems = true;
          parent.afterIntersection = false;
        }
        case CLASS_OPEN -> stack.push(newClassFrame(start));
        case INTERSECTION -> {
          if (!frame.hasItems || frame.afterIntersection) {
            throw lexer.classError("empty left side of character class intersection", start);
          }
          if (frame.leftOperand == null) frame.leftOperand = frame.currentUnion;
          else frame.leftOperand.intersect(frame.currentUnion);
          frame.currentUnion = new CharClassBuilder();
          frame.afterIntersection = true;
          RegexLexer.Kind next = lexer.peekClass().kind;
          if (next == RegexLexer.Kind.AMPERSAND || next == RegexLexer.Kind.INTERSECTION) {
            throw lexer.classError("invalid character class intersection", start);
          }
          if (next == RegexLexer.Kind.HYPHEN)
            throw lexer.classError("dangling character class '-'", lexer.peekClass().start);
        }
        case PROPERTY, PREDEFINED -> {
          boolean verticalTab =
              token.kind == RegexLexer.Kind.PREDEFINED && token.rune == 'v' && !token.negated;
          CharClassBuilder expanded = expandClassToken(token);
          if (verticalTab
              && lexer.peekClass().kind == RegexLexer.Kind.HYPHEN
              && !lexer.peekClass().leadingTrivia) {
            throw lexer.classError(
                "ambiguous \\v before '-' in a character class: use \\x0B for U+000B, or escape the"
                    + " hyphen as \\-",
                start);
          }
          frame.currentUnion.addCharClass(expanded);
          frame.hasItems = true;
          frame.afterIntersection = false;
        }
        case LITERAL, CARET, HYPHEN, AMPERSAND -> {
          if (frame.afterIntersection && token.kind == RegexLexer.Kind.HYPHEN) {
            throw lexer.classError("dangling character class '-'", start);
          }
          addScalarClassItem(frame.currentUnion, token.rune);
          frame.hasItems = true;
          frame.afterIntersection = false;
        }
        default -> throw lexer.error("bad class syntax", start);
      }
    }
    throw new AssertionError("class stack exhausted");
  }

  private ClassExpressionFrame newClassFrame(int start) {
    RegexLexer.Token first = lexer.peekClass();
    boolean negated = first.kind == RegexLexer.Kind.CARET && !first.leadingTrivia;
    if (negated) lexer.takeClass();
    return new ClassExpressionFrame(start, negated);
  }

  private void addScalarClassItem(CharClassBuilder ccb, int lo) {
    if (lexer.peekClass().kind == RegexLexer.Kind.HYPHEN) {
      lexer.takeClass();
      RegexLexer.Token endpoint = lexer.peekClassRangeEndpoint();
      if ((endpoint.kind == RegexLexer.Kind.CLASS_CLOSE
              || endpoint.kind == RegexLexer.Kind.CLASS_OPEN)
          && endpoint.leadingTrivia) {
        throw lexer.error("bad class syntax", endpoint.start);
      }
      if (endpoint.kind == RegexLexer.Kind.CLASS_CLOSE
          || endpoint.kind == RegexLexer.Kind.CLASS_OPEN
          || endpoint.kind == RegexLexer.Kind.END) {
        addRangeFlags(ccb, lo, lo, flags | ParseFlags.CLASS_NL);
        addRangeFlags(ccb, '-', '-', flags | ParseFlags.CLASS_NL);
        return;
      }
      if (endpoint.kind == RegexLexer.Kind.PREDEFINED
          && endpoint.rune == 'v'
          && !endpoint.negated) {
        throw lexer.classError(
            "ambiguous \\v as a character class range end: use \\x0B for U+000B, or escape the"
                + " hyphen as \\-",
            endpoint.start);
      }
      if (endpoint.kind != RegexLexer.Kind.LITERAL
          && endpoint.kind != RegexLexer.Kind.CARET
          && endpoint.kind != RegexLexer.Kind.HYPHEN
          && endpoint.kind != RegexLexer.Kind.AMPERSAND) {
        throw lexer.error("invalid escape sequence", endpoint.start);
      }
      int hi = endpoint.rune;
      int end = endpoint.start;
      lexer.takeClass();
      if (hi < lo) throw lexer.error("invalid character class range", end);
      addRangeFlags(ccb, lo, hi, flags | ParseFlags.CLASS_NL);
    } else {
      addRangeFlags(ccb, lo, lo, flags | ParseFlags.CLASS_NL);
    }
  }

  private CharClassBuilder expandClassToken(RegexLexer.Token token) {
    CharClassExpander.Group group =
        token.kind == RegexLexer.Kind.PROPERTY
            ? CharClassExpander.lookupUnicodeGroup(
                token.text, (flags & ParseFlags.UNICODE_CHAR_CLASS) != 0)
            : CharClassExpander.lookupPerlGroup(
                "\\" + (char) token.rune, (flags & ParseFlags.UNICODE_CHAR_CLASS) != 0);
    if (group == null) throw lexer.error("invalid Unicode group: " + token.text, token.start);
    CharClassBuilder ccb = new CharClassBuilder();
    if (token.negated) CharClassExpander.addNegatedGroup(ccb, group, flags);
    else CharClassExpander.addPositiveGroup(ccb, group, flags);
    return ccb;
  }

  private static void addRangeFlags(CharClassBuilder ccb, int lo, int hi, int parseFlags) {
    CharClassExpander.addRange(ccb, lo, hi, parseFlags);
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
