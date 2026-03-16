// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link Simplifier}, ported from RE2's {@code simplify_test.cc}. Tests parse each
 * regexp, simplify it, then compare the toString() output against the expected simplified form.
 */
class SimplifierTest {

  private static final int FLAGS =
      ParseFlags.MATCH_NL | (ParseFlags.LIKE_PERL & ~ParseFlags.ONE_LINE);

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

        // Posix character classes
        Arguments.of("[[:alnum:]]", "[0-9A-Za-z]"),
        Arguments.of("[[:alpha:]]", "[A-Za-z]"),
        Arguments.of("[[:blank:]]", "[\\t ]"),
        Arguments.of("[[:cntrl:]]", "[\\x00-\\x1f\\x7f]"),
        Arguments.of("[[:digit:]]", "[0-9]"),
        Arguments.of("[[:graph:]]", "[!-~]"),
        Arguments.of("[[:lower:]]", "[a-z]"),
        Arguments.of("[[:print:]]", "[ -~]"),
        Arguments.of("[[:punct:]]", "[!-/:-@\\[-`{-~]"),
        Arguments.of("[[:space:]]", "[\\t-\\r ]"),
        Arguments.of("[[:upper:]]", "[A-Z]"),
        Arguments.of("[[:xdigit:]]", "[0-9A-Fa-f]"),

        // Perl character classes
        Arguments.of("\\d", "[0-9]"),
        Arguments.of("\\s", "[\\t-\\n\\f-\\r ]"),
        Arguments.of("\\w", "[0-9A-Z_a-z]"),
        Arguments.of("\\D", "[^0-9]"),
        Arguments.of("\\S", "[^\\t-\\n\\f-\\r ]"),
        Arguments.of("\\W", "[^0-9A-Z_a-z]"),
        Arguments.of("[\\d]", "[0-9]"),
        Arguments.of("[\\s]", "[\\t-\\n\\f-\\r ]"),
        Arguments.of("[\\w]", "[0-9A-Z_a-z]"),
        Arguments.of("[\\D]", "[^0-9]"),
        Arguments.of("[\\S]", "[^\\t-\\n\\f-\\r ]"),
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
        Arguments.of("(?:a{1,})*", "(?:a+)*"),
        Arguments.of("(?:a{1,})+", "a+"),
        Arguments.of("(?:a{1,})?", "(?:a+)?"),
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

        // Empty character classes
        Arguments.of("[^[:cntrl:][:^cntrl:]]", "[^\\x00-\\x{10ffff}]"),

        // Full character classes
        Arguments.of("[[:cntrl:][:^cntrl:]]", "."),

        // Unicode case folding
        Arguments.of("(?i)A", "[Aa]"),
        Arguments.of("(?i)a", "[Aa]"),
        Arguments.of("(?i)K", "[Kk\\x{212a}]"),
        Arguments.of("(?i)k", "[Kk\\x{212a}]"),
        Arguments.of("(?i)\\x{212a}", "[Kk\\x{212a}]"),
        Arguments.of("(?i)[a-z]", "[A-Za-z\\x{17f}\\x{212a}]"),
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
        Arguments.of("(){0,2}", "(?:()()?)?" ),

        // Empty-width ops: repetition count capped at 1
        Arguments.of("(?:^){0,}", "^*"),
        Arguments.of("(?:$){28,}", "$+"),
        Arguments.of("(?-m:^){0,30}", "(?-m:^)?"),
        Arguments.of("(?-m:$){28,30}", "(?-m:$)"),
        Arguments.of("\\b(?:\\b\\B){999}\\B", "\\b\\b\\B\\B"),
        Arguments.of("\\b(?:\\b|\\B){999}\\B", "\\b(?:\\b|\\B)\\B"),
        // NonGreedy should also be handled.
        Arguments.of("(?:^){0,}?", "^*?"),
        Arguments.of("(?:$){28,}?", "$+?"),
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
        Arguments.of("(?i)A*a*", "[Aa]*"),
        Arguments.of("(?i)a+A+", "[Aa][Aa]+"),
        Arguments.of("(?i)A*(?-i)a*", "[Aa]*a*"),
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
        Arguments.of("(?i)A*a", "[Aa]+"),
        Arguments.of("(?i)a+A", "[Aa][Aa]+"),
        Arguments.of("(?i)A*(?-i)a", "[Aa]*a"),
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
        Arguments.of("(?:(?:a){0,1}){1,}", "a*")
    );
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
}
