// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Compatibility at boundaries within lexical constructs, rather than only between atoms. */
class LexicalCompatibilityTest {
  private static final List<String> INPUTS =
      List.of(
          "",
          "a",
          "aa",
          "aaa",
          "aaaa",
          "A",
          "!",
          "&",
          "%",
          "!a",
          "mz",
          " ",
          "#",
          "+",
          "a+",
          "😀",
          "𠀀",
          "\uD83D",
          "\uD840",
          "\uDE00",
          "\uDC00",
          "\uD83D\uD800\uDE00");

  @Test
  void literalRunsKeepAtomQuantifiersAndGroupBoundaries() {
    List<String> inputs =
        List.of(
            "",
            "a",
            "ab",
            "abc",
            "abcc",
            "abccc",
            "abcabc",
            "abab",
            "ababab",
            "abcd",
            "abC",
            "aBCD",
            "ABCD",
            "ABC",
            "abefef",
            "xabcabccyabc",
            "a😀",
            "a😀😀",
            "a😀a😀",
            "a\uD83D\uD800\uDE00",
            "a\uD83D\uD800\uDE00\uD800\uDE00");
    for (String regex :
        List.of(
            "abc+",
            "abc{0,2}",
            "abc*?",
            "(?:abc)+",
            "(abc)+",
            "(ab)c+",
            "(?:ab)c+",
            "ab|cd+",
            "ab(?:cd|ef)+",
            "ab(?i:c+)d",
            "(?i:a)bcd+",
            "ab(?i)cd+",
            "a\\x42\\u0043+",
            "\\Qabc\\E+",
            "(?x)a #c\n b c+",
            "a😀+",
            "(?:a😀)+",
            "a\\uD83D\\uDE00+",
            "a\\uD83D\\uD800\\uDE00+",
            "abc\\Q\\E+?",
            "(a|ab)c+",
            "(ab){0}c+",
            "^abc+$")) {
      for (int flags :
          List.of(
              0,
              Pattern.COMMENTS,
              Pattern.CASE_INSENSITIVE,
              Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)) {
        assertSame(regex, flags, inputs);
      }
    }
  }

  @Test
  void propertyComponentsCrossTriviaAndQuoteBoundaries() {
    for (String introducer : List.of("\\p", "\\P")) {
      for (String gap : List.of("", "\\Q\\E", "\\Q\\E\\Q\\E", " ", "#c\n", " \\Q\\E#c\n")) {
        for (String payload : List.of("L", "{L}", "{ L}", "{L }", "\\QL\\E", "\\Q{L}\\E")) {
          String escape = introducer + gap + payload;
          for (String expression : List.of(escape, "[" + escape + "]", "(" + escape + ")+")) {
            assertSame(expression, 0);
            assertSame(expression, Pattern.COMMENTS);
            assertSame("(?x)" + expression, 0);
            assertSame("(?x:" + expression + ")", 0);
          }
        }
      }
    }
  }

  @Test
  void controlAndNumericEscapesRespectTheirComponentBoundaries() {
    for (String expression :
        List.of(
            "\\c\\Q\\Ea",
            "\\c\\Q\\E\\Q\\Ea",
            "\\c\\Qa\\E",
            "\\c\t\\Q\\E-z",
            "\\x\\Q41\\E",
            "\\x4\\Q1\\E",
            "\\x 41",
            "\\x4 1",
            "\\u00 41",
            "\\0 77",
            "\\x{ 41}",
            "\\x{4 1}",
            "\\u00\\Q41\\E",
            "\\p\\Q{L}\\E")) {
      for (String wrapped : List.of(expression, "[" + expression + "]")) {
        assertSame(wrapped, 0);
        assertSame(wrapped, Pattern.COMMENTS);
      }
    }
  }

  @Test
  void quantifierModifiersCrossEmptyQuotesWithoutChangingMatchPriority() {
    for (String operand : List.of("a", "(a)", "(a?)", "()")) {
      for (String repeat : List.of("?", "*", "+", "{1}", "{0,2}", "{1,3}")) {
        for (String gap : List.of("\\Q\\E", "\\Q\\E\\Q\\E", " \\Q\\E#c\n")) {
          assertSame(operand + repeat + gap + "?", Pattern.COMMENTS);
          assertSame(operand + repeat + "?" + gap + "?", Pattern.COMMENTS);
          assertSame(operand + repeat + "?" + gap + "+", Pattern.COMMENTS);
          assertSame("(?x:" + operand + repeat + gap + "?)", 0);
        }
      }
    }
  }

  @Test
  void operandFreeCountedRepeatsRemainTransparentToEmptyQuotes() {
    for (String prefix : List.of("", "(?i)", "a+", "|", "(?:")) {
      for (String repeat : List.of("{0}", "{1}", "{01,03}")) {
        for (String modifier : List.of("?", "+")) {
          String regex = prefix + repeat + "\\Q\\E" + modifier;
          if (prefix.equals("(?:")) regex += ")";
          assertSame(regex, 0);
        }
      }
    }
  }

  @Test
  @DisabledForCrosscheck("SafeRE rejects possessive repetition over consuming operands")
  void consumingPossessiveRejectionHasOnePathForEquivalentSpellings() {
    for (String operand : List.of("a", "(a)", "(?:a?)", "\\p{Lower}", "^\\p{Lower}")) {
      for (String repeat : List.of("?", "*", "+", "{1}", "{0,2}", "{1,3}")) {
        for (String gap : List.of("", "\\Q\\E", "\\Q\\E\\Q\\E", " \\Q\\E#c\n")) {
          assertThatThrownBy(() -> Pattern.compile(operand + repeat + gap + "+", Pattern.COMMENTS))
              .isInstanceOf(PatternSyntaxException.class)
              .hasMessageContaining("possessive");
        }
      }
    }
  }

  @Test
  void zeroWidthPossessivesAndQuotedClassRangesKeepTheirSemantics() {
    for (String regex :
        List.of(
            "()*\\Q\\E+",
            "(){2}\\Q\\E+",
            "(){0,2}\\Q\\E+",
            "[\\Qab\\E-z]",
            "[a-\\Qyz\\E]",
            "[\\Q-]&^# \\E]",
            "[a&\\Q\\E&[a-z]]")) {
      assertSame(regex, 0);
      assertSame(regex, Pattern.COMMENTS);
    }
  }

  @Test
  void quotedClassSpansKeepCodePointsAndRangeEndpointsAcrossLengths() {
    List<String> inputs =
        List.of("", "a", "b", "z", "Z", "A", "~", "😀", "𠀀", "#", " ", "&", "-", "]");
    for (int length : List.of(3, 4, 5, 7, 8, 9, 15, 16, 17, 64, 65, 257)) {
      for (String unit : List.of("a", "😀", "# &-]")) {
        String quote = "\\QZ" + unit.repeat(length) + "z\\E";
        for (String regex :
            List.of(
                "[" + quote + "]",
                "[^" + quote + "]",
                "[" + quote + "-~]",
                "[A-" + quote + "]",
                "[[" + quote + "]&&[^z]]",
                "[\\Q\\E" + quote + "\\Qab\\E]")) {
          assertSame(regex, 0, inputs);
          assertSame(regex, Pattern.COMMENTS, inputs);
        }
      }
    }
  }

  @Test
  void rangeEndpointsConsumeOneAmpersandBeforeRecognizingIntersections() {
    for (String lower : List.of("!", "%", "&", "\\Q&\\E")) {
      for (String upperAndTail : List.of("&&", "\\Q&\\E&")) {
        String regex = "[" + lower + "-" + upperAndTail + "]";
        assertSame(regex, 0);
        assertSame(regex, Pattern.COMMENTS);
      }
    }
  }

  @Test
  void rangeEndpointControlsKeepDelimiterRolesAndValidation() {
    List<String> inputs = List.of("!", "-", "A", "]", "a", "!]", "-]", "A]", "]]");
    for (String regex : List.of("[!-]]", "[!-\\Q\\E]]", "[!-\\]]", "[!-\\Q]\\E]")) {
      assertSame(regex, 0, inputs);
      assertSame(regex, Pattern.COMMENTS, inputs);
    }
  }

  @Test
  void leadingClassTriviaKeepsCaretsLiteralWhileEmptyQuotesAreTransparent() {
    for (String regex :
        List.of(
            "[ ^a]", "[#c\n^a]", "[[ ^a]]", "[ ^]", "[\\Q\\E^a]", "[ \\Q\\E^a]", "[a&&[ ^a]]")) {
      assertSame(regex, Pattern.COMMENTS);
    }
  }

  @Test
  void surrogateEscapePairsUseTheSameComponentRulesAsIndividualEscapes() {
    for (String high : List.of("\\uD83D", "\\uD840")) {
      for (String low : List.of("DE00", "DC00", "D800", "0041")) {
        for (String gap : List.of("", " ", "#c\n", "\\Q\\E")) {
          for (String between : List.of("", " ", "\\Q\\E")) {
            String regex = high + between + "\\u" + low.substring(0, 2) + gap + low.substring(2);
            for (String expression : List.of(regex, "[" + regex + "]", regex + "\\uDE00")) {
              assertSame(expression, 0);
              assertSame(expression, Pattern.COMMENTS);
            }
          }
        }
      }
    }
  }

  @Test
  void flagChangesDoNotReuseLookaheadFromThePreviousMode() {
    for (String regex :
        List.of(
            "(?x:a b) c",
            "(?x)( ?:a)",
            "(?x)(? x:a)",
            "(?x)(?<n ame>a)",
            "(?x)a{1 2}",
            "(?x)a{ 1}",
            "(?x)a{1, 2}",
            "(?x)a(?-x) b",
            "(?x:a(?-x: b)c) d",
            "(?x)a+\\Q\\E?(?-x) b",
            "(?x:a#x\n)b c",
            "(?x)[a #x\n b](?-x) c")) {
      assertSame(regex, 0);
    }
    String regex = "(?x)\\Q #😀+ \\E";
    assertThat(Pattern.compile(regex).pattern()).isEqualTo(regex);
    assertThat(Pattern.compile(regex).matcher(" #😀+ ").matches()).isTrue();
    assertThat(Pattern.compile(regex, Pattern.LITERAL).matcher(regex).matches()).isTrue();
  }

  @Test
  @DisabledForCrosscheck("Quoting delimiters in ignored comments cannot affect later syntax")
  void ignoredCommentQuotesDoNotChangeLexicalState() {
    assertThat(Pattern.compile("(?x)#\\Q\n.").matcher("x").matches()).isTrue();
    assertThatThrownBy(() -> Pattern.compile("(?x)#\\Q\n("))
        .isInstanceOf(PatternSyntaxException.class);
    assertThatThrownBy(() -> Pattern.compile("(?x)#\\Q\n\\E"))
        .isInstanceOf(PatternSyntaxException.class);
  }

  @Test
  @DisabledForCrosscheck("Original UTF-16 coordinates are SafeRE's diagnostic contract")
  void errorsKeepOriginalSourceAndUtf16Positions() {
    String regex = "😀\\Q\\E\\p{NotAProperty}";
    try {
      Pattern.compile(regex);
      throw new AssertionError("expected syntax error");
    } catch (PatternSyntaxException e) {
      assertThat(e.getPattern()).isEqualTo(regex);
      assertThat(e.getIndex()).isEqualTo(regex.indexOf("\\p"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"a", "ab", "😀", "ab😀# &-]abcdefghi"})
  @DisabledForCrosscheck("Compilation allocation is a SafeRE complexity invariant")
  void manyShortQuotedClassFragmentsAllocateLinearly(String span)
      throws ReflectiveOperationException {
    // #1059: suffix-sized buffers made short quoted spans allocate quadratically. The streaming
    // lexer introduced in #1071 must keep allocation linear for every span length and code point.
    // ThreadMXBean is a supported public JDK management API, accessed reflectively so this
    // optional assertion does not add a jdk.management module dependency to the library.
    Object bean =
        Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean")
            .invoke(null);
    Class<?> type = Class.forName("com.sun.management.ThreadMXBean");
    Assumptions.assumeTrue(type.isInstance(bean));
    Assumptions.assumeTrue(
        (Boolean) type.getMethod("isThreadAllocatedMemorySupported").invoke(bean));
    type.getMethod("setThreadAllocatedMemoryEnabled", boolean.class).invoke(bean, true);
    Method allocated = type.getMethod("getThreadAllocatedBytes", long.class);
    String[] patterns = new String[3];
    for (int i = 0; i < patterns.length; i++) {
      patterns[i] = "[" + ("\\Q" + span + "\\E").repeat(128 << (2 * i)) + "]";
    }
    for (int i = 0; i < 12; i++) {
      for (String regex : patterns) {
        Pattern.compile(regex);
      }
    }
    long thread = Thread.currentThread().threadId();
    long previous = 0;
    for (String regex : patterns) {
      long before = (Long) allocated.invoke(bean, thread);
      Pattern.compile(regex);
      long bytes = (Long) allocated.invoke(bean, thread) - before;
      if (previous != 0) {
        // Four times as many fixed-size spans may allocate proportionally, not quadratically.
        assertThat(bytes)
            .as("allocation for %s UTF-16 units", regex.length())
            .isLessThan(previous * 6);
      }
      previous = bytes;
    }
  }

  private static void assertSame(String regex, int flags) {
    assertSame(regex, flags, INPUTS);
  }

  private static void assertSame(String regex, int flags, List<String> inputs) {
    java.util.regex.Pattern expected;
    try {
      expected = java.util.regex.Pattern.compile(regex, flags);
    } catch (PatternSyntaxException e) {
      assertThatThrownBy(() -> Pattern.compile(regex, flags))
          .as("rejection of %s with flags %s", regex, flags)
          .isInstanceOf(PatternSyntaxException.class);
      return;
    }
    Pattern actual = Pattern.compile(regex, flags);
    for (String input : inputs) {
      java.util.regex.Matcher jdk = expected.matcher(input);
      Matcher safe = actual.matcher(input);
      while (true) {
        boolean found = jdk.find();
        assertThat(safe.find())
            .as("find: %s flags %s input %s", regex, flags, input)
            .isEqualTo(found);
        if (!found) break;
        for (int group = 0; group <= jdk.groupCount(); group++) {
          assertThat(safe.start(group))
              .as("start: %s group %s", regex, group)
              .isEqualTo(jdk.start(group));
          assertThat(safe.end(group))
              .as("end: %s group %s", regex, group)
              .isEqualTo(jdk.end(group));
          assertThat(safe.group(group))
              .as("capture: %s group %s", regex, group)
              .isEqualTo(jdk.group(group));
        }
      }
    }
  }
}
