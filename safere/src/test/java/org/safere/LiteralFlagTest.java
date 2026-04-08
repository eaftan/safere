// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Pattern#LITERAL} flag behavior. When LITERAL is set, all metacharacters are
 * treated as literal characters and the pattern is not parsed as a regular expression.
 */
class LiteralFlagTest {

  @Test
  void dotIsLiteral() {
    Pattern p = Pattern.compile("a.b", Pattern.LITERAL);
    assertThat(p.matcher("a.b").matches()).isTrue();
    assertThat(p.matcher("axb").matches()).isFalse();
  }

  @Test
  void starIsLiteral() {
    Pattern p = Pattern.compile("a*", Pattern.LITERAL);
    assertThat(p.matcher("a*").matches()).isTrue();
    assertThat(p.matcher("aaa").matches()).isFalse();
    assertThat(p.matcher("").matches()).isFalse();
  }

  @Test
  void plusIsLiteral() {
    Pattern p = Pattern.compile("a+", Pattern.LITERAL);
    assertThat(p.matcher("a+").matches()).isTrue();
    assertThat(p.matcher("aaa").matches()).isFalse();
  }

  @Test
  void questionMarkIsLiteral() {
    Pattern p = Pattern.compile("a?", Pattern.LITERAL);
    assertThat(p.matcher("a?").matches()).isTrue();
    assertThat(p.matcher("a").matches()).isFalse();
    assertThat(p.matcher("").matches()).isFalse();
  }

  @Test
  void bracketsAreLiteral() {
    Pattern p = Pattern.compile("[abc]", Pattern.LITERAL);
    assertThat(p.matcher("[abc]").matches()).isTrue();
    assertThat(p.matcher("a").matches()).isFalse();
  }

  @Test
  void parenthesesAreLiteral() {
    Pattern p = Pattern.compile("(abc)", Pattern.LITERAL);
    assertThat(p.matcher("(abc)").matches()).isTrue();
    assertThat(p.matcher("abc").matches()).isFalse();
  }

  @Test
  void pipeIsLiteral() {
    Pattern p = Pattern.compile("a|b", Pattern.LITERAL);
    assertThat(p.matcher("a|b").matches()).isTrue();
    assertThat(p.matcher("a").matches()).isFalse();
    assertThat(p.matcher("b").matches()).isFalse();
  }

  @Test
  void caretIsLiteral() {
    Pattern p = Pattern.compile("^abc", Pattern.LITERAL);
    assertThat(p.matcher("^abc").matches()).isTrue();
    assertThat(p.matcher("abc").matches()).isFalse();
  }

  @Test
  void dollarIsLiteral() {
    Pattern p = Pattern.compile("abc$", Pattern.LITERAL);
    assertThat(p.matcher("abc$").matches()).isTrue();
    assertThat(p.matcher("abc").matches()).isFalse();
  }

  @Test
  void backslashNIsLiteral() {
    // Under LITERAL, \n is a literal backslash followed by 'n', not a newline.
    Pattern p = Pattern.compile("\\n", Pattern.LITERAL);
    assertThat(p.matcher("\\n").matches()).isTrue();
    assertThat(p.matcher("\n").matches()).isFalse();
  }

  @Test
  void backslashTIsLiteral() {
    Pattern p = Pattern.compile("\\t", Pattern.LITERAL);
    assertThat(p.matcher("\\t").matches()).isTrue();
    assertThat(p.matcher("\t").matches()).isFalse();
  }

  @Test
  void curlyBracesAreLiteral() {
    Pattern p = Pattern.compile("a{2,3}", Pattern.LITERAL);
    assertThat(p.matcher("a{2,3}").matches()).isTrue();
    assertThat(p.matcher("aa").matches()).isFalse();
    assertThat(p.matcher("aaa").matches()).isFalse();
  }

  @Test
  void literalWithCaseInsensitive() {
    Pattern p = Pattern.compile("Hello.World", Pattern.LITERAL | Pattern.CASE_INSENSITIVE);
    assertThat(p.matcher("Hello.World").matches()).isTrue();
    assertThat(p.matcher("hello.world").matches()).isTrue();
    assertThat(p.matcher("HELLO.WORLD").matches()).isTrue();
    assertThat(p.matcher("HelloXWorld").matches()).isFalse();
  }

  @Test
  void literalWithComments() {
    // LITERAL takes precedence over COMMENTS — whitespace is preserved.
    Pattern p = Pattern.compile("a b # comment", Pattern.LITERAL | Pattern.COMMENTS);
    assertThat(p.matcher("a b # comment").matches()).isTrue();
    assertThat(p.matcher("ab").matches()).isFalse();
  }

  @Test
  void emptyLiteralPattern() {
    Pattern p = Pattern.compile("", Pattern.LITERAL);
    assertThat(p.matcher("").matches()).isTrue();
    assertThat(p.matcher("a").find()).isTrue(); // empty pattern finds everywhere
  }

  @Test
  void unicodeCharsInLiteral() {
    Pattern p = Pattern.compile("café☕", Pattern.LITERAL);
    assertThat(p.matcher("café☕").matches()).isTrue();
    assertThat(p.matcher("cafe☕").matches()).isFalse();
  }

  @Test
  void findWithLiteral() {
    Pattern p = Pattern.compile("a.b", Pattern.LITERAL);
    Matcher m = p.matcher("xa.bya.bz");
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("a.b");
    assertThat(m.start()).isEqualTo(1);
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(5);
    assertThat(m.find()).isFalse();
  }

  @Test
  void replaceAllWithLiteral() {
    Pattern p = Pattern.compile("a+b", Pattern.LITERAL);
    assertThat(p.matcher("xa+by").replaceAll("Z")).isEqualTo("xZy");
    assertThat(p.matcher("xaaby").replaceAll("Z")).isEqualTo("xaaby");
  }

  @Test
  void splitWithLiteral() {
    Pattern p = Pattern.compile(".", Pattern.LITERAL);
    String[] parts = p.split("a.b.c");
    assertThat(parts).containsExactly("a", "b", "c");
  }

  @Test
  void backslashDIsLiteral() {
    // \d under LITERAL is literal backslash + d, not a digit class.
    Pattern p = Pattern.compile("\\d", Pattern.LITERAL);
    assertThat(p.matcher("\\d").matches()).isTrue();
    assertThat(p.matcher("5").matches()).isFalse();
  }

  @Test
  void multipleMetacharsInSequence() {
    Pattern p = Pattern.compile("(a+|b*)[c-d]", Pattern.LITERAL);
    assertThat(p.matcher("(a+|b*)[c-d]").matches()).isTrue();
    assertThat(p.matcher("aac").matches()).isFalse();
  }
}
