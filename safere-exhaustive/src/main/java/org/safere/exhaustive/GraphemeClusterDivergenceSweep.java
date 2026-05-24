// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.PatternSyntaxException;

/** Offline differential sweep for {@code \X} and {@code \b{g}} grapheme-cluster bugs. */
public final class GraphemeClusterDivergenceSweep {
  private static final int FIND_LIMIT = 32;
  private static final int FIRST_UNKNOWN_LIMIT = 100;
  private static final int UNKNOWN_STRATIFIED_SAMPLE_LIMIT = 100_000;
  private static final int ACTIONABLE_SAMPLE_LIMIT = 100;
  private static final long DEFAULT_PROGRESS_INTERVAL = 10_000_000;
  private static final ConcurrentMap<String, java.util.regex.Pattern> JDK_PATTERN_CACHE =
      new ConcurrentHashMap<>();
  private static final ConcurrentMap<String, org.safere.Pattern> SAFERE_PATTERN_CACHE =
      new ConcurrentHashMap<>();

  private static final List<GraphemeAtom> GRAPHEME_CLASS_ATOMS =
      List.of(
          atom("CR", "\r"),
          atom("LF", "\n"),
          atom("Control", "\u0000"),
          atom("Extend", "\u0301"),
          atom("Extend2", "\u0327"),
          atom("ZWJ", "\u200D"),
          atom("RegionalIndicator", "\uD83C\uDDFA"),
          atom("Prepend", "\u0600"),
          atom("SpacingMark", "\u0903"),
          atom("HangulL", "\u1100"),
          atom("HangulV", "\u1161"),
          atom("HangulT", "\u11A8"),
          atom("HangulLV", "\uAC00"),
          atom("HangulLVT", "\uAC01"),
          atom("EmojiModifier", "\uD83C\uDFFD"),
          atom("ExtendedPictographic", "\uD83D\uDC69"),
          atom("OtherBmp", "a"),
          atom("OtherSupplementary", "\uD83D\uDE00"),
          atom("HighSurrogate", "\uD83D"),
          atom("LowSurrogate", "\uDE00"));

  private static final List<GraphemeAtom> HIGH_RISK_GRAPHEME_CLASS_ATOMS =
      List.of(
          atom("CR", "\r"),
          atom("LF", "\n"),
          atom("Control", "\u0000"),
          atom("Extend", "\u0301"),
          atom("ZWJ", "\u200D"),
          atom("RegionalIndicator", "\uD83C\uDDFA"),
          atom("Prepend", "\u0600"),
          atom("SpacingMark", "\u0903"),
          atom("HangulL", "\u1100"),
          atom("HangulV", "\u1161"),
          atom("HangulT", "\u11A8"),
          atom("ExtendedPictographic", "\uD83D\uDC69"),
          atom("OtherBmp", "a"),
          atom("LowSurrogate", "\uDE00"));

  private static final List<GraphemeAtom> HIGH_RISK_LONG_GRAPHEME_CLASS_ATOMS =
      List.of(
          atom("Extend", "\u0301"),
          atom("ZWJ", "\u200D"),
          atom("RegionalIndicator", "\uD83C\uDDFA"),
          atom("ExtendedPictographic", "\uD83D\uDC69"),
          atom("OtherBmp", "a"),
          atom("LowSurrogate", "\uDE00"));

  private static final List<RegexTemplate> REGEX_TEMPLATES =
      List.of(
          regex("oneCluster", "\\X"),
          regex("twoClusters", "\\X\\X"),
          regex("anchoredTwoClusters", "^\\X\\X"),
          regex("anchoredOptionalCaretTwoClusters", "^\\^?\\X\\X"),
          regex("exactTwoClusters", "^\\X\\X$"),
          regex("twoClusterRepeat", "\\X{2}"),
          regex("anchoredTwoClusterRepeat", "^\\X{2}"),
          regex("exactTwoClusterRepeat", "^\\X{2}$"),
          regex("nonCapturingTwoClusters", "(?:\\X)(?:\\X)"),
          regex("anchoredNonCapturingTwoClusters", "^(?:\\X)(?:\\X)"),
          regex("exactNonCapturingTwoClusters", "^(?:\\X)(?:\\X)$"),
          regex("oneRepeatThenCluster", "\\X{1}\\X"),
          regex("anchoredOneRepeatThenCluster", "^\\X{1}\\X"),
          regex("exactOneRepeatThenCluster", "^\\X{1}\\X$"),
          regex("nonCapturingClusterRepeat", "(?:\\X){2}"),
          regex("anchoredNonCapturingClusterRepeat", "^(?:\\X){2}"),
          regex("exactNonCapturingClusterRepeat", "^(?:\\X){2}$"),
          regex("capturedCluster", "(\\X)"),
          regex("capturedTwoClusters", "(\\X)(\\X)"),
          regex("anchoredCapturedTwoClusters", "^(\\X)(\\X)"),
          regex("clusterPlus", "\\X+"),
          regex("anchoredClusterPlus", "^\\X+"),
          regex("invalidClusterInClass", "[\\X]"),
          regex("boundary", "\\b{g}"),
          regex("capturedBoundary", "(\\b{g})"),
          regex("optionalBoundary", "\\b{g}?"),
          regex("invalidBoundaryInClass", "[\\b{g}]"),
          regex("boundaryBetweenBaseAndMark", "a\\b{g}\\u0300"),
          regex("nonCapturingBaseBoundaryMark", "(?:a)\\b{g}\\u0300"),
          regex("capturedBaseBoundaryMark", "(a)\\b{g}\\u0300"),
          regex("baseNonCapturingBoundaryMark", "a(?:\\b{g})\\u0300"),
          regex("escapedBaseBoundaryEscapedMark", "\\u0061\\b{g}\\u0300"),
          regex("boundaryAroundBaseMark", "\\b{g}a\\u0300\\b{g}"),
          regex("boundaryBetweenCrLf", "\\r\\b{g}\\n"),
          regex("nonCapturingCrBoundaryLf", "(?:\\r)\\b{g}\\n"),
          regex("capturedCrBoundaryLf", "(\\r)\\b{g}\\n"),
          regex("crNonCapturingBoundaryLf", "\\r(?:\\b{g})\\n"),
          regex("escapedCrBoundaryEscapedLf", "\\u000D\\b{g}\\u000A"),
          regex("clusterThenBoundary", "\\X\\b{g}"),
          regex("anchoredClusterThenBoundary", "^\\X\\b{g}"),
          regex("boundaryClusterBoundary", "\\b{g}\\X\\b{g}"),
          regex("anchoredBoundaryClusterBoundary", "^\\b{g}\\X\\b{g}"),
          regex("boundaryOnlyAlternativeAfterAscii", "a|\\b{g}"),
          regex("boundaryOnlyAlternativeBeforeAscii", "\\b{g}|a"),
          regex("groupedBoundaryOnlyAlternativeAfterAscii", "(?:a|\\b{g})"),
          regex("groupedBoundaryOnlyAlternativeBeforeAscii", "(?:\\b{g}|a)"),
          regex("capturedBoundaryOnlyAlternativeAfterAscii", "(a)|(\\b{g})"),
          regex("capturedBoundaryOnlyAlternativeBeforeAscii", "(\\b{g})|(a)"),
          regex("nestedBoundaryOnlyAlternativeAfterAscii", "(?:a|(?:\\b{g}))"),
          regex("nestedBoundaryOnlyAlternativeBeforeAscii", "(?:(?:\\b{g})|a)"),
          regex("boundaryOnlyAlternativeAfterCluster", "\\X|\\b{g}"),
          regex("boundaryOnlyAlternativeBeforeCluster", "\\b{g}|\\X"),
          regex("groupedBoundaryOnlyAlternativeAfterCluster", "(?:\\X|\\b{g})"),
          regex("groupedBoundaryOnlyAlternativeBeforeCluster", "(?:\\b{g}|\\X)"),
          regex("capturedBoundaryOnlyAlternativeAfterCluster", "(\\X)|(\\b{g})"),
          regex("capturedBoundaryOnlyAlternativeBeforeCluster", "(\\b{g})|(\\X)"),
          regex("boundaryClusterAlternativeAfterLiteral", "b|\\b{g}\\X"),
          regex("boundaryClusterAlternativeBeforeLiteral", "\\b{g}\\X|b"),
          regex("groupedBoundaryClusterAlternativeAfterLiteral", "(?:b|\\b{g}\\X)"),
          regex("groupedBoundaryClusterAlternativeBeforeLiteral", "(?:\\b{g}\\X|b)"),
          regex("capturedBoundaryClusterAlternativeAfterLiteral", "(b)|(\\b{g}\\X)"),
          regex("capturedBoundaryClusterAlternativeBeforeLiteral", "(\\b{g}\\X)|(b)"),
          regex("boundaryClusterAlternativeAfterCluster", "\\X|\\b{g}\\X"),
          regex("boundaryClusterAlternativeBeforeCluster", "\\b{g}\\X|\\X"),
          regex("groupedBoundaryClusterAlternativeAfterCluster", "(?:\\X|\\b{g}\\X)"),
          regex("groupedBoundaryClusterAlternativeBeforeCluster", "(?:\\b{g}\\X|\\X)"),
          regex("boundaryClusterAlternativeAfterAscii", "a|\\b{g}\\X"),
          regex("boundaryClusterAlternativeBeforeAscii", "\\b{g}\\X|a"),
          regex("nestedBoundaryClusterAlternativeAfterLiteral", "(?:a|(?:\\b{g}\\X))"),
          regex("nestedBoundaryClusterAlternativeBeforeLiteral", "(?:(?:\\b{g}\\X)|a)"));

  private static final InputSpace INPUT_SPACE = buildInputSpace();
  private static final long INPUT_CASES = INPUT_SPACE.size();

  private static final List<RegionMode> REGION_MODES =
      List.of(
          region("full", "", "", 0, 0),
          region("wrapped", "#", "$", 0, 0),
          region("prefixed", "zz", "", 0, 0),
          region("insideSupplementaryPrefix", "\uD83D", "\uDE00", 0, 0),
          region("afterBaseBeforeMark", "a", "\u0300", 0, 0),
          fixedRegion("emptyInsideSupplementaryPrefix", "\uD83D", "\uDE00", 1, 1),
          fixedRegion("lowSurrogateOnlyPrefix", "\uD83D\uDE00", "", 1, 2),
          region("startAtLowSurrogatePrefix", "\uD83D", "", 0, 0),
          region("startAtLowSurrogateBeforeExtend", "\uD83D", "\u0301", 0, 0),
          region("startAtLowSurrogateBeforeZwj", "\uD83D", "\u200D", 0, 0),
          region("startAtLowSurrogateBeforeSpacingMark", "\uD83D", "\u0903", 0, 0),
          region("startAtLowSurrogateBeforeEmojiModifier", "\uD83D", "\uD83C\uDFFD", 0, 0),
          region("startAtLowSurrogateBeforeLowSurrogate", "\uD83D", "\uDE00", 0, 0),
          region("endBeforeSuffixExtend", "", "\u0301", 0, 0),
          region("endBeforeSuffixZwj", "", "\u200D", 0, 0),
          region("endBeforeSuffixSpacingMark", "", "\u0903", 0, 0),
          region("endBeforeSuffixEmojiModifier", "", "\uD83C\uDFFD", 0, 0),
          region("endBeforeSuffixRegionalIndicator", "", "\uD83C\uDDFA", 0, 0),
          region("endBeforeSuffixHangulL", "", "\u1100", 0, 0),
          region("endBeforeSuffixHangulV", "", "\u1161", 0, 0),
          region("endBeforeSuffixHangulT", "", "\u11A8", 0, 0),
          region("endBeforeSuffixLf", "", "\n", 0, 0),
          region("endBeforeSuffixExtendedPictographic", "", "\uD83D\uDC69", 0, 0),
          region("endBeforeSuffixLowSurrogate", "", "\uDE00", 0, 0),
          region("bothEndsSplitSurrogates", "\uD83D", "\uDE00", 0, 0));

  private static final List<BoundsMode> BOUNDS_MODES =
      List.of(
          bounds("opaqueAnchoring", false, true),
          bounds("opaqueNonAnchoring", false, false),
          bounds("transparentAnchoring", true, true),
          bounds("transparentNonAnchoring", true, false));

  private static final List<OperationMode> OPERATION_MODES =
      // Matcher.find() is specified in terms of the first find in a region and previous successful
      // find() invocations. Keep this sweep focused on those specified find sequences rather than
      // implementation-specific matcher state after matches() or lookingAt().
      List.of(
          operation(
              "freshTrace",
              GraphemeClusterDivergenceSweep::freshTrace,
              GraphemeClusterDivergenceSweep::freshTrace),
          operation(
              "resetRegionReuse",
              GraphemeClusterDivergenceSweep::resetRegionReuseTrace,
              GraphemeClusterDivergenceSweep::resetRegionReuseTrace));

  private static final List<TargetedRegionInput> TARGETED_REGION_INPUTS =
      List.of(
          targetedRegion("splitTrailingRegionalBeforeRegional", "\uD83C\uDDE6\uD83C\uDDE6", 0, 1),
          targetedRegion("splitLeadingRegionalBeforeRegional", "\uD83C\uDDE6\uD83C\uDDE6", 1, 3),
          targetedRegion(
              "splitLeadingRegionalBeforeRegionalFullSecond", "\uD83C\uDDE6\uD83C\uDDE6", 1, 4),
          targetedRegion(
              "splitTrailingRegionalTriple", "\uD83C\uDDE6\uD83C\uDDE6\uD83C\uDDE6", 0, 1),
          targetedRegion(
              "splitLeadingRegionalTriple", "\uD83C\uDDE6\uD83C\uDDE6\uD83C\uDDE6", 1, 5),
          targetedRegion("splitTrailingZwjEmoji", "\uD83D\uDC69\u200D\uD83D\uDC69", 0, 1),
          targetedRegion("splitLeadingZwjEmoji", "\uD83D\uDC69\u200D\uD83D\uDC69", 1, 5),
          targetedRegion("splitLeadingZwjEmojiSplitSecond", "\uD83D\uDC69\u200D\uD83D\uDC69", 1, 4),
          targetedRegion("splitTrailingEmojiModifier", "\uD83D\uDC4D\uD83C\uDFFB", 0, 1),
          targetedRegion("splitLeadingEmojiModifier", "\uD83D\uDC4D\uD83C\uDFFB", 1, 3),
          targetedRegion("splitLeadingEmojiModifierFullModifier", "\uD83D\uDC4D\uD83C\uDFFB", 1, 4),
          targetedRegion("splitTrailingSupplementaryBeforeExtend", "\uD83D\uDE00\u0301", 0, 1),
          targetedRegion("splitLeadingSupplementaryBeforeExtend", "\uD83D\uDE00\u0301", 1, 3),
          targetedRegion(
              "splitTrailingSupplementaryBeforeZwjEmoji", "\uD83D\uDE00\u200D\uD83D\uDE00", 0, 1),
          targetedRegion(
              "splitLeadingSupplementaryBeforeZwjEmoji", "\uD83D\uDE00\u200D\uD83D\uDE00", 1, 5),
          targetedRegion(
              "splitLeadingSupplementaryBeforeZwjEmojiSplitSecond",
              "\uD83D\uDE00\u200D\uD83D\uDE00",
              1,
              4),
          targetedRegion("indicConjunctFullRegion", "\u0915\u094D\u0937", 0, 3),
          targetedRegion("indicConjunctAfterFirstConsonant", "\u0915\u094D\u0937", 1, 3));

  private static final List<String> GRAPHEME_CANDIDATE_START_REGEX_LABELS =
      List.of(
          "twoClusters",
          "twoClusterRepeat",
          "nonCapturingTwoClusters",
          "oneRepeatThenCluster",
          "nonCapturingClusterRepeat",
          "capturedTwoClusters");

  private static final List<CaseSpec> TARGETED_REGION_CASES = buildTargetedRegionCases();

  private GraphemeClusterDivergenceSweep() {}

  public static void main(String[] args) throws IOException {
    GraphemeSweepOptions options = parseOptions(args);
    Files.createDirectories(options.sweep().outputDir());
    Files.deleteIfExists(options.sweep().jsonlPath());
    options.printStartup();

    if (options.sweep().replayFile() != null) {
      runReplay(options);
      return;
    }

    GraphemeRunResult result = runSweep(options);

    result.summary().writeReports(options.sweep().outputDir());
    System.out.println("checked=" + result.checked());
    System.out.println("generated=" + result.generated());
    System.out.println("totalCases=" + totalCases());
    System.out.println("inputCases=" + INPUT_CASES);
    System.out.println("inputFamilies=" + INPUT_SPACE.familySummary());
    System.out.println("divergences=" + result.divergences());
    System.out.println("actionableDivergences=" + result.summary().actionableCount());
    System.out.println("unknownDivergences=" + result.summary().count(DivergenceClass.UNKNOWN));
    System.out.println("threads=" + options.sweep().threads());
    System.out.println("jsonl=" + options.sweep().jsonlPath());
  }

  private static GraphemeSweepOptions parseOptions(String[] args) {
    List<String> sweepArgs = new ArrayList<>();
    int unknownStratifiedSamples = UNKNOWN_STRATIFIED_SAMPLE_LIMIT;
    for (String arg : args) {
      if (arg.startsWith("--unknown-stratified-samples=")) {
        unknownStratifiedSamples =
            Integer.parseInt(arg.substring("--unknown-stratified-samples=".length()));
      } else {
        sweepArgs.add(arg);
      }
    }
    if (unknownStratifiedSamples < 0) {
      throw new IllegalArgumentException("--unknown-stratified-samples must be non-negative");
    }
    SweepOptions sweepOptions =
        SweepOptions.parse(
            sweepArgs.toArray(String[]::new),
            Path.of("target", "exhaustive-reports", "grapheme-cluster-sweep"),
            "grapheme-cluster-divergences.jsonl",
            DEFAULT_PROGRESS_INTERVAL);
    return new GraphemeSweepOptions(sweepOptions, unknownStratifiedSamples);
  }

  private static GraphemeRunResult runSweep(GraphemeSweepOptions options) throws IOException {
    SweepOptions sweepOptions = options.sweep();
    long selectedCaseCount = sweepOptions.totalChecks(totalCases());
    StratifiedUnknownSampler unknownSampler =
        new StratifiedUnknownSampler(
            sweepOptions.rangeStartInclusive(),
            selectedCaseCount,
            options.unknownStratifiedSamples());
    GraphemeDivergenceSummary[] workerSummaries =
        new GraphemeDivergenceSummary[sweepOptions.threads()];
    try (SweepRunState runState = new SweepRunState(sweepOptions, selectedCaseCount)) {
      SweepWorkers.run(
          sweepOptions.threads(),
          "grapheme-cluster-sweep-",
          workerIndex -> {
            GraphemeDivergenceSummary workerSummary = new GraphemeDivergenceSummary(unknownSampler);
            SweepState worker = new SweepState(runState, workerSummary, workerIndex);
            worker.run();
            worker.finish();
            workerSummaries[workerIndex] = workerSummary;
          });
      GraphemeDivergenceSummary summary = new GraphemeDivergenceSummary(unknownSampler);
      for (GraphemeDivergenceSummary workerSummary : workerSummaries) {
        summary.merge(workerSummary);
      }
      return new GraphemeRunResult(
          runState.checked.sum(), runState.generated, runState.divergences.sum(), summary);
    }
  }

  private static void runReplay(GraphemeSweepOptions options) throws IOException {
    SweepOptions sweepOptions = options.sweep();
    StratifiedUnknownSampler unknownSampler =
        new StratifiedUnknownSampler(
            0, Math.max(1, options.unknownStratifiedSamples()), options.unknownStratifiedSamples());
    GraphemeDivergenceSummary summary = new GraphemeDivergenceSummary(unknownSampler);
    AtomicLong replayCaseIndex = new AtomicLong();
    try (BufferedReader reader =
            Files.newBufferedReader(sweepOptions.replayFile(), StandardCharsets.UTF_8);
        SweepRunState runState = new SweepRunState(sweepOptions, 0)) {
      long generated =
          SweepWorkers.runStreamingLines(
              sweepOptions.threads(),
              "grapheme-cluster-replay-",
              reader,
              line -> {
                runState.checked.increment();
                evaluateCase(
                    runState, summary, replayCase(line), replayCaseIndex.getAndIncrement());
              });
      runState.recordGenerated(generated);
      summary.writeReports(sweepOptions.outputDir());
      System.out.println("checked=" + runState.checked.sum());
      System.out.println("generated=" + runState.generated);
      System.out.println("divergences=" + runState.divergences.sum());
      System.out.println("actionableDivergences=" + summary.actionableCount());
      System.out.println("unknownDivergences=" + summary.count(DivergenceClass.UNKNOWN));
      System.out.println("threads=" + sweepOptions.threads());
      System.out.println("jsonl=" + sweepOptions.jsonlPath());
      if (summary.actionableCount() > 0) {
        throw new IllegalStateException(
            "replay found " + summary.actionableCount() + " actionable behavioral divergences");
      }
    }
  }

  private static long totalCases() {
    return cartesianCases() + TARGETED_REGION_CASES.size();
  }

  private static CaseSpec caseAt(long index) {
    long cartesianCases = cartesianCases();
    if (index >= cartesianCases) {
      return TARGETED_REGION_CASES.get(Math.toIntExact(index - cartesianCases));
    }
    int operationIndex = (int) (index % OPERATION_MODES.size());
    index /= OPERATION_MODES.size();
    int boundsIndex = (int) (index % BOUNDS_MODES.size());
    index /= BOUNDS_MODES.size();
    int regionIndex = (int) (index % REGION_MODES.size());
    index /= REGION_MODES.size();
    long inputIndex = index % INPUT_CASES;
    index /= INPUT_CASES;
    int regexIndex = (int) index;
    return new CaseSpec(
        REGEX_TEMPLATES.get(regexIndex),
        INPUT_SPACE.inputAt(inputIndex),
        REGION_MODES.get(regionIndex),
        BOUNDS_MODES.get(boundsIndex),
        OPERATION_MODES.get(operationIndex));
  }

  private static long cartesianCases() {
    return (long) REGEX_TEMPLATES.size()
        * INPUT_CASES
        * REGION_MODES.size()
        * BOUNDS_MODES.size()
        * OPERATION_MODES.size();
  }

  private static Outcome jdkOutcome(CaseSpec spec) {
    try {
      java.util.regex.Pattern pattern =
          JDK_PATTERN_CACHE.computeIfAbsent(spec.regex(), java.util.regex.Pattern::compile);
      return new Outcome(true, operationTrace(pattern, spec), "");
    } catch (PatternSyntaxException e) {
      return new Outcome(false, "", e.getDescription());
    } catch (RuntimeException e) {
      return new Outcome(false, "", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static Outcome safeReOutcome(CaseSpec spec) {
    try {
      org.safere.Pattern pattern =
          SAFERE_PATTERN_CACHE.computeIfAbsent(spec.regex(), org.safere.Pattern::compile);
      return new Outcome(true, operationTrace(pattern, spec), "");
    } catch (PatternSyntaxException e) {
      return new Outcome(false, "", e.getDescription());
    } catch (RuntimeException e) {
      return new Outcome(false, "", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static String operationTrace(java.util.regex.Pattern pattern, CaseSpec spec) {
    return spec.operationMode().jdkTrace().trace(pattern, spec);
  }

  private static String operationTrace(org.safere.Pattern pattern, CaseSpec spec) {
    return spec.operationMode().safeReTrace().trace(pattern, spec);
  }

  private static String freshTrace(java.util.regex.Pattern pattern, CaseSpec spec) {
    StringBuilder result = new StringBuilder();
    String text = spec.text();
    int start = spec.regionStart();
    int end = spec.regionEnd();
    java.util.regex.Matcher matcher = configure(pattern.matcher(text).region(start, end), spec);
    result.append("matches=").append(matchResult(matcher.matches(), matcher));
    matcher = configure(matcher.reset(text).region(start, end), spec);
    result.append(";lookingAt=").append(matchResult(matcher.lookingAt(), matcher));
    matcher = configure(matcher.reset(text).region(start, end), spec);
    result.append(";find=");
    appendFindTrace(result, matcher);
    return result.toString();
  }

  private static String freshTrace(org.safere.Pattern pattern, CaseSpec spec) {
    StringBuilder result = new StringBuilder();
    String text = spec.text();
    int start = spec.regionStart();
    int end = spec.regionEnd();
    org.safere.Matcher matcher = configure(pattern.matcher(text).region(start, end), spec);
    result.append("matches=").append(matchResult(matcher.matches(), matcher));
    matcher = configure(matcher.reset(text).region(start, end), spec);
    result.append(";lookingAt=").append(matchResult(matcher.lookingAt(), matcher));
    matcher = configure(matcher.reset(text).region(start, end), spec);
    result.append(";find=");
    appendFindTrace(result, matcher);
    return result.toString();
  }

  private static String resetRegionReuseTrace(java.util.regex.Pattern pattern, CaseSpec spec) {
    java.util.regex.Matcher matcher =
        configure(pattern.matcher(spec.text()).region(spec.regionStart(), spec.regionEnd()), spec);
    String first = findTrace(matcher);
    matcher =
        configure(matcher.reset(spec.text()).region(spec.regionStart(), spec.regionEnd()), spec);
    return "firstFind=" + first + ";afterResetFind=" + findTrace(matcher);
  }

  private static String resetRegionReuseTrace(org.safere.Pattern pattern, CaseSpec spec) {
    org.safere.Matcher matcher =
        configure(pattern.matcher(spec.text()).region(spec.regionStart(), spec.regionEnd()), spec);
    String first = findTrace(matcher);
    matcher =
        configure(matcher.reset(spec.text()).region(spec.regionStart(), spec.regionEnd()), spec);
    return "firstFind=" + first + ";afterResetFind=" + findTrace(matcher);
  }

  private static java.util.regex.Matcher configure(java.util.regex.Matcher matcher, CaseSpec spec) {
    return matcher
        .useTransparentBounds(spec.boundsMode().transparentBounds())
        .useAnchoringBounds(spec.boundsMode().anchoringBounds());
  }

  private static org.safere.Matcher configure(org.safere.Matcher matcher, CaseSpec spec) {
    return matcher
        .useTransparentBounds(spec.boundsMode().transparentBounds())
        .useAnchoringBounds(spec.boundsMode().anchoringBounds());
  }

  private static String matchResult(boolean matched, java.util.regex.Matcher matcher) {
    if (!matched) {
      return "false";
    }
    return "true@" + matchSpan(matcher);
  }

  private static String matchResult(boolean matched, org.safere.Matcher matcher) {
    if (!matched) {
      return "false";
    }
    return "true@" + matchSpan(matcher);
  }

  private static void appendFindTrace(StringBuilder result, java.util.regex.Matcher matcher) {
    int count = 0;
    while (matcher.find()) {
      if (count > 0) {
        result.append('|');
      }
      result.append(matchSpan(matcher));
      if (++count >= FIND_LIMIT) {
        result.append("|...");
        return;
      }
    }
    if (count == 0) {
      result.append("false");
    }
  }

  private static String findTrace(java.util.regex.Matcher matcher) {
    StringBuilder result = new StringBuilder();
    appendFindTrace(result, matcher);
    return result.toString();
  }

  private static void appendFindTrace(StringBuilder result, org.safere.Matcher matcher) {
    int count = 0;
    while (matcher.find()) {
      if (count > 0) {
        result.append('|');
      }
      result.append(matchSpan(matcher));
      if (++count >= FIND_LIMIT) {
        result.append("|...");
        return;
      }
    }
    if (count == 0) {
      result.append("false");
    }
  }

  private static String findTrace(org.safere.Matcher matcher) {
    StringBuilder result = new StringBuilder();
    appendFindTrace(result, matcher);
    return result.toString();
  }

  private static String matchSpan(java.util.regex.Matcher matcher) {
    return matchSpan(matcher.start(), matcher.end(), matcher.groupCount(), matcher::group);
  }

  private static String matchSpan(org.safere.Matcher matcher) {
    return matchSpan(matcher.start(), matcher.end(), matcher.groupCount(), matcher::group);
  }

  private static String matchSpan(int start, int end, int groupCount, GroupValue groupValue) {
    StringBuilder result = new StringBuilder();
    result.append(start).append('-').append(end).append(':').append(escape(groupValue.group(0)));
    for (int i = 1; i <= groupCount; i++) {
      result.append(':');
      String group = groupValue.group(i);
      result.append(group == null ? "<null>" : escape(group));
    }
    return result.toString();
  }

  private static CaseSpec replayCase(String line) {
    var object = SweepJson.parseObject(line);
    var caseObject = SweepJson.object(object, "case");
    String regex = SweepJson.string(caseObject, "regex");
    String regexLabel = SweepJson.string(caseObject, "regexLabel");
    String input = SweepJson.string(caseObject, "input");
    String inputLabel = SweepJson.string(caseObject, "inputLabel");
    String regionLabel = SweepJson.string(caseObject, "region");
    String prefix = SweepJson.string(caseObject, "prefix");
    String suffix = SweepJson.string(caseObject, "suffix");
    int regionStart = SweepJson.integer(caseObject, "regionStart");
    int regionEnd = SweepJson.integer(caseObject, "regionEnd");
    return new CaseSpec(
        new RegexTemplate(regexLabel, regex),
        new InputTemplate(inputLabel, input),
        new RegionMode(regionLabel, prefix, suffix, 0, 0, regionStart, regionEnd),
        replayBoundsMode(SweepJson.string(caseObject, "bounds")),
        operationMode(SweepJson.string(caseObject, "operation")));
  }

  private static String bucketFor(CaseSpec spec, Outcome jdk, Outcome safere) {
    String kind = jdk.accepted() != safere.accepted() ? "compile" : "behavior";
    return String.join(
        "|",
        "kind=" + kind,
        "direction=" + direction(jdk, safere),
        "regex=" + spec.regexTemplate().label(),
        "input=" + spec.inputTemplate().label(),
        "region=" + spec.regionMode().label(),
        "bounds=" + spec.boundsMode().label(),
        "operation=" + spec.operationMode().label());
  }

  private static String direction(Outcome jdk, Outcome safere) {
    if (jdk.accepted() && !safere.accepted()) {
      return "jdk-accepts";
    }
    if (!jdk.accepted() && safere.accepted()) {
      return "safere-accepts";
    }
    return "trace";
  }

  private static boolean semanticallyEqual(Outcome left, Outcome right) {
    if (left.accepted() != right.accepted()) {
      return false;
    }
    return !left.accepted() || left.trace().equals(right.trace());
  }

  private static DivergenceClass classifyDivergence(CaseSpec spec, Outcome jdk, Outcome safere) {
    String regexLabel = spec.regexTemplate().label();
    if (isNonAnchoringDollarRegionEnd(spec)) {
      return DivergenceClass.NON_ANCHORING_DOLLAR_REGION_END;
    }
    if (isGraphemeCandidateStartDedup(spec)) {
      return DivergenceClass.GRAPHEME_CANDIDATE_START_DEDUP;
    }
    if (regexLabel.equals("boundaryClusterBoundary")
        || regexLabel.equals("anchoredBoundaryClusterBoundary")) {
      return DivergenceClass.BOUNDARY_CLUSTER_BOUNDARY_COMPOSITION;
    }
    if (regexLabel.contains("BoundaryOnlyAlternative")
        || regexLabel.contains("boundaryOnlyAlternative")) {
      return DivergenceClass.BOUNDARY_ONLY_ALTERNATIVE_FIND_CURSOR;
    }
    if (regexLabel.contains("BoundaryClusterAlternative")
        || regexLabel.contains("boundaryClusterAlternative")) {
      return DivergenceClass.BOUNDARY_CLUSTER_ALTERNATIVE_FIND_CURSOR;
    }
    if (regexLabel.equals("clusterThenBoundary")
        || regexLabel.equals("anchoredClusterThenBoundary")) {
      return DivergenceClass.REGION_LOCAL_CONTINUATION_CLUSTER;
    }
    if (regexLabel.equals("boundary") || regexLabel.equals("capturedBoundary")) {
      return DivergenceClass.TRANSPARENT_BOUNDARY_JDK_DETAIL;
    }
    return DivergenceClass.UNKNOWN;
  }

  private static boolean isNonAnchoringDollarRegionEnd(CaseSpec spec) {
    return !spec.boundsMode().anchoringBounds()
        && spec.regex().endsWith("$")
        && spec.regionEnd() < spec.text().length();
  }

  private static boolean isGraphemeCandidateStartDedup(CaseSpec spec) {
    return GRAPHEME_CANDIDATE_START_REGEX_LABELS.contains(spec.regexTemplate().label())
        && spec.inputTemplate().label().startsWith("indicConjunctLinkerZwj");
  }

  private static String escape(String value) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> result.append("\\\\");
        case '\n' -> result.append("\\n");
        case '\t' -> result.append("\\t");
        case '\r' -> result.append("\\r");
        case '"' -> result.append("\\\"");
        default -> {
          if (Character.isISOControl(c) || Character.isSurrogate(c)) {
            result.append(String.format("\\u%04X", (int) c));
          } else {
            result.append(c);
          }
        }
      }
    }
    return result.toString();
  }

  private static RegexTemplate regex(String label, String regex) {
    return new RegexTemplate(label, regex);
  }

  private static InputTemplate input(String label, String input) {
    return new InputTemplate(label, input);
  }

  private static GraphemeAtom atom(String label, String input) {
    return new GraphemeAtom(label, input);
  }

  private static RegionMode region(
      String label, String prefix, String suffix, int startAdjustment, int endAdjustment) {
    return new RegionMode(label, prefix, suffix, startAdjustment, endAdjustment, null, null);
  }

  private static RegionMode fixedRegion(
      String label, String prefix, String suffix, int regionStart, int regionEnd) {
    return new RegionMode(label, prefix, suffix, 0, 0, regionStart, regionEnd);
  }

  private static BoundsMode bounds(
      String label, boolean transparentBounds, boolean anchoringBounds) {
    return new BoundsMode(label, transparentBounds, anchoringBounds);
  }

  private static OperationMode operation(String label, JdkTrace jdkTrace, SafeReTrace safeReTrace) {
    return new OperationMode(label, jdkTrace, safeReTrace);
  }

  private static TargetedRegionInput targetedRegion(
      String label, String text, int regionStart, int regionEnd) {
    return new TargetedRegionInput(label, text, regionStart, regionEnd);
  }

  private static List<CaseSpec> buildTargetedRegionCases() {
    List<CaseSpec> cases = new ArrayList<>();
    for (RegexTemplate regex : REGEX_TEMPLATES) {
      for (TargetedRegionInput input : TARGETED_REGION_INPUTS) {
        InputTemplate inputTemplate = input(input.label(), input.text());
        RegionMode region =
            fixedRegion(input.label(), "", "", input.regionStart(), input.regionEnd());
        for (BoundsMode bounds : BOUNDS_MODES) {
          for (OperationMode operation : OPERATION_MODES) {
            cases.add(new CaseSpec(regex, inputTemplate, region, bounds, operation));
          }
        }
      }
    }
    return List.copyOf(cases);
  }

  private static BoundsMode boundsMode(String label) {
    return BOUNDS_MODES.stream()
        .filter(mode -> mode.label().equals(label))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown bounds mode: " + label));
  }

  private static BoundsMode replayBoundsMode(String label) {
    return switch (label) {
      case "transparentAnchoring" -> bounds("transparentAnchoring", true, true);
      case "transparentNonAnchoring" -> bounds("transparentNonAnchoring", true, false);
      default -> boundsMode(label);
    };
  }

  private static OperationMode operationMode(String label) {
    return OPERATION_MODES.stream()
        .filter(mode -> mode.label().equals(label))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown operation mode: " + label));
  }

  private static InputSpace buildInputSpace() {
    List<InputFamily> families = new ArrayList<>();
    families.add(new ExplicitInputFamily("curated", buildCuratedInputs()));
    for (int length = 1; length <= 4; length++) {
      families.add(
          new SequenceInputFamily("allGcbClassLength" + length, GRAPHEME_CLASS_ATOMS, length));
    }
    families.add(
        new SequenceInputFamily("highRiskGcbClassLength5", HIGH_RISK_GRAPHEME_CLASS_ATOMS, 5));
    families.add(
        new SequenceInputFamily(
            "highRiskLongGcbClassLength6", HIGH_RISK_LONG_GRAPHEME_CLASS_ATOMS, 6));
    families.add(new ExplicitInputFamily("targetedLong", buildTargetedLongInputs()));
    families.add(
        new ExplicitInputFamily("indicConjunctLinkerZwj", buildIndicConjunctLinkerZwjInputs()));
    return new InputSpace(families);
  }

  private static List<InputTemplate> buildCuratedInputs() {
    Map<String, InputTemplate> inputs = new LinkedHashMap<>();
    addInput(inputs, input("empty", ""));
    addInput(inputs, input("ascii", "a"));
    addInput(inputs, input("twoAscii", "ab"));
    addInput(inputs, input("baseMark", "a\u0300"));
    addInput(inputs, input("baseExtend", "e\u0301"));
    addInput(inputs, input("baseExtendAscii", "e\u0301a"));
    addInput(inputs, input("leadingExtend", "\u0301"));
    addInput(inputs, input("twoLeadingExtends", "\u0301\u0301"));
    addInput(inputs, input("leadingExtendsThenBase", "\u0301\u0301a"));
    addInput(inputs, input("longLeadingExtendsThenBase", "\u0301".repeat(44) + "a".repeat(8)));
    addInput(inputs, input("crlf", "\r\n"));
    addInput(inputs, input("prependBase", "\u0600a"));
    addInput(inputs, input("hangulJamo", "\u1100\u1161"));
    addInput(inputs, input("hangulSyllableTail", "\uAC00\u11A8"));
    addInput(inputs, input("regionalPair", "\uD83C\uDDFA\uD83C\uDDF8"));
    addInput(inputs, input("regionalTriple", "\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDE8"));
    addInput(inputs, input("regionalQuad", "\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDE8\uD83C\uDDE6"));
    addInput(inputs, input("emojiModifier", "\uD83D\uDC4D\uD83C\uDFFD"));
    addInput(inputs, input("emojiModifierThenAscii", "\uD83D\uDC4D\uD83C\uDFFDa"));
    addInput(inputs, input("zwjEmoji", "\uD83D\uDC69\u200D\uD83D\uDCBB"));
    addInput(inputs, input("lowSurrogateZwj", "\uDC69\u200D\uD83D\uDCBB"));
    addInput(inputs, input("zwjEmojiModifier", "\uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDCBB"));
    addInput(inputs, input("zwjEmojiThenAscii", "\uD83D\uDC69\u200D\uD83D\uDCBBa"));
    addInput(inputs, input("hangulLeadingVowel", "\u1161\u11A8"));
    addInput(inputs, input("hangulLeadingTrail", "\u11A8\u11A8"));
    addInput(inputs, input("supplementary", "\uD83D\uDE00"));
    addInput(inputs, input("twoSupplementary", "\uD83D\uDE00\uD83D\uDE01"));
    addInput(inputs, input("zwjAfterAscii", "a\u200D"));

    List<InputTemplate> atoms =
        List.of(
            input("atomHighSurrogate", "\uD83D"),
            input("atomLowSurrogate", "\uDE00"),
            input("atomZwj", "\u200D"),
            input("atomCombiningMark", "\u0301"),
            input("atomEmojiModifier", "\uD83C\uDFFD"),
            input("atomExtendedPictographic", "\uD83D\uDC69"),
            input("atomRegionalIndicator", "\uD83C\uDDFA"),
            input("atomCr", "\r"),
            input("atomLf", "\n"),
            input("atomPrepend", "\u0600"),
            input("atomAscii", "a"));
    for (InputTemplate atom : atoms) {
      addInput(inputs, atom);
    }
    addInput(inputs, input("generatedHighLow", "\uD83D\uDE00"));
    addInput(inputs, input("generatedLowHigh", "\uDE00\uD83D"));
    addInput(inputs, input("generatedLowZwj", "\uDE00\u200D"));
    addInput(inputs, input("generatedZwjPictographic", "\u200D\uD83D\uDC69"));
    addInput(inputs, input("generatedPictographicZwj", "\uD83D\uDC69\u200D"));
    addInput(inputs, input("generatedAsciiMark", "a\u0301"));
    addInput(inputs, input("generatedMarkAscii", "\u0301a"));
    addInput(inputs, input("generatedCrLf", "\r\n"));
    addInput(inputs, input("generatedRegionalPair", "\uD83C\uDDFA\uD83C\uDDF8"));
    addInput(inputs, input("generatedPrependAscii", "\u0600a"));
    addInput(inputs, input("generatedLowZwjPictographic", "\uDE00\u200D\uD83D\uDC69"));
    addInput(inputs, input("generatedHighLowZwj", "\uD83D\uDE00\u200D"));
    addInput(inputs, input("generatedCrLfAscii", "\r\na"));
    addInput(inputs, input("generatedRegionalPairAscii", "\uD83C\uDDFA\uD83C\uDDF8a"));
    return List.copyOf(inputs.values());
  }

  private static List<InputTemplate> buildTargetedLongInputs() {
    Map<String, InputTemplate> inputs = new LinkedHashMap<>();
    List<InputTemplate> suffixes =
        List.of(
            input("End", ""),
            input("OtherBmp", "a"),
            input("Extend", "\u0301"),
            input("ExtendedPictographic", "\uD83D\uDC69"),
            input("LowSurrogate", "\uDE00"));

    for (int extendCount = 1; extendCount <= 12; extendCount++) {
      for (int zwjCount = 1; zwjCount <= 4; zwjCount++) {
        for (InputTemplate suffix : suffixes) {
          addInput(
              inputs,
              input(
                  "targetLeadingExtend" + extendCount + "Zwj" + zwjCount + suffix.label(),
                  "\u0301".repeat(extendCount) + "\u200D".repeat(zwjCount) + suffix.input()));
        }
      }
    }

    for (int regionalCount = 1; regionalCount <= 12; regionalCount++) {
      String regionalIndicators = "\uD83C\uDDFA".repeat(regionalCount);
      addInput(inputs, input("targetRegionalIndicators" + regionalCount, regionalIndicators));
      addInput(
          inputs,
          input("targetRegionalIndicators" + regionalCount + "Other", regionalIndicators + "a"));
      addInput(
          inputs,
          input(
              "targetRegionalIndicators" + regionalCount + "Extend",
              regionalIndicators + "\u0301"));
    }

    for (int chainLength = 2; chainLength <= 6; chainLength++) {
      addInput(inputs, input("targetEmojiZwjChain" + chainLength, emojiZwjChain(chainLength, "")));
      addInput(
          inputs,
          input(
              "targetEmojiZwjChain" + chainLength + "Extend",
              emojiZwjChain(chainLength, "\u0301")));
      addInput(
          inputs,
          input(
              "targetEmojiZwjModifierChain" + chainLength,
              "\uD83D\uDC69\uD83C\uDFFD"
                  + ("\u200D\uD83D\uDC69\uD83C\uDFFD").repeat(chainLength - 1)));
    }

    for (int leadingCount = 1; leadingCount <= 6; leadingCount++) {
      for (int vowelCount = 1; vowelCount <= 4; vowelCount++) {
        for (int trailingCount = 0; trailingCount <= 4; trailingCount++) {
          addInput(
              inputs,
              input(
                  "targetHangulL" + leadingCount + "V" + vowelCount + "T" + trailingCount,
                  "\u1100".repeat(leadingCount)
                      + "\u1161".repeat(vowelCount)
                      + "\u11A8".repeat(trailingCount)));
        }
      }
    }

    for (int count = 1; count <= 8; count++) {
      addInput(inputs, input("targetHighLowPairs" + count, "\uD83D\uDE00".repeat(count)));
      addInput(inputs, input("targetLowHighPairs" + count, "\uDE00\uD83D".repeat(count)));
      addInput(inputs, input("targetHighLowZwj" + count, ("\uD83D\uDE00\u200D").repeat(count)));
      addInput(inputs, input("targetLowZwj" + count, ("\uDE00\u200D").repeat(count)));
    }

    return List.copyOf(inputs.values());
  }

  private static List<InputTemplate> buildIndicConjunctLinkerZwjInputs() {
    Map<String, InputTemplate> inputs = new LinkedHashMap<>();
    addIndicConjunctLinkerZwjInputs(inputs, "Devanagari", "\u0915", "\u094D");
    addIndicConjunctLinkerZwjInputs(inputs, "Bengali", "\u0995", "\u09CD");
    addIndicConjunctLinkerZwjInputs(inputs, "Gujarati", "\u0A95", "\u0ACD");
    addIndicConjunctLinkerZwjInputs(inputs, "Odia", "\u0B15", "\u0B4D");
    addIndicConjunctLinkerZwjInputs(inputs, "Telugu", "\u0C15", "\u0C4D");
    addIndicConjunctLinkerZwjInputs(inputs, "Malayalam", "\u0D15", "\u0D4D");
    return List.copyOf(inputs.values());
  }

  private static void addIndicConjunctLinkerZwjInputs(
      Map<String, InputTemplate> inputs, String script, String consonant, String linker) {
    String prefix = "indicConjunctLinkerZwj" + script;
    String conjunct = consonant + linker + "\u200D" + consonant;
    addInput(inputs, input(prefix, conjunct));
    addInput(inputs, input(prefix + "Extend", conjunct + "\u0301"));
    addInput(inputs, input(prefix + "Ascii", conjunct + "a"));
    addInput(inputs, input(prefix + "Chain", conjunct + linker + "\u200D" + consonant));
    addInput(inputs, input(prefix + "LeadingLinker", linker + "\u200D" + consonant));
    addInput(inputs, input(prefix + "TrailingLinker", consonant + linker + "\u200D"));
    addInput(
        inputs, input(prefix + "LinkerExtendZwj", consonant + linker + "\u0301\u200D" + consonant));
    addInput(
        inputs, input(prefix + "LinkerZwjExtend", consonant + linker + "\u200D\u0301" + consonant));
  }

  private static String emojiZwjChain(int chainLength, String suffixAfterEachPictographic) {
    StringBuilder result = new StringBuilder("\uD83D\uDC69").append(suffixAfterEachPictographic);
    for (int i = 1; i < chainLength; i++) {
      result.append('\u200D').append("\uD83D\uDC69").append(suffixAfterEachPictographic);
    }
    return result.toString();
  }

  private static void addInput(Map<String, InputTemplate> inputs, InputTemplate input) {
    inputs.putIfAbsent(input.label(), input);
  }

  private interface GroupValue {
    String group(int group);
  }

  private interface JdkTrace {
    String trace(java.util.regex.Pattern pattern, CaseSpec spec);
  }

  private interface SafeReTrace {
    String trace(org.safere.Pattern pattern, CaseSpec spec);
  }

  private record RegexTemplate(String label, String regex) {}

  private record GraphemeAtom(String label, String input) {}

  private record InputTemplate(String label, String input) {}

  private record TargetedRegionInput(String label, String text, int regionStart, int regionEnd) {}

  private interface InputFamily {
    String label();

    long size();

    InputTemplate inputAt(long index);
  }

  private record InputSpace(List<InputFamily> families) {
    long size() {
      long total = 0;
      for (InputFamily family : families) {
        total = Math.addExact(total, family.size());
      }
      return total;
    }

    InputTemplate inputAt(long index) {
      long remaining = index;
      for (InputFamily family : families) {
        if (remaining < family.size()) {
          return family.inputAt(remaining);
        }
        remaining -= family.size();
      }
      throw new IndexOutOfBoundsException("input index " + index + " >= " + size());
    }

    String familySummary() {
      StringBuilder result = new StringBuilder();
      for (int i = 0; i < families.size(); i++) {
        if (i > 0) {
          result.append(',');
        }
        InputFamily family = families.get(i);
        result.append(family.label()).append('=').append(family.size());
      }
      return result.toString();
    }
  }

  private record ExplicitInputFamily(String label, List<InputTemplate> inputs)
      implements InputFamily {
    @Override
    public long size() {
      return inputs.size();
    }

    @Override
    public InputTemplate inputAt(long index) {
      return inputs.get(Math.toIntExact(index));
    }
  }

  private record SequenceInputFamily(String label, List<GraphemeAtom> atoms, int length)
      implements InputFamily {
    @Override
    public long size() {
      return pow(atoms.size(), length);
    }

    @Override
    public InputTemplate inputAt(long index) {
      long remaining = index;
      String[] atomLabels = new String[length];
      String[] atomInputs = new String[length];
      for (int i = length - 1; i >= 0; i--) {
        int atomIndex = (int) (remaining % atoms.size());
        remaining /= atoms.size();
        GraphemeAtom atom = atoms.get(atomIndex);
        atomLabels[i] = atom.label();
        atomInputs[i] = atom.input();
      }

      StringBuilder input = new StringBuilder();
      StringBuilder inputLabel = new StringBuilder(label).append('[');
      for (int i = 0; i < length; i++) {
        if (i > 0) {
          inputLabel.append('+');
        }
        inputLabel.append(atomLabels[i]);
        input.append(atomInputs[i]);
      }
      inputLabel.append(']');
      return new InputTemplate(inputLabel.toString(), input.toString());
    }

    private static long pow(int base, int exponent) {
      long result = 1;
      for (int i = 0; i < exponent; i++) {
        result = Math.multiplyExact(result, base);
      }
      return result;
    }
  }

  private record RegionMode(
      String label,
      String prefix,
      String suffix,
      int startAdjustment,
      int endAdjustment,
      Integer fixedStart,
      Integer fixedEnd) {}

  private record BoundsMode(String label, boolean transparentBounds, boolean anchoringBounds) {}

  private record OperationMode(String label, JdkTrace jdkTrace, SafeReTrace safeReTrace) {}

  private record CaseSpec(
      RegexTemplate regexTemplate,
      InputTemplate inputTemplate,
      RegionMode regionMode,
      BoundsMode boundsMode,
      OperationMode operationMode) {
    String regex() {
      return regexTemplate.regex();
    }

    String input() {
      return inputTemplate.input();
    }

    String text() {
      return regionMode.prefix() + input() + regionMode.suffix();
    }

    int regionStart() {
      if (regionMode.fixedStart() != null) {
        return regionMode.fixedStart();
      }
      return regionMode.prefix().length() + regionMode.startAdjustment();
    }

    int regionEnd() {
      if (regionMode.fixedEnd() != null) {
        return regionMode.fixedEnd();
      }
      return regionMode.prefix().length() + input().length() + regionMode.endAdjustment();
    }

    String labels() {
      return "regex="
          + regexTemplate.label()
          + ",input="
          + inputTemplate.label()
          + ",region="
          + regionMode.label()
          + ",bounds="
          + boundsMode.label()
          + ",operation="
          + operationMode.label();
    }
  }

  private record Outcome(boolean accepted, String trace, String error) {}

  private record GraphemeRunResult(
      long checked, long generated, long divergences, GraphemeDivergenceSummary summary) {}

  private record GraphemeSweepOptions(SweepOptions sweep, int unknownStratifiedSamples) {
    void printStartup() {
      sweep.printStartup("grapheme-cluster");
      System.out.println("unknownStratifiedSamples=" + unknownStratifiedSamples);
    }
  }

  private enum DivergenceStatus {
    KNOWN_INTENTIONAL,
    EXPECTED_ZERO,
    UNKNOWN
  }

  private enum DivergenceClass {
    BOUNDARY_CLUSTER_BOUNDARY_COMPOSITION(
        DivergenceStatus.KNOWN_INTENTIONAL,
        "SafeRE composes explicit \\b{g}, primitive \\X, and trailing \\b{g};"
            + " observed JDK repeated-find traces skip some candidate starts."),
    BOUNDARY_ONLY_ALTERNATIVE_FIND_CURSOR(
        DivergenceStatus.KNOWN_INTENTIONAL,
        "Observed JDK repeated-find cursor behavior for alternatives involving a bare"
            + " explicit \\b{g} is not part of SafeRE's compositional grapheme model."),
    BOUNDARY_CLUSTER_ALTERNATIVE_FIND_CURSOR(
        DivergenceStatus.KNOWN_INTENTIONAL,
        "Observed JDK traces for alternatives involving \\b{g}\\X expose matcher cursor and"
            + " transparent-boundary implementation details that SafeRE does not copy."),
    REGION_LOCAL_CONTINUATION_CLUSTER(
        DivergenceStatus.KNOWN_INTENTIONAL,
        "SafeRE keeps opaque region-local continuation clusters compositional instead of"
            + " copying selected JDK region-edge traces."),
    TRANSPARENT_BOUNDARY_JDK_DETAIL(
        DivergenceStatus.KNOWN_INTENTIONAL,
        "Observed JDK transparent-boundary traces expose implementation details that SafeRE"
            + " does not target when they conflict with the documented grapheme model."),
    NON_ANCHORING_DOLLAR_REGION_END(
        DivergenceStatus.EXPECTED_ZERO,
        "Non-anchoring bounds require $ to use full-input trailing-line context, not the"
            + " region-local end."),
    GRAPHEME_CANDIDATE_START_DEDUP(
        DivergenceStatus.EXPECTED_ZERO,
        "Unanchored search must preserve concurrent consuming \\X candidates with different"
            + " starts."),
    UNKNOWN(DivergenceStatus.UNKNOWN, "Unclassified SafeRE/JDK grapheme divergence.");

    private final DivergenceStatus status;
    private final String rationale;

    DivergenceClass(DivergenceStatus status, String rationale) {
      this.status = status;
      this.rationale = rationale;
    }

    boolean actionable() {
      return status == DivergenceStatus.EXPECTED_ZERO;
    }
  }

  private record Divergence(
      long caseIndex,
      CaseSpec spec,
      Outcome jdk,
      Outcome safere,
      String bucket,
      DivergenceClass classification) {
    String toJson() {
      var object = SweepJson.object();
      object.addProperty("caseIndex", caseIndex);
      object.add("case", caseJson(spec));
      object.addProperty("bucket", bucket);
      object.addProperty("classification", classification.name());
      object.addProperty("classificationStatus", classification.status.name());
      object.addProperty("labels", spec.labels());
      object.addProperty("regex", spec.regex());
      object.addProperty("input", spec.input());
      object.addProperty("prefix", spec.regionMode().prefix());
      object.addProperty("suffix", spec.regionMode().suffix());
      object.addProperty("bounds", spec.boundsMode().label());
      object.addProperty("operation", spec.operationMode().label());
      object.addProperty("regionStart", spec.regionStart());
      object.addProperty("regionEnd", spec.regionEnd());
      object.addProperty("jdkAccepted", jdk.accepted());
      object.addProperty("safeReAccepted", safere.accepted());
      object.addProperty("jdkTrace", jdk.trace());
      object.addProperty("safeReTrace", safere.trace());
      object.addProperty("jdkError", jdk.error());
      object.addProperty("safeReError", safere.error());
      return SweepJson.toJson(object);
    }

    private static com.google.gson.JsonObject caseJson(CaseSpec spec) {
      var object = SweepJson.object();
      object.addProperty("regexLabel", spec.regexTemplate().label());
      object.addProperty("regex", spec.regex());
      object.addProperty("inputLabel", spec.inputTemplate().label());
      object.addProperty("input", spec.input());
      object.addProperty("region", spec.regionMode().label());
      object.addProperty("prefix", spec.regionMode().prefix());
      object.addProperty("suffix", spec.regionMode().suffix());
      object.addProperty("regionStart", spec.regionStart());
      object.addProperty("regionEnd", spec.regionEnd());
      object.addProperty("bounds", spec.boundsMode().label());
      object.addProperty("operation", spec.operationMode().label());
      return object;
    }
  }

  private static final class GraphemeDivergenceSummary {
    private final EnumMap<DivergenceClass, LongAdder> counts = new EnumMap<>(DivergenceClass.class);
    private final List<DivergenceExample> firstUnknownExamples = new ArrayList<>();
    private final List<DivergenceExample> actionableExamples = new ArrayList<>();
    private final StratifiedUnknownSampler unknownSampler;

    GraphemeDivergenceSummary(StratifiedUnknownSampler unknownSampler) {
      this.unknownSampler = unknownSampler;
      for (DivergenceClass divergenceClass : DivergenceClass.values()) {
        counts.put(divergenceClass, new LongAdder());
      }
    }

    void record(Divergence divergence, long caseIndex) {
      DivergenceClass divergenceClass = divergence.classification();
      counts.get(divergenceClass).increment();
      if (divergenceClass == DivergenceClass.UNKNOWN) {
        recordUnknown(divergence, caseIndex);
      } else if (divergenceClass.actionable()) {
        recordActionable(divergence, caseIndex);
      }
    }

    void merge(GraphemeDivergenceSummary other) {
      if (other == null) {
        return;
      }
      for (DivergenceClass divergenceClass : DivergenceClass.values()) {
        counts.get(divergenceClass).add(other.count(divergenceClass));
      }
      firstUnknownExamples.addAll(other.firstUnknownExamples);
      firstUnknownExamples.sort(Comparator.comparingLong(DivergenceExample::caseIndex));
      truncate(firstUnknownExamples, FIRST_UNKNOWN_LIMIT);

      actionableExamples.addAll(other.actionableExamples);
      actionableExamples.sort(Comparator.comparingLong(DivergenceExample::caseIndex));
      truncate(actionableExamples, ACTIONABLE_SAMPLE_LIMIT);
    }

    long count(DivergenceClass divergenceClass) {
      return counts.get(divergenceClass).sum();
    }

    long actionableCount() {
      long total = 0;
      for (DivergenceClass divergenceClass : DivergenceClass.values()) {
        if (divergenceClass.actionable()) {
          total += count(divergenceClass);
        }
      }
      return total;
    }

    void writeReports(Path outputDir) throws IOException {
      writeClassCounts(outputDir.resolve("grapheme-cluster-class-counts.tsv"));
      writeExamples(
          outputDir.resolve("grapheme-cluster-unknown-first.jsonl"), firstUnknownExamples);
      writeStratifiedSamples(outputDir.resolve("grapheme-cluster-unknown-stratified.jsonl"));
      writeExamples(
          outputDir.resolve("grapheme-cluster-actionable-examples.jsonl"), actionableExamples);
    }

    private void recordUnknown(Divergence divergence, long caseIndex) {
      recordExample(
          firstUnknownExamples, new DivergenceExample(caseIndex, divergence), FIRST_UNKNOWN_LIMIT);
      unknownSampler.record(divergence, caseIndex);
    }

    private void recordActionable(Divergence divergence, long caseIndex) {
      recordExample(
          actionableExamples,
          new DivergenceExample(caseIndex, divergence),
          ACTIONABLE_SAMPLE_LIMIT);
    }

    private static void recordExample(
        List<DivergenceExample> examples, DivergenceExample example, int limit) {
      synchronized (examples) {
        if (examples.size() < limit) {
          examples.add(example);
        }
      }
    }

    private void writeClassCounts(Path path) throws IOException {
      try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
        writer.write("class\tstatus\tcount\trationale");
        writer.newLine();
        for (DivergenceClass divergenceClass : DivergenceClass.values()) {
          writer.write(divergenceClass.name());
          writer.write('\t');
          writer.write(divergenceClass.status.name());
          writer.write('\t');
          writer.write(Long.toString(count(divergenceClass)));
          writer.write('\t');
          writer.write(divergenceClass.rationale);
          writer.newLine();
        }
      }
    }

    private static void writeExamples(Path path, List<DivergenceExample> examples)
        throws IOException {
      try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
        for (DivergenceExample example : examples) {
          writer.write(example.divergence().toJson());
          writer.newLine();
        }
      }
    }

    private void writeStratifiedSamples(Path path) throws IOException {
      unknownSampler.write(path);
    }

    private static void truncate(List<?> list, int limit) {
      while (list.size() > limit) {
        list.remove(list.size() - 1);
      }
    }
  }

  private record DivergenceExample(long caseIndex, Divergence divergence) {}

  private static final class StratifiedUnknownSampler {
    private final AtomicReferenceArray<StratifiedSampleEntry> samples;
    private final long rangeStartInclusive;
    private final long selectedCaseCount;

    StratifiedUnknownSampler(
        long rangeStartInclusive, long selectedCaseCount, int unknownStratifiedSampleLimit) {
      this.rangeStartInclusive = rangeStartInclusive;
      this.selectedCaseCount = selectedCaseCount;
      int sampleSlots = (int) Math.min(unknownStratifiedSampleLimit, selectedCaseCount);
      this.samples = new AtomicReferenceArray<>(sampleSlots);
    }

    void record(Divergence divergence, long caseIndex) {
      int slot = stratifiedSlot(caseIndex);
      if (slot < 0) {
        return;
      }
      long distance = stratifiedDistance(slot, caseIndex);
      while (true) {
        StratifiedSampleEntry current = samples.get(slot);
        if (current != null
            && (current.distance() < distance
                || (current.distance() == distance && current.caseIndex() <= caseIndex))) {
          return;
        }
        StratifiedSampleEntry candidate =
            new StratifiedSampleEntry(caseIndex, distance, divergence.toJson());
        if (samples.compareAndSet(slot, current, candidate)) {
          return;
        }
      }
    }

    private int stratifiedSlot(long caseIndex) {
      if (samples.length() == 0
          || caseIndex < rangeStartInclusive
          || caseIndex - rangeStartInclusive >= selectedCaseCount) {
        return -1;
      }
      long relative = caseIndex - rangeStartInclusive;
      long slot = relative * samples.length() / selectedCaseCount;
      return (int) Math.min(slot, samples.length() - 1L);
    }

    private long stratifiedDistance(int slot, long caseIndex) {
      long slotStart = rangeStartInclusive + (long) slot * selectedCaseCount / samples.length();
      long slotEnd = rangeStartInclusive + (long) (slot + 1) * selectedCaseCount / samples.length();
      long midpoint = slotStart + (slotEnd - slotStart) / 2;
      return Math.abs(caseIndex - midpoint);
    }

    void write(Path path) throws IOException {
      try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
        for (int slot = 0; slot < samples.length(); slot++) {
          StratifiedSampleEntry sample = samples.get(slot);
          if (sample != null) {
            writer.write(sample.json());
            writer.newLine();
          }
        }
      }
    }
  }

  private record StratifiedSampleEntry(long caseIndex, long distance, String json) {}

  private static final class SweepState {
    final SweepRunState runState;
    final GraphemeDivergenceSummary summary;
    final SweepOptions options;
    final SweepWorkers.ProgressReporter progressReporter;
    final int workerIndex;
    long generated;

    SweepState(SweepRunState runState, GraphemeDivergenceSummary summary, int workerIndex) {
      this.runState = runState;
      this.summary = summary;
      this.options = runState.options;
      this.progressReporter = new SweepWorkers.ProgressReporter(runState);
      this.workerIndex = workerIndex;
    }

    void run() {
      long end = Math.min(options.rangeEndExclusive(), totalCases());
      generated = firstOwnedCaseIndex();
      while (generated < end) {
        long caseIndex = generated;
        generated += options.threads();
        progressReporter.checked();
        evaluateCase(caseIndex, caseAt(caseIndex));
      }
    }

    private long firstOwnedCaseIndex() {
      long start = options.rangeStartInclusive();
      long remainder = start % options.threads();
      long delta = (workerIndex - remainder + options.threads()) % options.threads();
      return start + delta;
    }

    void evaluateCase(long caseIndex, CaseSpec spec) {
      GraphemeClusterDivergenceSweep.evaluateCase(runState, summary, spec, caseIndex);
      reportProgressIfNeeded();
    }

    void finish() {
      runState.recordGenerated(generated);
    }

    void reportProgressIfNeeded() {
      progressReporter.reportIfNeeded(generated);
    }
  }

  private static void evaluateCase(
      SweepRunState runState, GraphemeDivergenceSummary summary, CaseSpec spec, long caseIndex) {
    Outcome jdk = jdkOutcome(spec);
    Outcome safere = safeReOutcome(spec);
    if (semanticallyEqual(jdk, safere)) {
      return;
    }
    String bucketName = bucketFor(spec, jdk, safere);
    DivergenceClass classification = classifyDivergence(spec, jdk, safere);
    Divergence divergence =
        new Divergence(caseIndex, spec, jdk, safere, bucketName, classification);
    runState.recordDivergence();
    summary.record(divergence, caseIndex);
    if (classification.actionable()) {
      runState.appendJsonl(divergence.toJson());
    }
  }
}
