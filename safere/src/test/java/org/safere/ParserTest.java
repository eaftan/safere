// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link Parser}. */
class ParserTest {

  /** Perl-compatible flags, used as the default for most tests. */
  private static final int PERL = ParseFlags.LIKE_PERL;

  /**
   * Flags matching the RE2 C++ test suite's default ({@code MatchNL | PerlX | PerlClasses |
   * UnicodeGroups}). These differ from {@link ParseFlags#LIKE_PERL} in that they omit {@code
   * ONE_LINE} and {@code PERL_B}.
   */
  private static final int RE2_TEST =
      ParseFlags.MATCH_NL
          | ParseFlags.PERL_X
          | ParseFlags.PERL_CLASSES
          | ParseFlags.PERL_B
          | ParseFlags.UNICODE_GROUPS;

  private static Regexp parse(String pattern) {
    return Parser.parse(pattern, PERL);
  }

  private static Regexp parse(String pattern, int flags) {
    return Parser.parse(pattern, flags);
  }

  // ---------------------------------------------------------------------------
  // 1. Literals
  // ---------------------------------------------------------------------------

  @Nested
  class Literals {

    @Test
    void singleCharacter() {
      Regexp re = parse("a");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('a');
    }

    @Test
    void singleDigit() {
      Regexp re = parse("1");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('1');
    }

    @Test
    void singlePunctuation() {
      Regexp re = parse("!");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('!');
    }

    @Test
    void multiCharBecomesLiteralString() {
      Regexp re = parse("abc");
      // Parser coalesces adjacent literals into a LITERAL_STRING.
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL_STRING);
      assertThat(re.toString()).isEqualTo("abc");
    }

    @Test
    void fiveCharLiteralString() {
      Regexp re = parse("abcde");
      assertThat(re.toString()).isEqualTo("abcde");
    }

    @Test
    void escapedDot() {
      Regexp re = parse("\\.");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('.');
    }

    @Test
    void escapedStar() {
      Regexp re = parse("\\*");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('*');
    }

    @Test
    void escapedPlus() {
      Regexp re = parse("\\+");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('+');
    }

    @Test
    void escapedQuestion() {
      Regexp re = parse("\\?");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('?');
    }

    @Test
    void escapedBackslash() {
      Regexp re = parse("\\\\");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('\\');
    }

    @Test
    void escapedCaret() {
      Regexp re = parse("\\^");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('^');
    }

    @Test
    void escapedDollar() {
      Regexp re = parse("\\$");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('$');
    }

    @Test
    void escapedPipe() {
      Regexp re = parse("\\|");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('|');
    }

    @Test
    void escapedDash() {
      Regexp re = parse("\\-");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('-');
    }

    @Test
    void escapedUnderscore() {
      Regexp re = parse("\\_");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('_');
    }

    @Test
    void multipleEscapedPunctuation() {
      Regexp re = parse("\\.\\^\\$\\\\");
      assertThat(re.toString()).isEqualTo("\\.\\^\\$\\\\");
    }

    @Test
    void literalFlag_entirePatternAsLiteral() {
      Regexp re = parse("a.b*c", ParseFlags.LITERAL);
      assertThat(re.toString()).isEqualTo("a\\.b\\*c");
    }

    @Test
    void literalFlag_specialCharsNotInterpreted() {
      Regexp re = parse("[abc]+", ParseFlags.LITERAL);
      assertThat(re.toString()).isEqualTo("\\[abc\\]\\+");
    }

    @Test
    void dashLiteral() {
      Regexp re = parse("-");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('-');
    }

    @Test
    void literalBrace() {
      Regexp re = parse("{");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('{');
    }

    @Test
    void literalCloseBrace() {
      Regexp re = parse("}");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('}');
    }
  }

  // ---------------------------------------------------------------------------
  // 2. Character Classes
  // ---------------------------------------------------------------------------

  @Nested
  class CharacterClasses {

    @Test
    void simpleCharClass() {
      Regexp re = parse("[abc]");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('a')).isTrue();
      assertThat(re.charClass.contains('b')).isTrue();
      assertThat(re.charClass.contains('c')).isTrue();
      assertThat(re.charClass.contains('d')).isFalse();
    }

    @Test
    void rangeCharClass() {
      Regexp re = parse("[a-z]");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('a')).isTrue();
      assertThat(re.charClass.contains('m')).isTrue();
      assertThat(re.charClass.contains('z')).isTrue();
      assertThat(re.charClass.contains('A')).isFalse();
    }

    @Test
    void multiRange() {
      Regexp re = parse("[0-9A-Fa-f]");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('0')).isTrue();
      assertThat(re.charClass.contains('9')).isTrue();
      assertThat(re.charClass.contains('A')).isTrue();
      assertThat(re.charClass.contains('F')).isTrue();
      assertThat(re.charClass.contains('a')).isTrue();
      assertThat(re.charClass.contains('f')).isTrue();
      assertThat(re.charClass.contains('g')).isFalse();
      assertThat(re.charClass.contains('G')).isFalse();
    }

    @Test
    void negatedCharClass() {
      Regexp re = parse("[^abc]");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('a')).isFalse();
      assertThat(re.charClass.contains('b')).isFalse();
      assertThat(re.charClass.contains('d')).isTrue();
    }

    @Test
    void negatedRange() {
      Regexp re = parse("[^a-z]");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('a')).isFalse();
      assertThat(re.charClass.contains('z')).isFalse();
      assertThat(re.charClass.contains('A')).isTrue();
      assertThat(re.charClass.contains('0')).isTrue();
    }

    @Test
    void singleCharInClass_optimizedToLiteral() {
      Regexp re = parse("[a]");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('a');
    }

    @Test
    void mixedRangeAndLiterals() {
      Regexp re = parse("[a-zABC]");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('A')).isTrue();
      assertThat(re.charClass.contains('B')).isTrue();
      assertThat(re.charClass.contains('C')).isTrue();
      assertThat(re.charClass.contains('a')).isTrue();
      assertThat(re.charClass.contains('z')).isTrue();
      assertThat(re.charClass.contains('D')).isFalse();
    }

    // -- POSIX classes --

    @Test
    void posixLower() {
      Regexp re = parse("[[:lower:]]");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('a')).isTrue();
      assertThat(re.charClass.contains('z')).isTrue();
    }

    @Test
    void posixAlpha() {
      Regexp re = parse("[[:alpha:]]");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('a')).isTrue();
      assertThat(re.charClass.contains('Z')).isTrue();
      assertThat(re.charClass.contains('0')).isFalse();
    }

    @Test
    void posixDigit() {
      Regexp re = parse("[[:digit:]]");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('0')).isTrue();
      assertThat(re.charClass.contains('9')).isTrue();
      assertThat(re.charClass.contains('a')).isFalse();
    }

    @Test
    void posixNegated() {
      Regexp re = parse("[[:^space:]]");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains(' ')).isFalse();
      assertThat(re.charClass.contains('a')).isTrue();
    }

    @Test
    void negatedPosixClass() {
      Regexp re = parse("[^[:lower:]]");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('a')).isFalse();
      assertThat(re.charClass.contains('A')).isTrue();
    }

    // -- Perl shorthand classes --

    @Test
    void perlDigit() {
      Regexp re = parse("\\d");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('0')).isTrue();
      assertThat(re.charClass.contains('9')).isTrue();
      assertThat(re.charClass.contains('a')).isFalse();
    }

    @Test
    void perlNonDigit() {
      Regexp re = parse("\\D");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('0')).isFalse();
      assertThat(re.charClass.contains('a')).isTrue();
    }

    @Test
    void perlSpace() {
      Regexp re = parse("\\s");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains(' ')).isTrue();
      assertThat(re.charClass.contains('\t')).isTrue();
      assertThat(re.charClass.contains('a')).isFalse();
    }

    @Test
    void perlNonSpace() {
      Regexp re = parse("\\S");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains(' ')).isFalse();
      assertThat(re.charClass.contains('a')).isTrue();
    }

    @Test
    void perlWord() {
      Regexp re = parse("\\w");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('a')).isTrue();
      assertThat(re.charClass.contains('Z')).isTrue();
      assertThat(re.charClass.contains('0')).isTrue();
      assertThat(re.charClass.contains('_')).isTrue();
      assertThat(re.charClass.contains(' ')).isFalse();
    }

    @Test
    void perlNonWord() {
      Regexp re = parse("\\W");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('a')).isFalse();
      assertThat(re.charClass.contains(' ')).isTrue();
    }

    @Test
    void caseInsensitiveWord() {
      Regexp re = parse("(?i)\\w");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('a')).isTrue();
      assertThat(re.charClass.contains('Z')).isTrue();
      assertThat(re.charClass.contains('_')).isTrue();
    }

    // -- Unicode property classes --

    @Test
    void unicodePropertyBraille() {
      Regexp re = parse("\\p{Braille}");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains(0x2800)).isTrue();
      assertThat(re.charClass.contains(0x28FF)).isTrue();
    }

    @Test
    void unicodePropertyBrailleNegated() {
      Regexp re = parse("\\P{Braille}");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains(0x2800)).isFalse();
      assertThat(re.charClass.contains('a')).isTrue();
    }

    @Test
    void unicodePropertyUppercaseLetter() {
      Regexp re = parse("\\p{Lu}");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('A')).isTrue();
      assertThat(re.charClass.contains('a')).isFalse();
    }

    @Test
    void unicodePropertyNegatedCaretSyntax() {
      // \p{^Braille} is the same as \P{Braille}
      Regexp re = parse("\\p{^Braille}");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains(0x2800)).isFalse();
    }

    @Test
    void unicodePropertyLatin() {
      Regexp re = parse("\\p{Latin}");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('a')).isTrue();
      assertThat(re.charClass.contains('z')).isTrue();
    }

    @Test
    void unicodePropertyNdNegated() {
      Regexp re = parse("\\P{Nd}");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('0')).isFalse();
      assertThat(re.charClass.contains('a')).isTrue();
    }

    // -- Special character class contents --

    @Test
    void escapeSequencesInClass() {
      Regexp re = parse("[\\n\\t]");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('\n')).isTrue();
      assertThat(re.charClass.contains('\t')).isTrue();
    }

    @Test
    void hexInClass() {
      // [\\x41] is a single-char class → optimized to LITERAL 'A'
      Regexp re = parse("[\\x41]");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('A');
    }

    @Test
    void dashAlone() {
      Regexp re = parse("[-]");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('-');
    }

    @Test
    void dashAtEnd() {
      Regexp re = parse("[a-]");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('a')).isTrue();
      assertThat(re.charClass.contains('-')).isTrue();
    }

    @Test
    void dashAtStart() {
      Regexp re = parse("[-a]");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('-')).isTrue();
      assertThat(re.charClass.contains('a')).isTrue();
    }

    @Test
    void negatedBackslash() {
      Regexp re = parse("[^\\\\]");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('\\')).isFalse();
      assertThat(re.charClass.contains('a')).isTrue();
    }

    @Test
    void charClassMatchesAll() {
      // [\s\S] matches every codepoint; parser may promote to ANY_CHAR.
      Regexp re = parse("[\\s\\S]");
      assertThat(re.op).isIn(RegexpOp.ANY_CHAR, RegexpOp.CHAR_CLASS);
    }
  }

  // ---------------------------------------------------------------------------
  // 3. Quantifiers
  // ---------------------------------------------------------------------------

  @Nested
  class Quantifiers {

    @Test
    void star() {
      Regexp re = parse("a*");
      assertThat(re.op).isEqualTo(RegexpOp.STAR);
      assertThat(re.sub().op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.sub().rune).isEqualTo('a');
      assertThat(re.nonGreedy()).isFalse();
    }

    @Test
    void plus() {
      Regexp re = parse("a+");
      assertThat(re.op).isEqualTo(RegexpOp.PLUS);
      assertThat(re.sub().op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.nonGreedy()).isFalse();
    }

    @Test
    void quest() {
      Regexp re = parse("a?");
      assertThat(re.op).isEqualTo(RegexpOp.QUEST);
      assertThat(re.sub().op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.nonGreedy()).isFalse();
    }

    @Test
    void nonGreedyStar() {
      Regexp re = parse("a*?");
      assertThat(re.op).isEqualTo(RegexpOp.STAR);
      assertThat(re.nonGreedy()).isTrue();
    }

    @Test
    void nonGreedyPlus() {
      Regexp re = parse("a+?");
      assertThat(re.op).isEqualTo(RegexpOp.PLUS);
      assertThat(re.nonGreedy()).isTrue();
    }

    @Test
    void nonGreedyQuest() {
      Regexp re = parse("a??");
      assertThat(re.op).isEqualTo(RegexpOp.QUEST);
      assertThat(re.nonGreedy()).isTrue();
    }

    @Test
    void repeatExact() {
      Regexp re = parse("a{2}");
      assertThat(re.op).isEqualTo(RegexpOp.REPEAT);
      assertThat(re.min).isEqualTo(2);
      assertThat(re.max).isEqualTo(2);
    }

    @Test
    void repeatRange() {
      Regexp re = parse("a{2,5}");
      assertThat(re.op).isEqualTo(RegexpOp.REPEAT);
      assertThat(re.min).isEqualTo(2);
      assertThat(re.max).isEqualTo(5);
    }

    @Test
    void repeatRange_2_3() {
      Regexp re = parse("a{2,3}");
      assertThat(re.op).isEqualTo(RegexpOp.REPEAT);
      assertThat(re.min).isEqualTo(2);
      assertThat(re.max).isEqualTo(3);
    }

    @Test
    void repeatUnbounded() {
      Regexp re = parse("a{3,}");
      assertThat(re.op).isEqualTo(RegexpOp.REPEAT);
      assertThat(re.min).isEqualTo(3);
      assertThat(re.max).isEqualTo(-1);
    }

    @Test
    void repeatNonGreedy() {
      Regexp re = parse("a{2,5}?");
      assertThat(re.op).isEqualTo(RegexpOp.REPEAT);
      assertThat(re.nonGreedy()).isTrue();
      assertThat(re.min).isEqualTo(2);
      assertThat(re.max).isEqualTo(5);
    }

    @Test
    void repeatNonGreedyExact() {
      Regexp re = parse("a{2}?");
      assertThat(re.op).isEqualTo(RegexpOp.REPEAT);
      assertThat(re.nonGreedy()).isTrue();
      assertThat(re.min).isEqualTo(2);
      assertThat(re.max).isEqualTo(2);
    }

    @Test
    void repeatNonGreedyUnbounded() {
      Regexp re = parse("a{2,}?");
      assertThat(re.op).isEqualTo(RegexpOp.REPEAT);
      assertThat(re.nonGreedy()).isTrue();
      assertThat(re.min).isEqualTo(2);
      assertThat(re.max).isEqualTo(-1);
    }

    @Test
    void quantifierOnNonCapturingGroup() {
      Regexp re = parse("(?:ab)*");
      assertThat(re.op).isEqualTo(RegexpOp.STAR);
      assertThat(re.sub().toString()).isEqualTo("ab");
    }

    @Test
    void quantifierOnCapturingGroup() {
      Regexp re = parse("(ab)*");
      assertThat(re.op).isEqualTo(RegexpOp.STAR);
      assertThat(re.sub().op).isEqualTo(RegexpOp.CAPTURE);
    }

    @Test
    void quantifierOnCharClass() {
      Regexp re = parse("[a-z]+");
      assertThat(re.op).isEqualTo(RegexpOp.PLUS);
      assertThat(re.sub().op).isEqualTo(RegexpOp.CHAR_CLASS);
    }

    @Test
    void boundedRepeatOfConcatenatedBoundedRepeats() {
      Regexp re = parse("(?:a{0,63}b{0,99}){0,5}");
      assertThat(re.op).isEqualTo(RegexpOp.REPEAT);
      assertThat(re.min).isEqualTo(0);
      assertThat(re.max).isEqualTo(5);
    }

    @Test
    void starAfterBrace() {
      // a*{ → concatenation of star(a) and literal({)
      Regexp re = parse("a*{");
      assertThat(re.op).isEqualTo(RegexpOp.CONCAT);
    }
  }

  // ---------------------------------------------------------------------------
  // 4. Groups
  // ---------------------------------------------------------------------------

  @Nested
  class Groups {

    @Test
    void capturingGroup() {
      Regexp re = parse("(a)");
      assertThat(re.op).isEqualTo(RegexpOp.CAPTURE);
      assertThat(re.cap).isEqualTo(1);
      assertThat(re.name).isNull();
      assertThat(re.sub().op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.sub().rune).isEqualTo('a');
    }

    @Test
    void capturingGroupWithAlternation() {
      Regexp re = parse("(ab|cd)");
      assertThat(re.op).isEqualTo(RegexpOp.CAPTURE);
      assertThat(re.sub().op).isEqualTo(RegexpOp.ALTERNATE);
    }

    @Test
    void nonCapturingGroup_simplifiesAway() {
      // (?:a) optimizes to just the literal
      Regexp re = parse("(?:a)");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('a');
    }

    @Test
    void nonCapturingGroupConcat() {
      // (?:ab)(?:cd) should coalesce to "abcd"
      Regexp re = parse("(?:ab)(?:cd)");
      assertThat(re.toString()).isEqualTo("abcd");
    }

    @Test
    void nonCapturingGroupWithAlternation() {
      Regexp re = parse("(?:ab|cd)");
      assertThat(re.op).isEqualTo(RegexpOp.ALTERNATE);
    }

    @Test
    void namedCapture_PythonSyntax() {
      Regexp re = parse("(?P<name>a)");
      assertThat(re.op).isEqualTo(RegexpOp.CAPTURE);
      assertThat(re.name).isEqualTo("name");
      assertThat(re.cap).isEqualTo(1);
    }

    @Test
    void namedCapture_angleBracketSyntax() {
      Regexp re = parse("(?<name>a)");
      assertThat(re.op).isEqualTo(RegexpOp.CAPTURE);
      assertThat(re.name).isEqualTo("name");
    }

    @Test
    void namedCapture_unicodeName_rejected() {
      // JDK only allows ASCII letters/digits in group names.
      assertThatThrownBy(() -> parse("(?P<\u4e2d\u6587>a)"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void nestedCaptures() {
      Regexp re = parse("((a)(b))");
      assertThat(re.op).isEqualTo(RegexpOp.CAPTURE);
      assertThat(re.cap).isEqualTo(1);
      Regexp inner = re.sub();
      assertThat(inner.op).isEqualTo(RegexpOp.CONCAT);
      assertThat(inner.subs).hasSize(2);
      assertThat(inner.subs.get(0).op).isEqualTo(RegexpOp.CAPTURE);
      assertThat(inner.subs.get(0).cap).isEqualTo(2);
      assertThat(inner.subs.get(1).op).isEqualTo(RegexpOp.CAPTURE);
      assertThat(inner.subs.get(1).cap).isEqualTo(3);
    }

    @Test
    void neverCapture_flag() {
      Regexp re = parse("(a)", PERL | ParseFlags.NEVER_CAPTURE);
      // With NEVER_CAPTURE, all parens are non-capturing, so it simplifies to literal.
      assertThat(re.op).isNotEqualTo(RegexpOp.CAPTURE);
    }

    @Test
    void multipleCaptures() {
      Regexp re = parse("(a)(b)");
      assertThat(re.op).isEqualTo(RegexpOp.CONCAT);
      assertThat(re.subs.get(0).op).isEqualTo(RegexpOp.CAPTURE);
      assertThat(re.subs.get(0).cap).isEqualTo(1);
      assertThat(re.subs.get(1).op).isEqualTo(RegexpOp.CAPTURE);
      assertThat(re.subs.get(1).cap).isEqualTo(2);
    }
  }

  // ---------------------------------------------------------------------------
  // 5. Alternation
  // ---------------------------------------------------------------------------

  @Nested
  class Alternation {

    @Test
    void twoSingleChars() {
      // a|b may be optimized to a char class by the parser.
      Regexp re = parse("a|b");
      assertThat(re.op).isIn(RegexpOp.ALTERNATE, RegexpOp.CHAR_CLASS);
    }

    @Test
    void threeSingleChars() {
      Regexp re = parse("a|b|c");
      assertThat(re.op).isIn(RegexpOp.ALTERNATE, RegexpOp.CHAR_CLASS);
    }

    @Test
    void complexAlternation() {
      Regexp re = parse("ab|cd");
      assertThat(re.op).isEqualTo(RegexpOp.ALTERNATE);
      assertThat(re.subs).hasSize(2);
    }

    @Test
    void alternationWithGroup() {
      Regexp re = parse("(a|b)c");
      assertThat(re.op).isEqualTo(RegexpOp.CONCAT);
    }

    @Test
    void emptyAlternation() {
      Regexp re = parse("|");
      assertThat(re.op).isEqualTo(RegexpOp.ALTERNATE);
    }

    @Test
    void emptyMiddle() {
      Regexp re = parse("|x|");
      assertThat(re.op).isEqualTo(RegexpOp.ALTERNATE);
    }

    @Test
    void alternationWithCapture() {
      Regexp re = parse("(a)|b");
      assertThat(re.op).isEqualTo(RegexpOp.ALTERNATE);
    }

    @Test
    void alternationWithCharClass() {
      // [ab]|c may be merged into a single char class.
      Regexp re = parse("[ab]|c");
      assertThat(re.op).isIn(RegexpOp.ALTERNATE, RegexpOp.CHAR_CLASS);
    }

    @Test
    void alternationWithDot() {
      // a|. — the parser may or may not simplify this depending on optimizations.
      Regexp re = parse("a|.", RE2_TEST);
      assertThat(re.op).isIn(RegexpOp.ANY_CHAR, RegexpOp.ALTERNATE, RegexpOp.CHAR_CLASS);
    }

    @Test
    void dotAlternationDot() {
      Regexp re = parse(".|.", RE2_TEST);
      assertThat(re.op).isIn(RegexpOp.ANY_CHAR, RegexpOp.ALTERNATE, RegexpOp.CHAR_CLASS);
    }

    @Test
    void alternationMergesCharClasses() {
      // (?:a|b)|(?:c|d) should merge into a single char class.
      Regexp re = parse("(?:a|b)|(?:c|d)");
      assertThat(re.op).isIn(RegexpOp.ALTERNATE, RegexpOp.CHAR_CLASS);
    }

    @Test
    void anchorAlternation() {
      // a|^ mixes a literal and an anchor.
      Regexp re = parse("a|^", RE2_TEST);
      assertThat(re.op).isEqualTo(RegexpOp.ALTERNATE);
    }
  }

  // ---------------------------------------------------------------------------
  // 6. Anchors
  // ---------------------------------------------------------------------------

  @Nested
  class Anchors {

    @Test
    void caret_withOneLine() {
      // LIKE_PERL includes ONE_LINE, so ^ → BEGIN_TEXT.
      Regexp re = parse("^");
      assertThat(re.op).isEqualTo(RegexpOp.BEGIN_TEXT);
    }

    @Test
    void dollar_withOneLine() {
      Regexp re = parse("$");
      assertThat(re.op).isEqualTo(RegexpOp.END_TEXT);
    }

    @Test
    void caret_multiline() {
      // (?m) clears ONE_LINE, so ^ → BEGIN_LINE.
      Regexp re = parse("(?m)^");
      assertThat(re.op).isEqualTo(RegexpOp.BEGIN_LINE);
    }

    @Test
    void dollar_multiline() {
      Regexp re = parse("(?m)$");
      assertThat(re.op).isEqualTo(RegexpOp.END_LINE);
    }

    @Test
    void caret_withoutOneLine() {
      // When ONE_LINE is not set, ^ → BEGIN_LINE.
      Regexp re = parse("^", RE2_TEST);
      assertThat(re.op).isEqualTo(RegexpOp.BEGIN_LINE);
    }

    @Test
    void dollar_withoutOneLine() {
      Regexp re = parse("$", RE2_TEST);
      assertThat(re.op).isEqualTo(RegexpOp.END_LINE);
    }

    @Test
    void beginText_explicit() {
      Regexp re = parse("\\A");
      assertThat(re.op).isEqualTo(RegexpOp.BEGIN_TEXT);
    }

    @Test
    void endText_explicit() {
      Regexp re = parse("\\z");
      assertThat(re.op).isEqualTo(RegexpOp.END_TEXT);
    }

    @Test
    void wordBoundary() {
      Regexp re = parse("\\b");
      assertThat(re.op).isEqualTo(RegexpOp.WORD_BOUNDARY);
    }

    @Test
    void noWordBoundary() {
      Regexp re = parse("\\B");
      assertThat(re.op).isEqualTo(RegexpOp.NO_WORD_BOUNDARY);
    }

    @Test
    void anchorCombinedWithLiteral() {
      Regexp re = parse("^abc$");
      assertThat(re.op).isEqualTo(RegexpOp.CONCAT);
    }
  }

  // ---------------------------------------------------------------------------
  // 7. Dot
  // ---------------------------------------------------------------------------

  @Nested
  class Dot {

    @Test
    void singleDot() {
      // Without DOT_NL, dot becomes a CHAR_CLASS that excludes '\n'.
      Regexp re = parse(".");
      assertThat(re.op).isIn(RegexpOp.ANY_CHAR, RegexpOp.CHAR_CLASS);
    }

    @Test
    void singleDot_withDotNL() {
      Regexp re = parse(".", PERL | ParseFlags.DOT_NL);
      assertThat(re.op).isEqualTo(RegexpOp.ANY_CHAR);
    }

    @Test
    void dotInConcat() {
      Regexp re = parse("a.b");
      assertThat(re.op).isEqualTo(RegexpOp.CONCAT);
      assertThat(re.subs).hasSize(3);
      assertThat(re.subs.get(0).op).isEqualTo(RegexpOp.LITERAL);
      // Dot is CHAR_CLASS (excluding \n) when DOT_NL is off.
      assertThat(re.subs.get(1).op).isIn(RegexpOp.ANY_CHAR, RegexpOp.CHAR_CLASS);
      assertThat(re.subs.get(2).op).isEqualTo(RegexpOp.LITERAL);
    }

    @Test
    void dotInLargerPattern() {
      Regexp re = parse("a.b.c");
      assertThat(re.op).isEqualTo(RegexpOp.CONCAT);
      assertThat(re.subs).hasSize(5);
    }

    @Test
    void dotWithSFlag() {
      // (?s) enables DOT_NL.
      Regexp re = parse("(?s).");
      assertThat(re.op).isEqualTo(RegexpOp.ANY_CHAR);
    }
  }

  // ---------------------------------------------------------------------------
  // 8. Escape Sequences
  // ---------------------------------------------------------------------------

  @Nested
  class EscapeSequences {

    @Test
    void newline() {
      Regexp re = parse("\\n");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('\n');
    }

    @Test
    void carriageReturn() {
      Regexp re = parse("\\r");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('\r');
    }

    @Test
    void tab() {
      Regexp re = parse("\\t");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('\t');
    }

    @Test
    void formFeed() {
      Regexp re = parse("\\f");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('\f');
    }

    @Test
    void bell() {
      Regexp re = parse("\\a");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo(0x07);
    }

    @Test
    void hexTwoDigit() {
      Regexp re = parse("\\x41");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('A');
    }

    @Test
    void hexBraces() {
      Regexp re = parse("\\x{1F600}");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo(0x1F600);
    }

    @Test
    void hexBracesSmall() {
      Regexp re = parse("\\x{61}");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('a');
    }

    @Test
    void quotedLiteral_specialChars() {
      Regexp re = parse("\\Q+|*?{[\\E");
      assertThat(re.toString()).isEqualTo("\\+\\|\\*\\?\\{\\[");
    }

    @Test
    void quotedLiteral_thenPlus() {
      Regexp re = parse("\\Q+\\E+");
      assertThat(re.op).isEqualTo(RegexpOp.PLUS);
      assertThat(re.sub().rune).isEqualTo('+');
    }

    @Test
    void quotedBackslash() {
      Regexp re = parse("\\Q\\\\E");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('\\');
    }

    @Test
    void quotedLiteral_thenStar() {
      Regexp re = parse("\\Qa\\E*");
      assertThat(re.op).isEqualTo(RegexpOp.STAR);
      assertThat(re.sub().rune).isEqualTo('a');
    }

    @Test
    void quotedLiteral_twoCharsThenStar() {
      // \Qab\E* → cat{lit{a}star{lit{b}}}
      Regexp re = parse("\\Qab\\E*");
      assertThat(re.op).isEqualTo(RegexpOp.CONCAT);
    }

    // ---- Octal escapes ----

    @Test
    void octal_nul() {
      Regexp re = parse("\\0");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo(0x00);
    }

    @Test
    void octal_singleDigit() {
      Regexp re = parse("\\07");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo(7);
    }

    @Test
    void octal_twoDigits() {
      Regexp re = parse("\\012");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('\n'); // 012 octal = 10 = newline
    }

    @Test
    void octal_maxTwoDigitsAfterZero() {
      Regexp re = parse("\\077");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo(63); // 077 octal = 63
    }

    @Test
    void octal_nonZeroLeading_twoDigits() {
      // \12 is a backreference in JDK, but RE2 treats two-digit as octal.
      Regexp re = parse("\\12");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo(10); // 12 octal = 10
    }

    @Test
    void octal_nonZeroLeading_threeDigits() {
      Regexp re = parse("\\123");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo(0x53); // 123 octal = 83 = 'S'
    }

    // ---- Escape char and control chars ----

    @Test
    void escape() {
      Regexp re = parse("\\e");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo(0x1B);
    }

    @Test
    void controlChar_A() {
      Regexp re = parse("\\cA");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo(0x01); // ctrl-A
    }

    @Test
    void controlChar_Z() {
      Regexp re = parse("\\cZ");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo(0x1A); // ctrl-Z
    }

    @Test
    void controlChar_at() {
      Regexp re = parse("\\c@");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo(0x00); // ctrl-@ = NUL
    }

    @Test
    void controlChar_openBracket() {
      Regexp re = parse("\\c[");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo(0x1B); // ctrl-[ = ESC
    }

    // ---- Named Unicode character ----

    @Test
    void namedUnicode_latinA() {
      Regexp re = parse("\\N{LATIN SMALL LETTER A}");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('a');
    }

    @Test
    void namedUnicode_smiley() {
      Regexp re = parse("\\N{WHITE SMILING FACE}");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo(0x263A);
    }

    @Test
    void namedUnicode_unknown() {
      assertThatThrownBy(() -> parse("\\N{NOT A REAL NAME}"))
          .isInstanceOf(PatternSyntaxException.class)
          .hasMessageContaining("unknown Unicode character name");
    }

    @Test
    void namedUnicode_missingBrace() {
      assertThatThrownBy(() -> parse("\\N{LATIN SMALL LETTER A"))
          .isInstanceOf(PatternSyntaxException.class);
    }
  }

  // ---------------------------------------------------------------------------
  // 9. Flags
  // ---------------------------------------------------------------------------

  @Nested
  class Flags {

    @Test
    void caseInsensitive_inline() {
      Regexp re = parse("(?i)a");
      assertThat(re.foldCase()).isTrue();
    }

    @Test
    void caseInsensitive_scoped() {
      Regexp re = parse("(?i:abc)");
      assertThat(re.foldCase()).isTrue();
    }

    @Test
    void caseInsensitive_turnedOff() {
      Regexp re = parse("(?i)a(?-i)b");
      assertThat(re.op).isEqualTo(RegexpOp.CONCAT);
      assertThat(re.subs.get(0).foldCase()).isTrue();
      assertThat(re.subs.get(1).foldCase()).isFalse();
    }

    @Test
    void dotMatchesNewline_sFlag() {
      Regexp re = parse("(?s).");
      assertThat(re.op).isEqualTo(RegexpOp.ANY_CHAR);
    }

    @Test
    void multiline_mFlag() {
      Regexp re = parse("(?m)^");
      assertThat(re.op).isEqualTo(RegexpOp.BEGIN_LINE);
    }

    @Test
    void multiline_mFlag_dollar() {
      Regexp re = parse("(?m)$");
      assertThat(re.op).isEqualTo(RegexpOp.END_LINE);
    }

    @Test
    void unicodeCharClass_UFlag() {
      // (?U) is JDK's UNICODE_CHARACTER_CLASS, not RE2's non-greedy.
      Regexp re = parse("(?U)a*");
      assertThat(re.op).isEqualTo(RegexpOp.STAR);
      assertThat(re.nonGreedy()).isFalse();
      assertThat((re.flags & ParseFlags.UNICODE_CHAR_CLASS) != 0).isTrue();
    }

    @Test
    void combinedFlags() {
      Regexp re = parse("(?is)a.b");
      assertThat(re.op).isEqualTo(RegexpOp.CONCAT);
      assertThat(re.subs.get(0).foldCase()).isTrue();
    }

    @Test
    void turnOffMultiline() {
      // (?-m)^ should be BEGIN_TEXT when ONE_LINE is restored.
      Regexp re = parse("(?-m)^", RE2_TEST);
      assertThat(re.op).isEqualTo(RegexpOp.BEGIN_TEXT);
    }

    @Test
    void turnOffMultiline_dollar() {
      Regexp re = parse("(?-m)$", RE2_TEST);
      assertThat(re.op).isEqualTo(RegexpOp.END_TEXT);
    }

    @Test
    void caseInsensitive_charClass() {
      // (?i)[a-z] should include uppercase letters.
      Regexp re = parse("(?i)[a-z]");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('A')).isTrue();
      assertThat(re.charClass.contains('Z')).isTrue();
      assertThat(re.charClass.contains('a')).isTrue();
    }

    @Test
    void caseInsensitive_posixLower() {
      Regexp re = parse("(?i)[[:lower:]]");
      assertThat(re.op).isEqualTo(RegexpOp.CHAR_CLASS);
      assertThat(re.charClass.contains('A')).isTrue();
      assertThat(re.charClass.contains('a')).isTrue();
    }
  }

  // ---------------------------------------------------------------------------
  // 10. Parse Errors
  // ---------------------------------------------------------------------------

  @Nested
  class ParseErrors {

    @Test
    void missingCloseParen() {
      assertThatThrownBy(() -> parse("(abc"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void missingCloseBracket() {
      assertThatThrownBy(() -> parse("[abc"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void unexpectedCloseParen() {
      assertThatThrownBy(() -> parse("abc)"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void nothingToRepeat_star() {
      assertThatThrownBy(() -> parse("*"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void nothingToRepeat_plus() {
      assertThatThrownBy(() -> parse("+abc"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void trailingBackslash() {
      assertThatThrownBy(() -> parse("\\"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void repeatTooLarge_1001() {
      assertThatThrownBy(() -> parse("a{1001}"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void repeatTooLarge_unbounded() {
      assertThatThrownBy(() -> parse("a{1001,}"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void repeatTooLarge_huge() {
      assertThatThrownBy(() -> parse("a{100000}"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void repeatTooLarge_hugeUnbounded() {
      assertThatThrownBy(() -> parse("a{100000,}"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void invalidNamedCapture_empty_P() {
      assertThatThrownBy(() -> parse("(?P<>a)"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void invalidNamedCapture_empty_angle() {
      assertThatThrownBy(() -> parse("(?<>a)"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void invalidNamedCapture_spaceInName_P() {
      assertThatThrownBy(() -> parse("(?P<x y>a)"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void invalidNamedCapture_spaceInName_angle() {
      assertThatThrownBy(() -> parse("(?<x y>a)"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    // Regression: (?<test_group>.*) rejected — underscore not allowed (issue #129)
    void invalidNamedCapture_underscore() {
      assertThatThrownBy(() -> parse("(?<test_group>.*)"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void invalidNamedCapture_startsWithDigit() {
      assertThatThrownBy(() -> parse("(?<1abc>a)"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void invalidNamedCapture_startsWithDigit_P() {
      assertThatThrownBy(() -> parse("(?P<1abc>a)"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void invalidNamedCapture_unicodeLetter() {
      assertThatThrownBy(() -> parse("(?<caf\u00e9>a)"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void invalidNamedCapture_connectorPunctuation() {
      assertThatThrownBy(() -> parse("(?<foo\u203fbar>a)"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void invalidNamedCapture_allDigits() {
      assertThatThrownBy(() -> parse("(?<123>a)"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void invalidPerlOp() {
      assertThatThrownBy(() -> parse("(?z)"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void unclosedGroup_withAlternation() {
      assertThatThrownBy(() -> parse("(a|b|"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void unclosedGroup_partial() {
      assertThatThrownBy(() -> parse("(a|b"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void mismatchedBrackets() {
      assertThatThrownBy(() -> parse("([a-z)"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void unclosedNamedCapture_P() {
      assertThatThrownBy(() -> parse("(?P<name>a"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void unclosedNamedCapture_angle() {
      assertThatThrownBy(() -> parse("(?<name>a"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void invalidCharRange() {
      assertThatThrownBy(() -> parse("[z-a]"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void invalidCharRange_caseInsensitive() {
      assertThatThrownBy(() -> parse("(?i)[a-Z]"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a**", "a++", "a+*", "a?*"})
    void invalidNestedRepetition(String pattern) {
      assertThatThrownBy(() -> parse(pattern))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void unclosedParen_solo() {
      assertThatThrownBy(() -> parse("("))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void unexpectedCloseParen_solo() {
      assertThatThrownBy(() -> parse(")"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void incompleteNamedCapture_P() {
      assertThatThrownBy(() -> parse("(?P<name"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void incompleteNamedCapture_angle() {
      assertThatThrownBy(() -> parse("(?<name"))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void unclosedGroup_solo_a() {
      assertThatThrownBy(() -> parse("(a"))
          .isInstanceOf(PatternSyntaxException.class);
    }
  }

  // ---------------------------------------------------------------------------
  // 11. Edge Cases
  // ---------------------------------------------------------------------------

  @Nested
  class EdgeCases {

    @Test
    void emptyPattern() {
      Regexp re = parse("");
      assertThat(re.op).isEqualTo(RegexpOp.EMPTY_MATCH);
    }

    @Test
    void singleDot() {
      // Without DOT_NL, dot is a CHAR_CLASS excluding '\n'.
      Regexp re = parse(".");
      assertThat(re.op).isIn(RegexpOp.ANY_CHAR, RegexpOp.CHAR_CLASS);
    }

    @Test
    void supplementaryUnicode_hex() {
      Regexp re = parse("\\x{1F600}");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo(0x1F600);
    }

    @Test
    void supplementaryUnicode_inClass() {
      Regexp re = parse("[\\x{1F600}]");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo(0x1F600);
    }

    @Test
    void nestedRepetition_starStar() {
      // (?:(?:a)*)* simplifies to a*
      Regexp re = parse("(?:(?:a)*)*");
      assertThat(re.op).isEqualTo(RegexpOp.STAR);
      assertThat(re.sub().op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.sub().rune).isEqualTo('a');
    }

    @Test
    void nestedRepetition_plusPlus() {
      // (?:(?:a)+)+ simplifies to a+
      Regexp re = parse("(?:(?:a)+)+");
      assertThat(re.op).isEqualTo(RegexpOp.PLUS);
      assertThat(re.sub().op).isEqualTo(RegexpOp.LITERAL);
    }

    @Test
    void nestedRepetition_questQuest() {
      // (?:(?:a)?)? simplifies to a?
      Regexp re = parse("(?:(?:a)?)?");
      assertThat(re.op).isEqualTo(RegexpOp.QUEST);
    }

    @Test
    void nestedRepetition_starPlus() {
      // (?:(?:a)*)+ simplifies to a*
      Regexp re = parse("(?:(?:a)*)+");
      assertThat(re.op).isEqualTo(RegexpOp.STAR);
    }

    @Test
    void nestedRepetition_starQuest() {
      // (?:(?:a)*)? simplifies to a*
      Regexp re = parse("(?:(?:a)*)?");
      assertThat(re.op).isEqualTo(RegexpOp.STAR);
    }

    @Test
    void nestedRepetition_plusStar() {
      // (?:(?:a)+)* simplifies to a*
      Regexp re = parse("(?:(?:a)+)*");
      assertThat(re.op).isEqualTo(RegexpOp.STAR);
    }

    @Test
    void nestedRepetition_plusQuest() {
      // (?:(?:a)+)? simplifies to a*
      Regexp re = parse("(?:(?:a)+)?");
      assertThat(re.op).isEqualTo(RegexpOp.STAR);
    }

    @Test
    void nestedRepetition_questStar() {
      // (?:(?:a)?)* simplifies to a*
      Regexp re = parse("(?:(?:a)?)*");
      assertThat(re.op).isEqualTo(RegexpOp.STAR);
    }

    @Test
    void nestedRepetition_questPlus() {
      // (?:(?:a)?)+ simplifies to a*
      Regexp re = parse("(?:(?:a)?)+");
      assertThat(re.op).isEqualTo(RegexpOp.STAR);
    }

    @Test
    void nonCapturingNoop() {
      Regexp re = parse("(?:a)");
      assertThat(re.op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.rune).isEqualTo('a');
    }

    @Test
    void commaNotRepeat() {
      // a{,2} is not a valid repeat syntax → treated as literal.
      Regexp re = parse("a{,2}");
      assertThat(re.toString()).isEqualTo("a\\{,2\\}");
    }

    @Test
    void maxRepeatBoundary() {
      // 1000 is the maximum allowed repeat count.
      Regexp re = parse("a{1000}");
      assertThat(re.op).isEqualTo(RegexpOp.REPEAT);
      assertThat(re.min).isEqualTo(1000);
      assertThat(re.max).isEqualTo(1000);
    }

    @Test
    void repeatZero() {
      Regexp re = parse("a{0}");
      // a{0} matches empty string
      assertThat(re.op).isIn(RegexpOp.REPEAT, RegexpOp.EMPTY_MATCH);
    }

    @Test
    void repeatZeroToOne() {
      Regexp re = parse("a{0,1}");
      assertThat(re.op).isIn(RegexpOp.QUEST, RegexpOp.REPEAT);
    }

    @Test
    void repeatOneOrMore() {
      Regexp re = parse("a{1,}");
      assertThat(re.op).isIn(RegexpOp.PLUS, RegexpOp.REPEAT);
    }

    @Test
    void repeatZeroOrMore() {
      Regexp re = parse("a{0,}");
      assertThat(re.op).isIn(RegexpOp.STAR, RegexpOp.REPEAT);
    }

    @Test
    void caseInsensitiveUnicodeCaseCharClassAddsUnicodeFolds() {
      // (?iu)[a-z] should include the Kelvin sign (0x212A) and long-s (0x17F).
      Regexp re = parse("(?iu)[a-z]");
      assertThat(re.charClass.contains(0x17F)).isTrue(); // ſ (long s)
      assertThat(re.charClass.contains(0x212A)).isTrue(); // K (Kelvin sign)
    }

    @Test
    void caseInsensitiveCharClassUsesAsciiFoldsWithoutUnicodeCase() {
      Regexp re = parse("(?i)[a-z]");
      assertThat(re.charClass.contains('A')).isTrue();
      assertThat(re.charClass.contains('Z')).isTrue();
      assertThat(re.charClass.contains(0x17F)).isFalse();
      assertThat(re.charClass.contains(0x212A)).isFalse();
    }

    @Test
    void beginEndText_withExplicitEscapes() {
      Regexp re = parse("\\A");
      assertThat(re.op).isEqualTo(RegexpOp.BEGIN_TEXT);
      Regexp re2 = parse("\\z");
      assertThat(re2.op).isEqualTo(RegexpOp.END_TEXT);
    }

    @Test
    void dotConcatPattern() {
      Regexp re = parse("a.");
      assertThat(re.op).isEqualTo(RegexpOp.CONCAT);
      assertThat(re.subs.get(0).op).isEqualTo(RegexpOp.LITERAL);
      assertThat(re.subs.get(1).op).isIn(RegexpOp.ANY_CHAR, RegexpOp.CHAR_CLASS);
    }
  }
}
