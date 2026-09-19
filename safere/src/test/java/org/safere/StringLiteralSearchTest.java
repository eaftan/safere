// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Tag;
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
  void agreesWithJdkWhenTheAnchorRecursJustInsideTheDensityStride() {
    // The shape the strike charge is calibrated on: an anchor the rarity model rates highly, which
    // nevertheless recurs every 46 characters -- the stride measured for this literal on a log
    // line, and inside MIN_DENSITY_STRIDE -- so every observation is a dense one and none of them
    // verifies. How soon the scan concedes is not visible in the result, only in the benchmark;
    // what is pinned here is that conceding still finds everything the JDK finds.
    String literal = "error:[";
    int offset = StringLiteralSearch.anchorOffset(literal);
    assertThat(offset).isNotEqualTo(StringLiteralSearch.NO_ANCHOR);
    String unit = StringLiteralSearch.anchorAt(literal, offset) + "y".repeat(45);
    String noise = unit.repeat(64);
    assertThat(noise.length()).isGreaterThan(StringLiteralSearch.MIN_ANCHORED_WINDOW);
    assertThat(anchored(noise, literal, 0)).isEqualTo(-1);
    assertThat(anchored(noise + literal, literal, 0)).isEqualTo(noise.length());
    String surrounded = noise + literal + noise;
    for (int from = 0; from < surrounded.length(); from += 137) {
      assertAgreesAt(surrounded, literal, from);
    }
  }

  @Test
  void agreesWithJdkWhenTheAnchorClumpsAndThenThinsOut() {
    // Repayment has to keep a mostly sparse anchor alive, so a handful of dense observations
    // followed by a long sparse run must not be treated the same as a uniformly dense anchor.
    String literal = "error:[";
    char anchor = StringLiteralSearch.anchorAt(literal, StringLiteralSearch.anchorOffset(literal));
    String text =
        ((anchor + "y".repeat(9)).repeat(5) + (anchor + "y".repeat(400)).repeat(3)).repeat(4);
    assertThat(text.length()).isGreaterThan(StringLiteralSearch.MIN_ANCHORED_WINDOW);
    assertThat(anchored(text, literal, 0)).isEqualTo(-1);
    assertAgreesAt(text + literal, literal, 0);
    assertAgreesAt(text + literal + text, literal, 0);
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

  /**
   * Literals whose rarest character is also their last one, searched over haystacks that carry
   * false candidates — an anchor occurrence that is not part of a match.
   *
   * <p>Both halves matter, and the rest of the suite has neither. When the anchor is the last
   * character, the candidate pre-check re-reads the byte the anchor scan just returned, so it
   * always passes and every candidate runs a full verification. A haystack whose only anchor
   * occurrences are real matches then never reaches that verification with anything to reject.
   */
  @Test
  void agreesWithJdkWhereTheAnchorIsTheLiteralsLastCharacter() {
    for (String literal : new String[] {"id:", "record:", "users/"}) {
      assertThat(StringLiteralSearch.anchorOffset(literal)).isEqualTo(literal.length() - 1);
    }
    assertAgreesAtEveryStart("other:x id:7 more:y", "id:");
    assertAgreesAtEveryStart("tag:a record:b tag:c", "record:");
    assertAgreesAtEveryStart("/a/ users/ /b/", "users/");
    // The anchor present without the rest of the literal, and the rest of the literal present
    // without the anchor.
    assertAgreesAtEveryStart("rrrr: record:", "record:");
    assertAgreesAtEveryStart("recorx: record:", "record:");
    // A match at the very start of the text, and one whose anchor is its last character.
    assertAgreesAtEveryStart("record: trailing", "record:");
    assertAgreesAtEveryStart("leading record:", "record:");
  }

  @Test
  void declinedLiteralsStillAgreeWithJdk() {
    assertAgreesAtEveryStart("hello world", "o");
    assertAgreesAtEveryStart("a b  c   d", "  ");
    assertAgreesAtEveryStart("日本語テキスト", "テキ");
  }

  @Test
  void routesOnlyOneCharacterAsciiLiteralsToTheCharacterKernel() {
    assertThat(StringLiteralSearch.singleAsciiChar("-")).isEqualTo('-');
    assertThat(StringLiteralSearch.singleAsciiChar("\n")).isEqualTo('\n');
    assertThat(StringLiteralSearch.singleAsciiChar("\0")).isEqualTo(0);
    // 0x7f is the last character the two kernels agree on.
    assertThat(StringLiteralSearch.singleAsciiChar("\u007f")).isEqualTo(0x7f);

    assertThat(StringLiteralSearch.singleAsciiChar(null))
        .isEqualTo(StringLiteralSearch.NOT_SINGLE_ASCII);
    assertThat(StringLiteralSearch.singleAsciiChar(""))
        .isEqualTo(StringLiteralSearch.NOT_SINGLE_ASCII);
    assertThat(StringLiteralSearch.singleAsciiChar("ab"))
        .isEqualTo(StringLiteralSearch.NOT_SINGLE_ASCII);
    // Above ASCII String.indexOf(int, int) matches by code point, so it is a different search.
    assertThat(StringLiteralSearch.singleAsciiChar("\u0080"))
        .isEqualTo(StringLiteralSearch.NOT_SINGLE_ASCII);
    assertThat(StringLiteralSearch.singleAsciiChar("é"))
        .isEqualTo(StringLiteralSearch.NOT_SINGLE_ASCII);
    // One code point, but two chars, so not one character by the length test either.
    assertThat(StringLiteralSearch.singleAsciiChar("\uD83D\uDE00"))
        .isEqualTo(StringLiteralSearch.NOT_SINGLE_ASCII);
  }

  @Test
  void plannedSearchAgreesWithJdk() {
    // Single characters, which is what the dispatch is for.
    assertPlannedAgreesAtEveryStart("hello world", "o");
    assertPlannedAgreesAtEveryStart("---a---", "-");
    assertPlannedAgreesAtEveryStart("no hit here", "q");
    assertPlannedAgreesAtEveryStart("", "q");
    assertPlannedAgreesAtEveryStart("line\nbreak", "\n");
    // A haystack the JDK stores as UTF16, where the two kernels index differently internally.
    assertPlannedAgreesAtEveryStart("日本語-日本語", "-");
    // Characters the dispatch declines, which must still reach the anchored path unchanged.
    assertPlannedAgreesAtEveryStart("ünïcödé", "é");
    assertPlannedAgreesAtEveryStart("a q: b q: c", "q:");
    assertPlannedAgreesAtEveryStart("日本語テキスト", "テキ");
  }

  @Test
  void plannedSearchAgreesWithJdkOnOutOfRangeStarts() {
    String text = "a-b-c";
    for (String literal : new String[] {"-", "q", "-b"}) {
      for (int from : new int[] {Integer.MIN_VALUE, -7, -1, 6, 99, Integer.MAX_VALUE}) {
        assertThat(planned(text, literal, from))
            .as("literal=%s fromIndex=%s", literal, from)
            .isEqualTo(text.indexOf(literal, from));
      }
    }
  }

  @Test
  void plannedSearchAgreesWithJdkOnRandomInputs() {
    Random random = new Random(20260918L);
    String alphabet = "abcq:-/x";
    for (int trial = 0; trial < 3000; trial++) {
      StringBuilder text = new StringBuilder();
      for (int i = random.nextInt(40); i > 0; i--) {
        text.append(alphabet.charAt(random.nextInt(alphabet.length())));
      }
      String literal = String.valueOf(alphabet.charAt(random.nextInt(alphabet.length())));
      assertPlannedAgreesAtEveryStart(text.toString(), literal);
    }
  }

  /**
   * The dispatched and undispatched kernels have to record identical work. {@code
   * ScanDispatchAudit} compares recorded work against the path taken, so a search that charges
   * differently depending on which kernel its plan picked would show up there as a bug somewhere
   * else entirely.
   */
  @Test
  @Tag("work-counter")
  void chargesTheSameWorkWhicheverKernelThePlanChose() {
    String text = "abc-def-ghi" + FILLER + "-tail";
    for (String literal : new String[] {"-", "a", "z", "\n"}) {
      for (int from :
          new int[] {-3, 0, 1, 5, text.length() - 1, text.length(), text.length() + 4}) {
        long dispatched = WorkCounter.countForTesting(() -> planned(text, literal, from));
        long undispatched = WorkCounter.countForTesting(() -> anchored(text, literal, from));
        assertThat(dispatched).as("literal=%s fromIndex=%s", literal, from).isEqualTo(undispatched);
      }
    }
  }

  private static int planned(String text, String literal, int fromIndex) {
    int offset = StringLiteralSearch.anchorOffset(literal);
    return StringLiteralSearch.indexOfPlanned(
        text,
        literal,
        offset,
        StringLiteralSearch.anchorAt(literal, offset),
        StringLiteralSearch.singleAsciiChar(literal),
        fromIndex);
  }

  private static void assertPlannedAgreesAtEveryStart(String text, String literal) {
    String padded = text + FILLER;
    for (int from = 0; from <= text.length() + 1; from++) {
      assertThat(planned(text, literal, from))
          .as("literal=%s text=%s fromIndex=%s", literal, text, from)
          .isEqualTo(text.indexOf(literal, from));
      assertThat(planned(padded, literal, from))
          .as("literal=%s paddedText=%s fromIndex=%s", literal, padded, from)
          .isEqualTo(padded.indexOf(literal, from));
    }
  }
}
