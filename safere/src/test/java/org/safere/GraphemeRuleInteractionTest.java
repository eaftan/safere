// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unicode 17 rule interactions, independently of JDK implementation exceptions (issue #954). */
class GraphemeRuleInteractionTest {
  @Test
  @DisabledForCrosscheck("Unicode rule oracle, including intentional grapheme differences")
  void prependDoesNotHideIndicOrRegionalIndicatorContext() {
    for (String prepend : List.of("", "\u0600", "\u0600\u0600")) {
      assertClusters(prepend + "\u0915\u094D\u0915", List.of(prepend + "\u0915\u094D\u0915"));
      String ri = "\uD83C\uDDE6";
      assertClusters(prepend + ri.repeat(3), List.of(prepend + ri.repeat(2), ri));
    }
  }

  @Test
  @DisabledForCrosscheck("Unicode rule oracle, including intentional grapheme differences")
  void indicContextUsesIncbRatherThanGraphemeExtend() {
    String consonant = "\u0915";
    String linker = "\u094D";
    for (String extend : List.of("", "\u0300", "\u200D", "\u0300\u200D\u0300")) {
      String conjunct = consonant + extend + linker + extend + consonant;
      assertClusters(conjunct, List.of(conjunct));
      for (String interrupt : List.of("\u200C", "\u0903")) {
        // ZWNJ is GCB=Extend but InCB=None. SpacingMark also interrupts GB9c.
        String prefix = consonant + linker + extend + interrupt + linker;
        assertClusters(prefix + consonant, List.of(prefix, consonant));
      }
    }
  }

  @Test
  @DisabledForCrosscheck("Unicode rule oracle, including intentional grapheme differences")
  void hangulAndControlRulesPrecedeAttachmentRules() {
    for (String hangul : List.of("\u1100\u1160\u11A8", "\uAC00\u1160\u11A8", "\uAC01\u11A8")) {
      assertClusters("\u0600" + hangul + "\u0300", List.of("\u0600" + hangul + "\u0300"));
      assertClusters(hangul + "\u0300\u11A8", List.of(hangul + "\u0300", "\u11A8"));
    }
    for (String control : List.of("\r", "\n", "\r\n", "\u0000", "\u2028")) {
      assertClusters("\u0600" + control + "\u0300", List.of("\u0600", control, "\u0300"));
    }
  }

  @Test
  void explicitBoundaryAfterScalarDoesNotInventBreaksInsideClusters() {
    for (String cluster : List.of("\r\n", "a\u0300", "a\u0903", "\u0600a")) {
      String lastScalar = Character.toString(cluster.codePointBefore(cluster.length()));
      for (String prefix : List.of("", "xy", "\u0000\u0000")) {
        String input = prefix + cluster;
        Matcher matcher = Pattern.compile("(?s).\\b{g}").matcher(input);
        List<String> actual = new ArrayList<>();
        while (matcher.find()) {
          actual.add(matcher.group());
        }
        List<String> expected = new ArrayList<>();
        prefix.codePoints().forEach(cp -> expected.add(Character.toString(cp)));
        expected.add(lastScalar);
        assertThat(actual).as("scalar boundaries on %s", input).isEqualTo(expected);
      }
    }
  }

  @Test
  @DisabledForCrosscheck(
      "Transparent boundaries must see the following extender; JDK invents a break")
  void transparentTrailingBoundarySeesExtendersAfterConsumedPrefix() {
    Matcher matcher =
        Pattern.compile("\\b{g}a\\u0300\\b{g}")
            .matcher("aa\u0300\u0300")
            .region(1, 3)
            .useTransparentBounds(true);
    assertThat(matcher.matches()).isFalse();
    assertThat(matcher.lookingAt()).isFalse();
    assertThat(matcher.find()).isFalse();
    assertThat(matcher.useTransparentBounds(false).reset().region(1, 3).matches()).isTrue();
  }

  private static void assertClusters(String text, List<String> expected) {
    for (String regex : List.of("\\X", "(\\X)", "\\b{g}\\X\\b{g}")) {
      Matcher matcher = Pattern.compile(regex).matcher(text);
      List<String> actual = new ArrayList<>();
      while (matcher.find()) {
        actual.add(matcher.group());
      }
      assertThat(actual).as("/%s/ on %s", regex, text).isEqualTo(expected);
    }
  }
}
