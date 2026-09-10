// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class EndAnchoredGapChainTest {

  @Test
  void endAnchoredDescriptorEligibility() {
    Pattern p1 = Pattern.compile("foo[0-9]+bar[0-9]+baz$");
    MultiAnchorDescriptor d1 = p1.multiAnchor();
    assertThat(d1.chain().isEndAnchored()).isTrue();
    assertThat(d1.chain().endAnchorWasDollar()).isTrue();
    assertThat(d1.isExecutableChain()).isTrue();
    assertThat(d1.isExecutableUtf8Chain()).isTrue();

    Pattern p2 = Pattern.compile("foo[0-9]+bar[0-9]+baz\\z");
    MultiAnchorDescriptor d2 = p2.multiAnchor();
    assertThat(d2.chain().isEndAnchored()).isTrue();
    assertThat(d2.chain().endAnchorWasDollar()).isFalse();
    assertThat(d2.isExecutableChain()).isTrue();
    assertThat(d2.isExecutableUtf8Chain()).isTrue();

    Pattern p3 = Pattern.compile("user:[a-z]+-host:[a-z]+-status:[0-9]+$");
    MultiAnchorDescriptor d3 = p3.multiAnchor();
    assertThat(d3.chain().isEndAnchored()).isTrue();
    assertThat(d3.chain().endAnchorWasDollar()).isTrue();
    assertThat(d3.isExecutableChain()).isTrue();
    assertThat(d3.isExecutableUtf8Chain()).isTrue();
    assertThat(d3.isExecutableUtf8Chain()).isTrue();

    // Multiline mode (?m) must not be treated as end-anchored for the entire input
    Pattern pMulti = Pattern.compile("(?m)foo[0-9]+bar[0-9]+baz$");
    MultiAnchorDescriptor dMulti = pMulti.multiAnchor();
    assertThat(dMulti.chain().isEndAnchored()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "foo[0-9]+bar[0-9]+baz$",
        "foo[0-9]+bar[0-9]+baz\\z",
        "^foo[0-9]+bar[0-9]+baz$",
        "user:[a-z]+-host:[a-z]+-status:[0-9]+$",
        "PREFIX[0-9]{2}MIDDLE[a-z]{3}SUFFIX$"
      })
  void matchesJdkAcrossInputTerminators(String regex) {
    Pattern pattern = Pattern.compile(regex);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);

    String body;
    if (regex.contains("status")) {
      body = "user:alice-host:prod-status:200";
    } else if (regex.contains("MIDDLE")) {
      body = "PREFIX12MIDDLEabcSUFFIX";
    } else {
      body = "foo123bar456baz";
    }

    String noise = "header line with unrelated content\nanother random line\n";

    String[] candidateInputs = {
      body,
      body + "\n",
      body + "\r\n",
      body + "\r",
      noise + body,
      noise + body + "\n",
      noise + body + "\r\n",
      noise + "foo999bar888mismatch\n" + body + "\n",
      noise + "foo999bar888baz_not_end extra characters\n",
      "completely mismatched input text"
    };

    for (String input : candidateInputs) {
      assertMatchesJdk(pattern, jdkPattern, input, regex);
    }
  }

  @Test
  void unixLinesBehaviorWithCarriageReturn() {
    String regex = "foo[0-9]+bar$";
    Pattern pDefault = Pattern.compile(regex);
    Pattern pUnix = Pattern.compile(regex, Pattern.UNIX_LINES);
    java.util.regex.Pattern jdkDefault = java.util.regex.Pattern.compile(regex);
    java.util.regex.Pattern jdkUnix =
        java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.UNIX_LINES);

    // In default mode, \r is recognized as a line terminator, so $ can match before \r
    String withCr = "foo123bar\r";
    assertMatchesJdk(pDefault, jdkDefault, withCr, regex);

    // In UNIX_LINES mode, \r is not a line terminator, so $ only matches at EOF or before \n
    assertMatchesJdk(pUnix, jdkUnix, withCr, regex);

    String withNl = "foo123bar\n";
    assertMatchesJdk(pUnix, jdkUnix, withNl, regex);
  }

  @Test
  void multilineModeFindsMatchesOnIntermediateLines() {
    String regex = "(?m)foo[0-9]+bar$";
    Pattern pattern = Pattern.compile(regex);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);

    String input = "foo123bar\nnoise line\nfoo456bar\nlast line\n";
    assertMatchesJdk(pattern, jdkPattern, input, regex);
  }

  @Test
  void trailingBoundedClassRepeatBeforeEndAnchor() {
    String regex = "foo[0-9]+bar[a-z]{1,4}$";
    Pattern pattern = Pattern.compile(regex);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);

    String[] inputs = {
      "foo123barabc",
      "foo123barabc\n",
      "noise prefix\nfoo123barz\n",
      "foo123bar", // too short, needs {1,4}
      "foo123barabcdef" // too long (6 chars)
    };

    for (String input : inputs) {
      assertMatchesJdk(pattern, jdkPattern, input, regex);
    }
  }

  @Test
  void largeInputReverseSearchInstant() {
    String regex = "user:[a-z]+-host:[a-z]+-status:[0-9]+$";
    Pattern pattern = Pattern.compile(regex);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);

    // 1 MB noise followed by target line
    String noiseLine = "2026-09-10 12:00:00 [worker-thread-42] INFO processing event item\n";
    String largeNoise = noiseLine.repeat(15_000);
    String targetLine = "user:alice-host:prod-status:500\n";
    String input = largeNoise + targetLine;

    assertMatchesJdk(pattern, jdkPattern, input, regex);

    // Non-matching input with noise
    String mismatchInput = largeNoise + "user:alice-host:prod-status:FAILED\n";
    assertMatchesJdk(pattern, jdkPattern, mismatchInput, regex);
  }

  private static void assertMatchesJdk(
      Pattern pattern, java.util.regex.Pattern jdkPattern, String input, String regex) {
    Matcher strMatcher = pattern.matcher(input);
    java.util.regex.Matcher jdkMatcher = jdkPattern.matcher(input);

    boolean jdkFound = jdkMatcher.find();
    boolean strFound = strMatcher.find();

    assertThat(strFound)
        .as("String match existence for '%s' on %s", regex, escapeInput(input))
        .isEqualTo(jdkFound);

    if (jdkFound) {
      assertThat(strMatcher.start())
          .as("String start for '%s' on %s", regex, escapeInput(input))
          .isEqualTo(jdkMatcher.start());
      assertThat(strMatcher.end())
          .as("String end for '%s' on %s", regex, escapeInput(input))
          .isEqualTo(jdkMatcher.end());
      assertThat(strMatcher.group(0))
          .as("String group for '%s' on %s", regex, escapeInput(input))
          .isEqualTo(jdkMatcher.group(0));
    }

    byte[] utf8Bytes = input.getBytes(UTF_8);
    Utf8Matcher utf8Matcher = pattern.matcher(Utf8Input.validated(utf8Bytes));
    boolean utf8Found = utf8Matcher.find();

    assertThat(utf8Found)
        .as("UTF-8 match existence for '%s' on %s", regex, escapeInput(input))
        .isEqualTo(jdkFound);

    if (jdkFound) {
      // In ASCII/valid UTF-8, byte offsets equal char offsets for these tests
      assertThat(utf8Matcher.start())
          .as("UTF-8 start for '%s' on %s", regex, escapeInput(input))
          .isEqualTo(jdkMatcher.start());
      assertThat(utf8Matcher.end())
          .as("UTF-8 end for '%s' on %s", regex, escapeInput(input))
          .isEqualTo(jdkMatcher.end());
    }
  }

  private static String escapeInput(String s) {
    if (s.length() > 50) {
      s = s.substring(0, 47) + "...";
    }
    return s.replace("\n", "\\n").replace("\r", "\\r");
  }
}
