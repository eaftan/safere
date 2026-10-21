// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayDeque;
import java.util.Deque;

/** A required suffix and a guarded gap can exclude a prefix of candidate match starts. */
final class InnerLiteralSearch {
  // A failed probe must add bounded work even when the entire input belongs to the gap class.
  private static final int MAX_BACKWARD_CODE_POINTS = 256;

  // Pay for narrowing only after the native matcher has consumed this much failed input.
  // Prefix-accelerator skips do not contribute to the budget.
  static final int MIN_FAILED_INPUT_UNITS = 64;

  private final RejectPrefilter.Literal suffix;
  private final CharClassScanInfo gap;
  private final int prefixCodePoints;

  private InnerLiteralSearch(
      RejectPrefilter.Literal suffix, CharClassScanInfo gap, int prefixCodePoints) {
    this.suffix = suffix;
    this.gap = gap;
    this.prefixCodePoints = prefixCodePoints;
  }

  static InnerLiteralSearch compile(Regexp ast, RejectPrefilter rejectPrefilter) {
    Deque<Regexp> pending = new ArrayDeque<>();
    pending.push(ast);
    StringBuilder prefix = new StringBuilder();
    StringBuilder suffix = new StringBuilder();
    CharClassScanInfo gap = null;
    while (!pending.isEmpty()) {
      Regexp node = pending.pop();
      switch (node.op) {
        case CAPTURE, NON_CAPTURE, CONCAT -> {
          for (int i = node.subs.size() - 1; i >= 0; i--) {
            pending.push(node.subs.get(i));
          }
        }
        case EMPTY_MATCH,
            BEGIN_TEXT,
            END_TEXT,
            BEGIN_LINE,
            END_LINE,
            WORD_BOUNDARY,
            NO_WORD_BOUNDARY -> {
          // Assertions only restrict the language; ordinary matching still evaluates them.
        }
        case LITERAL, LITERAL_STRING -> {
          if ((node.flags & ParseFlags.FOLD_CASE) != 0) {
            return null;
          }
          StringBuilder target = gap == null ? prefix : suffix;
          if (node.op == RegexpOp.LITERAL) {
            if (!appendScalar(target, node.rune)) {
              return null;
            }
          } else {
            for (int rune : node.runes) {
              if (!appendScalar(target, rune)) {
                return null;
              }
            }
          }
        }
        case STAR, PLUS, QUEST, REPEAT -> {
          if (gap != null || prefix.isEmpty()) {
            return null;
          }
          Regexp operand = node.subs.getFirst();
          while (operand.op == RegexpOp.CAPTURE || operand.op == RegexpOp.NON_CAPTURE) {
            operand = operand.subs.getFirst();
          }
          if (operand.op != RegexpOp.CHAR_CLASS || operand.charClass.isEmpty()) {
            return null;
          }
          gap = CharClassScanInfo.fromCharClass(operand.charClass);
        }
        default -> {
          return null;
        }
      }
    }
    if (gap == null || suffix.isEmpty()) {
      return null;
    }
    String literal = suffix.toString();
    RejectPrefilter.Literal filter =
        rejectPrefilter instanceof RejectPrefilter.Literal existing
                && existing.literal().equals(literal)
            ? existing
            : RejectPrefilter.Literal.create(literal);
    return new InnerLiteralSearch(filter, gap, prefix.codePointCount(0, prefix.length()));
  }

  private static boolean appendScalar(StringBuilder target, int rune) {
    if (rune >= Character.MIN_SURROGATE && rune <= Character.MAX_SURROGATE) {
      return false;
    }
    target.appendCodePoint(rune);
    return true;
  }

  /**
   * Returns a safe lower bound for match starts, or {@code -1} when the suffix is absent.
   *
   * <p>No match can end before the first suffix occurrence. If the character before that suffix
   * cannot belong to the gap, any start whose fixed prefix ends before that character is also
   * impossible for every later suffix occurrence. Retreating by the prefix's code-point length
   * retains every possible start. The normal engine checks the remaining candidates, including
   * bounds, assertions, greediness, and captures.
   *
   * <p>This performs one literal scan and one bounded backward probe per search, without retries. A
   * successful match consumes the first suffix occurrence or a later one, so successive finds do
   * not repeatedly scan the same unconsumed suffix range.
   */
  int searchStart(InputScanner scanner, int from) {
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.recordInnerLiteralProbe();
    }
    int inner;
    if (scanner instanceof Utf8InputScanner bytes) {
      inner = bytes.indexOf(suffix.utf8(), suffix.failure(), suffix.shifts(), from);
    } else {
      String text = ((StringInputScanner) scanner).text();
      inner =
          StringLiteralSearch.indexOf(
              text, suffix.literal(), suffix.anchorOffset(), suffix.anchor(), from);
    }
    if (inner < 0) {
      return -1;
    }
    int position = inner;
    for (int count = 0; count < MAX_BACKWARD_CODE_POINTS && position > from; count++) {
      long decoded = scanner.decodeBackward(position);
      int previous = InputScanner.position(decoded);
      if (previous < from) {
        break;
      }
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      if (!gap.contains(InputScanner.codePoint(decoded))) {
        return Math.max(from, scanner.retreatByCodePoints(position, prefixCodePoints));
      }
      position = previous;
    }
    return from;
  }
}
