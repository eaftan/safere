// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisabledForCrosscheck(
    "compares String and UTF-8 entry points with diagnostics enabled and disabled")
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class LeadingExpansionEntryPointTest {
  @AfterEach
  void disableDiagnostics() {
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void leadingRepetitionPreservesMatchesCapturesReplacementAndSplit(boolean diagnostics) {
    Pattern.setDiagnostics(
        diagnostics ? new SafeReMatchDiagnostics() {} : SafeReMatchDiagnostics.NONE);
    for (String regex :
        List.of(
            "(\\s*)# noqa[0-9]",
            "(\\s*?)# noqa[0-9]",
            "(\\s+)# noqa[0-9]",
            "(\\s{0,3})# noqa[0-9]",
            "(\\s{1,3})# noqa[0-9]",
            "(\\s*)# noqa\\b",
            "([é😀]*)# noqa[0-9]",
            "(?i)(\\s*)# noqa[0-9]",
            "\\b([a-z]*)target[0-9]",
            "([ab]*)a[0-9]",
            "([ab]*)(?:a[0-9]|b[0-9])")) {
      Pattern pattern = Pattern.compile(regex);
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);
      for (String input :
          List.of(
              "",
              "# noqaX",
              "    # noqaX",
              "    # noqa1",
              "# noqa1",
              "😀é    # noqaX    # noqa1    # noqa2 tail",
              "é😀é# noqa1é😀# noqaXé# noqa2",
              "    # NOQA1 tail",
              "prefix wordtarget1 targetX target2",
              "abbaX abba1 abbb2")) {
        Utf8Input utf8 = Utf8Input.trusted(input.getBytes(UTF_8));
        assertThat(pattern.find(utf8))
            .as("%s on %s", regex, input)
            .isEqualTo(jdk.matcher(input).find());
        Matcher matcher = pattern.matcher(input);
        Utf8Matcher utf8Matcher = pattern.matcher(utf8);
        java.util.regex.Matcher expected = jdk.matcher(input);
        while (true) {
          boolean found = expected.find();
          assertThat(matcher.find()).as("%s on %s", regex, input).isEqualTo(found);
          assertThat(utf8Matcher.find()).as("UTF-8 %s on %s", regex, input).isEqualTo(found);
          if (!found) {
            break;
          }
          for (int group = 0; group <= expected.groupCount(); group++) {
            assertThat(matcher.start(group)).isEqualTo(expected.start(group));
            assertThat(matcher.end(group)).isEqualTo(expected.end(group));
            assertThat(matcher.group(group)).isEqualTo(expected.group(group));
            assertThat(utf8Matcher.start(group))
                .isEqualTo(input.substring(0, expected.start(group)).getBytes(UTF_8).length);
            assertThat(utf8Matcher.end(group))
                .isEqualTo(input.substring(0, expected.end(group)).getBytes(UTF_8).length);
          }
        }
        for (String replacement : List.of("X", "<$0>", "<$1>")) {
          assertThat(pattern.matcher(input).replaceAll(replacement))
              .as("%s replaceAll on %s", regex, input)
              .isEqualTo(jdk.matcher(input).replaceAll(replacement));
          assertThat(pattern.matcher(input).replaceFirst(replacement))
              .as("%s replaceFirst on %s", regex, input)
              .isEqualTo(jdk.matcher(input).replaceFirst(replacement));
        }
        for (int limit : new int[] {-1, 0, 1, 2}) {
          assertThat(pattern.split(input, limit))
              .as("%s split on %s", regex, input)
              .containsExactly(jdk.split(input, limit));
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void exhaustedCandidateVerificationFallsBackToAuthoritativeMatching(boolean diagnostics) {
    Pattern.setDiagnostics(
        diagnostics ? new SafeReMatchDiagnostics() {} : SafeReMatchDiagnostics.NONE);
    // Binary suffix windows require more DFA states than the cache can retain. The trailing
    // window still matches, so cache exhaustion must fall back rather than mean rejection.
    StringBuilder input = new StringBuilder(" ".repeat(2_000) + "# noqa");
    Random random = new Random(1024);
    for (int i = 0; i < 30_000; i++) {
      input.append(random.nextBoolean() ? 'a' : 'b');
    }
    input.append('a').append("b".repeat(14)).append('Z');
    String text = input.toString();
    String regex = "\\s*# noqa[ab]*a[ab]{14}Z";
    Pattern pattern = Pattern.compile(regex);
    assertThat(
            pattern
                .forwardFirstMatchDfa()
                .doSearch(new StringInputScanner(text), 2_000, false, false, true))
        .isNull();
    assertThat(pattern.matcher(text).replaceAll("X")).isEqualTo("X");
    assertThat(pattern.matcher(text).replaceFirst("X")).isEqualTo("X");
    assertThat(pattern.find(Utf8Input.trusted(text.getBytes(UTF_8)))).isTrue();
    String earlyMatch = "# noqa" + "a" + "b".repeat(14) + "Z;";
    assertThat(pattern.matcher(earlyMatch + text).replaceAll("X")).isEqualTo("X;X");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void replacementSearchDoesNotStartInsideAnIntactSurrogatePair(boolean diagnostics) {
    Pattern.setDiagnostics(
        diagnostics ? new SafeReMatchDiagnostics() {} : SafeReMatchDiagnostics.NONE);
    for (String regex :
        List.of(
            "\\uDC00[bc]",
            "(\\uDC00)[bc]",
            "\\s*\\uDC00[bc]",
            "(\\s*)\\uDC00[bc]",
            "\\s*[\\uDC00-\\uDFFF][bc]",
            "\\s{0,3}\\uDC00[bc]",
            "\\s+\\uDC00[bc]",
            "\\s*\\uDC00[bc]\\b{g}")) {
      Pattern pattern = Pattern.compile(regex);
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);
      for (String input :
          List.of(
              "\uDC00b;\uD800\uDC00b",
              " \uDC00b; \uD800\uDC00b \uDC00c;",
              " \uDC00b;\uD800\uDC00b;\uD800\uDC00c; \uDC00c")) {
        for (String replacement : List.of("X", "<$0>")) {
          assertThat(pattern.matcher(input).replaceAll(replacement))
              .as("%s on %s", regex, input)
              .isEqualTo(jdk.matcher(input).replaceAll(replacement));
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void repeatedLiteralPrefixReplacementPreservesMatches(boolean diagnostics) {
    Pattern.setDiagnostics(
        diagnostics ? new SafeReMatchDiagnostics() {} : SafeReMatchDiagnostics.NONE);
    for (String regex :
        List.of(
            "tag[bc]",
            "(?i)tag[bc]",
            "(?iu)éx[bc]",
            "(?iu)İx[bc]",
            "😀[bc]",
            "^tag[bc]",
            "(?m)^tag[bc]")) {
      Pattern pattern = Pattern.compile(regex);
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);
      for (String input :
          List.of(
              "",
              "tagX",
              "tagb TAGc tagX tagc;",
              "tagb\ntagc\nTAGb tagX;tagc",
              "éxb ÉXc éxX éxc",
              "İxb ixc ıxX İxc",
              "😀b;😀c 😀X 😀b",
              "x".repeat(1_000) + "tagb;tagX;tagc")) {
        for (String replacement : List.of("X", "<$0>")) {
          assertThat(pattern.matcher(input).replaceAll(replacement))
              .as("%s replaceAll on %s", regex, input)
              .isEqualTo(jdk.matcher(input).replaceAll(replacement));
          assertThat(pattern.matcher(input).replaceFirst(replacement))
              .as("%s replaceFirst on %s", regex, input)
              .isEqualTo(jdk.matcher(input).replaceFirst(replacement));
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void initialSurrogateInteriorCursorRemainsEligibleForLeadingExpansion(boolean diagnostics) {
    Pattern.setDiagnostics(
        diagnostics ? new SafeReMatchDiagnostics() {} : SafeReMatchDiagnostics.NONE);
    String input = "\uD800\uDC00b; \uDC00c;\uD800\uDC00b";
    for (String regex :
        List.of("\\s*\\uDC00[bc]", "\\s{0,3}\\uDC00[bc]", "\\s*[\\uDC00-\\uDFFF][bc]")) {
      java.util.regex.Matcher expected = java.util.regex.Pattern.compile(regex).matcher(input);
      Matcher actual = Pattern.compile(regex).matcher(input);
      assertThat(actual.find(1)).isEqualTo(expected.find(1)).isTrue();
      assertThat(actual.start()).isEqualTo(expected.start());
      assertThat(actual.end()).isEqualTo(expected.end());
      actual.region(1, input.length());
      expected.region(1, input.length());
      assertThat(actual.find()).isEqualTo(expected.find()).isTrue();
      assertThat(actual.start()).isEqualTo(expected.start());
      assertThat(actual.end()).isEqualTo(expected.end());
    }
  }
}
