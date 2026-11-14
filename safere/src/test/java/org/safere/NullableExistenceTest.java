// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Existence proofs must not publish unguarded nullable-loop bounds or captures. */
@DisabledForCrosscheck("compares SafeRE's guarded and DFA execution paths")
class NullableExistenceTest {
  private static final EnginePathOptions EXACT = EnginePathOptions.builder().dfa(false).build();

  @Test
  void booleanResultsAndConsumedMatchesAgreeWithGuardedExecution() {
    List<String> patterns =
        List.of(
            "(a?)*y",
            "(?:a?)*y",
            "((a?)*)y",
            "(a?)*?y",
            "((a?){2,4})*y",
            "(a?)*(z)?y",
            "(a?)*z|y",
            "(a?)*y.*",
            "(a?)*y$",
            "^(a?)*y",
            "(a?)*",
            "(|a)*y",
            "(a|)*y",
            "(\\b|a)*y",
            "(é?)*😀",
            "(😀?)*y");
    List<String> inputs =
        List.of(
            "",
            "y",
            "aaay",
            "aaaz",
            "y" + "a".repeat(1024) + "z",
            "ay".repeat(32),
            "a".repeat(1023) + "y",
            "é".repeat(512) + "😀",
            "😀😀y");
    for (String regex : patterns) {
      for (String input : inputs) {
        Pattern actual = Pattern.compile(regex);
        Pattern expected = Pattern.compile(regex, 0, EXACT);
        boolean found = expected.matcher(input).find();
        assertThat(actual.asPredicate().test(input))
            .as("/%s/ on %s", regex, input)
            .isEqualTo(found);
        assertThat(actual.find(utf8(input))).as(regex).isEqualTo(found);
        compareSequence(actual.matcher(input), expected.matcher(input));
        // Capture consumption on the String pattern must not suppress UTF-8 deferral.
        compareUtf8Sequence(
            Pattern.compile(regex).matcher(utf8(input)), expected.matcher(utf8(input)));
      }
    }
  }

  @Test
  void boundsDemandKeepsLaterFindsExactAcrossReset() {
    Matcher matcher = Pattern.compile("(a?)*y").matcher("a".repeat(1023) + "y");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.capturesResolved()).isFalse();
    assertThat(matcher.start()).isZero();
    assertThat(matcher.end()).isEqualTo(1024);
    assertThat(matcher.capturesResolved()).isFalse();
    assertThat(matcher.pattern().innerCapturesObserved()).isFalse();
    assertThat(matcher.group(1)).isEmpty();
    assertThat(matcher.find()).isFalse();
    matcher.reset("ay");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.end()).isEqualTo(2);
  }

  @Test
  void aWitnessFromAnotherStartDoesNotConstrainPreferredBounds() {
    String regex = "(a?)*z|y";
    String input = "y" + "a".repeat(1024) + "z";
    Matcher matcher = Pattern.compile(regex).matcher(input);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.toString()).endsWith(" lastmatch=y]");
    assertThat(matcher.toMatchResult().group()).isEqualTo("y");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(1);
    assertThat(matcher.end()).isEqualTo(input.length());
    assertThat(matcher.find()).isFalse();
  }

  @Test
  void deferredResultsSurviveBoundsChangesAndPatternChanges() {
    for (boolean anchoring : List.of(false, true)) {
      Matcher matcher = Pattern.compile("(a?)*y").matcher("aaay");
      assertThat(matcher.find()).isTrue();
      if (anchoring) {
        matcher.useAnchoringBounds(false);
      } else {
        matcher.useTransparentBounds(true);
      }
      assertThat(matcher.group()).isEqualTo("aaay");
      matcher.usePattern(Pattern.compile("z"));
      assertThat(matcher.group()).isEqualTo("aaay");
      assertThat(matcher.find()).isFalse();
    }
  }

  @Test
  void resettingOrChangingRegionDiscardsProvisionalBounds() {
    Matcher matcher = Pattern.compile("(a?)*y").matcher("aaay");
    assertThat(matcher.find()).isTrue();
    matcher.reset("x");
    assertThat(matcher.find()).isFalse();
    assertThatThrownBy(matcher::start).isInstanceOf(IllegalStateException.class);
    matcher.reset("xayx").region(1, 3);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(1);
    assertThat(matcher.end()).isEqualTo(3);
    matcher.reset("xay");
    assertThat(matcher.find(2)).isTrue();
    assertThat(matcher.start()).isEqualTo(2);
    assertThat(matcher.group()).isEqualTo("y");
  }

  @Test
  void replacementsAndSnapshotsConsumeTheExactSequence() {
    String input = "ay".repeat(64);
    String regex = "(a?)*(z)?y";
    for (String replacement : List.of("!", "$0", "$1:$2")) {
      Pattern actual = Pattern.compile(regex);
      Pattern expected = Pattern.compile(regex, 0, EXACT);
      assertThat(actual.matcher(input).replaceAll(replacement))
          .isEqualTo(expected.matcher(input).replaceAll(replacement));
      assertThat(actual.matcher(input).replaceFirst(replacement))
          .isEqualTo(expected.matcher(input).replaceFirst(replacement));
    }
    Matcher matcher = Pattern.compile(regex).matcher(input);
    assertThat(matcher.find()).isTrue();
    var snapshot = matcher.toMatchResult();
    assertThat(matcher.find()).isTrue();
    assertThat(snapshot.start()).isZero();
    assertThat(snapshot.end()).isEqualTo(2);
    assertThat(snapshot.group(1)).isEmpty();
    assertThat(snapshot.group(2)).isNull();
  }

  private static void compareSequence(Matcher actual, Matcher expected) {
    while (expected.find()) {
      assertThat(actual.find()).isTrue();
      for (int group = 0; group <= expected.groupCount(); group++) {
        assertThat(actual.start(group)).isEqualTo(expected.start(group));
        assertThat(actual.end(group)).isEqualTo(expected.end(group));
        assertThat(actual.group(group)).isEqualTo(expected.group(group));
      }
    }
    assertThat(actual.find()).isFalse();
  }

  private static void compareUtf8Sequence(Utf8Matcher actual, Utf8Matcher expected) {
    while (expected.find()) {
      assertThat(actual.find()).isTrue();
      for (int group = 0; group <= expected.groupCount(); group++) {
        assertThat(actual.start(group)).isEqualTo(expected.start(group));
        assertThat(actual.end(group)).isEqualTo(expected.end(group));
      }
    }
    assertThat(actual.find()).isFalse();
  }

  private static Utf8Input utf8(String text) {
    return Utf8Input.trusted(text.getBytes(UTF_8));
  }
}
