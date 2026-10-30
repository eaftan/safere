// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link Simplifier}, ported from RE2's {@code simplify_test.cc}. Tests parse each
 * regexp, simplify it, then compare the toString() output against the expected simplified form.
 */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class SimplifierTest {

  private static final int FLAGS =
      ParseFlags.MATCH_NL | (ParseFlags.LIKE_PERL & ~ParseFlags.ONE_LINE);

  @Test
  void greedyNestedAtomRepeatsKeepTheLatestCaptureBeforeTheSuffix() {
    List<String> atoms = List.of("(?<g>[ab])", "(?<g>ab)", "(?<g>😀)", "((?<g>a))");
    List<String> units = List.of("a", "ab", "😀", "a");
    for (int atom = 0; atom < atoms.size(); atom++) {
      for (int min : List.of(0, 1)) {
        for (int max : List.of(2, 3, 8)) {
          for (String outer : List.of("*", "+")) {
            String regex =
                "\\A(?:(?:" + atoms.get(atom) + "){" + min + "," + max + "})" + outer + "b\\z";
            for (int count : List.of(0, 1, 2, 5, 8)) {
              String body = units.get(atom).repeat(count);
              Matcher matcher = Pattern.compile(regex).matcher(body + "b");
              boolean matched = count != 0 || min == 0 || outer.equals("*");
              assertThat(matcher.matches()).as("/%s/ on %s", regex, body + "b").isEqualTo(matched);
              if (matched) {
                assertThat(matcher.start("g"))
                    .isEqualTo(count == 0 ? -1 : body.length() - units.get(atom).length());
                assertThat(matcher.end("g")).isEqualTo(count == 0 ? -1 : body.length());
              }
            }
          }
        }
      }
    }
  }

  @Test
  void nestedRepeatGroupingRemainsObservableForHigherMinimaAndLazyOrder() {
    Matcher minimum = Pattern.compile("(?:(?:(a)){3,4})*").matcher("aaaaaa");
    assertThat(minimum.find()).isTrue();
    assertThat(minimum.end()).isEqualTo(4);
    assertThat(minimum.start(1)).isEqualTo(3);

    Matcher lazyOuter = Pattern.compile("(?:(?:(?<g>[ab])){0,2})*?b").matcher("abb");
    assertThat(lazyOuter.find()).isTrue();
    assertThat(lazyOuter.end()).isEqualTo(3);
    assertThat(lazyOuter.start("g")).isEqualTo(1);

    Matcher lazyInner = Pattern.compile("(?:(?:(?<g>[ab])){0,2}?)*b").matcher("abb");
    assertThat(lazyInner.find()).isTrue();
    assertThat(lazyInner.end()).isEqualTo(2);
    assertThat(lazyInner.start("g")).isEqualTo(0);

    Matcher chunk = Pattern.compile("((a){0,2})*").matcher("aaaa");
    assertThat(chunk.find()).isTrue();
    assertThat(chunk.start(1)).isEqualTo(4);
    assertThat(chunk.end(1)).isEqualTo(4);
    assertThat(chunk.start(2)).isEqualTo(3);
  }

  @Test
  void greedyNestedAtomRepeatProgramsDoNotGrowWithChunkLimit() {
    for (String atom : List.of("(a)", "((a))", "([ab])", "(ab)", "(😀)", "(.)")) {
      for (int minimum : List.of(0, 1)) {
        for (String outer : List.of("*", "+")) {
          String flat = minimum == 1 && outer.equals("+") ? "+" : "*";
          int expected =
              Pattern.compile("^(?:" + atom + ")" + flat + "b$").analysis().programSize();
          for (int maximum : List.of(2, 32, 1000)) {
            Pattern nested =
                Pattern.compile(
                    "^(?:" + atom + "{" + minimum + "," + maximum + "})" + outer + "b$");
            assertThat(nested.analysis().programSize())
                .as("/%s/ has no observable chunk boundaries", nested)
                .isEqualTo(expected);
          }
        }
      }
    }
  }

  private static String simplify(String pattern) {
    Regexp re = Parser.parse(pattern, FLAGS);
    Regexp sre = Simplifier.simplify(re);
    return sre.toString();
  }

  static Stream<Arguments> simplifyTests() {
    return Stream.of(
        // Already-simple constructs
        Arguments.of("a", "a"),
        Arguments.of("ab", "ab"),
        Arguments.of("a|b", "a|b"),
        Arguments.of("ab|cd", "ab|cd"),
        Arguments.of("(ab)*", "(ab)*"),
        Arguments.of("(ab)+", "(ab)+"),
        Arguments.of("(ab)?", "(ab)?"),
        Arguments.of(".", "."),
        Arguments.of("^", "^"),
        Arguments.of("$", "$"),
        Arguments.of("[ac]", "[ac]"),
        Arguments.of("[^ac]", "[^ac]"),

        // JDK treats POSIX bracket-class spelling as ordinary character-class text.
        Arguments.of("[[:alnum:]]", "[:al-nu]"),
        Arguments.of("[[:alpha:]]", "[:ahlp]"),
        Arguments.of("[[:blank:]]", "[:a-bk-ln]"),
        Arguments.of("[[:cntrl:]]", "[:clnrt]"),
        Arguments.of("[[:digit:]]", "[:dgit]"),
        Arguments.of("[[:graph:]]", "[:ag-hpr]"),
        Arguments.of("[[:lower:]]", "[:elorw]"),
        Arguments.of("[[:print:]]", "[:inprt]"),
        Arguments.of("[[:punct:]]", "[:cnpt-u]"),
        Arguments.of("[[:space:]]", "[:aceps]"),
        Arguments.of("[[:upper:]]", "[:epru]"),
        Arguments.of("[[:xdigit:]]", "[:dgitx]"),

        // Perl character classes
        Arguments.of("\\d", "[0-9]"),
        Arguments.of("\\s", "[\\t-\\r ]"),
        Arguments.of("\\w", "[0-9A-Z_a-z]"),
        Arguments.of("\\D", "[^0-9]"),
        Arguments.of("\\S", "[^\\t-\\r ]"),
        Arguments.of("\\W", "[^0-9A-Z_a-z]"),
        Arguments.of("[\\d]", "[0-9]"),
        Arguments.of("[\\s]", "[\\t-\\r ]"),
        Arguments.of("[\\w]", "[0-9A-Z_a-z]"),
        Arguments.of("[\\D]", "[^0-9]"),
        Arguments.of("[\\S]", "[^\\t-\\r ]"),
        Arguments.of("[\\W]", "[^0-9A-Z_a-z]"),

        // Posix repetitions
        Arguments.of("a{1}", "a"),
        Arguments.of("a{2}", "aa"),
        Arguments.of("a{5}", "aaaaa"),
        Arguments.of("a{0,1}", "a?"),
        Arguments.of("(a){0,2}", "(?:(a)(a)?)?"),
        Arguments.of("(a){0,4}", "(?:(a)(?:(a)(?:(a)(a)?)?)?)?"),
        Arguments.of("(a){2,6}", "(a)(a)(?:(a)(?:(a)(?:(a)(a)?)?)?)?"),
        Arguments.of("a{0,2}", "(?:aa?)?"),
        Arguments.of("a{0,4}", "(?:a(?:a(?:aa?)?)?)?"),
        Arguments.of("a{2,6}", "aa(?:a(?:a(?:aa?)?)?)?"),
        Arguments.of("a{0,}", "a*"),
        Arguments.of("a{1,}", "a+"),
        Arguments.of("a{2,}", "aa+"),
        Arguments.of("a{5,}", "aaaaa+"),

        // Test that operators simplify their arguments.
        Arguments.of("(?:a{1,}){1,}", "a+"),
        Arguments.of("(a{1,}b{1,})", "(a+b+)"),
        Arguments.of("a{1,}|b{1,}", "a+|b+"),
        Arguments.of("(?:a{1,})*", "a*"),
        Arguments.of("(?:a{1,})+", "a+"),
        Arguments.of("(?:a{1,})?", "a*"),
        Arguments.of("a{0}", ""),

        // Character class simplification
        Arguments.of("[ab]", "[a-b]"),
        Arguments.of("[a-za-za-z]", "[a-z]"),
        Arguments.of("[A-Za-zA-Za-z]", "[A-Za-z]"),
        Arguments.of("[ABCDEFGH]", "[A-H]"),
        Arguments.of("[AB-CD-EF-GH]", "[A-H]"),
        Arguments.of("[W-ZP-XE-R]", "[E-Z]"),
        Arguments.of("[a-ee-gg-m]", "[a-m]"),
        Arguments.of("[a-ea-ha-m]", "[a-m]"),
        Arguments.of("[a-ma-ha-e]", "[a-m]"),
        Arguments.of("[a-zA-Z0-9 -~]", "[ -~]"),

        // Negated literal text character classes
        Arguments.of("[^[:cntrl:][:^cntrl:]]", "[^:\\^clnrt]"),

        // Literal text character classes
        Arguments.of("[[:cntrl:][:^cntrl:]]", "[:\\^clnrt]"),

        // Unicode case folding
        Arguments.of("(?i)A", "A"),
        Arguments.of("(?i)a", "[Aa]"),
        Arguments.of("(?i)K", "K"),
        Arguments.of("(?i)k", "[Kk]"),
        Arguments.of("(?i)\\x{212a}", "\\x{212a}"),
        Arguments.of("(?i)[a-z]", "[A-Za-z]"),
        Arguments.of("(?iu)K", "[Kk\\x{212a}]"),
        Arguments.of("(?iu)k", "[Kk\\x{212a}]"),
        Arguments.of("(?iu)\\x{212a}", "[Kk\\x{212a}]"),
        Arguments.of("(?iu)[a-z]", "[A-Za-z\\x{130}-\\x{131}\\x{17f}\\x{212a}]"),
        Arguments.of("(?i)[\\x00-\\x{FFFD}]", "[\\x00-\\x{fffd}]"),
        Arguments.of("(?i)[\\x00-\\x{10ffff}]", "."),

        // Empty string as a regular expression.
        Arguments.of("(a|b|)", "(a|b|(?:))"),
        Arguments.of("(|)", "((?:)|(?:))"),
        Arguments.of("a()", "a()"),
        Arguments.of("(()|())", "(()|())"),
        Arguments.of("(a|)", "(a|(?:))"),
        Arguments.of("ab()cd()", "ab()cd()"),
        Arguments.of("()", "()"),
        Arguments.of("()*", "()*"),
        Arguments.of("()+", "()+"),
        Arguments.of("()?", "()?"),
        Arguments.of("(){0}", ""),
        Arguments.of("(){1}", "()"),
        Arguments.of("(){1,}", "()+"),
        Arguments.of("(){0,2}", "()?"),
        Arguments.of("(?:()){0,2}", "()?"),

        // Empty-width ops: repetition count capped at 1
        Arguments.of("(?:^){0,}", "^*"),
        Arguments.of("(?:$){28,}", "$"),
        Arguments.of("(?-m:^){0,30}", "(?-m:^)?"),
        Arguments.of("(?-m:$){28,30}", "(?-m:$)"),
        Arguments.of("\\b(?:\\b\\B){999}\\B", "\\b\\b\\B\\B"),
        Arguments.of("\\b(?:\\b|\\B){999}\\B", "\\b(?:\\b|\\B)\\B"),
        // NonGreedy should also be handled.
        Arguments.of("(?:^){0,}?", "^*?"),
        Arguments.of("(?:$){28,}?", "$"),
        Arguments.of("(?-m:^){0,30}?", "(?-m:^)??"),
        Arguments.of("(?-m:$){28,30}?", "(?-m:$)"),
        Arguments.of("\\b(?:\\b\\B){999}?\\B", "\\b\\b\\B\\B"),
        Arguments.of("\\b(?:\\b|\\B){999}?\\B", "\\b(?:\\b|\\B)\\B"),

        // Coalescing: two-op combinations with a literal
        Arguments.of("a*a*", "a*"),
        Arguments.of("a*a+", "a+"),
        Arguments.of("a*a?", "a*"),
        Arguments.of("a*a{2}", "aa+"),
        Arguments.of("a*a{2,}", "aa+"),
        Arguments.of("a*a{2,3}", "aa+"),
        Arguments.of("a+a*", "a+"),
        Arguments.of("a+a+", "aa+"),
        Arguments.of("a+a?", "a+"),
        Arguments.of("a+a{2}", "aaa+"),
        Arguments.of("a+a{2,}", "aaa+"),
        Arguments.of("a+a{2,3}", "aaa+"),
        Arguments.of("a?a*", "a*"),
        Arguments.of("a?a+", "a+"),
        Arguments.of("a?a?", "(?:aa?)?"),
        Arguments.of("a?a{2}", "aaa?"),
        Arguments.of("a?a{2,}", "aa+"),
        Arguments.of("a?a{2,3}", "aa(?:aa?)?"),
        Arguments.of("a{2}a*", "aa+"),
        Arguments.of("a{2}a+", "aaa+"),
        Arguments.of("a{2}a?", "aaa?"),
        Arguments.of("a{2}a{2}", "aaaa"),
        Arguments.of("a{2}a{2,}", "aaaa+"),
        Arguments.of("a{2}a{2,3}", "aaaaa?"),
        Arguments.of("a{2,}a*", "aa+"),
        Arguments.of("a{2,}a+", "aaa+"),
        Arguments.of("a{2,}a?", "aa+"),
        Arguments.of("a{2,}a{2}", "aaaa+"),
        Arguments.of("a{2,}a{2,}", "aaaa+"),
        Arguments.of("a{2,}a{2,3}", "aaaa+"),
        Arguments.of("a{2,3}a*", "aa+"),
        Arguments.of("a{2,3}a+", "aaa+"),
        Arguments.of("a{2,3}a?", "aa(?:aa?)?"),
        Arguments.of("a{2,3}a{2}", "aaaaa?"),
        Arguments.of("a{2,3}a{2,}", "aaaa+"),
        Arguments.of("a{2,3}a{2,3}", "aaaa(?:aa?)?"),
        // With a char class and any char (skipping \C which is not supported in Java):
        Arguments.of("\\d*\\d*", "[0-9]*"),
        Arguments.of(".*.*", ".*"),
        // FoldCase works, but must be consistent:
        Arguments.of("(?i)A*a*", "A*[Aa]*"),
        Arguments.of("(?i)a+A+", "[Aa]+A+"),
        Arguments.of("(?i)A*(?-i)a*", "A*a*"),
        Arguments.of("(?i)a+(?-i)A+", "[Aa]+A+"),
        // NonGreedy works, but must be consistent:
        Arguments.of("a*?a*?", "a*?"),
        Arguments.of("a+?a+?", "aa+?"),
        Arguments.of("a*?a*", "a*?a*"),
        Arguments.of("a+a+?", "a+a+?"),
        // The second element is the literal, char class, any char:
        Arguments.of("a*a", "a+"),
        Arguments.of("\\d*\\d", "[0-9]+"),
        Arguments.of(".*.", ".+"),
        // FoldCase consistent:
        Arguments.of("(?i)A*a", "A*[Aa]"),
        Arguments.of("(?i)a+A", "[Aa]+A"),
        Arguments.of("(?i)A*(?-i)a", "A*a"),
        Arguments.of("(?i)a+(?-i)A", "[Aa]+A"),
        // The second element is a literal string beginning with the literal:
        Arguments.of("a*aa", "aa+"),
        Arguments.of("a*aab", "aa+b"),
        // FoldCase consistent:
        Arguments.of("(?i)a*aa", "[Aa][Aa]+"),
        Arguments.of("(?i)a*aab", "[Aa][Aa]+[Bb]"),
        Arguments.of("(?i)a*(?-i)aa", "[Aa]*aa"),
        Arguments.of("(?i)a*(?-i)aab", "[Aa]*aab"),
        // Negative tests with mismatching ops:
        Arguments.of("a*b*", "a*b*"),
        Arguments.of("\\d*\\D*", "[0-9]*[^0-9]*"),
        Arguments.of("a+b", "a+b"),
        Arguments.of("\\d+\\D", "[0-9]+[^0-9]"),
        Arguments.of("a?bb", "a?bb"),
        // Negative tests with capturing groups:
        Arguments.of("(a*)a*", "(a*)a*"),
        Arguments.of("a+(a)", "a+(a)"),
        Arguments.of("(a?)(aa)", "(a?)(aa)"),
        // Just for fun:
        Arguments.of("aa*aa+aa?aa{2}aaa{2,}aaa{2,3}a", "aaaaaaaaaaaaaaaa+"),

        // Repeat min/max must be preserved during coalescing.
        Arguments.of("(?:a*aab){2}", "aa+baa+b"),

        // Capture cap must be preserved during coalescing.
        Arguments.of("(a*aab)", "(aa+b)"),

        // Squashing **, ++, ?? etc.
        Arguments.of("(?:(?:a){0,}){0,}", "a*"),
        Arguments.of("(?:(?:a){1,}){1,}", "a+"),
        Arguments.of("(?:(?:a){0,1}){0,1}", "a?"),
        Arguments.of("(?:(?:a){0,}){1,}", "a*"),
        Arguments.of("(?:(?:a){0,}){0,1}", "a*"),
        Arguments.of("(?:(?:a){1,}){0,}", "a*"),
        Arguments.of("(?:(?:a){1,}){0,1}", "a*"),
        Arguments.of("(?:(?:a){0,1}){0,}", "a*"),
        Arguments.of("(?:(?:a){0,1}){1,}", "a*"));
  }

  @ParameterizedTest(name = "[{index}] simplify({0}) = {1}")
  @MethodSource("simplifyTests")
  void simplifyRegexp(String input, String expected) {
    assertThat(simplify(input)).isEqualTo(expected);
  }

  @Test
  void alreadySimpleReturnsSameObject() {
    Regexp re = Parser.parse("a", FLAGS);
    Regexp sre = Simplifier.simplify(re);
    // Already-simple regexps should return the same object.
    assertThat(sre).isSameAs(re);
  }

  @Test
  void deeplyNestedCapturesRemainStackSafeDuringCompileSimplification() {
    String regex = "(".repeat(10_000) + "a" + ")".repeat(10_000) + "*";

    assertThatNoException().isThrownBy(() -> Pattern.compile(regex));
  }

  @Test
  void deeplyNestedSimpleGroupsRemainStackSafeDuringSimplification() {
    Regexp re = Parser.parse("(?:".repeat(10_000) + "a" + ")".repeat(10_000), FLAGS);

    assertThat(Simplifier.simplify(re).toString()).isEqualTo("a");
  }

  @Test
  void equalBasic() {
    Regexp a = Parser.parse("a+b*", FLAGS);
    Regexp b = Parser.parse("a+b*", FLAGS);
    assertThat(Simplifier.equal(a, b)).isTrue();
  }

  @Test
  void equalDifferent() {
    Regexp a = Parser.parse("a+b*", FLAGS);
    Regexp b = Parser.parse("a+c*", FLAGS);
    assertThat(Simplifier.equal(a, b)).isFalse();
  }

  // ---------------------------------------------------------------------------
  // Simplify repeat edge cases
  // ---------------------------------------------------------------------------

  @Test
  void simplifyRepeatExactZero() {
    // a{0} should simplify to empty match.
    Regexp re = Parser.parse("a{0}", FLAGS);
    Regexp simplified = Simplifier.simplify(re);
    assertThat(simplified.op).isEqualTo(RegexpOp.EMPTY_MATCH);
  }

  @Test
  void simplifyRepeatFourOrMore() {
    // a{4,} should simplify to aaaa+
    Regexp re = Parser.parse("a{4,}", FLAGS);
    Regexp simplified = Simplifier.simplify(re);
    String s = simplified.toString();
    assertThat(s).contains("a").contains("+");
  }

  @Test
  void simplifyRepeatRangeMinMax() {
    // a{2,5} should simplify without REPEAT node.
    Regexp re = Parser.parse("a{2,5}", FLAGS);
    Regexp simplified = Simplifier.simplify(re);
    assertThat(simplified.toString()).doesNotContain("{");
  }

  @Test
  void simplifyRepeatZeroToN() {
    // a{0,3} should simplify to (a(a(a)?)?)?
    Regexp re = Parser.parse("a{0,3}", FLAGS);
    Regexp simplified = Simplifier.simplify(re);
    assertThat(simplified.toString()).doesNotContain("{");
    assertThat(simplified.toString()).contains("?");
  }

  @Test
  void simplifyRepeatExactOne() {
    // a{1} should simplify to just a
    assertThat(simplify("a{1}")).isEqualTo("a");
  }

  @Test
  void simplifyRepeatEmptyWidthOp() {
    // Repeating a required zero-width assertion has the same effect as checking it once.
    String result = simplify("^{3,}");
    assertThat(result).isEqualTo("^");
  }

  @Test
  void simplifyRepeatEmptyWidthOpZero() {
    // ^{0,5} — empty-width op capped to {0,1} → ^?
    String result = simplify("^{0,5}");
    assertThat(result).isEqualTo("^?");
  }

  @Test
  void simplifyQuantifierOfEmptyMatch() {
    // (?:){3} — repeat of empty match simplifies to empty match
    Regexp re = Parser.parse("(?:){3}", FLAGS);
    Regexp simplified = Simplifier.simplify(re);
    assertThat(simplified.op).isEqualTo(RegexpOp.EMPTY_MATCH);
  }

  @Test
  void simplifyCharClassEmpty() {
    // [^\x00-\x{10ffff}] — empty char class → NO_MATCH
    // We can't directly construct this through the parser, but we can verify
    // that full char class simplifies to ANY_CHAR through a pattern.
    String result = simplify("[\\s\\S]");
    assertThat(result).isEqualTo(".");
  }

  @Test
  void simplifyRepeatOfNonGreedy() {
    // a{2,3}? — non-greedy repeat
    String result = simplify("a{2,3}?");
    assertThat(result).doesNotContain("{");
    assertThat(result).contains("?");
  }
}
