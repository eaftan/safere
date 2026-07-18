// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Forced-path equivalence coverage for matching directly over UTF-8 storage. */
@DisabledForCrosscheck("uses package-private engine-path controls to compare SafeRE internals")
class Utf8EnginePathEquivalenceTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("forcedPaths")
  @DisplayName("forced UTF-8 engine paths preserve byte-coordinate find traces")
  void forcedEnginePathsPreserveFindTraces(
      String description, String regex, String input, EnginePathOptions forced) {
    Pattern canonical = Pattern.compile(regex);
    Pattern forcedPattern = Pattern.compile(regex, 0, forced);

    assertThat(trace(forcedPattern, input))
        .as("%s: /%s/ on %s", description, regex, input)
        .isEqualTo(trace(canonical, input));
    assertThat(trace(canonical, input))
        .as("String/UTF-8 oracle: /%s/ on %s", regex, input)
        .isEqualTo(stringOracleTrace(canonical, input));
  }

  private static Stream<Arguments> forcedPaths() {
    String longPrefix = "x".repeat(600);
    return Stream.of(
        Arguments.of(
            "OnePass versus DFA/fallback",
            "^(?<word>[\\p{L}]+):([0-9]+)$",
            "élan:42",
            EnginePathOptions.builder().onePass(false).build()),
        Arguments.of(
            "forward, reverse, and anchored DFA versus Pike NFA",
            "([\\p{L}]+?)([0-9]+)",
            "😀élan42 fin7",
            EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build()),
        Arguments.of(
            "reverse-first end-anchored DFA versus forward search",
            "(?<tail>élan[0-9]+)$",
            longPrefix + "élan42",
            EnginePathOptions.builder().reverseDfa(false).build()),
        Arguments.of(
            "BitState versus Pike NFA",
            "((?:é|éé)*)(😀)?",
            "ééé😀xé",
            EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build()),
        Arguments.of(
            "deferred versus eager capture extraction",
            "(?<letters>[\\p{L}]+)(?<digits>[0-9]+)",
            "😀élan42 fin7",
            EnginePathOptions.builder().lazyCaptureExtraction(false).build()),
        Arguments.of(
            "line anchors and CRLF", "(?m)^(?<line>[^\\r\\n]*)$", "α\r\nβ\n", canonicalFallback()),
        Arguments.of(
            "Unicode word boundaries", "\\b(?<word>élan|β)\\b", "😀 élan,β!", canonicalFallback()),
        Arguments.of(
            "grapheme boundaries", "(?<cluster>\\X)\\b{g}", "á👩‍💻β", canonicalFallback()),
        Arguments.of(
            "literal fast path is safely guarded",
            "élan",
            "😀élanélan",
            EnginePathOptions.builder().literalFastPaths(false).build()),
        Arguments.of(
            "literal prefix acceleration is safely guarded",
            "élan[0-9]+",
            "😀xxélan42 yyélan7",
            EnginePathOptions.builder().startAcceleration(false).build()),
        Arguments.of(
            "character-class acceleration is safely guarded",
            "[α-ω][0-9]+",
            "😀xxβ42 yyγ7",
            EnginePathOptions.builder()
                .charClassMatchFastPaths(false)
                .startAcceleration(false)
                .build()));
  }

  @Test
  @DisplayName("empty UTF-8 matches advance by Unicode scalar boundaries across engines")
  void emptyMatchesAdvanceByUnicodeScalarBoundariesAcrossEngines() {
    String regex = "(?<empty>)(?:é)?";
    String input = "😀éx";
    Pattern canonical = Pattern.compile(regex);
    Pattern nfa = Pattern.compile(regex, 0, canonicalFallback());

    assertThat(trace(nfa, input)).isEqualTo(trace(canonical, input));
    assertThat(trace(canonical, input))
        .extracting(match -> match.groups().getFirst().start())
        .containsExactly(0, 4, 6, 7);
  }

  @Test
  @DisplayName("malformed trusted input is equivalent across UTF-8 fallback engines")
  void malformedTrustedInputIsEquivalentAcrossFallbackEngines() {
    byte[] bytes = {'a', (byte) 0x80, (byte) 0xc2, 'b', (byte) 0xf0, (byte) 0x9f, 'c'};
    List<String> regexes = List.of(".", ".*?", "[^a]", "(?:a|.)+", "^.*$", "\\X");

    for (String regex : regexes) {
      Pattern canonical = Pattern.compile(regex);
      Pattern nfa = Pattern.compile(regex, 0, canonicalFallback());
      assertThat(trace(nfa, Utf8Input.trusted(bytes)))
          .as("malformed trusted input for /%s/", regex)
          .isEqualTo(trace(canonical, Utf8Input.trusted(bytes)));
    }
  }

  @Test
  @DisplayName("DFA caches do not mix UTF-8 input views")
  void dfaCachesDoNotMixInputViews() {
    Pattern pattern = Pattern.compile("(?<value>[\\p{L}]+)([0-9]+)");
    byte[] first = "padding-élan42-tail".getBytes(UTF_8);
    byte[] second = "padding-β7-tail".getBytes(UTF_8);

    assertThat(trace(pattern, Utf8Input.validated(first, 8, "élan42".getBytes(UTF_8).length)))
        .containsExactly(
            new MatchTrace(List.of(group("élan42", 0, 7), group("élan", 0, 5), group("42", 5, 7))));
    assertThat(trace(pattern, Utf8Input.validated(second, 8, "β7".getBytes(UTF_8).length)))
        .containsExactly(
            new MatchTrace(List.of(group("β7", 0, 3), group("β", 0, 2), group("7", 2, 3))));
  }

  @Test
  @DisplayName("pattern engine caches do not retain UTF-8 input views")
  void patternEngineCachesDoNotRetainUtf8InputViews() throws ReflectiveOperationException {
    Pattern pattern =
        Pattern.compile(
            "(?:é|éé)*x", 0, EnginePathOptions.builder().dfa(false).onePass(false).build());
    assertThat(pattern.matcher(Utf8Input.validated("ééx".getBytes(UTF_8))).find()).isTrue();

    BitState cached = pattern.borrowBitState();
    assertThat(cached).isNotNull();
    Field text = BitState.class.getDeclaredField("text");
    text.setAccessible(true);
    Field graphemeContext = BitState.class.getDeclaredField("graphemeContext");
    graphemeContext.setAccessible(true);
    assertThat(text.get(cached)).isNull();
    assertThat(graphemeContext.get(cached)).isNull();
    pattern.returnBitState(cached);
  }

  private static EnginePathOptions canonicalFallback() {
    return EnginePathOptions.builder()
        .literalFastPaths(false)
        .charClassMatchFastPaths(false)
        .keywordAlternationFastPath(false)
        .startAcceleration(false)
        .onePass(false)
        .dfa(false)
        .reverseDfa(false)
        .bitState(false)
        .lazyCaptureExtraction(false)
        .build();
  }

  private static List<MatchTrace> trace(Pattern pattern, String input) {
    return trace(pattern, Utf8Input.validated(input.getBytes(UTF_8)));
  }

  private static List<MatchTrace> trace(Pattern pattern, Utf8Input input) {
    ArrayUtf8Input array = (ArrayUtf8Input) input;
    Utf8Matcher matcher = pattern.matcher(input);
    List<MatchTrace> matches = new ArrayList<>();
    while (matcher.find()) {
      List<GroupTrace> groups = new ArrayList<>();
      for (int group = 0; group <= matcher.groupCount(); group++) {
        int start = matcher.start(group);
        int end = matcher.end(group);
        String value =
            start < 0
                ? null
                : new String(
                    array.scanner().bytes(), array.scanner().offset() + start, end - start, UTF_8);
        groups.add(group(value, start, end));
      }
      matches.add(new MatchTrace(groups));
    }
    return matches;
  }

  private static List<MatchTrace> stringOracleTrace(Pattern pattern, String input) {
    Matcher matcher = pattern.matcher(input);
    List<MatchTrace> matches = new ArrayList<>();
    while (matcher.find()) {
      List<GroupTrace> groups = new ArrayList<>();
      for (int group = 0; group <= matcher.groupCount(); group++) {
        int start = matcher.start(group);
        int end = matcher.end(group);
        groups.add(
            group(
                matcher.group(group),
                utf8Offset(input, start),
                end < 0 ? -1 : utf8Offset(input, end)));
      }
      matches.add(new MatchTrace(groups));
    }
    return matches;
  }

  private static int utf8Offset(String input, int utf16Offset) {
    return utf16Offset < 0 ? -1 : input.substring(0, utf16Offset).getBytes(UTF_8).length;
  }

  private static GroupTrace group(String value, int start, int end) {
    return new GroupTrace(value, start, end);
  }

  private record MatchTrace(List<GroupTrace> groups) {}

  private record GroupTrace(String value, int start, int end) {}
}
