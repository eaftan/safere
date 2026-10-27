// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Fresh entries to nested nullable repetitions must not inherit a previous entry's progress. */
@DisabledForCrosscheck("compares directly with the JDK using package-private engine controls")
class NestedNullableLoopTest {
  private static final List<EnginePathOptions> ENGINE_OPTIONS =
      List.of(
          EnginePathOptions.builder().build(),
          EnginePathOptions.builder().onePass(false).bitState(false).build(),
          EnginePathOptions.builder().dfa(false).onePass(false).build(),
          EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build());

  private static Stream<Arguments> nestedNullableLoopCases() {
    List<Arguments> cases = new ArrayList<>();
    for (String atom : List.of("x", "xx", "é", "😀")) {
      for (String body : List.of("(?:" + atom + "|)", "(?:" + atom + "?)")) {
        for (String inner : List.of("+?", "*?", "+", "*")) {
          for (String outer : List.of("*", "+", "{1,}", "{2}", "+?")) {
            String regex = "(?:" + body + inner + ")" + outer;
            String input = atom.repeat(4);
            cases.add(Arguments.of(regex, input));
            cases.add(
                Arguments.of("(?:a?" + body + inner + "b?)" + outer, atom + "b" + atom.repeat(2)));
            cases.add(Arguments.of(regex + "y", input + "y"));
            cases.add(Arguments.of(regex + "$", input));
            cases.add(Arguments.of("(" + regex + ")", input));
          }
        }
      }
    }
    cases.add(Arguments.of("(?:(?:x|)+?)*", "xx"));
    cases.add(Arguments.of("(?:(?:x|)+?b?)*", "xbx"));
    cases.add(Arguments.of("(?:a?(?:x|)+?)*", "xx"));
    cases.add(Arguments.of("(?:(?:xx|)+?)*", "xxxx"));
    cases.add(Arguments.of("(?:(?:x?)+?)*", "xx"));
    cases.add(Arguments.of("(?:(?:x|)+?){1,}", "xx"));
    // Captures within the repeated body also exercise retained and empty final captures.
    cases.add(Arguments.of("(?:(x|)+?)*", "xxx"));
    cases.add(Arguments.of("((x?)+?)*", "xxx"));
    cases.add(Arguments.of("(?:(?:x|)+?)*", ""));
    cases.add(Arguments.of("(?:(?:x|)+?)*", "!xx!xxx"));
    // Empty paths must be revisited when a loop's progress state changes, including NOPs
    // and assertions shared by a consuming iteration and the final zero-width iteration.
    for (String empty : List.of("", "\\b", "\\B", "\\b\\b", "(?:)")) {
      for (String quantifier : List.of("+", "*", "+?", "{1,3}")) {
        for (String atom : List.of("a", "é", "😀")) {
          String regex = "(?:" + atom + "?(?:" + empty + "|.))" + quantifier;
          for (String input : List.of(atom + "!" + atom + "!", "!" + atom + "!", atom.repeat(3))) {
            cases.add(Arguments.of(regex, input));
            cases.add(Arguments.of(regex + "!", input));
          }
        }
      }
    }
    return cases.stream();
  }

  private static Stream<String> nestedNullableEpsilonCases() {
    List<String> cases = new ArrayList<>();
    for (String body : List.of("(?:a?(?:|.))", "(?:a?(?:\\b|.))")) {
      for (String firstQuantifier : List.of("+", "+?")) {
        String regex = body;
        String quantifier = firstQuantifier;
        for (int depth = 1; depth <= 7; depth++) {
          regex = "(?:" + regex + ")" + quantifier;
          cases.add(regex);
          quantifier = quantifier.equals("+") ? "+?" : "+";
        }
      }
    }
    return cases.stream();
  }

  @ParameterizedTest(name = "[{index}] /{0}/")
  @MethodSource("nestedNullableEpsilonCases")
  void nestedNullableEpsilonFindSequencesMatchJdk(String regex) {
    String input = "!a!".repeat(300) + "z";
    EnginePathOptions nfaOnly =
        EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build();
    Pattern pattern = Pattern.compile(regex, 0, nfaOnly);
    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(regex).matcher(input);
    Matcher safere = pattern.matcher(input);
    Utf8Matcher utf8 = pattern.matcher(Utf8Input.trusted(input.getBytes(UTF_8)));
    // The ASCII input gives both representations the same offsets and empty-match advancement.
    while (jdk.find()) {
      assertThat(safere.find()).isTrue();
      assertGroupsMatch(jdk, safere);
      assertThat(utf8.find()).isTrue();
      assertThat(utf8.start()).isEqualTo(jdk.start());
      assertThat(utf8.end()).isEqualTo(jdk.end());
    }
    assertThat(safere.find()).isFalse();
    assertThat(utf8.find()).isFalse();
  }

  @ParameterizedTest(name = "[{index}] /{0}/ on {1}")
  @MethodSource("nestedNullableLoopCases")
  void freshLoopEntriesMatchJdkAcrossEngines(String regex, String input) {
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);
    for (EnginePathOptions options : ENGINE_OPTIONS) {
      Pattern pattern = Pattern.compile(regex, 0, options);
      java.util.regex.Matcher jdk = jdkPattern.matcher(input);
      Matcher safere = pattern.matcher(input);
      boolean matches = jdk.matches();
      assertThat(safere.matches()).isEqualTo(matches);
      if (matches) {
        assertGroupsMatch(jdk, safere);
      }
      jdk.reset();
      safere.reset();
      boolean lookingAt = jdk.lookingAt();
      assertThat(safere.lookingAt()).isEqualTo(lookingAt);
      if (lookingAt) {
        assertGroupsMatch(jdk, safere);
      }
      jdk.reset();
      safere.reset();
      while (jdk.find()) {
        assertThat(safere.find()).isTrue();
        assertGroupsMatch(jdk, safere);
      }
      assertThat(safere.find()).isFalse();
      assertThat(pattern.matcher(input).replaceAll("-"))
          .isEqualTo(jdkPattern.matcher(input).replaceAll("-"));

      // Both representations use the same compiled loops. Compare a nonzero region as well,
      // mapping JDK UTF-16 coordinates to UTF-8 byte offsets. Only the first match is compared
      // because subsequent empty-match advancement has representation-specific semantics.
      String regionInput = "!" + input + "!";
      jdk = jdkPattern.matcher(regionInput).region(1, 1 + input.length());
      byte[] bytes = regionInput.getBytes(UTF_8);
      Utf8Matcher utf8 = pattern.matcher(Utf8Input.validated(bytes)).region(1, bytes.length - 1);
      boolean found = jdk.find();
      assertThat(utf8.find()).isEqualTo(found);
      if (found) {
        for (int group = 0; group <= jdk.groupCount(); group++) {
          assertThat(utf8.start(group)).isEqualTo(byteOffset(regionInput, jdk.start(group)));
          assertThat(utf8.end(group)).isEqualTo(byteOffset(regionInput, jdk.end(group)));
        }
      }
    }
  }

  private static void assertGroupsMatch(java.util.regex.Matcher jdk, Matcher safere) {
    for (int group = 0; group <= jdk.groupCount(); group++) {
      assertThat(safere.group(group)).isEqualTo(jdk.group(group));
      assertThat(safere.start(group)).isEqualTo(jdk.start(group));
      assertThat(safere.end(group)).isEqualTo(jdk.end(group));
    }
  }

  private static int byteOffset(String input, int index) {
    return index < 0 ? -1 : input.substring(0, index).getBytes(UTF_8).length;
  }
}
