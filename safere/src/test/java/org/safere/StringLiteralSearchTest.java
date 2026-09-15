// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link StringLiteralSearch} must be indistinguishable from {@link String#indexOf(String, int)}.
 * It is a pure throughput change, so every test here is a differential against the JDK.
 */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
final class StringLiteralSearchTest {

  /**
   * Filler long enough to push the remaining window past {@link
   * StringLiteralSearch#MIN_ANCHORED_WINDOW}, made of a character none of the literals here
   * contains so that padding cannot introduce a match.
   */
  private static final String FILLER = "z".repeat(StringLiteralSearch.MIN_ANCHORED_WINDOW);

  private static int anchored(String text, String literal, int fromIndex) {
    int offset = StringLiteralSearch.anchorOffset(literal);
    return StringLiteralSearch.indexOf(
        text, literal, offset, StringLiteralSearch.anchorAt(literal, offset), fromIndex);
  }

  private static void assertAgreesAt(String text, String literal, int from) {
    assertThat(anchored(text, literal, from))
        .as("literal=%s text=%s fromIndex=%s", literal, text, from)
        .isEqualTo(text.indexOf(literal, from));
  }

  /**
   * Runs the differential twice: once on {@code text} as given, and once on {@code text} followed
   * by enough filler that every start index tested leaves a window long enough to anchor. Without
   * the second pass the short-window guard would route these cases straight to the JDK and the
   * anchored loop would go untested.
   */
  private static void assertAgreesAtEveryStart(String text, String literal) {
    String padded = text + FILLER;
    for (int from = 0; from <= text.length() + 1; from++) {
      assertAgreesAt(text, literal, from);
      assertAgreesAt(padded, literal, from);
    }
  }

  @Test
  void agreesWithJdkOnRandomInputs() {
    // A small alphabet makes accidental near-matches common, which is what exercises the
    // candidate-verification path rather than just the scan.
    Random random = new Random(20260914L);
    String alphabet = "abcq:-/x";
    for (int trial = 0; trial < 3000; trial++) {
      int textLength = random.nextInt(40);
      StringBuilder text = new StringBuilder(textLength);
      for (int i = 0; i < textLength; i++) {
        text.append(alphabet.charAt(random.nextInt(alphabet.length())));
      }
      int literalLength = 1 + random.nextInt(5);
      StringBuilder literal = new StringBuilder(literalLength);
      for (int i = 0; i < literalLength; i++) {
        literal.append(alphabet.charAt(random.nextInt(alphabet.length())));
      }
      assertAgreesAtEveryStart(text.toString(), literal.toString());
    }
  }

  @Test
  void agreesWithJdkWhenTheAnchorIsDenseEnoughToExhaustTheStrikeBudget() {
    // The rarity model says 'q' is rare. This haystack says otherwise, which is exactly the case
    // adaptive defeat exists for: every position is a candidate and every one fails.
    String literal = "qx";
    assertThat(StringLiteralSearch.anchorOffset(literal))
        .isNotEqualTo(StringLiteralSearch.NO_ANCHOR);
    String dense = "q".repeat(5000);
    assertThat(anchored(dense, literal, 0)).isEqualTo(-1);
    assertThat(anchored(dense + "qx", literal, 0)).isEqualTo(dense.length());
    // A match beyond the point where the budget is exhausted must still be found. The prefix has
    // to clear MIN_ANCHORED_WINDOW, or the scan never anchors and the budget is never spent.
    int prefix = 4 * StringLiteralSearch.MIN_ANCHORED_WINDOW;
    assertThat(anchored("q".repeat(prefix) + "qx" + "q".repeat(200), literal, 0)).isEqualTo(prefix);
  }

  @Test
  void agreesWithJdkOnWindowsTooShortToAnchor() {
    // Below MIN_ANCHORED_WINDOW the search delegates rather than anchoring; it still has to be
    // indistinguishable from the JDK, including when the window shrinks only because fromIndex has
    // advanced into a long text.
    String literal = "qx";
    String text = "q".repeat(StringLiteralSearch.MIN_ANCHORED_WINDOW * 2) + "qx" + "q".repeat(50);
    for (int from = text.length() - 1; from >= 0; from -= 7) {
      assertAgreesAt(text, literal, from);
    }
    assertAgreesAt("q".repeat(StringLiteralSearch.MIN_ANCHORED_WINDOW - 1) + "qx", literal, 0);
  }

  @Test
  void agreesWithJdkOnOverlappingAndRepeatedLiterals() {
    assertAgreesAtEveryStart("aqaqaqaqaq", "aqa");
    assertAgreesAtEveryStart("qqqqqqqq", "qqq");
    assertAgreesAtEveryStart("://://://", "://");
    assertAgreesAtEveryStart("xxxxxxxxxx", "xy");
  }

  @Test
  void agreesWithJdkOnBoundaryConditions() {
    assertThat(anchored("", "qx", 0)).isEqualTo("".indexOf("qx", 0));
    assertThat(anchored("q", "qx", 0)).isEqualTo("q".indexOf("qx", 0));
    assertThat(anchored("qx", "qx", 0)).isEqualTo(0);
    assertThat(anchored("qx", "qx", 1)).isEqualTo(-1);
    assertThat(anchored("qx", "qx", 99)).isEqualTo(-1);
    assertThat(anchored("qx", "qx", -5)).isEqualTo("qx".indexOf("qx", -5));
    assertThat(anchored("abc", "abcd", 0)).isEqualTo(-1);
  }

  @Test
  void agreesWithJdkOnNonAsciiText() {
    // A non-ASCII literal has no anchor, but a non-ASCII haystack with an ASCII literal does, and
    // the haystack is UTF16-coded there, which is a different JDK kernel.
    assertAgreesAtEveryStart("日本語 q: 日本語", "q:");
    assertAgreesAtEveryStart("ünïcödé-qx-ünïcödé", "qx");
    assertAgreesAtEveryStart("日本語日本語", "日本");
    assertAgreesAtEveryStart("aa\uD83D\uDE00bb", "\uD83D\uDE00b");
  }

  @Test
  void declinesToAnchorWhereAnchoringCannotPayOff() {
    assertThat(StringLiteralSearch.anchorOffset(null)).isEqualTo(StringLiteralSearch.NO_ANCHOR);
    assertThat(StringLiteralSearch.anchorOffset("")).isEqualTo(StringLiteralSearch.NO_ANCHOR);
    // The JDK already routes a single-character needle to its own character kernel.
    assertThat(StringLiteralSearch.anchorOffset("q")).isEqualTo(StringLiteralSearch.NO_ANCHOR);
    // No ASCII character to anchor on; the rarity model has nothing to say.
    assertThat(StringLiteralSearch.anchorOffset("日本")).isEqualTo(StringLiteralSearch.NO_ANCHOR);
    // Every character is common enough to be a poisonous anchor.
    assertThat(StringLiteralSearch.anchorOffset("   ")).isEqualTo(StringLiteralSearch.NO_ANCHOR);
  }

  @Test
  void anchorsOnTheRarestCharacter() {
    assertThat(StringLiteralSearch.anchorOffset("id:")).isEqualTo(2);
    assertThat(StringLiteralSearch.anchorOffset("error:")).isEqualTo(5);
    assertThat(StringLiteralSearch.anchorAt("id:", 2)).isEqualTo(':');
  }

  @Test
  void declinedLiteralsStillAgreeWithJdk() {
    assertAgreesAtEveryStart("hello world", "o");
    assertAgreesAtEveryStart("a b  c   d", "  ");
    assertAgreesAtEveryStart("日本語テキスト", "テキ");
  }
}
