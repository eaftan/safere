// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.PatternSyntaxException;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Systematic test coverage for every syntax feature documented in the JDK 21
 * {@link java.util.regex.Pattern} Javadoc.
 *
 * <p>For each feature, verifies that:
 * <ul>
 *   <li>JDK accepts the pattern (sanity check)
 *   <li>SafeRE compiles it and produces the same match result, OR
 *   <li>SafeRE intentionally rejects it (for features that violate linear-time guarantees)
 * </ul>
 *
 * <p>Refs: <a href="https://github.com/eaftan/safere/issues/131">#131</a>,
 * <a href="https://github.com/eaftan/safere/issues/127">#127</a>
 */
@DisplayName("JDK syntax compatibility")
class JdkSyntaxCompatibilityTest {

  // ---- Helpers ----

  /** Asserts SafeRE compiles the pattern without error. */
  private static void assertCompiles(String regex) {
    // Sanity: JDK must accept it too.
    assertThatNoException()
        .as("JDK should accept: %s", regex)
        .isThrownBy(() -> java.util.regex.Pattern.compile(regex));
    assertThatNoException()
        .as("SafeRE should accept: %s", regex)
        .isThrownBy(() -> Pattern.compile(regex));
  }

  /** Asserts JDK and SafeRE both reject the pattern. */
  private static void assertRejectedByJdkAndSafeRe(String regex) {
    assertThatThrownBy(() -> java.util.regex.Pattern.compile(regex))
        .as("JDK should reject: %s", regex)
        .isInstanceOf(PatternSyntaxException.class);
    assertThatThrownBy(() -> Pattern.compile(regex))
        .as("SafeRE should reject: %s", regex)
        .isInstanceOf(PatternSyntaxException.class);
  }

  /** Asserts SafeRE compiles and matches identically to JDK on the given input. */
  private static void assertMatchesSame(String regex, String input) {
    // Sanity: JDK must accept it.
    java.util.regex.Matcher jdkM = java.util.regex.Pattern.compile(regex).matcher(input);
    boolean jdkFound = jdkM.find();

    Matcher safeM = Pattern.compile(regex).matcher(input);
    boolean safeFound = safeM.find();

    assertThat(safeFound)
        .as("find() for /%s/ on \"%s\"", regex, input)
        .isEqualTo(jdkFound);

    if (jdkFound && safeFound) {
      assertThat(safeM.group())
          .as("group() for /%s/ on \"%s\"", regex, input)
          .isEqualTo(jdkM.group());
    }
  }

  /** Asserts SafeRE compiles and full-matches identically to JDK on the given input. */
  private static void assertMatchesFull(String regex, String input) {
    java.util.regex.Matcher jdkM = java.util.regex.Pattern.compile(regex).matcher(input);
    boolean jdkMatches = jdkM.matches();

    Matcher safeM = Pattern.compile(regex).matcher(input);
    boolean safeMatches = safeM.matches();

    assertThat(safeMatches)
        .as("matches() for /%s/ on \"%s\"", regex, input)
        .isEqualTo(jdkMatches);
  }

  /** Asserts SafeRE full-match behavior agrees with the JDK for every input. */
  private static void assertFullMatchesSameForAll(String regex, List<String> inputs) {
    for (String input : inputs) {
      assertMatchesFull(regex, input);
    }
  }

  /**
   * Asserts SafeRE compiles with the given flags and matches identically to JDK on the given
   * input.
   */
  private static void assertMatchesSameWithFlags(String regex, int jdkFlags, String input) {
    java.util.regex.Matcher jdkM =
        java.util.regex.Pattern.compile(regex, jdkFlags).matcher(input);
    boolean jdkFound = jdkM.find();

    // Map JDK flags to SafeRE flags (same values by design).
    Matcher safeM = Pattern.compile(regex, jdkFlags).matcher(input);
    boolean safeFound = safeM.find();

    assertThat(safeFound)
        .as("find() for /%s/ (flags=%d) on \"%s\"", regex, jdkFlags, input)
        .isEqualTo(jdkFound);

    if (jdkFound && safeFound) {
      assertThat(safeM.group())
          .as("group() for /%s/ (flags=%d) on \"%s\"", regex, jdkFlags, input)
          .isEqualTo(jdkM.group());
    }
  }

  private record SyntaxFamilyCase(
      String family, String acceptedRegex, String matchingInput, String nonMatchingInput) {}

  private record DialectRejection(String family, String regex) {}

  private record DialectLiteralCase(String family, String regex, String input) {}

  private record CharacterClassMembershipCase(String regex, List<String> inputs) {}

  private record EscapeMembershipCase(String regex, List<String> inputs) {}

  private record CharacterClassMatrixPiece(String label, String text) {}

  private record CharacterClassMatrixSeparator(
      String label, String text, boolean commentsModeOnly) {}

  private record CharacterClassMatrixOutcome(boolean accepted, String matches) {}

  private record CharacterClassExpressionMatrix(List<CharacterClassMatrixSpace> spaces, long size) {
    CharacterClassExpressionMatrix(List<CharacterClassMatrixSpace> spaces) {
      this(spaces, spaces.stream().mapToLong(CharacterClassMatrixSpace::size).sum());
    }

    String regexAt(long index) {
      if (index < 0 || index >= size) {
        throw new IndexOutOfBoundsException("matrix index " + index + " outside [0, " + size
            + ")");
      }
      long remaining = index;
      for (CharacterClassMatrixSpace space : spaces) {
        if (remaining < space.size()) {
          return space.regexAt(remaining);
        }
        remaining -= space.size();
      }
      throw new AssertionError("unreachable matrix index " + index);
    }
  }

  private record CharacterClassMatrixSpace(
      String prefix, List<List<String>> dimensions, long size) {
    CharacterClassMatrixSpace(String prefix, List<List<String>> dimensions) {
      this(prefix, dimensions, dimensions.stream().mapToLong(List::size)
          .reduce(1, Math::multiplyExact));
    }

    String regexAt(long index) {
      String[] parts = new String[dimensions.size()];
      long remaining = index;
      for (int i = dimensions.size() - 1; i >= 0; i--) {
        List<String> dimension = dimensions.get(i);
        int element = (int) (remaining % dimension.size());
        parts[i] = dimension.get(element);
        remaining /= dimension.size();
      }
      StringBuilder regex = new StringBuilder(prefix);
      for (String part : parts) {
        regex.append(part);
      }
      return regex.append(']').toString();
    }
  }

  @Nested
  @DisplayName("Syntax-family compatibility matrix")
  class SyntaxFamilyCompatibilityMatrix {
    static Stream<Arguments> quantifiedBareQuestionGroups() {
      return Stream.of(
          Arguments.of("(?)?"),
          Arguments.of("(?)*"),
          Arguments.of("(?)+"),
          Arguments.of("()??(?)?"),
          Arguments.of("()?(?)?"),
          Arguments.of("()*?(?)?"),
          Arguments.of("()+?(?)?"),
          Arguments.of("(a)??(?)?"),
          Arguments.of("a??(?)?"),
          Arguments.of("()??|(?)?"));
    }

    @ParameterizedTest(name = "/{0}/")
    @MethodSource("quantifiedBareQuestionGroups")
    @DisplayName("bare question groups reject following quantifiers like JDK")
    void bareQuestionGroupsRejectFollowingQuantifiersLikeJdk(String regex) {
      assertRejectedByJdkAndSafeRe(regex);
    }

    static Stream<Arguments> countedBareQuestionGroups() {
      return Stream.of(
          Arguments.of("(?){2}", "", true),
          Arguments.of("a(?){2}", "a", true),
          Arguments.of("a(?){2}", "aa", false),
          Arguments.of("()??(?){2}", "", true),
          Arguments.of("a(?){2}?", "a", true),
          Arguments.of("(?){1}{1}", "", true));
    }

    @ParameterizedTest(name = "/{0}/ matches \"{1}\" = {2}")
    @MethodSource("countedBareQuestionGroups")
    @DisplayName("bare question groups accept counted repeats like JDK")
    void bareQuestionGroupsAcceptCountedRepeatsLikeJdk(
        String regex, String input, boolean expected) {
      assertMatchesFull(regex, input);
      assertThat(Pattern.compile(regex).matcher(input).matches()).isEqualTo(expected);
    }

    static Stream<Arguments> acceptedSyntaxFamilies() {
      return Stream.of(
          Arguments.of(new SyntaxFamilyCase("literal characters", "abc", "abc", "ab")),
          Arguments.of(new SyntaxFamilyCase("quoting", "\\Q.+*\\E", ".+*", "abc")),
          Arguments.of(new SyntaxFamilyCase("escaped non-ASCII literals", "\\©", "©", "c")),
          Arguments.of(new SyntaxFamilyCase("control escapes", "\\t", "\t", "t")),
          Arguments.of(new SyntaxFamilyCase("octal escapes", "\\041", "!", "1")),
          Arguments.of(new SyntaxFamilyCase("hex escapes", "\\x41", "A", "x41")),
          Arguments.of(new SyntaxFamilyCase("Unicode escapes", "\\u0041", "A", "u0041")),
          Arguments.of(new SyntaxFamilyCase("named characters", "\\N{LATIN SMALL LETTER A}",
              "a", "A")),
          Arguments.of(new SyntaxFamilyCase("predefined classes", "\\d", "7", "a")),
          Arguments.of(new SyntaxFamilyCase("Java character classes", "\\p{javaLowerCase}",
              "a", "A")),
          Arguments.of(new SyntaxFamilyCase("POSIX property escapes", "\\p{Lower}", "a", "A")),
          Arguments.of(new SyntaxFamilyCase("POSIX bracket fragments", "[[:lower:]]",
              "l", "a")),
          Arguments.of(new SyntaxFamilyCase("Unicode scripts", "\\p{IsLatin}", "A", "1")),
          Arguments.of(new SyntaxFamilyCase("Unicode blocks", "\\p{InGreek}", "\u0391", "A")),
          Arguments.of(new SyntaxFamilyCase("Unicode categories", "\\p{Lu}", "A", "a")),
          Arguments.of(new SyntaxFamilyCase("Unicode binary properties", "\\p{IsAlphabetic}",
              "a", "1")),
          Arguments.of(new SyntaxFamilyCase("class union", "[a-d[m-p]]", "m", "z")),
          Arguments.of(new SyntaxFamilyCase("class intersection", "[a-z&&[def]]", "d", "a")),
          Arguments.of(new SyntaxFamilyCase("class subtraction", "[a-z&&[^bc]]", "a", "b")),
          Arguments.of(new SyntaxFamilyCase("capturing groups", "(ab)+", "abab", "aba")),
          Arguments.of(new SyntaxFamilyCase("named groups", "(?<word>\\w+)", "abc", "!")),
          Arguments.of(new SyntaxFamilyCase("non-capturing groups", "(?:ab)+", "abab", "aba")),
          Arguments.of(new SyntaxFamilyCase("greedy quantifiers", "ab*c", "abbc", "abdc")),
          Arguments.of(new SyntaxFamilyCase("lazy quantifiers", "a.*?c", "abc", "ab")),
          Arguments.of(new SyntaxFamilyCase("bounded quantifiers", "a{2,4}", "aaa", "a")),
          Arguments.of(new SyntaxFamilyCase("boundary matchers", "\\bword\\b", "word", "sword")),
          Arguments.of(new SyntaxFamilyCase("linebreak matcher", "\\R", "\r\n", "a")),
          Arguments.of(new SyntaxFamilyCase("comments flag", "(?x)a b c # comment", "abc", "ab")),
          Arguments.of(new SyntaxFamilyCase("embedded flags", "(?i:abc)def", "ABCdef",
              "ABCDEF")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedSyntaxFamilies")
    @DisplayName("accepted syntax families compile and match like JDK")
    void acceptedSyntaxFamiliesCompileAndMatchLikeJdk(SyntaxFamilyCase syntaxCase) {
      assertMatchesFull(syntaxCase.acceptedRegex(), syntaxCase.matchingInput());
      assertMatchesFull(syntaxCase.acceptedRegex(), syntaxCase.nonMatchingInput());
    }

    static Stream<Arguments> nonJdkDialectSpellings() {
      return Stream.of(
          Arguments.of(new DialectRejection("Python named capture", "(?P<word>a)")),
          Arguments.of(new DialectRejection("PCRE single-quoted named capture", "(?'word'a)")),
          Arguments.of(new DialectRejection("Python named backreference", "(?P=word)")),
          Arguments.of(new DialectRejection("PCRE subroutine call", "(?&word)")),
          Arguments.of(new DialectRejection("PCRE branch-reset group", "(?|a)")),
          Arguments.of(new DialectRejection("PCRE conditional group", "(?(1)a|b)")),
          Arguments.of(new DialectRejection("PCRE inline comment", "(?#comment)a")),
          Arguments.of(new DialectRejection("PCRE keep-out escape", "\\K")),
          Arguments.of(new DialectRejection("PCRE byte escape", "\\C")),
          Arguments.of(new DialectRejection("PCRE g-name backreference", "\\g{name}")),
          Arguments.of(new DialectRejection("PCRE g-number backreference", "\\g1")),
          Arguments.of(new DialectRejection("bare script property", "\\p{Latin}")),
          Arguments.of(new DialectRejection("bare binary property", "\\p{Alphabetic}")),
          Arguments.of(new DialectRejection("lowercase category", "\\p{lu}")),
          Arguments.of(new DialectRejection("PCRE assigned property", "\\p{Assigned}")),
          Arguments.of(new DialectRejection("PCRE any property", "\\p{Any}")),
          Arguments.of(new DialectRejection("PCRE letter-and-mark category", "\\p{L&}")),
          Arguments.of(new DialectRejection("invalid numeric class escape", "[\\123]")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonJdkDialectSpellings")
    @DisplayName("non-JDK dialect spellings are rejected")
    void nonJdkDialectSpellingsAreRejected(DialectRejection rejection) {
      assertRejectedByJdkAndSafeRe(rejection.regex());
    }

    static Stream<Arguments> generatedNonJdkDialectSpellings() {
      List<DialectRejection> groupSpellings = List.of(
          new DialectRejection("Python named capture", "(?P<name>a)"),
          new DialectRejection("PCRE single-quoted named capture", "(?'name'a)"),
          new DialectRejection("Python named backreference", "(?P=name)"),
          new DialectRejection("PCRE subroutine call", "(?&name)"),
          new DialectRejection("PCRE branch-reset group", "(?|a)"),
          new DialectRejection("PCRE conditional group", "(?(1)a|b)"),
          new DialectRejection("PCRE inline comment", "(?#comment)a"));
      List<DialectRejection> escapeSpellings = List.of(
          new DialectRejection("PCRE keep-out escape", "\\K"),
          new DialectRejection("PCRE byte escape", "\\C"),
          new DialectRejection("PCRE g-name backreference", "\\g{name}"),
          new DialectRejection("PCRE g-number backreference", "\\g1"));
      List<String> wrappers = List.of("%s", "(?:%s)", "%s|z", "z%s");

      Stream<Arguments> groups = groupSpellings.stream()
          .flatMap(rejection -> wrappers.stream()
              .map(wrapper -> Arguments.of(new DialectRejection(rejection.family(),
                  String.format(wrapper, rejection.regex())))));
      Stream<Arguments> escapes = escapeSpellings.stream()
          .flatMap(rejection -> wrappers.stream()
              .map(wrapper -> Arguments.of(new DialectRejection(rejection.family(),
                  String.format(wrapper, rejection.regex())))));
      return Stream.concat(groups, escapes);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("generatedNonJdkDialectSpellings")
    @DisplayName("generated non-JDK dialect spellings are rejected")
    void generatedNonJdkDialectSpellingsAreRejected(DialectRejection rejection) {
      assertRejectedByJdkAndSafeRe(rejection.regex());
    }

    static Stream<Arguments> dialectLookingLiteralSpellings() {
      return Stream.of(
          Arguments.of(new DialectLiteralCase("POSIX collating element spelling", "[.ch.]",
              ".")),
          Arguments.of(new DialectLiteralCase("POSIX collating element spelling", "[.ch.]",
              "c")),
          Arguments.of(new DialectLiteralCase("POSIX equivalence class spelling", "[=a=]",
              "=")),
          Arguments.of(new DialectLiteralCase("POSIX equivalence class spelling", "[=a=]",
              "a")),
          Arguments.of(new DialectLiteralCase("POSIX word-start bracket spelling", "[[:<:]]",
              "<")),
          Arguments.of(new DialectLiteralCase("POSIX word-end bracket spelling", "[[:>:]]",
              ">")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialectLookingLiteralSpellings")
    @DisplayName("dialect-looking bracket spellings match JDK literal semantics")
    void dialectLookingBracketSpellingsMatchJdkLiteralSemantics(DialectLiteralCase literalCase) {
      assertMatchesFull(literalCase.regex(), literalCase.input());
    }

    static Stream<Arguments> malformedJdkSyntax() {
      return Stream.of(
          Arguments.of(new DialectRejection("unterminated character class", "[abc")),
          Arguments.of(new DialectRejection("empty character class", "[]")),
          Arguments.of(new DialectRejection("negated empty character class", "[^]")),
          Arguments.of(new DialectRejection("unclosed named character escape", "\\N{A")),
          Arguments.of(new DialectRejection("unknown named character escape", "\\N{NO SUCH}")),
          Arguments.of(new DialectRejection("unclosed braced hex escape", "\\x{41")),
          Arguments.of(new DialectRejection("braced hex escape above Unicode range",
              "\\x{110000}")),
          Arguments.of(new DialectRejection("malformed Unicode escape", "\\u123")),
          Arguments.of(new DialectRejection("unknown character property", "\\p{NoSuch}")),
          Arguments.of(new DialectRejection("unclosed character property", "\\p{Lower")),
          Arguments.of(new DialectRejection("empty character property", "\\p{}")),
          Arguments.of(new DialectRejection("unknown flag", "(?q)a")),
          Arguments.of(new DialectRejection("unterminated scoped flag group", "(?i:a")),
          Arguments.of(new DialectRejection("unmatched close group", "a)")),
          Arguments.of(new DialectRejection("unterminated group", "(a")),
          Arguments.of(new DialectRejection("dangling alternation group opener", "(|")),
          Arguments.of(new DialectRejection("nothing to repeat star", "*a")),
          Arguments.of(new DialectRejection("malformed bounded quantifier", "a{2,1}")),
          Arguments.of(new DialectRejection("bare leading class intersection", "[&&]")),
          Arguments.of(new DialectRejection("bare negated leading class intersection", "[^&&]")),
          Arguments.of(new DialectRejection("solitary ampersand after leading class intersection",
              "[&&&b]")),
          Arguments.of(new DialectRejection(
              "solitary ampersand after negated leading class intersection", "[^&&&b]")),
          Arguments.of(new DialectRejection("repeated leading class intersection", "[&&&&b]")),
          Arguments.of(new DialectRejection("repeated negated leading class intersection",
              "[^&&&&b]")),
          Arguments.of(new DialectRejection("comments-mode spaced bare leading class intersection",
              "(?x)[&&  ]")),
          Arguments.of(new DialectRejection(
              "comments-mode commented bare leading class intersection", "(?x)[&& #x\n ]")),
          Arguments.of(new DialectRejection(
              "comments-mode spaced solitary ampersand after leading class intersection",
              "(?x)[&&  &b]")),
          Arguments.of(new DialectRejection(
              "comments-mode spaced repeated leading class intersection", "(?x)[&&  &&b]")),
          Arguments.of(new DialectRejection(
              "comments-mode odd leading intersection before nested class", "(?x)[&&& [a]]")),
          Arguments.of(new DialectRejection(
              "leading intersection followed by zero-width syntax only", "[&&\\Q\\E]")),
          Arguments.of(new DialectRejection(
              "leading intersection followed by zero-width syntax and repeated marker",
              "[&&\\Q\\E&&a]")),
          Arguments.of(new DialectRejection(
              "comments-mode leading intersection followed by zero-width syntax "
                  + "and repeated marker",
              "(?x)[&& \\Q\\E&&a]")),
          Arguments.of(new DialectRejection("range ending at nested class opener", "[a-[]")),
          Arguments.of(new DialectRejection(
              "leading intersection range ending at nested class opener", "[&&--[]")),
          Arguments.of(new DialectRejection(
              "negated leading intersection range ending at nested class opener", "[^&&--[]")),
          Arguments.of(new DialectRejection(
              "leading intersection range ending before nested class", "[&&--[x]")),
          Arguments.of(new DialectRejection(
              "comments-mode leading intersection range ending at nested class opener",
              "(?x)[&&  --[]")),
          Arguments.of(new DialectRejection(
              "comments-mode leading intersection range ending before nested class",
              "(?x)[&& #x\n --[x]")),
          Arguments.of(new DialectRejection(
              "intersection rhs range ending at nested class opener", "[a&&b-[]")),
          Arguments.of(new DialectRejection(
              "odd ampersand intersection run before malformed range", "[\\d&&&-\\D]")),
          Arguments.of(new DialectRejection(
              "odd ampersand intersection run before zero-width malformed range",
              "[\\d&&&\\Q\\E-\\D]")),
          Arguments.of(new DialectRejection(
              "intersection RHS raw ampersand before malformed zero-width range",
              "[a\\Q\\E&&\\Q\\E\\Q\\E&-\\D]")),
          Arguments.of(new DialectRejection(
              "quoted ampersand before intersection RHS malformed range",
              "[\\Q&\\E&&\\Q\\E&-\\D]")),
          Arguments.of(new DialectRejection(
              "raw ampersand separator before malformed zero-width range",
              "[[^b]&\\Q\\E\\Q\\E&&&&\\Q\\E-\\D]")),
          Arguments.of(new DialectRejection(
              "negated raw ampersand separator before malformed zero-width range",
              "[^[^b]&\\Q\\E\\Q\\E&&&&\\Q\\E-\\D]")),
          Arguments.of(new DialectRejection(
              "negated raw ampersand separator before immediate malformed range",
              "[^[^b]&\\Q\\E&&-\\D]")),
          Arguments.of(new DialectRejection(
              "ordinary literal before trailing class intersection after nested class",
              "[[a]b&&]")),
          Arguments.of(new DialectRejection(
              "ordinary literal before trailing class intersection after predefined class",
              "[\\d0&&]")),
          Arguments.of(new DialectRejection(
              "quoted literal before trailing class intersection after nested class",
              "[[a]\\Qa\\E&&]")),
          Arguments.of(new DialectRejection(
              "quoted ampersand before trailing class intersection after nested class",
              "[[a]\\Q&\\E&&]")),
          Arguments.of(new DialectRejection("empty quoted class item has no terminator",
              "[\\Q\\E]")),
          Arguments.of(new DialectRejection("comments-mode spaced range has no endpoint",
              "(?x)[a- ]")),
          Arguments.of(new DialectRejection("comments-mode spaced empty quote has no endpoint",
              "(?x)[a- \\Q\\E]")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedJdkSyntax")
    @DisplayName("malformed JDK syntax is rejected")
    void malformedJdkSyntaxIsRejected(DialectRejection rejection) {
      assertRejectedByJdkAndSafeRe(rejection.regex());
    }

    static Stream<Arguments> unsupportedNonRegularJdkSyntax() {
      return Stream.of(
          Arguments.of(new DialectRejection("backreference", "(a)\\1")),
          Arguments.of(new DialectRejection("multi-digit backreference", "(a)(b)\\12")),
          Arguments.of(new DialectRejection("named backreference", "(?<name>a)\\k<name>")),
          Arguments.of(new DialectRejection("positive lookahead", "a(?=b)")),
          Arguments.of(new DialectRejection("negative lookahead", "a(?!b)")),
          Arguments.of(new DialectRejection("positive lookbehind", "(?<=a)b")),
          Arguments.of(new DialectRejection("negative lookbehind", "(?<!a)b")),
          Arguments.of(new DialectRejection("bounded positive lookbehind", "(?<=a{1,3})b")),
          Arguments.of(new DialectRejection("independent noncapturing group", "(?>ab|a)")),
          Arguments.of(new DialectRejection("possessive question quantifier", "a?+")),
          Arguments.of(new DialectRejection("possessive star quantifier", "a*+")),
          Arguments.of(new DialectRejection("possessive quantifier", "a++")),
          Arguments.of(new DialectRejection("possessive bounded quantifier", "a{2,4}+")),
          Arguments.of(new DialectRejection("atomic group", "(?>a+)")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedNonRegularJdkSyntax")
    @DisplayName("JDK syntax outside SafeRE's linear-time subset is rejected")
    void unsupportedNonRegularJdkSyntaxIsRejected(DialectRejection rejection) {
      assertThatNoException()
          .as("JDK should accept: %s", rejection.regex())
          .isThrownBy(() -> java.util.regex.Pattern.compile(rejection.regex()));
      assertThatThrownBy(() -> Pattern.compile(rejection.regex()))
          .as("SafeRE should reject unsupported syntax: %s", rejection.regex())
          .isInstanceOf(PatternSyntaxException.class);
    }
  }

  // ===========================================================================
  // 1. Characters & Escapes
  // ===========================================================================

  @Nested
  @DisplayName("Characters and escapes")
  class CharactersAndEscapes {

    @Test
    @DisplayName("literal character")
    void literalCharacter() {
      assertMatchesSame("x", "x");
    }

    @Test
    @DisplayName("escaped backslash: \\\\")
    void escapedBackslash() {
      assertMatchesSame("\\\\", "a\\b");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\©", "\\о", "\\\uD83D\uDE00"})
    @DisplayName("escaped non-ASCII character is literal")
    void escapedNonAsciiCharacterIsLiteral(String regex) {
      String literal = regex.substring(1);
      assertMatchesFull(regex, literal);
      assertMatchesFull(regex, "x");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\\©]", "[\\о]", "[\\\uD83D\uDE00]"})
    @DisplayName("escaped non-ASCII character in class is literal")
    void escapedNonAsciiCharacterInClassIsLiteral(String regex) {
      String literal = regex.substring(2, regex.length() - 1);
      assertMatchesFull(regex, literal);
      assertMatchesFull(regex, "x");
    }

    // -- Octal escapes --

    @Test
    @DisplayName("octal \\\\0n (single digit)")
    void octalSingleDigit() {
      assertMatchesSame("\\07", "\u0007"); // bell
    }

    @Test
    @DisplayName("octal \\\\0nn (two digits)")
    void octalTwoDigits() {
      assertMatchesSame("\\041", "!");  // 041 octal = 33 = '!'
    }

    @Test
    @DisplayName("octal \\\\0mnn (three digits)")
    void octalThreeDigits() {
      assertMatchesSame("\\0101", "A");  // 0101 octal = 65 = 'A'
    }

    @Test
    @DisplayName("octal \\\\0mnn above 0377 stops before overflow")
    void octalStopsBeforeOverflow() {
      assertMatchesSame("\\0400", " 0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\0", "\\08"})
    @DisplayName("malformed leading-zero octal escape is rejected")
    void malformedLeadingZeroOctalEscapeRejected(String regex) {
      assertThatThrownBy(() -> java.util.regex.Pattern.compile(regex))
          .as("JDK should reject malformed octal escape: %s", regex)
          .isInstanceOf(PatternSyntaxException.class);
      assertThatThrownBy(() -> Pattern.compile(regex))
          .as("SafeRE should reject malformed octal escape: %s", regex)
          .isInstanceOf(PatternSyntaxException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\400", "\\777", "\\123"})
    @DisplayName("unresolved non-zero numeric escapes never match")
    void unresolvedNonZeroNumericEscapesNeverMatch(String regex) {
      assertMatchesSame(regex, "\u0100");
      assertMatchesSame(regex, "\u01ff");
      assertMatchesSame(regex, "S");
      assertMatchesSame(regex, " 0");
      assertMatchesSame(regex, "?7");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\\123]", "[\\400]"})
    @DisplayName("non-zero numeric escapes in character classes are rejected")
    void nonZeroNumericEscapesInCharacterClassesRejected(String regex) {
      assertThatThrownBy(() -> java.util.regex.Pattern.compile(regex))
          .as("JDK should reject non-zero numeric escape in class: %s", regex)
          .isInstanceOf(PatternSyntaxException.class);
      assertThatThrownBy(() -> Pattern.compile(regex))
          .as("SafeRE should reject non-zero numeric escape in class: %s", regex)
          .isInstanceOf(PatternSyntaxException.class);
    }

    static Stream<Arguments> generatedNumericAndOctalEscapeCases() {
      List<String> inputs = List.of("", "\u0000", "\u0001", "\u0007", "\b", "\t", "\n", " ",
          "!", "A", "S", "\u007f", "\u00ff", "\u0100", "00", " 0", "?7", "123");
      return Stream.of(
          Arguments.of(new EscapeMembershipCase("\\00", inputs)),
          Arguments.of(new EscapeMembershipCase("\\000", inputs)),
          Arguments.of(new EscapeMembershipCase("\\01", inputs)),
          Arguments.of(new EscapeMembershipCase("\\001", inputs)),
          Arguments.of(new EscapeMembershipCase("\\07", inputs)),
          Arguments.of(new EscapeMembershipCase("\\077", inputs)),
          Arguments.of(new EscapeMembershipCase("\\0100", inputs)),
          Arguments.of(new EscapeMembershipCase("\\0377", inputs)),
          Arguments.of(new EscapeMembershipCase("\\0400", inputs)),
          Arguments.of(new EscapeMembershipCase("\\1", inputs)),
          Arguments.of(new EscapeMembershipCase("\\9", inputs)),
          Arguments.of(new EscapeMembershipCase("\\12", inputs)),
          Arguments.of(new EscapeMembershipCase("\\123", inputs)),
          Arguments.of(new EscapeMembershipCase("\\400", inputs)),
          Arguments.of(new EscapeMembershipCase("\\777", inputs)),
          Arguments.of(new EscapeMembershipCase("[\\00]", inputs)),
          Arguments.of(new EscapeMembershipCase("[\\000]", inputs)),
          Arguments.of(new EscapeMembershipCase("[\\01]", inputs)),
          Arguments.of(new EscapeMembershipCase("[\\07]", inputs)),
          Arguments.of(new EscapeMembershipCase("[\\077]", inputs)),
          Arguments.of(new EscapeMembershipCase("[\\0100]", inputs)),
          Arguments.of(new EscapeMembershipCase("[\\0377]", inputs)),
          Arguments.of(new EscapeMembershipCase("[\\0400]", inputs)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("generatedNumericAndOctalEscapeCases")
    @DisplayName("generated numeric and octal escape cases match JDK")
    void generatedNumericAndOctalEscapeCasesMatchJdk(EscapeMembershipCase escapeCase) {
      assertFullMatchesSameForAll(escapeCase.regex(), escapeCase.inputs());
    }

    static Stream<Arguments> generatedMalformedEscapeCases() {
      return Stream.of(
          "\\0",
          "\\08",
          "\\09",
          "\\x",
          "\\xG0",
          "\\x{}",
          "\\x{110000}",
          "\\u",
          "\\u0",
          "\\u00",
          "\\u000",
          "\\u000G",
          "\\N{}",
          "\\N{NO SUCH CHARACTER}",
          "[\\0]",
          "[\\08]",
          "[\\09]",
          "[\\400]",
          "[\\777]",
          "[\\123]",
          "[\\x]",
          "[\\x{}]",
          "[\\x{110000}]",
          "[\\u000]",
          "[\\N{}]")
          .map(regex -> Arguments.of(new DialectRejection("malformed escape", regex)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("generatedMalformedEscapeCases")
    @DisplayName("generated malformed escape cases are rejected")
    void generatedMalformedEscapeCasesAreRejected(DialectRejection rejection) {
      assertRejectedByJdkAndSafeRe(rejection.regex());
    }

    // -- Hex escapes --

    @Test
    @DisplayName("hex \\\\xhh")
    void hexTwoDigit() {
      assertMatchesSame("\\x41", "A");
    }

    @Test
    @DisplayName("hex \\\\x{h...h} (BMP)")
    void hexBracedBmp() {
      assertMatchesSame("\\x{41}", "A");
    }

    @Test
    @DisplayName("hex \\\\x{h...h} (supplementary)")
    void hexBracedSupplementary() {
      // U+1F600 = grinning face emoji
      assertMatchesSame("\\x{1F600}", "\uD83D\uDE00");
    }

    // -- Unicode escape (backslash-u) --

    @Test
    @DisplayName("unicode escape \\\\uhhhh (BMP)")
    void unicodeEscapeBmp() {
      assertMatchesSame("\\u0041", "A");
    }

    @Test
    @DisplayName("unicode escape \\\\uhhhh (Thai character)")
    void unicodeEscapeThai() {
      assertMatchesSame("\\u0E01", "\u0E01");
    }

    @Test
    @DisplayName("unicode escape range in character class")
    void unicodeEscapeRange() {
      assertMatchesSame("[\\u0E00-\\u0E7F]", "\u0E01");
    }

    @Test
    @DisplayName("unicode escape \\\\uhhhh (supplementary via surrogate pair)")
    void unicodeEscapeSurrogatePair() {
      // JDK treats surrogate pair escapes as U+1F600
      assertMatchesSame("\\uD83D\\uDE00", "\uD83D\uDE00");
    }

    // -- Named Unicode character --

    @Test
    @DisplayName("named unicode character \\\\N{name}")
    void namedUnicodeCharacter() {
      assertMatchesSame("\\N{WHITE SMILING FACE}", "\u263A");
    }

    @Test
    @DisplayName("named unicode character \\\\N{name} (Latin letter)")
    void namedUnicodeCharacterLatin() {
      assertMatchesSame("\\N{LATIN SMALL LETTER A}", "a");
    }

    // -- C escapes --

    @Test
    @DisplayName("tab \\\\t")
    void tabEscape() {
      assertMatchesSame("\\t", "\t");
    }

    @Test
    @DisplayName("newline \\\\n")
    void newlineEscape() {
      assertMatchesSame("\\n", "\n");
    }

    @Test
    @DisplayName("carriage return \\\\r")
    void crEscape() {
      assertMatchesSame("\\r", "\r");
    }

    @Test
    @DisplayName("form feed \\\\f")
    void formFeedEscape() {
      assertMatchesSame("\\f", "\f");
    }

    @Test
    @DisplayName("alert/bell \\\\a")
    void alertEscape() {
      assertMatchesSame("\\a", "\u0007");
    }

    @Test
    @DisplayName("escape \\\\e")
    void escapeEscape() {
      assertMatchesSame("\\e", "\u001B");
    }

    // -- Control character --

    @Test
    @DisplayName("control character \\\\cA")
    void controlCharA() {
      assertMatchesSame("\\cA", "\u0001");
    }

    @Test
    @DisplayName("control character \\\\cZ")
    void controlCharZ() {
      assertMatchesSame("\\cZ", "\u001A");
    }

    @Test
    @DisplayName("control character \\\\cM (carriage return)")
    void controlCharM() {
      assertMatchesSame("\\cM", "\r");
    }
  }

  // ===========================================================================
  // 2. Predefined Character Classes
  // ===========================================================================

  @Nested
  @DisplayName("Predefined character classes")
  class PredefinedCharacterClasses {

    @Test
    @DisplayName("dot matches non-newline")
    void dot() {
      assertMatchesSame(".", "a");
      assertMatchesSame(".", "\r");
    }

    @Test
    @DisplayName("\\\\d matches digit")
    void digitClass() {
      assertMatchesSame("\\d", "5");
      assertMatchesFull("\\d", "a");  // should not match
    }

    @Test
    @DisplayName("\\\\D matches non-digit")
    void nonDigitClass() {
      assertMatchesSame("\\D", "a");
      assertMatchesFull("\\D", "5");  // should not match
    }

    @Test
    @DisplayName("\\\\h matches horizontal whitespace")
    void horizontalWhitespace() {
      assertMatchesSame("\\h", " ");
      assertMatchesSame("\\h", "\t");
      assertMatchesFull("\\h", "a");  // should not match
    }

    @Test
    @DisplayName("\\\\H matches non-horizontal whitespace")
    void nonHorizontalWhitespace() {
      assertMatchesSame("\\H", "a");
      assertMatchesFull("\\H", " ");  // should not match
    }

    @Test
    @DisplayName("\\\\s matches whitespace")
    void whitespaceClass() {
      assertMatchesSame("\\s", " ");
      assertMatchesSame("\\s", "\n");
      assertMatchesSame("\\s", "\u000B");
      assertMatchesFull("\\s", "a");  // should not match
    }

    @Test
    @DisplayName("\\\\S matches non-whitespace")
    void nonWhitespaceClass() {
      assertMatchesSame("\\S", "a");
      assertMatchesFull("\\S", " ");  // should not match
      assertMatchesFull("\\S", "\u000B");  // should not match
    }

    @Test
    @DisplayName("\\\\v matches vertical whitespace")
    void verticalWhitespace() {
      assertMatchesSame("\\v", "\n");
      assertMatchesSame("\\v", "\u000B");
      assertMatchesSame("\\v", "\f");
      assertMatchesSame("\\v", "\r");
      assertMatchesFull("\\v", " ");  // should not match
    }

    @Test
    @DisplayName("\\\\V matches non-vertical whitespace")
    void nonVerticalWhitespace() {
      assertMatchesSame("\\V", "a");
      assertMatchesFull("\\V", "\n");  // should not match
    }

    @Test
    @DisplayName("\\\\w matches word character")
    void wordClass() {
      assertMatchesSame("\\w", "a");
      assertMatchesSame("\\w", "5");
      assertMatchesSame("\\w", "_");
      assertMatchesFull("\\w", "!");  // should not match
    }

    @Test
    @DisplayName("\\\\W matches non-word character")
    void nonWordClass() {
      assertMatchesSame("\\W", "!");
      assertMatchesFull("\\W", "a");  // should not match
    }

    @Test
    @DisplayName("\\\\R matches linebreak sequence")
    void linebreakMatcher() {
      assertMatchesSame("\\R", "\n");
      assertMatchesSame("\\R", "\r\n");
      assertMatchesSame("\\R", "\r");
      assertMatchesSame("\\R", "\u0085");
      assertMatchesSame("\\R", "\u2028");
      assertMatchesSame("\\R", "\u2029");
      assertMatchesFull("\\R", "a");  // should not match
    }

    @Test
    @DisplayName("\\\\X matches extended grapheme cluster")
    void extendedGraphemeCluster() {
      assertCompiles("\\X");
      // Basic: single character
      assertMatchesSame("\\X", "a");
    }
  }

  // ===========================================================================
  // 3. Character Classes
  // ===========================================================================

  @Nested
  @DisplayName("Character classes")
  class CharacterClasses {

    @Test
    @DisplayName("simple class [abc]")
    void simpleClass() {
      assertMatchesSame("[abc]", "b");
      assertMatchesFull("[abc]", "d");
    }

    @Test
    @DisplayName("negation [^abc]")
    void negation() {
      assertMatchesSame("[^abc]", "d");
      assertMatchesFull("[^abc]", "b");
    }

    @Test
    @DisplayName("range [a-zA-Z]")
    void range() {
      assertMatchesSame("[a-zA-Z]", "m");
      assertMatchesFull("[a-zA-Z]", "5");
    }

    @Test
    @DisplayName("union [a-d[m-p]]")
    void union() {
      assertMatchesSame("[a-d[m-p]]", "n");
      assertMatchesFull("[a-d[m-p]]", "f");
    }

    @Test
    @DisplayName("intersection [a-z&&[def]]")
    void intersection() {
      assertMatchesSame("[a-z&&[def]]", "d");
      assertMatchesFull("[a-z&&[def]]", "a");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[&&`+]˫]*", "[&&abc]", "[a&&&&b]"})
    @DisplayName("empty left side of class intersection matches like JDK")
    void emptyLeftSideOfIntersection(String regex) {
      assertMatchesSame(regex, "");
      assertMatchesSame(regex, "a");
      assertMatchesSame(regex, "`");
      assertMatchesSame(regex, "˫");
    }

    static Stream<Arguments> generatedCharacterClassMembershipCases() {
      List<String> inputs = List.of("", "&", "[", "]", ":", "^", "-", "a", "b", "c",
          "d", "e", "f", "m", "p", "z", "`", "+");
      return Stream.of(
          Arguments.of(new CharacterClassMembershipCase("[&&abc]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[&&-a]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[^&&-a]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[&&  -a]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[&& #x\n -a]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[&&^a]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[&&\\d]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[&&[a]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[&&a-]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[&&--a]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[&&-[a]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[&&a&&b]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[&&a&&[ab]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[\\Q\\E]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[\\Q\\E-]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[\\Q\\E-a]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[\\Q\\E&&a]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[ \\Q\\E-a]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[a\\Q\\E-b]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[a&&&&b]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[a&&\\Q\\E&&a]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[ab&&\\Q\\E&&b]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[a&&\\Q\\E&&b]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[ab&&  ]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[ab&& #x\n ]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[a-z&&[def]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[a-z&&[^bc]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[a-\\Q\\E]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[a-\\Qb\\E]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[a-\\Qbc\\E]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[\\Qa\\E-b]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[\\Qab\\E-c]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[a-[a]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[a-[x]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[&&--[a]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[a-d[m-p]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[[:lower:]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[^[:lower:]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[[:^space:]]", inputs)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("generatedCharacterClassMembershipCases")
    @DisplayName("character-class edge syntax matches JDK over generated inputs")
    void characterClassEdgeSyntaxMatchesJdkOverGeneratedInputs(
        CharacterClassMembershipCase membershipCase) {
      assertFullMatchesSameForAll(membershipCase.regex(), membershipCase.inputs());
    }

    static Stream<Arguments> generatedRepresentativeCharacterClassOperationCases() {
      List<String> inputs = characterClassMatrixInputs();
      List<String> operands = List.of(
          "a-c",
          "\\d",
          "\\w",
          "\\Q-\\E",
          "\\Q&\\E",
          "[ab]",
          "[^b]",
          "[[:lower:]]",
          "[[.ch.]]",
          "[[=a=]]");
      List<String> operators = List.of("", "&&");
      Stream.Builder<Arguments> cases = Stream.builder();
      for (boolean negated : List.of(false, true)) {
        for (String left : operands) {
          cases.add(Arguments.of(new CharacterClassMembershipCase(
              "[" + (negated ? "^" : "") + left + "]", inputs)));
          for (String operator : operators) {
            for (String right : operands) {
              cases.add(Arguments.of(new CharacterClassMembershipCase(
                  "[" + (negated ? "^" : "") + left + operator + right + "]", inputs)));
            }
          }
        }
      }
      return cases.build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("generatedRepresentativeCharacterClassOperationCases")
    @DisplayName("generated representative character-class operation cases match JDK")
    void generatedRepresentativeCharacterClassOperationCasesMatchJdk(
        CharacterClassMembershipCase membershipCase) {
      CharacterClassMatrixOutcome jdk = jdkCharacterClassOutcome(membershipCase.regex());
      CharacterClassMatrixOutcome safere = safeReCharacterClassOutcome(membershipCase.regex());
      assertThat(safere)
          .as("character-class outcome for /%s/", membershipCase.regex())
          .isEqualTo(jdk);
    }

    static Stream<Arguments> deferredCharacterClassExpressionParserCases() {
      List<String> inputs = List.of("", "&", "[", "]", "-", "a", "b", "x", "0", "1", " ",
          "\t", "Ā");
      return Stream.of(
          Arguments.of(new CharacterClassMembershipCase("[ [a]&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[ \\d&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[ &&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[ &&&a]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[&&[x]-&&a]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[&&\\Q\\E[x]-&&a]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[&&[x]-&&a]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[^ [a]&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[^ \\d&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[^ &&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[^&&[x]-&&a]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[[ab]&&[bc]&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[[ab]&&\\Q\\E[bc]&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[\\Qab\\E&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[\\Qab\\E&&[b]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[[ab]&&[^b]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[[a]a-b&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[\\da-b&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[^[a]a-b&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[\\D\\Q\\E &&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[\\D\\Q\\E #x\n &&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[[a]&\\Q\\E&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[\\d&\\Q\\E&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[[a]& &&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[\\d& #x\n&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[\\w&\\Q\\E&& &&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[ && \\D&\\Q\\E&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[&\\Q\\E &&\\d]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[b&&[a]&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[^b&&[a]&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[a&&& -\\D]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[a&&& #x\n -\\D]", inputs)),
          Arguments.of(new CharacterClassMembershipCase(
              "[ab\\Q\\E\\Q\\E&&&&&\\Q\\E&\\&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase(
              "[\\&\\Q\\E&&&&&\\Q\\E\\Q\\E&-\\D]", inputs)),
          Arguments.of(new CharacterClassMembershipCase(
              "[[^b]&\\Q\\E\\Q\\E&&&&\\Q\\E&\\&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase(
              "[^[^b]&\\Q\\E\\Q\\E&&&&\\Q\\E&\\&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase(
              "[[^b]&\\Q\\E\\Q\\E&&&&\\Q\\E&-\\D]", inputs)),
          Arguments.of(new CharacterClassMembershipCase(
              "[^[^b]&\\Q\\E\\Q\\E&&&&\\Q\\E&-\\D]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[a\\d&& [0]&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[a[b]&& [a]&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[0-1ab&&[a]&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase(
              "(?x)[^ab\\p{javaLowerCase}&&\\Q\\E [a]&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[^0-1ab&&[a]&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase(
              "(?x)[^0-1\\Qab\\E\\Q\\E\\Q\\E&& [a]&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[[a]Ā&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[\\d0-1&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[ [ab] && #x\n [bc] && ]",
              inputs)));
    }

    static Stream<Arguments> characterClassExpressionOracleMatrixCases() {
      List<String> inputs = List.of("", "a", "b", "c", "&", "-", "0", "1", "9", "A", "Z", "_",
          "`", "x", " ", "\t", "Ā", "é");
      return Stream.of(
          Arguments.of(new CharacterClassMembershipCase("[ab&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[a-b&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[ab&&&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[ab&&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[ab&&&c]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[^ab&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[[a]&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[[a]&&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[[a]&&&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[[a]&&&[b]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[[a]&&&[b]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[[a]&&& [a]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[[a]&&& [b]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[a&&& [b]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[\\Qa\\E&&& [b]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[\\d&&& [0]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[\\d&&& [a]]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[[a]a-b&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[[a]Ā&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[\\d&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[\\d&&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[\\d&&&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[\\d0-1&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[\\dĀ&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[[a]&\\Q\\E&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[[a]& &&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[\\d& #x\n&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[\\d&\\Q\\E&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[\\d&\\Q\\E&&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[\\d&\\Q\\E&&&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[\\w&\\Q\\E&& &&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[a&&&\\Q\\E&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[a\\d&&&\\Q\\E&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[a&&&\\Q\\E&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[a&\\Q\\E&&\\Q\\E&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[a&\\Q\\E&&\\Q\\E&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[&&[a]&-a]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[&&[a]&-&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[&&abc]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[ &&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[ &&&a]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[ && \\D&\\Q\\E&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("[^[a]a-b&&]", inputs)),
          Arguments.of(new CharacterClassMembershipCase("(?x)[^[a]& &&]", inputs)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "(?x)[0&\\Q\\E\\Q\\E&&&& #x\n-&&]",
        "(?x)[0&\\Q\\E\\Q\\E&&&&&& #x\n-&&]"
    })
    @DisplayName("character-class ampersand runs with empty quotes reject malformed syntax")
    void characterClassAmpersandRunsWithEmptyQuotesRejectMalformedSyntax(String regex) {
      assertRejectedByJdkAndSafeRe(regex);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("deferredCharacterClassExpressionParserCases")
    @DisplayName("character-class expression parser cases match JDK")
    void characterClassExpressionParserCasesMatchJdk(
        CharacterClassMembershipCase membershipCase) {
      assertFullMatchesSameForAll(membershipCase.regex(), membershipCase.inputs());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("characterClassExpressionOracleMatrixCases")
    @DisplayName("character-class expression oracle matrix matches JDK")
    void characterClassExpressionOracleMatrixMatchesJdk(
        CharacterClassMembershipCase membershipCase) {
      assertFullMatchesSameForAll(membershipCase.regex(), membershipCase.inputs());
    }

    private static final String LONG_SYNTAX_MATRIX_PROPERTY = "safere.longSyntaxMatrix";
    private static final String SYNTAX_MATRIX_SHARD_PROPERTY = "safere.syntaxMatrix.shard";
    private static final String SYNTAX_MATRIX_SHARDS_PROPERTY = "safere.syntaxMatrix.shards";
    private static final String SYNTAX_MATRIX_LIMIT_PROPERTY = "safere.syntaxMatrix.limit";
    private static final String SYNTAX_MATRIX_PARALLEL_PROPERTY = "safere.syntaxMatrix.parallel";

    @Test
    @DisplayName("generated character-class expression matrix matches JDK")
    @DisabledForCrosscheck(
        "generated differential matrix already compares SafeRE with java.util.regex")
    void generatedCharacterClassExpressionMatrixMatchesJdk() {
      Assumptions.assumeTrue(Boolean.getBoolean(LONG_SYNTAX_MATRIX_PROPERTY),
          () -> "set -D" + LONG_SYNTAX_MATRIX_PROPERTY + "=true to run the long syntax matrix");

      CharacterClassExpressionMatrix matrix = generatedCharacterClassExpressionMatrix();
      int shardCount = Integer.getInteger(SYNTAX_MATRIX_SHARDS_PROPERTY, 1);
      int shard = Integer.getInteger(SYNTAX_MATRIX_SHARD_PROPERTY, 0);
      assertThat(shardCount).as(SYNTAX_MATRIX_SHARDS_PROPERTY).isPositive();
      assertThat(shard).as(SYNTAX_MATRIX_SHARD_PROPERTY).isBetween(0, shardCount - 1);

      long startInclusive = matrix.size() * shard / shardCount;
      long endExclusive = matrix.size() * (shard + 1) / shardCount;
      long limit = Long.getLong(SYNTAX_MATRIX_LIMIT_PROPERTY, -1);
      if (limit >= 0) {
        endExclusive = Math.min(endExclusive, startInclusive + limit);
      }

      Queue<String> divergences = new ConcurrentLinkedQueue<>();
      LongAdder divergenceCount = new LongAdder();
      LongStream indexes = LongStream.range(startInclusive, endExclusive);
      if (Boolean.parseBoolean(System.getProperty(SYNTAX_MATRIX_PARALLEL_PROPERTY, "true"))) {
        indexes = indexes.parallel();
      }
      indexes.forEach(index -> {
        String regex = matrix.regexAt(index);
        CharacterClassMatrixOutcome jdk = jdkCharacterClassOutcome(regex);
        CharacterClassMatrixOutcome safere = safeReCharacterClassOutcome(regex);
        if (!jdk.equals(safere)) {
          divergenceCount.increment();
          if (divergences.size() < 50) {
            divergences.add(regex + " JDK=" + jdk + " SafeRE=" + safere);
          }
        }
      });

      assertThat(divergences)
          .as("generated character-class expression divergences in index range [%d, %d) "
              + "of %,d total cases, shard %d/%d: %d; first entries: %s",
              startInclusive, endExclusive, matrix.size(), shard, shardCount,
              divergenceCount.sum(), divergences)
          .isEmpty();
    }

    private static CharacterClassExpressionMatrix generatedCharacterClassExpressionMatrix() {
      List<CharacterClassMatrixPiece> basePieces = List.of(
          new CharacterClassMatrixPiece("empty", ""),
          new CharacterClassMatrixPiece("litA", "a"),
          new CharacterClassMatrixPiece("litAB", "ab"),
          new CharacterClassMatrixPiece("rangeAB", "a-b"),
          new CharacterClassMatrixPiece("zero", "0"),
          new CharacterClassMatrixPiece("range01", "0-1"),
          new CharacterClassMatrixPiece("rawAmp", "&"),
          new CharacterClassMatrixPiece("escapedAmp", "\\&"),
          new CharacterClassMatrixPiece("quoteAmp", "\\Q&\\E"),
          new CharacterClassMatrixPiece("quoteA", "\\Qa\\E"),
          new CharacterClassMatrixPiece("quoteAB", "\\Qab\\E"),
          new CharacterClassMatrixPiece("quoteEmpty", "\\Q\\E"),
          new CharacterClassMatrixPiece("nonInline", "Ā"),
          new CharacterClassMatrixPiece("escapedNonAscii", "\\Ā"),
          new CharacterClassMatrixPiece("nestedA", "[a]"),
          new CharacterClassMatrixPiece("nestedB", "[b]"),
          new CharacterClassMatrixPiece("nestedAB", "[ab]"),
          new CharacterClassMatrixPiece("nestedNotB", "[^b]"),
          new CharacterClassMatrixPiece("digit", "\\d"),
          new CharacterClassMatrixPiece("nonDigit", "\\D"),
          new CharacterClassMatrixPiece("word", "\\w"),
          new CharacterClassMatrixPiece("nonWord", "\\W"),
          new CharacterClassMatrixPiece("propertyLower", "\\p{Lower}"),
          new CharacterClassMatrixPiece("propertyNotLower", "\\P{Lower}"),
          new CharacterClassMatrixPiece("propertyJavaLower", "\\p{javaLowerCase}"));
      List<CharacterClassMatrixPiece> ampersandPieces = List.of(
          new CharacterClassMatrixPiece("rawAmp", "&"),
          new CharacterClassMatrixPiece("escapedAmp", "\\&"),
          new CharacterClassMatrixPiece("quoteAmp", "\\Q&\\E"));
      List<CharacterClassMatrixPiece> trailingPieces = List.of(
          new CharacterClassMatrixPiece("none", ""),
          new CharacterClassMatrixPiece("rawAmp", "&"),
          new CharacterClassMatrixPiece("escapedAmp", "\\&"),
          new CharacterClassMatrixPiece("quoteAmp", "\\Q&\\E"),
          new CharacterClassMatrixPiece("rangeToNonDigit", "-\\D"),
          new CharacterClassMatrixPiece("rangeToA", "-a"),
          new CharacterClassMatrixPiece("rangeToIntersection", "-&&"),
          new CharacterClassMatrixPiece("zeroWidthRangeToNonDigit", "\\Q\\E-\\D"));
      List<CharacterClassMatrixSeparator> separators = List.of(
          new CharacterClassMatrixSeparator("none", "", false),
          new CharacterClassMatrixSeparator("emptyQuote", "\\Q\\E", false),
          new CharacterClassMatrixSeparator("twoEmptyQuotes", "\\Q\\E\\Q\\E", false),
          new CharacterClassMatrixSeparator("space", " ", true),
          new CharacterClassMatrixSeparator("comment", " #x\n", true),
          new CharacterClassMatrixSeparator("emptyQuoteSpace", "\\Q\\E ", true),
          new CharacterClassMatrixSeparator("spaceEmptyQuote", " \\Q\\E", true));
      List<CharacterClassMatrixSeparator> afterOperatorSeparators = List.of(
          new CharacterClassMatrixSeparator("none", "", false),
          new CharacterClassMatrixSeparator("emptyQuote", "\\Q\\E", false),
          new CharacterClassMatrixSeparator("twoEmptyQuotes", "\\Q\\E\\Q\\E", false),
          new CharacterClassMatrixSeparator("space", " ", true),
          new CharacterClassMatrixSeparator("comment", " #x\n", true),
          new CharacterClassMatrixSeparator("emptyQuoteSpace", "\\Q\\E ", true),
          new CharacterClassMatrixSeparator("spaceEmptyQuote", " \\Q\\E", true));
      List<String> operators = List.of("&&", "&&&", "&&&&", "&&&&&", "&&&&&&");
      List<CharacterClassMatrixPiece> rightPieces = List.of(
          new CharacterClassMatrixPiece("none", ""),
          new CharacterClassMatrixPiece("litA", "a"),
          new CharacterClassMatrixPiece("litB", "b"),
          new CharacterClassMatrixPiece("rangeAB", "a-b"),
          new CharacterClassMatrixPiece("zero", "0"),
          new CharacterClassMatrixPiece("range01", "0-1"),
          new CharacterClassMatrixPiece("rawAmp", "&"),
          new CharacterClassMatrixPiece("escapedAmp", "\\&"),
          new CharacterClassMatrixPiece("quoteAmp", "\\Q&\\E"),
          new CharacterClassMatrixPiece("quoteA", "\\Qa\\E"),
          new CharacterClassMatrixPiece("quoteEmpty", "\\Q\\E"),
          new CharacterClassMatrixPiece("nonInline", "Ā"),
          new CharacterClassMatrixPiece("escapedNonAscii", "\\Ā"),
          new CharacterClassMatrixPiece("nestedA", "[a]"),
          new CharacterClassMatrixPiece("nestedB", "[b]"),
          new CharacterClassMatrixPiece("nestedAB", "[ab]"),
          new CharacterClassMatrixPiece("digit", "\\d"),
          new CharacterClassMatrixPiece("word", "\\w"),
          new CharacterClassMatrixPiece("nonDigit", "\\D"),
          new CharacterClassMatrixPiece("propertyLower", "\\p{Lower}"),
          new CharacterClassMatrixPiece("propertyNotLower", "\\P{Lower}"),
          new CharacterClassMatrixPiece("propertyJavaLower", "\\p{javaLowerCase}"));

      List<CharacterClassMatrixSpace> spaces = new ArrayList<>();
      for (boolean comments : List.of(false, true)) {
        for (boolean negated : List.of(false, true)) {
          String prefix = (comments ? "(?x)" : "") + "[" + (negated ? "^" : "");
          List<String> activeSeparators = activeSeparatorTexts(separators, comments);
          List<String> activeAfterOperatorSeparators =
              activeSeparatorTexts(afterOperatorSeparators, comments);

          spaces.add(new CharacterClassMatrixSpace(prefix, List.of(
              pieceTexts(basePieces),
              pieceTexts(basePieces),
              activeSeparators,
              operators,
              activeAfterOperatorSeparators,
              pieceTexts(rightPieces),
              pieceTexts(trailingPieces))));
          spaces.add(new CharacterClassMatrixSpace(prefix, List.of(
              pieceTexts(basePieces),
              pieceTexts(ampersandPieces),
              activeSeparators,
              operators,
              activeAfterOperatorSeparators,
              pieceTexts(rightPieces),
              pieceTexts(trailingPieces))));
          spaces.add(new CharacterClassMatrixSpace(prefix, List.of(
              activeSeparators,
              operators,
              activeAfterOperatorSeparators,
              pieceTexts(rightPieces),
              pieceTexts(trailingPieces))));
        }
      }
      return new CharacterClassExpressionMatrix(spaces);
    }

    private static List<String> pieceTexts(List<CharacterClassMatrixPiece> pieces) {
      return pieces.stream().map(CharacterClassMatrixPiece::text).toList();
    }

    private static List<String> activeSeparatorTexts(
        List<CharacterClassMatrixSeparator> separators, boolean comments) {
      return separators.stream()
          .filter(separator -> comments || !separator.commentsModeOnly())
          .map(CharacterClassMatrixSeparator::text)
          .toList();
    }

    private static CharacterClassMatrixOutcome jdkCharacterClassOutcome(String regex) {
      try {
        return new CharacterClassMatrixOutcome(true,
            characterClassMatrixMatches(java.util.regex.Pattern.compile(regex)));
      } catch (PatternSyntaxException e) {
        return new CharacterClassMatrixOutcome(false, "");
      }
    }

    private static CharacterClassMatrixOutcome safeReCharacterClassOutcome(String regex) {
      try {
        return new CharacterClassMatrixOutcome(true,
            characterClassMatrixMatches(Pattern.compile(regex)));
      } catch (PatternSyntaxException e) {
        return new CharacterClassMatrixOutcome(false, "");
      }
    }

    private static String characterClassMatrixMatches(java.util.regex.Pattern pattern) {
      StringBuilder result = new StringBuilder();
      for (String input : characterClassMatrixInputs()) {
        if (pattern.matcher(input).matches()) {
          if (result.length() > 0) {
            result.append(',');
          }
          result.append(input);
        }
      }
      return result.toString();
    }

    private static String characterClassMatrixMatches(Pattern pattern) {
      StringBuilder result = new StringBuilder();
      for (String input : characterClassMatrixInputs()) {
        if (pattern.matcher(input).matches()) {
          if (result.length() > 0) {
            result.append(',');
          }
          result.append(input);
        }
      }
      return result.toString();
    }

    private static List<String> characterClassMatrixInputs() {
      return List.of("", "a", "b", "c", "&", "-", "0", "1", "x", "_", " ", "\t", "Ā", "é",
          "\n");
    }

    @Test
    @DisplayName("subtraction [a-z&&[^bc]]")
    void subtraction() {
      assertMatchesSame("[a-z&&[^bc]]", "a");
      assertMatchesFull("[a-z&&[^bc]]", "b");
    }

    @Test
    @DisplayName("subtraction [a-z&&[^m-p]]")
    void subtractionRange() {
      assertMatchesSame("[a-z&&[^m-p]]", "a");
      assertMatchesFull("[a-z&&[^m-p]]", "n");
    }

    @Test
    @DisplayName("surrogate pair range in character class")
    void surrogatePairRange() {
      // From issue #127 comment: surrogate pairs encoding supplementary ranges
      assertCompiles("[\\uD800\\uDC00-\\uDBFF\\uDFFF]");
    }

    @Test
    @DisplayName("complex Unicode range with explicit surrogate endpoints")
    void complexUnicodeRange() {
      // Pattern from issue #127 comment
      assertCompiles("([\\u0020-\\uD7FF\\uE000-\\uFFFD\\uD800\\uDC00-\\uDBFF\\uDFFF\\t]*)$");
    }
  }

  // ===========================================================================
  // 4. POSIX Character Classes (\p{...})
  // ===========================================================================

  @Nested
  @DisplayName("POSIX character classes")
  class PosixCharacterClasses {

    static Stream<Arguments> posixClasses() {
      return Stream.of(
          Arguments.of("\\p{Lower}", "a", "5"),
          Arguments.of("\\p{Upper}", "A", "a"),
          Arguments.of("\\p{ASCII}", "x", "\u0080"),
          Arguments.of("\\p{Alpha}", "a", "5"),
          Arguments.of("\\p{Digit}", "5", "a"),
          Arguments.of("\\p{Alnum}", "a", "!"),
          Arguments.of("\\p{Punct}", "!", "a"),
          Arguments.of("\\p{Graph}", "a", " "),
          Arguments.of("\\p{Print}", "a", "\u0000"),
          Arguments.of("\\p{Blank}", " ", "a"),
          Arguments.of("\\p{Cntrl}", "\u0000", "a"),
          Arguments.of("\\p{XDigit}", "f", "g"),
          Arguments.of("\\p{Space}", " ", "a"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("posixClasses")
    @DisplayName("POSIX class")
    void posixClass(String regex, String shouldMatch, String shouldNotMatch) {
      assertMatchesSame(regex, shouldMatch);
      assertMatchesFull(regex, shouldNotMatch);
    }

    static Stream<Arguments> posixBracketClassSyntax() {
      return Stream.of(
          Arguments.of("[[:lower:]]", "l", true),
          Arguments.of("[[:lower:]]", "o", true),
          Arguments.of("[[:lower:]]", "w", true),
          Arguments.of("[[:lower:]]", "e", true),
          Arguments.of("[[:lower:]]", "r", true),
          Arguments.of("[[:lower:]]", "a", false),
          Arguments.of("[[:lower:]]", ":", true),
          Arguments.of("[[:alpha:]]", "p", true),
          Arguments.of("[[:alpha:]]", "h", true),
          Arguments.of("[[:alpha:]]", "Z", false),
          Arguments.of("[[:digit:]]", "d", true),
          Arguments.of("[[:digit:]]", "i", true),
          Arguments.of("[[:digit:]]", "g", true),
          Arguments.of("[[:digit:]]", "t", true),
          Arguments.of("[[:digit:]]", "5", false),
          Arguments.of("[[:^space:]]", "^", true),
          Arguments.of("[[:^space:]]", "s", true),
          Arguments.of("[[:^space:]]", "p", true),
          Arguments.of("[[:^space:]]", "a", true),
          Arguments.of("[[:^space:]]", "c", true),
          Arguments.of("[[:^space:]]", "e", true),
          Arguments.of("[[:^space:]]", " ", false),
          Arguments.of("[[.ch.]]", ".", true),
          Arguments.of("[[.ch.]]", "c", true),
          Arguments.of("[[.ch.]]", "h", true),
          Arguments.of("[[=a=]]", "=", true),
          Arguments.of("[[=a=]]", "a", true),
          Arguments.of("[^[:lower:]]", "a", true),
          Arguments.of("[^[:lower:]]", "l", false),
          Arguments.of("[^[:lower:]]", ":", false));
    }

    @ParameterizedTest(name = "{0} on \"{1}\"")
    @MethodSource("posixBracketClassSyntax")
    @DisplayName("POSIX bracket class spelling is ordinary character-class text")
    void posixBracketClassSyntaxIsOrdinaryText(
        String regex, String input, boolean expectedMatch) {
      boolean jdkMatches = java.util.regex.Pattern.compile(regex).matcher(input).matches();
      boolean safeMatches = Pattern.compile(regex).matcher(input).matches();

      assertThat(jdkMatches)
          .as("JDK baseline for /%s/ on \"%s\"", regex, input)
          .isEqualTo(expectedMatch);
      assertThat(safeMatches)
          .as("SafeRE for /%s/ on \"%s\"", regex, input)
          .isEqualTo(expectedMatch);
    }
  }

  // ===========================================================================
  // 5. java.lang.Character Classes
  // ===========================================================================

  @Nested
  @DisplayName("java.lang.Character classes")
  class JavaCharacterClasses {

    static Stream<Arguments> javaClasses() {
      return Stream.of(
          Arguments.of("\\p{javaLowerCase}", "a", "A"),
          Arguments.of("\\p{javaUpperCase}", "A", "a"),
          Arguments.of("\\p{javaWhitespace}", " ", "a"),
          Arguments.of("\\p{javaMirrored}", "(", "a"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("javaClasses")
    @DisplayName("java.lang.Character class")
    void javaCharClass(String regex, String shouldMatch, String shouldNotMatch) {
      assertMatchesSame(regex, shouldMatch);
      assertMatchesFull(regex, shouldNotMatch);
    }
  }

  // ===========================================================================
  // 6. Unicode Scripts, Blocks, Categories, Binary Properties
  // ===========================================================================

  @Nested
  @DisplayName("Unicode scripts")
  class UnicodeScripts {

    @Test
    @DisplayName("\\\\p{IsLatin}")
    void isLatinScript() {
      assertMatchesSame("\\p{IsLatin}", "A");
      assertMatchesFull("\\p{IsLatin}", "\u4E00"); // CJK char
    }

    @Test
    @DisplayName("\\\\p{IsHiragana}")
    void isHiraganaScript() {
      assertMatchesSame("\\p{IsHiragana}", "\u3042"); // Hiragana 'a'
    }

    @Test
    @DisplayName("\\\\p{script=Hiragana}")
    void scriptKeywordHiragana() {
      assertMatchesSame("\\p{script=Hiragana}", "\u3042");
    }

    @Test
    @DisplayName("\\\\p{sc=Hiragana}")
    void scKeywordHiragana() {
      assertMatchesSame("\\p{sc=Hiragana}", "\u3042");
    }

    @Test
    @DisplayName("\\\\p{sc=Latin}")
    void scKeywordLatin() {
      assertMatchesSame("\\p{sc=Latin}", "A");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\p{Latin}", "\\P{Latin}", "[\\p{Latin}]", "\\p{script=Lu}"})
    @DisplayName("rejects non-JDK script property spellings")
    void rejectsNonJdkScriptPropertySpellings(String regex) {
      assertRejectedByJdkAndSafeRe(regex);
    }
  }

  @Nested
  @DisplayName("Unicode blocks")
  class UnicodeBlocks {

    @Test
    @DisplayName("\\\\p{InGreek}")
    void inGreek() {
      assertMatchesSame("\\p{InGreek}", "\u0391"); // Alpha
    }

    @Test
    @DisplayName("\\\\p{InBasicLatin}")
    void inBasicLatin() {
      assertMatchesSame("\\p{InBasicLatin}", "A");
    }

    @Test
    @DisplayName("\\\\p{block=Mongolian}")
    void blockKeywordMongolian() {
      assertMatchesSame("\\p{block=Mongolian}", "\u1820");
    }

    @Test
    @DisplayName("\\\\p{blk=Greek}")
    void blkKeywordGreek() {
      assertMatchesSame("\\p{blk=Greek}", "\u0391");
    }

    @Test
    @DisplayName("\\\\P{InGreek} (negated)")
    void notInGreek() {
      assertMatchesSame("\\P{InGreek}", "A");
      assertMatchesFull("\\P{InGreek}", "\u0391");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\\p{Braille}",
        "\\P{Braille}",
        "\\p{^Braille}",
        "[\\p{Braille}]",
        "\\p{general_category=Latin}"
    })
    @DisplayName("rejects non-JDK block property spellings")
    void rejectsNonJdkBlockPropertySpellings(String regex) {
      assertRejectedByJdkAndSafeRe(regex);
    }
  }

  @Nested
  @DisplayName("Unicode categories")
  class UnicodeCategories {

    @Test
    @DisplayName("\\\\p{Lu} (uppercase letter)")
    void luCategory() {
      assertMatchesSame("\\p{Lu}", "A");
      assertMatchesFull("\\p{Lu}", "a");
    }

    @Test
    @DisplayName("\\\\p{Ll} (lowercase letter)")
    void llCategory() {
      assertMatchesSame("\\p{Ll}", "a");
    }

    @Test
    @DisplayName("\\\\p{L} (letter)")
    void lCategory() {
      assertMatchesSame("\\p{L}", "a");
      assertMatchesFull("\\p{L}", "5");
    }

    @Test
    @DisplayName("\\\\p{IsLu} (category with Is prefix)")
    void isLuCategory() {
      assertMatchesSame("\\p{IsLu}", "A");
      assertMatchesFull("\\p{IsLu}", "a");
    }

    @Test
    @DisplayName("\\\\p{IsL} (category with Is prefix)")
    void isLCategory() {
      assertMatchesSame("\\p{IsL}", "a");
      assertMatchesFull("\\p{IsL}", "5");
    }

    @Test
    @DisplayName("\\\\p{Sc} (currency symbol)")
    void scCategory() {
      assertMatchesSame("\\p{Sc}", "$");
      assertMatchesFull("\\p{Sc}", "a");
    }

    @Test
    @DisplayName("\\\\p{Nd} (decimal digit number)")
    void ndCategory() {
      assertMatchesSame("\\p{Nd}", "5");
    }

    @Test
    @DisplayName("\\\\p{general_category=Lu}")
    void gcKeywordLu() {
      assertMatchesSame("\\p{general_category=Lu}", "A");
    }

    @Test
    @DisplayName("\\\\p{gc=Lu}")
    void gcShortKeywordLu() {
      assertMatchesSame("\\p{gc=Lu}", "A");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\p{lu}", "\\p{gc=Latin}", "\\p{gc=Letter}"})
    @DisplayName("rejects non-JDK category property spellings")
    void rejectsNonJdkCategoryPropertySpellings(String regex) {
      assertRejectedByJdkAndSafeRe(regex);
    }

    @Test
    @DisplayName("[\\\\p{L}&&[^\\\\p{Lu}]] (category subtraction)")
    void categorySubtraction() {
      assertMatchesSame("[\\p{L}&&[^\\p{Lu}]]", "a");
      assertMatchesFull("[\\p{L}&&[^\\p{Lu}]]", "A");
    }
  }

  @Nested
  @DisplayName("Unicode binary properties")
  class UnicodeBinaryProperties {

    static Stream<Arguments> binaryProperties() {
      return Stream.of(
          Arguments.of("\\p{IsAlphabetic}", "a", "5"),
          Arguments.of("\\p{IsIdeographic}", "\u4E00", "a"),
          Arguments.of("\\p{IsLetter}", "a", "5"),
          Arguments.of("\\p{IsLowercase}", "a", "A"),
          Arguments.of("\\p{IsUppercase}", "A", "a"),
          Arguments.of("\\p{IsTitlecase}", "\u01C5", "a"), // Dz with caron
          Arguments.of("\\p{IsPunctuation}", "!", "a"),
          Arguments.of("\\p{IsControl}", "\u0000", "a"),
          Arguments.of("\\p{IsWhite_Space}", " ", "a"),
          Arguments.of("\\p{IsDigit}", "5", "a"),
          Arguments.of("\\p{IsHex_Digit}", "f", "g"),
          Arguments.of("\\p{IsJoin_Control}", "\u200C", "a"), // ZWNJ
          Arguments.of("\\p{IsNoncharacter_Code_Point}", "\uFDD0", "a"),
          Arguments.of("\\p{IsAssigned}", "a", "\uFFFF"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("binaryProperties")
    @DisplayName("binary property")
    void binaryProperty(String regex, String shouldMatch, String shouldNotMatch) {
      assertMatchesSame(regex, shouldMatch);
      assertMatchesFull(regex, shouldNotMatch);
    }

    @Test
    @DisplayName("\\\\p{IsWhiteSpace} property alias without underscore")
    void isWhiteSpaceNoUnderscore() {
      // Regression for issue #127. JDK is flexible about underscores in property names.
      assertMatchesSame("\\p{IsWhiteSpace}", " ");
    }

    @Test
    @DisplayName("\\\\p{IsEmoji}")
    void isEmoji() {
      assertMatchesSame("\\p{IsEmoji}", "\u263A"); // white smiling face
    }

    @Test
    @DisplayName("\\\\p{IsEmoji_Presentation}")
    void isEmojiPresentation() {
      assertCompiles("\\p{IsEmoji_Presentation}");
    }

    @Test
    @DisplayName("\\\\p{IsEmoji_Modifier}")
    void isEmojiModifier() {
      assertCompiles("\\p{IsEmoji_Modifier}");
    }

    @Test
    @DisplayName("\\\\p{IsEmoji_Modifier_Base}")
    void isEmojiModifierBase() {
      assertCompiles("\\p{IsEmoji_Modifier_Base}");
    }

    @Test
    @DisplayName("\\\\p{IsEmoji_Component}")
    void isEmojiComponent() {
      assertCompiles("\\p{IsEmoji_Component}");
    }

    @Test
    @DisplayName("\\\\p{IsExtended_Pictographic}")
    void isExtendedPictographic() {
      assertCompiles("\\p{IsExtended_Pictographic}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\p{Alphabetic}", "\\P{Alphabetic}", "[\\p{Alphabetic}]"})
    @DisplayName("rejects binary properties without Is prefix")
    void rejectsBinaryPropertiesWithoutIsPrefix(String regex) {
      assertRejectedByJdkAndSafeRe(regex);
    }
  }

  // ===========================================================================
  // 7. Boundary Matchers
  // ===========================================================================

  @Nested
  @DisplayName("Boundary matchers")
  class BoundaryMatchers {

    @Test
    @DisplayName("^ beginning of line")
    void beginLine() {
      assertMatchesSame("^abc", "abc");
    }

    @Test
    @DisplayName("$ end of line")
    void endLine() {
      assertMatchesSame("abc$", "abc");
    }

    @Test
    @DisplayName("\\\\b word boundary")
    void wordBoundary() {
      assertMatchesSame("\\bword\\b", "a word here");
    }

    @Test
    @DisplayName("\\\\B non-word boundary")
    void nonWordBoundary() {
      assertMatchesSame("\\Bord", "word");
    }

    @Test
    @DisplayName("\\\\b{g} grapheme cluster boundary")
    void graphemeClusterBoundary() {
      assertCompiles("\\b{g}");
    }

    @Test
    @DisplayName("\\\\A beginning of input")
    void beginInput() {
      assertMatchesSame("\\Aabc", "abc");
    }

    @Test
    @DisplayName("\\\\Z end of input (before final terminator)")
    void endInputBeforeTerminator() {
      assertMatchesSame("abc\\Z", "abc\n");
    }

    @Test
    @DisplayName("\\\\z end of input")
    void endInput() {
      assertMatchesSame("abc\\z", "abc");
    }

    @Test
    @DisplayName("\\\\G end of previous match (should reject)")
    void endPreviousMatch() {
      // \G requires state from previous matches; SafeRE should reject.
      // But JDK accepts it.
      assertThatNoException()
          .as("JDK should accept \\G")
          .isThrownBy(() -> java.util.regex.Pattern.compile("\\G"));
      assertThatThrownBy(() -> Pattern.compile("\\G"))
          .as("SafeRE should reject \\G")
          .isInstanceOf(PatternSyntaxException.class);
    }
  }

  // ===========================================================================
  // 8. Quantifiers
  // ===========================================================================

  @Nested
  @DisplayName("Quantifiers")
  class Quantifiers {

    // -- Greedy --

    @Test
    @DisplayName("greedy ? (zero or one)")
    void greedyQuestion() {
      assertMatchesSame("ab?c", "ac");
      assertMatchesSame("ab?c", "abc");
    }

    @Test
    @DisplayName("greedy * (zero or more)")
    void greedyStar() {
      assertMatchesSame("ab*c", "ac");
      assertMatchesSame("ab*c", "abbc");
    }

    @Test
    @DisplayName("greedy + (one or more)")
    void greedyPlus() {
      assertMatchesSame("ab+c", "abc");
      assertMatchesSame("ab+c", "abbc");
    }

    @Test
    @DisplayName("greedy {n}")
    void greedyExact() {
      assertMatchesSame("a{3}", "aaa");
    }

    @Test
    @DisplayName("greedy {n,}")
    void greedyAtLeast() {
      assertMatchesSame("a{2,}", "aaaa");
    }

    @Test
    @DisplayName("greedy {n,m}")
    void greedyRange() {
      assertMatchesSame("a{2,4}", "aaaaa");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{?", "a{?", "a{,2}", "a{x}", "\\\\Q{?\\\\E"})
    @DisplayName("malformed unescaped counted repetition")
    void malformedUnescapedCountedRepetition(String regex) {
      assertThatThrownBy(() -> java.util.regex.Pattern.compile(regex))
          .as("JDK should reject malformed counted repetition: %s", regex)
          .isInstanceOf(PatternSyntaxException.class);
      assertThatThrownBy(() -> Pattern.compile(regex))
          .as("SafeRE should reject malformed counted repetition: %s", regex)
          .isInstanceOf(PatternSyntaxException.class);
    }

    // -- Reluctant --

    @Test
    @DisplayName("reluctant ??")
    void reluctantQuestion() {
      assertMatchesSame("ab??c", "ac");
    }

    @Test
    @DisplayName("reluctant *?")
    void reluctantStar() {
      assertMatchesSame("a.*?c", "abcbc");
    }

    @Test
    @DisplayName("reluctant +?")
    void reluctantPlus() {
      assertMatchesSame("a.+?c", "abcbc");
    }

    @Test
    @DisplayName("reluctant {n,m}?")
    void reluctantRange() {
      assertMatchesSame("a{2,4}?", "aaaaa");
    }

    // -- Possessive (SafeRE should reject) --

    @ParameterizedTest
    @ValueSource(strings = {"a?+", "a*+", "a++", "a{2}+", "a{2,}+", "a{2,4}+"})
    @DisplayName("possessive quantifiers (should reject)")
    void possessiveQuantifiers(String regex) {
      // JDK accepts these.
      assertThatNoException()
          .as("JDK should accept: %s", regex)
          .isThrownBy(() -> java.util.regex.Pattern.compile(regex));
      // SafeRE should reject — possessive quantifiers violate linear-time guarantees.
      assertThatThrownBy(() -> Pattern.compile(regex))
          .as("SafeRE should reject possessive quantifier: %s", regex)
          .isInstanceOf(PatternSyntaxException.class);
    }

    // -- Nested repetitions --

    @Test
    @DisplayName("nested repetition {0,99} inside {0,5}")
    void nestedRepetition() {
      // Regression for issue #127.
      assertCompiles("(?:a (?:b{0,99}|c{0,9})){0,5}");
    }

    @Test
    @DisplayName("simple nested repetition")
    void simpleNestedRepetition() {
      assertMatchesSame("(ab{2,3}){2}", "abbbabb");
    }

    @Test
    @DisplayName("bounded repeat of concatenated bounded repeats")
    void boundedRepeatOfConcatenatedBoundedRepeats() {
      assertMatchesSame("(?:a{0,63}b{0,99}){0,5}", "aaabbbb");
    }
  }

  // ===========================================================================
  // 9. Logical Operators
  // ===========================================================================

  @Nested
  @DisplayName("Logical operators")
  class LogicalOperators {

    @Test
    @DisplayName("concatenation XY")
    void concatenation() {
      assertMatchesSame("ab", "ab");
    }

    @Test
    @DisplayName("alternation X|Y")
    void alternation() {
      assertMatchesSame("cat|dog", "I have a dog");
    }

    @Test
    @DisplayName("capturing group (X)")
    void capturingGroup() {
      assertMatchesSame("(ab)+", "ababab");
    }
  }

  // ===========================================================================
  // 10. Back References (SafeRE should reject)
  // ===========================================================================

  @Nested
  @DisplayName("Back references (should reject)")
  class BackReferences {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "(a)\\1",
          "(a)(b)\\2",
          "(a)\\123",
          "(a)(b)(c)(d)\\400",
          "(?<name>a)\\k<name>"
        })
    @DisplayName("back reference")
    void backReference(String regex) {
      // JDK accepts these.
      assertThatNoException()
          .as("JDK should accept: %s", regex)
          .isThrownBy(() -> java.util.regex.Pattern.compile(regex));
      // SafeRE should reject — back references require backtracking.
      assertThatThrownBy(() -> Pattern.compile(regex))
          .as("SafeRE should reject back reference: %s", regex)
          .isInstanceOf(PatternSyntaxException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\1", "\\9", "\\12"})
    @DisplayName("unresolved numeric back reference forms never match")
    void unresolvedNumericBackReferenceFormsNeverMatch(String regex) {
      assertMatchesSame(regex, "");
      assertMatchesSame(regex, "1");
      assertMatchesSame(regex, "9");
      assertMatchesSame(regex, "S");
    }

    @ParameterizedTest
    @ValueSource(strings = {"(a)\\12", "(a)\\123", "(a)(b)\\12"})
    @DisplayName("numeric back reference shortened to an existing preceding group")
    void numericBackReferenceShortenedToExistingPrecedingGroup(String regex) {
      assertThatNoException()
          .as("JDK should accept shortened numeric back reference: %s", regex)
          .isThrownBy(() -> java.util.regex.Pattern.compile(regex));
      assertThatThrownBy(() -> Pattern.compile(regex))
          .as("SafeRE should reject numeric back reference: %s", regex)
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    @DisplayName("multi-digit numeric back reference to an existing preceding group")
    void multiDigitNumericBackReferenceToExistingPrecedingGroup() {
      String regex = "(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)(l)\\12";

      assertThatNoException()
          .as("JDK should accept numeric back reference to group 12")
          .isThrownBy(() -> java.util.regex.Pattern.compile(regex));
      assertThatThrownBy(() -> Pattern.compile(regex))
          .as("SafeRE should reject numeric back reference to group 12")
          .isInstanceOf(PatternSyntaxException.class);
    }
  }

  // ===========================================================================
  // 11. Quotation
  // ===========================================================================

  @Nested
  @DisplayName("Quotation")
  class Quotation {

    @Test
    @DisplayName("\\\\Q...\\\\E quotes metacharacters")
    void quotation() {
      assertMatchesSame("\\Q.+*\\E", ".+*");
    }

    @Test
    @DisplayName("\\\\Q...\\\\E at end of pattern (no \\\\E)")
    void quotationNoEnd() {
      assertMatchesSame("\\Q.+*", ".+*");
    }

    @Test
    @DisplayName("\\\\Q...\\\\E with normal regex after")
    void quotationWithRegexAfter() {
      assertMatchesSame("\\Q.+\\E.+", ".+ab");
    }

    @Test
    @DisplayName("\\\\Q...\\\\E quotes braces")
    void quotationWithBrace() {
      assertMatchesSame("\\Q{?\\E", "{?");
    }
  }

  // ===========================================================================
  // 12. Special Constructs
  // ===========================================================================

  @Nested
  @DisplayName("Special constructs")
  class SpecialConstructs {

    @Test
    @DisplayName("named capturing group (?<name>X)")
    void namedCapturingGroup() {
      assertMatchesSame("(?<word>\\w+)", "hello");
    }

    @Test
    @DisplayName("Python-style named capturing group (?P<name>X) is rejected")
    void pythonStyleNamedCapturingGroupRejected() {
      String regex = "(?" + "P<word>\\w+)";
      assertThatThrownBy(() -> java.util.regex.Pattern.compile(regex))
          .isInstanceOf(PatternSyntaxException.class);
      assertThatThrownBy(() -> Pattern.compile(regex))
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    @DisplayName("non-capturing group (?:X)")
    void nonCapturingGroup() {
      assertMatchesSame("(?:ab)+", "ababab");
    }

    // -- Lookahead/Lookbehind (SafeRE should reject) --

    @ParameterizedTest
    @ValueSource(
        strings = {
          "a(?=b)", // positive lookahead
          "a(?!b)", // negative lookahead
          "(?<=a)b", // positive lookbehind
          "(?<!a)b" // negative lookbehind
        })
    @DisplayName("lookahead/lookbehind (should reject)")
    void lookaround(String regex) {
      assertThatNoException()
          .as("JDK should accept: %s", regex)
          .isThrownBy(() -> java.util.regex.Pattern.compile(regex));
      assertThatThrownBy(() -> Pattern.compile(regex))
          .as("SafeRE should reject lookaround: %s", regex)
          .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    @DisplayName("independent non-capturing group (?>X) (should reject)")
    void atomicGroup() {
      String regex = "(?>a+)";
      assertThatNoException()
          .as("JDK should accept: %s", regex)
          .isThrownBy(() -> java.util.regex.Pattern.compile(regex));
      assertThatThrownBy(() -> Pattern.compile(regex))
          .as("SafeRE should reject atomic group: %s", regex)
          .isInstanceOf(PatternSyntaxException.class);
    }
  }

  // ===========================================================================
  // 13. Inline Flags
  // ===========================================================================

  @Nested
  @DisplayName("Inline flags")
  class InlineFlags {

    @Test
    @DisplayName("(?i) case insensitive")
    void flagI() {
      assertMatchesSame("(?i)abc", "ABC");
    }

    @Test
    @DisplayName("(?d) UNIX_LINES")
    void flagD() {
      assertCompiles("(?d).");
      // With (?d), only \n is a line terminator; \r should be matched by dot.
      assertMatchesSame("(?d).", "\r");
    }

    @Test
    @DisplayName("(?m) multiline")
    void flagM() {
      assertMatchesSame("(?m)^abc$", "xyz\nabc\ndef");
    }

    @Test
    @DisplayName("(?s) dotall")
    void flagS() {
      assertMatchesSame("(?s)a.b", "a\nb");
    }

    @Test
    @DisplayName("(?u) unicode case")
    void flagU() {
      assertCompiles("(?u)(?i)abc");
    }

    @Test
    @DisplayName("(?x) comments mode")
    void flagX() {
      assertMatchesSame("(?x) a b c ", "abc");
    }

    @Test
    @DisplayName("(?U) UNICODE_CHARACTER_CLASS")
    void flagBigU() {
      assertCompiles("(?U)\\w");
    }

    @Test
    @DisplayName("combined flags (?dm)")
    void combinedFlags() {
      assertCompiles("(?dm)^test$");
    }

    @Test
    @DisplayName("negated flags (?-i)")
    void negatedFlags() {
      assertCompiles("(?i)abc(?-i)def");
    }

    @Test
    @DisplayName("flags on non-capturing group (?i:abc)")
    void flagsOnGroup() {
      assertMatchesSame("(?i:abc)def", "ABCdef");
    }

    @Test
    @DisplayName("(?d) combined with (?m)")
    void flagDWithM() {
      // Regression for issue #127.
      assertCompiles("(?m)(?d)^(####? .+|---)$");
    }

    @Test
    @DisplayName("all JDK flags combined (?idmsuxU)")
    void allFlags() {
      assertCompiles("(?idmsuxU)test");
    }

    @Test
    @DisplayName("all JDK flags negated (?-idmsuxU)")
    void allFlagsNegated() {
      assertCompiles("(?idmsuxU)(?-idmsuxU)test");
    }

    @Test
    @DisplayName("flag group (?idmsuxU:X)")
    void flagGroup() {
      assertCompiles("(?ims:abc)");
    }
  }

  // ===========================================================================
  // 14. API Compile Flags
  // ===========================================================================

  @Nested
  @DisplayName("API compile flags")
  class ApiCompileFlags {

    @Test
    @DisplayName("Pattern.CASE_INSENSITIVE")
    void caseInsensitive() {
      assertMatchesSameWithFlags("abc", Pattern.CASE_INSENSITIVE, "ABC");
    }

    @Test
    @DisplayName("Pattern.MULTILINE")
    void multiline() {
      assertMatchesSameWithFlags("^abc$", Pattern.MULTILINE, "xyz\nabc\ndef");
    }

    @Test
    @DisplayName("Pattern.DOTALL")
    void dotall() {
      assertMatchesSameWithFlags("a.b", Pattern.DOTALL, "a\nb");
    }

    @Test
    @DisplayName("Pattern.UNIX_LINES")
    void unixLines() {
      assertMatchesSameWithFlags(".", Pattern.UNIX_LINES, "\r");
    }

    @Test
    @DisplayName("Pattern.COMMENTS")
    void comments() {
      assertMatchesSameWithFlags("a b c # comment", Pattern.COMMENTS, "abc");
    }

    @Test
    @DisplayName("Pattern.UNICODE_CASE")
    void unicodeCase() {
      assertMatchesSameWithFlags(
          "abc", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE, "ABC");
    }

    @Test
    @DisplayName("Pattern.UNICODE_CHARACTER_CLASS")
    void unicodeCharacterClass() {
      assertMatchesSameWithFlags("\\w", Pattern.UNICODE_CHARACTER_CLASS, "\u00E9"); // e-acute
    }
  }

  // ===========================================================================
  // 15. Linebreak and Grapheme (matching behavior)
  // ===========================================================================

  @Nested
  @DisplayName("Linebreak and grapheme matching")
  class LinebreakAndGrapheme {

    @Test
    @DisplayName("\\\\R prefers \\\\r\\\\n over bare \\\\r")
    void linebreakCrLfPreference() {
      // \R should match \r\n as a single unit, not just \r
      java.util.regex.Matcher jdkM = java.util.regex.Pattern.compile("\\R").matcher("\r\n");
      assertThat(jdkM.find()).isTrue();
      String jdkGroup = jdkM.group();

      Matcher safeM = Pattern.compile("\\R").matcher("\r\n");
      assertThat(safeM.find()).isTrue();
      assertThat(safeM.group()).isEqualTo(jdkGroup);
    }

    @Test
    @DisplayName("\\\\R does not match ordinary characters")
    void linebreakNoOrdinary() {
      assertMatchesFull("\\R", "a");
    }

    @Test
    @DisplayName("dot respects line terminators by default")
    void dotDefaultLineTerminators() {
      assertMatchesFull(".", "\n");
    }

    @Test
    @DisplayName("dot with DOTALL matches newline")
    void dotDotallMatchesNewline() {
      assertMatchesSameWithFlags(".", Pattern.DOTALL, "\n");
    }
  }

  // ===========================================================================
  // 16. Line terminators
  // ===========================================================================

  @Nested
  @DisplayName("Line terminators")
  class LineTerminators {

    static Stream<Arguments> lineTerminators() {
      return Stream.of(
          Arguments.of("\\n (newline)", "\n"),
          Arguments.of("\\r\\n (CRLF)", "\r\n"),
          Arguments.of("\\r (carriage return)", "\r"),
          Arguments.of("\\u0085 (next line)", "\u0085"),
          Arguments.of("\\u2028 (line separator)", "\u2028"),
          Arguments.of("\\u2029 (paragraph separator)", "\u2029"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lineTerminators")
    @DisplayName("dot does not match line terminator")
    void dotDoesNotMatch(String desc, String terminator) {
      // dot should NOT match line terminators (without DOTALL)
      java.util.regex.Matcher jdkM = java.util.regex.Pattern.compile(".").matcher(terminator);
      Matcher safeM = Pattern.compile(".").matcher(terminator);
      assertThat(safeM.find())
          .as("dot on %s", desc)
          .isEqualTo(jdkM.find());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lineTerminators")
    @DisplayName("(?m)$ matches before line terminator")
    void multilineDollar(String desc, String terminator) {
      String input = "abc" + terminator + "def";
      assertMatchesSameWithFlags("abc$", Pattern.MULTILINE, input);
    }
  }

  // ===========================================================================
  // 17. Escaped metacharacters
  // ===========================================================================

  @Nested
  @DisplayName("Escaped metacharacters")
  class EscapedMetacharacters {

    @ParameterizedTest
    @ValueSource(strings = {"\\.", "\\*", "\\+", "\\?", "\\(", "\\)", "\\[", "\\]",
        "\\{", "\\}", "\\|", "\\^", "\\$", "\\\\"})
    @DisplayName("escaped metacharacter is literal")
    void escapedMetacharacter(String regex) {
      assertCompiles(regex);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\©", "\\é", "\\Ā", "\\☃", "\\😀"})
    @DisplayName("escaped non-ASCII character is literal")
    void escapedNonAsciiCharacter(String regex) {
      String literal = regex.substring(1);
      assertMatchesFull(regex, literal);
      assertMatchesFull("^" + regex + "$", literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\\©]", "[\\é]", "[\\Ā]", "[\\☃]", "[\\😀]"})
    @DisplayName("escaped non-ASCII character in class is literal")
    void escapedNonAsciiCharacterInClass(String regex) {
      String literal = regex.substring(2, regex.length() - 1);
      assertMatchesFull(regex, literal);
    }
  }

  // ===========================================================================
  // 18. Compatibility edge cases
  // ===========================================================================

  @Nested
  @DisplayName("Compatibility edge cases")
  class CompatibilityEdgeCases {

    @Test
    @DisplayName("(?m)(?d)^(####? .+|---)$")
    void inlineFlagDWithMultiline() {
      // Regression for issue #127.
      assertMatchesSame("(?m)(?d)^(####? .+|---)$", "## Hello");
    }

    @Test
    @DisplayName("Thai character range with \\\\u escapes")
    void thaiCharacterRange() {
      assertMatchesSame("([\\u0E00-\\u0E7F])([0-9a-zA-Z])", "\u0E01a");
    }

    @Test
    @DisplayName("nested repetition (?:a (?:b{0,99}|c{0,9})){0,5}")
    void nestedRepetitionFromIssue() {
      // Regression for issue #127.
      assertMatchesSame("(?:a (?:b{0,99}|c{0,9})){0,5}", "a bbb");
    }

    @Test
    @DisplayName("\\\\p{IsWhiteSpace} (no underscore)")
    void isWhiteSpaceNoUnderscore() {
      assertMatchesSame("\\p{IsWhiteSpace}", " ");
    }

    @Test
    @DisplayName("complex Unicode range with surrogate pairs")
    void complexSurrogateRange() {
      // Regression for issue #127.
      assertCompiles("([\\u0020-\\uD7FF\\uE000-\\uFFFD\\uD800\\uDC00-\\uDBFF\\uDFFF\\t]*)$");
    }
  }

  // ===========================================================================
  // 19. Miscellaneous Patterns
  // ===========================================================================

  @Nested
  @DisplayName("Miscellaneous patterns")
  class MiscPatterns {

    @Test
    @DisplayName("empty pattern")
    void emptyPattern() {
      assertMatchesSame("", "abc");
    }

    @Test
    @DisplayName("empty alternation branch")
    void emptyAlternationBranch() {
      assertMatchesSame("a|", "b");
    }

    @Test
    @DisplayName("nested groups")
    void nestedGroups() {
      assertMatchesSame("((a)(b(c)))", "abc");
    }

    @Test
    @DisplayName("complex real-world pattern: email-like")
    void emailLike() {
      assertMatchesSame("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", "test@example.com");
    }

    @Test
    @DisplayName("complex real-world pattern: IPv4")
    void ipv4() {
      assertMatchesSame(
          "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "192.168.1.1");
    }

    @Test
    @DisplayName("large character class with union and intersection")
    void largeCharClassOps() {
      assertMatchesSame("[a-z[A-Z]&&[^aeiouAEIOU]]", "b"); // consonants only
    }
  }
}
