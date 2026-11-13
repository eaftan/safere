// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import org.junit.jupiter.api.Test;

/**
 * Behavioral tests for patterns that start with a repetition of a class excluding only a few code
 * points, such as non-DOTALL {@code .*}, {@code .+} and {@code [^\n]*}. Their start candidates come
 * from walking back from an inner literal to the nearest excluded code point (usually a line
 * terminator), so these tests compare matches, bounds and groups with {@code java.util.regex}
 * across line terminators, regions, supplementary characters and UTF-8 input.
 */
class LeadingLineExpansionTest {

  // Start candidates are only chosen this way for skips of at least
  // MultiAnchorCompiler.MIN_WIDE_LEADING_SKIP units. Inputs are also run behind this much padding,
  // either as a separate first line or joined to the first line.
  private static final String PAD = "p".repeat(300);

  private static final List<String> REGEXES =
      List.of(
          ".*error: \\w+",
          ".+error: \\w+",
          "[^\\n]*error: \\w+",
          "[^\\n]+error: \\w+",
          "(.*)(error): (\\w+)",
          "(.+?)error",
          ".*?error",
          "[^;]*error",
          "[^\\r\\n]*error(\\d)?",
          "(?:.*)error$",
          "(?m).*error$",
          "(?i).*ERROR: \\w+");

  private static final List<String> INPUTS =
      List.of(
          "",
          "error: x",
          "error: ",
          "no match here\nnor here\n",
          "first line\nsecond error: one\nthird",
          "error: at start\nmiddle error: two\n\nerror: three",
          "\n\n\nerror: after empty lines\n\n",
          "a error: \nb error: c",
          "crlf\r\nline error: one\r\nnext error: two\r\n",
          "lone cr\rthen error: x\rerror: y",
          "nel\u0085x error: a\u2028y error: b\u2029z error: c",
          "semi;colon error: x;y error: z",
          "😀😀 error: smile\n😀error: 😀\né error: é",
          "error: error: error: x",
          "errorerror: x error1 error",
          "ERROR: upper\nError: mixed",
          """
          2026-11-07 INFO starting
          2026-11-07 WARN error: nope
          2026-11-07 INFO error: \t
          2026-11-07 ERROR error: disk
          """);

  @Test
  void utf8ExistenceAgreesWithJdkAcrossLeadingRepeatConstraints() {
    List<String> regexes =
        List.of(
            ".*error: \\w+",
            ".*?error: \\w+",
            "(.*)(error): (\\w+)",
            "[^;]*error: \\w+",
            "[^😀]*error: \\w+",
            ".{0,3}error: \\w+",
            ".+error: \\w+",
            ".{1,3}error: \\w+",
            "^.*error: \\w+",
            "\\A.*error: \\w+",
            "\\b.*error: \\w+",
            ".*\\berror: \\w+",
            ".*error: \\w+$",
            ".*[ab]{2}error: \\w+",
            "(?i).*ERROR: \\w+",
            ".*error\\X");
    List<String> inputs =
        List.of(
            "",
            "error: disk",
            "no marker",
            "error: -",
            "error: -\nerror: disk",
            "error: -\r\nnext error: disk",
            "error: -;next error: disk",
            "é😀error: -\u0085中 error: disk",
            "error: -\u2028error: -\u2029error: disk",
            "aaerror: disk",
            "erroré\u0301");
    for (String regex : regexes) {
      for (int flags : new int[] {0, Pattern.UNIX_LINES, Pattern.MULTILINE}) {
        Pattern pattern = Pattern.compile(regex, flags);
        java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex, flags);
        for (String tail : inputs) {
          for (String prefix : List.of("", PAD, PAD + "\n")) {
            String input = prefix + tail;
            boolean expected = jdk.matcher(input).find();
            // A nonzero byte window must retain assertion context relative to the window.
            byte[] padded = ("before" + input + "after").getBytes(UTF_8);
            int length = input.getBytes(UTF_8).length;
            for (Utf8Input utf8 :
                List.of(
                    Utf8Input.trusted(input.getBytes(UTF_8)),
                    Utf8Input.trusted(padded, 6, length))) {
              assertThat(pattern.find(utf8))
                  .as("/%s/ flags=%s on %s", regex, flags, input)
                  .isEqualTo(expected);
            }
          }
        }
      }
    }
  }

  @Test
  void findAgreesWithJdk() {
    for (int flags : new int[] {0, Pattern.UNIX_LINES, Pattern.MULTILINE}) {
      for (String regex : REGEXES) {
        Pattern pattern = Pattern.compile(regex, flags);
        java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex, flags);
        for (String input : paddedInputs()) {
          String what = "%s (flags %d) on %s".formatted(regex, flags, input);
          assertThat(findAll(pattern.matcher(input)))
              .as(what)
              .isEqualTo(findAll(jdk.matcher(input)));
          assertThat(findAllUtf8(pattern, input))
              .as("UTF-8 " + what)
              .isEqualTo(jdkUtf8(jdk, input));
          // Within the padding, every start behaves alike, so sample it sparsely.
          int padded = padLength(input);
          for (int from = 0; from <= input.length(); from += from < padded - 8 ? 31 : 1) {
            java.util.regex.Matcher expected = jdk.matcher(input);
            Matcher actual = pattern.matcher(input);
            boolean found = expected.find(from);
            assertThat(actual.find(from)).as("%s from %d", what, from).isEqualTo(found);
            if (found) {
              assertThat(groups(actual)).as("%s from %d", what, from).isEqualTo(groups(expected));
            }
          }
        }
      }
    }
  }

  @Test
  void regionsAgreeWithJdk() {
    String lines = "aa error: one\nbb error: two\r\ncc error: three";
    regionsAgreeWithJdk(lines, 3, 4);
    regionsAgreeWithJdk(PAD + "\n" + lines, 13, 17);
    regionsAgreeWithJdk(PAD + lines, 13, 17);
  }

  private static void regionsAgreeWithJdk(String input, int startStep, int endStep) {
    for (String regex : REGEXES) {
      Pattern pattern = Pattern.compile(regex);
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);
      for (int start = 0; start <= input.length(); start += startStep) {
        for (int end = start; end <= input.length(); end += endStep) {
          for (boolean transparent : new boolean[] {false, true}) {
            java.util.regex.Matcher expected = jdk.matcher(input);
            expected.region(start, end).useTransparentBounds(transparent);
            Matcher actual = pattern.matcher(input);
            actual.region(start, end).useTransparentBounds(transparent);
            assertThat(findAll(actual))
                .as("%s region [%d, %d) transparent=%s", regex, start, end, transparent)
                .isEqualTo(findAll(expected));
          }
        }
      }
    }
  }

  @Test
  void failedAttemptAtLineStartMovesOnToLaterLine() {
    // The first line holds the literal, but its tail cannot match, so the start candidate at that
    // line's start fails and the search must find the match on the third line.
    Pattern pattern = Pattern.compile(".*error: (\\d+)");
    String input = PAD + " x error: none\nnothing\n" + PAD + " y error: 42\n";
    int thirdLine = input.indexOf("nothing\n") + "nothing\n".length();
    Matcher matcher = pattern.matcher(input);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(thirdLine);
    assertThat(matcher.group(1)).isEqualTo("42");
    assertThat(matcher.find()).isFalse();

    Utf8Matcher utf8 = pattern.matcher(Utf8Input.trusted(input.getBytes(UTF_8)));
    assertThat(utf8.find()).isTrue();
    assertThat(utf8.start()).isEqualTo(thirdLine);
    assertThat(utf8.find()).isFalse();
  }

  @Test
  void plusNeedsOneCharacterBeforeTheLiteral() {
    Pattern pattern = Pattern.compile(".+error");
    String input = PAD + "\nerror\nerror\nxerror";
    Matcher matcher = pattern.matcher(input);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(input.length() - 6);
    assertThat(matcher.end()).isEqualTo(input.length());
  }

  @Test
  void supplementaryCharactersBeforeTheLiteral() {
    Pattern pattern = Pattern.compile("(.*)error");
    String input = PAD + "\n" + "😀".repeat(150) + "b😀error";
    Matcher matcher = pattern.matcher(input);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(PAD.length() + 1);
    assertThat(matcher.group(1)).isEqualTo("😀".repeat(150) + "b😀");
    // A search starting between the halves of a pair still sees a valid match, as in the JDK.
    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile("(.*)error").matcher(input);
    assertThat(matcher.find(PAD.length() + 2)).isEqualTo(jdk.find(PAD.length() + 2));
    assertThat(groups(matcher)).isEqualTo(groups(jdk));
  }

  private static List<String> paddedInputs() {
    List<String> inputs = new ArrayList<>();
    for (String input : INPUTS) {
      inputs.add(input);
      inputs.add(PAD + "\n" + input);
      inputs.add(PAD + input);
    }
    return inputs;
  }

  private static int padLength(String input) {
    return input.startsWith(PAD) ? PAD.length() : 0;
  }

  private static List<String> findAll(Matcher matcher) {
    List<String> out = new ArrayList<>();
    while (matcher.find()) {
      out.add(groups(matcher));
    }
    return out;
  }

  private static List<String> findAll(java.util.regex.Matcher matcher) {
    List<String> out = new ArrayList<>();
    while (matcher.find()) {
      out.add(groups(matcher));
    }
    return out;
  }

  private static String groups(MatchResult result) {
    StringBuilder sb = new StringBuilder();
    for (int group = 0; group <= result.groupCount(); group++) {
      sb.append(result.start(group)).append(':').append(result.end(group)).append(' ');
    }
    return sb.toString();
  }

  private static List<String> findAllUtf8(Pattern pattern, String input) {
    Utf8Matcher matcher = pattern.matcher(Utf8Input.trusted(input.getBytes(UTF_8)));
    List<String> out = new ArrayList<>();
    while (matcher.find()) {
      StringBuilder sb = new StringBuilder();
      for (int group = 0; group <= matcher.groupCount(); group++) {
        sb.append(matcher.start(group)).append(':').append(matcher.end(group)).append(' ');
      }
      out.add(sb.toString());
    }
    return out;
  }

  /** Returns the JDK's matches with bounds converted to UTF-8 byte offsets. */
  private static List<String> jdkUtf8(java.util.regex.Pattern jdk, String input) {
    java.util.regex.Matcher matcher = jdk.matcher(input);
    List<String> out = new ArrayList<>();
    while (matcher.find()) {
      StringBuilder sb = new StringBuilder();
      for (int group = 0; group <= matcher.groupCount(); group++) {
        int start = matcher.start(group);
        sb.append(start < 0 ? -1 : byteOffset(input, start))
            .append(':')
            .append(start < 0 ? -1 : byteOffset(input, matcher.end(group)))
            .append(' ');
      }
      out.add(sb.toString());
    }
    return out;
  }

  private static int byteOffset(String input, int charIndex) {
    return input.substring(0, charIndex).getBytes(UTF_8).length;
  }
}
