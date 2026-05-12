// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.PatternSyntaxException;

/** Offline differential sweep for {@code \X} and {@code \b{g}} grapheme-cluster bugs. */
public final class GraphemeClusterDivergenceSweep {
  private static final int FIND_LIMIT = 32;
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
          regex("anchoredBoundaryClusterBoundary", "^\\b{g}\\X\\b{g}"));

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
          region("endBeforeSuffixLowSurrogate", "", "\uDE00", 0, 0),
          region("bothEndsSplitSurrogates", "\uD83D", "\uDE00", 0, 0));

  private static final List<BoundsMode> BOUNDS_MODES =
      List.of(bounds("opaqueAnchoring", false, true), bounds("opaqueNonAnchoring", false, false));

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

  private GraphemeClusterDivergenceSweep() {}

  public static void main(String[] args) throws IOException {
    SweepOptions options =
        SweepOptions.parse(
            args,
            Path.of("target", "exhaustive-reports", "grapheme-cluster-sweep"),
            "grapheme-cluster-divergences.jsonl",
            1_000_000);
    Files.createDirectories(options.outputDir());
    Files.deleteIfExists(options.jsonlPath());
    options.printStartup("grapheme-cluster");

    if (options.replayFile() != null) {
      runReplay(options);
      return;
    }

    SweepRunState state = runSweep(options);

    System.out.println("checked=" + state.checked.sum());
    System.out.println("generated=" + state.generated);
    System.out.println("totalCases=" + totalCases());
    System.out.println("inputCases=" + INPUT_CASES);
    System.out.println("inputFamilies=" + INPUT_SPACE.familySummary());
    System.out.println("divergences=" + state.divergences.sum());
    System.out.println("buckets=" + state.buckets.size());
    System.out.println("threads=" + options.threads());
    System.out.println("jsonl=" + options.jsonlPath());
  }

  private static SweepRunState runSweep(SweepOptions options) throws IOException {
    try (SweepRunState runState = new SweepRunState(options)) {
      SweepWorkers.run(
          options.threads(),
          "grapheme-cluster-sweep-",
          workerIndex -> {
            SweepState worker = new SweepState(runState, workerIndex);
            worker.run();
            worker.finish();
          });
      return runState;
    }
  }

  private static void runReplay(SweepOptions options) throws IOException {
    long generated = 0;
    long checked = 0;
    long divergences = 0;
    try (BufferedReader reader =
        Files.newBufferedReader(options.replayFile(), StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        generated++;
        checked++;
        CaseSpec spec = replayCase(trimmed);
        Outcome jdk = jdkOutcome(spec);
        Outcome safere = safeReOutcome(spec);
        if (semanticallyEqual(jdk, safere)) {
          continue;
        }
        divergences++;
        Files.writeString(
            options.jsonlPath(),
            new Divergence(spec, jdk, safere, bucketFor(spec, jdk, safere)).toJson() + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      }
    }
    System.out.println("checked=" + checked);
    System.out.println("generated=" + generated);
    System.out.println("divergences=" + divergences);
    System.out.println("buckets=" + divergences);
    System.out.println("threads=1");
    System.out.println("jsonl=" + options.jsonlPath());
    if (divergences > 0) {
      throw new IllegalStateException("replay found " + divergences + " behavioral divergences");
    }
  }

  private static long totalCases() {
    return (long) REGEX_TEMPLATES.size()
        * INPUT_CASES
        * REGION_MODES.size()
        * BOUNDS_MODES.size()
        * OPERATION_MODES.size();
  }

  private static CaseSpec caseAt(long index) {
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
    String regex = SweepJson.field(line, "regex");
    String input = SweepJson.field(line, "input");
    String prefix = SweepJson.field(line, "prefix");
    String suffix = SweepJson.field(line, "suffix");
    String bounds = SweepJson.field(line, "bounds");
    String operation = SweepJson.field(line, "operation");
    if (regex == null || input == null || prefix == null || suffix == null) {
      throw new IllegalArgumentException("replay line must contain regex, input, prefix, suffix");
    }
    return new CaseSpec(
        new RegexTemplate("replay", regex),
        new InputTemplate("replay", input),
        region("replay", prefix, suffix, 0, 0),
        bounds == null ? BOUNDS_MODES.get(0) : boundsMode(bounds),
        operation == null ? OPERATION_MODES.get(0) : operationMode(operation));
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

  private static BoundsMode boundsMode(String label) {
    return BOUNDS_MODES.stream()
        .filter(mode -> mode.label().equals(label))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown bounds mode: " + label));
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

  private record Divergence(CaseSpec spec, Outcome jdk, Outcome safere, String bucket) {
    String toJson() {
      var object = SweepJson.object();
      object.addProperty("bucket", bucket);
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
  }

  private static final class SweepState {
    final SweepRunState runState;
    final SweepOptions options;
    final int workerIndex;
    long generated;
    long nextProgressReport;

    SweepState(SweepRunState runState, int workerIndex) {
      this.runState = runState;
      this.options = runState.options;
      this.workerIndex = workerIndex;
      this.nextProgressReport =
          SweepWorkers.firstProgressAt(options.rangeStartInclusive(), options.progressInterval());
    }

    void run() {
      long end = Math.min(options.rangeEndExclusive(), totalCases());
      generated = firstOwnedCaseIndex();
      while (generated < end) {
        long caseIndex = generated;
        generated += options.threads();
        runState.checked.increment();
        checkOwned(caseAt(caseIndex));
      }
    }

    private long firstOwnedCaseIndex() {
      long start = options.rangeStartInclusive();
      long remainder = start % options.threads();
      long delta = (workerIndex - remainder + options.threads()) % options.threads();
      return start + delta;
    }

    void checkOwned(CaseSpec spec) {
      Outcome jdk = jdkOutcome(spec);
      Outcome safere = safeReOutcome(spec);
      if (semanticallyEqual(jdk, safere)) {
        reportProgressIfNeeded();
        return;
      }
      String bucketName = bucketFor(spec, jdk, safere);
      if (!runState.reserveDivergenceExample(bucketName)) {
        reportProgressIfNeeded();
        return;
      }
      runState.appendJsonl(new Divergence(spec, jdk, safere, bucketName).toJson());
      reportProgressIfNeeded();
    }

    void finish() {
      runState.recordGenerated(generated);
    }

    void reportProgressIfNeeded() {
      if (generated < nextProgressReport) {
        return;
      }
      runState.reportProgressIfNeeded(generated);
      while (nextProgressReport <= generated) {
        nextProgressReport += options.progressInterval();
      }
    }
  }
}
