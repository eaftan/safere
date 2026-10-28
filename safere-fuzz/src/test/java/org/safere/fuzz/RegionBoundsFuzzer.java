// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.safere.Matcher;
import org.safere.Pattern;

public final class RegionBoundsFuzzer {
  private record GraphemeRegion(String input, int start, int end) {}

  private record SafeReModelCase(
      String regex, String input, int start, int end, List<String> expectedFinds) {}

  private record MixedGraphemeScalarPattern(String regex, int firstMatchEndOffset) {}

  private static final List<GraphemeRegion> GRAPHEME_REGIONS =
      List.of(
          new GraphemeRegion("\uD83C\uDDE6".repeat(3), 2, 6),
          new GraphemeRegion("\uD83D\uDE00", 0, 1),
          new GraphemeRegion("e\u0301", 1, 2),
          new GraphemeRegion("a\u0301b", 1, 2),
          new GraphemeRegion("\u1100\u1161", 1, 2),
          new GraphemeRegion("\u0915\u094D\u0937", 0, 3));

  private static final List<String> GRAPHEME_REGION_REGEXES =
      List.of("\\X", "^\\X$", "\\b{g}", "\\b{g}\\X", "\\X\\b{g}", "\\b{g}\\X\\b{g}");

  private static final List<GraphemeRegion> SPLIT_REGIONAL_GRAPHEME_REGIONS =
      List.of(
          new GraphemeRegion("\uD83C\uDDE6\uD83C\uDDE6", 0, 1),
          new GraphemeRegion("\uD83C\uDDE6\uD83C\uDDE6", 1, 3));

  private static final List<String> SPLIT_REGIONAL_GRAPHEME_REGEXES =
      List.of("\\X", "\\b{g}", "\\X\\b{g}");

  private static final List<String> REGION_LOCAL_SCALAR_ATOMS =
      List.of(
          ".",
          ".*",
          ".+",
          "(.)",
          "(.+)",
          "([^a]+)",
          "[^a]",
          "[\\s\\S]",
          "[\\s\\S]*",
          "\\D",
          "\\p{Cs}",
          "\\P{Cs}",
          "[^\\p{Cs}]");

  private static final List<MixedGraphemeScalarPattern> MIXED_GRAPHEME_SCALAR_ATOMS =
      List.of(
          new MixedGraphemeScalarPattern("(?:\\X|.)", 1),
          new MixedGraphemeScalarPattern("(?:.|\\X)", 1),
          new MixedGraphemeScalarPattern("(?:\\X|[\\s\\S])", 1),
          new MixedGraphemeScalarPattern("(?:\\b{g}|.)", 0));

  private static final List<String> SCALAR_CONTEXTS = List.of("", "x", "\uD83D", "\uDC4D");

  private static final List<String> OPAQUE_CONSUMERS =
      List.of(
          "",
          ".",
          "(.)?",
          "(.)*",
          "[\\s\\S]",
          "\\p{Cs}",
          "\\P{Cs}",
          "\\uD83D",
          "(\\uDE00)",
          "a\\uD83D",
          "\\X",
          "(\\X)*",
          "(\\X)|(.)",
          "(.)|(\\X)");
  private static final List<String> OPAQUE_LOCAL_ASSERTIONS =
      List.of("", "\\b", "\\B", "(?U:\\b)", "(?U:\\B)", "\\b{g}");
  private static final List<String> OPAQUE_RIGHT_ATOMS = List.of("", ".", "(.)?", "a?");

  // Same bounded pattern grammar as RegionAssertionCompositionTest. Fuzzing extends its curated
  // corpus by concatenating Unicode/context fragments and choosing arbitrary regions within them.
  private static final List<String> ASSERTION_LEFT_ATOMS =
      List.of(".", "(.)?", "(.)*", "(.)+?", "[\\s\\S]", "\\p{Cs}", "\\P{Cs}");
  private static final List<String> ASSERTION_PREDICATES =
      List.of(
          "\\b",
          "\\B",
          "(?U:\\b)",
          "(?U:\\B)",
          "\\b{g}",
          "^",
          "$",
          "\\A",
          "\\z",
          "\\Z",
          "(?m:^)",
          "(?m:$)",
          "(?dm:$)",
          "\\b{g}$",
          "(?U:\\b)\\b{g}",
          "\\b{g}\\b{g}");
  private static final List<String> ASSERTION_RIGHT_ATOMS = List.of("", ".", "(.)?");
  private static final List<String> ASSERTION_INPUT_FRAGMENTS =
      List.of(
          "a",
          "b",
          " ",
          "\r",
          "\u0085",
          "\n",
          "\uD83D\uDC4D",
          "\uD83D\uDE00",
          "\uD801\uDC00",
          "\uD83C\uDDE6",
          "a\u0301",
          "\uD83D\uDC69\u200D\uD83D\uDC69",
          "\u1100\u1161",
          "\u0915\u094D\u0937",
          "\uD83D",
          "\uDC4D");

  private static final List<GraphemeRegion> TRANSPARENT_GRAPHEME_CONTEXT_REGIONS =
      List.of(new GraphemeRegion("\uD83D\uDC4D\uD83C\uDFFB", 1, 3));

  private static final List<String> TRANSPARENT_GRAPHEME_CONTEXT_REGEXES = List.of("\\X\\b{g}");

  private static final List<GraphemeRegion> NON_ANCHORING_FINAL_CRLF_GRAPHEME_REGIONS =
      List.of(new GraphemeRegion("\r\r\r\n", 0, 3));

  private static final List<String> NON_ANCHORING_FINAL_CRLF_GRAPHEME_REGEXES =
      List.of("^\\X\\X$", "\\X\\X$", "^(?:\\X){2}$", "^\\X{2}$");

  private static final List<SafeReModelCase> SAFE_RE_GRAPHEME_MODEL_CASES =
      List.of(
          new SafeReModelCase("\\b{g}\\X\\b{g}", "ab", 0, 2, List.of("0-1", "1-2")),
          new SafeReModelCase(
              "\\X\\b{g}", "\uD83D\uDC69\u200D\uD83D\uDC69", 2, 5, List.of("2-3", "3-5")),
          new SafeReModelCase(".\\b{g}.", "\uD83C\uDDE6".repeat(2), 1, 3, List.of("1-3")),
          new SafeReModelCase(
              "(?:.\\b{g}.)|(?:\\Xz)", "\uD83C\uDDE6".repeat(2), 1, 3, List.of("1-3")),
          new SafeReModelCase("\\X\\X", "\u0915\u094D\u0937", 0, 3, List.of("1-3")));

  @FuzzTest(maxDuration = "30s")
  void regionBounds(FuzzedDataProvider data) {
    fuzzerTestOneInput(data);
  }

  @FuzzTest(maxDuration = "30s")
  void assertionComposition(FuzzedDataProvider data) {
    compareGeneratedAssertionComposition(data);
  }

  @FuzzTest(maxDuration = "30s")
  void opaqueSubstring(FuzzedDataProvider data) {
    boolean anchoring = data.consumeBoolean();
    String regex =
        data.pickValue(OPAQUE_CONSUMERS)
            + data.pickValue(anchoring ? ASSERTION_PREDICATES : OPAQUE_LOCAL_ASSERTIONS)
            + data.pickValue(OPAQUE_RIGHT_ATOMS);
    StringBuilder builder = new StringBuilder();
    int fragments = data.consumeInt(0, 8);
    for (int i = 0; i < fragments; i++) {
      builder.append(data.pickValue(ASSERTION_INPUT_FRAGMENTS));
    }
    String input = builder.toString();
    int start = data.consumeInt(0, input.length());
    int end = data.consumeInt(start, input.length());
    Pattern pattern = Pattern.compile(regex);
    for (int operation = 0; operation < 3; operation++) {
      Matcher region = pattern.matcher(input).region(start, end).useAnchoringBounds(anchoring);
      Matcher substring = pattern.matcher(input.substring(start, end));
      List<String> expected = opaqueTrace(substring, operation, 0, input.length());
      List<String> actual = opaqueTrace(region, operation, start, input.length());
      if (!actual.equals(expected)) {
        throw new AssertionError(
            "Opaque substring mismatch: "
                + regex
                + " input="
                + input.codePoints().mapToObj(Integer::toHexString).toList()
                + " region="
                + start
                + ":"
                + end
                + " anchoring="
                + anchoring
                + " operation="
                + operation
                + " expected="
                + expected
                + " actual="
                + actual);
      }
    }
  }

  private static List<String> opaqueTrace(
      Matcher matcher, int operation, int offset, int inputLength) {
    List<String> trace = new ArrayList<>();
    for (int step = 0; step <= inputLength + 1; step++) {
      boolean matched =
          switch (operation) {
            case 0 -> matcher.matches();
            case 1 -> matcher.lookingAt();
            default -> matcher.find();
          };
      trace.add(Boolean.toString(matched));
      if (matched) {
        for (int group = 0; group <= matcher.groupCount(); group++) {
          int start = matcher.start(group);
          int end = matcher.end(group);
          trace.add((start < 0 ? start : start - offset) + ":" + (end < 0 ? end : end - offset));
          trace.add(matcher.group(group));
        }
      }
      if (!matched || operation != 2) {
        return trace;
      }
    }
    throw new AssertionError("Opaque substring search did not exhaust: " + matcher.pattern());
  }

  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
    compareGraphemeRegions();
    compareLineAnchorPaths(data);

    compareRegionLocalScalarModel(data);
    compareSplitSurrogateBoundaryModel(data);
    compareMixedGraphemeScalarModel(data);
    compareMixedAssertionModel(data);
    compareGeneratedAssertionComposition(data);

    String regex = data.consumeString(256);
    int flags = FuzzSupport.consumeFlags(data);
    String input = data.consumeString(2048);
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, flags);
    if (pattern == null) {
      return;
    }

    FuzzSupport.MatcherPair matcher = pattern.matcher(input);
    int[] region = FuzzSupport.consumeRegion(data, input);
    matcher.region(region[0], region[1]);
    matcher.useAnchoringBounds(data.consumeBoolean());
    matcher.useTransparentBounds(data.consumeBoolean());
    matcher.regionStart();
    matcher.regionEnd();
    matcher.hasAnchoringBounds();
    matcher.hasTransparentBounds();
    matcher.find();
    matcher.reset();
    matcher.region(region[0], region[1]);
    matcher.matches();
    matcher.reset();
    matcher.region(region[0], region[1]);
    matcher.lookingAt();
  }

  private static void compareLineAnchorPaths(FuzzedDataProvider data) {
    for (String prefix : List.of("", "(.)?", "([\\s\\S])?", "([\\s\\S])")) {
      for (String anchor : List.of("$", "\\Z", "(?m:$)", "(?m:^)", "(?dm:^)")) {
        compareLineAnchorPaths(prefix + anchor, "\r\na", 1, 2, true, false);
        compareLineAnchorPaths(prefix + anchor, "\na", 0, 1, true, false);
      }
    }
    String terminator = data.pickValue(List.of("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029"));
    String input = "a\r" + terminator + "a".repeat(data.consumeInt(0, 4));
    int start = data.consumeInt(0, 2);
    int end = data.consumeInt(start, input.length());
    String prefix =
        data.pickValue(List.of("", "(.)?", "([\\s\\S])?", "([\\s\\S])*?", "([\\s\\S])"));
    String anchor =
        data.pickValue(
            List.of("^", "(?m:^)", "(?dm:^)", "$", "\\Z", "(?m:$)", "(?dm:$)", "(?d:\\Z)"));
    compareLineAnchorPaths(
        prefix + anchor, input, start, end, data.consumeBoolean(), data.consumeBoolean());
  }

  private static void compareLineAnchorPaths(
      String regex, String input, int start, int end, boolean anchoring, boolean transparent) {
    Matcher scalar =
        Pattern.compile(regex)
            .matcher(input)
            .region(start, end)
            .useAnchoringBounds(anchoring)
            .useTransparentBounds(transparent);
    Matcher mixed =
        Pattern.compile("(?:" + regex + ")|(?:\\Xz)")
            .matcher(input)
            .region(start, end)
            .useAnchoringBounds(anchoring)
            .useTransparentBounds(transparent);
    // Engine equivalence is also the oracle for the approved opaque CRLF divergence.
    for (boolean find : List.of(false, true)) {
      scalar.region(start, end);
      mixed.region(start, end);
      boolean found;
      do {
        found = find ? scalar.find() : scalar.lookingAt();
        boolean mixedFound = find ? mixed.find() : mixed.lookingAt();
        if (found != mixedFound
            || (found && !lineAnchorCaptures(scalar).equals(lineAnchorCaptures(mixed)))) {
          throw new AssertionError(
              "Line anchor engine mismatch: regex="
                  + regex
                  + " region=["
                  + start
                  + ","
                  + end
                  + "] input="
                  + input
                  + " anchoring="
                  + anchoring
                  + " transparent="
                  + transparent
                  + " find="
                  + find);
        }
      } while (find && found);
    }
  }

  private static List<String> lineAnchorCaptures(Matcher matcher) {
    List<String> captures = new ArrayList<>();
    for (int group = 0; group <= matcher.groupCount(); group++) {
      captures.add(matcher.start(group) + "-" + matcher.end(group) + ":" + matcher.group(group));
    }
    return captures;
  }

  private static void compareRegionLocalScalarModel(FuzzedDataProvider data) {
    String regex =
        REGION_LOCAL_SCALAR_ATOMS.get(data.consumeInt(0, REGION_LOCAL_SCALAR_ATOMS.size() - 1));
    String prefix = SCALAR_CONTEXTS.get(data.consumeInt(0, SCALAR_CONTEXTS.size() - 1));
    String suffix = SCALAR_CONTEXTS.get(data.consumeInt(0, SCALAR_CONTEXTS.size() - 1));
    String exposedSurrogate = data.consumeBoolean() ? "\uD83D" : "\uDC4D";
    String input = prefix + exposedSurrogate + suffix;
    int start = prefix.length();
    int end = start + 1;
    boolean expected = !regex.equals("\\P{Cs}") && !regex.equals("[^\\p{Cs}]");
    Matcher matcher =
        Pattern.compile(regex)
            .matcher(input)
            .region(start, end)
            .useTransparentBounds(data.consumeBoolean());
    if (matcher.matches() != expected) {
      throw new AssertionError("Region-local scalar matches mismatch: " + regex);
    }
    matcher.reset(input).region(start, end);
    if (matcher.find() != expected) {
      throw new AssertionError("Region-local scalar find mismatch: " + regex);
    }
    if (expected && (matcher.start() != start || matcher.end() != end)) {
      throw new AssertionError("Region-local scalar match crossed the region: " + regex);
    }
    if (expected) {
      for (int group = 1; group <= matcher.groupCount(); group++) {
        if (matcher.start(group) != start
            || matcher.end(group) != end
            || !exposedSurrogate.equals(matcher.group(group))) {
          throw new AssertionError("Region-local scalar capture mismatch: " + regex);
        }
      }
    }
  }

  private static void compareSplitSurrogateBoundaryModel(FuzzedDataProvider data) {
    String prefix = SCALAR_CONTEXTS.get(data.consumeInt(0, SCALAR_CONTEXTS.size() - 1));
    String suffix = SCALAR_CONTEXTS.get(data.consumeInt(0, SCALAR_CONTEXTS.size() - 1));
    String input = prefix + "\uD801\uDC00" + suffix;
    int start = prefix.length();
    String regex = data.consumeBoolean() ? "(?U)\\b." : "(?U)\\B.";
    boolean expected = regex.equals("(?U)\\B.");
    if (Pattern.compile(regex).matcher(input).region(start, start + 1).matches() != expected) {
      throw new AssertionError("Region-local Unicode word boundary mismatch: " + regex);
    }
  }

  private static void compareMixedGraphemeScalarModel(FuzzedDataProvider data) {
    MixedGraphemeScalarPattern pattern =
        MIXED_GRAPHEME_SCALAR_ATOMS.get(data.consumeInt(0, MIXED_GRAPHEME_SCALAR_ATOMS.size() - 1));
    String prefix = SCALAR_CONTEXTS.get(data.consumeInt(0, SCALAR_CONTEXTS.size() - 1));
    String suffix = SCALAR_CONTEXTS.get(data.consumeInt(0, SCALAR_CONTEXTS.size() - 1));
    String input = prefix + "\uD83D\uDC4D" + suffix;
    int start = prefix.length();
    Pattern compiled = Pattern.compile(pattern.regex());
    if (!compiled.matcher(input).region(start, start + 1).matches()) {
      throw new AssertionError(
          "Mixed grapheme/scalar full match crossed region: " + pattern.regex());
    }
    for (boolean find : new boolean[] {false, true}) {
      Matcher matcher = compiled.matcher(input).region(start, start + 1);
      if (!(find ? matcher.find() : matcher.lookingAt())
          || matcher.start() != start
          || matcher.end() != start + pattern.firstMatchEndOffset()) {
        throw new AssertionError("Mixed grapheme/scalar priority mismatch: " + pattern.regex());
      }
    }
  }

  private static void compareMixedAssertionModel(FuzzedDataProvider data) {
    String prefix = (data.consumeBoolean() ? "a" : " ").repeat(data.consumeInt(0, 8));
    String boundary = data.consumeBoolean() ? "(?U:\\b)" : "(?U:\\B)";
    String scalar =
        data.consumeBoolean()
            ? boundary + "."
            : (data.consumeBoolean() ? "(.)\\b{g}" : "\\b{g}(.)\\b{g}");
    boolean interior = data.consumeBoolean();
    String regex = interior ? "(.)\\b{g}(.)" : Pattern.quote(prefix) + scalar;
    Pattern ordinary = Pattern.compile(regex);
    // No z occurs inside this region, so adding this alternative cannot create a match.
    Pattern mixed =
        Pattern.compile(
            data.consumeBoolean() ? "(?:" + regex + ")|(?:\\Xz)" : "(?:\\Xz)|(?:" + regex + ")");
    String suffix = SCALAR_CONTEXTS.get(data.consumeInt(0, SCALAR_CONTEXTS.size() - 1));
    String pair =
        List.of("\uD801\uDC00", "\uD83D\uDC4D", "\uD83C\uDDE6").get(data.consumeInt(0, 2));
    String input = prefix + pair.repeat(interior ? 2 : 1) + suffix;
    int start = interior ? prefix.length() + 1 : 0;
    int end = prefix.length() + (interior ? 3 : 1);
    for (boolean transparent : new boolean[] {false, true}) {
      for (int operation = 0; operation < 3; operation++) {
        Matcher expected =
            ordinary.matcher(input).region(start, end).useTransparentBounds(transparent);
        Matcher actual = mixed.matcher(input).region(start, end).useTransparentBounds(transparent);
        boolean expectedMatch =
            switch (operation) {
              case 0 -> expected.matches();
              case 1 -> expected.lookingAt();
              default -> expected.find();
            };
        boolean actualMatch =
            switch (operation) {
              case 0 -> actual.matches();
              case 1 -> actual.lookingAt();
              default -> actual.find();
            };
        if (actualMatch != expectedMatch
            || (actualMatch
                && (actual.start() != expected.start() || actual.end() != expected.end()))) {
          throw new AssertionError("Unreachable grapheme branch changed assertion: " + regex);
        }
        if (actualMatch) {
          for (int group = 1; group <= actual.groupCount(); group++) {
            if (actual.start(group) != expected.start(group)
                || actual.end(group) != expected.end(group)) {
              throw new AssertionError("Unreachable grapheme branch changed capture: " + regex);
            }
          }
        }
      }
    }
  }

  private static void compareGeneratedAssertionComposition(FuzzedDataProvider data) {
    String left = ASSERTION_LEFT_ATOMS.get(data.consumeInt(0, ASSERTION_LEFT_ATOMS.size() - 1));
    String assertion =
        ASSERTION_PREDICATES.get(data.consumeInt(0, ASSERTION_PREDICATES.size() - 1));
    String right = ASSERTION_RIGHT_ATOMS.get(data.consumeInt(0, ASSERTION_RIGHT_ATOMS.size() - 1));
    String regex = left + assertion + right;
    StringBuilder inputBuilder = new StringBuilder();
    int fragments = data.consumeInt(0, 8);
    for (int i = 0; i < fragments; i++) {
      inputBuilder.append(
          ASSERTION_INPUT_FRAGMENTS.get(data.consumeInt(0, ASSERTION_INPUT_FRAGMENTS.size() - 1)));
    }
    String input = inputBuilder.toString();
    int start = data.consumeInt(0, input.length());
    int end = data.consumeInt(start, input.length());
    boolean transparent = data.consumeBoolean();
    boolean anchoring = data.consumeBoolean();
    Pattern ordinary = Pattern.compile(regex);
    Pattern appended = Pattern.compile("(?:" + regex + ")|(?:\\Xz)");
    Pattern prepended = Pattern.compile("(?:\\Xz)|(?:" + regex + ")");
    String context =
        regex
            + " input="
            + input.codePoints().mapToObj(Integer::toHexString).toList()
            + " region="
            + start
            + ":"
            + end
            + " transparent="
            + transparent
            + " anchoring="
            + anchoring;
    for (boolean full : new boolean[] {false, true}) {
      Matcher expected = assertionMatcher(ordinary, input, start, end, transparent, anchoring);
      boolean matched = full ? expected.matches() : expected.lookingAt();
      for (Pattern mixed : List.of(appended, prepended)) {
        Matcher actual = assertionMatcher(mixed, input, start, end, transparent, anchoring);
        boolean actualMatch = full ? actual.matches() : actual.lookingAt();
        if (matched != actualMatch) {
          throw new AssertionError(
              "Unreachable branch changed anchored assertion: "
                  + context
                  + " full="
                  + full
                  + " mixed="
                  + mixed);
        }
        if (matched) {
          if (actual.groupCount() != expected.groupCount()) {
            throw new AssertionError("Unreachable branch changed group count: " + context);
          }
          for (int group = 0; group <= expected.groupCount(); group++) {
            if (expected.start(group) != actual.start(group)
                || expected.end(group) != actual.end(group)
                || !Objects.equals(expected.group(group), actual.group(group))) {
              throw new AssertionError(
                  "Unreachable branch changed assertion capture: "
                      + context
                      + " full="
                      + full
                      + " mixed="
                      + mixed
                      + " group="
                      + group);
            }
          }
        }
      }
    }
    // Supplementary find() candidate policies are checked separately by explicit model cases.
    if (input.codePoints().allMatch(Character::isBmpCodePoint)) {
      List<String> expected =
          assertionFindTrace(
              assertionMatcher(ordinary, input, start, end, transparent, anchoring),
              input.length());
      for (Pattern mixed : List.of(appended, prepended)) {
        List<String> actual =
            assertionFindTrace(
                assertionMatcher(mixed, input, start, end, transparent, anchoring), input.length());
        if (!actual.equals(expected)) {
          throw new AssertionError(
              "Unreachable branch changed assertion find sequence: "
                  + context
                  + " mixed="
                  + mixed
                  + " expected="
                  + expected
                  + " actual="
                  + actual);
        }
      }
    }
  }

  private static Matcher assertionMatcher(
      Pattern pattern, String input, int start, int end, boolean transparent, boolean anchoring) {
    return pattern
        .matcher(input)
        .region(start, end)
        .useTransparentBounds(transparent)
        .useAnchoringBounds(anchoring);
  }

  private static List<String> assertionFindTrace(Matcher matcher, int inputLength) {
    List<String> trace = new ArrayList<>();
    for (int step = 0; step <= inputLength + 1; step++) {
      if (!matcher.find()) {
        trace.add("false");
        return trace;
      }
      for (int group = 0; group <= matcher.groupCount(); group++) {
        trace.add(matcher.start(group) + ":" + matcher.end(group));
      }
    }
    throw new AssertionError("Assertion find sequence did not exhaust: " + matcher.pattern());
  }

  private static void compareGraphemeRegions() {
    compareSafeReGraphemeModelCases();
    for (String regex : GRAPHEME_REGION_REGEXES) {
      FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, 0);
      if (pattern == null) {
        continue;
      }
      for (GraphemeRegion region : GRAPHEME_REGIONS) {
        compareGraphemeRegion(pattern, region, false);
        compareGraphemeRegion(pattern, region, true);
      }
    }
    for (String regex : SPLIT_REGIONAL_GRAPHEME_REGEXES) {
      FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, 0);
      if (pattern == null) {
        continue;
      }
      for (GraphemeRegion region : SPLIT_REGIONAL_GRAPHEME_REGIONS) {
        compareGraphemeRegion(pattern, region, false);
        compareGraphemeRegion(pattern, region, true);
      }
    }
    for (String regex : TRANSPARENT_GRAPHEME_CONTEXT_REGEXES) {
      FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, 0);
      if (pattern == null) {
        continue;
      }
      for (GraphemeRegion region : TRANSPARENT_GRAPHEME_CONTEXT_REGIONS) {
        compareGraphemeRegion(pattern, region, false);
        compareGraphemeRegion(pattern, region, true);
      }
    }
    for (String regex : NON_ANCHORING_FINAL_CRLF_GRAPHEME_REGEXES) {
      FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, 0);
      if (pattern == null) {
        continue;
      }
      for (GraphemeRegion region : NON_ANCHORING_FINAL_CRLF_GRAPHEME_REGIONS) {
        compareNonAnchoringGraphemeRegion(pattern, region, false);
        compareNonAnchoringGraphemeRegion(pattern, region, true);
      }
    }
  }

  private static void compareSafeReGraphemeModelCases() {
    for (SafeReModelCase testCase : SAFE_RE_GRAPHEME_MODEL_CASES) {
      Matcher matcher =
          Pattern.compile(testCase.regex())
              .matcher(testCase.input())
              .region(testCase.start(), testCase.end());
      List<String> actual = new ArrayList<>();
      while (matcher.find()) {
        actual.add(matcher.start() + "-" + matcher.end());
      }
      if (!actual.equals(testCase.expectedFinds())) {
        throw new AssertionError(
            "SafeRE grapheme model mismatch"
                + "\nRegex: "
                + testCase.regex()
                + "\nRegion: ["
                + testCase.start()
                + ","
                + testCase.end()
                + "]"
                + "\nExpected: "
                + testCase.expectedFinds()
                + "\nActual: "
                + actual);
      }
    }
  }

  private static void compareGraphemeRegion(
      FuzzSupport.CompiledPattern pattern, GraphemeRegion region, boolean transparentBounds) {
    if (!transparentBounds) {
      for (int operation = 0; operation < 3; operation++) {
        Matcher actual =
            pattern.safeRePattern().matcher(region.input()).region(region.start(), region.end());
        Matcher expected =
            pattern.safeRePattern().matcher(region.input().substring(region.start(), region.end()));
        if (!opaqueTrace(actual, operation, region.start(), region.input().length())
            .equals(opaqueTrace(expected, operation, 0, region.input().length()))) {
          throw new AssertionError(
              "Opaque grapheme region differs from substring: " + pattern.regex());
        }
      }
      return;
    }
    if (region.end() > 0
        && region.end() < region.input().length()
        && Character.isHighSurrogate(region.input().charAt(region.end() - 1))
        && Character.isLowSurrogate(region.input().charAt(region.end()))) {
      // JDK-8394894: the JDK can consume beyond this region. Its answers are not a
      // consumption oracle here. The explicit unit matrix checks transparent assertion results;
      // this matrix checks confinement for all operations, captures, and successive searches.
      for (int operation = 0; operation < 3; operation++) {
        Matcher bounded =
            pattern
                .safeRePattern()
                .matcher(region.input())
                .region(region.start(), region.end())
                .useTransparentBounds(true);
        boolean matched;
        do {
          matched =
              switch (operation) {
                case 0 -> bounded.matches();
                case 1 -> bounded.lookingAt();
                default -> bounded.find();
              };
          if (matched) {
            for (int group = 0; group <= bounded.groupCount(); group++) {
              if (bounded.start(group) >= 0
                  && (bounded.start(group) < region.start() || bounded.end(group) > region.end())) {
                throw new AssertionError(
                    "Transparent match escaped its region: " + pattern.regex());
              }
            }
          }
        } while (operation == 2 && matched);
      }
      return;
    }
    FuzzSupport.MatcherPair matcher = pattern.matcher(region.input());
    matcher.region(region.start(), region.end()).useTransparentBounds(transparentBounds);
    while (matcher.find()) {
      // Walk the whole sequence so extra or missing zero-width boundaries are compared.
    }
    matcher.reset().region(region.start(), region.end()).useTransparentBounds(transparentBounds);
    matcher.matches();
    matcher.reset().region(region.start(), region.end()).useTransparentBounds(transparentBounds);
    matcher.lookingAt();
  }

  private static void compareNonAnchoringGraphemeRegion(
      FuzzSupport.CompiledPattern pattern, GraphemeRegion region, boolean transparentBounds) {
    FuzzSupport.MatcherPair matcher = pattern.matcher(region.input());
    matcher
        .region(region.start(), region.end())
        .useAnchoringBounds(false)
        .useTransparentBounds(transparentBounds);
    while (matcher.find()) {
      // Walk the whole sequence so extra or missing zero-width boundaries are compared.
    }
    matcher
        .reset()
        .region(region.start(), region.end())
        .useAnchoringBounds(false)
        .useTransparentBounds(transparentBounds);
    matcher.matches();
    matcher
        .reset()
        .region(region.start(), region.end())
        .useAnchoringBounds(false)
        .useTransparentBounds(transparentBounds);
    matcher.lookingAt();
  }
}
