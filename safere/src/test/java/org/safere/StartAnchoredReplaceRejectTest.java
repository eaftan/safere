// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for the start-anchored fast reject in {@code Matcher.replaceImpl}.
 *
 * <p>A start-anchored pattern skips the unanchored reject prefilter, so before this fast path such
 * patterns got no whole-input rejection at all from {@code replaceAll} and {@code replaceFirst}.
 * These tests pin the behaviour that the fast path must preserve exactly.
 */
class StartAnchoredReplaceRejectTest {

  /**
   * Representative start-anchored scrubbers: both compile to {@code anchorStart = true} with a
   * single-character {@code anchoredPrefix} and a null {@code rejectPrefilter}, which is the exact
   * shape the fast path targets.
   */
  private static final String CITATION_PARENS =
      "^\\([Ss]ource:\\s*\\d+(?:\\s*,\\s*(?:[Ss]ource:\\s*)?\\d+)*\\s*\\)\\s*";

  private static final String CITATION_BRACKETS =
      "^\\[[Ss]ource:\\s*\\d+(?:\\s*,\\s*(?:[Ss]ource:\\s*)?\\d+)*\\s*\\]\\s*";

  @Test
  void rejectedInputIsReturnedUnchangedAndIdenticalToTheJdk() {
    List<String> inputs =
        List.of(
            "",
            "x",
            "(",
            "[",
            "No citation marker here at all.",
            "(Not a source marker)",
            "[Nope]",
            " (Source: 1) leading space defeats the anchor",
            "\n(Source: 1) leading newline defeats the anchor");

    for (String regex : List.of(CITATION_PARENS, CITATION_BRACKETS)) {
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);
      Pattern safere = Pattern.compile(regex);
      for (String input : inputs) {
        assertThat(safere.matcher(input).replaceAll("X"))
            .as("replaceAll %s on %s", regex, input)
            .isEqualTo(jdk.matcher(input).replaceAll("X"));
        assertThat(safere.matcher(input).replaceFirst("X"))
            .as("replaceFirst %s on %s", regex, input)
            .isEqualTo(jdk.matcher(input).replaceFirst("X"));
      }
    }
  }

  @Test
  void matchingInputStillReplacesCorrectly() {
    List<String> inputs =
        List.of(
            "(Source: 1) body",
            "(Source: 1, 2) body",
            "(Source: 1, Source: 2)   body",
            "(source: 42) body",
            "(Source: 1) (Source: 2) body");

    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(CITATION_PARENS);
    Pattern safere = Pattern.compile(CITATION_PARENS);
    for (String input : inputs) {
      assertThat(safere.matcher(input).replaceAll("X"))
          .as("replaceAll on %s", input)
          .isEqualTo(jdk.matcher(input).replaceAll("X"));
      assertThat(safere.matcher(input).replaceFirst("X"))
          .as("replaceFirst on %s", input)
          .isEqualTo(jdk.matcher(input).replaceFirst("X"));
    }
  }

  @Test
  void anchoredCharacterClassPrefixRejectsAndMatches() {
    Pattern pattern = Pattern.compile("^[0-9]+ items");
    assertThat(pattern.matcher("no digits").replaceAll("X")).isEqualTo("no digits");
    assertThat(pattern.matcher("12 items left").replaceAll("X")).isEqualTo("X left");
  }

  @Test
  void multilineStartAnchorIsUnaffected() {
    // (?m)^ is a begin-line assertion, not prog().anchorStart(); matches on later lines must
    // still be found and replaced.
    Pattern pattern = Pattern.compile("(?m)^\\(Source: \\d+\\)\\s*");
    assertThat(pattern.matcher("first\n(Source: 7) second").replaceAll(""))
        .isEqualTo("first\nsecond");
    assertThat(pattern.matcher("first\nsecond").replaceAll("X")).isEqualTo("first\nsecond");
  }

  @Test
  void rejectedReplaceLeavesTheMatcherWithNoMatch() {
    // The fast path must leave the matcher in the same observable state as the slow path: no
    // match available, and the same state the sibling unanchored reject-prefilter arm produces.
    Matcher anchored = Pattern.compile(CITATION_PARENS).matcher("no marker");
    assertThat(anchored.replaceAll("X")).isEqualTo("no marker");
    assertThatThrownBy(anchored::group).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> anchored.start()).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> anchored.end()).isInstanceOf(IllegalStateException.class);

    Matcher unanchored = Pattern.compile("zqxjv[0-9]+").matcher("no marker");
    assertThat(unanchored.replaceAll("X")).isEqualTo("no marker");
    assertThatThrownBy(unanchored::group).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void matcherRemainsReusableAfterARejectedReplace() {
    Matcher matcher = Pattern.compile(CITATION_PARENS).matcher("no marker");
    assertThat(matcher.replaceAll("X")).isEqualTo("no marker");

    // Still usable for a fresh search, and for a fresh input.
    assertThat(matcher.find()).isFalse();
    matcher.reset("(Source: 3) body");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group()).isEqualTo("(Source: 3) ");
    assertThat(matcher.replaceAll("X")).isEqualTo("Xbody");
  }

  @Test
  void regionIsResetByReplaceJustAsBefore() {
    // replaceImpl calls reset(), which clears any region, so a region set beforehand does not
    // narrow the anchored check. Pin that this is still true.
    Matcher matcher = Pattern.compile(CITATION_PARENS).matcher("zz(Source: 1) body");
    matcher.region(2, matcher.regionEnd());
    assertThat(matcher.replaceAll("X")).isEqualTo("zz(Source: 1) body");
  }

  @Test
  void functionOverloadsAgreeWithStringOverloads() {
    // The Function overloads route through find(), which already had the anchored reject. The
    // String overloads bypassed find(); after the fix both families must agree.
    for (String regex : List.of(CITATION_PARENS, CITATION_BRACKETS)) {
      for (String input : List.of("no marker", "(Source: 1) body", "[Source: 2] body")) {
        Pattern pattern = Pattern.compile(regex);
        assertThat(pattern.matcher(input).replaceAll(r -> "X"))
            .as("replaceAll(Function) %s on %s", regex, input)
            .isEqualTo(pattern.matcher(input).replaceAll("X"));
        assertThat(pattern.matcher(input).replaceFirst(r -> "X"))
            .as("replaceFirst(Function) %s on %s", regex, input)
            .isEqualTo(pattern.matcher(input).replaceFirst("X"));
      }
    }
  }

  @Test
  void replacementIsStillValidatedBeforeRejection() {
    // Objects.requireNonNull(replacement) runs before the reject arm, so a null replacement must
    // still throw even when the input cannot possibly match.
    Matcher matcher = Pattern.compile(CITATION_PARENS).matcher("no marker");
    assertThatThrownBy(() -> matcher.replaceAll((String) null))
        .isInstanceOf(NullPointerException.class);
  }
}
