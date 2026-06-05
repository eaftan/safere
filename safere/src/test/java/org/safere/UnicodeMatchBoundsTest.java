// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Unicode match bounds")
class UnicodeMatchBoundsTest {

  record Case(String label, String regex, int flags, String input) {
    @Override
    public String toString() {
      return label;
    }
  }

  record RegionCase(
      String label,
      String regex,
      int flags,
      String input,
      int regionStart,
      int regionEnd,
      boolean transparentBounds,
      boolean anchoringBounds) {
    @Override
    public String toString() {
      return label;
    }
  }

  record Outcome(boolean matched, int start, int end) {}

  static Stream<Case> dotAndAnchorBoundsMatchJdk() {
    return Stream.of(
        new Case("dot-empty-alternative", ".|", 352, "\ud85c"),
        new Case("dollar-anchor", "$", 332, "\udb7f\udb7f\ud85c\r\r"),
        new Case("dot-plus-surrogate", ".+", 358, "\ud828"),
        new Case(
            "dot-plus-anchor",
            ".+^",
            300,
            "\u0301\r\u2028\u0301\u0301\u2028\u0301\u0301\ud85c\u2028a"),
        new Case("dot-unpaired-high-surrogate", ".", Pattern.DOTALL, "\ud83d"),
        new Case("dot-unpaired-low-surrogate", ".", Pattern.DOTALL, "\ude00"),
        new Case("dot-valid-surrogate-pair", ".", Pattern.DOTALL, "\ud83d\ude00"));
  }

  static Stream<RegionCase> scalarRegionBoundsMatchJdk() {
    return Stream.of(
        new RegionCase(
            "dot-region-ends-after-high-surrogate",
            ".",
            Pattern.DOTALL,
            "\ud83d\ude00",
            0,
            1,
            false,
            true),
        new RegionCase(
            "negated-class-region-ends-after-high-surrogate",
            "[^a]",
            0,
            "\ud83d\ude00",
            0,
            1,
            false,
            true),
        new RegionCase(
            "any-class-region-ends-after-high-surrogate",
            "[\\s\\S]",
            0,
            "\ud83d\ude00",
            0,
            1,
            true,
            true),
        new RegionCase(
            "non-digit-region-ends-after-high-surrogate",
            "\\D",
            0,
            "\ud83d\ude00",
            0,
            1,
            false,
            false),
        new RegionCase(
            "surrogate-category-region-starts-at-low-surrogate",
            "\\p{Cs}",
            0,
            "\ud83d\ude00",
            1,
            2,
            false,
            true),
        new RegionCase(
            "surrogate-category-complement-on-lone-surrogate",
            "\\P{Cs}",
            0,
            "\ud83d",
            0,
            1,
            false,
            true),
        new RegionCase(
            "find-does-not-continue-at-high-surrogate-after-low-surrogate",
            ".",
            Pattern.DOTALL,
            "\ud83d\udc4d\ud83c\udffd",
            1,
            3,
            false,
            true),
        new RegionCase(
            "end-anchor-finds-region-end-after-split-high-surrogate",
            "$",
            0,
            "\ud83d\ude00",
            0,
            1,
            false,
            true),
        new RegionCase(
            "end-anchor-finds-transparent-region-end-after-split-high-surrogate",
            "$",
            0,
            "\ud83d\ude00",
            0,
            1,
            true,
            true),
        new RegionCase(
            "start-anchor-does-not-see-region-start-without-anchoring-bounds",
            "^a",
            0,
            "ba",
            1,
            2,
            false,
            false),
        new RegionCase(
            "end-anchor-does-not-see-region-end-without-anchoring-bounds",
            "a$",
            0,
            "ab",
            0,
            1,
            false,
            false),
        new RegionCase(
            "anchored-single-letter-does-not-match-region-without-anchoring-bounds",
            "^a$",
            Pattern.MULTILINE,
            "a\u0301",
            0,
            1,
            true,
            false),
        new RegionCase(
            "multiline-start-anchor-does-not-match-empty-region-inside-surrogate-pair",
            "^",
            Pattern.MULTILINE,
            "\ud83d\ude00",
            1,
            1,
            false,
            true),
        new RegionCase(
            "multiline-anchored-empty-does-not-match-empty-region-inside-surrogate-pair",
            "^$",
            Pattern.MULTILINE,
            "x\ud83d\ude00y",
            2,
            2,
            true,
            true),
        new RegionCase(
            "non-word-boundary-dot-does-not-consume-past-split-surrogate-region-end",
            "\\B.",
            Pattern.DOTALL,
            "\ud83d\ude00",
            0,
            1,
            false,
            true),
        new RegionCase(
            "non-word-boundary-any-class-does-not-consume-past-split-surrogate-region-end",
            "\\B[\\s\\S]",
            0,
            "\ud83d\ude00",
            0,
            1,
            true,
            true));
  }

  @ParameterizedTest
  @MethodSource
  @DisplayName("dot and anchor bounds match java.util.regex")
  void dotAndAnchorBoundsMatchJdk(Case c) {
    Pattern safePattern = Pattern.compile(c.regex(), c.flags());
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(c.regex(), c.flags());

    assertThat(findOutcome(safePattern.matcher(c.input())))
        .as("%s find", c.label())
        .isEqualTo(findOutcome(jdkPattern.matcher(c.input())));
    assertThat(lookingAtOutcome(safePattern.matcher(c.input())))
        .as("%s lookingAt", c.label())
        .isEqualTo(lookingAtOutcome(jdkPattern.matcher(c.input())));
    assertThat(matchesOutcome(safePattern.matcher(c.input())))
        .as("%s matches", c.label())
        .isEqualTo(matchesOutcome(jdkPattern.matcher(c.input())));
  }

  record DivergentRegionCase(RegionCase c, String expectedSafeTrace) {
    @Override
    public String toString() {
      return c.label();
    }
  }

  static Stream<DivergentRegionCase> scalarRegionBoundsIntentionallyDivergeFromJdk() {
    return Stream.of(
        new DivergentRegionCase(
            new RegionCase(
                "non-word-boundary-finds-utf16-offset-inside-supplementary-scalar",
                "\\B",
                0,
                "x\ud83d\ude00y",
                1,
                4,
                true,
                true),
            "matches=false,lookingAt=false,find0=false"),
        new DivergentRegionCase(
            new RegionCase(
                "non-word-boundary-alternative-remains-leftmost-inside-supplementary-scalar",
                "\\B|$",
                0,
                "x\ud83d\ude00y",
                1,
                4,
                true,
                false),
            "matches=false,lookingAt=false,find0=true@4-4,find1=false"),
        new DivergentRegionCase(
            new RegionCase(
                "non-word-boundary-consuming-alternative-remains-leftmost-inside-supplementary-scalar",
                "\\B|y",
                0,
                "x\ud83d\ude00y",
                0,
                4,
                false,
                true),
            "matches=false,lookingAt=false,find0=true@3-4,find1=false"),
        new DivergentRegionCase(
            new RegionCase(
                "non-word-boundary-consuming-alternative-finds-utf16-offset-before-later-miss",
                "\\B|a",
                0,
                "x\ud83d\ude00y",
                0,
                4,
                false,
                true),
            "matches=false,lookingAt=false,find0=false"),
        new DivergentRegionCase(
            new RegionCase(
                "consuming-alternative-before-non-word-boundary-still-finds-leftmost-boundary",
                "y|\\B",
                0,
                "x\ud83d\ude00y",
                0,
                4,
                false,
                true),
            "matches=false,lookingAt=false,find0=true@3-4,find1=false"));
  }

  @ParameterizedTest
  @MethodSource
  @DisplayName("scalar region bounds intentionally diverge from java.util.regex")
  void scalarRegionBoundsIntentionallyDivergeFromJdk(DivergentRegionCase dc) {
    Pattern safePattern = Pattern.compile(dc.c.regex(), dc.c.flags());
    assertThat(trace(safePattern.matcher(dc.c.input()), dc.c))
        .as("%s trace", dc.c.label())
        .isEqualTo(dc.expectedSafeTrace);
  }

  @ParameterizedTest
  @MethodSource
  @DisplayName("scalar region bounds match java.util.regex")
  void scalarRegionBoundsMatchJdk(RegionCase c) {
    Pattern safePattern = Pattern.compile(c.regex(), c.flags());
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(c.regex(), c.flags());

    assertThat(trace(safePattern.matcher(c.input()), c))
        .as("%s trace", c.label())
        .isEqualTo(trace(jdkPattern.matcher(c.input()), c));
  }

  private static String trace(Matcher matcher, RegionCase c) {
    matcher
        .region(c.regionStart(), c.regionEnd())
        .useTransparentBounds(c.transparentBounds())
        .useAnchoringBounds(c.anchoringBounds());
    StringBuilder trace = new StringBuilder();
    appendOutcome(trace, "matches", matchesOutcome(matcher));
    matcher
        .reset(c.input())
        .region(c.regionStart(), c.regionEnd())
        .useTransparentBounds(c.transparentBounds())
        .useAnchoringBounds(c.anchoringBounds());
    appendOutcome(trace, "lookingAt", lookingAtOutcome(matcher));
    matcher
        .reset(c.input())
        .region(c.regionStart(), c.regionEnd())
        .useTransparentBounds(c.transparentBounds())
        .useAnchoringBounds(c.anchoringBounds());
    for (int i = 0; i < 4; i++) {
      Outcome outcome = findOutcome(matcher);
      appendOutcome(trace, "find" + i, outcome);
      if (!outcome.matched()) {
        break;
      }
    }
    return trace.toString();
  }

  private static String trace(java.util.regex.Matcher matcher, RegionCase c) {
    matcher
        .region(c.regionStart(), c.regionEnd())
        .useTransparentBounds(c.transparentBounds())
        .useAnchoringBounds(c.anchoringBounds());
    StringBuilder trace = new StringBuilder();
    appendOutcome(trace, "matches", matchesOutcome(matcher));
    matcher
        .reset(c.input())
        .region(c.regionStart(), c.regionEnd())
        .useTransparentBounds(c.transparentBounds())
        .useAnchoringBounds(c.anchoringBounds());
    appendOutcome(trace, "lookingAt", lookingAtOutcome(matcher));
    matcher
        .reset(c.input())
        .region(c.regionStart(), c.regionEnd())
        .useTransparentBounds(c.transparentBounds())
        .useAnchoringBounds(c.anchoringBounds());
    for (int i = 0; i < 4; i++) {
      Outcome outcome = findOutcome(matcher);
      appendOutcome(trace, "find" + i, outcome);
      if (!outcome.matched()) {
        break;
      }
    }
    return trace.toString();
  }

  private static void appendOutcome(StringBuilder trace, String operation, Outcome outcome) {
    if (!trace.isEmpty()) {
      trace.append(',');
    }
    trace.append(operation).append('=').append(outcome.matched());
    if (outcome.matched()) {
      trace.append('@').append(outcome.start()).append('-').append(outcome.end());
    }
  }

  private static Outcome findOutcome(Matcher matcher) {
    boolean matched = matcher.find();
    return new Outcome(matched, matched ? matcher.start() : -1, matched ? matcher.end() : -1);
  }

  private static Outcome findOutcome(java.util.regex.Matcher matcher) {
    boolean matched = matcher.find();
    return new Outcome(matched, matched ? matcher.start() : -1, matched ? matcher.end() : -1);
  }

  private static Outcome lookingAtOutcome(Matcher matcher) {
    boolean matched = matcher.lookingAt();
    return new Outcome(matched, matched ? matcher.start() : -1, matched ? matcher.end() : -1);
  }

  private static Outcome lookingAtOutcome(java.util.regex.Matcher matcher) {
    boolean matched = matcher.lookingAt();
    return new Outcome(matched, matched ? matcher.start() : -1, matched ? matcher.end() : -1);
  }

  private static Outcome matchesOutcome(Matcher matcher) {
    boolean matched = matcher.matches();
    return new Outcome(matched, matched ? matcher.start() : -1, matched ? matcher.end() : -1);
  }

  private static Outcome matchesOutcome(java.util.regex.Matcher matcher) {
    boolean matched = matcher.matches();
    return new Outcome(matched, matched ? matcher.start() : -1, matched ? matcher.end() : -1);
  }
}
