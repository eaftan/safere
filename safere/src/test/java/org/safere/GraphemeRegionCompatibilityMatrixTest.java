// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Focused differential matrix for SafeRE's supported grapheme-region contract. */
@DisabledForCrosscheck("differential matrix already compares SafeRE with java.util.regex")
class GraphemeRegionCompatibilityMatrixTest {

  @ParameterizedTest(name = "[{index}] {0} /{1}/")
  @MethodSource("supportedContractCases")
  @DisplayName("supported grapheme-region contract matches java.util.regex")
  void supportedGraphemeRegionContractMatchesJdk(Scenario scenario, String regex) {
    Trace safeTrace = safeTrace(regex, scenario);
    Trace jdkTrace = jdkTrace(regex, scenario);

    assertThat(safeTrace)
        .as(
            "%s%nInvariant: %s%nInput length=%s region=[%s,%s] transparent=%s anchoring=%s",
            scenario.name(),
            scenario.invariant(),
            scenario.input().length(),
            scenario.start(),
            scenario.end(),
            scenario.transparentBounds(),
            scenario.anchoringBounds())
        .isEqualTo(jdkTrace);
  }

  private static Stream<Arguments> supportedContractCases() {
    return scenarios().stream()
        .flatMap(
            scenario -> scenario.regexes().stream().map(regex -> Arguments.of(scenario, regex)));
  }

  private static List<Scenario> scenarios() {
    return List.of(
        new Scenario(
            "split trailing surrogate scalar completion",
            "\\X may complete the scalar for lookingAt/find, while matches() remains region-end"
                + " strict and ordinary atoms do not report half-code-point bounds.",
            "\uD83D\uDE00",
            0,
            1,
            false,
            true,
            List.of("\\X", "^\\X$", "\\X\\b{g}", "\\b{g}\\X", "(.)|(\\X)", "(\\X)|(.)")),
        new Scenario(
            "split trailing regional indicator uses completed boundary edge",
            "A trailing \\b{g} after \\X sees the effective grapheme consume end when an opaque"
                + " region ends inside a regional-indicator surrogate pair.",
            "\uD83C\uDDE6\uD83C\uDDE6",
            0,
            1,
            false,
            true,
            List.of("\\X\\b{g}", "\\b{g}\\X\\b{g}", "^\\X\\b{g}$")),
        new Scenario(
            "split trailing surrogate with non-anchoring bounds",
            "Non-anchoring bounds do not make region edges anchors, but text anchors can still"
                + " match when scalar completion reaches the real input end.",
            "\uD83D\uDE00",
            0,
            1,
            false,
            false,
            List.of("^\\X$", "\\X$", "\\A\\X\\z")),
        new Scenario(
            "interior non-anchoring bounds do not satisfy anchors",
            "With non-anchoring bounds, region edges are not anchor positions for ^ or $ even when"
                + " the consuming atom is a valid grapheme cluster.",
            "#a\u0301$",
            1,
            3,
            false,
            false,
            List.of("^\\X$", "\\X$")),
        new Scenario(
            "non-anchoring grapheme matches remain region bounded",
            "matches() still checks the logical matcher region when no end anchor needs full-text"
                + " context.",
            "#a$",
            1,
            2,
            false,
            false,
            List.of("\\X", "\\X+", "(\\X)", "\\b{g}?")),
        new Scenario(
            "non-anchoring bounds do not expose region-local line terminator anchors",
            "With non-anchoring bounds, $ must not accept a position before a line terminator"
                + " merely because that line terminator is inside the region.",
            "\r\u0000\n\n",
            0,
            3,
            false,
            false,
            List.of("^\\X\\X$", "\\X\\X$", "^(?:\\X){2}$", "^\\X{2}$")),
        new Scenario(
            "non-anchoring dollar sees final crlf across region edge",
            "With non-anchoring bounds, $ uses full-input trailing-line context, including a"
                + " final CRLF terminator split by the matcher region end.",
            "\r\r\r\n",
            0,
            3,
            false,
            false,
            List.of("^\\X\\X$", "\\X\\X$", "^(?:\\X){2}$", "^\\X{2}$")),
        new Scenario(
            "transparent non-anchoring dollar sees final crlf across region edge",
            "Transparent bounds do not change $ anchor context: with non-anchoring bounds, the"
                + " final CRLF terminator can still be split by the matcher region end.",
            "\r\r\r\n",
            0,
            3,
            true,
            false,
            List.of("^\\X\\X$", "\\X\\X$", "^(?:\\X){2}$", "^\\X{2}$")),
        new Scenario(
            "transparent low surrogate sees preceding high surrogate",
            "Transparent grapheme context may inspect outside the region, while candidate starts"
                + " remain inside the region.",
            "\uD83D\uDC4D\uD83C\uDFFB",
            1,
            3,
            true,
            true,
            List.of("\\X\\b{g}", "\\b{g}", "\\b{g}\\X\\b{g}")),
        new Scenario(
            "opaque low surrogate is region local",
            "Opaque bounds make the region edge a local grapheme context edge without adopting the"
                + " full-text cluster.",
            "\uD83D\uDC4D\uD83C\uDFFB",
            1,
            3,
            false,
            true,
            List.of("\\X", "\\X\\b{g}", "\\b{g}")),
        new Scenario(
            "opaque low surrogate before regional indicator keeps contextual boundary rules",
            "A region-local low surrogate can start consumption, but the following boundary must"
                + " still obey regional-indicator parity instead of becoming unconditional.",
            "\uD83C\uDDE6\uD83C\uDDE6",
            1,
            3,
            false,
            true,
            List.of("\\X\\b{g}")),
        new Scenario(
            "opaque non-surrogate cluster edge",
            "Opaque region edges are grapheme-boundary context edges even when they split a"
                + " base-plus-mark cluster.",
            "a\u0301b",
            1,
            2,
            false,
            true,
            List.of("\\b{g}", "\\b{g}\\X", "\\b{g}\\X\\b{g}")),
        new Scenario(
            "empty opaque region inside base-plus-mark cluster",
            "An empty opaque region inside a grapheme cluster is still a local boundary context for"
                + " zero-width grapheme predicates.",
            "a\u0301",
            1,
            1,
            false,
            true,
            List.of("\\b{g}", "(\\b{g})", "\\b{g}|a", "\\b{g}\\X?")),
        new Scenario(
            "empty transparent region inside base-plus-mark cluster",
            "An empty transparent region inside a grapheme cluster lets \\b{g} inspect the hidden"
                + " base and extender context.",
            "a\u0301",
            1,
            1,
            true,
            true,
            List.of("\\b{g}", "(\\b{g})", "\\b{g}|a", "\\b{g}\\X?")),
        new Scenario(
            "empty transparent region inside surrogate pair",
            "An empty transparent region inside a valid surrogate pair lets grapheme-boundary"
                + " predicates inspect both halves.",
            "\uD83D\uDE00",
            1,
            1,
            true,
            true,
            List.of("\\b{g}", "(\\b{g})", "\\b{g}|a")),
        new Scenario(
            "transparent trailing boundary sees hidden extender",
            "Transparent bounds let trailing grapheme-boundary predicates see a combining mark just"
                + " outside the region.",
            "a\u0301",
            0,
            1,
            true,
            true,
            List.of("\\X\\b{g}", "^\\X\\b{g}", "\\b{g}\\X\\b{g}")),
        new Scenario(
            "opaque regional-indicator parity starts at region",
            "Regional-indicator parity is relative to the active opaque grapheme context, not the"
                + " full input.",
            "\uD83C\uDDE6\uD83C\uDDE6\uD83C\uDDE6",
            2,
            6,
            false,
            true,
            List.of("\\X", "\\b{g}", "\\b{g}\\X\\b{g}")),
        new Scenario(
            "opaque crlf remains atomic at region edge",
            "CRLF segmentation remains atomic inside an opaque grapheme context.",
            "#\r\n$",
            1,
            3,
            false,
            true,
            List.of("\\r\\b{g}\\n", "\\X", "\\X\\b{g}")),
        new Scenario(
            "control characters remain standalone before extenders",
            "Control characters force grapheme boundaries before following combining marks.",
            "a\u0000\u0301",
            1,
            3,
            false,
            true,
            List.of("\\X", "\\X\\b{g}")),
        new Scenario(
            "transparent crlf boundary sees hidden line feed",
            "Transparent bounds let trailing grapheme-boundary predicates keep CRLF atomic across"
                + " the region edge.",
            "\r\n\u0301",
            0,
            1,
            true,
            true,
            List.of("\\X\\b{g}", "\\r\\b{g}\\n?")),
        new Scenario(
            "transparent hangul continuation sees suffix",
            "Transparent grapheme context lets \\X and \\b{g} see Hangul continuation state outside"
                + " the consumption range.",
            "\u1100\u1161",
            0,
            1,
            true,
            true,
            List.of("\\X\\b{g}")),
        new Scenario(
            "opaque region inside zwj sequence starts at edge",
            "Opaque bounds treat a region start inside a ZWJ sequence as a local grapheme"
                + " boundary.",
            "\uD83D\uDC69\u200D\uD83D\uDC69",
            2,
            5,
            false,
            true,
            List.of("\\b{g}")),
        new Scenario(
            "transparent prepend sees following cluster",
            "Transparent grapheme context lets a Prepend character stay attached to the following"
                + " hidden cluster.",
            "\u0600a",
            0,
            1,
            true,
            true,
            List.of("\\X\\b{g}", "\\b{g}\\X\\b{g}")),
        new Scenario(
            "opaque prepend edge is region local",
            "Opaque bounds let a region ending after a Prepend character form a local grapheme"
                + " cluster.",
            "\u0600a",
            0,
            1,
            false,
            true,
            List.of("\\X", "\\X\\b{g}", "\\b{g}\\X\\b{g}")),
        new Scenario(
            "transparent spacing mark sees preceding base",
            "Transparent grapheme context lets a SpacingMark stay attached to the hidden preceding"
                + " base.",
            "a\u0903",
            1,
            2,
            true,
            true,
            List.of("\\b{g}", "\\b{g}\\X", "\\X\\b{g}")),
        new Scenario(
            "indic conjunct remains one extended cluster",
            "Indic conjunct segmentation follows the extended grapheme cluster rules rather than"
                + " splitting at the virama linker.",
            "\u0915\u094D\u0937",
            0,
            3,
            false,
            true,
            List.of("\\X", "\\X+", "(\\X)+", "\\X\\b{g}", "\\b{g}\\X\\b{g}")),
        new Scenario(
            "greedy grapheme quantifiers preserve priority",
            "Repeated and quantified \\X atoms remain leftmost-first and greedy while preserving"
                + " capture bounds.",
            "a\u0301b",
            0,
            3,
            false,
            true,
            List.of("(\\X)+", "(\\X)+?", "(\\X)(\\X)", "\\X{2}", "a|\\X", "\\X|a")),
        new Scenario(
            "long end-anchored base-plus-mark input uses grapheme engine semantics",
            "Long inputs exercise optimized engine selection while preserving grapheme-aware end"
                + " anchors.",
            "a\u0301".repeat(1000) + "z",
            0,
            2001,
            false,
            true,
            List.of("\\Xz$", "\\X\\b{g}z$")),
        new Scenario(
            "long regional-indicator parity remains context relative",
            "Long regional-indicator runs keep bounded parity state across engine paths.",
            "\uD83C\uDDE6".repeat(1001),
            0,
            2002,
            false,
            true,
            List.of("\\X$")));
  }

  private static Trace safeTrace(String regex, Scenario scenario) {
    Pattern pattern = Pattern.compile(regex);
    Matcher matcher = configure(pattern.matcher(scenario.input()), scenario);
    int groupCount = matcher.groupCount();

    String matches =
        matchTrace(configure(pattern.matcher(scenario.input()), scenario), Operation.MATCHES);
    String lookingAt =
        matchTrace(configure(pattern.matcher(scenario.input()), scenario), Operation.LOOKING_AT);
    List<String> finds = findTrace(configure(pattern.matcher(scenario.input()), scenario));
    List<String> results = resultsTrace(configure(pattern.matcher(scenario.input()), scenario));
    List<String> resultsAfterFind =
        resultsAfterFindTrace(configure(pattern.matcher(scenario.input()), scenario));

    return new Trace(groupCount, matches, lookingAt, finds, results, resultsAfterFind);
  }

  private static Trace jdkTrace(String regex, Scenario scenario) {
    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
    java.util.regex.Matcher matcher = configure(pattern.matcher(scenario.input()), scenario);
    int groupCount = matcher.groupCount();

    String matches =
        matchTrace(configure(pattern.matcher(scenario.input()), scenario), Operation.MATCHES);
    String lookingAt =
        matchTrace(configure(pattern.matcher(scenario.input()), scenario), Operation.LOOKING_AT);
    List<String> finds = findTrace(configure(pattern.matcher(scenario.input()), scenario));
    List<String> results = resultsTrace(configure(pattern.matcher(scenario.input()), scenario));
    List<String> resultsAfterFind =
        resultsAfterFindTrace(configure(pattern.matcher(scenario.input()), scenario));

    return new Trace(groupCount, matches, lookingAt, finds, results, resultsAfterFind);
  }

  private static Matcher configure(Matcher matcher, Scenario scenario) {
    return matcher
        .region(scenario.start(), scenario.end())
        .useTransparentBounds(scenario.transparentBounds())
        .useAnchoringBounds(scenario.anchoringBounds());
  }

  private static java.util.regex.Matcher configure(
      java.util.regex.Matcher matcher, Scenario scenario) {
    return matcher
        .region(scenario.start(), scenario.end())
        .useTransparentBounds(scenario.transparentBounds())
        .useAnchoringBounds(scenario.anchoringBounds());
  }

  private static String matchTrace(Matcher matcher, Operation operation) {
    boolean matched =
        switch (operation) {
          case MATCHES -> matcher.matches();
          case LOOKING_AT -> matcher.lookingAt();
        };
    return matched ? capturedMatchTrace(matcher) : "NO_MATCH";
  }

  private static String matchTrace(java.util.regex.Matcher matcher, Operation operation) {
    boolean matched =
        switch (operation) {
          case MATCHES -> matcher.matches();
          case LOOKING_AT -> matcher.lookingAt();
        };
    return matched ? capturedMatchTrace(matcher) : "NO_MATCH";
  }

  private static List<String> findTrace(Matcher matcher) {
    List<String> trace = new ArrayList<>();
    while (matcher.find()) {
      trace.add(capturedMatchTrace(matcher));
    }
    return trace;
  }

  private static List<String> findTrace(java.util.regex.Matcher matcher) {
    List<String> trace = new ArrayList<>();
    while (matcher.find()) {
      trace.add(capturedMatchTrace(matcher));
    }
    return trace;
  }

  private static List<String> resultsTrace(Matcher matcher) {
    return matcher.results().map(GraphemeRegionCompatibilityMatrixTest::matchResultTrace).toList();
  }

  private static List<String> resultsTrace(java.util.regex.Matcher matcher) {
    return matcher.results().map(GraphemeRegionCompatibilityMatrixTest::matchResultTrace).toList();
  }

  private static List<String> resultsAfterFindTrace(Matcher matcher) {
    if (!matcher.find()) {
      return List.of();
    }
    return resultsTrace(matcher);
  }

  private static List<String> resultsAfterFindTrace(java.util.regex.Matcher matcher) {
    if (!matcher.find()) {
      return List.of();
    }
    return resultsTrace(matcher);
  }

  private static String capturedMatchTrace(Matcher matcher) {
    StringBuilder builder = new StringBuilder();
    builder.append(matcher.start()).append('-').append(matcher.end());
    for (int group = 0; group <= matcher.groupCount(); group++) {
      appendGroup(builder, group, matcher.start(group), matcher.end(group), matcher.group(group));
    }
    return builder.toString();
  }

  private static String matchResultTrace(MatchResult result) {
    StringBuilder builder = new StringBuilder();
    builder.append(result.start()).append('-').append(result.end());
    for (int group = 0; group <= result.groupCount(); group++) {
      appendGroup(builder, group, result.start(group), result.end(group), result.group(group));
    }
    return builder.toString();
  }

  private static String capturedMatchTrace(java.util.regex.Matcher matcher) {
    StringBuilder builder = new StringBuilder();
    builder.append(matcher.start()).append('-').append(matcher.end());
    for (int group = 0; group <= matcher.groupCount(); group++) {
      appendGroup(builder, group, matcher.start(group), matcher.end(group), matcher.group(group));
    }
    return builder.toString();
  }

  private static void appendGroup(
      StringBuilder builder, int group, int start, int end, String value) {
    builder
        .append(";g")
        .append(group)
        .append('=')
        .append(start)
        .append('-')
        .append(end)
        .append(':')
        .append(value == null ? "<null>" : value);
  }

  private record Scenario(
      String name,
      String invariant,
      String input,
      int start,
      int end,
      boolean transparentBounds,
      boolean anchoringBounds,
      List<String> regexes) {
    @Override
    public String toString() {
      return name;
    }
  }

  private record Trace(
      int groupCount,
      String matches,
      String lookingAt,
      List<String> finds,
      List<String> results,
      List<String> resultsAfterFind) {}

  private enum Operation {
    MATCHES,
    LOOKING_AT
  }
}
