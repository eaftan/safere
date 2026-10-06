// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import java.util.List;
import org.safere.Pattern;

/** The documented case-equivalence model, independent of JDK regex outcomes. */
final class CaseFamilyChecks {
  private CaseFamilyChecks() {}

  static void assertCaseFamilyClosureSafeRe(FuzzedDataProvider data) {
    String family = data.pickValue(List.of("Iiİı", "KkK", "Σσς", "ÅåÅ", "ΩωΩ", "ßẞ", "Θθϑϴ", "ﬅﬆ"));
    int[] members = family.codePoints().toArray();
    int source = members[data.consumeInt(0, members.length - 1)];
    int target = members[data.consumeInt(0, members.length - 1)];
    String literal = "\\x{" + Integer.toHexString(source) + "}";
    String regex =
        switch (data.consumeInt(0, 4)) {
          case 0 -> literal;
          case 1 -> "[" + literal + "]";
          case 2 -> "[" + literal + "-" + literal + "]";
          case 3 -> "[^" + literal + "]";
          default -> "[^" + literal + "-" + literal + "]";
        };
    boolean actual =
        Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
            .matcher(new String(Character.toChars(target)))
            .matches();
    if (actual == regex.startsWith("[^")) {
      throw new AssertionError(
          "SafeRE case family closure: " + regex + " U+" + Integer.toHexString(target));
    }
  }
}
