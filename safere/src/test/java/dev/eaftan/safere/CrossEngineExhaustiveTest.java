// Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.

package dev.eaftan.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Exhaustive cross-engine tests comparing SafeRE against JDK regex.
 *
 * <p>These tests systematically generate all possible regular expressions within given parameters,
 * generate all possible strings over a given alphabet, and verify that SafeRE and JDK agree on
 * every (regexp, string) pair. This is the mechanical approach to finding bugs — enumerate
 * combinations and check agreement.
 *
 * <p>Each test configuration targets a specific gap identified in issue #28: feature combinations,
 * engine-specific paths (short vs long text), flag interactions, and real-world pattern
 * constructions.
 *
 * <p>Inspired by RE2's exhaustive_test infrastructure.
 */
@DisplayName("Cross-Engine Exhaustive Tests (SafeRE vs JDK)")
class CrossEngineExhaustiveTest {

  // ===== 1. Flag + Anchor combinations (bugs #1, #8, #9 from issue #28) =====

  @Nested
  @DisplayName("MULTILINE + anchors")
  class MultilineAnchors {

    @Test
    @DisplayName("MULTILINE ^/$ with \\n in text, short + long")
    void multilineAnchorsNewline() {
      int tests =
          ExhaustiveUtils.run(
              new ExhaustiveUtils.Config(
                  /* maxAtoms= */ 2,
                  /* maxOps= */ 2,
                  /* atoms= */ List.of("a", "b", "(?:^)", "(?:$)", "."),
                  /* ops= */ ExhaustiveUtils.EGREP_OPS,
                  /* maxStrLen= */ 4,
                  /* strAlphabet= */ List.of("a", "b", "\n"),
                  /* wrapper= */ "",
                  /* flags= */ Pattern.MULTILINE,
                  /* testLongText= */ true));
      System.err.printf("multilineAnchorsNewline: %,d tests%n", tests);
    }

    @Test
    @DisplayName("MULTILINE ^/$ with multiple \\n in text, short + long")
    void multilineAnchorsCrLf() {
      // Test with multiple newlines to exercise DFA boundary handling
      int tests =
          ExhaustiveUtils.run(
              new ExhaustiveUtils.Config(
                  /* maxAtoms= */ 2,
                  /* maxOps= */ 1,
                  /* atoms= */ List.of("a", "(?:^)", "(?:$)", "."),
                  /* ops= */ List.of("%s%s", "%s*", "%s+"),
                  /* maxStrLen= */ 4,
                  /* strAlphabet= */ List.of("a", "b", "\n"),
                  /* wrapper= */ "",
                  /* flags= */ Pattern.MULTILINE,
                  /* testLongText= */ true));
      System.err.printf("multilineAnchorsCrLf: %,d tests%n", tests);
    }

    @Test
    @DisplayName("MULTILINE $ with captures on long text (Spring Boot pattern)")
    void multilineDollarCapturesLongText() {
      // Directly test patterns similar to the Spring Boot security password pattern
      List<ExhaustiveUtils.TestResult> failures = new ArrayList<>();
      int tests = 0;

      String[] patterns = {
        "^(.+)$",
        "^(a*)$",
        "^(.*)$",
        "^(a+)$",
        "^([ab]+)$",
        "^a(.*)b$",
        "^(.+?)$",
        "^(.*?)$",
        "(^.+$)",
        "^(.*)\\n(.*)$",
      };

      for (String pat : patterns) {
        String[] texts = {
          "hello",
          "a\nb",
          "abc\ndef\nghi",
          "a".repeat(200) + "\n" + "b".repeat(200),
          "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10",
          "#".repeat(260) + "\n" + "target",
          "prefix\n" + "x".repeat(300) + "\nsuffix",
        };
        for (String text : texts) {
          tests +=
              ExhaustiveUtils.testPair(pat, text, Pattern.MULTILINE, failures);
        }
      }

      System.err.printf("multilineDollarCapturesLongText: %,d tests%n", tests);
      if (!failures.isEmpty()) {
        fail(failures.size() + " failures:\n" + failures.subList(0, Math.min(20, failures.size())));
      }
    }
  }

  // ===== 2. CASE_INSENSITIVE + DFA path (bug #3 from issue #28) =====

  @Nested
  @DisplayName("CASE_INSENSITIVE + DFA")
  class CaseInsensitiveDfa {

    @Test
    @DisplayName("Case-insensitive with letters near equivalence class boundaries")
    void caseInsensitiveBoundaries() {
      // Characters near each other in Unicode that should/shouldn't match under /i
      int tests =
          ExhaustiveUtils.run(
              new ExhaustiveUtils.Config(
                  /* maxAtoms= */ 2,
                  /* maxOps= */ 2,
                  /* atoms= */ List.of("a", "b", "c", "[a-c]", "[b-d]", "."),
                  /* ops= */ ExhaustiveUtils.EGREP_OPS,
                  /* maxStrLen= */ 4,
                  /* strAlphabet= */ List.of("a", "A", "b", "B", "c", "C", "d", "D"),
                  /* wrapper= */ "",
                  /* flags= */ Pattern.CASE_INSENSITIVE,
                  /* testLongText= */ true));
      System.err.printf("caseInsensitiveBoundaries: %,d tests%n", tests);
    }

    @Test
    @DisplayName("Case-insensitive + MULTILINE combined")
    void caseInsensitiveMultiline() {
      int tests =
          ExhaustiveUtils.run(
              new ExhaustiveUtils.Config(
                  /* maxAtoms= */ 2,
                  /* maxOps= */ 1,
                  /* atoms= */ List.of("a", "b", "(?:^)", "(?:$)", "[a-c]"),
                  /* ops= */ List.of("%s%s", "%s*", "%s+", "%s?"),
                  /* maxStrLen= */ 3,
                  /* strAlphabet= */ List.of("a", "A", "b", "B", "\n"),
                  /* wrapper= */ "",
                  /* flags= */ Pattern.CASE_INSENSITIVE | Pattern.MULTILINE,
                  /* testLongText= */ true));
      System.err.printf("caseInsensitiveMultiline: %,d tests%n", tests);
    }

    @Test
    @DisplayName("Case-insensitive character classes on long text")
    void caseInsensitiveCharClassLong() {
      // This specifically targets the Lucene bug: CASE_INSENSITIVE with DFA equivalence classes
      List<ExhaustiveUtils.TestResult> failures = new ArrayList<>();
      int tests = 0;

      String[] patterns = {
        "birds",
        "bird",
        "[b][i][r][d]",
        "b[aeiou]rd",
        "[a-z]+",
        "b.*d",
        "^birds$",
        "the bird",
      };

      // Test with various texts that exercise DFA equivalence class boundaries
      // Key: 'B' and 'C' may share equivalence class, but 'B' matches [b]/i while 'C' doesn't
      String[] texts = {
        "BIRDS",
        "birds",
        "Birds",
        "CARDS", // C in same range as B but shouldn't match 'b'
        "the BIRDS flew",
        "A".repeat(260) + "birds",
        "C".repeat(260) + "birds", // C characters may poison DFA cache
        "ABCDEFG".repeat(40) + "birds",
        "birds" + "X".repeat(260),
      };

      for (String pat : patterns) {
        for (String text : texts) {
          tests +=
              ExhaustiveUtils.testPair(
                  pat, text, Pattern.CASE_INSENSITIVE, failures);
        }
      }

      System.err.printf("caseInsensitiveCharClassLong: %,d tests%n", tests);
      if (!failures.isEmpty()) {
        fail(failures.size() + " failures:\n" + failures.subList(0, Math.min(20, failures.size())));
      }
    }
  }

  // ===== 3. DOTALL flag combinations =====

  @Nested
  @DisplayName("DOTALL combinations")
  class DotallCombinations {

    @Test
    @DisplayName("DOTALL: dot matches \\n")
    void dotallDotMatchesNewline() {
      int tests =
          ExhaustiveUtils.run(
              new ExhaustiveUtils.Config(
                  /* maxAtoms= */ 2,
                  /* maxOps= */ 2,
                  /* atoms= */ List.of("a", ".", "[^a]"),
                  /* ops= */ List.of("%s%s", "%s*", "%s+", "%s?"),
                  /* maxStrLen= */ 4,
                  /* strAlphabet= */ List.of("a", "b", "\n"),
                  /* wrapper= */ "",
                  /* flags= */ Pattern.DOTALL,
                  /* testLongText= */ true));
      System.err.printf("dotallDotMatchesNewline: %,d tests%n", tests);
    }

    @Test
    @DisplayName("DOTALL + MULTILINE combined")
    void dotallMultiline() {
      int tests =
          ExhaustiveUtils.run(
              new ExhaustiveUtils.Config(
                  /* maxAtoms= */ 2,
                  /* maxOps= */ 1,
                  /* atoms= */ List.of("a", ".", "(?:^)", "(?:$)"),
                  /* ops= */ List.of("%s%s", "%s*", "%s+"),
                  /* maxStrLen= */ 3,
                  /* strAlphabet= */ List.of("a", "\n"),
                  /* wrapper= */ "",
                  /* flags= */ Pattern.DOTALL | Pattern.MULTILINE,
                  /* testLongText= */ true));
      System.err.printf("dotallMultiline: %,d tests%n", tests);
    }

    @Test
    @DisplayName("All three flags: DOTALL + MULTILINE + CASE_INSENSITIVE")
    void allThreeFlags() {
      int tests =
          ExhaustiveUtils.run(
              new ExhaustiveUtils.Config(
                  /* maxAtoms= */ 2,
                  /* maxOps= */ 1,
                  /* atoms= */ List.of("a", ".", "(?:^)", "(?:$)"),
                  /* ops= */ List.of("%s%s", "%s*", "%s+"),
                  /* maxStrLen= */ 3,
                  /* strAlphabet= */ List.of("a", "A", "\n"),
                  /* wrapper= */ "",
                  /* flags= */ Pattern.DOTALL | Pattern.MULTILINE | Pattern.CASE_INSENSITIVE,
                  /* testLongText= */ true));
      System.err.printf("allThreeFlags: %,d tests%n", tests);
    }
  }

  // ===== 4. Word boundaries on long text =====

  @Nested
  @DisplayName("Word boundaries")
  class WordBoundaries {

    @Test
    @DisplayName("\\b and \\B with various text, short + long")
    void wordBoundaries() {
      int tests =
          ExhaustiveUtils.run(
              new ExhaustiveUtils.Config(
                  /* maxAtoms= */ 2,
                  /* maxOps= */ 2,
                  /* atoms= */ List.of("a", "(?:\\b)", "(?:\\B)", "\\w", "\\W"),
                  /* ops= */ List.of("%s%s", "%s|%s", "%s*", "%s+"),
                  /* maxStrLen= */ 4,
                  /* strAlphabet= */ List.of("a", "b", " ", "_"),
                  /* wrapper= */ "",
                  /* flags= */ 0,
                  /* testLongText= */ true));
      System.err.printf("wordBoundaries: %,d tests%n", tests);
    }
  }

  // ===== 5. Anchor stripping + find() (bug #5 from issue #28) =====

  @Nested
  @DisplayName("Anchor stripping")
  class AnchorStripping {

    @Test
    @DisplayName("Anchored patterns: find() from position 0 and replaceAll")
    void anchoredFindAndReplace() {
      List<ExhaustiveUtils.TestResult> failures = new ArrayList<>();
      int tests = 0;

      // Patterns where the compiler strips ^/\A into anchorStart
      String[] patterns = {
        "^a",
        "^abc",
        "^[a-c]+",
        "^a*",
        "^a+b",
        "^(a)(b)",
        "^.*",
        "^.+",
        "\\Aa",
        "\\Aabc",
        "\\A[a-c]+",
        "\\A.*",
      };

      String[] texts = {
        "",
        "a",
        "abc",
        "xabc",
        "abcabc",
        "abc\nabc",
        "x".repeat(300) + "abc",
        "abc" + "x".repeat(300),
      };

      for (String pat : patterns) {
        for (String text : texts) {
          // Test without MULTILINE (anchor stripping applies)
          tests += ExhaustiveUtils.testPair(pat, text, 0, failures);

          // Also compare replaceAll
          tests++;
          try {
            java.util.regex.Pattern jdkPat = java.util.regex.Pattern.compile(pat);
            Pattern saferePat = Pattern.compile(pat);

            String jdkResult = jdkPat.matcher(text).replaceAll("X");
            String safereResult = saferePat.matcher(text).replaceAll("X");

            if (!jdkResult.equals(safereResult)) {
              failures.add(
                  new ExhaustiveUtils.TestResult(
                      pat, text, 0, "replaceAll", safereResult, jdkResult));
            }
          } catch (PatternSyntaxException e) {
            // skip if either rejects
          }
        }
      }

      System.err.printf("anchoredFindAndReplace: %,d tests%n", tests);
      if (!failures.isEmpty()) {
        fail(failures.size() + " failures:\n" + failures.subList(0, Math.min(20, failures.size())));
      }
    }

    @Test
    @DisplayName("End-anchored patterns: $ and \\z")
    void endAnchoredPatterns() {
      List<ExhaustiveUtils.TestResult> failures = new ArrayList<>();
      int tests = 0;

      String[] patterns = {
        "a$",
        "abc$",
        "[a-c]+$",
        "a*$",
        "(a)(b)$",
        ".*$",
        ".+$",
        "a\\z",
        "abc\\z",
        "[a-c]+\\z",
      };

      String[] texts = {
        "",
        "a",
        "abc",
        "xabc",
        "abcabc",
        "abc\nabc",
        "x".repeat(300) + "abc",
        "abc" + "x".repeat(300),
      };

      for (String pat : patterns) {
        for (String text : texts) {
          tests += ExhaustiveUtils.testPair(pat, text, 0, failures);
          tests += ExhaustiveUtils.testPair(pat, text, Pattern.MULTILINE, failures);
        }
      }

      System.err.printf("endAnchoredPatterns: %,d tests%n", tests);
      if (!failures.isEmpty()) {
        fail(failures.size() + " failures:\n" + failures.subList(0, Math.min(20, failures.size())));
      }
    }
  }

  // ===== 6. Dynamic pattern construction (bugs #2, #7 from issue #28) =====

  @Nested
  @DisplayName("Dynamic patterns")
  class DynamicPatterns {

    @Test
    @DisplayName("Pattern.quote() inside character classes")
    void patternQuoteInCharClass() {
      List<ExhaustiveUtils.TestResult> failures = new ArrayList<>();
      int tests = 0;

      // Characters that Pattern.quote() wraps in \Q..\E
      String[] specialChars = {"*", ".", "+", "?", "[", "]", "{", "}", "(", ")", "^", "$", "|",
          "\\"};

      for (String ch : specialChars) {
        String quoted = Pattern.quote(ch);
        String pat = "[" + quoted + "]+";

        String[] texts = {ch, ch + ch, "a" + ch + "b", "abc", ch.repeat(5),
            "#".repeat(260) + ch};

        for (String text : texts) {
          tests += ExhaustiveUtils.testPair(pat, text, 0, failures);
        }
      }

      // Compound patterns: multiple quoted chars in a class
      String[] compoundPatterns = {
        "[" + Pattern.quote("*") + Pattern.quote(".") + "]+",
        "[" + Pattern.quote("\\") + Pattern.quote("/") + "]+",
        "[a-z" + Pattern.quote("*") + "]+",
        "[" + Pattern.quote("[") + Pattern.quote("]") + "]+",
      };

      String[] compoundTexts = {"*.", "\\/", "abc*", "[]", "a*b.c",
          "#".repeat(260) + "*."};

      for (String pat : compoundPatterns) {
        for (String text : compoundTexts) {
          tests += ExhaustiveUtils.testPair(pat, text, 0, failures);
        }
      }

      System.err.printf("patternQuoteInCharClass: %,d tests%n", tests);
      if (!failures.isEmpty()) {
        fail(failures.size() + " failures:\n" + failures.subList(0, Math.min(20, failures.size())));
      }
    }

    @Test
    @DisplayName("Pattern.quote() composed into larger patterns")
    void patternQuoteComposed() {
      List<ExhaustiveUtils.TestResult> failures = new ArrayList<>();
      int tests = 0;

      // Patterns built like real code does
      String[][] configs = {
        // { pattern template, key, text }
        {Pattern.quote("key") + "=(.+)", "key=value", "key=value"},
        {Pattern.quote("key") + "=(.+)", "key=", "key="},
        {Pattern.quote("key") + "=(\\d+)", "key=123", "key=123abc"},
        {"(" + Pattern.quote("hello") + ")\\s+(\\w+)", "hello world", "hello world"},
        {Pattern.quote("$") + "\\{(\\w+)\\}", "${var}", "${var}"},
        {Pattern.quote("[") + "(\\d+)" + Pattern.quote("]"), "[42]", "item[42]"},
      };

      for (String[] config : configs) {
        String pat = config[0];
        // Test against all the example texts
        for (int i = 1; i < config.length; i++) {
          tests += ExhaustiveUtils.testPair(pat, config[i], 0, failures);
          // Also long text
          tests +=
              ExhaustiveUtils.testPair(
                  pat, "#".repeat(260) + config[i], 0, failures);
        }
      }

      System.err.printf("patternQuoteComposed: %,d tests%n", tests);
      if (!failures.isEmpty()) {
        fail(failures.size() + " failures:\n" + failures.subList(0, Math.min(20, failures.size())));
      }
    }

    @Test
    @DisplayName("Nested character classes: [[a-z]], [a-z[0-9]], etc.")
    void nestedCharClasses() {
      List<ExhaustiveUtils.TestResult> failures = new ArrayList<>();
      int tests = 0;

      String[] patterns = {
        "[[a-f]]",
        "[[a-f][0-9]]",
        "[a-z[A-Z]]",
        "[[a-z]]",
        "[a[b[c]]]",
        "[[a-f]]+",
        "[[A-Fa-f0-9]]+",
        "[[a-z][0-9]]+",
        "[^[aeiou]]",
        // Note: [a-z&&[^aeiou]] (intersection) is not tested — SafeRE doesn't support &&
      };

      String[] texts = {
        "a", "f", "g", "0", "5", "Z", "abc123", "HELLO", "aeiou", "bcdfg",
        "x".repeat(300), "#".repeat(260) + "abc123",
      };

      for (String pat : patterns) {
        for (String text : texts) {
          // Skip if both reject the pattern (e.g., && intersection)
          boolean jdkRejects = false;
          boolean safereRejects = false;
          try {
            java.util.regex.Pattern.compile(pat);
          } catch (PatternSyntaxException e) {
            jdkRejects = true;
          }
          try {
            Pattern.compile(pat);
          } catch (PatternSyntaxException e) {
            safereRejects = true;
          }
          if (jdkRejects || safereRejects) {
            continue;
          }
          tests += ExhaustiveUtils.testPair(pat, text, 0, failures);
          tests +=
              ExhaustiveUtils.testPair(pat, text, Pattern.CASE_INSENSITIVE, failures);
        }
      }

      System.err.printf("nestedCharClasses: %,d tests%n", tests);
      if (!failures.isEmpty()) {
        fail(failures.size() + " failures:\n" + failures.subList(0, Math.min(20, failures.size())));
      }
    }
  }

  // ===== 7. Engine path: short vs long text =====

  @Nested
  @DisplayName("Engine path: short vs long")
  class EnginePathTests {

    @Test
    @DisplayName("All assertions on short vs long text")
    void assertionsShortVsLong() {
      // Same patterns tested at ≤256 and >256 char text to force different engines
      int tests =
          ExhaustiveUtils.run(
              new ExhaustiveUtils.Config(
                  /* maxAtoms= */ 2,
                  /* maxOps= */ 2,
                  /* atoms= */ List.of("a", ".", "(?:^)", "(?:$)", "(?:\\b)", "\\d", "\\w"),
                  /* ops= */ ExhaustiveUtils.EGREP_OPS,
                  /* maxStrLen= */ 3,
                  /* strAlphabet= */ List.of("a", "1", " "),
                  /* wrapper= */ "",
                  /* flags= */ 0,
                  /* testLongText= */ true));
      System.err.printf("assertionsShortVsLong: %,d tests%n", tests);
    }

    @Test
    @DisplayName("Captures on long text (DFA deferred resolution)")
    void capturesLongText() {
      List<ExhaustiveUtils.TestResult> failures = new ArrayList<>();
      int tests = 0;

      String[] patterns = {
        "(a+)",
        "(a)(b)",
        "(a+)(b+)",
        "(a|b)+",
        "(.)(.)(.)",
        "(a+)b(c+)",
        "(?:(a)|(b))+",
        "(\\d+)\\.(\\d+)",
        "([a-z]+)([0-9]+)",
      };

      String[] shortTexts = {"ab", "aab", "abc", "aabcc", "1.2", "abc123", "abab"};

      for (String pat : patterns) {
        for (String shortText : shortTexts) {
          // Short text (OnePass/BitState path)
          tests += ExhaustiveUtils.testPair(pat, shortText, 0, failures);

          // Long text (DFA path) — same content, padded
          String longText = "x".repeat(260) + shortText;
          tests += ExhaustiveUtils.testPair(pat, longText, 0, failures);

          // Long text with content in the middle
          String midText = "x".repeat(130) + shortText + "x".repeat(130);
          tests += ExhaustiveUtils.testPair(pat, midText, 0, failures);
        }
      }

      System.err.printf("capturesLongText: %,d tests%n", tests);
      if (!failures.isEmpty()) {
        fail(failures.size() + " failures:\n" + failures.subList(0, Math.min(20, failures.size())));
      }
    }

    @Test
    @DisplayName("Repetition operators on long text")
    void repetitionLongText() {
      int tests =
          ExhaustiveUtils.run(
              new ExhaustiveUtils.Config(
                  /* maxAtoms= */ 3,
                  /* maxOps= */ 2,
                  /* atoms= */ List.of("a", "b", "(a)"),
                  /* ops= */ List.of(
                      "%s{0}", "%s{0,}", "%s{1}", "%s{1,}", "%s{0,1}",
                      "%s{0,2}", "%s{1,2}", "%s{2}", "%s{2,}",
                      "%s*", "%s+", "%s?", "%s*?", "%s+?", "%s??"),
                  /* maxStrLen= */ 4,
                  /* strAlphabet= */ List.of("a", "b"),
                  /* wrapper= */ "(?:%s)",
                  /* flags= */ 0,
                  /* testLongText= */ true));
      System.err.printf("repetitionLongText: %,d tests%n", tests);
    }
  }

  // ===== 8. Matcher reuse and lifecycle (bugs #4, #6 from issue #28) =====

  @Nested
  @DisplayName("Matcher lifecycle")
  class MatcherLifecycle {

    @Test
    @DisplayName("Mutable CharSequence with reset()")
    void mutableCharSequenceReset() {
      String[] patterns = {"a+", "\\d+", "[a-z]+", "a.*b", "^a+$", "(a)(b)"};

      for (String pat : patterns) {
        Pattern saferePat = Pattern.compile(pat);
        java.util.regex.Pattern jdkPat = java.util.regex.Pattern.compile(pat);

        StringBuilder sb = new StringBuilder();
        Matcher safereM = saferePat.matcher(sb);
        java.util.regex.Matcher jdkM = jdkPat.matcher(sb);

        String[] inputs = {"aaa", "123", "hello", "ab", "aaaa", "bbb"};

        for (String input : inputs) {
          sb.setLength(0);
          sb.append(input);

          safereM.reset();
          jdkM.reset();

          boolean safereResult = safereM.find();
          boolean jdkResult = jdkM.find();

          assertThat(safereResult)
              .as("find() pat=%s text=%s", pat, input)
              .isEqualTo(jdkResult);

          if (safereResult && jdkResult) {
            assertThat(safereM.start())
                .as("start() pat=%s text=%s", pat, input)
                .isEqualTo(jdkM.start());
            assertThat(safereM.end())
                .as("end() pat=%s text=%s", pat, input)
                .isEqualTo(jdkM.end());
          }
        }
      }
    }

    @Test
    @DisplayName("Sequential reset(CharSequence) + matches() — no state leakage")
    void sequentialResetMatches() {
      String[] patterns = {"a+", "[a-z]+b", "\\d{3}", "^abc$", "(a|b)+"};

      for (String pat : patterns) {
        Pattern saferePat = Pattern.compile(pat);
        java.util.regex.Pattern jdkPat = java.util.regex.Pattern.compile(pat);

        Matcher safereM = saferePat.matcher("");
        java.util.regex.Matcher jdkM = jdkPat.matcher("");

        // Mix of matching and non-matching inputs
        String[] inputs = {"aaa", "xyz", "aab", "123", "456", "abc", "def", "", "a", "bb"};

        for (String input : inputs) {
          safereM.reset(input);
          jdkM.reset(input);

          assertThat(safereM.matches())
              .as("matches() pat=%s text=%s", pat, input)
              .isEqualTo(jdkM.matches());
        }
      }
    }

    @Test
    @DisplayName("find() loop after reset with different-length inputs")
    void findLoopAfterReset() {
      String[] patterns = {"a", "ab", "a+", "[a-z]"};

      for (String pat : patterns) {
        Pattern saferePat = Pattern.compile(pat);
        java.util.regex.Pattern jdkPat = java.util.regex.Pattern.compile(pat);

        Matcher safereM = saferePat.matcher("");
        java.util.regex.Matcher jdkM = jdkPat.matcher("");

        String[] inputs = {"aaa", "abab", "a", "xaxax", "aaaa".repeat(100)};

        for (String input : inputs) {
          safereM.reset(input);
          jdkM.reset(input);

          List<String> safereMatches = new ArrayList<>();
          List<String> jdkMatches = new ArrayList<>();

          while (safereM.find()) {
            safereMatches.add(String.format("[%d,%d)=%s", safereM.start(), safereM.end(),
                safereM.group()));
          }
          while (jdkM.find()) {
            jdkMatches.add(String.format("[%d,%d)=%s", jdkM.start(), jdkM.end(), jdkM.group()));
          }

          assertThat(safereMatches)
              .as("findAll pat=%s text=%s (len=%d)", pat, input.length() > 20
                  ? input.substring(0, 20) + "..." : input, input.length())
              .isEqualTo(jdkMatches);
        }
      }
    }

    @Test
    @DisplayName("Custom CharSequence without toString() override")
    void customCharSequenceNoToString() {
      // Simulates Ghidra's ByteCharSequence — a CharSequence backed by data
      // that does NOT override toString()
      String[] patterns = {"a+", "abc", "a.c", "[a-z]+", "\\d+", "a+b+"};
      String[] texts = {"abc", "aabcc", "a1c", "hello123", "aaabbb"};

      for (String pat : patterns) {
        Pattern saferePat = Pattern.compile(pat);
        java.util.regex.Pattern jdkPat = java.util.regex.Pattern.compile(pat);

        for (String text : texts) {
          // Create a CharSequence that does NOT override toString()
          CharSequence cs = new CharSequence() {
            @Override
            public int length() {
              return text.length();
            }

            @Override
            public char charAt(int index) {
              return text.charAt(index);
            }

            @Override
            public CharSequence subSequence(int start, int end) {
              return text.subSequence(start, end);
            }
            // Deliberately do NOT override toString()
          };

          Matcher safereM = saferePat.matcher(cs);
          java.util.regex.Matcher jdkM = jdkPat.matcher(cs);

          assertThat(safereM.find())
              .as("find() pat=%s text=%s (custom CS)", pat, text)
              .isEqualTo(jdkM.find());

          if (safereM.find(0) && jdkM.find(0)) {
            assertThat(safereM.group())
                .as("group() pat=%s text=%s", pat, text)
                .isEqualTo(jdkM.group());
          }
        }
      }
    }

    @Test
    @DisplayName("Byte-backed CharSequence (Ghidra-style)")
    void byteBackedCharSequence() {
      String[] patterns = {"[a-z]+", "\\w+", "abc", "a.c", "[\\x00-\\x7f]+"};
      byte[][] byteArrays = {
        {0x61, 0x62, 0x63}, // "abc"
        {0x68, 0x65, 0x6c, 0x6c, 0x6f}, // "hello"
        {0x41, 0x42, 0x43}, // "ABC"
        {0x30, 0x31, 0x32}, // "012"
        {0x61, 0x20, 0x62}, // "a b"
      };

      for (String pat : patterns) {
        Pattern saferePat;
        java.util.regex.Pattern jdkPat;
        try {
          saferePat = Pattern.compile(pat);
          jdkPat = java.util.regex.Pattern.compile(pat);
        } catch (PatternSyntaxException e) {
          continue;
        }

        for (byte[] bytes : byteArrays) {
          CharSequence cs = new CharSequence() {
            @Override
            public int length() {
              return bytes.length;
            }

            @Override
            public char charAt(int index) {
              return (char) (bytes[index] & 0xFF);
            }

            @Override
            public CharSequence subSequence(int start, int end) {
              char[] chars = new char[end - start];
              for (int i = start; i < end; i++) {
                chars[i - start] = (char) (bytes[i] & 0xFF);
              }
              return new String(chars);
            }
          };

          Matcher safereM = saferePat.matcher(cs);
          java.util.regex.Matcher jdkM = jdkPat.matcher(cs);

          boolean safereFound = safereM.find();
          boolean jdkFound = jdkM.find();

          assertThat(safereFound)
              .as("find() pat=%s bytes=%s", pat, java.util.Arrays.toString(bytes))
              .isEqualTo(jdkFound);

          if (safereFound && jdkFound) {
            assertThat(safereM.start())
                .as("start() pat=%s", pat)
                .isEqualTo(jdkM.start());
            assertThat(safereM.end())
                .as("end() pat=%s", pat)
                .isEqualTo(jdkM.end());
          }
        }
      }
    }

    @Test
    @DisplayName("replaceAll/replaceFirst after reset")
    void replaceAfterReset() {
      String[] patterns = {"a+", "[0-9]+", "(a)(b)", "\\s+"};
      String[] texts = {"aaa bbb", "x1y2z3", "abab", "  hello  world  "};

      for (String pat : patterns) {
        Pattern saferePat = Pattern.compile(pat);
        java.util.regex.Pattern jdkPat = java.util.regex.Pattern.compile(pat);

        Matcher safereM = saferePat.matcher("");
        java.util.regex.Matcher jdkM = jdkPat.matcher("");

        for (String text : texts) {
          safereM.reset(text);
          jdkM.reset(text);

          assertThat(safereM.replaceAll("X"))
              .as("replaceAll pat=%s text=%s", pat, text)
              .isEqualTo(jdkM.replaceAll("X"));

          safereM.reset(text);
          jdkM.reset(text);

          assertThat(safereM.replaceFirst("X"))
              .as("replaceFirst pat=%s text=%s", pat, text)
              .isEqualTo(jdkM.replaceFirst("X"));
        }
      }
    }
  }

  // ===== 9. Line endings (RE2 exhaustive2_test.cc port) =====

  @Nested
  @DisplayName("Line endings exhaustive")
  class LineEndings {

    @Test
    @DisplayName("^/$/.\\A\\z with line endings (port of RE2 exhaustive2_test)")
    void lineEndsExhaustive() {
      int tests =
          ExhaustiveUtils.run(
              new ExhaustiveUtils.Config(
                  /* maxAtoms= */ 2,
                  /* maxOps= */ 2,
                  /* atoms= */ List.of("(?:^)", "(?:$)", ".", "a", "\\n", "(?:\\A)", "(?:\\z)"),
                  /* ops= */ ExhaustiveUtils.EGREP_OPS,
                  /* maxStrLen= */ 3,
                  /* strAlphabet= */ List.of("a", "b", "\n"),
                  /* wrapper= */ "",
                  /* flags= */ 0,
                  /* testLongText= */ true));
      System.err.printf("lineEndsExhaustive: %,d tests%n", tests);
    }

    @Test
    @DisplayName("Mixed line endings: \\n, \\r\\n, \\r")
    void mixedLineEndings() {
      List<ExhaustiveUtils.TestResult> failures = new ArrayList<>();
      int tests = 0;

      String[] patterns = {
        "^a$", "^.*$", "^.+$", "^a+$", "^[a-z]+$",
        "^(a+)$", "(?m)^a$", "(?m)^.*$", "(?m)^.+$",
      };

      // Test \n endings only. \r and \r\n tests are skipped by ExhaustiveUtils because
      // JDK treats \r as a line terminator (dot doesn't match it, $ matches before it)
      // but SafeRE (like RE2) only gives special treatment to \n. This is a known design
      // difference documented in #28.
      String[] texts = {
        "a\nb",
        "a\nb\nc",
        "\na\n",
        "a".repeat(260) + "\nb",
        "\n".repeat(5) + "a" + "\n".repeat(5),
        "a\n" + "b".repeat(260) + "\nc",
      };

      for (String pat : patterns) {
        for (String text : texts) {
          tests += ExhaustiveUtils.testPair(pat, text, 0, failures);
          tests += ExhaustiveUtils.testPair(pat, text, Pattern.MULTILINE, failures);
        }
      }

      System.err.printf("mixedLineEndings: %,d tests%n", tests);
      if (!failures.isEmpty()) {
        fail(failures.size() + " failures:\n" + failures.subList(0, Math.min(20, failures.size())));
      }
    }
  }

  // ===== 10. Character classes exhaustive (RE2 exhaustive3_test.cc port) =====

  @Nested
  @DisplayName("Character classes exhaustive")
  class CharacterClassesExhaustive {

    @Test
    @DisplayName("Character class combinations")
    void charClassCombinations() {
      int tests =
          ExhaustiveUtils.run(
              new ExhaustiveUtils.Config(
                  /* maxAtoms= */ 2,
                  /* maxOps= */ 1,
                  /* atoms= */ List.of(
                      "[a]", "[b]", "[ab]", "[^bc]", "[b-d]", "[^b-d]", "a", "b", "."),
                  /* ops= */ ExhaustiveUtils.EGREP_OPS,
                  /* maxStrLen= */ 4,
                  /* strAlphabet= */ List.of("a", "b", "c"),
                  /* wrapper= */ "",
                  /* flags= */ 0,
                  /* testLongText= */ true));
      System.err.printf("charClassCombinations: %,d tests%n", tests);
    }

    @Test
    @DisplayName("Shorthand character classes (\\d, \\w, \\s)")
    void shorthandCharClasses() {
      int tests =
          ExhaustiveUtils.run(
              new ExhaustiveUtils.Config(
                  /* maxAtoms= */ 2,
                  /* maxOps= */ 1,
                  /* atoms= */ List.of("\\d", "\\D", "\\w", "\\W", "\\s", "\\S", ".", "a"),
                  /* ops= */ List.of("%s%s", "%s*", "%s+"),
                  /* maxStrLen= */ 3,
                  /* strAlphabet= */ List.of("a", "1", " ", "!"),
                  /* wrapper= */ "",
                  /* flags= */ 0,
                  /* testLongText= */ true));
      System.err.printf("shorthandCharClasses: %,d tests%n", tests);
    }
  }

  // ===== 11. Case folding (RE2 exhaustive_test.cc FoldCase port) =====

  @Nested
  @DisplayName("Case folding exhaustive")
  class CaseFolding {

    @Test
    @DisplayName("Case-insensitive with punctuation near A-Z/a-z boundaries")
    void caseFoldWithPunctuation() {
      // RE2 comment: "The punctuation characters surround A-Z and a-z in the ASCII table.
      // This looks for bugs in the bytemap range code in the DFA."
      int tests =
          ExhaustiveUtils.run(
              new ExhaustiveUtils.Config(
                  /* maxAtoms= */ 2,
                  /* maxOps= */ 2,
                  /* atoms= */ List.of("a", "b", "A", "B", "."),
                  /* ops= */ ExhaustiveUtils.EGREP_OPS,
                  /* maxStrLen= */ 3,
                  /* strAlphabet= */ List.of("a", "B", "c", "@", "_", "~"),
                  /* wrapper= */ "(?i:%s)",
                  /* flags= */ 0,
                  /* testLongText= */ true));
      System.err.printf("caseFoldWithPunctuation: %,d tests%n", tests);
    }
  }
}
