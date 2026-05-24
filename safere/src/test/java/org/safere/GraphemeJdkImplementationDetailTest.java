// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Inventory of observed JDK grapheme-region behavior that appears to depend on {@code
 * java.util.regex} implementation details rather than on specified regex semantics.
 *
 * <p>These cases are intentionally not active SafeRE compatibility requirements. If one of these
 * behaviors is reclassified as a principled SafeRE target, move it into the focused grapheme tests
 * with an explicit model-based expectation instead of relying on exact JDK trace equality.
 */
@Disabled("Observed JDK grapheme-region implementation details; not a SafeRE compatibility target")
@DisplayName("JDK grapheme implementation-detail inventory")
class GraphemeJdkImplementationDetailTest {

  @Test
  @DisplayName("boundary-starting grapheme alternatives advance repeated find like JDK")
  void boundaryStartingGraphemeAlternativesAdvanceRepeatedFindLikeJdk() {
    assertTraceSameAsJdk("\\b{g}\\X|b", "abc", 0, 3);
    assertTraceSameAsJdk("b|\\b{g}\\X", "abc", 0, 3);
    assertTraceSameAsJdk("(?:\\b{g}\\X|b)", "abc", 0, 3);
    assertTraceSameAsJdk("(\\b{g}\\X)|(b)", "abc", 0, 3);
    assertTraceSameAsJdk("\\b{g}\\X|\\X", "abc", 0, 3);
    assertTraceSameAsJdk("\\b{g}\\X|b", "#\r\r\n\r$", 1, 5);
    assertTraceSameAsJdk("b|\\b{g}\\X", "#\r\r\n\r$", 1, 5);
    assertTraceSameAsJdk("\\X|\\b{g}\\X", "\r\r\n", 0, 3);
    assertTraceSameAsJdk("\\X|\\b{g}\\X", "\n\r\n", 0, 3);
    assertTraceSameAsJdk("\\X|\\b{g}\\X", "\uD83D\uDE00\u0301\u200D\uD83D\uDE00", 1, 5);
    assertTraceSameAsJdk("\\b{g}\\X|a", "\r\u0600a\u0301", 0, 4);
    assertTraceSameAsJdk("\\b{g}\\X|a", "\uD83D\uDE00\u0600a\u0301", 1, 5);
    assertTraceSameAsJdk("a|\\b{g}\\X", "\uD83D\uDE00\u0600a\r", 1, 5);
    assertTraceSameAsJdk("\\b{g}\\X|a", "#\ra\u200D\u0600a$", 1, 6);
    assertTraceSameAsJdk("\\b{g}\\X|a", "#\ra\u0301\u0600a$", 1, 6);
    assertTraceSameAsJdk("b|\\b{g}\\X", "\uD83D\uDE00\u0301\u200D\uD83D\uDC69\n", 1, 7);
    assertTraceSameAsJdk("b|\\b{g}\\X", "\uD83D\uDE00\u0301\u200D\uD83D\uDC69\uD83C\uDDFAa", 1, 9);
    assertTransparentTraceSameAsJdk("a|\\b{g}\\X", "#\r\u0600a$", 1, 4);
    assertTransparentCapturedFindTraceSameAsJdk("(\\b{g}\\X)|(b)", "#ab$", 1, 3);
    assertTraceSameAsJdk("\\b{g}\\X|b", "\u11A8\r\n\u0000\u0301", 0, 5);
    assertTraceSameAsJdk("\\b{g}\\X|b", "\u1100\u200Da\u0903\uDE00", 0, 5);
  }

  @Test
  @DisplayName("boundary-starting grapheme alternatives handle low-surrogate searches like JDK")
  void boundaryStartingGraphemeAlternativesHandleLowSurrogateSearchesLikeJdk() {
    assertTraceSameAsJdk("b|\\b{g}\\X", "\r\uDE00", 0, 2);
    assertTraceSameAsJdk("b|\\b{g}\\X", "\n\uDE00", 0, 2);
    assertTraceSameAsJdk("b|\\b{g}\\X", "a\uDE00", 0, 2);
    assertTraceSameAsJdk("b|\\b{g}\\X", "\uD83D\uDE00\u200D\uD83D\uDC69", 1, 5);
    assertTraceSameAsJdk("b|\\b{g}\\X", "\uD83D\uDC69\u200D\uD83D\uDCBB", 1, 5);
    assertTraceSameAsJdk("b|\\b{g}\\X", "\uD83D\uDE00\u0301\u200D\uD83D\uDE00", 1, 5);
    assertTraceSameAsJdk("b|\\b{g}\\X", "\uD83D\uDE00\u0301\u200D\uD83D\uDE00", 1, 6);
    assertTraceSameAsJdk("a|\\b{g}\\X", "\uD83D\uDE00\u200D\uD83D\uDC69\u0600a", 1, 7);
    assertTraceSameAsJdk("a|\\b{g}\\X", "\uD83D\uDE00\u0600a\u0600\uD83C\uDDFA", 1, 7);

    assertTransparentTraceSameAsJdk("b|\\b{g}\\X", "#\u0301\uDE00$", 1, 3);
    assertTransparentTraceSameAsJdk("b|\\b{g}\\X", "#\u200D\uDE00$", 1, 3);
    assertTransparentTraceSameAsJdk("b|\\b{g}\\X", "#\uD83C\uDFFD\uDE00$", 1, 4);
    assertTransparentTraceSameAsJdk("\\X|\\b{g}\\X", "\uD83D\uDE00\u200D\uD83D\uDCBB\uDE00", 1, 5);
    assertTransparentTraceSameAsJdk("\\X|\\b{g}\\X", "\uD83D\uDE00\u0301", 1, 3);
    assertTransparentTraceSameAsJdk("\\X|\\b{g}\\X", "\uD83D\uDE00\uD83C\uDFFD", 1, 4);
    assertTransparentTraceSameAsJdk("\\X|\\b{g}\\X", "\uD83D\uDE00\u0301\r", 1, 4);
    assertTransparentTraceSameAsJdk("\\X|\\b{g}\\X", "\uD83D\uDE00\u200D\u0301\uD83C\uDDFA", 1, 6);
    assertTraceSameAsJdk("\\X|\\b{g}\\X", "\uD83D\uDE00\u200D\uD83D\uDC69\r", 1, 6);
    assertTransparentTraceSameAsJdk("\\b{g}\\X|\\X", "\uD83D\uDE00\u0301\r", 1, 4);
    assertTransparentTraceSameAsJdk("(?:\\X)|(?:\\b{g}\\X)", "\uD83D\uDE00\u0301\r", 1, 4);
  }

  @Test
  @DisplayName("explicit grapheme boundaries treat selected region edges like JDK")
  void explicitGraphemeBoundariesTreatSelectedRegionEdgesLikeJdk() {
    assertTransparentTraceSameAsJdk("\\X\\b{g}", "aa\u0300", 1, 2);
    assertTransparentTraceSameAsJdk("^\\X\\b{g}", "aa\u0300", 1, 2);
    assertTransparentTraceSameAsJdk("\\b{g}\\X\\b{g}", "aa\u0300", 1, 2);
    assertTransparentTraceSameAsJdk("^\\b{g}\\X\\b{g}", "aa\u0300", 1, 2);
    assertTransparentTraceSameAsJdk("\\b{g}\\X|b", "zz\u0301\u0301", 2, 4);
    assertTransparentTraceSameAsJdk("\\b{g}\\X|\\X", "#\u0301\u0600\u0327\u200D", 1, 5);
    assertTransparentTraceSameAsJdk("\\b{g}a\\u0300\\b{g}", "\uD83Da\u0300\u200D", 1, 3);
    assertTransparentTraceSameAsJdk("\\X\\b{g}", "a\u0301\u0300", 1, 2);
    assertTransparentTraceSameAsJdk("\\X\\b{g}", "#\u0600$", 1, 2);
    assertTransparentTraceSameAsJdk("\\X\\b{g}", "\uD83D\uDE00\u0301", 1, 2);
    assertTransparentTraceSameAsJdk("\\X\\b{g}", "\uD83D\uDE00a", 1, 2);
    assertTransparentTraceSameAsJdk("\\X\\b{g}", "\uD83D\u0301\u200D", 1, 2);
    assertTransparentTraceSameAsJdk("^\\X\\b{g}", "a\u0301\u0300", 1, 2);
    assertTransparentTraceSameAsJdk("^\\X\\b{g}", "\uD83D\uDE00\u0301", 1, 2);
    assertTransparentTraceSameAsJdk("\\b{g}\\X\\b{g}", "a\u0301a\u0300", 1, 3);
    assertTransparentTraceSameAsJdk("\\b{g}\\X\\b{g}", "#\u0301\u0600$", 1, 3);
    assertTransparentTraceSameAsJdk("\\b{g}\\X\\b{g}", "#\r\r\u0301\u0300", 1, 4);
    assertTransparentTraceSameAsJdk("^\\b{g}\\X\\b{g}", "\uD83D\uDE00", 1, 2);
    assertTransparentTraceSameAsJdk("^\\b{g}\\X\\b{g}", "\uD83D\uDE00\uD83D\uDE00", 1, 2);
    assertTransparentTraceSameAsJdk(
        "b|\\b{g}\\X", "\uD83D\uDC69\u200D\uD83D\uDC69\uD83C\uDFFD", 0, 7);
    assertTransparentTraceSameAsJdk(
        "b|\\b{g}\\X", "#\uD83D\uDC69\u200D\uD83D\uDE00\uD83C\uDFFD$", 1, 8);
  }

  @Test
  @DisplayName("boundary-starting alternatives continue after contextual grapheme characters")
  void boundaryStartingAlternativesContinueAfterContextualGraphemeCharacters() {
    assertTraceSameAsJdk("\\b{g}\\X|\\X", "\uD83D\uDC69\u200D\uD83D\uDCBB\u0301", 1, 5);
    assertTraceSameAsJdk("\\X|\\b{g}\\X", "\uD83D\uDC69\u200D\uD83D\uDCBB\u0301", 1, 5);
    assertTraceSameAsJdk("\\b{g}\\X|a", "#\r\u0600a$", 1, 4);
    assertTraceSameAsJdk("a|\\b{g}\\X", "#\r\u0600a$", 1, 4);
    assertTraceSameAsJdk("(?:(?:\\b{g}\\X)|a)", "#\r\u0600a$", 1, 4);
    assertCapturedFindTraceSameAsJdk("(\\b{g}\\X)|(b)", "ab", 0, 2);
  }

  @Test
  @DisplayName("repeated \\X find positions expose JDK candidate-start details")
  void repeatedGraphemeFindPositionsExposeJdkCandidateStartDetails() {
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "ab", 0, 2);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "e\u0301a", 0, 3);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "\u0301a", 0, 2);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDE8", 0, 6);
    assertTraceSameAsJdk("\\X\\b{g}", "\uD83D\uDC69\u200D\uD83D\uDCBB", 1, 5);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "\uD83D\uDC69\u200D\uD83D\uDCBB", 1, 5);
    assertTraceSameAsJdk("\\X", "\uD83D\uDE00\u200D\uD83D\uDE00", 1, 4);
    assertTransparentTraceSameAsJdk("\\X\\X", "\uD83C\uDDFA\uD83C\uDDF8", 0, 4);
    assertTransparentTraceSameAsJdk("\\X\\X", "\uD83D\uDC4D\uD83C\uDFFD", 0, 4);
    assertTransparentTraceSameAsJdk("\\X\\X", "\uD83D\uDC69\u200D\uD83D\uDCBB", 0, 5);
    assertTraceSameAsJdk("\\X\\X", "#\r\r\uD83C\uDDFA\uD83C\uDDFA\u0301$", 1, 8);
    assertTraceSameAsJdk("\\X\\b{g}", "\uD83D\uDE00\u0301\u200D\uD83D\uDE00", 1, 6);
    assertTraceSameAsJdk("\\X\\b{g}", "\uD83D\uDE00\u200D\u200D\uD83D\uDE00", 1, 6);
    assertTraceSameAsJdk("\\X\\b{g}", "\uD83D\uDE00\uD83C\uDFFD\u200D\uD83D\uDE00", 1, 7);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "\uAC01\uD83C\uDDFA\uD83C\uDDFA\r", 0, 6);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "a\uD83C\uDDFA\u200D\uD83C\uDDFA", 0, 6);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "\r\u0903\u0903\u0903\u1100", 0, 5);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "\uD83D\uDE00\u200D", 1, 3);
    assertTransparentTraceSameAsJdk("\\X\\X", "\uD83D\uDE00\u0600\u0600\uD83D\uDE00", 1, 5);
    assertTransparentTraceSameAsJdk("\\X\\X", "\uD83D\uDE00\uD83D\uDC69\u200D\uD83D\uDE00", 1, 6);
    assertTransparentTraceSameAsJdk("\\X\\X", "\uD83D\uDE00\uD83D\uDE00\u200D\uD83D\uDE00", 1, 6);
    assertTraceSameAsJdk("\\X\\b{g}", "\uD83D\uDC69\u200D\uD83D\uDCBB\uDE00", 1, 5);
    assertTraceSameAsJdk("\\X\\X", "#\r\r\uD83C\uDDFA\uD83C\uDDFA$", 1, 7);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "\uD83D\uDE00\r\r\u200D", 1, 4);
    assertTransparentTraceSameAsJdk("\\X{2}", "\u0903\u11A8\uD83D\uDC69\u200D\uD83D\uDC69", 0, 7);
    assertTransparentTraceSameAsJdk("(\\X)(\\X)", "\uD83D\uDC69\u200D\uD83D\uDCBB", 0, 5);
    assertTransparentTraceSameAsJdk("(\\X)(\\X)", "\uD83D\uDC69\u200D\uD83D\uDCBB", 1, 5);
    assertTransparentCapturedFindTraceSameAsJdk(
        "(\\X)(\\X)", "\uD83D\uDC69\u200D\uD83D\uDCBB", 0, 5);
    assertTraceSameAsJdk("(\\X)(\\X)", "\uD83D\uDC69\u200D\uD83D\uDC69", 0, 5);
    assertCapturedFindTraceSameAsJdk("(\\X)(\\X)", "\uD83D\uDC69\u200D\uD83D\uDC69", 0, 5);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "#\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDE8$", 1, 7);
    assertTransparentTraceSameAsJdk("\\X+", "\uD83D\uDC69\u200D\uD83D\uDCBB\uDE00", 1, 5);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "#\r\uD83C\uDDFA$", 1, 4);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "#\u200D\uD83D\uDC69$", 1, 4);
    assertTransparentTraceSameAsJdk(
        "\\b{g}\\X\\b{g}", "zz\uD83D\uDC69\u200D\uD83D\uDC69\uD83C\uDFFD", 2, 9);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "\uD83D\uDE00\u0301\r\r\uDE00", 1, 5);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "\uD83D\uDE00\u200D\r\r", 1, 5);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "\uD83D\uDE00\u200D\r\n", 1, 5);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "\uD83D\uDE00\u200D\uDE00\uDE00", 1, 5);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "\uD83D\uDE00\u200D\uD83D\uDC69\u0301\r", 1, 7);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "\uD83D\uDE00\u200D\uD83D\uDC69\u200D\uDE00a", 1, 8);
    assertTraceSameAsJdk(
        "\\b{g}\\X\\b{g}", "\uD83D\uDE00\u200D\uD83D\uDC69\u200D\uD83D\uDC69", 1, 8);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "\uD83D\uDE00\u0301\u200D\uD83D\uDC69\u0301a", 1, 8);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "\uD83D\uDE00\u200D\u200D\uD83D\uDC69\u200Da", 1, 8);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "\uD83D\uDE00\u0301\u200D\uD83D\uDE00", 1, 6);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "\uD83D\uDE00\u0301\u200D\uD83C\uDDFA", 1, 6);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "\uD83D\uDE00\u0301\u200D\uD83D", 1, 5);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "\uD83C\uDDE6\uD83C\uDDE6", 1, 3);
    assertTraceSameAsJdk("\\b{g}\\X\\b{g}$", "\uD83C\uDDE6".repeat(1001), 0, 2002);
    assertTraceSameAsJdk(
        "\\X\\X",
        "#\uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDC69\uD83C\uDFFD"
            + "\u200D\uD83D\uDC69\uD83C\uDFFD$",
        1,
        15);
    assertTraceSameAsJdk("\\X\\X", "\uD83C\uDDFA\uD83C\uDDF8", 0, 4);
    assertTraceSameAsJdk("\\X{2}", "\uD83D\uDC4D\uD83C\uDFFD", 0, 4);
    assertTraceSameAsJdk("(\\X)(\\X)", "\uD83D\uDC69\u200D\uD83D\uDCBB", 0, 5);
    assertTraceSameAsJdk("\\X\\X", "\u0915\u094D\u0937", 0, 3);
    assertTraceSameAsJdk("\\X{2}", "\u0915\u094D\u0937", 0, 3);
    assertTraceSameAsJdk("(\\X)(\\X)", "\u0915\u094D\u0937", 0, 3);
    assertTraceSameAsJdk("(?:\\X)(?:\\X)", "\uD83C\uDDFA\uD83C\uDDF8", 0, 4);
    assertTraceSameAsJdk("\\X{1}\\X", "\uD83C\uDDFA\uD83C\uDDF8", 0, 4);
    assertTraceSameAsJdk("(?:\\X){2}", "\uD83C\uDDFA\uD83C\uDDF8", 0, 4);

    String regionEndsInsidePair = "\uDE00\uD83D\uDE00";
    assertTraceSameAsJdk("^\\X\\X$", regionEndsInsidePair, 0, 2);
    assertTraceSameAsJdk("\\X{2}", regionEndsInsidePair, 0, 2);

    String regionStartsInsidePair = "\uD83D\uDE00\uD83D\uDE01";
    assertTraceSameAsJdk("\\X{2}", regionStartsInsidePair, 1, 4);
  }

  private void assertTraceSameAsJdk(String regex, String input, int start, int end) {
    java.util.regex.Matcher jdkMatcher =
        java.util.regex.Pattern.compile(regex).matcher(input).region(start, end);
    Matcher safeMatcher = Pattern.compile(regex).matcher(input).region(start, end);

    assertThat(safeMatcher.matches())
        .as("matches() for /%s/ on %s region [%s,%s]", regex, input, start, end)
        .isEqualTo(jdkMatcher.matches());

    jdkMatcher.reset(input).region(start, end);
    safeMatcher.reset(input).region(start, end);
    assertThat(safeMatcher.lookingAt())
        .as("lookingAt() for /%s/ on %s region [%s,%s]", regex, input, start, end)
        .isEqualTo(jdkMatcher.lookingAt());

    jdkMatcher.reset(input).region(start, end);
    safeMatcher.reset(input).region(start, end);
    assertThat(findBounds(safeMatcher))
        .as("find() positions for /%s/ on %s region [%s,%s]", regex, input, start, end)
        .containsExactly(findBounds(jdkMatcher).toArray(int[][]::new));
  }

  private void assertTransparentTraceSameAsJdk(String regex, String input, int start, int end) {
    java.util.regex.Matcher jdkMatcher =
        java.util.regex.Pattern.compile(regex)
            .matcher(input)
            .region(start, end)
            .useTransparentBounds(true);
    Matcher safeMatcher =
        Pattern.compile(regex).matcher(input).region(start, end).useTransparentBounds(true);

    assertThat(safeMatcher.matches())
        .as("transparent matches() for /%s/ on %s region [%s,%s]", regex, input, start, end)
        .isEqualTo(jdkMatcher.matches());

    jdkMatcher.reset(input).region(start, end).useTransparentBounds(true);
    safeMatcher.reset(input).region(start, end).useTransparentBounds(true);
    assertThat(safeMatcher.lookingAt())
        .as("transparent lookingAt() for /%s/ on %s region [%s,%s]", regex, input, start, end)
        .isEqualTo(jdkMatcher.lookingAt());

    jdkMatcher.reset(input).region(start, end).useTransparentBounds(true);
    safeMatcher.reset(input).region(start, end).useTransparentBounds(true);
    assertThat(findBounds(safeMatcher))
        .as("transparent find() positions for /%s/ on %s region [%s,%s]", regex, input, start, end)
        .containsExactly(findBounds(jdkMatcher).toArray(int[][]::new));
  }

  private void assertCapturedFindTraceSameAsJdk(String regex, String input, int start, int end) {
    java.util.regex.Matcher jdkMatcher =
        java.util.regex.Pattern.compile(regex).matcher(input).region(start, end);
    Matcher safeMatcher = Pattern.compile(regex).matcher(input).region(start, end);

    assertThat(capturedFindTrace(safeMatcher))
        .as("captured find() trace for /%s/ on %s region [%s,%s]", regex, input, start, end)
        .containsExactlyElementsOf(capturedFindTrace(jdkMatcher));
  }

  private void assertTransparentCapturedFindTraceSameAsJdk(
      String regex, String input, int start, int end) {
    java.util.regex.Matcher jdkMatcher =
        java.util.regex.Pattern.compile(regex)
            .matcher(input)
            .region(start, end)
            .useTransparentBounds(true);
    Matcher safeMatcher =
        Pattern.compile(regex).matcher(input).region(start, end).useTransparentBounds(true);

    assertThat(capturedFindTrace(safeMatcher))
        .as(
            "transparent captured find() trace for /%s/ on %s region [%s,%s]",
            regex, input, start, end)
        .containsExactlyElementsOf(capturedFindTrace(jdkMatcher));
  }

  private List<int[]> findBounds(java.util.regex.Matcher matcher) {
    List<int[]> matches = new ArrayList<>();
    while (matcher.find()) {
      matches.add(new int[] {matcher.start(), matcher.end()});
    }
    return matches;
  }

  private List<int[]> findBounds(Matcher matcher) {
    List<int[]> matches = new ArrayList<>();
    while (matcher.find()) {
      matches.add(new int[] {matcher.start(), matcher.end()});
    }
    return matches;
  }

  private List<String> capturedFindTrace(java.util.regex.Matcher matcher) {
    List<String> trace = new ArrayList<>();
    while (matcher.find()) {
      trace.add(capturedMatchTrace(matcher));
    }
    return trace;
  }

  private List<String> capturedFindTrace(Matcher matcher) {
    List<String> trace = new ArrayList<>();
    while (matcher.find()) {
      trace.add(capturedMatchTrace(matcher));
    }
    return trace;
  }

  private String capturedMatchTrace(java.util.regex.Matcher matcher) {
    StringBuilder builder = new StringBuilder();
    builder.append(matcher.start()).append('-').append(matcher.end()).append(':');
    builder.append(matcher.group());
    for (int i = 1; i <= matcher.groupCount(); i++) {
      builder.append(':').append(matcher.group(i));
    }
    return builder.toString();
  }

  private String capturedMatchTrace(Matcher matcher) {
    StringBuilder builder = new StringBuilder();
    builder.append(matcher.start()).append('-').append(matcher.end()).append(':');
    builder.append(matcher.group());
    for (int i = 1; i <= matcher.groupCount(); i++) {
      builder.append(':').append(matcher.group(i));
    }
    return builder.toString();
  }
}
