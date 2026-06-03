// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.PatternSyntaxException;

/** Offline differential sweep for ordinary scalar-consuming atoms under matcher regions. */
public final class RegionScalarDivergenceSweep {
  private static final int FIND_LIMIT = 8;
  private static final List<DivergenceClass> DIVERGENCE_CLASSES =
      List.of(
          DivergenceClass.QUANTIFIED_SPLIT_SURROGATE_SCALAR_COMPOSITION, DivergenceClass.UNKNOWN);

  private static final List<AtomCase> ATOMS =
      List.of(
          atom("dot", "."),
          atom("anyClass", "[\\s\\S]"),
          atom("emptyComplement", "[^\\s\\S]"),
          atom("literalA", "a"),
          atom("notLiteralA", "[^a]"),
          atom("asciiRange", "[a-z]"),
          atom("notAsciiRange", "[^a-z]"),
          atom("digit", "\\d"),
          atom("nonDigit", "\\D"),
          atom("space", "\\s"),
          atom("nonSpace", "\\S"),
          atom("word", "\\w"),
          atom("nonWord", "\\W"),
          atom("horizontalSpace", "\\h"),
          atom("nonHorizontalSpace", "\\H"),
          atom("verticalSpace", "\\v"),
          atom("nonVerticalSpace", "\\V"),
          atom("posixLower", "\\p{Lower}"),
          atom("notPosixLower", "\\P{Lower}"),
          atom("posixAscii", "\\p{ASCII}"),
          atom("notPosixAscii", "\\P{ASCII}"),
          atom("posixControl", "\\p{Cntrl}"),
          atom("notPosixControl", "\\P{Cntrl}"),
          atom("javaWhitespace", "\\p{javaWhitespace}"),
          atom("notJavaWhitespace", "\\P{javaWhitespace}"),
          atom("javaLetter", "\\p{javaLetter}"),
          atom("notJavaLetter", "\\P{javaLetter}"),
          atom("unicodeAlphabetic", "\\p{IsAlphabetic}"),
          atom("notUnicodeAlphabetic", "\\P{IsAlphabetic}"),
          atom("surrogateCategory", "\\p{Cs}"),
          atom("notSurrogateCategory", "\\P{Cs}"),
          atom("surrogateCategoryClass", "[\\p{Cs}]"),
          atom("notSurrogateCategoryClass", "[^\\p{Cs}]"),
          atom("symbolOther", "\\p{So}"),
          atom("notSymbolOther", "\\P{So}"),
          atom("unassigned", "\\p{Cn}"),
          atom("notUnassigned", "\\P{Cn}"));

  private static final List<WrapperCase> WRAPPERS =
      List.of(
          wrapper("bare", "%s"),
          wrapper("captured", "(%s)"),
          wrapper("nonCapturing", "(?:%s)"),
          wrapper("anchoredStart", "^%s"),
          wrapper("anchoredEnd", "%s$"),
          wrapper("anchoredBoth", "^%s$"),
          wrapper("optionalGreedy", "%s?"),
          wrapper("optionalReluctant", "%s??"),
          wrapper("starGreedy", "%s*"),
          wrapper("plusGreedy", "%s+"),
          wrapper("countOne", "%s{1}"),
          wrapper("countOptional", "%s{0,1}"),
          wrapper("countTwo", "%s{2}"),
          wrapper("capturedOptional", "(%s)?"),
          wrapper("capturedPlus", "(%s)+"),
          wrapper("doubleAtom", "%s%s"),
          wrapper("capturedDoubleAtom", "(%s)(%s)"),
          wrapper("literalPrefix", "a%s"),
          wrapper("literalSuffix", "%sa"),
          wrapper("alternativeAfterLiteral", "a|%s"),
          wrapper("alternativeBeforeLiteral", "%s|a"),
          wrapper("capturedAlternativeAfterLiteral", "(a)|(%s)"),
          wrapper("capturedAlternativeBeforeLiteral", "(%s)|(a)"),
          wrapper("optionalThenLiteral", "%s?a"),
          wrapper("literalThenOptional", "a%s?"));

  private static final List<FlagMode> FLAG_MODES =
      List.of(
          flags("none", 0),
          flags("dotall", java.util.regex.Pattern.DOTALL),
          flags("caseInsensitive", java.util.regex.Pattern.CASE_INSENSITIVE),
          flags(
              "caseInsensitiveUnicodeCase",
              java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE),
          flags("unicodeCharacterClass", java.util.regex.Pattern.UNICODE_CHARACTER_CLASS),
          flags(
              "unicodeCharacterClassDotall",
              java.util.regex.Pattern.UNICODE_CHARACTER_CLASS | java.util.regex.Pattern.DOTALL),
          flags("multiline", java.util.regex.Pattern.MULTILINE),
          flags("unixLines", java.util.regex.Pattern.UNIX_LINES),
          flags("comments", java.util.regex.Pattern.COMMENTS),
          flags(
              "dotallMultiline",
              java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.MULTILINE),
          flags(
              "unicodeCaseDotall",
              java.util.regex.Pattern.CASE_INSENSITIVE
                  | java.util.regex.Pattern.UNICODE_CASE
                  | java.util.regex.Pattern.DOTALL));

  private static final List<BoundsMode> BOUNDS_MODES =
      List.of(
          bounds("opaqueAnchoring", false, true),
          bounds("opaqueNonAnchoring", false, false),
          bounds("transparentAnchoring", true, true),
          bounds("transparentNonAnchoring", true, false));

  private static final List<TextRegion> TEXT_REGIONS = buildTextRegions();

  private RegionScalarDivergenceSweep() {}

  public static void main(String[] args) throws IOException {
    SweepOptions options =
        SweepOptions.parse(
            args,
            Path.of("target", "exhaustive-reports", "region-scalar-sweep"),
            "region-scalar-divergences.jsonl",
            1_000_000);
    Files.createDirectories(options.outputDir());
    if (options.replayFile() != null) {
      Files.deleteIfExists(options.jsonlPath());
    }
    options.printStartup("region-scalar");

    RegionScalarDivergenceSweep sweep = new RegionScalarDivergenceSweep();
    if (options.replayFile() != null) {
      sweep.runReplay(options);
      return;
    }

    SweepRunState state = sweep.runSweep(options);
    System.out.println("checked=" + state.checked.sum());
    System.out.println("generated=" + state.generated);
    System.out.println("totalCases=" + totalCases());
    System.out.println("divergences=" + state.divergences.sum());
    System.out.println("threads=" + options.threads());
  }

  static long totalCases() {
    return (long) ATOMS.size()
        * WRAPPERS.size()
        * FLAG_MODES.size()
        * TEXT_REGIONS.size()
        * BOUNDS_MODES.size();
  }

  static String compactReplayJson(long caseIndex, String classification) {
    return compactReplayJson(caseIndex, classification, caseFromIndex(caseIndex));
  }

  static boolean containsGeneratedCaseForTesting(
      String atom, String wrapper, int flags, String text, int start, int end) {
    return generatedCaseForTesting(atom, wrapper, flags, text, start, end) != null;
  }

  static String classifyDivergenceShapeForTesting(
      String atom, String wrapper, int flags, String text, int start, int end) {
    CaseSpec spec = generatedCaseForTesting(atom, wrapper, flags, text, start, end);
    if (spec == null) {
      throw new IllegalArgumentException("case is not generated by the region scalar sweep");
    }
    return classifyDivergence(spec).name();
  }

  private static CaseSpec generatedCaseForTesting(
      String atom, String wrapper, int flags, String text, int start, int end) {
    for (AtomCase atomCase : ATOMS) {
      if (!atomCase.regex().equals(atom)) {
        continue;
      }
      for (WrapperCase wrapperCase : WRAPPERS) {
        if (!wrapperCase.template().equals(wrapper)) {
          continue;
        }
        for (FlagMode flagMode : FLAG_MODES) {
          if (flagMode.flags() != flags) {
            continue;
          }
          for (TextRegion textRegion : TEXT_REGIONS) {
            if (textRegion.text().equals(text)
                && textRegion.start() == start
                && textRegion.end() == end) {
              return new CaseSpec(
                  atomCase, wrapperCase, flagMode, textRegion, BOUNDS_MODES.getFirst());
            }
          }
        }
      }
    }
    return null;
  }

  private SweepRunState runSweep(SweepOptions options) throws IOException {
    try (SweepRunState runState = new SweepRunState(options, options.totalChecks(totalCases()))) {
      runState.enableCompactLogs(
          "region-scalar",
          totalCases(),
          DIVERGENCE_CLASSES.stream().map(DivergenceClass::name).toList(),
          DIVERGENCE_CLASSES.stream().map(DivergenceClass::status).toList());
      SweepWorkers.run(
          options.threads(),
          "region-scalar-sweep-",
          workerIndex -> {
            SweepWorkers.ProgressReporter progressReporter =
                new SweepWorkers.ProgressReporter(runState, workerIndex);
            long generated = 0;
            long end = Math.min(options.rangeEndExclusive(), totalCases());
            for (long caseIndex = options.rangeStartInclusive(); caseIndex < end; caseIndex++) {
              generated = caseIndex + 1;
              if (caseIndex % options.threads() != workerIndex) {
                continue;
              }
              progressReporter.checked();
              evaluateCase(runState, caseFromIndex(caseIndex), workerIndex, caseIndex);
              progressReporter.reportIfNeeded(generated);
            }
            runState.recordGenerated(generated);
            runState.updateWorkerNextCaseIndex(workerIndex, generated);
          });
      return runState;
    }
  }

  private void runReplay(SweepOptions options) throws IOException {
    try (BufferedReader reader =
            Files.newBufferedReader(options.replayFile(), StandardCharsets.UTF_8);
        SweepRunState runState = new SweepRunState(options, 0)) {
      long generated =
          SweepWorkers.runStreamingLines(
              options.threads(),
              "region-scalar-replay-",
              reader,
              line -> {
                runState.checked.increment();
                evaluateCase(runState, replayCase(line), -1, -1);
              });
      runState.recordGenerated(generated);
      System.out.println("checked=" + runState.checked.sum());
      System.out.println("generated=" + runState.generated);
      System.out.println("totalCases=" + totalCases());
      System.out.println("divergences=" + runState.divergences.sum());
      System.out.println("threads=" + options.threads());
      System.out.println("jsonl=" + options.jsonlPath());
      if (runState.divergences.sum() > 0) {
        throw new IllegalStateException(
            "replay found " + runState.divergences.sum() + " behavioral divergences");
      }
    }
  }

  private void evaluateCase(
      SweepRunState runState, CaseSpec spec, int workerIndex, long caseIndex) {
    Outcome jdk = jdkOutcome(spec);
    Outcome safere = safeReOutcome(spec);
    if (semanticallyEqual(jdk, safere)) {
      return;
    }
    DivergenceClass classification = classifyDivergence(spec);
    if (workerIndex >= 0) {
      runState.recordCompactDivergence(workerIndex, caseIndex, classification.ordinal());
      return;
    }
    if (classification.status() == DivergenceStatus.KNOWN_INTENTIONAL) {
      return;
    }
    runState.recordDivergence();
    runState.appendJsonl(new Divergence(spec, jdk, safere, classification).toJson());
  }

  private static DivergenceClass classifyDivergence(CaseSpec spec) {
    if (isQuantifiedSplitSurrogateScalarComposition(spec)) {
      return DivergenceClass.QUANTIFIED_SPLIT_SURROGATE_SCALAR_COMPOSITION;
    }
    return DivergenceClass.UNKNOWN;
  }

  private static boolean isQuantifiedSplitSurrogateScalarComposition(CaseSpec spec) {
    return ("starGreedy".equals(spec.wrapperCase().label())
            || "plusGreedy".equals(spec.wrapperCase().label()))
        && regionEndsInsideSurrogatePair(spec.textRegion());
  }

  private static boolean regionEndsInsideSurrogatePair(TextRegion region) {
    String text = region.text();
    int end = region.end();
    return end > 0
        && end < text.length()
        && Character.isHighSurrogate(text.charAt(end - 1))
        && Character.isLowSurrogate(text.charAt(end));
  }

  private Outcome jdkOutcome(CaseSpec spec) {
    try {
      java.util.regex.Pattern pattern =
          java.util.regex.Pattern.compile(spec.regex(), spec.flagMode().flags());
      return new Outcome(true, operationTrace(pattern, spec), "");
    } catch (PatternSyntaxException e) {
      return new Outcome(false, "", e.getDescription());
    } catch (StackOverflowError e) {
      return new Outcome(false, "", "StackOverflowError");
    } catch (RuntimeException e) {
      return new Outcome(false, "", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private Outcome safeReOutcome(CaseSpec spec) {
    try {
      org.safere.Pattern pattern =
          org.safere.Pattern.compile(spec.regex(), spec.flagMode().flags());
      return new Outcome(true, operationTrace(pattern, spec), "");
    } catch (PatternSyntaxException e) {
      return new Outcome(false, "", e.getDescription());
    } catch (StackOverflowError e) {
      return new Outcome(false, "", "StackOverflowError");
    } catch (RuntimeException e) {
      return new Outcome(false, "", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static String operationTrace(java.util.regex.Pattern pattern, CaseSpec spec) {
    StringBuilder result = new StringBuilder();
    String text = spec.textRegion().text();
    int start = spec.textRegion().start();
    int end = spec.textRegion().end();
    java.util.regex.Matcher matcher = configure(pattern.matcher(text).region(start, end), spec);
    appendTrace(result, "matches", matcher.matches(), matcher);
    matcher = configure(matcher.reset(text).region(start, end), spec);
    appendTrace(result, "lookingAt", matcher.lookingAt(), matcher);
    matcher = configure(matcher.reset(text).region(start, end), spec);
    appendFindTrace(result, "find", matcher);
    matcher = configure(matcher.reset(text).region(start, end), spec);
    appendFindTrace(result, "resetFind", matcher);
    return result.toString();
  }

  private static String operationTrace(org.safere.Pattern pattern, CaseSpec spec) {
    StringBuilder result = new StringBuilder();
    String text = spec.textRegion().text();
    int start = spec.textRegion().start();
    int end = spec.textRegion().end();
    org.safere.Matcher matcher = configure(pattern.matcher(text).region(start, end), spec);
    appendTrace(result, "matches", matcher.matches(), matcher);
    matcher = configure(matcher.reset(text).region(start, end), spec);
    appendTrace(result, "lookingAt", matcher.lookingAt(), matcher);
    matcher = configure(matcher.reset(text).region(start, end), spec);
    appendFindTrace(result, "find", matcher);
    matcher = configure(matcher.reset(text).region(start, end), spec);
    appendFindTrace(result, "resetFind", matcher);
    return result.toString();
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

  private static void appendTrace(
      StringBuilder result, String operation, boolean matched, java.util.regex.Matcher matcher) {
    appendSeparator(result);
    result.append(operation).append('=').append(matched);
    if (matched) {
      appendMatch(
          result,
          matcher.start(),
          matcher.end(),
          matcher.groupCount(),
          matcher::start,
          matcher::end,
          matcher::group);
    }
  }

  private static void appendTrace(
      StringBuilder result, String operation, boolean matched, org.safere.Matcher matcher) {
    appendSeparator(result);
    result.append(operation).append('=').append(matched);
    if (matched) {
      appendMatch(
          result,
          matcher.start(),
          matcher.end(),
          matcher.groupCount(),
          matcher::start,
          matcher::end,
          matcher::group);
    }
  }

  private static void appendFindTrace(
      StringBuilder result, String operation, java.util.regex.Matcher matcher) {
    for (int count = 0; count < FIND_LIMIT; count++) {
      boolean matched = matcher.find();
      appendSeparator(result);
      result.append(operation).append(count).append('=').append(matched);
      if (matched) {
        appendMatch(
            result,
            matcher.start(),
            matcher.end(),
            matcher.groupCount(),
            matcher::start,
            matcher::end,
            matcher::group);
      } else {
        return;
      }
    }
  }

  private static void appendFindTrace(
      StringBuilder result, String operation, org.safere.Matcher matcher) {
    for (int count = 0; count < FIND_LIMIT; count++) {
      boolean matched = matcher.find();
      appendSeparator(result);
      result.append(operation).append(count).append('=').append(matched);
      if (matched) {
        appendMatch(
            result,
            matcher.start(),
            matcher.end(),
            matcher.groupCount(),
            matcher::start,
            matcher::end,
            matcher::group);
      } else {
        return;
      }
    }
  }

  private static void appendMatch(
      StringBuilder result,
      int start,
      int end,
      int groupCount,
      GroupInt groupStart,
      GroupInt groupEnd,
      GroupString groupText) {
    result.append('@').append(start).append('-').append(end);
    if (groupCount == 0) {
      return;
    }
    result.append("{groups=").append(groupCount);
    for (int group = 1; group <= groupCount; group++) {
      String text = groupText.get(group);
      result.append(";g").append(group).append('=');
      if (text == null) {
        result.append("null");
      } else {
        result
            .append(groupStart.get(group))
            .append('-')
            .append(groupEnd.get(group))
            .append(':')
            .append(escape(text));
      }
    }
    result.append('}');
  }

  private static boolean semanticallyEqual(Outcome left, Outcome right) {
    if (left.accepted() != right.accepted()) {
      return false;
    }
    return !left.accepted() || left.trace().equals(right.trace());
  }

  private static CaseSpec caseFromIndex(long index) {
    int boundsIndex = (int) (index % BOUNDS_MODES.size());
    index /= BOUNDS_MODES.size();
    int textRegionIndex = (int) (index % TEXT_REGIONS.size());
    index /= TEXT_REGIONS.size();
    int flagIndex = (int) (index % FLAG_MODES.size());
    index /= FLAG_MODES.size();
    int wrapperIndex = (int) (index % WRAPPERS.size());
    index /= WRAPPERS.size();
    int atomIndex = (int) index;
    return new CaseSpec(
        ATOMS.get(atomIndex),
        WRAPPERS.get(wrapperIndex),
        FLAG_MODES.get(flagIndex),
        TEXT_REGIONS.get(textRegionIndex),
        BOUNDS_MODES.get(boundsIndex));
  }

  private static CaseSpec replayCase(String line) {
    JsonObject object = SweepJson.parseObject(line);
    JsonObject caseObject = SweepJson.object(object, "case");
    return new CaseSpec(
        atom(SweepJson.string(caseObject, "atomLabel"), SweepJson.string(caseObject, "atomRegex")),
        wrapper(
            SweepJson.string(caseObject, "wrapperLabel"),
            SweepJson.string(caseObject, "wrapperTemplate")),
        flags(SweepJson.string(caseObject, "flagLabel"), SweepJson.integer(caseObject, "flags")),
        new TextRegion(
            SweepJson.string(caseObject, "regionLabel"),
            SweepJson.string(caseObject, "text"),
            SweepJson.integer(caseObject, "regionStart"),
            SweepJson.integer(caseObject, "regionEnd")),
        bounds(
            SweepJson.string(caseObject, "boundsLabel"),
            SweepJson.bool(caseObject, "transparentBounds"),
            SweepJson.bool(caseObject, "anchoringBounds")));
  }

  private static String compactReplayJson(long caseIndex, String classification, CaseSpec spec) {
    JsonObject object = SweepJson.object();
    object.addProperty("caseIndex", caseIndex);
    object.addProperty("classification", classification);
    object.add("case", Divergence.caseJson(spec));
    return SweepJson.toJson(object);
  }

  private static List<TextRegion> buildTextRegions() {
    return List.of(
        region("empty", "", 0, 0),
        region("asciiA", "a", 0, 1),
        region("asciiB", "b", 0, 1),
        region("digit", "5", 0, 1),
        region("space", " ", 0, 1),
        region("newline", "\n", 0, 1),
        region("carriageReturn", "\r", 0, 1),
        region("nel", "\u0085", 0, 1),
        region("combiningMark", "\u0301", 0, 1),
        region("zwj", "\u200D", 0, 1),
        region("arabicNumberSign", "\u0600", 0, 1),
        region("devanagariSpacingMark", "\u0903", 0, 1),
        region("hangulL", "\u1100", 0, 1),
        region("loneHighSurrogate", "\uD83D", 0, 1),
        region("loneLowSurrogate", "\uDE00", 0, 1),
        region("emojiFull", "\uD83D\uDE00", 0, 2),
        region("emojiHighOnly", "\uD83D\uDE00", 0, 1),
        region("emojiLowOnly", "\uD83D\uDE00", 1, 2),
        region("emojiEmptyInside", "\uD83D\uDE00", 1, 1),
        region("emojiPrefixedHighOnly", "a\uD83D\uDE00", 1, 2),
        region("emojiPrefixedLowOnly", "a\uD83D\uDE00", 2, 3),
        region("emojiPrefixedEndsAfterHigh", "a\uD83D\uDE00", 0, 2),
        region("emojiSuffixedHighOnly", "\uD83D\uDE00a", 0, 1),
        region("emojiSuffixedLowOnly", "\uD83D\uDE00a", 1, 2),
        region("emojiSuffixedFull", "\uD83D\uDE00a", 0, 2),
        region("twoEmojiFirstHighOnly", "\uD83D\uDE00\uD83D\uDE00", 0, 1),
        region("twoEmojiMiddleLowHigh", "\uD83D\uDE00\uD83D\uDE00", 1, 3),
        region("twoEmojiSecondHighOnly", "\uD83D\uDE00\uD83D\uDE00", 2, 3),
        region("regionalIndicatorFull", "\uD83C\uDDFA", 0, 2),
        region("regionalIndicatorHighOnly", "\uD83C\uDDFA", 0, 1),
        region("regionalIndicatorLowOnly", "\uD83C\uDDFA", 1, 2),
        region("deseretUpperFull", "\uD801\uDC00", 0, 2),
        region("deseretUpperHighOnly", "\uD801\uDC00", 0, 1),
        region("deseretUpperLowOnly", "\uD801\uDC00", 1, 2),
        region("mathDigitFull", "\uD835\uDFCE", 0, 2),
        region("mathDigitHighOnly", "\uD835\uDFCE", 0, 1),
        region("mathDigitLowOnly", "\uD835\uDFCE", 1, 2),
        region("invalidLowHighFull", "\uDE00\uD83D", 0, 2),
        region("invalidLowHighLowOnly", "\uDE00\uD83D", 0, 1),
        region("invalidLowHighHighOnly", "\uDE00\uD83D", 1, 2),
        region("splitBeforeCombining", "\uD83D\uDE00\u0301", 0, 1),
        region("lowBeforeCombining", "\uD83D\uDE00\u0301", 1, 3),
        region("splitBeforeZwj", "\uD83D\uDE00\u200D", 0, 1),
        region("lowBeforeZwj", "\uD83D\uDE00\u200D", 1, 3),
        region("splitBeforeModifier", "\uD83D\uDC4D\uD83C\uDFFD", 0, 1),
        region("lowBeforeModifier", "\uD83D\uDC4D\uD83C\uDFFD", 1, 3),
        region("asciiThenSplitThenAscii", "x\uD83D\uDE00y", 1, 2),
        region("asciiThenLowThenAscii", "x\uD83D\uDE00y", 2, 3),
        region("asciiThroughSplitHigh", "x\uD83D\uDE00y", 0, 2),
        region("splitHighThroughAscii", "x\uD83D\uDE00y", 1, 4),
        region("emptyBeforeHigh", "x\uD83D\uDE00y", 1, 1),
        region("emptyInsidePair", "x\uD83D\uDE00y", 2, 2),
        region("emptyAfterLow", "x\uD83D\uDE00y", 3, 3),
        region("finalCrLfSplitCrOnly", "\r\n", 0, 1),
        region("finalCrLfLfOnly", "\r\n", 1, 2),
        region("baseBeforeMark", "a\u0301", 0, 1),
        region("markOnlyAfterBase", "a\u0301", 1, 2));
  }

  private static AtomCase atom(String label, String regex) {
    return new AtomCase(label, regex);
  }

  private static WrapperCase wrapper(String label, String template) {
    return new WrapperCase(label, template);
  }

  private static FlagMode flags(String label, int flags) {
    return new FlagMode(label, flags);
  }

  private static BoundsMode bounds(
      String label, boolean transparentBounds, boolean anchoringBounds) {
    return new BoundsMode(label, transparentBounds, anchoringBounds);
  }

  private static TextRegion region(String label, String text, int start, int end) {
    return new TextRegion(label, text, start, end);
  }

  private static void appendSeparator(StringBuilder result) {
    if (result.length() > 0) {
      result.append(',');
    }
  }

  private static String escape(String value) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> result.append("\\\\");
        case '\n' -> result.append("\\n");
        case '\r' -> result.append("\\r");
        case '\t' -> result.append("\\t");
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

  private record AtomCase(String label, String regex) {}

  private record WrapperCase(String label, String template) {
    String regex(String atom) {
      return template.formatted(atom, atom);
    }
  }

  private record FlagMode(String label, int flags) {}

  private record BoundsMode(String label, boolean transparentBounds, boolean anchoringBounds) {}

  private record TextRegion(String label, String text, int start, int end) {}

  private record CaseSpec(
      AtomCase atomCase,
      WrapperCase wrapperCase,
      FlagMode flagMode,
      TextRegion textRegion,
      BoundsMode boundsMode) {
    String regex() {
      return wrapperCase.regex(atomCase.regex());
    }
  }

  private record Outcome(boolean accepted, String trace, String error) {}

  private record Divergence(
      CaseSpec spec, Outcome jdk, Outcome safere, DivergenceClass classification) {
    String toJson() {
      JsonObject object = SweepJson.object();
      object.add("case", caseJson(spec));
      object.addProperty("bucket", bucket());
      object.addProperty("classification", classification.name());
      object.addProperty("classificationStatus", classification.status().name());
      object.addProperty("regex", spec.regex());
      object.addProperty("text", spec.textRegion().text());
      object.addProperty("regionStart", spec.textRegion().start());
      object.addProperty("regionEnd", spec.textRegion().end());
      object.addProperty("jdkAccepted", jdk.accepted());
      object.addProperty("safeReAccepted", safere.accepted());
      object.addProperty("jdkTrace", jdk.trace());
      object.addProperty("safeReTrace", safere.trace());
      object.addProperty("jdkError", jdk.error());
      object.addProperty("safeReError", safere.error());
      return SweepJson.toJson(object);
    }

    private String bucket() {
      return "atom="
          + spec.atomCase().label()
          + ";wrapper="
          + spec.wrapperCase().label()
          + ";region="
          + spec.textRegion().label()
          + ";bounds="
          + spec.boundsMode().label();
    }

    private static JsonObject caseJson(CaseSpec spec) {
      JsonObject object = SweepJson.object();
      object.addProperty("atomLabel", spec.atomCase().label());
      object.addProperty("atomRegex", spec.atomCase().regex());
      object.addProperty("wrapperLabel", spec.wrapperCase().label());
      object.addProperty("wrapperTemplate", spec.wrapperCase().template());
      object.addProperty("flagLabel", spec.flagMode().label());
      object.addProperty("flags", spec.flagMode().flags());
      object.addProperty("regionLabel", spec.textRegion().label());
      object.addProperty("text", spec.textRegion().text());
      object.addProperty("regionStart", spec.textRegion().start());
      object.addProperty("regionEnd", spec.textRegion().end());
      object.addProperty("boundsLabel", spec.boundsMode().label());
      object.addProperty("transparentBounds", spec.boundsMode().transparentBounds());
      object.addProperty("anchoringBounds", spec.boundsMode().anchoringBounds());
      return object;
    }
  }

  private interface GroupInt {
    int get(int group);
  }

  private interface GroupString {
    String get(int group);
  }

  private enum DivergenceClass {
    QUANTIFIED_SPLIT_SURROGATE_SCALAR_COMPOSITION(
        DivergenceStatus.KNOWN_INTENTIONAL,
        "Observed JDK traces allow greedy quantified scalar atoms to consume a high surrogate at"
            + " a region end that splits a valid surrogate pair. SafeRE keeps scalar consumption"
            + " compositional, so a quantified atom cannot consume a scalar that its unquantified"
            + " form cannot consume at the same region boundary."),
    UNKNOWN(DivergenceStatus.UNKNOWN, "Unclassified SafeRE/JDK region scalar divergence.");

    private final DivergenceStatus status;
    private final String rationale;

    DivergenceClass(DivergenceStatus status, String rationale) {
      this.status = status;
      this.rationale = rationale;
    }

    DivergenceStatus status() {
      return status;
    }

    String rationale() {
      return rationale;
    }
  }
}
