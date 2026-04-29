// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.MatchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for {@link Matcher} functional replacement methods. */
class MatcherFunctionalReplaceTest {

  @Test
  @DisplayName("replaceAll(Function) expands group references in returned replacement")
  void replaceAllFunctionExpandsGroupReferences() {
    Pattern p = Pattern.compile("(\\w+)");
    Matcher m = p.matcher("hello world");
    assertThat(m.replaceAll(result -> "[$1]")).isEqualTo("[hello] [world]");
  }

  @Test
  @DisplayName("replaceFirst(Function) expands group references in returned replacement")
  void replaceFirstFunctionExpandsGroupReferences() {
    Pattern p = Pattern.compile("(\\w+)");
    Matcher m = p.matcher("hello world");
    assertThat(m.replaceFirst(result -> "[$1]")).isEqualTo("[hello] world");
  }

  @Test
  @DisplayName("replaceAll(Function) rejects null replacement result")
  void replaceAllFunctionRejectsNullReplacementResult() {
    Pattern p = Pattern.compile("a");
    Matcher m = p.matcher("a");
    assertThatThrownBy(() -> m.replaceAll(result -> null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("replaceFirst(Function) rejects null replacement result")
  void replaceFirstFunctionRejectsNullReplacementResult() {
    Pattern p = Pattern.compile("a");
    Matcher m = p.matcher("a");
    assertThatThrownBy(() -> m.replaceFirst(result -> null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("replaceAll(Function) replaces all matches using function")
  void replaceAllFunction() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("a1b22c333");
    String result = m.replaceAll(mr -> "[" + mr.group() + "]");
    assertThat(result).isEqualTo("a[1]b[22]c[333]");
  }

  @Test
  @DisplayName("replaceFirst(Function) replaces only first match using function")
  void replaceFirstFunction() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("a1b22c333");
    String result = m.replaceFirst(mr -> "[" + mr.group() + "]");
    assertThat(result).isEqualTo("a[1]b22c333");
  }

  @Test
  @DisplayName("replaceAll(Function) with no matches returns original")
  void replaceAllFunctionNoMatch() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("no digits");
    String result = m.replaceAll(mr -> "X");
    assertThat(result).isEqualTo("no digits");
  }

  @Test
  @DisplayName("replaceFirst(Function) with no matches returns original")
  void replaceFirstFunctionNoMatch() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("no digits");
    String result = m.replaceFirst(mr -> "X");
    assertThat(result).isEqualTo("no digits");
  }

  @Test
  @DisplayName("replaceAll(Function) can access capture groups")
  void replaceAllFunctionWithGroups() {
    Pattern p = Pattern.compile("(\\w+)=(\\w+)");
    Matcher m = p.matcher("a=1 b=2");
    String result = m.replaceAll(mr -> mr.group(2) + ":" + mr.group(1));
    assertThat(result).isEqualTo("1:a 2:b");
  }

  @Test
  @DisplayName("replaceAll(Function) throws on null replacer")
  void replaceAllFunctionNullThrows() {
    Pattern p = Pattern.compile("x");
    Matcher m = p.matcher("x");
    assertThatThrownBy(() -> m.replaceAll((java.util.function.Function<MatchResult, String>) null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("replaceFirst(Function) throws on null replacer")
  void replaceFirstFunctionNullThrows() {
    Pattern p = Pattern.compile("x");
    Matcher m = p.matcher("x");
    assertThatThrownBy(
            () -> m.replaceFirst((java.util.function.Function<MatchResult, String>) null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisabledForCrosscheck("crosscheck wrapper cannot observe replacer mutation checks")
  @DisplayName("replaceAll(Function) detects matcher mutation from replacer")
  void replaceAllFunctionDetectsMatcherMutation() {
    Pattern p = Pattern.compile("a");
    Matcher m = p.matcher("aa");

    assertThatThrownBy(
            () ->
                m.replaceAll(
                    result -> {
                      m.find();
                      return "x";
                    }))
        .isInstanceOf(java.util.ConcurrentModificationException.class);
  }

  @Test
  @DisabledForCrosscheck("crosscheck wrapper cannot observe replacer mutation checks")
  @DisplayName("replaceFirst(Function) detects matcher mutation from replacer")
  void replaceFirstFunctionDetectsMatcherMutation() {
    Pattern p = Pattern.compile("a");
    Matcher m = p.matcher("aa");

    assertThatThrownBy(
            () ->
                m.replaceFirst(
                    result -> {
                      m.find();
                      return "x";
                    }))
        .isInstanceOf(java.util.ConcurrentModificationException.class);
  }
}
