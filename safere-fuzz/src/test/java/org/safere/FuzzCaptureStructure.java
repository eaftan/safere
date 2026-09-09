// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayDeque;
import java.util.BitSet;

/** Test-only bridge to capture ancestry in the parsed, unsimplified pattern. */
public final class FuzzCaptureStructure {
  private FuzzCaptureStructure() {}

  /** Returns a fresh set of capture indices with a quantifier ancestor. */
  public static BitSet quantifiedGroups(Pattern pattern) {
    BitSet groups = new BitSet();
    ArrayDeque<Visit> pending = new ArrayDeque<>();
    pending.push(new Visit(pattern.ast(), false));
    while (!pending.isEmpty()) {
      Visit visit = pending.pop();
      Regexp node = visit.node();
      if (node.op == RegexpOp.CAPTURE && node.cap > 0 && visit.quantified()) {
        groups.set(node.cap);
      }
      boolean quantified =
          visit.quantified()
              || switch (node.op) {
                case STAR, PLUS, QUEST, REPEAT -> true;
                default -> false;
              };
      if (node.subs != null) {
        for (Regexp child : node.subs) {
          pending.push(new Visit(child, quantified));
        }
      }
    }
    return groups;
  }

  private record Visit(Regexp node, boolean quantified) {}
}
