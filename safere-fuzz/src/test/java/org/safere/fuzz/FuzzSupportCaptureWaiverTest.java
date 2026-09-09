// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class FuzzSupportCaptureWaiverTest {
  @Test
  void captureBeforeAcceptedStartIsWaivedAcrossAccessors() {
    for (String regex : new String[] {"(?:(a){1})*$", "(?:(?<item>a){2})*$"}) {
      var pair = FuzzSupport.compileOrSkip(regex, 0).matcher("aab");
      assertThat(pair.find()).isTrue();
      assertThat(pair.group(1)).isNull();
      assertThat(pair.start(1)).isEqualTo(-1);
      assertThat(pair.end(1)).isEqualTo(-1);
      pair.toMatchResult();
      if (regex.contains("?<item>")) {
        assertThat(pair.group("item")).isNull();
        assertThat(pair.start("item")).isEqualTo(-1);
        assertThat(pair.end("item")).isEqualTo(-1);
      }
      pair.reset();
      assertThat(pair.find()).isTrue();
    }
  }

  @Test
  void sameStartLeakageInQuantifiedCaptureIsWaived() {
    var pair = FuzzSupport.compileOrSkip("(?:(?:())*{0}{1}?{1}|a).", 0).matcher("ab");
    assertThat(pair.matches()).isTrue();
    assertThat(pair.group(1)).isNull();
    pair.toMatchResult();
  }

  @Test
  void missingUnquantifiedCaptureIsReported() {
    // Deliberately substitute a matcher with the wrong capture to simulate an engine defect.
    var pair =
        new FuzzSupport.MatcherPair(
            "(a)b$",
            0,
            "ab",
            org.safere.Pattern.compile("(?:()z|ab)$").matcher("ab"),
            java.util.regex.Pattern.compile("(a)b$").matcher("ab"));
    assertThatThrownBy(pair::matches).isInstanceOf(AssertionError.class);
  }

  @Test
  void leakageWaivesReplacementOutput() {
    var pair = FuzzSupport.compileOrSkip("(?:(a){1})*$", 0).matcher("ab");
    assertThat(pair.replaceAll("$1")).isTrue();
  }

  @Test
  void unrelatedReplacementDifferencesAreReported() {
    var pair =
        new FuzzSupport.MatcherPair(
            "(a)?b$",
            0,
            "ab",
            org.safere.Pattern.compile("b$").matcher("ab"),
            java.util.regex.Pattern.compile("(a)?b$").matcher("ab"));
    assertThatThrownBy(() -> pair.replaceAll("constant")).isInstanceOf(AssertionError.class);
  }

  @Test
  void ancestryComesFromParsedSyntax() {
    var groups =
        org.safere.FuzzCaptureStructure.quantifiedGroups(
            org.safere.Pattern.compile("(a*)(b)?(?:(?<item>c))+"));
    assertThat(groups.get(1)).isFalse();
    assertThat(groups.get(2)).isTrue();
    assertThat(groups.get(3)).isTrue();
    assertThat(
            org.safere.FuzzCaptureStructure.quantifiedGroups(
                    org.safere.Pattern.compile("(a)?", org.safere.Pattern.LITERAL))
                .isEmpty())
        .isTrue();
    assertThat(
            org.safere.FuzzCaptureStructure.quantifiedGroups(
                    org.safere.Pattern.compile("(?x)(a) # ? * + {2}\n"))
                .isEmpty())
        .isTrue();
  }

  @Test
  void missingQuantifiedCaptureIsAnIntentionalBlindSpot() {
    var pair =
        new FuzzSupport.MatcherPair(
            "(a)?b$",
            0,
            "ab",
            org.safere.Pattern.compile("()??ab$").matcher("ab"),
            java.util.regex.Pattern.compile("(a)?b$").matcher("ab"));
    assertThat(pair.matches()).isTrue();
    pair.toMatchResult();
  }

  @Test
  void differingParticipatingQuantifiedCapturesAreReported() {
    var pair =
        new FuzzSupport.MatcherPair(
            "(a)?b$",
            0,
            "ab",
            org.safere.Pattern.compile("a(b)?$").matcher("ab"),
            java.util.regex.Pattern.compile("(a)?b$").matcher("ab"));
    assertThatThrownBy(pair::matches).isInstanceOf(AssertionError.class);
  }

  @Test
  void replacementWaiverHandlesEachApiAndDoesNotSurviveReset() {
    String regex = "(?:(?<item>a){1})*$";
    assertThat(FuzzSupport.compileOrSkip(regex, 0).matcher("ab").replaceFirst("${item}")).isTrue();
    assertThat(
            FuzzSupport.compileOrSkip(regex, 0)
                .matcher("ab")
                .replaceAll(result -> result.group(1) == null ? "empty" : "captured"))
        .isTrue();
    var pair = FuzzSupport.compileOrSkip(regex, 0).matcher("ab");
    assertThat(pair.find()).isTrue();
    StringBuilder builder = new StringBuilder();
    assertThat(pair.appendReplacement(builder, "${item}")).isTrue();
    pair.appendTail(builder);
    assertThat(builder.toString()).isEqualTo("ab");
    pair.reset();
    assertThat(pair.find()).isTrue();
    StringBuffer buffer = new StringBuffer();
    assertThat(pair.appendReplacement(buffer, "${item}")).isTrue();
    pair.appendTail(buffer);
    assertThat(buffer.toString()).isEqualTo("ab");
    pair.reset("aa");
    assertThat(pair.replaceAll("${item}")).isTrue();
  }

  @Test
  void namedSameStartCaptureIsWaivedButSafeReExpectedStateIsChecked() {
    var pair = FuzzSupport.compileOrSkip("(?:(?:(?<item>))*{0}{1}?{1}|a).", 0).matcher("ab");
    assertThat(pair.matches()).isTrue();
    assertThat(pair.group("item")).isNull();
    assertThat(pair.start("item")).isEqualTo(-1);
    assertThat(pair.end("item")).isEqualTo(-1);
    pair.toMatchResult();
  }
}
