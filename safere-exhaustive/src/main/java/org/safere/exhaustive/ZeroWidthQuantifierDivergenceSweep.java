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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Offline differential sweep for repeated quantifiers over zero-width operands. */
public final class ZeroWidthQuantifierDivergenceSweep {
  private static final int FIND_LIMIT = 16;

  private static final List<Atom> ZERO_WIDTH_ATOMS =
      List.of(
          atom("empty", ""),
          atom("emptyCapturing", "()"),
          atom("emptyNonCapturing", "(?:)"),
          atom("beginLine", "^"),
          atom("endLine", "$"),
          atom("beginText", "\\A"),
          atom("endTextBeforeFinalTerminator", "\\Z"),
          atom("endText", "\\z"),
          atom("wordBoundary", "\\b"),
          atom("nonWordBoundary", "\\B"),
          atom("graphemeBoundary", "\\b{g}"));

  private static final List<Operand> OPERANDS = buildOperands();

  private static final List<Wrapper> WRAPPERS =
      List.of(
          wrapper("bare", "%s"),
          wrapper("capturing", "(%s)"),
          wrapper("nonCapturing", "(?:%s)"),
          wrapper("nestedGroups", "(?:(%s))"));

  private static final List<Quantifier> FIRST_QUANTIFIERS =
      List.of(
          quantifier("star", "*"),
          quantifier("plus", "+"),
          quantifier("question", "?"),
          quantifier("repeatZero", "{0}"),
          quantifier("repeatOne", "{1}"),
          quantifier("repeatTwo", "{2}"),
          quantifier("repeatRange", "{0,2}"),
          quantifier("repeatAtLeastOne", "{1,}"));

  private static final List<Quantifier> SUFFIX_QUANTIFIERS = buildSuffixQuantifiers();

  private static final List<Context> CONTEXTS =
      List.of(
          context("bare", "%s"),
          context("capturedWhole", "(%s)"),
          context("nonCapturedWhole", "(?:%s)"),
          context("scopedComments", "(?x:%s)"),
          context("prefixLiteral", "a%s"),
          context("suffixLiteral", "%sa"),
          context("surroundingLiterals", "a%sb"),
          context("anchoredSurroundingLiterals", "^a%sb$"),
          context("embeddedMultilineAnchored", "(?m:^%s$)"));

  private static final List<FlagMode> FLAG_MODES =
      List.of(
          new FlagMode("none", "", 0, ""),
          new FlagMode("commentsFlag", "", java.util.regex.Pattern.COMMENTS, " "),
          new FlagMode("commentsFlagTab", "", java.util.regex.Pattern.COMMENTS, "\t"),
          new FlagMode("commentsFlagComment", "", java.util.regex.Pattern.COMMENTS, "#q\n"),
          new FlagMode("commentsEmbedded", "(?x)", 0, " "),
          new FlagMode("commentsEmbeddedComment", "(?x)", 0, "#q\n"));

  private ZeroWidthQuantifierDivergenceSweep() {}

  public static void main(String[] args) throws IOException {
    SweepOptions options =
        SweepOptions.parse(
            args,
            Path.of("target", "exhaustive-reports", "zero-width-quantifier-sweep"),
            "zero-width-quantifier-divergences.jsonl",
            100_000);
    Files.createDirectories(options.outputDir());
    Files.deleteIfExists(options.jsonlPath());
    options.printStartup("zero-width-quantifier");

    if (options.replayFile() != null) {
      runReplay(options);
      return;
    }

    SweepRunState state = runSweep(options);

    System.out.println("checked=" + state.checked.sum());
    System.out.println("generated=" + state.generated);
    System.out.println("totalCases=" + totalCases());
    System.out.println("divergences=" + state.divergences.sum());
    System.out.println("threads=" + options.threads());
    System.out.println("jsonl=" + options.jsonlPath());
  }

  private static SweepRunState runSweep(SweepOptions options) throws IOException {
    try (SweepRunState runState = new SweepRunState(options, options.totalChecks(totalCases()))) {
      SweepWorkers.run(
          options.threads(),
          "zero-width-quantifier-sweep-",
          workerIndex -> {
            SweepState worker = new SweepState(runState, workerIndex);
            worker.run();
            worker.finish();
          });
      return runState;
    }
  }

  private static void runReplay(SweepOptions options) throws IOException {
    try (BufferedReader reader =
            Files.newBufferedReader(options.replayFile(), StandardCharsets.UTF_8);
        SweepRunState runState = new SweepRunState(options, 0)) {
      long generated =
          SweepWorkers.runStreamingLines(
              options.threads(),
              "zero-width-quantifier-replay-",
              reader,
              line -> {
                runState.checked.increment();
                evaluateCase(runState, replayCase(line));
              });
      runState.recordGenerated(generated);
      System.out.println("checked=" + runState.checked.sum());
      System.out.println("generated=" + runState.generated);
      System.out.println("divergences=" + runState.divergences.sum());
      System.out.println("threads=" + options.threads());
      System.out.println("jsonl=" + options.jsonlPath());
      if (runState.divergences.sum() > 0) {
        throw new IllegalStateException(
            "replay found " + runState.divergences.sum() + " behavioral divergences");
      }
    }
  }

  private static CaseSpec replayCase(String line) {
    var object = SweepJson.parseObject(line);
    var caseObject = SweepJson.object(object, "case");
    return new CaseSpec(
        new Operand(
            SweepJson.string(caseObject, "operandLabel"),
            SweepJson.string(caseObject, "operandRegex")),
        new Wrapper(
            SweepJson.string(caseObject, "wrapperLabel"),
            SweepJson.string(caseObject, "wrapperTemplate")),
        new Quantifier(
            SweepJson.string(caseObject, "firstQuantifierLabel"),
            SweepJson.string(caseObject, "firstQuantifier")),
        new Quantifier(
            SweepJson.string(caseObject, "suffixQuantifierLabel"),
            SweepJson.string(caseObject, "suffixQuantifier")),
        new Context(
            SweepJson.string(caseObject, "contextLabel"),
            SweepJson.string(caseObject, "contextTemplate")),
        new FlagMode(
            SweepJson.string(caseObject, "flagLabel"),
            SweepJson.string(caseObject, "flagPrefix"),
            SweepJson.integer(caseObject, "flags"),
            SweepJson.string(caseObject, "trivia")));
  }

  private static long totalCases() {
    return (long) OPERANDS.size()
        * WRAPPERS.size()
        * FIRST_QUANTIFIERS.size()
        * SUFFIX_QUANTIFIERS.size()
        * CONTEXTS.size()
        * FLAG_MODES.size();
  }

  private static CaseSpec caseAt(long index) {
    int flagIndex = (int) (index % FLAG_MODES.size());
    index /= FLAG_MODES.size();
    int contextIndex = (int) (index % CONTEXTS.size());
    index /= CONTEXTS.size();
    int suffixIndex = (int) (index % SUFFIX_QUANTIFIERS.size());
    index /= SUFFIX_QUANTIFIERS.size();
    int firstIndex = (int) (index % FIRST_QUANTIFIERS.size());
    index /= FIRST_QUANTIFIERS.size();
    int wrapperIndex = (int) (index % WRAPPERS.size());
    index /= WRAPPERS.size();
    int operandIndex = (int) index;
    return new CaseSpec(
        OPERANDS.get(operandIndex),
        WRAPPERS.get(wrapperIndex),
        FIRST_QUANTIFIERS.get(firstIndex),
        SUFFIX_QUANTIFIERS.get(suffixIndex),
        CONTEXTS.get(contextIndex),
        FLAG_MODES.get(flagIndex));
  }

  static boolean containsAllGeneratedRegexesForTesting(List<String> regexes) {
    Set<String> remaining = new LinkedHashSet<>(regexes);
    for (long index = 0; index < totalCases() && !remaining.isEmpty(); index++) {
      remaining.remove(caseAt(index).regex());
    }
    return remaining.isEmpty();
  }

  private static String bucketFor(
      CaseSpec spec, RegexSweep.Outcome jdk, RegexSweep.Outcome safere) {
    String kind = jdk.accepted() != safere.accepted() ? "compile" : "behavior";
    return String.join(
        "|",
        "kind=" + kind,
        "direction=" + direction(jdk, safere),
        "operand=" + spec.operand().label(),
        "wrapper=" + spec.wrapper().label(),
        "first=" + spec.firstQuantifier().label(),
        "suffix=" + spec.suffixQuantifier().label(),
        "context=" + spec.context().label(),
        "flags=" + spec.flagMode().label());
  }

  private static String direction(RegexSweep.Outcome jdk, RegexSweep.Outcome safere) {
    if (jdk.accepted() && !safere.accepted()) {
      return "jdk-accepts";
    }
    if (!jdk.accepted() && safere.accepted()) {
      return "safere-accepts";
    }
    return "matches";
  }

  private static List<Operand> buildOperands() {
    List<Operand> operands = new ArrayList<>();
    for (Atom atom : ZERO_WIDTH_ATOMS) {
      operands.add(operand("atom:" + atom.label(), atom.regex()));
    }
    for (Atom left : ZERO_WIDTH_ATOMS) {
      for (Atom right : ZERO_WIDTH_ATOMS) {
        operands.add(
            operand("concat:" + left.label() + "+" + right.label(), left.regex() + right.regex()));
      }
    }
    for (Atom left : ZERO_WIDTH_ATOMS) {
      for (Atom right : ZERO_WIDTH_ATOMS) {
        operands.add(
            operand(
                "alternate:" + left.label() + "|" + right.label(),
                "(?:" + left.regex() + "|" + right.regex() + ")"));
      }
    }
    return List.copyOf(operands);
  }

  private static List<Quantifier> buildSuffixQuantifiers() {
    List<Quantifier> quantifiers = new ArrayList<>();
    List<Quantifier> bases =
        List.of(
            quantifier("star", "*"),
            quantifier("plus", "+"),
            quantifier("question", "?"),
            quantifier("repeatZero", "{0}"),
            quantifier("repeatOne", "{1}"),
            quantifier("repeatTwo", "{2}"),
            quantifier("repeatRange", "{0,2}"));
    for (Quantifier base : bases) {
      quantifiers.add(base);
      quantifiers.add(quantifier(base.label() + "Reluctant", base.text() + "?"));
      quantifiers.add(quantifier(base.label() + "Possessive", base.text() + "+"));
    }
    return List.copyOf(quantifiers);
  }

  private static Atom atom(String label, String regex) {
    return new Atom(label, regex);
  }

  private static Operand operand(String label, String regex) {
    return new Operand(label, regex);
  }

  private static Wrapper wrapper(String label, String template) {
    return new Wrapper(label, template);
  }

  private static Quantifier quantifier(String label, String text) {
    return new Quantifier(label, text);
  }

  private static Context context(String label, String template) {
    return new Context(label, template);
  }

  private record Atom(String label, String regex) {}

  private record Operand(String label, String regex) {}

  private record Wrapper(String label, String template) {
    String regex(String operand) {
      return template.formatted(operand);
    }
  }

  private record Quantifier(String label, String text) {}

  private record Context(String label, String template) {
    String regex(String repeated) {
      return template.formatted(repeated);
    }
  }

  private record FlagMode(String label, String prefix, int flags, String trivia) {}

  private record CaseSpec(
      Operand operand,
      Wrapper wrapper,
      Quantifier firstQuantifier,
      Quantifier suffixQuantifier,
      Context context,
      FlagMode flagMode) {
    String regex() {
      String quantified =
          wrapper.regex(operand.regex())
              + flagMode.trivia()
              + firstQuantifier.text()
              + flagMode.trivia()
              + suffixQuantifier.text();
      return flagMode.prefix() + context.regex(quantified);
    }

    List<String> inputs() {
      Set<String> inputs = new LinkedHashSet<>();
      inputs.add("");
      inputs.add("a");
      inputs.add("b");
      inputs.add("ab");
      inputs.add("aa");
      inputs.add("ba");
      inputs.add("\n");
      inputs.add("a\n");
      inputs.add("\na");
      inputs.add("\u00E9");
      inputs.add("e\u0301");
      inputs.add("\uD83D\uDC69\u200D\uD83D\uDCBB");
      return List.copyOf(inputs);
    }

    String labels() {
      return "operand="
          + operand.label()
          + ",wrapper="
          + wrapper.label()
          + ",first="
          + firstQuantifier.label()
          + ",suffix="
          + suffixQuantifier.label()
          + ",context="
          + context.label()
          + ",flags="
          + flagMode.label();
    }
  }

  private record Divergence(
      CaseSpec spec, RegexSweep.Outcome jdk, RegexSweep.Outcome safere, String bucket) {
    String toJson() {
      var object = SweepJson.object();
      object.add("case", caseJson(spec));
      object.addProperty("bucket", bucket);
      object.addProperty("labels", spec.labels());
      object.addProperty("regex", spec.regex());
      object.addProperty("operand", spec.operand().label());
      object.addProperty("wrapper", spec.wrapper().label());
      object.addProperty("firstQuantifier", spec.firstQuantifier().label());
      object.addProperty("suffixQuantifier", spec.suffixQuantifier().label());
      object.addProperty("context", spec.context().label());
      object.addProperty("flags", spec.flagMode().label());
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
      object.addProperty("operandLabel", spec.operand().label());
      object.addProperty("operandRegex", spec.operand().regex());
      object.addProperty("wrapperLabel", spec.wrapper().label());
      object.addProperty("wrapperTemplate", spec.wrapper().template());
      object.addProperty("firstQuantifierLabel", spec.firstQuantifier().label());
      object.addProperty("firstQuantifier", spec.firstQuantifier().text());
      object.addProperty("suffixQuantifierLabel", spec.suffixQuantifier().label());
      object.addProperty("suffixQuantifier", spec.suffixQuantifier().text());
      object.addProperty("contextLabel", spec.context().label());
      object.addProperty("contextTemplate", spec.context().template());
      object.addProperty("flagLabel", spec.flagMode().label());
      object.addProperty("flagPrefix", spec.flagMode().prefix());
      object.addProperty("flags", spec.flagMode().flags());
      object.addProperty("trivia", spec.flagMode().trivia());
      return object;
    }
  }

  private static final class SweepState {
    final SweepRunState runState;
    final SweepOptions options;
    final SweepWorkers.ProgressReporter progressReporter;
    final int workerIndex;
    long generated;

    SweepState(SweepRunState runState, int workerIndex) {
      this.runState = runState;
      this.options = runState.options;
      this.progressReporter = new SweepWorkers.ProgressReporter(runState);
      this.workerIndex = workerIndex;
    }

    void run() {
      long end = Math.min(options.rangeEndExclusive(), totalCases());
      while (generated < end) {
        long caseIndex = generated++;
        if (caseIndex < options.rangeStartInclusive()) {
          reportProgressIfNeeded();
          continue;
        }
        if (caseIndex % options.threads() != workerIndex) {
          continue;
        }
        progressReporter.checked();
        evaluateCase(caseAt(caseIndex));
      }
    }

    void evaluateCase(CaseSpec spec) {
      ZeroWidthQuantifierDivergenceSweep.evaluateCase(runState, spec);
      reportProgressIfNeeded();
    }

    void finish() {
      runState.recordGenerated(generated);
    }

    void reportProgressIfNeeded() {
      progressReporter.reportIfNeeded(generated);
    }
  }

  private static void evaluateCase(SweepRunState runState, CaseSpec spec) {
    RegexSweep.Outcome jdk =
        RegexSweep.jdkTraceOutcome(
            spec.regex(), spec.flagMode().flags(), spec.inputs(), FIND_LIMIT);
    RegexSweep.Outcome safere =
        RegexSweep.safeReTraceOutcome(
            spec.regex(), spec.flagMode().flags(), spec.inputs(), FIND_LIMIT);
    if (RegexSweep.semanticallyEqual(jdk, safere)) {
      return;
    }
    String bucketName = bucketFor(spec, jdk, safere);
    runState.recordDivergence();
    runState.appendJsonl(new Divergence(spec, jdk, safere, bucketName).toJson());
  }
}
