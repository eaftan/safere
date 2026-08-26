// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates structural analysis of a regular expression AST in a single bottom-up pass using the
 * stack-safe {@link Walker}.
 */
record AstAnalysis(
    boolean hasLazy,
    boolean hasAlt,
    boolean hasNullableAlt,
    boolean canMatchEmpty,
    boolean hasUserCaptures,
    int minMatchLength,
    Map<String, Integer> namedGroups) {

  static AstAnalysis analyze(Regexp re) {
    if (re == null) {
      return new AstAnalysis(false, false, false, true, false, 0, Map.of());
    }
    Map<String, Integer> named = new HashMap<>();
    AnalysisNode root =
        new AstAnalysisWalker(named)
            .walk(re, new AnalysisNode(false, false, false, true, false, 0));
    return new AstAnalysis(
        root.hasLazy,
        root.hasAlt,
        root.hasNullableAlt,
        root.canMatchEmpty,
        root.hasUserCaptures,
        root.minMatchLength,
        named.isEmpty() ? Map.of() : Collections.unmodifiableMap(named));
  }

  private record AnalysisNode(
      boolean hasLazy,
      boolean hasAlt,
      boolean hasNullableAlt,
      boolean canMatchEmpty,
      boolean hasUserCaptures,
      int minMatchLength) {}

  private static final class AstAnalysisWalker extends Walker<AnalysisNode> {
    private final Map<String, Integer> named;

    AstAnalysisWalker(Map<String, Integer> named) {
      this.named = named;
    }

    @Override
    protected AnalysisNode shortVisit(Regexp re, AnalysisNode parentArg) {
      return new AnalysisNode(false, false, false, true, false, 0);
    }

    @Override
    protected AnalysisNode postVisit(
        Regexp re, AnalysisNode parentArg, AnalysisNode preArg, List<AnalysisNode> childArgs) {
      if (re.op == RegexpOp.CAPTURE && re.name != null && !re.name.isEmpty()) {
        named.put(re.name, re.cap);
      }

      boolean anyLazy = re.nonGreedy();
      boolean anyAlt = re.op == RegexpOp.ALTERNATE;
      boolean anyNullableAlt = false;
      boolean anyUserCaptures = re.op == RegexpOp.CAPTURE && re.cap > 0;

      for (AnalysisNode child : childArgs) {
        if (child.hasLazy) {
          anyLazy = true;
        }
        if (child.hasAlt) {
          anyAlt = true;
        }
        if (child.hasNullableAlt) {
          anyNullableAlt = true;
        }
        if (child.hasUserCaptures) {
          anyUserCaptures = true;
        }
      }

      boolean canMatchEmpty;
      int minMatchLength;

      switch (re.op) {
        case NO_MATCH -> {
          canMatchEmpty = false;
          minMatchLength = Integer.MAX_VALUE / 2;
        }
        case EMPTY_MATCH,
            BEGIN_LINE,
            END_LINE,
            BEGIN_TEXT,
            END_TEXT,
            WORD_BOUNDARY,
            NO_WORD_BOUNDARY,
            GRAPHEME_CLUSTER_BOUNDARY,
            HAVE_MATCH -> {
          canMatchEmpty = true;
          minMatchLength = 0;
        }
        case LITERAL -> {
          int count = re.rune != 0 ? Character.charCount(re.rune) : 0;
          canMatchEmpty = count == 0;
          minMatchLength = count;
        }
        case LITERAL_STRING -> {
          int count = 0;
          if (re.runes != null) {
            for (int r : re.runes) {
              count += Character.charCount(r);
            }
          }
          canMatchEmpty = count == 0;
          minMatchLength = count;
        }
        case CHAR_CLASS, ANY_CHAR, ANY_BYTE, GRAPHEME_CLUSTER -> {
          canMatchEmpty = false;
          minMatchLength = 1;
        }
        case STAR, QUEST -> {
          canMatchEmpty = true;
          minMatchLength = 0;
        }
        case PLUS -> {
          canMatchEmpty = !childArgs.isEmpty() && childArgs.getFirst().canMatchEmpty;
          minMatchLength = !childArgs.isEmpty() ? childArgs.getFirst().minMatchLength : 0;
        }
        case REPEAT -> {
          int subMin = !childArgs.isEmpty() ? childArgs.getFirst().minMatchLength : 0;
          minMatchLength =
              (int) Math.min((long) subMin * Math.max(0, re.min), Integer.MAX_VALUE / 2);
          canMatchEmpty =
              re.min == 0 || (!childArgs.isEmpty() && childArgs.getFirst().canMatchEmpty);
        }
        case CAPTURE, NON_CAPTURE -> {
          canMatchEmpty = !childArgs.isEmpty() && childArgs.getFirst().canMatchEmpty;
          minMatchLength = !childArgs.isEmpty() ? childArgs.getFirst().minMatchLength : 0;
        }
        case CONCAT -> {
          boolean allEmpty = true;
          long sum = 0;
          for (AnalysisNode child : childArgs) {
            if (!child.canMatchEmpty) {
              allEmpty = false;
            }
            sum += child.minMatchLength;
          }
          canMatchEmpty = allEmpty;
          minMatchLength = (int) Math.min(sum, Integer.MAX_VALUE / 2);
        }
        case ALTERNATE -> {
          boolean anyChildEmpty = false;
          int min = Integer.MAX_VALUE / 2;
          for (AnalysisNode child : childArgs) {
            if (child.canMatchEmpty) {
              anyChildEmpty = true;
            }
            min = Math.min(min, child.minMatchLength);
          }
          canMatchEmpty = anyChildEmpty || childArgs.isEmpty();
          minMatchLength = min == Integer.MAX_VALUE / 2 ? 0 : min;
          if (anyChildEmpty) {
            anyNullableAlt = true;
          }
        }
        default -> {
          canMatchEmpty = false;
          minMatchLength = 0;
        }
      }

      return new AnalysisNode(
          anyLazy, anyAlt, anyNullableAlt, canMatchEmpty, anyUserCaptures, minMatchLength);
    }
  }
}
