// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("ShiftDfa is an internal SafeRE engine")
class ShiftDfaTest {

  private static ShiftDfa compile(String regex, int flags) {
    Regexp ast = Parser.parse(regex, flags);
    Prog prog = Compiler.compile(ast);
    return ShiftDfa.compile(prog);
  }

  private static ShiftDfa compile(String regex) {
    return compile(regex, ParseFlags.LIKE_PERL);
  }

  @Test
  @DisplayName("simple literals and short tokens")
  void simpleLiteralsAndShortTokens() {
    ShiftDfa dfa = compile("abc");
    assertThat(dfa).isNotNull();
    assertThat(dfa.numStates()).isEqualTo(4);
    assertThat(dfa.matches("abc", 0, 3)).isTrue();
    assertThat(dfa.matches("ab", 0, 2)).isFalse();
    assertThat(dfa.matches("abcd", 0, 4)).isFalse();
    assertThat(dfa.matches("axc", 0, 3)).isFalse();

    byte[] bytes = "abc".getBytes(UTF_8);
    Utf8InputScanner scanner = new Utf8InputScanner(bytes, 0, bytes.length);
    assertThat(dfa.matches(scanner, 0, 3)).isTrue();
  }

  @Test
  @DisplayName("json boolean and null tokens")
  void jsonBooleanAndNullTokens() {
    ShiftDfa boolDfa = compile("true|false");
    assertThat(boolDfa).isNotNull();
    assertThat(boolDfa.numStates()).isLessThanOrEqualTo(10);
    assertThat(boolDfa.matches("true", 0, 4)).isTrue();
    assertThat(boolDfa.matches("false", 0, 5)).isTrue();
    assertThat(boolDfa.matches("tru", 0, 3)).isFalse();
    assertThat(boolDfa.matches("truee", 0, 5)).isFalse();
    assertThat(boolDfa.matches("null", 0, 4)).isFalse();

    ShiftDfa nullDfa = compile("null");
    assertThat(nullDfa).isNotNull();
    assertThat(nullDfa.numStates()).isEqualTo(5);
    assertThat(nullDfa.matches("null", 0, 4)).isTrue();
    assertThat(nullDfa.matches("nul", 0, 3)).isFalse();
  }

  @Test
  @DisplayName("UTF-8 matching honors the scanner backing-array offset")
  void utf8MatchingHonorsScannerOffset() {
    ShiftDfa dfa = compile("true|false");
    byte[] storage = "xxxxfalseyyyy".getBytes(UTF_8);
    Utf8InputScanner scanner = new Utf8InputScanner(storage, 4, 5);

    assertThat(dfa.matches(scanner, 0, scanner.length())).isTrue();
  }

  @Test
  @DisplayName("boolean-token benchmark pattern selects ShiftDfa")
  void booleanTokenBenchmarkPatternSelectsShiftDfa() {
    Pattern pattern = Pattern.compile("(?:true|false) ?");

    assertThat(pattern.preparedMatchRunner(false))
        .isInstanceOf(Matcher.ShiftDfaPreparedRunner.class);
  }

  @Test
  @DisplayName("quantified digits and repetition ranges")
  void quantifiedDigitsAndRepetitions() {
    ShiftDfa dfa = compile("[0-9]{1,3}");
    assertThat(dfa).isNotNull();
    assertThat(dfa.numStates()).isLessThanOrEqualTo(10);

    assertThat(dfa.matches("1", 0, 1)).isTrue();
    assertThat(dfa.matches("12", 0, 2)).isTrue();
    assertThat(dfa.matches("123", 0, 3)).isTrue();
    assertThat(dfa.matches("1234", 0, 4)).isFalse();
    assertThat(dfa.matches("", 0, 0)).isFalse();
    assertThat(dfa.matches("a", 0, 1)).isFalse();
  }

  @Test
  @DisplayName("c-style identifiers")
  void identifiers() {
    ShiftDfa dfa = compile("[a-zA-Z_][a-zA-Z0-9_]*");
    assertThat(dfa).isNotNull();
    assertThat(dfa.numStates()).isLessThanOrEqualTo(10);

    assertThat(dfa.matches("foo", 0, 3)).isTrue();
    assertThat(dfa.matches("_var123", 0, 7)).isTrue();
    assertThat(dfa.matches("123var", 0, 6)).isFalse();
    assertThat(dfa.matches("", 0, 0)).isFalse();
  }

  @Test
  @DisplayName("year-month date pattern")
  void yearMonthDatePattern() {
    ShiftDfa dfa = compile("[0-9]{4}-[0-9]{2}");
    assertThat(dfa).isNotNull();
    assertThat(dfa.numStates()).isLessThanOrEqualTo(10);

    assertThat(dfa.matches("2026-08", 0, 7)).isTrue();
    assertThat(dfa.matches("1999-12", 0, 7)).isTrue();
    assertThat(dfa.matches("2026-8", 0, 6)).isFalse();
    assertThat(dfa.matches("2026/08", 0, 7)).isFalse();
  }

  @Test
  @DisplayName("self-loop vector acceleration via StateAccelerator")
  void stateAcceleratorSelfLoop() {
    ShiftDfa dfa = compile("[\\x00-\\x21\\x23-\\x7F]*\"");
    assertThat(dfa).isNotNull();
    assertThat(dfa.accelerators()).isNotNull();

    String longInput = "a".repeat(1000) + "\"";
    assertThat(dfa.matches(longInput, 0, longInput.length())).isTrue();

    String longNonMatching = "a".repeat(1000) + "x";
    assertThat(dfa.matches(longNonMatching, 0, longNonMatching.length())).isFalse();
  }

  @Test
  @DisplayName("non-ASCII boundaries are specific to ASCII-only acceleration")
  void nonAsciiBoundariesAreSpecificToAsciiOnlyAcceleration() {
    StateAccelerator accelerator = new StateAccelerator.SingleAsciiEscape('"');
    StringInputScanner scanner = new StringInputScanner("aé\"");

    assertThat(StateAccelerator.findNextEscape(accelerator, scanner, 0, scanner.length()))
        .isEqualTo(2);
    assertThat(
            StateAccelerator.findNextAsciiOrNonAsciiEscape(
                accelerator, scanner, 0, scanner.length()))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("state acceleration stops at non-ASCII dead transitions")
  void stateAccelerationStopsAtNonAsciiDeadTransitions() {
    Pattern pattern = Pattern.compile("[\\x00-\\x21\\x23-\\x7F]*\"");
    for (String nonAscii : List.of("é", "Ω", "😀")) {
      for (int prefixLength : new int[] {0, 15, 16, 20, 64}) {
        String input = "a".repeat(prefixLength) + nonAscii + "\"";
        assertThat(pattern.matcher(input).matches())
            .as("String prefix=%s nonAscii=%s", prefixLength, nonAscii)
            .isFalse();
        assertThat(pattern.matcher(Utf8Input.trusted(input.getBytes(UTF_8))).matches())
            .as("UTF-8 prefix=%s nonAscii=%s", prefixLength, nonAscii)
            .isFalse();
      }
    }
  }

  @Test
  @DisplayName("rejects excessive state count (> 10 states)")
  void rejectsExcessiveStates() {
    // 15 linear states
    String longPattern = "a".repeat(15);
    ShiftDfa dfa = compile(longPattern);
    assertThat(dfa).isNull();
  }

  @Test
  void asciiPartitionsPreserveEveryShortInput() {
    // Exercise overlapping classes, both bitmap words, epsilon cycles, and saved DFA frontiers.
    for (String regex :
        List.of(
            "[\\x00-\\x7f]{0,2}",
            "[\\x00-\\x3f]*[\\x40-\\x7f]?",
            "[\\x00\\x3f\\x40\\x7f]+",
            "(?:[a-f]|[d-j])*[h-m]?",
            "(?:a?|b?)*c?",
            "(?:ab|ba|a)*",
            "(?:[A-Z_][a-z0-9]*|[a-z]+)",
            "[a-c]*?[b-d]?")) {
      ShiftDfa dfa = compile(regex);
      assertThat(dfa).as(regex).isNotNull();
      java.util.regex.Pattern expected = java.util.regex.Pattern.compile(regex);
      assertSameMatch(dfa, expected, "");
      for (int first = 0; first < 128; first++) {
        assertSameMatch(dfa, expected, Character.toString(first));
        for (int second = 0; second < 128; second++) {
          assertSameMatch(dfa, expected, "" + (char) first + (char) second);
        }
      }
      for (String input : List.of("abcabc", "bababa", "Az09", "\u0080", "é", "😀")) {
        assertSameMatch(dfa, expected, input);
      }
    }
  }

  private static void assertSameMatch(
      ShiftDfa dfa, java.util.regex.Pattern expected, String input) {
    boolean matched = expected.matcher(input).matches();
    assertThat(dfa.matches(input, 0, input.length())).as("String %s", expected).isEqualTo(matched);
    byte[] bytes = input.getBytes(UTF_8);
    Utf8InputScanner scanner = new Utf8InputScanner(bytes, 0, bytes.length);
    assertThat(dfa.matches(scanner, 0, bytes.length)).as("UTF-8 %s", expected).isEqualTo(matched);
  }

  @Test
  void stateBudgetIncludesExactlyTenStates() {
    ShiftDfa dfa = compile("a".repeat(9));
    assertThat(dfa).isNotNull();
    assertThat(dfa.numStates()).isEqualTo(ShiftDfa.MAX_STATES);
    assertThat(dfa.matches("a".repeat(9), 0, 9)).isTrue();
    assertThat(compile("a".repeat(10))).isNull();
  }

  @Test
  void caseSensitiveLiteralSupersedesShiftDfaOnlyWhenEnabled() {
    Pattern literal = Pattern.compile("hello");
    assertThat(literal.matchDescriptor().shiftDfa()).isNull();
    assertThat(literal.preparedMatchRunner(false))
        .isInstanceOf(Matcher.LiteralPreparedRunner.class);
    assertThat(literal.preparedMatchRunner(true)).isInstanceOf(Matcher.LiteralPreparedRunner.class);

    Pattern forcedShift =
        Pattern.compile("hello", 0, EnginePathOptions.builder().literalFastPaths(false).build());
    assertThat(forcedShift.matchDescriptor().shiftDfa()).isNotNull();
    assertThat(forcedShift.preparedMatchRunner(false))
        .isInstanceOf(Matcher.ShiftDfaPreparedRunner.class);
  }

  @Test
  void foldedLiteralRetainsUtf8Fallback() {
    Pattern folded = Pattern.compile("hello", Pattern.CASE_INSENSITIVE);
    assertThat(folded.matchDescriptor().shiftDfa()).isNotNull();
    assertThat(folded.matcher(Utf8Input.trusted("HELLO".getBytes(UTF_8))).matches()).isTrue();
  }

  @Test
  void disabledShiftDfaIsNotConstructed() {
    Pattern disabled =
        Pattern.compile("(?:true|false) ?", 0, EnginePathOptions.builder().shiftDfa(false).build());
    assertThat(disabled.matchDescriptor().shiftDfa()).isNull();
  }

  @Test
  void unsupportedInstructionsStillRejectConstruction() {
    for (String regex : List.of("a\\b", "a\\p{L}", "\\X", "ab$", "(ab)")) {
      assertThat(compile(regex)).as(regex).isNull();
    }
  }

  @Test
  @DisplayName("full pattern matcher integration")
  void patternMatcherIntegration() {
    Pattern pattern = Pattern.compile("true|false");
    Matcher matcher = pattern.matcher("true");
    assertThat(matcher.matches()).isTrue();

    Matcher matcherFalse = pattern.matcher("false");
    assertThat(matcherFalse.matches()).isTrue();

    Matcher matcherOther = pattern.matcher("other");
    assertThat(matcherOther.matches()).isFalse();
  }
}
