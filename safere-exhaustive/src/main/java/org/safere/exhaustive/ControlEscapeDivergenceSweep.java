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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

/** Offline differential sweep for {@code \cX} control-escape target bugs. */
public final class ControlEscapeDivergenceSweep {
  private static final int TARGET_COUNT = Character.MAX_CODE_POINT + 1;

  private static final List<Context> CONTEXTS =
      List.of(
          context("bare", "\\c%s"),
          context("anchored", "^\\c%s$"),
          context("class", "[\\c%s]"),
          context("negatedClass", "[^\\c%s]"),
          context("prefixLiteral", "a\\c%s"),
          context("suffixLiteral", "\\c%sa"),
          context("captured", "(\\c%s)"),
          context("optional", "\\c%s?"));

  private static final List<FlagMode> FLAG_MODES =
      List.of(
          new FlagMode("none", "", 0),
          new FlagMode("comments", "(?x)", java.util.regex.Pattern.COMMENTS),
          new FlagMode("caseInsensitive", "(?i)", java.util.regex.Pattern.CASE_INSENSITIVE),
          new FlagMode(
              "commentsCaseInsensitive",
              "(?xi)",
              java.util.regex.Pattern.COMMENTS | java.util.regex.Pattern.CASE_INSENSITIVE));

  private ControlEscapeDivergenceSweep() {}

  public static void main(String[] args) throws IOException {
    SweepOptions options =
        SweepOptions.parse(
            args,
            Path.of("target", "exhaustive-reports", "control-escape-sweep"),
            "control-escape-divergences.jsonl",
            1_000_000);
    Files.createDirectories(options.outputDir());
    Files.deleteIfExists(options.jsonlPath());
    options.printStartup("control-escape");

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
          "control-escape-sweep-",
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
              "control-escape-replay-",
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
        SweepJson.integer(caseObject, "target"),
        new Context(
            SweepJson.string(caseObject, "contextLabel"),
            SweepJson.string(caseObject, "contextTemplate")),
        new FlagMode(
            SweepJson.string(caseObject, "flagLabel"),
            SweepJson.string(caseObject, "flagPrefix"),
            SweepJson.integer(caseObject, "flags")));
  }

  private static long totalCases() {
    return (long) TARGET_COUNT * CONTEXTS.size() * FLAG_MODES.size();
  }

  private static CaseSpec caseAt(long index) {
    int flagIndex = (int) (index % FLAG_MODES.size());
    index /= FLAG_MODES.size();
    int contextIndex = (int) (index % CONTEXTS.size());
    index /= CONTEXTS.size();
    int target = (int) index;
    return new CaseSpec(target, CONTEXTS.get(contextIndex), FLAG_MODES.get(flagIndex));
  }

  private static Outcome jdkOutcome(String regex, int flags, List<String> inputs) {
    try {
      return new Outcome(true, matches(java.util.regex.Pattern.compile(regex, flags), inputs), "");
    } catch (PatternSyntaxException e) {
      return new Outcome(false, "", e.getDescription());
    } catch (RuntimeException e) {
      return new Outcome(false, "", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static Outcome safeReOutcome(String regex, int flags, List<String> inputs) {
    try {
      return new Outcome(true, matches(org.safere.Pattern.compile(regex, flags), inputs), "");
    } catch (PatternSyntaxException e) {
      return new Outcome(false, "", e.getDescription());
    } catch (RuntimeException e) {
      return new Outcome(false, "", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static Outcome jdkOutcome(String regex) {
    return jdkOutcome(regex, 0, defaultReplayInputs());
  }

  private static Outcome safeReOutcome(String regex) {
    return safeReOutcome(regex, 0, defaultReplayInputs());
  }

  private static String matches(java.util.regex.Pattern pattern, List<String> inputs) {
    StringBuilder result = new StringBuilder();
    for (String input : inputs) {
      if (pattern.matcher(input).matches()) {
        appendInput(result, input);
      }
    }
    return result.toString();
  }

  private static String matches(org.safere.Pattern pattern, List<String> inputs) {
    StringBuilder result = new StringBuilder();
    for (String input : inputs) {
      if (pattern.matcher(input).matches()) {
        appendInput(result, input);
      }
    }
    return result.toString();
  }

  private static List<String> inputsFor(CaseSpec spec) {
    String target = stringForCodePoint(spec.target());
    String transformed = stringForCodePoint(spec.target() ^ 0x40);
    Set<String> inputs = new LinkedHashSet<>();
    inputs.add("");
    inputs.add(transformed);
    inputs.add(target);
    addIfValid(inputs, (spec.target() ^ 0x40) - 1);
    addIfValid(inputs, (spec.target() ^ 0x40) + 1);
    inputs.add("a");
    inputs.add("A");
    inputs.add("!");
    inputs.add("\u0001");
    inputs.add("\u001b");
    inputs.add("\u0140");
    inputs.add("\uf57f");
    inputs.add("\uD83D\uDE40");
    if (spec.context().needsPrefixA()) {
      inputs.add("a" + transformed);
      inputs.add("aa");
      inputs.add("a" + target);
    }
    if (spec.context().needsSuffixA()) {
      inputs.add(transformed + "a");
      inputs.add(target + "a");
    }
    return List.copyOf(inputs);
  }

  private static List<String> defaultReplayInputs() {
    return List.of("", "a", "A", "!", "\u0001", "\u001b", "\u0140", "\uf57f", "\uD83D\uDE40");
  }

  private static void addIfValid(Set<String> inputs, int codePoint) {
    if (Character.isValidCodePoint(codePoint)) {
      inputs.add(stringForCodePoint(codePoint));
    }
  }

  private static String stringForCodePoint(int codePoint) {
    if (codePoint <= Character.MAX_VALUE) {
      return Character.toString((char) codePoint);
    }
    return new String(Character.toChars(codePoint));
  }

  private static void appendInput(StringBuilder result, String input) {
    if (result.length() > 0) {
      result.append(',');
    }
    result.append(escape(input));
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
          if (Character.isISOControl(c)
              || Character.isSurrogate(c)
              || Character.getType(c) == Character.PRIVATE_USE) {
            result.append(String.format("\\u%04X", (int) c));
          } else {
            result.append(c);
          }
        }
      }
    }
    return result.toString();
  }

  private static String bucketFor(CaseSpec spec, Outcome jdk, Outcome safere) {
    String kind = jdk.accepted() != safere.accepted() ? "compile" : "membership";
    return String.join(
        "|",
        "kind=" + kind,
        "direction=" + direction(jdk, safere),
        "context=" + spec.context().label(),
        "flags=" + spec.flagMode().label(),
        "targetClass=" + targetClass(spec.target()));
  }

  private static String direction(Outcome jdk, Outcome safere) {
    if (jdk.accepted() && !safere.accepted()) {
      return "jdk-accepts";
    }
    if (!jdk.accepted() && safere.accepted()) {
      return "safere-accepts";
    }
    return "matches";
  }

  private static String targetClass(int target) {
    if (Character.isHighSurrogate((char) target)) {
      return "high-surrogate";
    }
    if (Character.isLowSurrogate((char) target)) {
      return "low-surrogate";
    }
    if (target < 0x20 || target == 0x7F) {
      return "ascii-control";
    }
    if (target < 0x80) {
      return "ascii-printable";
    }
    if (target <= Character.MAX_VALUE) {
      return "bmp";
    }
    return "supplementary";
  }

  private static boolean semanticallyEqual(Outcome left, Outcome right) {
    if (left.accepted() != right.accepted()) {
      return false;
    }
    return !left.accepted() || left.matches().equals(right.matches());
  }

  private static Context context(String label, String template) {
    return new Context(label, template);
  }

  private record Context(String label, String template) {
    String regex(String target) {
      return template.formatted(target);
    }

    boolean needsPrefixA() {
      return label.equals("prefixLiteral");
    }

    boolean needsSuffixA() {
      return label.equals("suffixLiteral");
    }
  }

  private record FlagMode(String label, String prefix, int flags) {}

  private record CaseSpec(int target, Context context, FlagMode flagMode) {
    String regex() {
      return flagMode.prefix() + context.regex(stringForCodePoint(target));
    }

    String labels() {
      return "target="
          + String.format("U+%04X", target)
          + ",context="
          + context.label()
          + ",flags="
          + flagMode.label()
          + ",targetClass="
          + targetClass(target);
    }
  }

  private record Outcome(boolean accepted, String matches, String error) {}

  private record Divergence(
      CaseSpec spec, String regex, Outcome jdk, Outcome safere, String bucket) {
    String toJson() {
      var object = SweepJson.object();
      object.add("case", caseJson(spec));
      object.addProperty("bucket", bucket);
      object.addProperty("labels", spec.labels());
      object.addProperty("regex", regex);
      object.addProperty("target", spec.target());
      object.addProperty("context", spec.context().label());
      object.addProperty("flags", spec.flagMode().label());
      object.addProperty("jdkAccepted", jdk.accepted());
      object.addProperty("safeReAccepted", safere.accepted());
      object.addProperty("jdkMatches", jdk.matches());
      object.addProperty("safeReMatches", safere.matches());
      object.addProperty("jdkError", jdk.error());
      object.addProperty("safeReError", safere.error());
      return SweepJson.toJson(object);
    }

    private static com.google.gson.JsonObject caseJson(CaseSpec spec) {
      var object = SweepJson.object();
      object.addProperty("target", spec.target());
      object.addProperty("contextLabel", spec.context().label());
      object.addProperty("contextTemplate", spec.context().template());
      object.addProperty("flagLabel", spec.flagMode().label());
      object.addProperty("flagPrefix", spec.flagMode().prefix());
      object.addProperty("flags", spec.flagMode().flags());
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
      ControlEscapeDivergenceSweep.evaluateCase(runState, spec);
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
    String regex = spec.regex();
    List<String> inputs = inputsFor(spec);
    Outcome jdk = jdkOutcome(regex, spec.flagMode().flags(), inputs);
    Outcome safere = safeReOutcome(regex, spec.flagMode().flags(), inputs);
    if (semanticallyEqual(jdk, safere)) {
      return;
    }
    String bucketName = bucketFor(spec, jdk, safere);
    runState.recordDivergence();
    runState.appendJsonl(new Divergence(spec, regex, jdk, safere, bucketName).toJson());
  }
}
