// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("Ignored range trivia preserves bracket roles; intentional divergence #1070")
class CharacterClassRangeTriviaTest {
  private static final List<String> GAPS =
      List.of("", " ", "\t", "\r\n", "#c\n", "\\Q\\E", " \\Q\\E", "\\Q\\E #c\n");
  private static final List<String> INPUTS = inputs();

  @Test
  void delimiterRangesKeepTheirUnspacedMeanings() {
    // #1070: skipped trivia must not turn a raw bracket into a scalar endpoint.
    for (String gap : GAPS) {
      assertMembers("[!-" + gap + "]]", Set.of("!]", "-]"));
      assertMembers("[!-" + gap + "[A]]", Set.of("!", "-", "A"));
      assertMembers("[a-" + gap + "]", Set.of("a", "-"));
      assertMembers("[a-" + gap + "[z]]", Set.of("a", "-", "z"));
      assertMembers("[[a]-" + gap + "z]", Set.of("a", "-", "z"));
    }
  }

  @Test
  void rangeTriviaPreservesClassNestingNegationAndFlagScopes() {
    for (String lower : List.of("!", "a", "\\x41", "\\Q!\\E", "😀")) {
      for (String tail : List.of("]", "]a]", "[A]]", "[a-z]]", "[^A]]", "[\\d]]")) {
        String canonical = "[" + lower + "-" + tail;
        for (String gap : GAPS) {
          String candidate = "[" + lower + "-" + gap + tail;
          assertEquivalent(candidate, canonical);
          assertEquivalent("[x" + candidate + "]", "[x" + canonical + "]");
          assertEquivalent("[^" + candidate + "]", "[^" + canonical + "]");
          assertEquivalent("([x" + candidate + "])+", "([x" + canonical + "])+");
          assertEquivalent("[" + candidate + "&&[a-z]]", "[" + canonical + "&&[a-z]]");
        }
      }
    }
  }

  @Test
  void escapedAndQuotedBracketsRemainScalarRangeEndpoints() {
    for (String endpoint : List.of("\\]", "\\Q]\\E", "\\x5D", "\\[", "\\Q[\\E", "\\u005B")) {
      for (String gap : GAPS) {
        assertEquivalent("[!-" + gap + endpoint + "]", "[!-" + endpoint + "]");
      }
    }
    assertThat(Pattern.compile("(?x)[!- \\]]").matcher("A").matches()).isTrue();
    assertThat(Pattern.compile("(?x)[!- \\[]").matcher("A").matches()).isTrue();
  }

  @Test
  void invalidRangesAndUnclosedClassesStayInvalidAfterTrivia() {
    for (String gap : GAPS) {
      for (String suffix : List.of("\\d]", "\\p{Digit}]", "[A]", "[", "")) {
        assertThatThrownBy(() -> Pattern.compile("[!-" + gap + suffix, Pattern.COMMENTS))
            .isInstanceOf(PatternSyntaxException.class);
      }
      assertThatThrownBy(() -> Pattern.compile("[a-" + gap + "\\]]", Pattern.COMMENTS))
          .isInstanceOf(PatternSyntaxException.class);
    }
  }

  @Test
  void whitespaceIsSignificantWhenEscapedQuotedOrOutsideCommentsMode() {
    for (String endpoint : List.of("\\ ", "\\x20", "\\Q \\E")) {
      assertThatThrownBy(() -> Pattern.compile("[!-" + endpoint + "]", Pattern.COMMENTS))
          .isInstanceOf(PatternSyntaxException.class);
    }
    assertThatThrownBy(() -> Pattern.compile("[a- ]")).isInstanceOf(PatternSyntaxException.class);
    assertThatThrownBy(() -> Pattern.compile("(?x)(?-x:[a- ])"))
        .isInstanceOf(PatternSyntaxException.class);
    assertThat(Pattern.compile("(?x:[a- ]) ").matcher("a ").matches()).isTrue();
  }

  private static void assertMembers(String regex, Set<String> expected) {
    for (String scoped : List.of(regex, "(?x)" + regex, "(?x:" + regex + ")")) {
      Pattern pattern = Pattern.compile(scoped, Pattern.COMMENTS);
      for (String input : INPUTS) {
        assertThat(pattern.matcher(input).matches())
            .as("/%s/ on <%s>", scoped, input)
            .isEqualTo(expected.contains(input));
      }
    }
  }

  private static void assertEquivalent(String candidate, String canonical) {
    for (int flags : List.of(Pattern.COMMENTS, Pattern.COMMENTS | Pattern.UNIX_LINES)) {
      assertEquivalent(candidate, canonical, flags);
    }
    assertEquivalent("(?x)" + candidate, "(?x)" + canonical, 0);
    assertEquivalent("(?x:" + candidate + ")", "(?x:" + canonical + ")", 0);
  }

  private static void assertEquivalent(String candidate, String canonical, int flags) {
    java.util.regex.Pattern expected;
    try {
      // Only the unspaced form is a JDK oracle; the spaced form intentionally diverges.
      expected = java.util.regex.Pattern.compile(canonical, flags);
    } catch (PatternSyntaxException e) {
      assertThatThrownBy(() -> Pattern.compile(candidate, flags))
          .as("rejection of /%s/", candidate)
          .isInstanceOf(PatternSyntaxException.class);
      return;
    }
    Pattern actual = Pattern.compile(candidate, flags);
    for (String input : INPUTS) {
      assertThat(actual.matcher(input).matches())
          .as("/%s/ equivalent to /%s/ on <%s>", candidate, canonical, input)
          .isEqualTo(expected.matcher(input).matches());
    }
  }

  private static List<String> inputs() {
    List<String> inputs = new ArrayList<>();
    inputs.add("");
    for (int rune = 0; rune < 128; rune++) {
      String scalar = Character.toString(rune);
      inputs.add(scalar);
      inputs.add(scalar + "]");
      inputs.add(scalar + "a]");
    }
    inputs.addAll(List.of("😀", "😀]", "Ā", "\uD83D", "\uDE00", "aa"));
    return List.copyOf(inputs);
  }
}
