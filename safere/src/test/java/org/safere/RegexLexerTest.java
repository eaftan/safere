// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("Lexer source spans and lookahead are SafeRE implementation contracts")
class RegexLexerTest {
  @Test
  void completeQuantifiersKeepOriginalUtf16Spans() {
    String regex = "😀a+\\Q\\E?";
    RegexLexer lexer = new RegexLexer(regex, ParseFlags.LIKE_PERL);
    RegexLexer.Token token = lexer.next();
    assertThat(token.rune).isEqualTo(0x1f600);
    assertThat(token.start).isZero();
    assertThat(token.end).isEqualTo(2);
    lexer.next();
    token = lexer.next();
    assertThat(token.kind).isEqualTo(RegexLexer.Kind.REPEAT);
    assertThat(token.mode).isEqualTo(RegexLexer.RepetitionMode.RELUCTANT);
    assertThat(token.start).isEqualTo(regex.indexOf('+'));
    assertThat(token.end).isEqualTo(regex.length());
  }

  @Test
  void unpairedUnicodeEscapeLookaheadKeepsSeparateSourceSpans() {
    RegexLexer lexer = new RegexLexer("\\uD800\\u0041", ParseFlags.LIKE_PERL);
    RegexLexer.Token first = lexer.next();
    assertThat(first.rune).isEqualTo(0xd800);
    assertThat(first.start).isZero();
    assertThat(first.end).isEqualTo(6);
    RegexLexer.Token second = lexer.next();
    assertThat(second.rune).isEqualTo('A');
    assertThat(second.start).isEqualTo(6);
    assertThat(second.end).isEqualTo(12);
  }

  @Test
  void groupDelimitersLeaveTheNextModeToTheParser() {
    RegexLexer lexer = new RegexLexer("(?x:a b) c", ParseFlags.LIKE_PERL);
    RegexLexer.Token header = lexer.next();
    assertThat(header.kind).isEqualTo(RegexLexer.Kind.SCOPED_FLAGS);
    lexer.setFlags(header.flags);
    assertThat(lexer.next().rune).isEqualTo('a');
    assertThat(lexer.next().rune).isEqualTo('b');
    assertThat(lexer.next().kind).isEqualTo(RegexLexer.Kind.CLOSE);
    lexer.setFlags(ParseFlags.LIKE_PERL);
    assertThat(lexer.next().rune).isEqualTo(' ');
    assertThat(lexer.next().rune).isEqualTo('c');
  }

  @Test
  void modeChangesCannotInvalidateAnOutstandingClassToken() {
    RegexLexer lexer = new RegexLexer("a", ParseFlags.LIKE_PERL);
    lexer.peekClass();
    assertThatThrownBy(() -> lexer.setFlags(ParseFlags.LIKE_PERL | ParseFlags.COMMENTS))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @Tag("work-counter")
  void surrogateLookaheadConsumesLongTriviaOnlyOnce() {
    String regex = "\\uD83D\\uDE" + " #c\n".repeat(512) + "00";
    long work =
        WorkCounter.countForTesting(
            () -> {
              RegexLexer lexer = new RegexLexer(regex, ParseFlags.LIKE_PERL | ParseFlags.COMMENTS);
              assertThat(lexer.next().rune).isEqualTo(0x1f600);
              assertThat(lexer.next().kind).isEqualTo(RegexLexer.Kind.END);
            });
    assertThat(work).isPositive().isLessThan(regex.length() * 2L);
  }

  @Test
  @Tag("work-counter")
  void repeatedClassPeeksDoNotRescanUnboundedTrivia() {
    String regex = "\\Q\\E #ignored\\Q\n".repeat(512) + "a";
    long work =
        WorkCounter.countForTesting(
            () -> {
              RegexLexer lexer = new RegexLexer(regex, ParseFlags.LIKE_PERL | ParseFlags.COMMENTS);
              for (int i = 0; i < 1000; i++) {
                assertThat(lexer.peekClass().rune).isEqualTo('a');
              }
              lexer.takeClass();
              assertThat(lexer.next().kind).isEqualTo(RegexLexer.Kind.END);
              assertThat(lexer.sawCommentQuoting()).isTrue();
            });
    assertThat(work).isPositive().isLessThan(regex.length() * 2L);
  }
}
