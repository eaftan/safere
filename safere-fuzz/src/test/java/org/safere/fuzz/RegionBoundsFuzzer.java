// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.ArrayList;
import java.util.List;
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
          "[^a]",
          "[\\s\\S]",
          "[\\s\\S]*",
          "\\D",
          "\\p{Cs}",
          "\\P{Cs}",
          "[^\\p{Cs}]");

  private static final List<MixedGraphemeScalarPattern> MIXED_GRAPHEME_SCALAR_ATOMS =
      List.of(
          new MixedGraphemeScalarPattern("(?:\\X|.)", 2),
          new MixedGraphemeScalarPattern("(?:.|\\X)", 1),
          new MixedGraphemeScalarPattern("(?:\\X|[\\s\\S])", 2),
          new MixedGraphemeScalarPattern("(?:\\b{g}|.)", 0));

  private static final List<String> SCALAR_CONTEXTS = List.of("", "x", "\uD83D", "\uDC4D");

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
          new SafeReModelCase("\\X\\X", "\u0915\u094D\u0937", 0, 3, List.of("1-3")));

  @FuzzTest(maxDuration = "30s")
  void regionBounds(FuzzedDataProvider data) {
    fuzzerTestOneInput(data);
  }

  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
    compareGraphemeRegions();
    compareRegionLocalScalarModel(data);
    compareSplitSurrogateBoundaryModel(data);
    compareMixedGraphemeScalarModel(data);

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
