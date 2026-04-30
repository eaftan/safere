// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for {@link Regexp}. */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class RegexpTest {

  @Test
  void noMatch() {
    Regexp re = Regexp.noMatch(0);
    assertThat(re.op).isEqualTo(RegexpOp.NO_MATCH);
  }

  @Test
  void emptyMatch() {
    Regexp re = Regexp.emptyMatch(0);
    assertThat(re.op).isEqualTo(RegexpOp.EMPTY_MATCH);
  }

  @Test
  void literal() {
    Regexp re = Regexp.literal('a', 0);
    assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
    assertThat(re.rune).isEqualTo('a');
    assertThat(re.toString()).isEqualTo("a");
  }

  @Test
  void literalString() {
    int[] runes = {'h', 'e', 'l', 'l', 'o'};
    Regexp re = Regexp.literalString(runes, 0);
    assertThat(re.op).isEqualTo(RegexpOp.LITERAL_STRING);
    assertThat(re.runes).isEqualTo(runes);
    assertThat(re.toString()).isEqualTo("hello");
  }

  @Test
  void concat() {
    Regexp a = Regexp.literal('a', 0);
    Regexp b = Regexp.literal('b', 0);
    Regexp re = Regexp.concat(List.of(a, b), 0);
    assertThat(re.op).isEqualTo(RegexpOp.CONCAT);
    assertThat(re.subs.size()).isEqualTo(2);
    assertThat(re.toString()).isEqualTo("ab");
  }

  @Test
  void alternate() {
    Regexp a = Regexp.literal('a', 0);
    Regexp b = Regexp.literal('b', 0);
    Regexp re = Regexp.alternate(List.of(a, b), 0);
    assertThat(re.op).isEqualTo(RegexpOp.ALTERNATE);
    assertThat(re.subs.size()).isEqualTo(2);
    assertThat(re.toString()).isEqualTo("a|b");
  }

  @Test
  void star() {
    Regexp a = Regexp.literal('a', 0);
    Regexp re = Regexp.star(a, 0);
    assertThat(re.op).isEqualTo(RegexpOp.STAR);
    assertThat(re.sub()).isEqualTo(a);
    assertThat(re.toString()).isEqualTo("a*");
  }

  @Test
  void plus() {
    Regexp a = Regexp.literal('a', 0);
    Regexp re = Regexp.plus(a, 0);
    assertThat(re.op).isEqualTo(RegexpOp.PLUS);
    assertThat(re.toString()).isEqualTo("a+");
  }

  @Test
  void quest() {
    Regexp a = Regexp.literal('a', 0);
    Regexp re = Regexp.quest(a, 0);
    assertThat(re.op).isEqualTo(RegexpOp.QUEST);
    assertThat(re.toString()).isEqualTo("a?");
  }

  @Test
  void repeat_bounded() {
    Regexp a = Regexp.literal('a', 0);
    Regexp re = Regexp.repeat(a, 0, 2, 5);
    assertThat(re.op).isEqualTo(RegexpOp.REPEAT);
    assertThat(re.min).isEqualTo(2);
    assertThat(re.max).isEqualTo(5);
    assertThat(re.toString()).isEqualTo("a{2,5}");
  }

  @Test
  void repeat_unbounded() {
    Regexp a = Regexp.literal('a', 0);
    Regexp re = Regexp.repeat(a, 0, 3, -1);
    assertThat(re.min).isEqualTo(3);
    assertThat(re.max).isEqualTo(-1);
    assertThat(re.toString()).isEqualTo("a{3,}");
  }

  @Test
  void capture_unnamed() {
    Regexp a = Regexp.literal('a', 0);
    Regexp re = Regexp.capture(a, 0, 1, null);
    assertThat(re.op).isEqualTo(RegexpOp.CAPTURE);
    assertThat(re.cap).isEqualTo(1);
    assertThat(re.name).isNull();
    assertThat(re.toString()).isEqualTo("(a)");
  }

  @Test
  void capture_named() {
    Regexp a = Regexp.literal('a', 0);
    Regexp re = Regexp.capture(a, 0, 1, "foo");
    assertThat(re.name).isEqualTo("foo");
  }

  @Test
  void anyChar() {
    Regexp re = Regexp.anyChar(0);
    assertThat(re.op).isEqualTo(RegexpOp.ANY_CHAR);
    assertThat(re.toString()).isEqualTo(".");
  }

  @Test
  void anchors() {
    assertThat(Regexp.beginLine(0).toString()).isEqualTo("^");
    assertThat(Regexp.endLine(0).toString()).isEqualTo("$");
    assertThat(Regexp.beginText(0).toString()).isEqualTo("(?-m:^)");
    assertThat(Regexp.endText(0).toString()).isEqualTo("\\z");
    assertThat(Regexp.wordBoundary(0).toString()).isEqualTo("\\b");
    assertThat(Regexp.noWordBoundary(0).toString()).isEqualTo("\\B");
  }

  @Test
  void charClassNode() {
    CharClass cc = new CharClassBuilder().addRange('a', 'z').build();
    Regexp re = Regexp.charClass(cc, 0);
    assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
    assertThat(re.charClass).isNotNull();
    assertThat(re.toString()).contains("a-z");
  }

  @Test
  void haveMatch() {
    Regexp re = Regexp.haveMatch(42, 0);
    assertThat(re.op).isEqualTo(RegexpOp.HAVE_MATCH);
    assertThat(re.matchId).isEqualTo(42);
  }

  @Test
  void sub_throwsForWrongArity() {
    Regexp re = Regexp.concat(List.of(Regexp.literal('a', 0), Regexp.literal('b', 0)), 0);
    assertThatThrownBy(re::sub).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void foldCase_flag() {
    Regexp re = Regexp.literal('a', ParseFlags.FOLD_CASE);
    assertThat(re.foldCase()).isTrue();
    assertThat(re.nonGreedy()).isFalse();
  }

  @Test
  void nonGreedy_flag() {
    Regexp re = Regexp.star(Regexp.literal('a', 0), ParseFlags.NON_GREEDY);
    assertThat(re.nonGreedy()).isTrue();
    assertThat(re.foldCase()).isFalse();
  }

  // ---------------------------------------------------------------------------
  // toString coverage
  // ---------------------------------------------------------------------------

  @Test
  void toStringNoMatch() {
    Regexp re = Regexp.noMatch(0);
    assertThat(re.toString()).contains("[^");
  }

  @Test
  void toStringEmptyMatch() {
    Regexp re = Regexp.emptyMatch(0);
    // At top-level precedence, EMPTY_MATCH renders as the empty string.
    assertThat(re.toString()).isEmpty();
  }

  @Test
  void toStringHaveMatch() {
    Regexp re = Regexp.haveMatch(5, 0);
    assertThat(re.toString()).contains("HaveMatch").contains("5");
  }

  @Test
  void toStringEndTextWithDollarFlag() {
    Regexp re = Regexp.endText(ParseFlags.WAS_DOLLAR);
    assertThat(re.toString()).isEqualTo("(?-m:$)");
  }

  @Test
  void toStringNonGreedyStar() {
    Regexp re = Regexp.star(Regexp.literal('a', 0), ParseFlags.NON_GREEDY);
    assertThat(re.toString()).isEqualTo("a*?");
  }

  @Test
  void toStringNonGreedyPlus() {
    Regexp re = Regexp.plus(Regexp.literal('a', 0), ParseFlags.NON_GREEDY);
    assertThat(re.toString()).isEqualTo("a+?");
  }

  @Test
  void toStringNonGreedyQuest() {
    Regexp re = Regexp.quest(Regexp.literal('a', 0), ParseFlags.NON_GREEDY);
    assertThat(re.toString()).isEqualTo("a??");
  }

  @Test
  void toStringNonGreedyRepeat() {
    Regexp re =
        Regexp.repeat(Regexp.literal('a', 0), ParseFlags.NON_GREEDY, 2, 5);
    assertThat(re.toString()).isEqualTo("a{2,5}?");
  }

  @Test
  void toStringExactRepeat() {
    Regexp re = Regexp.repeat(Regexp.literal('a', 0), 0, 3, 3);
    assertThat(re.toString()).isEqualTo("a{3}");
  }

  @Test
  void toStringConcatNeedsParen() {
    // Concat inside a quantifier needs (?:...) wrapping.
    Regexp ab =
        Regexp.concat(
            List.of(Regexp.literal('a', 0), Regexp.literal('b', 0)), 0);
    Regexp star = Regexp.star(ab, 0);
    assertThat(star.toString()).isEqualTo("(?:ab)*");
  }

  @Test
  void toStringNamedCapture() {
    Regexp re = Regexp.capture(Regexp.literal('a', 0), 0, 1, "foo");
    assertThat(re.toString()).isEqualTo("(?<foo>a)");
  }

  @Test
  void toStringFoldCaseLiteral() {
    Regexp re = Regexp.literal('a', ParseFlags.FOLD_CASE);
    assertThat(re.toString()).isEqualTo("[Aa]");
  }

  // ---------------------------------------------------------------------------
  // Quantifier squashing
  // ---------------------------------------------------------------------------

  @Test
  void starOfStarIsIdentity() {
    Regexp a = Regexp.literal('a', 0);
    Regexp star1 = Regexp.star(a, 0);
    Regexp star2 = Regexp.star(star1, 0);
    assertThat(star2).isSameAs(star1);
  }

  @Test
  void plusOfStarBecomesStar() {
    Regexp a = Regexp.literal('a', 0);
    Regexp star = Regexp.star(a, 0);
    Regexp result = Regexp.plus(star, 0);
    assertThat(result.op).isEqualTo(RegexpOp.STAR);
  }

  @Test
  void questOfPlusBecomesStar() {
    Regexp a = Regexp.literal('a', 0);
    Regexp plus = Regexp.plus(a, 0);
    Regexp result = Regexp.quest(plus, 0);
    assertThat(result.op).isEqualTo(RegexpOp.STAR);
    assertThat(result.sub().rune).isEqualTo('a');
  }

  @Test
  void plusOfPlusIsIdentity() {
    Regexp a = Regexp.literal('a', 0);
    Regexp plus1 = Regexp.plus(a, 0);
    Regexp plus2 = Regexp.plus(plus1, 0);
    assertThat(plus2).isSameAs(plus1);
  }

  @Test
  void questOfQuestIsIdentity() {
    Regexp a = Regexp.literal('a', 0);
    Regexp q1 = Regexp.quest(a, 0);
    Regexp q2 = Regexp.quest(q1, 0);
    assertThat(q2).isSameAs(q1);
  }

  @Test
  void nsubNullSubs() {
    Regexp re = Regexp.literal('a', 0);
    assertThat(re.nsub()).isEqualTo(0);
  }

  @Test
  void subNullSubsThrows() {
    Regexp re = Regexp.literal('a', 0);
    assertThatThrownBy(re::sub).isInstanceOf(IllegalStateException.class);
  }
}
