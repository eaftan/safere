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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Offline differential sweep for repeated quantifiers over zero-width operands. */
public final class ZeroWidthQuantifierDivergenceSweep {
  private static final int FIND_LIMIT = 16;
  private static final int FIRST_UNKNOWN_LIMIT = 100;
  private static final int ACTIONABLE_SAMPLE_LIMIT = 100;
  private static final int MAX_QUANTIFIER_CHAIN_LENGTH = 4;
  private static final long DEFAULT_PROGRESS_INTERVAL = 10_000_000;
  private static final java.util.regex.Pattern CAPTURE_FIELD =
      java.util.regex.Pattern.compile("g(\\d+)=(?:null|-?\\d+-\\d+:[^;}]*+)");
  private static final java.util.regex.Pattern FIND_TRACE_FIELD =
      java.util.regex.Pattern.compile("(?:^|,)[^,:]*+:find\\d+=(?:true@\\d+-\\d+|false)");
  private static final java.util.regex.Pattern NON_REPLACEMENT_TRACE_FIELD =
      java.util.regex.Pattern.compile("(?:^|,)([^,:]*+:(?:matches|lookingAt|find\\d++))=([^,]*+)");
  private static final java.util.regex.Pattern DECOMPOSED_E_ACUTE_TRACE_FIELD =
      java.util.regex.Pattern.compile("(?:^|,)e\u0301:[^,]*");

  private static final List<Atom> ZERO_WIDTH_ATOMS =
      List.of(
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

  private static final List<Quantifier> QUANTIFIER_TOKENS = buildQuantifierTokens();
  private static final List<QuantifierChain> QUANTIFIER_CHAINS = buildQuantifierChains();

  private static final List<Context> CONTEXTS =
      List.of(
          context("bare", "%s"),
          context("capturedWhole", "(%s)"),
          context("nonCapturedWhole", "(?:%s)"),
          context("scopedComments", "(?x:%s)"),
          context("prefixLiteral", "a%s"),
          context("suffixLiteral", "%sa"),
          context("mixedLeadingLiteralAlternative", "(?:%s|a)."),
          context("mixedLeadingLiteralAlternativeReversed", "(?:a|%s)."),
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

  private static final long CARTESIAN_CASES = cartesianCases();
  private static final List<CaseSpec> TARGETED_CASES = buildTargetedCases();

  private ZeroWidthQuantifierDivergenceSweep() {}

  public static void main(String[] args) throws IOException {
    ZeroWidthSweepOptions options = parseOptions(args);
    Files.createDirectories(options.sweep().outputDir());
    if (options.sweep().replayFile() != null) {
      Files.deleteIfExists(options.sweep().jsonlPath());
    }
    options.printStartup();

    if (options.sweep().replayFile() != null) {
      runReplay(options);
      return;
    }

    ZeroWidthRunResult result = runSweep(options);

    System.out.println("checked=" + result.checked());
    System.out.println("generated=" + result.generated());
    System.out.println("totalCases=" + totalCases());
    System.out.println("divergences=" + result.divergences());
    System.out.println("actionableDivergences=" + result.summary().actionableCount());
    System.out.println("unknownDivergences=" + result.summary().count(DivergenceClass.UNKNOWN));
    System.out.println("threads=" + options.sweep().threads());
  }

  private static ZeroWidthSweepOptions parseOptions(String[] args) {
    SweepOptions sweepOptions =
        SweepOptions.parse(
            args,
            Path.of("target", "exhaustive-reports", "zero-width-quantifier-sweep"),
            "zero-width-quantifier-divergences.jsonl",
            DEFAULT_PROGRESS_INTERVAL);
    return new ZeroWidthSweepOptions(sweepOptions);
  }

  private static ZeroWidthRunResult runSweep(ZeroWidthSweepOptions options) throws IOException {
    SweepOptions sweepOptions = options.sweep();
    long selectedCaseCount = sweepOptions.totalChecks(totalCases());
    List<ClassifiedDivergenceSummary<DivergenceClass>> workerSummaries = new ArrayList<>();
    for (int i = 0; i < sweepOptions.threads(); i++) {
      workerSummaries.add(null);
    }
    try (SweepRunState runState = new SweepRunState(sweepOptions, selectedCaseCount)) {
      runState.enableCompactLogs(
          "zero-width-quantifier",
          totalCases(),
          divergenceClassificationNames(),
          divergenceClassificationStatuses());
      SweepWorkers.run(
          sweepOptions.threads(),
          "zero-width-quantifier-sweep-",
          workerIndex -> {
            ClassifiedDivergenceSummary<DivergenceClass> workerSummary =
                newZeroWidthDivergenceSummary();
            SweepState worker = new SweepState(runState, workerSummary, workerIndex);
            worker.run();
            worker.finish();
            workerSummaries.set(workerIndex, workerSummary);
          });
      ClassifiedDivergenceSummary<DivergenceClass> summary = newZeroWidthDivergenceSummary();
      for (ClassifiedDivergenceSummary<DivergenceClass> workerSummary : workerSummaries) {
        summary.merge(workerSummary);
      }
      return new ZeroWidthRunResult(
          runState.checked.sum(), runState.generated, runState.divergences.sum(), summary);
    }
  }

  private static void runReplay(ZeroWidthSweepOptions options) throws IOException {
    SweepOptions sweepOptions = options.sweep();
    ClassifiedDivergenceSummary<DivergenceClass> summary = newZeroWidthDivergenceSummary();
    AtomicLong replayCaseIndex = new AtomicLong();
    try (BufferedReader reader =
            Files.newBufferedReader(sweepOptions.replayFile(), StandardCharsets.UTF_8);
        SweepRunState runState = new SweepRunState(sweepOptions, 0)) {
      long generated =
          SweepWorkers.runStreamingLines(
              sweepOptions.threads(),
              "zero-width-quantifier-replay-",
              reader,
              line -> {
                CaseSpec spec = replayCaseOrNull(line);
                if (spec == null) {
                  return;
                }
                runState.checked.increment();
                evaluateCase(runState, summary, spec, replayCaseIndex.getAndIncrement(), -1);
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
      long unknownCount = summary.count(DivergenceClass.UNKNOWN);
      if (summary.actionableCount() > 0 || unknownCount > 0) {
        throw new IllegalStateException(
            "replay found "
                + summary.actionableCount()
                + " actionable and "
                + unknownCount
                + " unknown behavioral divergences");
      }
    }
  }

  private static CaseSpec replayCaseOrNull(String line) {
    var object = SweepJson.parseObjectOrNull(line);
    if (object == null) {
      return null;
    }
    var caseObject = SweepJson.object(object, "case");
    return new CaseSpec(
        new Operand(
            SweepJson.string(caseObject, "operandLabel"),
            SweepJson.string(caseObject, "operandRegex")),
        new Wrapper(
            SweepJson.string(caseObject, "wrapperLabel"),
            SweepJson.string(caseObject, "wrapperTemplate")),
        replayQuantifierChain(caseObject),
        new Context(
            SweepJson.string(caseObject, "contextLabel"),
            SweepJson.string(caseObject, "contextTemplate")),
        new FlagMode(
            SweepJson.string(caseObject, "flagLabel"),
            SweepJson.string(caseObject, "flagPrefix"),
            SweepJson.integer(caseObject, "flags"),
            SweepJson.string(caseObject, "trivia")));
  }

  private static QuantifierChain replayQuantifierChain(com.google.gson.JsonObject caseObject) {
    if (caseObject.has("quantifierChainLabel")) {
      return new QuantifierChain(
          SweepJson.string(caseObject, "quantifierChainLabel"),
          SweepJson.string(caseObject, "quantifierChain"));
    }
    return new QuantifierChain(
        SweepJson.string(caseObject, "firstQuantifierLabel")
            + ","
            + SweepJson.string(caseObject, "suffixQuantifierLabel"),
        SweepJson.string(caseObject, "firstQuantifier")
            + SweepJson.string(caseObject, "suffixQuantifier"));
  }

  static long totalCases() {
    return CARTESIAN_CASES + TARGETED_CASES.size();
  }

  private static long cartesianCases() {
    return (long) OPERANDS.size()
        * WRAPPERS.size()
        * QUANTIFIER_CHAINS.size()
        * CONTEXTS.size()
        * FLAG_MODES.size();
  }

  private static CaseSpec caseAt(long index) {
    if (index >= CARTESIAN_CASES) {
      return TARGETED_CASES.get((int) (index - CARTESIAN_CASES));
    }
    int flagIndex = (int) (index % FLAG_MODES.size());
    index /= FLAG_MODES.size();
    int contextIndex = (int) (index % CONTEXTS.size());
    index /= CONTEXTS.size();
    int chainIndex = (int) (index % QUANTIFIER_CHAINS.size());
    index /= QUANTIFIER_CHAINS.size();
    int wrapperIndex = (int) (index % WRAPPERS.size());
    index /= WRAPPERS.size();
    int operandIndex = (int) index;
    return new CaseSpec(
        OPERANDS.get(operandIndex),
        WRAPPERS.get(wrapperIndex),
        QUANTIFIER_CHAINS.get(chainIndex),
        CONTEXTS.get(contextIndex),
        FLAG_MODES.get(flagIndex));
  }

  static boolean containsGeneratedCaseForTesting(String regex, String input) {
    for (CaseSpec spec : TARGETED_CASES) {
      if (spec.regex().equals(regex) && spec.inputs().contains(input)) {
        return true;
      }
    }
    return false;
  }

  static boolean containsCartesianCaseForTesting(
      String operandRegex,
      String wrapperTemplate,
      String quantifierChain,
      String contextTemplate,
      String input) {
    Operand operand = findOperandByRegex(operandRegex);
    Wrapper wrapper = findWrapperByTemplate(wrapperTemplate);
    QuantifierChain chain = findQuantifierChainByText(quantifierChain);
    Context context = findContextByTemplate(contextTemplate);
    FlagMode flags = new FlagMode("none", "", 0, "");
    if (operand == null || wrapper == null || chain == null || context == null) {
      return false;
    }
    return new CaseSpec(operand, wrapper, chain, context, flags).inputs().contains(input);
  }

  static boolean containsQuantifierChainForTesting(String quantifierChain) {
    return findQuantifierChainByText(quantifierChain) != null;
  }

  static long totalCasesForTesting() {
    return totalCases();
  }

  static String compactReplayJson(long caseIndex, String classification) {
    CaseSpec spec = caseAt(caseIndex);
    var object = SweepJson.object();
    object.addProperty("caseIndex", caseIndex);
    object.addProperty("classification", classification);
    object.add("case", Divergence.caseJson(spec));
    return SweepJson.toJson(object);
  }

  private static Operand findOperandByRegex(String regex) {
    for (Operand operand : OPERANDS) {
      if (operand.regex().equals(regex)) {
        return operand;
      }
    }
    return null;
  }

  private static Wrapper findWrapperByTemplate(String template) {
    for (Wrapper wrapper : WRAPPERS) {
      if (wrapper.template().equals(template)) {
        return wrapper;
      }
    }
    return null;
  }

  private static QuantifierChain findQuantifierChainByText(String text) {
    for (QuantifierChain chain : QUANTIFIER_CHAINS) {
      if (chain.text().equals(text)) {
        return chain;
      }
    }
    return null;
  }

  private static Context findContextByTemplate(String template) {
    for (Context context : CONTEXTS) {
      if (context.template().equals(template)) {
        return context;
      }
    }
    return null;
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
        "chain=" + spec.quantifierChain().label(),
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

  private static List<CaseSpec> buildTargetedCases() {
    return List.of(
        new CaseSpec(
            operand("targeted:capturedGraphemeBoundary", "\\b{g}"),
            wrapper("capturing", "(%s)"),
            quantifierChain(quantifier("star", "*"), quantifier("plus", "+")),
            context("capturedThenOptionalLiteral", "(%s)a?"),
            new FlagMode("none", "", 0, "")),
        new CaseSpec(
            operand("targeted:deepEmptyCapturing", nestedNonCapturingGroups("()", 12000)),
            wrapper("bare", "%s"),
            quantifierChain(quantifier("star", "*"), quantifier("plus", "+")),
            context("suffixLiteral", "%sa"),
            new FlagMode("none", "", 0, "")),
        new CaseSpec(
            operand(
                "targeted:deepRepeatedBoundaryAlternation",
                nestedAlternation("\\b{g}{2}", "a", 12000)),
            wrapper("bare", "%s"),
            quantifierChain(quantifier("repeatOne", "{1}"), quantifier("repeatOne", "{1}")),
            context("bare", "%s"),
            new FlagMode("none", "", 0, "")));
  }

  private static String nestedNonCapturingGroups(String inner, int depth) {
    StringBuilder regex = new StringBuilder(inner.length() + 4 * depth);
    regex.append("(?:".repeat(depth));
    regex.append(inner);
    regex.append(")".repeat(depth));
    return regex.toString();
  }

  private static String nestedAlternation(String initial, String otherBranch, int depth) {
    String regex = initial;
    for (int i = 0; i < depth; i++) {
      regex = "(?:" + regex + "|" + otherBranch + ")";
    }
    return regex;
  }

  private static List<Quantifier> buildQuantifierTokens() {
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

  private static List<QuantifierChain> buildQuantifierChains() {
    Map<String, QuantifierChain> chains = new LinkedHashMap<>();
    appendQuantifierChains(chains, new ArrayList<>(), 0);
    return List.copyOf(chains.values());
  }

  private static void appendQuantifierChains(
      Map<String, QuantifierChain> chains, List<Quantifier> current, int depth) {
    if (depth > 0) {
      QuantifierChain chain = quantifierChain(current);
      chains.putIfAbsent(chain.text(), chain);
    }
    if (depth == MAX_QUANTIFIER_CHAIN_LENGTH) {
      return;
    }
    for (Quantifier token : QUANTIFIER_TOKENS) {
      current.add(token);
      appendQuantifierChains(chains, current, depth + 1);
      current.removeLast();
    }
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

  private static QuantifierChain quantifierChain(Quantifier... quantifiers) {
    return quantifierChain(List.of(quantifiers));
  }

  private static QuantifierChain quantifierChain(List<Quantifier> quantifiers) {
    StringBuilder label = new StringBuilder();
    StringBuilder text = new StringBuilder();
    for (Quantifier quantifier : quantifiers) {
      if (!label.isEmpty()) {
        label.append(',');
      }
      label.append(quantifier.label());
      text.append(quantifier.text());
    }
    return new QuantifierChain(label.toString(), text.toString());
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

  private record QuantifierChain(String label, String text) {}

  private record Context(String label, String template) {
    String regex(String repeated) {
      return template.formatted(repeated);
    }
  }

  private record FlagMode(String label, String prefix, int flags, String trivia) {}

  private record ZeroWidthRunResult(
      long checked,
      long generated,
      long divergences,
      ClassifiedDivergenceSummary<DivergenceClass> summary) {}

  private record ZeroWidthSweepOptions(SweepOptions sweep) {
    void printStartup() {
      sweep.printStartup("zero-width-quantifier");
    }
  }

  private static ClassifiedDivergenceSummary<DivergenceClass> newZeroWidthDivergenceSummary() {
    return new ClassifiedDivergenceSummary<>(
        DivergenceClass.class,
        DivergenceClass.UNKNOWN,
        "zero-width-quantifier",
        FIRST_UNKNOWN_LIMIT,
        ACTIONABLE_SAMPLE_LIMIT);
  }

  static List<String> divergenceClassificationNames() {
    return Arrays.stream(DivergenceClass.values()).map(Enum::name).toList();
  }

  static List<DivergenceStatus> divergenceClassificationStatuses() {
    return Arrays.stream(DivergenceClass.values()).map(DivergenceClass::status).toList();
  }

  private enum DivergenceClass implements DivergenceClassification {
    STACK_OVERFLOW(
        DivergenceStatus.EXPECTED_ZERO,
        "Generated stack-safety sentinel cases must not throw StackOverflowError in SafeRE."),
    RELUCTANT_QUANTIFIER_MODIFIER(
        DivergenceStatus.EXPECTED_ZERO,
        "JDK reluctant quantifier modifiers over zero-width operands are part of the supported"
            + " linear-time syntax."),
    COMMENTS_MODE_GRAPHEME_QUANTIFIER_TRIVIA(
        DivergenceStatus.EXPECTED_ZERO,
        "Comments-mode trivia around zero-width grapheme quantifiers must not make a valid JDK"
            + " quantifier look argument-less."),
    INVALID_QUANTIFIER_CHAIN_ACCEPTED(
        DivergenceStatus.EXPECTED_ZERO,
        "SafeRE must reject nested or dangling quantifier chains that java.util.regex rejects."),
    ZERO_WIDTH_POSSESSIVE_QUANTIFIER_REJECTED(
        DivergenceStatus.EXPECTED_ZERO,
        "JDK-compatible possessive modifiers over zero-width operands are normalizable without"
            + " introducing consuming possessive semantics."),
    ZERO_WIDTH_POSSESSIVE_CAPTURE_RETENTION(
        DivergenceStatus.EXPECTED_ZERO,
        "JDK-compatible possessive modifiers over zero-width operands must preserve captures from"
            + " the observed zero-width iteration."),
    POSSESSIVE_QUANTIFIER_UNSUPPORTED(
        DivergenceStatus.KNOWN_INTENTIONAL,
        "SafeRE rejects possessive quantifiers over consuming operands to preserve the supported"
            + " linear-time dialect."),
    GRAPHEME_BOUNDARY_ALTERNATIVE_FIND_CURSOR(
        DivergenceStatus.KNOWN_INTENTIONAL,
        "Observed JDK repeated-find cursor behavior for alternatives involving a bare explicit"
            + " \\b{g} is not part of SafeRE's compositional grapheme model."),
    REPEATED_GRAPHEME_BOUNDARY_COMPOSITION(
        DivergenceStatus.KNOWN_INTENTIONAL,
        "Observed JDK traces for repeated explicit \\b{g} skip or reject compositions that"
            + " SafeRE's compositional grapheme model preserves."),
    GRAPHEME_BOUNDARY_ALTERNATIVE_GRAPHEME_MODEL(
        DivergenceStatus.KNOWN_INTENTIONAL,
        "Observed JDK traces for alternatives involving explicit \\b{g} expose grapheme"
            + " segmentation details that conflict with SafeRE's documented grapheme model."),
    ASCII_WORD_BOUNDARY_COMBINING_MARK(
        DivergenceStatus.KNOWN_INTENTIONAL,
        "SafeRE follows the documented default ASCII word-character model for \\b and \\B"
            + " without attaching combining marks to preceding base characters."),
    FAILED_PATH_CAPTURE_LEAKAGE(
        DivergenceStatus.KNOWN_INTENTIONAL,
        "Observed JDK traces can preserve captures from failed backtracking paths; SafeRE does"
            + " not preserve captures across failed NFA start/path attempts."),
    UNKNOWN(DivergenceStatus.UNKNOWN, "Unclassified SafeRE/JDK zero-width quantifier divergence.");

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

  private record CaseSpec(
      Operand operand,
      Wrapper wrapper,
      QuantifierChain quantifierChain,
      Context context,
      FlagMode flagMode) {
    String regex() {
      String quantified = wrapper.regex(operand.regex()) + quantifiedTriviaChain();
      return flagMode.prefix() + context.regex(quantified);
    }

    private String quantifiedTriviaChain() {
      if (flagMode.trivia().isEmpty()) {
        return quantifierChain.text();
      }
      StringBuilder result = new StringBuilder();
      for (int i = 0; i < quantifierChain.text().length(); i++) {
        char c = quantifierChain.text().charAt(i);
        if (c == '*' || c == '+' || c == '?' || c == '{') {
          result.append(flagMode.trivia());
        }
        result.append(c);
      }
      return result.toString();
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
      inputs.add("\uD83D\uDC69\u200D\uD83D\uDCBBx");
      if (operand.label().contains("graphemeBoundary")
          && !operand.label().contains("wordBoundary")
          && !operand.label().contains("nonWordBoundary")) {
        inputs.add("x\uD83D\uDC69\u200D\uD83D\uDCBBx");
      }
      return List.copyOf(inputs);
    }

    String labels() {
      return "operand="
          + operand.label()
          + ",wrapper="
          + wrapper.label()
          + ",chain="
          + quantifierChain.label()
          + ",context="
          + context.label()
          + ",flags="
          + flagMode.label();
    }
  }

  private record Divergence(
      long caseIndex,
      CaseSpec spec,
      RegexSweep.Outcome jdk,
      RegexSweep.Outcome safere,
      String bucket,
      DivergenceClass classification) {
    String toJson() {
      var object = SweepJson.object();
      object.addProperty("caseIndex", caseIndex);
      object.add("case", caseJson(spec));
      object.addProperty("bucket", bucket);
      object.addProperty("classification", classification.name());
      object.addProperty("classificationStatus", classification.status().name());
      object.addProperty("labels", spec.labels());
      object.addProperty("regex", spec.regex());
      object.addProperty("operand", spec.operand().label());
      object.addProperty("wrapper", spec.wrapper().label());
      object.addProperty("quantifierChain", spec.quantifierChain().label());
      object.addProperty("quantifierChainLength", spec.quantifierChain().label().split(",").length);
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
      object.addProperty("quantifierChainLabel", spec.quantifierChain().label());
      object.addProperty("quantifierChain", spec.quantifierChain().text());
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
    final ClassifiedDivergenceSummary<DivergenceClass> summary;
    final SweepOptions options;
    final SweepWorkers.ProgressReporter progressReporter;
    final int workerIndex;
    long generated;

    SweepState(
        SweepRunState runState,
        ClassifiedDivergenceSummary<DivergenceClass> summary,
        int workerIndex) {
      this.runState = runState;
      this.summary = summary;
      this.options = runState.options;
      this.progressReporter = new SweepWorkers.ProgressReporter(runState, workerIndex);
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
      return SweepWorkers.firstOwnedCaseIndex(
          options.rangeStartInclusive(), endExclusive(), options.threads(), workerIndex);
    }

    void evaluateCase(long caseIndex, CaseSpec spec) {
      ZeroWidthQuantifierDivergenceSweep.evaluateCase(
          runState, summary, spec, caseIndex, workerIndex);
      reportProgressIfNeeded();
    }

    void finish() {
      runState.recordGenerated(Math.min(generated, endExclusive()));
      runState.updateWorkerNextCaseIndex(workerIndex, Math.min(generated, endExclusive()));
    }

    void reportProgressIfNeeded() {
      progressReporter.reportIfNeeded(Math.min(generated, endExclusive()));
    }

    private long endExclusive() {
      return Math.min(options.rangeEndExclusive(), totalCases());
    }
  }

  private static void evaluateCase(
      SweepRunState runState,
      ClassifiedDivergenceSummary<DivergenceClass> summary,
      CaseSpec spec,
      long caseIndex,
      int workerIndex) {
    RegexSweep.Outcome jdk =
        RegexSweep.jdkTraceOutcome(
            spec.regex(), spec.flagMode().flags(), spec.inputs(), FIND_LIMIT);
    RegexSweep.Outcome safere =
        RegexSweep.safeReTraceOutcome(
            spec.regex(), spec.flagMode().flags(), spec.inputs(), FIND_LIMIT);
    if (isStackSafetySentinel(spec)) {
      if (safere.error().contains("StackOverflowError")) {
        String bucketName = bucketFor(spec, jdk, safere);
        recordDivergence(
            runState,
            summary,
            caseIndex,
            spec,
            jdk,
            safere,
            bucketName,
            DivergenceClass.STACK_OVERFLOW,
            workerIndex);
      }
      return;
    }
    if (RegexSweep.semanticallyEqual(jdk, safere)) {
      return;
    }
    String bucketName = bucketFor(spec, jdk, safere);
    DivergenceClass classification = classifyDivergence(spec, jdk, safere);
    recordDivergence(
        runState, summary, caseIndex, spec, jdk, safere, bucketName, classification, workerIndex);
  }

  private static void recordDivergence(
      SweepRunState runState,
      ClassifiedDivergenceSummary<DivergenceClass> summary,
      long caseIndex,
      CaseSpec spec,
      RegexSweep.Outcome jdk,
      RegexSweep.Outcome safere,
      String bucketName,
      DivergenceClass classification,
      int workerIndex) {
    if (workerIndex >= 0) {
      runState.recordCompactDivergence(workerIndex, caseIndex, classification.ordinal());
      summary.record(classification, caseIndex, null);
      return;
    }
    Divergence divergence =
        new Divergence(caseIndex, spec, jdk, safere, bucketName, classification);
    runState.recordDivergence();
    String json = divergence.toJson();
    summary.record(classification, caseIndex, json);
    if (classification.actionable()) {
      runState.appendJsonl(json);
    }
  }

  private static DivergenceClass classifyDivergence(
      CaseSpec spec, RegexSweep.Outcome jdk, RegexSweep.Outcome safere) {
    if (isReluctantQuantifierModifierDivergence(spec, jdk, safere)) {
      return DivergenceClass.RELUCTANT_QUANTIFIER_MODIFIER;
    }
    if (isCommentsModeGraphemeQuantifierTriviaDivergence(spec, jdk, safere)) {
      return DivergenceClass.COMMENTS_MODE_GRAPHEME_QUANTIFIER_TRIVIA;
    }
    if (isInvalidQuantifierChainAccepted(jdk, safere)) {
      return DivergenceClass.INVALID_QUANTIFIER_CHAIN_ACCEPTED;
    }
    if (isZeroWidthPossessiveQuantifierRejected(spec, jdk, safere)) {
      return DivergenceClass.ZERO_WIDTH_POSSESSIVE_QUANTIFIER_REJECTED;
    }
    if (isPossessiveQuantifierUnsupported(spec, jdk, safere)) {
      return DivergenceClass.POSSESSIVE_QUANTIFIER_UNSUPPORTED;
    }
    if (isZeroWidthPossessiveCaptureRetentionDivergence(spec, jdk, safere)) {
      return DivergenceClass.ZERO_WIDTH_POSSESSIVE_CAPTURE_RETENTION;
    }
    if (isKnownGraphemeBoundaryAlternativeFindCursor(spec, jdk, safere)) {
      return DivergenceClass.GRAPHEME_BOUNDARY_ALTERNATIVE_FIND_CURSOR;
    }
    if (isKnownRepeatedGraphemeBoundaryComposition(spec, jdk, safere)) {
      return DivergenceClass.REPEATED_GRAPHEME_BOUNDARY_COMPOSITION;
    }
    if (isKnownGraphemeBoundaryAlternativeGraphemeModel(spec, jdk, safere)) {
      return DivergenceClass.GRAPHEME_BOUNDARY_ALTERNATIVE_GRAPHEME_MODEL;
    }
    if (isKnownAsciiWordBoundaryCombiningMarkDivergence(spec, jdk, safere)) {
      return DivergenceClass.ASCII_WORD_BOUNDARY_COMBINING_MARK;
    }
    if (isKnownFailedPathCaptureLeakageDivergence(spec, jdk, safere)) {
      return DivergenceClass.FAILED_PATH_CAPTURE_LEAKAGE;
    }
    return DivergenceClass.UNKNOWN;
  }

  private static boolean isZeroWidthPossessiveQuantifierRejected(
      CaseSpec spec, RegexSweep.Outcome jdk, RegexSweep.Outcome safere) {
    return jdk.accepted()
        && !safere.accepted()
        && containsPossessiveQuantifierModifier(spec.quantifierChain().text());
  }

  private static boolean isPossessiveQuantifierUnsupported(
      CaseSpec spec, RegexSweep.Outcome jdk, RegexSweep.Outcome safere) {
    return jdk.accepted()
        && !safere.accepted()
        && containsPossessiveQuantifierModifier(spec.quantifierChain().text());
  }

  private static boolean isZeroWidthPossessiveCaptureRetentionDivergence(
      CaseSpec spec, RegexSweep.Outcome jdk, RegexSweep.Outcome safere) {
    return jdk.accepted()
        && safere.accepted()
        && containsPossessiveQuantifierModifier(spec.quantifierChain().text())
        && isZeroWidthPossessiveCaptureRetentionSpec(spec)
        && isDirectZeroWidthPossessiveCaptureRetentionContext(spec.context())
        && normalizeCaptureFields(jdk.trace()).equals(normalizeCaptureFields(safere.trace()));
  }

  private static boolean isZeroWidthPossessiveCaptureRetentionSpec(CaseSpec spec) {
    if (isCapturedConditionalZeroWidthAssertion(spec)) {
      return true;
    }
    return switch (spec.operand().label()) {
      case "atom:emptyCapturing",
          "atom:emptyNonCapturing",
          "alternate:emptyCapturing|emptyCapturing",
          "alternate:emptyCapturing|emptyNonCapturing",
          "alternate:emptyNonCapturing|emptyCapturing",
          "alternate:emptyNonCapturing|emptyNonCapturing" ->
          true;
      default -> false;
    };
  }

  private static boolean isCapturedConditionalZeroWidthAssertion(CaseSpec spec) {
    return (spec.wrapper().label().equals("capturing")
            || spec.wrapper().label().equals("nestedGroups"))
        && isConditionalZeroWidthOperand(spec.operand());
  }

  private static boolean isDirectZeroWidthPossessiveCaptureRetentionContext(Context context) {
    return switch (context.label()) {
      case "bare", "capturedWhole", "embeddedMultilineAnchored", "surroundingLiterals" -> true;
      default -> false;
    };
  }

  private static boolean containsPossessiveQuantifierModifier(String quantifierChain) {
    for (int i = 1; i < quantifierChain.length(); i++) {
      if (quantifierChain.charAt(i) == '+'
          && isQuantifierModifierPrefix(quantifierChain.charAt(i - 1))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isQuantifierModifierPrefix(char c) {
    return c == '*' || c == '+' || c == '?' || c == '}';
  }

  private static boolean isReluctantQuantifierModifierDivergence(
      CaseSpec spec, RegexSweep.Outcome jdk, RegexSweep.Outcome safere) {
    return jdk.accepted()
        && !safere.accepted()
        && containsReluctantQuantifierModifier(spec.quantifierChain().text());
  }

  private static boolean containsReluctantQuantifierModifier(String quantifierChain) {
    for (int i = 1; i < quantifierChain.length(); i++) {
      if (quantifierChain.charAt(i) == '?'
          && isQuantifierModifierPrefix(quantifierChain.charAt(i - 1))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isCommentsModeGraphemeQuantifierTriviaDivergence(
      CaseSpec spec, RegexSweep.Outcome jdk, RegexSweep.Outcome safere) {
    return jdk.accepted()
        && !safere.accepted()
        && spec.flagMode().label().contains("comments")
        && spec.operand().label().contains("graphemeBoundary")
        && safere.error().equals("missing argument to repetition operator");
  }

  private static boolean isInvalidQuantifierChainAccepted(
      RegexSweep.Outcome jdk, RegexSweep.Outcome safere) {
    return !jdk.accepted()
        && safere.accepted()
        && (jdk.error().startsWith("Dangling meta character")
            || jdk.error().equals("Illegal repetition"));
  }

  private static boolean isKnownGraphemeBoundaryAlternativeFindCursor(
      CaseSpec spec, RegexSweep.Outcome jdk, RegexSweep.Outcome safere) {
    return jdk.accepted()
        && safere.accepted()
        && spec.operand().label().equals("atom:graphemeBoundary")
        && spec.wrapper().label().equals("bare")
        && (spec.context().label().equals("mixedLeadingLiteralAlternative")
            || spec.context().label().equals("mixedLeadingLiteralAlternativeReversed"))
        && isFindOnlyTraceDifference(jdk.trace(), safere.trace());
  }

  static boolean isKnownGraphemeBoundaryAlternativeFindCursorForTesting(
      String jdkTrace, String safereTrace) {
    return isFindOnlyTraceDifference(jdkTrace, safereTrace);
  }

  private static boolean isKnownRepeatedGraphemeBoundaryComposition(
      CaseSpec spec, RegexSweep.Outcome jdk, RegexSweep.Outcome safere) {
    return jdk.accepted()
        && safere.accepted()
        && containsExplicitGraphemeBoundary(spec.operand())
        && isRepeatedGraphemeBoundaryCompositionTraceDifference(jdk.trace(), safere.trace());
  }

  private static boolean isKnownGraphemeBoundaryAlternativeGraphemeModel(
      CaseSpec spec, RegexSweep.Outcome jdk, RegexSweep.Outcome safere) {
    return jdk.accepted()
        && safere.accepted()
        && containsExplicitGraphemeBoundary(spec.operand())
        && isGraphemeSensitiveAlternativeTraceDifference(jdk.trace(), safere.trace());
  }

  private static boolean containsExplicitGraphemeBoundary(Operand operand) {
    return operand.label().contains("graphemeBoundary");
  }

  static boolean isRepeatedGraphemeBoundaryCompositionTraceDifferenceForTesting(
      String jdkTrace, String safereTrace) {
    return isRepeatedGraphemeBoundaryCompositionTraceDifference(jdkTrace, safereTrace);
  }

  private static boolean isRepeatedGraphemeBoundaryCompositionTraceDifference(
      String jdkTrace, String safereTrace) {
    if (jdkTrace.equals(safereTrace)) {
      return false;
    }
    return isRepeatedGraphemeBoundaryCompositionTraceDifference(
        nonReplacementTraceFields(normalizeCaptureFields(removeDecomposedEAcuteFields(jdkTrace))),
        nonReplacementTraceFields(
            normalizeCaptureFields(removeDecomposedEAcuteFields(safereTrace))));
  }

  private static boolean isGraphemeSensitiveAlternativeTraceDifference(
      String jdkTrace, String safereTrace) {
    if (jdkTrace.equals(safereTrace)) {
      return false;
    }
    Map<String, String> jdkOperations =
        nonReplacementTraceFields(normalizeCaptureFields(removeDecomposedEAcuteFields(jdkTrace)));
    Map<String, String> safeReOperations =
        nonReplacementTraceFields(
            normalizeCaptureFields(removeDecomposedEAcuteFields(safereTrace)));
    Map<String, String> nonSensitiveJdkOperations = new LinkedHashMap<>();
    Map<String, String> nonSensitiveSafeReOperations = new LinkedHashMap<>();
    boolean sawSensitiveDifference = false;
    for (Map.Entry<String, String> entry : jdkOperations.entrySet()) {
      String safeReValue = safeReOperations.get(entry.getKey());
      if (isGraphemeSensitiveTraceKey(entry.getKey())) {
        if (!entry.getValue().equals(safeReValue)) {
          sawSensitiveDifference = true;
        }
      } else {
        nonSensitiveJdkOperations.put(entry.getKey(), entry.getValue());
      }
    }
    for (Map.Entry<String, String> entry : safeReOperations.entrySet()) {
      if (isGraphemeSensitiveTraceKey(entry.getKey())) {
        if (!entry.getValue().equals(jdkOperations.get(entry.getKey()))) {
          sawSensitiveDifference = true;
        }
      } else {
        nonSensitiveSafeReOperations.put(entry.getKey(), entry.getValue());
      }
    }
    return sawSensitiveDifference
        && (nonSensitiveJdkOperations.equals(nonSensitiveSafeReOperations)
            || isRepeatedGraphemeBoundaryCompositionTraceDifference(
                nonSensitiveJdkOperations, nonSensitiveSafeReOperations));
  }

  private static boolean isGraphemeSensitiveTraceKey(String key) {
    int colon = key.lastIndexOf(':');
    String input = colon >= 0 ? key.substring(0, colon) : key;
    return input.contains("\\uD83D") || input.indexOf('\u200D') >= 0;
  }

  private static boolean isRepeatedGraphemeBoundaryCompositionTraceDifference(
      Map<String, String> jdkOperations, Map<String, String> safeReOperations) {
    boolean sawCompositionalExtra = false;
    for (Map.Entry<String, String> entry : jdkOperations.entrySet()) {
      if (isFindTraceKey(entry.getKey())) {
        continue;
      }
      String safeReValue = safeReOperations.get(entry.getKey());
      if (safeReValue == null) {
        return false;
      }
      if (entry.getValue().startsWith("true@")) {
        if (!entry.getValue().equals(safeReValue)) {
          return false;
        }
      } else if (safeReValue.startsWith("true@")) {
        sawCompositionalExtra = true;
      } else if (!entry.getValue().equals(safeReValue)) {
        return false;
      }
    }
    Map<String, Set<String>> jdkFindSuccesses = findSuccessesByInput(jdkOperations);
    Map<String, Set<String>> safeReFindSuccesses = findSuccessesByInput(safeReOperations);
    for (Map.Entry<String, Set<String>> entry : jdkFindSuccesses.entrySet()) {
      Set<String> safeReSuccesses = safeReFindSuccesses.get(entry.getKey());
      if (safeReSuccesses == null || !safeReSuccesses.containsAll(entry.getValue())) {
        return false;
      }
      if (safeReSuccesses.size() > entry.getValue().size()) {
        sawCompositionalExtra = true;
      }
    }
    for (Map.Entry<String, Set<String>> entry : safeReFindSuccesses.entrySet()) {
      if (!jdkFindSuccesses.containsKey(entry.getKey()) && !entry.getValue().isEmpty()) {
        sawCompositionalExtra = true;
      }
    }
    for (Map.Entry<String, String> entry : safeReOperations.entrySet()) {
      if (isFindTraceKey(entry.getKey())) {
        continue;
      }
      if (!jdkOperations.containsKey(entry.getKey())) {
        if (entry.getValue().startsWith("true@")) {
          sawCompositionalExtra = true;
        } else if (!entry.getKey().contains(":find")) {
          return false;
        }
      }
    }
    return sawCompositionalExtra;
  }

  private static boolean isFindTraceKey(String key) {
    int colon = key.lastIndexOf(':');
    return colon >= 0 && key.startsWith("find", colon + 1);
  }

  private static Map<String, Set<String>> findSuccessesByInput(Map<String, String> fields) {
    Map<String, Set<String>> successes = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : fields.entrySet()) {
      if (!isFindTraceKey(entry.getKey()) || !entry.getValue().startsWith("true@")) {
        continue;
      }
      int colon = entry.getKey().lastIndexOf(':');
      successes
          .computeIfAbsent(entry.getKey().substring(0, colon), ignored -> new LinkedHashSet<>())
          .add(entry.getValue());
    }
    return successes;
  }

  private static Map<String, String> nonReplacementTraceFields(String trace) {
    Map<String, String> fields = new LinkedHashMap<>();
    java.util.regex.Matcher matcher = NON_REPLACEMENT_TRACE_FIELD.matcher(trace);
    while (matcher.find()) {
      fields.put(matcher.group(1), matcher.group(2));
    }
    return fields;
  }

  private static boolean isFindOnlyTraceDifference(String jdkTrace, String safereTrace) {
    return !jdkTrace.equals(safereTrace)
        && FIND_TRACE_FIELD
            .matcher(jdkTrace)
            .replaceAll("")
            .equals(FIND_TRACE_FIELD.matcher(safereTrace).replaceAll(""));
  }

  private static boolean isKnownAsciiWordBoundaryCombiningMarkDivergence(
      CaseSpec spec, RegexSweep.Outcome jdk, RegexSweep.Outcome safere) {
    return jdk.accepted()
        && safere.accepted()
        && containsAsciiWordBoundary(spec.operand())
        && (isDecomposedEAcuteOnlyTraceDifference(jdk.trace(), safere.trace())
            || isDecomposedEAcuteOnlyTraceDifference(
                normalizeCaptureFields(jdk.trace()), normalizeCaptureFields(safere.trace())));
  }

  private static boolean containsAsciiWordBoundary(Operand operand) {
    return operand.label().contains("wordBoundary") || operand.label().contains("nonWordBoundary");
  }

  static boolean isDecomposedEAcuteOnlyTraceDifferenceForTesting(
      String jdkTrace, String safereTrace) {
    return isDecomposedEAcuteOnlyTraceDifference(jdkTrace, safereTrace);
  }

  private static boolean isDecomposedEAcuteOnlyTraceDifference(
      String jdkTrace, String safereTrace) {
    return !jdkTrace.equals(safereTrace)
        && removeDecomposedEAcuteFields(jdkTrace).equals(removeDecomposedEAcuteFields(safereTrace));
  }

  private static String removeDecomposedEAcuteFields(String trace) {
    return DECOMPOSED_E_ACUTE_TRACE_FIELD.matcher(trace).replaceAll("");
  }

  private static boolean isKnownFailedPathCaptureLeakageDivergence(
      CaseSpec spec, RegexSweep.Outcome jdk, RegexSweep.Outcome safere) {
    boolean knownShape =
        isKnownFailedPathCaptureLeakageOperand(spec)
            || isPossessiveZeroWidthFailedPathCaptureLeakage(spec);
    if (!knownShape) {
      return false;
    }
    if (jdk.accepted() != safere.accepted() || !jdk.error().equals(safere.error())) {
      return false;
    }
    if (!normalizeCaptureFields(jdk.trace()).equals(normalizeCaptureFields(safere.trace()))) {
      return false;
    }
    return true;
  }

  private static boolean isPossessiveZeroWidthFailedPathCaptureLeakage(CaseSpec spec) {
    return containsPossessiveQuantifierModifier(spec.quantifierChain().text())
        && isFailedPathCaptureLeakageContext(spec.context())
        && isEmptyCaptureLeakageShape(spec);
  }

  private static boolean isFailedPathCaptureLeakageContext(Context context) {
    return switch (context.label()) {
      case "mixedLeadingLiteralAlternative",
          "mixedLeadingLiteralAlternativeReversed",
          "suffixLiteral" ->
          true;
      default -> false;
    };
  }

  private static boolean isEmptyCaptureLeakageShape(CaseSpec spec) {
    return spec.wrapper().label().equals("capturing")
        || spec.wrapper().label().equals("nestedGroups")
        || spec.operand().label().contains("emptyCapturing");
  }

  private static boolean isConditionalZeroWidthOperand(Operand operand) {
    return switch (operand.label()) {
      case "atom:beginLine",
          "atom:endLine",
          "atom:beginText",
          "atom:endTextBeforeFinalTerminator",
          "atom:endText",
          "atom:wordBoundary",
          "atom:nonWordBoundary",
          "atom:graphemeBoundary" ->
          true;
      default -> false;
    };
  }

  private static boolean isKnownFailedPathCaptureLeakageOperand(CaseSpec spec) {
    Operand operand = spec.operand();
    return isConditionalZeroWidthOperand(operand)
        || isEmptyCaptureThenConditionalZeroWidthOperand(operand)
        || isConditionalZeroWidthThenEmptyCaptureOperand(operand)
        || isEmptyNonCaptureThenConditionalZeroWidthOperand(operand)
        || isConditionalZeroWidthThenEmptyNonCaptureOperand(operand)
        || isConcatOfConditionalZeroWidthOperands(operand)
        || operand.label().equals("concat:emptyCapturing+emptyCapturing")
        || operand.label().equals("concat:emptyCapturing+emptyNonCapturing")
        || operand.label().equals("concat:emptyNonCapturing+emptyCapturing")
        || operand.label().equals("concat:emptyNonCapturing+emptyNonCapturing")
        || isAlternativeOfConditionalZeroWidthOperands(operand)
        || isGraphemeBoundaryEmptyCaptureAlternative(operand)
        || isWrappedEmptyCaptureFailedPathLeakage(spec)
        || isWrappedEmptyNonCaptureFailedPathLeakage(spec)
        || isEmptyCaptureAlternativeFailedPathLeakage(spec)
        || isGreedyRepeatedEmptyCaptureAlternativeFailedPathLeakage(spec)
        || isGreedyEmptyCaptureAlternativeFailedPathLeakage(spec);
  }

  private static boolean isWrappedEmptyCaptureFailedPathLeakage(CaseSpec spec) {
    return spec.operand().label().equals("atom:emptyCapturing")
        && !spec.wrapper().label().equals("bare")
        && (spec.context().label().equals("suffixLiteral")
            || spec.context().label().equals("mixedLeadingLiteralAlternative")
            || spec.context().label().equals("mixedLeadingLiteralAlternativeReversed"));
  }

  private static boolean isWrappedEmptyNonCaptureFailedPathLeakage(CaseSpec spec) {
    return spec.operand().label().equals("atom:emptyNonCapturing")
        && spec.wrapper().label().equals("nestedGroups")
        && (spec.context().label().equals("suffixLiteral")
            || spec.context().label().equals("mixedLeadingLiteralAlternative")
            || spec.context().label().equals("mixedLeadingLiteralAlternativeReversed"));
  }

  private static boolean isEmptyCaptureAlternativeFailedPathLeakage(CaseSpec spec) {
    return (spec.context().label().equals("mixedLeadingLiteralAlternative")
            || spec.context().label().equals("mixedLeadingLiteralAlternativeReversed"))
        && spec.operand().label().equals("atom:emptyCapturing");
  }

  private static boolean isGreedyRepeatedEmptyCaptureAlternativeFailedPathLeakage(CaseSpec spec) {
    return spec.context().label().equals("mixedLeadingLiteralAlternative")
        && spec.wrapper().label().equals("capturing")
        && spec.operand().label().equals("atom:emptyCapturing")
        && spec.quantifierChain().label().startsWith("star");
  }

  private static boolean isGreedyEmptyCaptureAlternativeFailedPathLeakage(CaseSpec spec) {
    return spec.context().label().equals("mixedLeadingLiteralAlternative")
        && isAlternativeOperand(spec.operand())
        && ((spec.quantifierChain().label().startsWith("star")
                && !spec.quantifierChain().label().startsWith("star,question"))
            || spec.quantifierChain().label().startsWith("repeatRange"));
  }

  private static boolean isAlternativeOperand(Operand operand) {
    return operand.label().startsWith("alternate:");
  }

  private static boolean isAlternativeOfConditionalZeroWidthOperands(Operand operand) {
    String prefix = "alternate:";
    if (!operand.label().startsWith(prefix)) {
      return false;
    }
    String body = operand.label().substring(prefix.length());
    int separator = body.indexOf('|');
    if (separator < 0) {
      return false;
    }
    return isConditionalZeroWidthPart(body.substring(0, separator))
        && isConditionalZeroWidthPart(body.substring(separator + 1));
  }

  private static boolean isGraphemeBoundaryEmptyCaptureAlternative(Operand operand) {
    return operand.label().equals("alternate:graphemeBoundary|emptyCapturing")
        || operand.label().equals("alternate:emptyCapturing|graphemeBoundary");
  }

  private static boolean isEmptyCaptureThenConditionalZeroWidthOperand(Operand operand) {
    return switch (operand.label()) {
      case "concat:emptyCapturing+beginLine",
          "concat:emptyCapturing+endLine",
          "concat:emptyCapturing+beginText",
          "concat:emptyCapturing+endTextBeforeFinalTerminator",
          "concat:emptyCapturing+endText",
          "concat:emptyCapturing+wordBoundary",
          "concat:emptyCapturing+nonWordBoundary",
          "concat:emptyCapturing+graphemeBoundary" ->
          true;
      default -> false;
    };
  }

  private static boolean isConditionalZeroWidthThenEmptyCaptureOperand(Operand operand) {
    return switch (operand.label()) {
      case "concat:beginLine+emptyCapturing",
          "concat:endLine+emptyCapturing",
          "concat:beginText+emptyCapturing",
          "concat:endTextBeforeFinalTerminator+emptyCapturing",
          "concat:endText+emptyCapturing",
          "concat:wordBoundary+emptyCapturing",
          "concat:nonWordBoundary+emptyCapturing",
          "concat:graphemeBoundary+emptyCapturing" ->
          true;
      default -> false;
    };
  }

  private static boolean isConcatOfConditionalZeroWidthOperands(Operand operand) {
    String prefix = "concat:";
    if (!operand.label().startsWith(prefix)) {
      return false;
    }
    String body = operand.label().substring(prefix.length());
    int plus = body.indexOf('+');
    if (plus < 0) {
      return false;
    }
    return isConditionalZeroWidthPart(body.substring(0, plus))
        && isConditionalZeroWidthPart(body.substring(plus + 1));
  }

  private static boolean isConditionalZeroWidthPart(String label) {
    return switch (label) {
      case "beginLine",
          "endLine",
          "beginText",
          "endTextBeforeFinalTerminator",
          "endText",
          "wordBoundary",
          "nonWordBoundary",
          "graphemeBoundary" ->
          true;
      default -> false;
    };
  }

  private static boolean isConditionalZeroWidthThenEmptyNonCaptureOperand(Operand operand) {
    return switch (operand.label()) {
      case "concat:beginLine+emptyNonCapturing",
          "concat:endLine+emptyNonCapturing",
          "concat:beginText+emptyNonCapturing",
          "concat:endTextBeforeFinalTerminator+emptyNonCapturing",
          "concat:endText+emptyNonCapturing",
          "concat:wordBoundary+emptyNonCapturing",
          "concat:nonWordBoundary+emptyNonCapturing",
          "concat:graphemeBoundary+emptyNonCapturing" ->
          true;
      default -> false;
    };
  }

  private static boolean isEmptyNonCaptureThenConditionalZeroWidthOperand(Operand operand) {
    return switch (operand.label()) {
      case "concat:emptyNonCapturing+beginLine",
          "concat:emptyNonCapturing+endLine",
          "concat:emptyNonCapturing+beginText",
          "concat:emptyNonCapturing+endTextBeforeFinalTerminator",
          "concat:emptyNonCapturing+endText",
          "concat:emptyNonCapturing+wordBoundary",
          "concat:emptyNonCapturing+nonWordBoundary",
          "concat:emptyNonCapturing+graphemeBoundary" ->
          true;
      default -> false;
    };
  }

  private static String normalizeCaptureFields(String trace) {
    return CAPTURE_FIELD.matcher(trace).replaceAll("g$1=<capture>").replace("[null]", "[]");
  }

  private static boolean isStackSafetySentinel(CaseSpec spec) {
    return spec.operand().label().equals("targeted:deepEmptyCapturing")
        || spec.operand().label().equals("targeted:deepRepeatedBoundaryAlternation");
  }
}
