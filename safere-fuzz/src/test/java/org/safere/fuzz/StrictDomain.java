// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/** Bounded ASCII grammar shared by enumeration and coverage-guided sampling. */
final class StrictDomain {
  static final List<String> ATOMS =
      List.of("", "a", "b", "ab", "[ab]", "[^a]", "[a-c]", "\\.", ".");
  static final List<String> QUANTIFIERS =
      List.of("?", "??", "*", "*?", "+", "+?", "{0,2}", "{1,3}?");
  static final int MAX_DEPTH = 3;
  static final int MAX_INPUT = 24;

  private StrictDomain() {}

  record Sample(String regex, String witness) {}

  static Sample generate(FuzzedDataProvider data) {
    return generate(bound -> data.consumeInt(0, bound - 1));
  }

  static Sample generate(IntUnaryOperator choice) {
    return generate(choice, MAX_DEPTH);
  }

  // Recursion is statically bounded by MAX_DEPTH, independent of fuzz input.
  private static Sample generate(IntUnaryOperator choice, int depth) {
    int operation = depth == 0 ? 0 : choice.applyAsInt(4);
    if (operation == 0) {
      int atom = choice.applyAsInt(ATOMS.size());
      return new Sample(
          ATOMS.get(atom), List.of("", "a", "b", "ab", "a", "b", "a", ".", "a").get(atom));
    }
    Sample left = generate(choice, depth - 1);
    if (operation == 3) {
      String quantifier = QUANTIFIERS.get(choice.applyAsInt(QUANTIFIERS.size()));
      int copies = quantifier.startsWith("+") || quantifier.startsWith("{1") ? 1 : 0;
      if (!quantifier.startsWith("?")) {
        copies += choice.applyAsInt(2);
      }
      return new Sample(quantify(left.regex(), quantifier), left.witness().repeat(copies));
    }
    Sample right = generate(choice, depth - 1);
    if (operation == 1) {
      return new Sample(concatenate(left.regex(), right.regex()), left.witness() + right.witness());
    }
    return new Sample(
        alternate(left.regex(), right.regex()),
        choice.applyAsInt(2) == 0 ? left.witness() : right.witness());
  }

  static String input(FuzzedDataProvider data) {
    StringBuilder input = new StringBuilder();
    int length = data.consumeInt(0, MAX_INPUT);
    for (int index = 0; index < length; index++) {
      input.append(
          data.pickValue(
              List.of("a", "b", "c", ".", "!", "é", "中", "😀", "\ud800", "\udc00", "\n")));
    }
    return input.toString();
  }

  static String concatenate(String left, String right) {
    return "(?:" + left + ")(?:" + right + ")";
  }

  static String alternate(String left, String right) {
    return "(?:" + left + "|" + right + ")";
  }

  static String quantify(String expression, String quantifier) {
    return "(?:" + expression + ")" + quantifier;
  }

  static List<String> smallPatterns() {
    List<String> result = new ArrayList<>(ATOMS);
    for (String atom : ATOMS) {
      for (String quantifier : QUANTIFIERS) {
        result.add(quantify(atom, quantifier));
      }
      for (String right : ATOMS) {
        result.add(concatenate(atom, right));
        result.add(alternate(atom, right));
      }
    }
    return List.copyOf(result);
  }
}
