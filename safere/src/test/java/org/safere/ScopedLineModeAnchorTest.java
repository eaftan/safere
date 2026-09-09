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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Scoped line modes belong to each anchor, including when both modes occur in one program. */
@DisabledForCrosscheck("differential matrix includes UTF-8 and internal engine coverage")
class ScopedLineModeAnchorTest {
  @ParameterizedTest(name = "[{index}] /{0}/ flags={1} terminator={3} padding={4}")
  @MethodSource("anchorCases")
  void scopedAnchorsMatchJdk(
      String regex, int flags, String terminator, String description, int padding) {
    String input = "x".repeat(padding) + "foo" + terminator;
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex, flags);
    Pattern safe = Pattern.compile(regex, flags);
    List<String> expected = new ArrayList<>();
    java.util.regex.Matcher reference = jdk.matcher(input);
    while (reference.find()) {
      expected.add(captureTrace(reference));
    }
    List<String> actual = new ArrayList<>();
    Matcher matcher = safe.matcher(input);
    while (matcher.find()) {
      actual.add(captureTrace(matcher));
    }
    assertThat(actual).as("String find sequence").isEqualTo(expected);
    actual.clear();
    Utf8Matcher utf8 = safe.matcher(Utf8Input.validated(input.getBytes(UTF_8)));
    while (utf8.find()) {
      List<String> captures = new ArrayList<>();
      for (int group = 0; group <= utf8.groupCount(); group++) {
        int start = utf8.start(group);
        int end = utf8.end(group);
        String value =
            start < 0 ? null : new String(input.getBytes(UTF_8), start, end - start, UTF_8);
        captures.add(start + ":" + end + ":" + value);
      }
      actual.add(captures.toString());
    }
    assertThat(actual).as("UTF-8 find sequence").isEqualTo(expected);
    Prog prog = safe.prog();
    assertThat(
            Nfa.search(
                    prog,
                    input,
                    Nfa.Anchor.UNANCHORED,
                    Nfa.MatchKind.FIRST_MATCH,
                    prog.numCaptures())
                != null)
        .as("NFA")
        .isEqualTo(!expected.isEmpty());
    assertThat(BitState.search(prog, input, false, false, false, prog.numCaptures()) != null)
        .as("BitState")
        .isEqualTo(!expected.isEmpty());
    Dfa.SearchResult dfa = Dfa.search(prog, input, false, false);
    assertThat(dfa).as("DFA budget").isNotNull();
    assertThat(dfa.matched()).as("DFA").isEqualTo(!expected.isEmpty());
    OnePass onePass = OnePass.build(prog);
    if (onePass != null) {
      assertThat(onePass.search(input, false, prog.numCaptures()) != null)
          .as("OnePass")
          .isEqualTo(jdk.matcher(input).lookingAt());
    }
    assertThat(safe.matcher(input).lookingAt()).isEqualTo(jdk.matcher(input).lookingAt());
    assertThat(safe.matcher(input).matches()).isEqualTo(jdk.matcher(input).matches());
    assertThat(safe.matcher(Utf8Input.validated(input.getBytes(UTF_8))).lookingAt())
        .isEqualTo(jdk.matcher(input).lookingAt());
    assertThat(safe.matcher(Utf8Input.validated(input.getBytes(UTF_8))).matches())
        .isEqualTo(jdk.matcher(input).matches());
  }

  @Test
  void mixedLineEndsRetainCrContextWithinAndAcrossSearches() {
    for (String regex :
        List.of(
            "(?-d:(?m:$))(?dm:$)", "(?dm:$)(?-d:(?m:$))",
            "((?-d:(?m:$)))(?dm:$)", "(?-d:(?m:$))(?dm:$)a?")) {
      for (int flags : new int[] {0, Pattern.UNIX_LINES}) {
        for (int padding : new int[] {0, 512}) {
          List<String> inputs = List.of("\r\na\na", "\na\r\na", "\r\na", "a\na");
          for (List<String> order : List.of(inputs, inputs.reversed())) {
            Pattern safe = Pattern.compile(regex, flags);
            java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex, flags);
            for (String suffix : order) {
              String input = "x".repeat(padding) + suffix;
              List<String> expected = new ArrayList<>();
              java.util.regex.Matcher reference = jdk.matcher(input);
              while (reference.find()) {
                expected.add(reference.start() + ":" + reference.end());
              }
              List<String> actual = new ArrayList<>();
              Matcher matcher = safe.matcher(input);
              while (matcher.find()) {
                actual.add(matcher.start() + ":" + matcher.end());
              }
              assertThat(actual)
                  .as("String /%s/ flags=%s input=%s", regex, flags, input)
                  .isEqualTo(expected);
              actual.clear();
              Utf8Matcher utf8 = safe.matcher(Utf8Input.validated(input.getBytes(UTF_8)));
              while (utf8.find()) {
                actual.add(utf8.start() + ":" + utf8.end());
              }
              assertThat(actual)
                  .as("UTF-8 /%s/ flags=%s input=%s", regex, flags, input)
                  .isEqualTo(expected);
            }
          }
        }
      }
    }
  }

  @Test
  void scopedLineStartsAndCachedTransitionsMatchJdk() {
    for (int flags : new int[] {0, Pattern.UNIX_LINES}) {
      for (String mode : List.of("d", "-d")) {
        for (String regex :
            List.of("(?m)(?" + mode + ":^)(foo|bar)", "(?m)(?" + mode + ":^foo$)|(?-d:^bar$)")) {
          Pattern safe = Pattern.compile(regex, flags);
          java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex, flags);
          for (String terminator : List.of("\n", "\r\n", "\r", "\u0085", "\u2028", "\u2029")) {
            String input = "x".repeat(2048) + terminator + "foo" + terminator;
            boolean expected = jdk.matcher(input).find();
            assertThat(safe.matcher(input).find())
                .as("String /%s/ flags=%s", regex, flags)
                .isEqualTo(expected);
            assertThat(safe.matcher(Utf8Input.validated(input.getBytes(UTF_8))).find())
                .as("UTF-8 /%s/ flags=%s", regex, flags)
                .isEqualTo(expected);
          }
        }
      }
    }
  }

  @Test
  void scopedEndAnchorsRespectUtf8RegionsAndBounds() {
    for (String anchor : List.of("$", "\\Z", "(?m:$)")) {
      for (int flags : new int[] {0, Pattern.UNIX_LINES}) {
        for (String mode : List.of("d", "-d")) {
          String regex = "(foo|bar)(?" + mode + ":" + anchor + ")";
          Pattern safe = Pattern.compile(regex, flags);
          java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex, flags);
          for (String terminator : List.of("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029")) {
            String input = "xfoo" + terminator;
            for (int end = 4; end <= input.length(); end++) {
              for (boolean anchoring : new boolean[] {false, true}) {
                for (boolean transparent : new boolean[] {false, true}) {
                  java.util.regex.Matcher reference =
                      jdk.matcher(input)
                          .region(1, end)
                          .useAnchoringBounds(anchoring)
                          .useTransparentBounds(transparent);
                  Matcher actual =
                      new Matcher(safe, new Utf8InputScanner(input.getBytes(UTF_8)))
                          .region(1, input.substring(0, end).getBytes(UTF_8).length)
                          .useAnchoringBounds(anchoring)
                          .useTransparentBounds(transparent);
                  boolean expected = reference.find();
                  assertThat(actual.find())
                      .as(
                          "/%s/ flags=%s end=%s anchoring=%s transparent=%s",
                          regex, flags, end, anchoring, transparent)
                      .isEqualTo(expected);
                  if (expected) {
                    assertThat(actual.start()).isEqualTo(reference.start());
                    assertThat(actual.end()).isEqualTo(reference.end());
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  private static String captureTrace(MatchResult matcher) {
    List<String> captures = new ArrayList<>();
    for (int group = 0; group <= matcher.groupCount(); group++) {
      captures.add(matcher.start(group) + ":" + matcher.end(group) + ":" + matcher.group(group));
    }
    return captures.toString();
  }

  private static Stream<Arguments> anchorCases() {
    List<Arguments> cases = new ArrayList<>();
    String[] terminators = {"", "\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029"};
    String[] names = {"empty", "LF", "CR", "CRLF", "NEL", "LS", "PS"};
    for (String anchor : List.of("$", "\\Z", "\\z", "(?m:$)")) {
      for (int flags : new int[] {0, Pattern.UNIX_LINES}) {
        for (String body : List.of("foo", "fo[op]", "(?:foo|bar)", "(foo|bar)", "^foo", "[a-z]+")) {
          for (String suffix :
              List.of(
                  "(?-d:" + anchor + ")",
                  "(?d:" + anchor + ")",
                  "(?d:(?-d:" + anchor + "))",
                  "(?-d:" + anchor + ")" + anchor,
                  "(?d:" + anchor + ")" + anchor,
                  "(?:(?d:" + anchor + ")|(?-d:" + anchor + "))",
                  "(?:(?-d:" + anchor + ")|(?d:" + anchor + "))")) {
            for (int i = 0; i < terminators.length; i++) {
              for (int padding : new int[] {0, 500, 2048}) {
                cases.add(Arguments.of(body + suffix, flags, terminators[i], names[i], padding));
              }
            }
          }
        }
      }
    }
    return cases.stream();
  }
}
