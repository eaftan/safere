// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.exhaustive;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Offline differential sweep for reverse-DFA sandwich leftmost-start semantics. */
public final class DfaSandwichLeftmostStartDivergenceSweep {
  private static final int FIND_LIMIT = 8;
  private static final int FIRST_UNKNOWN_LIMIT = 100;
  private static final int ACTIONABLE_SAMPLE_LIMIT = 100;
  private static final long DEFAULT_PROGRESS_INTERVAL = 100_000;

  private static final List<PrefixCase> PREFIXES =
      List.of(
          prefix("empty", ""),
          prefix("emptyNonCapturing", "(?:)"),
          prefix("nonWordBoundary", "\\B"),
          prefix("wordBoundary", "\\b"),
          prefix("emptyThenNonWordBoundary", "(?:)\\B"),
          prefix("emptyThenWordBoundary", "(?:)\\b"));

  private static final List<MiddleCase> MIDDLES =
      List.of(
          middle("empty", ""),
          middle("negatedAStar", "[^a]*"),
          middle("negatedAStarCaptured", "([^a])*"),
          middle("negatedAStarNonCapturing", "(?:[^a])*"),
          middle("dotStar", ".*"),
          middle("dotStarCaptured", "(.)*"),
          middle("shortFirstAlternativeStar", "(?:b|bb)*"),
          middle("longFirstAlternativeStar", "(?:bb|b)*"),
          middle("shortFirstAlternativeStarCaptured", "(b|bb)*"),
          middle("boundedNegatedA", "[^a]{0,3}"),
          middle("boundedNegatedARepeated", "(?:[^a]{1,2})*"),
          middle("nullableBRepeated", "(?:b?)*"));

  private static final List<SuffixCase> SUFFIXES =
      List.of(
          suffix("negatedAPair", "[^a][^a]"),
          suffix("negatedATriple", "[^a][^a][^a]"),
          suffix("literalBPair", "bb"),
          suffix("literalBTriple", "bbb"),
          suffix("literalBThenNegatedA", "b[^a]"),
          suffix("anyClassPair", "[\\s\\S][\\s\\S]"),
          suffix("dotPair", ".."));

  private static final List<ContextCase> CONTEXTS =
      List.of(
          context("bare", "%s"),
          context("capturedWhole", "(%s)"),
          context("nonCapturedWhole", "(?:%s)"),
          context("alternativeAfterLiteralA", "a|%s"),
          context("alternativeBeforeLiteralA", "%s|a"),
          context("alternativeAfterLiteralBB", "bb|%s"),
          context("alternativeBeforeLiteralBB", "%s|bb"));

  private static final List<FlagMode> FLAG_MODES =
      List.of(
          flags("none", 0),
          flags("dotall", java.util.regex.Pattern.DOTALL),
          flags("multiline", java.util.regex.Pattern.MULTILINE),
          flags("unicodeCharacterClass", java.util.regex.Pattern.UNICODE_CHARACTER_CLASS));

  private static final List<String> INPUTS =
      List.of(
          "", "a", "b", "bb", "bbb", "bbbb", "bbbbb", "abbbb", "bbbbx", "xbbbb", "baabbbb", "!!!!",
          "!!", "a_bbbb", "bb\nbb", "\nbbbb", "bbbb\n");

  private DfaSandwichLeftmostStartDivergenceSweep() {}

  public static void main(String[] args) throws IOException {
    SweepOptions options =
        SweepOptions.parse(
            args,
            Path.of("target", "exhaustive-reports", "dfa-sandwich-leftmost-start-sweep"),
            "dfa-sandwich-leftmost-start-divergences.jsonl",
            DEFAULT_PROGRESS_INTERVAL);
    Files.createDirectories(options.outputDir());
    if (options.replayFile() != null) {
      Files.deleteIfExists(options.jsonlPath());
    }
    options.printStartup("dfa-sandwich-leftmost-start");

    if (options.replayFile() != null) {
      runReplay(options);
      return;
    }

    ClassifiedDivergenceSummary<DivergenceClass> summary = newSummary();
    SweepRunState state = runSweep(options, summary);
    summary.writeReports(options.outputDir());
    System.out.println("checked=" + state.checked.sum());
    System.out.println("generated=" + state.generated);
    System.out.println("totalCases=" + totalCases());
    System.out.println("divergences=" + state.divergences.sum());
    System.out.println("actionableDivergences=" + summary.actionableCount());
    System.out.println("unknownDivergences=" + summary.count(DivergenceClass.UNKNOWN));
    System.out.println("threads=" + options.threads());
  }

  static long totalCases() {
    return (long) PREFIXES.size()
        * MIDDLES.size()
        * SUFFIXES.size()
        * CONTEXTS.size()
        * FLAG_MODES.size();
  }

  static String compactReplayJson(long caseIndex, String classification) {
    CaseSpec spec = caseFromIndex(caseIndex);
    return divergenceJson(
        spec,
        spec.regex(),
        new RegexSweep.Outcome(false, "", ""),
        new RegexSweep.Outcome(false, "", ""),
        DivergenceClass.valueOf(classification),
        caseIndex);
  }

  private static SweepRunState runSweep(
      SweepOptions options, ClassifiedDivergenceSummary<DivergenceClass> summary)
      throws IOException {
    try (SweepRunState runState = new SweepRunState(options, options.totalChecks(totalCases()))) {
      runState.enableCompactLogs(
          "dfa-sandwich-leftmost-start",
          totalCases(),
          divergenceClassificationNames(),
          divergenceClassificationStatuses());
      SweepWorkers.run(
          options.threads(),
          "dfa-sandwich-leftmost-start-sweep-",
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
              evaluateCase(runState, summary, caseFromIndex(caseIndex), workerIndex, caseIndex);
              progressReporter.reportIfNeeded(generated);
            }
            runState.recordGenerated(generated);
            runState.updateWorkerNextCaseIndex(workerIndex, generated);
          });
      return runState;
    }
  }

  private static void runReplay(SweepOptions options) throws IOException {
    ClassifiedDivergenceSummary<DivergenceClass> summary = newSummary();
    try (BufferedReader reader =
            Files.newBufferedReader(options.replayFile(), StandardCharsets.UTF_8);
        SweepRunState runState = new SweepRunState(options, 0)) {
      long generated =
          SweepWorkers.runStreamingLines(
              options.threads(),
              "dfa-sandwich-leftmost-start-replay-",
              reader,
              line -> {
                CaseSpec spec = replayCaseOrNull(line);
                if (spec == null) {
                  return;
                }
                runState.checked.increment();
                evaluateCase(runState, summary, spec, -1, -1);
              });
      runState.recordGenerated(generated);
      summary.writeReports(options.outputDir());
      System.out.println("checked=" + runState.checked.sum());
      System.out.println("generated=" + runState.generated);
      System.out.println("divergences=" + runState.divergences.sum());
      System.out.println("actionableDivergences=" + summary.actionableCount());
      System.out.println("unknownDivergences=" + summary.count(DivergenceClass.UNKNOWN));
      System.out.println("threads=" + options.threads());
      System.out.println("jsonl=" + options.jsonlPath());
      if (summary.actionableCount() > 0 || summary.count(DivergenceClass.UNKNOWN) > 0) {
        throw new IllegalStateException(
            "replay found "
                + summary.actionableCount()
                + " actionable and "
                + summary.count(DivergenceClass.UNKNOWN)
                + " unknown behavioral divergences");
      }
    }
  }

  private static CaseSpec replayCaseOrNull(String line) {
    JsonObject object = SweepJson.parseObjectOrNull(line);
    if (object == null) {
      return null;
    }
    JsonObject caseObject = SweepJson.object(object, "case");
    return new CaseSpec(
        new PrefixCase(
            SweepJson.string(caseObject, "prefixLabel"),
            SweepJson.string(caseObject, "prefixRegex")),
        new MiddleCase(
            SweepJson.string(caseObject, "middleLabel"),
            SweepJson.string(caseObject, "middleRegex")),
        new SuffixCase(
            SweepJson.string(caseObject, "suffixLabel"),
            SweepJson.string(caseObject, "suffixRegex")),
        new ContextCase(
            SweepJson.string(caseObject, "contextLabel"),
            SweepJson.string(caseObject, "contextTemplate")),
        new FlagMode(
            SweepJson.string(caseObject, "flagLabel"), SweepJson.integer(caseObject, "flags")));
  }

  private static void evaluateCase(
      SweepRunState runState,
      ClassifiedDivergenceSummary<DivergenceClass> summary,
      CaseSpec spec,
      int workerIndex,
      long caseIndex) {
    String regex = spec.regex();
    RegexSweep.Outcome jdk =
        RegexSweep.jdkTraceOutcome(regex, spec.flagMode().flags(), INPUTS, FIND_LIMIT);
    RegexSweep.Outcome safere =
        RegexSweep.safeReTraceOutcome(regex, spec.flagMode().flags(), INPUTS, FIND_LIMIT);
    if (RegexSweep.semanticallyEqual(jdk, safere)) {
      return;
    }

    DivergenceClass classification = classify(jdk, safere);
    String json = divergenceJson(spec, regex, jdk, safere, classification, caseIndex);
    if (workerIndex >= 0 && caseIndex >= 0) {
      runState.recordCompactDivergence(workerIndex, caseIndex, classification.ordinal());
    } else {
      runState.recordDivergence();
    }
    runState.appendJsonl(json);
    summary.record(classification, caseIndex, json);
  }

  private static DivergenceClass classify(RegexSweep.Outcome jdk, RegexSweep.Outcome safere) {
    if (jdk.accepted() != safere.accepted()) {
      return DivergenceClass.ACCEPTANCE_MISMATCH;
    }
    if (jdk.accepted()) {
      return DivergenceClass.TRACE_MISMATCH;
    }
    return DivergenceClass.UNKNOWN;
  }

  private static String divergenceJson(
      CaseSpec spec,
      String regex,
      RegexSweep.Outcome jdk,
      RegexSweep.Outcome safere,
      DivergenceClass classification,
      long caseIndex) {
    JsonObject root = SweepJson.object();
    root.addProperty("classification", classification.name());
    root.addProperty("caseIndex", caseIndex);
    root.add("case", caseJson(spec, regex));
    root.add("jdk", outcomeJson(jdk));
    root.add("safere", outcomeJson(safere));
    return SweepJson.toJson(root);
  }

  private static JsonObject caseJson(CaseSpec spec, String regex) {
    JsonObject object = SweepJson.object();
    object.addProperty("prefixLabel", spec.prefix().label());
    object.addProperty("prefixRegex", spec.prefix().regex());
    object.addProperty("middleLabel", spec.middle().label());
    object.addProperty("middleRegex", spec.middle().regex());
    object.addProperty("suffixLabel", spec.suffix().label());
    object.addProperty("suffixRegex", spec.suffix().regex());
    object.addProperty("contextLabel", spec.context().label());
    object.addProperty("contextTemplate", spec.context().template());
    object.addProperty("flagLabel", spec.flagMode().label());
    object.addProperty("flags", spec.flagMode().flags());
    object.addProperty("regex", regex);
    JsonArray inputs = new JsonArray();
    for (String input : INPUTS) {
      inputs.add(input);
    }
    object.add("inputs", inputs);
    return object;
  }

  private static JsonObject outcomeJson(RegexSweep.Outcome outcome) {
    JsonObject object = SweepJson.object();
    object.addProperty("accepted", outcome.accepted());
    object.addProperty("trace", outcome.trace());
    object.addProperty("error", outcome.error());
    return object;
  }

  private static ClassifiedDivergenceSummary<DivergenceClass> newSummary() {
    return new ClassifiedDivergenceSummary<>(
        DivergenceClass.class,
        DivergenceClass.UNKNOWN,
        "dfa-sandwich-leftmost-start",
        FIRST_UNKNOWN_LIMIT,
        ACTIONABLE_SAMPLE_LIMIT);
  }

  private static List<String> divergenceClassificationNames() {
    return java.util.Arrays.stream(DivergenceClass.values()).map(Enum::name).toList();
  }

  private static List<DivergenceStatus> divergenceClassificationStatuses() {
    return java.util.Arrays.stream(DivergenceClass.values())
        .map(DivergenceClassification::status)
        .toList();
  }

  private static CaseSpec caseFromIndex(long index) {
    int flagIndex = (int) (index % FLAG_MODES.size());
    index /= FLAG_MODES.size();
    int contextIndex = (int) (index % CONTEXTS.size());
    index /= CONTEXTS.size();
    int suffixIndex = (int) (index % SUFFIXES.size());
    index /= SUFFIXES.size();
    int middleIndex = (int) (index % MIDDLES.size());
    index /= MIDDLES.size();
    int prefixIndex = (int) (index % PREFIXES.size());
    return new CaseSpec(
        PREFIXES.get(prefixIndex),
        MIDDLES.get(middleIndex),
        SUFFIXES.get(suffixIndex),
        CONTEXTS.get(contextIndex),
        FLAG_MODES.get(flagIndex));
  }

  private static PrefixCase prefix(String label, String regex) {
    return new PrefixCase(label, regex);
  }

  private static MiddleCase middle(String label, String regex) {
    return new MiddleCase(label, regex);
  }

  private static SuffixCase suffix(String label, String regex) {
    return new SuffixCase(label, regex);
  }

  private static ContextCase context(String label, String template) {
    return new ContextCase(label, template);
  }

  private static FlagMode flags(String label, int flags) {
    return new FlagMode(label, flags);
  }

  private enum DivergenceClass implements DivergenceClassification {
    ACCEPTANCE_MISMATCH(
        DivergenceStatus.EXPECTED_ZERO,
        "DFA sandwich leftmost-start cases use supported regex syntax and must compile the same as"
            + " java.util.regex."),
    TRACE_MISMATCH(
        DivergenceStatus.EXPECTED_ZERO,
        "DFA sandwich optimizations must preserve java.util.regex traces for matches(),"
            + " lookingAt(), find(), captures, and replacements."),
    UNKNOWN(DivergenceStatus.UNKNOWN, "Unclassified DFA sandwich leftmost-start sweep divergence.");

    private final DivergenceStatus status;
    private final String rationale;

    DivergenceClass(DivergenceStatus status, String rationale) {
      this.status = status;
      this.rationale = rationale;
    }

    @Override
    public DivergenceStatus status() {
      return status;
    }

    @Override
    public String rationale() {
      return rationale;
    }
  }

  private record PrefixCase(String label, String regex) {}

  private record MiddleCase(String label, String regex) {}

  private record SuffixCase(String label, String regex) {}

  private record ContextCase(String label, String template) {}

  private record FlagMode(String label, int flags) {}

  private record CaseSpec(
      PrefixCase prefix,
      MiddleCase middle,
      SuffixCase suffix,
      ContextCase context,
      FlagMode flagMode) {
    String regex() {
      return context.template().formatted(prefix.regex() + middle.regex() + suffix.regex());
    }
  }
}
